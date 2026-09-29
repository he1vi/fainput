/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 【C 层 / 微 LM】候选打分 —— 神经层。
 *
 * ## 它做什么
 *
 * ```
 * 输入：上文（上一个上屏的词）+ 候选词
 * 输出：P(候选 | 上文) → 一个 0~3 的加权
 * ```
 *
 * 和 A 层（统计）的区别：A 层只会说「**你打过这个词**」，
 * C 层会说「**在这个上下文里，这个词更像话**」—— 它见过你没打过的东西。
 *
 * ## ★ 预算守卫（这一层唯一真正危险的地方）
 *
 * 实测（同机 aarch64，HID128 + NEON，20 候选 × 4 字 = 96 步）：
 *
 * ```
 *                              归一化     C 方案
 *   20 候选 × 4 字              22.6 ms    7.6 ms     ← 2.97 倍
 *   12 候选 × 4 字              13.8 ms    5.1 ms
 *    8 候选 × 4 字              10.1 ms    3.8 ms
 * ```
 *
 * 一帧只有 16ms。**万一候选多、模型大、机器慢，键盘就会卡。**
 *
 * ⇒ 所以这里有一道**硬预算**：一次重排总共只准花 [BUDGET_MS] 毫秒，
 *    超了就立刻停手，**已算出来的分照用，没算的当 0**（= C 层不参与那部分）。
 *
 * 这是"宁可少提升，不可卡键盘"的落地。
 *
 * ## 降级三层
 *
 * | 情况 | 行为 |
 * |---|---|
 * | `native-lib` 没加载 | [ready] = false，**连调用都不调** |
 * | 没有模型文件 | 同上 |
 * | 超预算 | 当次返回 0，日志记一次 |
 *
 * 三种都只是"C 层不参与"，其余三层照常工作。
 */
object LstmScorer {

    /** 模型放这儿（用户导入 / 训练脚本产出）。 */
    private const val DIR = "lstm"
    private const val EXT = ".fnlstm"

    /**
     * **一次重排的总预算**（毫秒）。
     *
     * 10ms 的来历：C 方案实测 20 候选 × 4 字 = 7.6ms（旧归一化版 22.6ms，
     * 会直接被截断）。一帧 16ms，A/B 两层加起来约 1ms —— 10ms 留了
     * 1.3 倍的余量，同时不挤掉系统的绘制/输入处理。
     * **宁可少提升，不可卡键盘。**
     */
    private const val BUDGET_MS = 10.0

    /**
     * C 方案把分数从「log 概率」换成了「原始 logit」，两者差一个 logsumexp。
     *
     * 实测这个模型的 logsumexp **非常稳定**（calib.py，n=300）：
     *
     * | | 归一化 | 原始 logit | 差 |
     * |---|---|---|---|
     * | 真候选（中位） | −5.18 | +1.71 | **6.89** |
     * | 随机候选（中位） | −16.57 | −9.66 | **6.91** |
     *
     * 所以旧的 −8.0 平移 +6.9 就是新的地板 —— **映射语义一模一样**
     * （真候选中位数都落在 1.41，随机候选都是 0），鉴别力也没有损失
     * （中位间距 11.39 vs 11.37）。
     */
    private const val LSE_SHIFT = 6.9

    /** 原始分（logit 平均）低于这个基本等于"不可能"，映射成 0 加权。 */
    private const val RAW_FLOOR = -8.0 + LSE_SHIFT

    /** 加权上限 —— 和 A/B 两层同一个量级，不搞特殊。 */
    private const val MAX_BOOST = 3.0

    @Volatile
    private var ready = false

    @Volatile
    private var context: String = ""

    /** 本批次已用掉的纳秒。 */
    @Volatile
    private var usedNs = 0L

    @Volatile
    private var budgetWarned = false

    /** 上次加载失败的模型名（避免重复刷日志）。 */
    @Volatile
    private var lastFailed: String? = null

    val isReady: Boolean
        get() = ready

    // ==================== 模型文件 ====================

    /** 模型目录 —— 应用私有。**非 root 写不进去**，只能靠 [importModel]。 */
    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** 磁盘上的模型：多个时取**最大的那个**（和 LLM 那边同一个策略：大的通常更好）。 */
    fun currentModel(ctx: Context): File? = dir(ctx).listFiles()
        ?.filter { it.isFile && it.name.endsWith(EXT, ignoreCase = true) }
        ?.maxByOrNull { it.length() }

