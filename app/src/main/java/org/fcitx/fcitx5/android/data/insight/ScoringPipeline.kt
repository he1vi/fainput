/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

/**
 * 【ABCD】三层打分的**统一入口** —— A 词级 · B 句级 · C 融合。
 *
 * ## 三层各管什么（这就是"独立个体"）
 *
 * | 层 | 看的东西 | 数据来源 | 冷启动时 |
 * |---|---|---|---|
 * | **A** 词级 | **这一个词**你打过多少次、上一次之后常不常跟它 | `word_stat` · `word_bigram` | ✅ 参与 |
 * | **B** 句级 | **这串文本整体**像不像你的话（词表覆盖度 + 首词接得上） | 同上，但换一种用法 | ⛔ `wSeq==0` 时**整层跳过** |
 * | **C** 融合 | 把上面两层按 [UserProfile] 的权重要成一个分 | —— | ✅ 参与 |
 *
 * ## 为什么 B 层要单独存在（它到底解决了什么）
 *
 * A 层只会说「**你打过这个词**」。
 * 可是当你一次打了一整句拼音，候选里躺的是「今天天气不错」这种**长串**——
 * 它作为一个"词"从来没被提交过，A 层永远给 0 分。
 *
 * B 层换了个问法：「这句话里，**有多少字落在我认识的词里**？」
 * 认识得越多，越可能是你想说的。再加上「句首能不能接上你刚打的词」。
 *
 * ⇒ 这就是**不需要神经模型的 L2 骨架**。
 *    将来换成小 LM，只需把这个对象换掉 —— 上层（[CandidateReranker]）一行都不用改。
 *
 * ## 纪律（照抄 A 层的三条安全边界）
 *
 * - **只抬不降**：所有分都 `>= 0`，绝不会把某个候选压到引擎序之后
 * - **有界**：每个分量都有上限，不存在"打过一次就永远第一"
 * - **可归零**：`wSeq == 0` 时 B 层**连算都不算**（冷启动的默认状态）
 */
object ScoringPipeline {

