/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 【影子统计】—— 一次定死「我们的重排到底是在帮忙还是帮倒忙」。
 *
 * ## 为什么必须做这个
 *
 * 现在的架构是**两段**：
 * ```
 * libime 引擎（词典 198K 词 + 33MB n-gram）→ 候选列表（引擎原序）
 *      ↓
 * 我们的 ABCD + LSTM → 重排（把 maxPromote 个候选提到最前）
 * ```
 *
 * 用户设备上实测**首选命中率 73.3%**。但这个数字**分不清**：
 * - 是**引擎本来就只有 73%**，我们没帮上忙？
 * - 还是**引擎本来更高**，被我们**顶掉了**？
 *
 * ⇒ 差别是决定性的：前者要"加强重排"，后者要"立刻关掉重排"。
 *
 * ## 怎么量（关键设计）
 *
 * 每次候选列表到达时，同时记下两样：
 * ```
 * engineFirst  = 引擎原序的第 0 个   ← 引擎认为最好的
 * ourFirst     = 重排后的第 0 个      ← 我们认为最好的
 * ```
 * 用户上屏时对比文本，两边各算一次命中。
 *
 * **口径和「输入数据」页现有的「首选命中率」完全一致** ——
 * 只统计「上屏文本能在候选列表里找到」的提交
 * （整句提交 / 标点 / 英文找不到，不参与，见 `InsightRecorder.record`）。
 *
 * ## 为什么是内存态、不落盘
 *
 * 落盘要给 `input_event` 加列 ⇒ 要 DB migration ⇒ 风险和收益不成比例。
 * 这个统计**只是用来做一次判断**，不是长期指标 ——
 * 用一天就有几百个样本，重启清零完全够用。
 *
 * ## 为什么用 AtomicInteger
 *
 * `noteCandidates()` 在**主线程**（候选列表事件里），
 * `onCommit()` 在**IO 协程**（落库那条路径）。
 * 两边不同线程 ⇒ 普通 `var ++` 会丢计数。
 */
object ShadowStats {

    /** 引擎原序第 0 个（候选列表变化时刷新）。 */
    @Volatile
    private var engineFirst: String = ""

    /** 我们重排后的第 0 个。 */
    @Volatile
    private var ourFirst: String = ""

    /** 这一轮候选是不是有效的（空列表 / 非拼音系时为 false）。 */
    @Volatile
    private var active: Boolean = false

    /** 参与对比的提交数（= 上屏文本在候选列表里能找到的次数）。 */
    val samples = AtomicInteger(0)

    /** 其中，上屏文本 == **引擎原序第一** 的次数。 */
    val engineTopHits = AtomicInteger(0)

    /** 其中，上屏文本 == **我们排序第一** 的次数。 */
    val ourTopHits = AtomicInteger(0)

    /** 我们**改动过第一名**的次数（说明重排真的动了手）。 */
    val movedCount = AtomicInteger(0)

    /**
     * 重排后**显示顺序**的候选文本（= 用户真正看到的顺序）。
     *
     * ## 为什么需要它（这是个口径 bug）
     *
     * `InsightRecorder` 挂在**原始事件流**上（`FcitxInputMethodService.handleFcitxEvent`
     * 的最前面，重排还没发生），所以它手里的 `lastCandidates` 是**引擎原序**。
     * 于是「首选命中率」算的是"你在**引擎顺序**里选了第 0 个"——
     * 可你看到的是**我们重排后的顺序**。两个口径对不上。
     *
     * 实测（2026-10-05）就露馅了：
     * ```
     *     DB「首选命中率」  78.9%   ← 按引擎序算
     *     影子「引擎第一」  78.3%   ← 两者互相验证，说明影子是对的
     *     影子「我们第一」  63.7%   ← 这才是你真实的体验
     * ```
     * ⇒ 把这个列表交给 `InsightRecorder`，DB 的口径就跟着修正了。
     */
    @Volatile
    var displayCandidates: List<String> = emptyList()
        private set

    /** 候选列表重排完 / 加工完时调用（`CandidateReranker.reorder` 末尾）。 */
    fun noteDisplay(list: List<String>) {
        displayCandidates = list
    }

    /**
     * 候选列表到达时调用（`CandidateReranker.reorder` 里）。
     *
     * @param engine 引擎原序的第一个候选文本（没有就传 null）
     * @param ours   我们重排后的第一个候选文本（没有就传 null）
     */
    fun noteCandidates(engine: String?, ours: String?) {
        if (engine.isNullOrEmpty() || ours.isNullOrEmpty()) {
            active = false
            return
        }
        engineFirst = engine
        ourFirst = ours
        active = true
        if (engine != ours) movedCount.incrementAndGet()
    }

    /** 候选列表为空 / 不参与时调用，避免拿旧值误判。 */
    fun clear() {
        active = false
    }

    /**
     * 用户上屏时调用（`InsightRecorder.record` 里）。
     *
     * @param text    上屏的文本
     * @param offered 这个文本**是否出现在候选列表里**（= 现有口径的门槛）
     */
    fun onCommit(text: String, offered: Boolean) {
        if (!active || !offered) return
        val t = text.trim()
        if (t.isEmpty()) return
        samples.incrementAndGet()
        if (t == engineFirst) engineTopHits.incrementAndGet()
        if (t == ourFirst) ourTopHits.incrementAndGet()
    }

    fun reset() {
        samples.set(0)
        engineTopHits.set(0)
        ourTopHits.set(0)
        movedCount.set(0)
        active = false
    }

    /**
     * 给 UI / 日志用的一行。
     *
     * 判读：
     * - `引擎 ≥ 我们` ⇒ **重排在帮倒忙** ⇒ 应该关掉或大幅收敛
     * - `我们 > 引擎` ⇒ 重排在起作用 ⇒ 继续加强
     */
    fun line(): String {
        val n = samples.get()
        if (n == 0) return "暂无"
        val e = 100.0 * engineTopHits.get() / n
        val o = 100.0 * ourTopHits.get() / n
        val d = o - e
        val sign = if (d >= 0) "+" else ""
        // 形如 `71.0→73.3 (+2.3)  500 次`
        // 左 = 引擎原序第一命中率，右 = 我们排序第一命中率。
        return String.format(Locale.US, "%.1f→%.1f (%s%.1f)  %,d 次", e, o, sign, d, n)
    }

    /** 给「输入数据」页的第二行：差值和改动次数。 */
    fun detail(): String {
        val n = samples.get()
        if (n == 0) return "——"
        val e = 100.0 * engineTopHits.get() / n
        val o = 100.0 * ourTopHits.get() / n
        val d = o - e
        val sign = if (d >= 0) "+" else ""
        return "%s%.1f 点 · 改过 %d 次首选".format(sign, d, movedCount.get())
    }
}
