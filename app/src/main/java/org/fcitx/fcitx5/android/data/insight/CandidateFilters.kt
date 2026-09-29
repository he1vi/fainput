/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import android.content.SharedPreferences
import org.fcitx.fcitx5.android.core.CandidateWord
import timber.log.Timber

/**
 * 【从 Rime 借鉴】候选后处理管道 —— 就是它 `engine` 里的 **`filters`** 那一段。
 *
 * ## 为什么要有这一层
 *
 * Rime 的 engine 是四段流水线：
 *
 * ```
 * processors → segmentors → translators → filters
 * ```
 *
 * 前三段 `libime` 已经在做（按键处理 / 音节切分 / 翻译成候选）。
 * **我们缺的是 `filters`** —— 它是「候选产出**之后**」的正交加工：
 * 简繁转换、去重、标点、编码转换……
 *
 * 我们原来**只有「打分」，没有「加工」**。后果很具体：
 * 任何"改候选"的需求（遮罩 / 去重 / 转换）只能塞进
 * [CandidateReranker.reorder] —— 而那里是**学习层的排序逻辑**。
 * 塞进去有两个问题：
 *
 * 1. **污染打分**：那些加工和用户数据无关，不该有学习层的权重
 * 2. **不可组合**：想加第二个加工，就得再改一遍 `reorder()` 的主干
 *
 * ## 为什么必须返回「显示 → 引擎」映射
 *
 * 过滤器**可以删项**（去重就是删）。而选候选走的是**下标**
 * （`select(index)` / `displayToEngine(0)`），所以删项之后**必须**给出新映射 ——
 * 否则点第 3 个会选到第 4 个。这就是 [Item] 要带着 `engineIndex` 流动的原因。
 *
 * ## 纪律（和 A/B/C 三层同源）
 *
 * - **不排序**：过滤器只做"加工"，排序是 [CandidateReranker] 的事
 * - **可关**：每个过滤器有自己的开关，关掉 = 完全回到上一层的结果
 * - **永不崩**：任何一个过滤器抛异常 → **跳过它**，输入法照常工作
 */
object CandidateFilters {

    /** 管道里流动的一项：候选本身 + 它在**引擎原始列表**里的下标。 */
    data class Item(val engineIndex: Int, val word: CandidateWord)

    /** 一次加工的参数。 */
    data class Ctx(
        /** 当前输入框是不是密码框 —— 决定要不要遮罩。 */
        val sensitive: Boolean,
    )

    /**
     * 一个过滤器。
     *
     * 实现者只需要保证：**输入是一个有序列表，输出也是一个有序列表**
     * （可以更短、可以改内容，但**顺序语义由你自己负责**）。
     */
    interface Filter {
        /** 稳定标识，只用于日志。 */
        val id: String

        /** 现在开不开。 */
        val enabled: Boolean

        fun apply(items: List<Item>, ctx: Ctx): List<Item>
    }

    // ==================== 内置过滤器 ====================

    /**
     * **【隐私】密码框遮罩** —— 把候选文字一律换成「·」。
     *
     * 这一段原先内联在 `InputView.handleFcitxEvent` 里。搬进管道是为了
     * **证明这个抽象能容纳已有功能**（而不是只能容纳新功能）。
     *
     * 安全性前提（已核实）：选候选走的是**下标**（`select(index)`），
     * 不是候选文字 ⇒ 遮住显示**不影响上屏**。
     *
     * 为什么必须遮：中文输入法在密码框里照样会拼拼音、出候选，
     * 候选栏等于把密码（或它的拼音）明晃晃写在屏幕上。
     */
    private object SensitiveMask : Filter {
        override val id = "sensitive-mask"
        override val enabled = true

        override fun apply(items: List<Item>, ctx: Ctx): List<Item> {
            if (!ctx.sensitive) return items
            // 数量**一个不变** —— 所以 `total` 也不用动。
            return items.map { Item(it.engineIndex, it.word.copy(text = "·", comment = "")) }
        }
    }

    /**
     * **【从 Rime 抄的】去重** —— 对应 Rime 的 `uniquifier`。
     *
     * 候选可能来自多条通路（词库 / 整句 / 用户词 / 笔画 / 拆字），
     * 同一个词被不同通路各产出一次是有可能的。**保留最靠前的那个**
     * （也就是分数最高的那个），其余丢掉。
     *
     * ## ⚠️ 为什么**默认关**
     *
     * 它会**改变候选数量**。而 `Data.total` 是**引擎的总数**，用于判断
     * "展开窗翻到底了没"（`adapter.total == childCount`）。
     * 我们删了几项但引擎总数没变 ⇒ 那个判断会误以为"还有更多"。
     *
     * 影响很小（最坏是分页指示不准），但**默认行为不该有副作用** ——
     * 所以它是个显式开关，用户开了才生效。
     */
    private object Uniquifier : Filter {
        override val id = "uniquifier"
        override val enabled: Boolean
            get() = uniquifierEnabled

        override fun apply(items: List<Item>, ctx: Ctx): List<Item> {
            if (items.size < 2) return items
            val seen = HashSet<String>(items.size)
            val out = ArrayList<Item>(items.size)
            for (it in items) {
                if (seen.add(it.word.text)) out += it
            }
            if (out.size == items.size) return items
            Timber.i("[filter] uniquifier 去掉 %d 个重复候选", items.size - out.size)
            return out
        }
    }

    private val all: List<Filter> = listOf(SensitiveMask, Uniquifier)

    /**
     * 跑整条管道。**顺序就是 [all] 的顺序** —— 先隐私，后美化。
     *
     * 任何一步抛异常都只是"这一步跳过"，其余照跑。
     */
    fun run(items: List<Item>, ctx: Ctx): List<Item> {
        if (items.isEmpty()) return items
        var cur = items
        for (f in all) {
            if (!f.enabled) continue
            cur = runCatching { f.apply(cur, ctx) }
                .onFailure { Timber.w(it, "[filter] %s 出错 —— 跳过这一步", f.id) }
                .getOrDefault(cur)
        }
        return cur
    }

    /** 给 UI / 日志用：现在有哪些过滤器在生效。 */
    fun activeIds(): List<String> = all.filter { it.enabled }.map { it.id }

    // ==================== 开关（持久化） ====================

    private const val PREFS_NAME = "fainput_filters"
    private const val K_UNIQUIFIER = "uniquifier"

    private var prefs: SharedPreferences? = null

    @Volatile
    private var uniquifierEnabled: Boolean = false

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        uniquifierEnabled = p.getBoolean(K_UNIQUIFIER, false)
        Timber.i("[filter] init: %s", activeIds().joinToString().ifEmpty { "(无)" })
    }

    val isUniquifierEnabled: Boolean
        get() = uniquifierEnabled

    fun setUniquifierEnabled(value: Boolean) {
        uniquifierEnabled = value
        prefs?.edit()?.putBoolean(K_UNIQUIFIER, value)?.apply()
        Timber.i("[filter] uniquifier -> %s", value)
    }
}