    /**
     * 从系统文件选择器导入一个 `.fnlstm`。
     *
     * **这个入口是必须的，不是锦上添花。** 模型要放在 `filesDir/lstm/`，
     * 那是应用私有目录 —— 非 root 设备上 `cp` 不进去、`adb push` 也不认，
     * 没有它模型在真机上永远装不上，"装载模型"这件事根本无从谈起。
     */
    suspend fun importModel(ctx: Context, uri: Uri, displayName: String?): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = displayName?.takeIf { it.isNotBlank() } ?: "model$EXT"
                val name = (if (raw.endsWith(EXT, ignoreCase = true)) raw else raw + EXT)
                    .replace('/', '_')
                    .replace('\\', '_')
                val target = File(dir(ctx), name)
                val tmp = File(dir(ctx), "$name.part")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                } ?: error("打不开这个文件")
                if (target.exists()) target.delete()
                tmp.renameTo(target)
                invalidate()          // 换模型了 —— 让下次 init 重新加载
                Timber.i("[lstm] 模型已导入：%s（%d 字节）", name, target.length())
                target
            }
        }

    /** 删掉所有模型（含写了一半的 .part）。返回删掉几个。 */
    fun removeModels(ctx: Context): Int {
        invalidate()
        var n = 0
        dir(ctx).listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        return n
    }

    // ==================== 生命周期 ====================

    /**
     * 找并加载模型。**在后台线程调**（加载要读几 MB 文件）。
     *
     * 多个模型时取**最大的那个**（和 `LlmModel` 的策略一致：大的通常更好）。
     */
    fun init(ctx: Context) {
        if (ready) return
        runCatching {
            val f = currentModel(ctx)
            if (f == null) {
                Timber.i("[lstm] 没有模型文件 —— C 层不参与（这是正常的）")
                return
            }
            if (lastFailed == f.name) return
            if (LstmNative.load(f.absolutePath)) {
                ready = true
                lastFailed = null
                Timber.i(
                    "[lstm] 已加载 %s（%d 字节）· %s",
                    f.name, f.length(), LstmNative.dimsText()
                )
            } else {
                lastFailed = f.name
                Timber.w("[lstm] 加载失败：%s（文件格式不对？）", f.name)
            }
        }.onFailure { Timber.w(it, "[lstm] init 失败") }
    }

    /** 换模型后调它，下次 [init] 会重新加载。 */
    fun invalidate() {
        ready = false
        lastFailed = null
        LstmNative.unload()
    }

    /** 卸载（清数据 / 换模型时）。 */
    fun release() {
        ready = false
        LstmNative.unload()
    }

    // ==================== 输入 ====================

    /**
     * 设置**上文**（上一个上屏的词）。
     *
     * 为什么是"上一个词"而不是整句：LSTM 的状态是固定大小的，
     * 上下文越长收益越小、成本越高；而上一个词已经能提供大部分信号。
     * （真要用长上下文，把这里换成最近 N 个字即可，接口不用变。）
     */
    fun setContext(prev: String) {
        val c = prev.trim()
        if (c != context) context = c
    }

    /** 每次重排开始时调 —— **重置预算**。 */
    fun beginBatch() {
        usedNs = 0L
    }

    // ==================== 打分 ====================

    /**
     * 给一个候选打分，返回 **0 ~ [MAX_BOOST]** 的加权。
     *
     * `0.0` 的含义是"**这一层不参与**"，不是"这个词不好" ——
     * 所以它不会把候选往下压（只抬不降，和 A/B 两层同一条纪律）。
     */
    fun score(candidate: String): Double {
        if (!ready) return 0.0
        if (candidate.isEmpty()) return 0.0

        // ── 预算检查（在调用**之前**，避免超支后才后悔）──
        if (usedNs >= (BUDGET_MS * 1_000_000).toLong()) {
            if (!budgetWarned) {
                budgetWarned = true
                Timber.i("[lstm] 预算用尽（%.1fms）—— 本轮后面的候选不参与", BUDGET_MS)
            }
            return 0.0
        }

        val t0 = System.nanoTime()
        val raw = LstmNative.score(context, candidate)
        usedNs += System.nanoTime() - t0

        if (raw == 0.0) return 0.0      // 不可用 / OOV
        return toBoost(raw)
    }

    /**
     * 原始 log P → 加权。
     *
     * 线性映射 + 封顶：`(-8 → 0)`，`(-3 → 2.5)`，`(≥-2 → 3.0)`。
     *
     * 为什么不直接用原始分：它是**负的 log 概率**（-2 ~ -8），
     * 而 A/B 两层都是 0 ~ 正数。不映射的话 C 层会把所有候选都往下拉。
     */
    private fun toBoost(raw: Double): Double =
        ((raw - RAW_FLOOR) / 2.0).coerceIn(0.0, MAX_BOOST)

    /** 重置"预算用尽"的日志标记（下一轮重新报）。 */
    fun resetWarn() {
        budgetWarned = false
    }

    // ==================== 给 UI ====================

    fun statusLine(): String = when {
        ready -> LstmNative.statusLine()
        else -> "C 层：无模型"
    }

    /** 学了点什么 / 装了什么（「输入数据」页显示）。 */
    fun stats(): String = if (ready) {
        "${LstmNative.paramCount / 1_000_000}M · 预算 ${BUDGET_MS.toInt()}ms"
    } else {
        "暂无"
    }
}