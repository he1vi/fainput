/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

import org.fcitx.fcitx5.android.data.insight.db.InsightDao

/**
 * 【M·L1】词搭配模型：`上一个上屏的词` → 后续词加权。
 *
 * ## 和 D 阶段的区别
 *
 * | | D-1' | M·L1 |
 * |---|---|---|
 * | 学的是 | **这个词**你常用 | **这对词**你常用 |
 * | 例 | 「输入法」用得多 → 出现早 | 「输入」之后必跟「法」 |
 *
 * ## 为什么不必新建采集管道
 *
 * 数据本来就在：`input_event` 有 `timestamp + text + sessionId`，
 * **同一个 session 里相邻两次提交就是一对**。
 * 这里只是把它聚合进 `word_bigram`，查的时候走索引。
 * （同一个 session 才配对 —— 换输入框 / 隔了 5 分钟以上，上下文已经断了。）
 *
 * ## 纪律
 *
 * - **只统计 L1 明文**：调用方在 `level == PLAIN` 分支里才叫我们
 * - **只抬不降**：只往上加，从不把候选往下压
 * - **不是布尔闸门**：见 [bonus] 的注释
 */
object BigramModel {

    /** 一对词至少见过这么多次才算数（1 次多半是巧合）。 */
    private const val MIN_PAIR = 2

    /** 单词长度上限，和 `InsightRecorder.MAX_WORD_LENGTH` 保持一致。 */
    private const val MAX_WORD_LENGTH = 32

    /** 一次最多取多少个后续词。 */
    private const val LIMIT = 24

    /** 加权封顶 —— **绝不能压过词频轴**。 */
    private const val MAX_BONUS = 3.0

    /**
     * 当前「上一词」的后续词 → 加权。
     *
     * `@Volatile`：写在这里（落库协程），读在 `CandidateReranker.reorder()`
     * （主线程、候选列表事件里）—— 必须无锁、瞬时。
     */
    @Volatile
    private var boosts: Map<String, Double> = emptyMap()

    /** 同一个 session 内上一个上屏的词。 */
    @Volatile
    private var lastWord: String = ""

    private var sessionId: Long = -1L

    /** 当前记住了多少个后续词（给设置页显示用）。 */
    val size: Int
        get() = boosts.size

    /** 由重排器同步读取（主线程，只读一次引用）。 */
    val current: Map<String, Double>
        get() = boosts

    /** 上一个上屏的词（给设置页显示用）。 */
    val previousWord: String
        get() = lastWord

    /**
     * 每次 **L1 明文** 上屏时调用。
     *
     * 跑在 `InsightRecorder` 的落库协程里（已带写锁），
     * 所以这里可以放心做一次索引查询。
     */
    suspend fun onCommitted(dao: InsightDao, word: String, session: Long, now: Long) {
        val w = word.trim()
        if (w.isEmpty() || w.length > MAX_WORD_LENGTH) return

        // 换 session（新输入框 / 中间隔了 5 分钟以上）→ 上下文断了，不配对
        if (session != sessionId) {
            sessionId = session
            lastWord = ""
        }
        val a = lastWord
        lastWord = w

        if (a.isNotEmpty() && a != w) {
            runCatching { dao.bumpBigram(a, w, now) }
        }
        refresh(dao, w)
    }

    /** 「清空全部数据」时调用。 */
    fun clear() {
        lastWord = ""
        sessionId = -1L
        boosts = emptyMap()
    }

    private suspend fun refresh(dao: InsightDao, a: String) {
        val list = runCatching { dao.nextWords(a, LIMIT) }.getOrNull() ?: return
        boosts = list.asSequence()
            .filter { it.count >= MIN_PAIR }
            .associate { it.b to bonus(it.count) }
    }

    /**
     * **有界 + 渐变**，刻意不做成布尔闸门。
     *
     * 教训（`ARCHITECTURE.md` §16.1）：WindInput 早期用「用过就赢」的布尔闸门，
     * 结果打一个 `d`，只用过一次的「的样子」把权重 1.54e7 的「的」挤了下去。
     *
     * 这里：配对次数越多加得越多，但**封顶 [MAX_BONUS]** ——
     * 一个你打了 20 遍的词，照样赢过一对只见过 3 次的搭配。
     */
    private fun bonus(count: Int): Double =
        (count.coerceAtMost(6) * 0.5).coerceAtMost(MAX_BONUS)
}