/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

import timber.log.Timber

/**
 * 【C 层】LSTM 打分的 Kotlin 侧 —— **只在 native 干活**。
 *
 * ## 降级是设计，不是意外
 *
 * `native-lib.so` 里那个 LSTM 推理**永远编得出来**（不依赖任何外部源码），
 * 但**模型文件**是用户自己导入的 —— 没有模型是常态。
 *
 * ⇒ 这里每一个调用都可能拿到"不可用"，**全部走 `runCatching`**，
 *    任何一步失败都只是"C 层不参与"，绝不会把输入法带崩。
 *
 * ## 为什么 JNI 名这么长
 *
 * JNI 按「包名 + 类名 + 方法名」绑定 ⇒ **这个类不能改名、不能挪包**
 * （改了就得同步改 `cpp/lstm/lstm-jni.cpp` 里的函数名）。
 */
object LstmNative {

    /**
     * 库有没有加载成功。**失败不是异常，是降级。**
     *
     * 用的是主 `native-lib` —— LSTM 推理挂在那个目标上，
     * 所以这里通常已经加载过了；`loadLibrary` 重复调用是幂等的。
     */
    private val linked: Boolean = runCatching { System.loadLibrary("native-lib") }.isSuccess

    // ---- JNI 原生函数（名字必须和 cpp/lstm/lstm-jni.cpp 对齐）----

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeUnload()
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeVocabSize(): Int
    private external fun nativeParamCount(): Long
    private external fun nativeDims(): String

    /**
     * 给一个候选打分：`log P(候选的每个字 | 上文 + 候选前面的字)` 的**平均**。
     *
     * ⚠️ **它在主线程上跑**（`CandidateReranker.reorder()` 直接调）。
     *    兜住卡顿的不是线程，而是 [LstmScorer] 的**预算守卫** ——
     *    每次调用前先看本轮用掉多少纳秒，超了就返回 0。
     *
     * 真机实测单个候选约 **0.25 ms**（20 候选 × 4 字合计 4.89 ms）。
     */
    private external fun nativeScore(context: String, candidate: String): Float

    // ---- 安全包装（永不抛、永不崩）----

    /** 后端在不在（只说明库编进来了，不代表有模型）。 */
    val isAvailable: Boolean
        get() = linked && runCatching { nativeIsLoaded() }.getOrDefault(false)

    fun load(path: String): Boolean = when {
        !linked -> false
        else -> runCatching { nativeLoad(path) }
            .onFailure { Timber.w(it, "[lstm] 加载抛异常") }
            .getOrDefault(false)
    }

    fun unload() {
        if (linked) runCatching { nativeUnload() }
    }

    val vocabSize: Int
        get() = if (!linked) 0 else runCatching { nativeVocabSize() }.getOrDefault(0)

    val paramCount: Long
        get() = if (!linked) 0L else runCatching { nativeParamCount() }.getOrDefault(0L)

    fun dimsText(): String =
        if (!linked) "(native-lib 没加载起来)" else runCatching { nativeDims() }.getOrDefault("")

    /**
     * 安全打分。**任何一步出岔子都返回 0.0**（= C 层不参与）。
     */
    fun score(context: String, candidate: String): Double = when {
        !isAvailable -> 0.0
        candidate.isEmpty() -> 0.0
        else -> runCatching { nativeScore(context, candidate).toDouble() }.getOrDefault(0.0)
    }

    /** 给日志用的一句话状态。 */
    fun statusLine(): String = when {
        !linked -> "C 层：native-lib 未加载"
        !isAvailable -> "C 层：无模型"
        else -> "C 层：${dimsText()} · ${paramCount / 1_000_000}M 参数"
    }
}