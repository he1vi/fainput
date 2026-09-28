/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import android.text.InputType
import android.util.Base64
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import org.fcitx.fcitx5.android.utils.appContext
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 决定一条输入是 L0/L1/L2/L3。
 *
 * 判定顺序（**顺序很重要**）：
 * 1. EditorInfo 层面 —— 系统已经告诉我们"这是密码框"时，绝不看内容
 * 2. 内容层面 —— 正则匹配弱空间内容
 *
 * 原则：**宁可过度保护**。把一句普通话误判成敏感，代价只是"分析少一条样本"；
 * 把密码误判成普通，代价是明文落库。
 */
object SensitiveClassifier {

    // ==================== 第一层：EditorInfo ====================

    /**
     * 这个输入框是不是明确敏感（密码/支付）？
     *
     * [editor] 为 null 时返回 true —— 拿不到 EditorInfo 说明是异常路径，
     * 按最保守处理。
     */
    fun isSensitiveEditor(editor: EditorInfo?): Boolean {
        if (editor == null) return true
        if (isPasswordInputType(editor.inputType)) return true

        // 系统在密码框上会设这个 flag（Android 8+），是比 inputType 更可靠的信号
        if (editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true

        // autofillHints 是 App 主动声明的"这个框是什么"
        editor.autofillHints?.forEach { hint ->
            val h = hint.lowercase()
            if (h.contains("password") ||
                h.contains("credit") ||
                h.contains("card") ||
                h.contains("cvc") ||
                h.contains("otp") ||
                h.contains("sms")
            ) {
                return true
            }
        }
        return false
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            else -> false
        }
    }

    // ==================== 第二层：内容 ====================

    private val DIGITS_ONLY = Regex("^\\d+$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")
    private val CN_MOBILE = Regex("^1[3-9]\\d{9}$")
    private val CN_ID_CARD = Regex("^\\d{17}[\\dXx]$")

    /**
     * 内容分级。只在 [isSensitiveEditor] 返回 false 时调用。
     */
    fun classify(text: String): InsightLevel {
        val t = text.trim()
        if (t.isEmpty()) return InsightLevel.META

        // 弱空间内容：哈希 ≈ 明文，但至少比明文好，且能用来做"同一串出现过几次"的去重
        if (DIGITS_ONLY.matches(t)) {
            // 4~8 位：验证码 / PIN / 短口令
            if (t.length in 4..8) return InsightLevel.HASHED
            // 13~19 位：银行卡 / 账号
            if (t.length in 13..19) return InsightLevel.HASHED
            // 其他长度的纯数字：日期、金额、编号 —— 也算敏感，保守处理
            if (t.length >= 9) return InsightLevel.HASHED
        }

        if (CN_MOBILE.matches(t)) return InsightLevel.HASHED
        if (CN_ID_CARD.matches(t)) return InsightLevel.HASHED
        if (EMAIL.matches(t)) return InsightLevel.HASHED

        return InsightLevel.PLAIN
    }

    // ==================== 每设备盐 + SHA-256 ====================

    private const val PREFS_NAME = "fainput_insight"
    private const val KEY_SALT = "hash_salt"

    @Volatile
    private var cachedSalt: ByteArray? = null

    /**
     * 取每设备盐。首次调用时生成 32 字节随机盐并写入应用私有 SharedPreferences。
     *
     * 存在应用私有目录而不是 Keystore，原因：
     * - 这里要的是"跨设备不可关联"，不是"抗物理取证"
     * - Keystore 的密钥不可导出，但也没法在导出数据库时带出去 ——
     *   而用户导出自己的数据时，**应该能保留"这两条是同一个串"这个信息**
     * - 应用私有目录已经被 Android 沙箱保护，且本 App 没有 INTERNET 权限
     *
     * 如果以后要抗物理取证，把这里换成 EncryptedSharedPreferences 即可。
     */
    private fun salt(): ByteArray {
        cachedSalt?.let { return it }
        synchronized(this) {
            cachedSalt?.let { return it }
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_SALT, null)
            val bytes = if (stored != null) {
                Base64.decode(stored, Base64.NO_WRAP)
            } else {
                ByteArray(32).also { SecureRandom().nextBytes(it) }.also {
                    prefs.edit(commit = true) {
                        putString(KEY_SALT, Base64.encodeToString(it, Base64.NO_WRAP))
                    }
                }
            }
            cachedSalt = bytes
            return bytes
        }
    }

    /**
     * `SHA-256(盐 || 原文)`，返回 64 位小写十六进制。
     *
     * 用途：让"同一串敏感内容出现过几次"可统计，但**无法从库里还原原文**。
     */
    fun hash(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt())
        md.update(text.trim().toByteArray(Charsets.UTF_8))
        val digest = md.digest()
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }
}