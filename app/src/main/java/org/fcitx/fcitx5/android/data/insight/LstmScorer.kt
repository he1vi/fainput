/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import org.fcitx.fcitx5.android.core.data.DataManager
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

    /**
     * 【内置模型】放在 `app/src/main/assets/lstm/model.fnlstm`。
     *
     * ## 为什么不需要"导入"、也不需要解压代码
     *
     * `DataDescriptorTask`（build-logic）会**扫整个 assets 目录**、
     * 给每个文件算 SHA256 写进 `descriptor.json`；运行时 `DataManager.sync()`
     * 按这张表把文件同步到 **`数据目录/<相对路径>`**。
     *
     * ⇒ `assets/lstm/model.fnlstm` 会变成 `数据目录/lstm/model.fnlstm` ——
     *   **一个真实文件路径，C++ 直接 `fopen`**。
     *
     * ## 所以 [dir] 必须指向 `DataManager.dataDir` 而不是 `filesDir`
     *
     * 两者是不同的目录。写到 `filesDir` 里的话，**内置模型永远找不到**。
     */
    private const val DIR = "lstm"
    private const val EXT = ".fnlstm"

    /** 内置模型的文件名（assets 里那个）。 */
    private const val BUILTIN_NAME = "model.fnlstm"

    /**
     * **一次重排的总预算**（毫秒）。
     *
     * 10ms 的来历：C 方案实测 20 候选 × 4 字 = 7.6ms（旧归一化版 22.6ms，
     * 会直接被截断）。一帧 16ms，A/B 两层加起来约 1ms —— 10ms 留了
     * 1.3 倍的余量，同时不挤掉系统的绘制/输入处理。
     * **宁可少提升，不可卡键盘。**
     */
    private const val BUDGET_MS = 10.0

    /** [BUDGET_MS] 的纳秒表示 —— 免得每次打分都做一遍浮点乘法。 */
    private val BUDGET_NS = (BUDGET_MS * 1_000_000).toLong()

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

    /**
     * **本轮**预算是否已用尽。由 [beginBatch] 每轮重置。
     *
     * 为什么必须区分"本轮"和"历史"：`ScoringPipeline` 对**每个候选**各调一次
     * [score]，预算用尽后后面的候选拿到 `lm = 0` ⇒ **同一次重排里一半候选
     * 带神经加成、一半不带**。重排器靠这个标志把 C 层贡献**整体撤掉**。
     */
    @Volatile
    private var exhausted = false

    /** 「预算用尽」这条日志打过没 —— 只打一次，避免刷屏。 */
    @Volatile
    private var warned = false

    /** 上次加载失败的模型名（避免重复刷日志）。 */
    @Volatile
    private var lastFailed: String? = null

    val isReady: Boolean
        get() = ready

    // ==================== 模型文件 ====================

    /**
     * 模型目录。
     *
     * ⚠️ **必须是 `DataManager.dataDir`，不是 `filesDir`** ——
     * assets 同步的目标就是这里，写错地方内置模型就永远找不到。
     */
    fun dir(ctx: Context): File = File(DataManager.dataDir, DIR).apply { mkdirs() }

    /**
     * 当前要用的模型。
     *
     * **内置优先**（`assets/lstm/model.fnlstm` → 同步过来的那个），
     * 找不到才退回"目录里最大的 `.fnlstm`"（给将来放多个模型留的路）。
     */
    fun currentModel(ctx: Context): File? {
        val d = dir(ctx)
        File(d, BUILTIN_NAME).takeIf { it.isFile && it.length() > 0 }?.let { return it }
        return d.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXT, ignoreCase = true) }
            ?.maxByOrNull { it.length() }
    }

    // ==================== 为什么没有「导入模型」====================
    //
    // 【已删除】`importModel()` / `removeModels()`。
    //
    // 模型现在是**内置**的：`app/src/main/assets/lstm/model.fnlstm` 由
    // `DataDescriptorTask` 记进 `descriptor.json`，运行时 `DataManager.sync()`
    // 同步到 `数据目录/lstm/model.fnlstm` —— 一个真实路径，C++ 直接 `fopen`。
    //
    // 所以既不需要导入 UI，也不需要"从 assets 解压出来"的代码（同步已经把
    // 文件落到磁盘了）。少两条路径 ⇒ 少两类 bug。
    //
    // 将来要换模型：**换掉 assets 里那个文件重新构建**。
    // （真要支持"用户自己换"，再把导入加回来 —— 但那是另一个决定。）

    // ==================== 生命周期 ====================

    /**
     * 找并加载模型。**在后台线程调**（加载要读几 MB 文件）。
     *
     * 多个模型时取**最大的那个**（大的通常更好）。
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

    /** 每次重排开始时调 —— **重置预算**（含"本轮已用尽"标志）。 */
    fun beginBatch() {
        usedNs = 0L
        exhausted = false
    }

    /**
     * 本轮预算是否已用尽。
     *
     * `true` ⇒ 重排器**必须把 C 层贡献整体撤掉**。理由：已经有一半候选
     * 拿过 `lm` 加分、另一半没有 —— 混在一起排序等于两套标准，
     * 而且"谁在候选表里靠前谁占便宜"，这种不可预测比"整层不参与"更糟。
     */
    val budgetExhausted: Boolean
        get() = exhausted

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
        if (usedNs >= BUDGET_NS) {
            exhausted = true
            if (!warned) {
                warned = true
                Timber.i("[lstm] 预算用尽（%.1fms）—— 本轮 C 层贡献整体撤掉", BUDGET_MS)
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
     * 原始分 → 加权。线性映射 + 封顶。
     *
     * ## ⚠️ 下面这些数字是**平移后**的坐标
     *
     * 模型吐的是**原始 logit**（没过 logsumexp），比"log 概率"整体高一个
     * logsumexp（实测 ≈6.9，见 [LSE_SHIFT]）。所以同一个语义点有两个坐标：
     *
     * | 语义 | 平移前（log 概率） | 平移后（logit ＝ 这里的入参） | 加权 |
     * |---|---|---|---|
     * | 地板 | −8.0 | **−1.1** ← 就是 [RAW_FLOOR] | 0.0 |
     * | 中段 | −3.0 | **3.9** | 2.5 |
     * | 封顶 | ≥ −2.0 | **≥ 4.9** | 3.0 |
     *
     * 两列的**语义完全一样**（真候选中位数落在 1.41、随机候选都落 0），
     * 只是坐标轴平移了 —— 这也是为什么斜率还是 `/2.0` 没变。
     * **改 [LSE_SHIFT] 时这张表要跟着改。**
     *
     * 为什么不直接用原始分：它是负的（log 概率 −2 ~ −8），
     * 而 A/B 两层都是 0 ~ 正数。不映射的话 C 层会把所有候选都往下拉。
     */
    private fun toBoost(raw: Double): Double =
        ((raw - RAW_FLOOR) / 2.0).coerceIn(0.0, MAX_BOOST)

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