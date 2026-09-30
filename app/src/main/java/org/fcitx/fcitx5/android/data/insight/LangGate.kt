/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

/**
 * 【语言隔离】—— 把「非中文」挡在中文学习层之外。
 *
 * ## 为什么需要这个文件
 *
 * 2026-09-30 用户报的 bug：
 *
 * > 「**输入法的模型词库没对英文进行隔离**，正常打字候选框会出现拼音」
 *
 * 查完发现：学习层里**三道闸只有一道挡了英文**。
 *
 * | 位置 | 原来的判据 | 英文能进吗 |
 * |---|---|---|
 * | [PersonalDictionary.normalizeWord] | 全汉字（`0x4E00..0x9FFF`） | ❌ 不能 ✓ |
 * | [InsightRecorder.bumpWord] | 只看长度 | ✅ **能进** ✗ |
 * | [CandidateReranker.maybeRefresh] | 只看长度 | ✅ **能进** ✗ |
 *
 * 于是：你打过的英文词进了 `word_stat`，又被搬进 `promote` 词表，
 * 然后**无条件**地给候选加分（A 层的 `freq` 不看当前输入什么码）。
 *
 * ## 为什么判据要放在一个文件里
 *
 * 上面那张表就是教训：**同一条规则写在三个地方，就会漂移**。
 * 所以这里只留一份实现，谁要用谁调。
 *
 * ## 为什么是「至少一个汉字」而不是「全是汉字」
 *
 * - 纯英文 `hello` / `nihao` → **拒**（这是用户报的那个 bug）
 * - 中英混合 `iPhone手机` → **收**（它确实是中文输入场景里的词）
 *
 * 注意 [PersonalDictionary] 用的是**更严**的「全是汉字」——
 * 那是对的：B 层写进的是引擎的**绝对优先级轴**（`customphrase`），
 * 影响引擎行为，所以门槛更高。两者不是不一致，是**职责不同**。
 */
object LangGate {

    /** CJK 统一表意文字（含扩展 A）。够覆盖日常中日汉字。 */
    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

    /** 这段文字里**至少有一个汉字**吗。 */
    fun hasCjk(s: String): Boolean = s.any { isCjk(it) }

    /**
     * 能不能进中文学习层。
     *
     * @param min 最短（默认 2 —— 长度 1 是直通提交的噪音）
     * @param max 最长（默认 12 —— 再长是句子不是词）
     */
    fun isChineseWord(s: String, min: Int = 2, max: Int = 12): Boolean {
        val w = s.trim()
        if (w.length < min || w.length > max) return false
        return hasCjk(w)
    }

    /**
     * 这个引擎是不是中文的。
     *
     * 判据抄上游 `CommonKeyActionListener`（空格键行为就是按这个分的）：
     * ```kotlin
     * if (inputMethodEntryCached.languageCode.startsWith("zh")) { ... }
     * ```
     *
     * ⚠️ `null` / 空 ⇒ **放行**（宁可多参与，不可因为拿不到字段就整层失效）。
     */
    fun isChineseLang(languageCode: String?): Boolean {
        val l = languageCode?.trim()?.lowercase() ?: return true
        if (l.isEmpty()) return true
        return l.startsWith("zh")
    }
}