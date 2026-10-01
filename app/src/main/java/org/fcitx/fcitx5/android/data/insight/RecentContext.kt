/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

/**
 * 【最近上文】—— 给 LSTM 喂足够的上下文。
 *
 * ## 为什么需要它（这是个真 bug）
 *
 * 之前 `CandidateReranker` 给 LSTM 的上下文是 [BigramModel.previousWord]，
 * 也就是**上一个上屏的词**（2~4 字）。但：
 *
 * - 训练时窗口是 `--seq 64`（64 字）
 * - 离线评测用 16 字上文，LSTM 同音 top-1 = **72.1%**
 * - 真机只有 1 个词 ⇒ 实测（`eval_ime.py --ctx` 扫描）：
 * ```
 *     16 字  72.1%
 *      8 字  73.5%
 *      4 字  69.3%
 *      2 字  67.5%   ← 真机在这个区间
 *      1 字  68.3%
 * ```
 * ⇒ **白白丢掉 4 个点。**
 *
 * ## 隐私（这是项目的第一原则）
 *
 * - **纯内存**，不落盘、不写 DB、不进 SharedPreferences
 * - 只在**非敏感**提交时追加（`InsightLevel.PLAIN`）——
 *   敏感内容走 HASHED / COUNT_ONLY，**永远不进这个缓冲**
 * - 只保留**最近 [MAX_CHARS] 个字**，超出的自动滚掉
 * - 会话超时（[InsightRecorder.SESSION_GAP_MS]）时清空
 *
 * ## 线程
 *
 * `append()` / `clear()` 在 `InsightRecorder` 的落库协程里；
 * [text] 在**主线程**（候选列表事件）里读。
 * 所以用 `@Volatile` 暴露一个**不可变快照**，不做原地修改。
 */
object RecentContext {

    /**
     * 保留多少个字。
     *
     * 16 是实测拐点：8~16 字最好，4 字以下开始掉分（见上面的表）。
     * 取 16 而不是 8 —— 长一点对模型无害（训练窗口 64 字），
     * 而短一点是真的掉分。
     */
    private const val MAX_CHARS = 16

    /** 不可变快照，供主线程无锁读。 */
    @Volatile
    private var snapshot: String = ""

    /** 只在落库协程里碰它。 */
    private val builder = StringBuilder()

    /** 当前可用的上文（可能为空）。 */
    val text: String
        get() = snapshot

    /**
     * 追加一次提交的文本（**只在非敏感时调用**）。
     *
     * 不在这里判敏感 —— 判据在 `InsightRecorder`（它已经算好了 [InsightLevel]），
     * 这里只负责滚动窗口，职责单一。
     */
    fun append(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        builder.append(t)
        // 超出就从头砍掉，保留尾部 MAX_CHARS 个字
        if (builder.length > MAX_CHARS) {
            builder.delete(0, builder.length - MAX_CHARS)
        }
        snapshot = builder.toString()
    }

    /** 会话超时 / 清空数据时调用。 */
    fun clear() {
        builder.setLength(0)
        snapshot = ""
    }
}