/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.llm.LlmModel
import org.fcitx.fcitx5.android.data.llm.LlmNative

/**
 * 【fainput / F 阶段】翻译 —— **用我们自己的模型**，离线。
 *
 * ## ⚠️ 为什么不是文档里写的 ML Kit
 *
 * 路线图原本写的是「翻译（ML Kit 离线）」。动手前核了一下它的交付方式：
 *
 * - ML Kit 的翻译模型是**在 App 进程里下载**的（按语言对约 30 MB）
 * - 也就是要给它 `INTERNET` 权限
 * - 而这个项目的**第一条原则**是「数据不出设备」，CI 里还有个门禁
 *   （`aapt2 dump permissions` 见到 INTERNET 当场 fail）——**会直接打脸**
 *
 * 而且你这台机器上本来就有系统级离线翻译（`com.coloros.translate`）。
 *
 * ⇒ 所以这里的取舍是：**翻译走本地模型**。宁可质量一般（0.5B），
 * 也不要为了一个翻译功能在权限表上开个洞。
 *
 * ## 质量预期（先说实话）
 *
 * Qwen2.5-0.5B 翻**短句**能用，长句和专业内容会露怯。
 * 它的定位是"够用的随身翻译"，不是"替代翻译软件"。
 */
object Translator {

    enum class Lang(val label: String) {
        ZH("中文"),
        EN("English"),
        JA("日本語");

        fun next(): Lang = entries[(ordinal + 1) % entries.size]
    }

    /** 极简语种判断：有假名 → 日；有汉字 → 中；否则英。 */
    fun guess(text: String): Lang = when {
        text.any { it.code in 0x3040..0x30FF } -> Lang.JA
        text.any { it.code in 0x4E00..0x9FFF } -> Lang.ZH
        else -> Lang.EN
    }

    /**
     * 翻译。译文已经 trim 过；失败时给出**一句能看懂的**原因。
     *
     * 温度压到 0.2：翻译要的是稳定，不是灵气。
     */
    suspend fun translate(text: String, to: Lang): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (text.isBlank()) error("没有内容")
            if (!LlmNative.isAvailable) error("这个 APK 没带推理后端")
            if (LlmNative.describe().isEmpty()) {
                val file = LlmModel.current() ?: error("没有模型")
                LlmNative.loadModel(file.absolutePath)?.let { error(it) }
            }
            LlmNative.generateText(
                LlmNative.chatml(
                    "你是翻译。只输出译文，不要解释，不要加引号。",
                    "把下面的话翻译成${to.label}：\n$text"
                ),
                maxTokens = 256,
                temperature = 0.2f
            )?.trim() ?: error("生成失败")
        }
    }
}