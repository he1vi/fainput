/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

/**
 * 【B 层 / 个人纠错重排】—— 你打错过的，下次直接算对。
 *
 * ## 这一层解决什么（用户原话）
 *
 * > 「**就算用户输入一堆错的，也要通过每个字符首字母和之后的字母
 * > 来预算出最接近的答案**」
 *
 * ## 为什么它和 libime 的纠错不是一回事
 *
 * libime 的 `PinyinCorrectionProfile` 是**规则式**的 —— 它内置了一张
 * **Qwerty 键盘邻键表**（`pinyincorrectionprofile.cpp` 里的
 * `case BuiltinPinyinCorrectionProfile::Qwerty`），只知道"你按错了旁边的键"。
 *
 * 它学不到这些：
 *
 * | 你的实际错法 | libime 能不能纠 |
 * |---|---|
 * | `shi` → `si`（前后鼻音不分） | ❌ 规则里没有 |
 * | `nh` → `你好`（首字母缩写） | ⚠️ 部分 |
 * | `zhongguo` 打成 `zongguo` | ✅ 有 |
 * | **你个人**老把某个词打成某个样 | ❌ **完全学不到** |
 *
 * ⇒ **这一层学的是"你自己的错法"**，数据来源就是你已经打了的那几百次输入。
 *
 * ## 两张表，两种强度
 *
 * | 信号 | 数据 | 权重 | 为什么 |
 * |---|---|---|---|
 * | **你亲口纠正过** | `x\|<码>\|<错>\|<对>` | **+3.0** | 这不是猜的 —— 是你在这个码下**主动把「错」改成了「对」** |
 * | **你用这个码打过它** | `k\|<词>` = 码 | **+1.5** | 同一个词换个码打，不该算数 |
 *
 * ## 纪律
 *
 * - **只抬不降**：从不为某个候选减分（减分 = 把引擎的排序往下压，危险）
 * - **精确匹配码**：不做前缀匹配 —— `de` 和 `deng` 完全是两回事
 * - **有界**：两个常量封顶，不存在"纠错过一次就永远第一"
 * - **零模型**：纯查表。所以它**立刻可用**，不用等训练
 */
object CorrectionScorer {

    /** 「你亲口纠正过」的加权。 */
    private const val W_CORRECTION = 3.0

    /** 「你用这个码打过它」的加权。 */
    private const val W_CODE_WORD = 1.5

    /**
     * 给**一个候选**打分。
     *
     * 纯查表：两次 HashMap 查找 + 一次 Set.contains —— 微秒级，
     * 可以放心放在打字路径上。
     *
     * @param code      当前预编辑（拼音串）。空串直接返回 0。
     * @param candidate 候选文字
     */
    fun score(code: String, candidate: String): Double {
        val c = code.trim().lowercase()
        if (c.isEmpty()) return 0.0
        val w = candidate.trim()
        if (w.isEmpty()) return 0.0

        val snap = PersonalDictionary.scoringSnapshot()
        if (snap.isEmpty) return 0.0

        // ① 最强信号：你在这个码下**亲口纠正过**它
        val fixes = snap.corrections[c]
        if (fixes != null && fixes.containsValue(w)) return W_CORRECTION

        // ② 次强：你用这个码打过它
        val words = snap.codeWords[c]
        if (words != null && words.contains(w)) return W_CODE_WORD

        return 0.0
    }

    /**
     * 这个码下你**纠正过的对词**（给 UI / 日志用）。
     *
     * 顺带解释了为什么某个候选会被提前 —— 出问题时能一眼看出来。
     */
    fun fixedWords(code: String): List<String> {
        val c = code.trim().lowercase()
        if (c.isEmpty()) return emptyList()
        return PersonalDictionary.scoringSnapshot().corrections[c]?.values?.toList() ?: emptyList()
    }

    /** 学了点什么（给「输入数据」页显示）。 */
    fun stats(): String {
        val snap = PersonalDictionary.scoringSnapshot()
        if (snap.isEmpty) return "暂无"
        val fixes = snap.corrections.values.sumOf { it.size }
        return "${snap.codeWords.size} 码 · $fixes 纠错"
    }
}