/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight

/**
 * 一条输入记录以什么形式落库。
 *
 * 这是整个数据层的核心约定：**不同敏感度走不同的存储路径**。
 * 分级在写入前完成，落库之后无法"降级"——所以宁可保守。
 *
 * | 级别 | 触发条件 | 存什么 |
 * |---|---|---|
 * | META | 空白内容 / 拿不到 EditorInfo | 只有长度、时间、按键数 |
 * | PLAIN | 普通文本 | 明文（用户决策：D3 明文，为了能做 SQL 全文分析） |
 * | HASHED | 验证码 / PIN / 银行卡 / 手机号 / 邮箱 / 身份证 | `SHA-256(每设备盐 + 原文)` + 长度，**不存原文** |
 * | COUNT_ONLY | 密码框 / `IME_FLAG_NO_PERSONALIZED_LEARNING` / autofillHints 命中 | **连哈希都不存**，只记一条计数 |
 *
 * 为什么 HASHED 不用 MD5：
 * MD5 是**哈希**不是加密（不可逆），且抗碰撞已破。更重要的是——
 * 弱空间内容（4~8 位数字）的哈希**等于明文**：攻击者枚举 10^6 次就能还原。
 * 所以这里用 SHA-256 + **每设备随机盐**，让彩虹表/跨设备关联失效。
 */
enum class InsightLevel(val code: Int) {
    /** L0：只有元数据，不含任何内容字符。 */
    META(0),

    /** L1：普通文本，明文存储。 */
    PLAIN(1),

    /** L2：疑似敏感，只存 SHA-256(盐 + 原文) 和长度。 */
    HASHED(2),

    /** L3：明确敏感，只记一条计数。 */
    COUNT_ONLY(3);

    companion object {
        private val Types = entries.toTypedArray()
        fun of(code: Int) = Types.getOrElse(code) { META }
    }
}