    /**
     * 一个候选的**分层得分**。
     *
     * 保留各层原始分（而不只是 [fused]）是为了**可解释** ——
     * 出问题时能一眼看出"是词频抬的、还是个人纠错抬的、还是模型抬的"。
     */
    data class Score(
        /** **A 层**·词频（含时间衰减 + 热词加权）。 */
        val freq: Double,
        /** **A 层**·词搭配（上一个上屏的词 → 这个词）。 */
        val pair: Double,
        /** **A 层**·整句统计（词表覆盖度 + 首词搭配）。 */
        val seq: Double,
        /** **B 层**·个人纠错（你亲口纠正过 / 你用这个码打过它）。 */
        val fix: Double,
        /** **C 层**·神经打分（LSTM）。**模型没装时恒为 0。** */
        val lm: Double,
        /** 融合后的最终分 —— 排序就看它。 */
        val fused: Double,
    ) {
        /** 所有层都是 0 —— 这个词我们一无所知，保持引擎原序。 */
        val isZero: Boolean
            get() = freq <= 0.0 && pair <= 0.0 && seq <= 0.0 && fix <= 0.0 && lm <= 0.0

        /** 给日志用的一行：`A(freq|pair|seq) B(fix) C(lm) → fused`。 */
        fun brief(): String =
            "(%.2f|%.2f|%.2f) + %.2f + %.2f → %.2f".format(freq, pair, seq, fix, lm, fused)

        companion object {
            val Zero = Score(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** B 层最多看这么长的文本 —— 再长也不是"候选"而是"段落"了。 */
    private const val SEQ_MAX_LEN = 12

    /** B 层只对**至少这么长**的候选算 —— 两个字以内的词 A 层已经管了。 */
    private const val SEQ_MIN_LEN = 3

    /** 贪婪切词时的最长词长。 */
    private const val MAX_WORD = 12

    /**
     * 给**一个候选**打三层分。
     *
     * 纯函数：不碰 IO、不碰全局状态（[profile] 由调用方传进来）。
     * ⇒ 可以放心在主线程调，也可以直接单测。
     *
     * @param text    候选文字（会被 trim）
     * @param vocab   A 层词表：词 → 词频分（`CandidateReranker` 维护）
     * @param bigram  A 层搭配表：后继词 → 加权（[BigramModel] 维护）
     * @param profile 当前画像（决定各层权重；`wSeq==0` 则 B 层不参与）
     */
    fun score(
        text: String,
        code: String,
        vocab: Map<String, Double>,
        bigram: Map<String, Double>,
        profile: UserProfile.Snapshot,
    ): Score {
        val t = text.trim()
        if (t.isEmpty()) return Score.Zero

        // ── A 层：统计 ──
        // 三条轴相加。各自表里都已经封过顶，所以这里不会再叠爆。
        val freq = vocab[t] ?: 0.0
        val pair = bigram[t] ?: 0.0
        // wSeq == 0（冷启动）⇒ **整句统计整块跳过**，连 coverage 都不算。
        // 这既是性能考虑，也是行为考虑：没数据时不该瞎猜整句。
        val seq = if (profile.wSeq > 0.0 && t.length >= SEQ_MIN_LEN) {
            sequenceScore(t, vocab, bigram)
        } else {
            0.0
        }

        // ── B 层：个人纠错 ──
        // **零模型成本**，纯查表（两次 HashMap + 一次 Set.contains）。
        // 冷启动时表是空的 ⇒ 自然返回 0 ⇒ 不参与。
        val fix = if (profile.wFix > 0.0) CorrectionScorer.score(code, t) else 0.0

        // ── C 层：神经打分（LSTM）──
        // `wLm == 0`（冷启动 / 没装模型）⇒ **连调用都不调用**。
        // 模型在 native 侧，跨 JNI 有固定开销，不该白跑。
        val lm = if (profile.wLm > 0.0) LstmScorer.score(t) else 0.0

        // ── 融合 ──
        // 每层都被自己的权重和内部封顶约束住，所以这里可以直接相加。
        val fused = freq * profile.wFreq + pair * profile.wPair + seq * profile.wSeq +
            fix * profile.wFix + lm * profile.wLm
        return Score(freq, pair, seq, fix, lm, fused)
    }

    /**
     * B 层本体：**整句连贯度**。
     *
     * 两个分量相加：
     *
     * 1. **首词接得上**：候选**以**上一个词的后继词开头 → 加那一段的分 × 0.5。
     *    为什么只给 0.5：整词命中时 A 层 `pair` 已经加过满额了，
     *    这里加的是"整句的**头**正好接得上"这个额外信号，不该重复计满。
     *
     * 2. **词表覆盖度**：这句话里有多少字落在我认识的词里（0~1）。
     *    这是 B 层真正的价值 —— 它让"从没被整句提交过、但由熟词拼成的句子"也能浮上来。
     */
    fun sequenceScore(
        text: String,
        vocab: Map<String, Double>,
        bigram: Map<String, Double>,
    ): Double {
        if (text.length < SEQ_MIN_LEN) return 0.0
        val t = if (text.length > SEQ_MAX_LEN) text.substring(0, SEQ_MAX_LEN) else text

        var s = 0.0

        // ① 首词搭配
        if (bigram.isNotEmpty()) {
            val maxHead = minOf(t.length, MAX_WORD)
            for (len in maxHead downTo 2) {
                val hit = bigram[t.substring(0, len)] ?: continue
                s += hit * 0.5
                break
            }
        }

        // ② 词表覆盖度（0~1）
        s += coverage(t, vocab)

        return s
    }

    /**
     * 贪婪最大匹配：用 [vocab] 里的词把 [text] 切一遍，
     * 返回**被词覆盖的字符比例**（0.0 ~ 1.0）。
     *
     * 例：词表里有「今天」「天气」，文本「今天天气不错」→ 覆盖 4/6 ≈ 0.67。
     *
     * 为什么用贪婪而不是 DP：候选最长 12 字、词表 ≤ 500 条，
     * 贪婪的误差可以忽略，但代码量和执行时间都小一个数量级。
     * **打字路径上的东西，简单 > 精确。**
     */
    private fun coverage(text: String, vocab: Map<String, Double>): Double {
        if (vocab.isEmpty() || text.isEmpty()) return 0.0
        var i = 0
        var covered = 0
        while (i < text.length) {
            val maxLen = minOf(text.length - i, MAX_WORD)
            var matched = 0
            for (len in maxLen downTo 2) {
                if (vocab.containsKey(text.substring(i, i + len))) {
                    matched = len
                    break
                }
            }
            if (matched > 0) {
                covered += matched
                i += matched
            } else {
                i += 1
            }
        }
        return covered.toDouble() / text.length
    }
}