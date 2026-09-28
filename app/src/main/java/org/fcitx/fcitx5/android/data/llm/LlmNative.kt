/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.llm

/**
 * 【fainput / L3】llama.cpp 的 Kotlin 侧 —— **只在后台整理时用**。
 *
 * ## 位置
 *
 * 打字路径上**永远不碰它**。打字用的是 D-1' 位置提升 + L1 用户 bigram。
 * 这一层只在**充电 / 熄屏**时被叫起来（`InsightMaintenance` → LLM 叙述）。
 *
 * ## 降级是设计，不是意外
 *
 * `native-llm` 这个 .so 在**没有 llama.cpp 源码时也能编出来**（CMake 会编译成桩），
 * 所以这里的每一个调用都可能拿到"不可用"。**全部走 `runCatching`**，
 * 任何一步失败都只是功能降级，绝不会把输入法带崩。
 *
 * ## 为什么 JNI 名这么长
 *
 * JNI 按「包名 + 类名 + 方法名」绑定，所以这个类**不能改名、不能挪包**
 * （改了就得同步改 `llm-jni.cpp` 里的函数名）。
 */
object LlmNative {

    /** 库有没有加载成功。**失败不是异常，是降级。** */
    private val linked: Boolean = runCatching { System.loadLibrary("native-llm") }.isSuccess

    // ---- 下面是 JNI 原生函数（名字必须和 llm-jni.cpp 对齐）----

    /** APK 里到底有没有把 llama.cpp 编进来。 */
    private external fun available(): Boolean

    /** llama.cpp 自己打的 CPU / 后端能力串。 */
    private external fun systemInfo(): String

    /** 加载模型。返回 **null = 成功**，非 null 是错误描述。 */
    private external fun load(path: String, vocabOnly: Boolean): String?

    private external fun modelDesc(): String
    private external fun modelSize(): Long
    private external fun modelParams(): Long
    private external fun unload()

    // ---- 下面是安全包装（永不抛、永不崩）----

    /** APK 里有没有把 llama.cpp 真的编进来。 */
    val isAvailable: Boolean
        get() = linked && runCatching { available() }.getOrDefault(false)

    /**
     * CPU / 后端能力串 —— 用来确认它**真的跑起来了**，
     * 而不是只编进去一个空壳。
     */
    fun systemInfoText(): String = when {
        !linked -> "(native-llm 没加载起来)"
        else -> runCatching { systemInfo() }.getOrDefault("(调用失败)")
    }

    /** 加载模型。返回 null = 成功。 */
    fun loadModel(path: String, vocabOnly: Boolean = false): String? = when {
        !linked -> "native-llm 没加载起来"
        else -> runCatching { load(path, vocabOnly) }
            .getOrElse { it.message ?: "加载时抛异常" }
    }

    /** 模型描述（架构/参数量档位），没加载时是空串。 */
    fun describe(): String =
        if (!linked) "" else runCatching { modelDesc() }.getOrDefault("")

    /** 权重字节数。 */
    fun sizeBytes(): Long =
        if (!linked) 0L else runCatching { modelSize() }.getOrDefault(0L)

    /** 参数量。 */
    fun paramCount(): Long =
        if (!linked) 0L else runCatching { modelParams() }.getOrDefault(0L)

    fun release() {
        if (linked) runCatching { unload() }
    }

    /** 给日志用的一句话状态。 */
    fun statusLine(): String =
        if (!isAvailable) "LLM 后端：未编入"
        else "LLM 后端：llama.cpp 就绪（${systemInfoText().take(80)}）"
}