/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import timber.log.Timber
import kotlin.math.exp

/**
 * 【D-1'】候选栏注入 —— 「越用越懂你」真正开始的地方。
 *
 * ## 它做什么
 *
 * 每次候选列表到达 UI 之前，把**你已经学过的词**提到本页最前面。
 *
 * ```
 * 引擎给的（engine order）:  你好  尼豪  你号  拟好  泥壕 …
 * 我们显示的（display order）: 泥壕  你好  尼豪  你号  拟好 …
 *                              ↑ 你以前打「nihao」时平均选到第 5 位
 * ```
 *
 * ## 为什么不是"另加一条预测栏"
 *
 * 用户不关心"这是引擎给的"还是"我们推的" —— 他只要那个词。
 * **两条候选栏是外挂，不是一个输入法该有的样子。**（2026-09-28 用户拍板）
 *
 * ## 三条安全边界（这是"要有限度"的落地）
 *
 * | 边界 | 值 | 为什么 |
 * |---|---|---|
 * | **最少见过几次才提升** | [UserProfile.Snapshot.minCount]（冷启 3，数据厚了降到 2） | 打过一次的词不足以证明"你这人爱用" |
 * | **一次最多提升几个** | [UserProfile.Snapshot.maxPromote]（冷启 2，最多 4） | 全推到前面 = 频繁推荐 = 适得其反 |
 * | **码长门槛** | [MIN_PREEDIT] = 2 | 打一个字就重排，等于在跟用户抢方向盘 |
 *
 * ⚠️ 前两条**不再是这里的常量** —— 它们由 D 层算出的画像决定（见 [UserProfile]）。
 *    冷启动画像 = 3 / 2，和以前写死的值完全一致。
 *
 * ## 时间衰减（照抄 rime 的思想，不是重造轮子）
 *
 * ```
 * score = count × e^( -(now - lastSeen) / TAU )
 * ```
 * `_refs/librime/dynamics.h` 里的 `formula_d` 就是指数衰减；
 * rime 用时间衰减解决"越推越推"，我们用同一招。
 * **三个月前打了 100 次、最近一次都没碰过的词，不该压过昨天刚打 5 次的词。**
 *
 * ## 下标映射（正确性的关键）
 *
 * 重排会**打乱显示顺序与引擎下标的对应关系**：
 * ```
 * perm = [4, 0, 1, 2, 3]     // 显示第 0 位 → 引擎第 4 个
 * 用户点显示第 0 位 → 必须 select(4)，而不是 select(0)
 * ```
 * 所有"按显示位置选择"的地方都要过 [displayToEngine]：
 * - `HorizontalCandidateComponent` 点击 / 长按
 * - `CommonKeyActionListener` 空格选首选（`select(0)`）
 *
 * ⚠️ **`BaseExpandedCandidateWindow`（展开窗）不走这里** ——
 *    它用 `getCandidates(offset, limit)` 直接从引擎拉全量列表，
 *    显示的就是引擎原序，所以它传的 idx 是引擎下标，**不能映射**。
 *
 * ## 已查证的事实（省得以后再查）
 *
 * - 数字键 1-9 **不会**选候选：引擎 `pinyin.cpp:1400` 是
 *   `if (!event.isVirtual())` —— 虚拟按键（屏幕键盘）被主动排除。
 *   所以重排**不会**造成"按 3 出第 4 个"的错位。
 * - `PagedCandidateEvent` 实测不发，候选页的真正来源是 `CandidateListEvent`。
 */
object CandidateReranker {

    private const val PREFS_NAME = "fainput_rerank"
    private const val KEY_ENABLED = "enabled"

    // ⚠️ 「至少提交几次才够格」和「一次最多提升几个」**不再写死在这里** ——
    //    它们现在是 [UserProfile] 画像的一部分（D 层按数据量算出来的）。
    //    冷启动画像 = 3 / 2，**和以前完全一样** ⇒ 升级不改变现有行为。

    /** 预编辑（拼音串）短于这个长度就不重排。 */
    private const val MIN_PREEDIT = 2

    /** 提升表最短刷新间隔。 */
    private const val REFRESH_INTERVAL_MS = 30_000L

    /** 时间衰减常数：7 天。 */
    private const val TAU_MS = 7L * 24 * 3600 * 1000

    /** 长按「短暂屏蔽推荐」的有效期。 */
    private const val SUPPRESS_MS = 24L * 3600 * 1000

    /** 词表最多看这么多条（够用，且刷新很快）。 */
    private const val TOP_LIMIT = 500

    private var prefs: SharedPreferences? = null

    // ==================== 状态 ====================

    @Volatile
    private var enabled: Boolean = true

    /** 词 → 分数（已含时间衰减）。 */
    @Volatile
    private var promote: Map<String, Double> = emptyMap()

    /**
     * 【M·L1】词搭配加权：`上一个上屏的词` 之后常跟哪些词。
     *
     * **不在这里自己算** —— 由 [BigramModel] 在每次上屏后灌好。
     * `reorder()` 跑在候选列表事件里（主线程），所以这里只读一次引用。
     */
    private val bigram: Map<String, Double>
        get() = BigramModel.current

    /** 词 → 屏蔽到什么时候（毫秒时间戳）。 */
    private val suppressed = HashMap<String, Long>()

    /**
     * **屏蔽必须持久化**。
     *
     * 说了"24 小时"，如果进程一重启就失效，那是骗人 ——
     * 输入法进程被系统杀是很常见的事。
     */
    @Volatile
    private var suppressedLoaded = false

    private const val K_SUPPRESS = "s|"

    /** 第一次访问时才从 SharedPreferences 读（打字路径上不能有 IO）。 */
    private fun ensureSuppressedLoaded() {
        if (suppressedLoaded) return
        // ⚠️ 必须先确认 prefs 就绪**再**置位。
        //    否则 init() 之前被调一次，就会把 loaded 打成 true，
        //    之后真正的屏蔽记录永远读不进来 —— 而且不会有任何报错。
        val p = prefs ?: return
        suppressedLoaded = true
        val now = System.currentTimeMillis()
        p.all.forEach { (k, v) ->
            if (!k.startsWith(K_SUPPRESS)) return@forEach
            val until = v as? Long ?: return@forEach
            if (until > now) suppressed[k.removePrefix(K_SUPPRESS)] = until
        }
    }

    @Volatile
    private var lastLoadAt: Long = 0L

    /** 最近一次看到的预编辑长度。 */
    @Volatile
    private var lastPreeditLength: Int = 0

    /**
     * 【B/C 层】最近一次看到的**码**（拼音串，小写）。
     *
     * 两处要用：
     * - **B 层**：查「码 → 词」和「码 + 错 → 对」两张表
     * - **C 层**：不是给 LSTM 的（它要的是**中文上文**），只是留着排查用
     */
    @Volatile
    private var lastCode: String = ""

    // ==================== 【ABCD / 边界】引擎门禁 ====================

    /**
     * 当前输入法是不是"中文拼音系"。
     *
     * ## 为什么需要这道门
     *
     * 学习数据（`word_stat` / `word_bigram` / 画像）**只在拼音下产生**。
     * 如果拿到五笔 / 日语 / 英语上去用，就是**跨引擎污染** ——
     * 中文的词频会去抬日语的候选，而且**用户完全看不出来为什么**。
     *
     * ⇒ 不做多语言预测的代价，就是必须有这道门。
     *    没这道门，"只支持中文"会变成"到处乱插中文候选"。
     *
     * ## 判定用 `addon` 而不是 `uniqueName`
     *
     * `pinyin` 和 `shuangpin` 是两个 IM，但**同属 pinyin addon**，
     * 码空间一致，学习数据本来就该共用。
     * 用 `addon == "pinyin"` 一句话同时覆盖它们，还顺便排除了
     * `table`（五笔/郑码/电报码）和 `keyboard`（英语）。
     */
    @Volatile
    private var pinyinLike: Boolean = false

    /**
     * 由 `InputView` 在 `IMChangeEvent` 时调用。
     *
     * **切换引擎必须立刻生效** —— 不然用户从拼音切到五笔，
     * 第一个词就会吃到中文的词频分。
     */
    fun setActiveIm(addon: String?, uniqueName: String?) {
        val a = addon?.trim()?.lowercase() ?: ""
        val u = uniqueName?.trim()?.lowercase() ?: ""
        val like = a == "pinyin" || u == "pinyin" || u == "shuangpin"
        if (like != pinyinLike) {
            Timber.i("[rerank] 引擎切换：%s（addon=%s）→ 学习层%s", u, a, if (like) "参与" else "不参与")
        }
        pinyinLike = like
    }

    /** 给设置页显示用。 */
    val isPinyinLike: Boolean
        get() = pinyinLike

    /**
     * 当前这一屏的「显示下标 → 引擎下标」映射。
     * `null` 表示**没有重排**（原序），此时映射是恒等的。
     */
    @Volatile
    private var perm: IntArray? = null

    // ==================== 生命周期 ====================

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean(KEY_ENABLED, true)
        Timber.i("[rerank] init, enabled=%s", enabled)
    }

    val isEnabled: Boolean
        get() = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        if (!value) perm = null
        Timber.i("[rerank] enabled -> %s", value)
    }

    /**
     * 【ABCD】让词表**立刻失效**，下次重排重新从库里读。
     *
     * 为什么必须补这个方法：「清空全部数据」之后，`promote` 里还留着旧词频表，
     * 而 [maybeRefresh] 有 30 秒节流 —— 这 30 秒内重排会按**已经被删掉的**数据提前候选。
     * 用户刚点了"清空"，看到的行为却像没清 —— 这是最伤信任的一类 bug。
     */
    fun invalidate() {
        promote = emptyMap()
        lastLoadAt = 0L
        perm = null
        Timber.i("[rerank] 词表已失效（下次重排重新读库）")
    }

    /** 已学会多少个词（给「输入数据」页显示用）。 */
    val learnedWordCount: Int
        get() = promote.size

    // ==================== 输入 ====================

    /**
     * 由 `InputView` 在收到 `InputPanelEvent` 时调用。
     *
     * 为什么需要它：只有正在拼一个词（preedit 非空）的时候才谈得上"候选"。
     * 空 preedit 时重排毫无意义，还会让候选栏在收起/展开时闪一下。
     */
    /**
     * 由 `InputView` 在收到 `InputPanelEvent` 时调用。
     *
     * @param preedit **原始**预编辑（**不是**密码遮罩后的版本）——
     *                B 层要拿这个码去查表。密码框在 `InputView` 那边就
     *                被 `sensitiveEditor` 拦住了，走不到这儿。
     */
    fun onPreeditChanged(preedit: FormattedText) {
        val text = preedit.strings.joinToString("")
        lastPreeditLength = text.length
        lastCode = text.trim().lowercase()
    }

    /**
     * 长按候选词 → 「短暂屏蔽推荐」。
     *
     * **不是删除**：词照旧留在引擎词库里，正常打字还会在它原来的位置出现，
     * 只是**不再被我们提前**。过了 [SUPPRESS_MS] 自动恢复。
     */
    fun suppress(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val until = System.currentTimeMillis() + SUPPRESS_MS
        suppressed[w] = until
        prefs?.edit()?.putLong(K_SUPPRESS + w, until)?.apply()
        perm = null
        Timber.i("[rerank] suppressed (len=%d)", w.length)
    }

    /**
     * 恢复推荐。
     *
     * **由设置页调用，不在长按菜单里**（用户拍板：长按只做"立即动作"，
     * 恢复属于管理，归设置页）。
     */
    fun unsuppress(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        suppressed.remove(w)
        prefs?.edit()?.remove(K_SUPPRESS + w)?.apply()
        perm = null
    }

    /** 全部恢复。 */
    fun clearSuppressed() {
        suppressed.clear()
        val p = prefs ?: return
        val e = p.edit()
        p.all.keys.filter { it.startsWith(K_SUPPRESS) }.forEach { e.remove(it) }
        e.apply()
    }

    fun isSuppressed(word: String): Boolean {
        ensureSuppressedLoaded()
        val w = word.trim()
        val until = suppressed[w] ?: return false
        if (until <= System.currentTimeMillis()) {
            unsuppress(w)
            return false
        }
        return true
    }

    /** 当前被屏蔽的词 + 解禁时刻（给设置页展示）。顺带清掉已过期的。 */
    fun suppressedWords(): List<Pair<String, Long>> {
        ensureSuppressedLoaded()
        val now = System.currentTimeMillis()
        suppressed.entries.removeAll { it.value <= now }
        return suppressed.entries.sortedBy { it.value }.map { it.key to it.value }
    }

    // ==================== 核心：重排 ====================

    /**
     * 把 [data] 里的候选词重排。**只提升，不增删** ——
     * 候选总数、每个词的内容都不变，只有顺序变。
     */
    fun reorder(data: FcitxEvent.CandidateListEvent.Data): FcitxEvent.CandidateListEvent.Data {
        ensureSuppressedLoaded()
        perm = null
        val list = data.candidates
        if (!enabled || list.size < 3) return data
        if (lastPreeditLength < MIN_PREEDIT) return data
        // 【ABCD / 边界】学习层**只在拼音系引擎上参与**。
        // 其它引擎（五笔 / 日语 / 英语）直接放行 —— 引擎原序，一个字都不动。
        if (!pinyinLike) return data

        maybeRefresh()
        val scores = promote
        // 【M·L1】词搭配：`上一个上屏的词` 后面常跟哪些词。
        val pairs = bigram
        if (scores.isEmpty() && pairs.isEmpty()) return data

        // 【ABCD】画像：A/B/C 三层的权重与边界都从这一份来（无锁读）。
        val profile = UserProfile.current

        // 【C 层】上文 + 预算重置。
        // 上下文只设一次 —— native 侧会缓存它的 LSTM 状态，几十个候选共用一份，
        // 这就是"每个候选只跑自己那几个字"的来源。
        LstmScorer.setContext(BigramModel.previousWord)
        LstmScorer.beginBatch()

        val now = System.currentTimeMillis()
        val scored = ArrayList<Pair<Int, Double>>(list.size)
        list.forEachIndexed { i, w ->
            val t = w.text.trim()
            if (t.isEmpty()) return@forEachIndexed
            val until = suppressed[t]
            if (until != null && until > now) return@forEachIndexed
            // 【ABCD】统一打分：A 统计 + B 个人纠错 + C 神经 → 融合。
            // 码（`lastCode`）给 B 层查表用。
            val s = ScoringPipeline.score(t, lastCode, scores, pairs, profile)
            // 三层全 0 → 这个词我们一无所知，保持引擎原序（不插手）
            if (s.isZero) return@forEachIndexed
            scored += i to s.fused
        }
        if (scored.isEmpty()) return data

        // 分数高的优先；同分保持引擎原有的先后（稳定）
        scored.sortWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
        val front = scored.take(profile.maxPromote).map { it.first }

        // 已经都在最前面 → 不用动
        if (front.withIndex().all { (i, engineIdx) -> i == engineIdx }) return data

        val order = ArrayList<Int>(list.size)
        order += front
        list.indices.forEach { if (it !in front) order += it }
        val p = IntArray(order.size) { order[it] }
        perm = p
        Timber.i(
            "[rerank] promoted %d of %d candidates (front=%s)",
            front.size, list.size, front.toString()
        )
        return data.copy(candidates = Array(list.size) { list[p[it]] })
    }

    /**
     * 显示下标 → 引擎下标。
     *
     * 没重排时是恒等映射，所以可以无脑调用。
     */
    fun displayToEngine(idx: Int): Int {
        val p = perm ?: return idx
        return if (idx in p.indices) p[idx] else idx
    }

    /**
     * 最近一次的「显示下标 → 引擎下标」映射，没重排时返回 `null`。
     *
     * 给 `HorizontalCandidateViewAdapter` 用：候选条把引擎下标直接放进
     * `holder.idx`，于是**点击、长按、动作菜单全都不用改** ——
     * 它们拿到的本来就是引擎下标。
     */
    fun lastPermutation(): IntArray? = perm?.copyOf()

    // ==================== 提升表 ====================

    private fun maybeRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastLoadAt < REFRESH_INTERVAL_MS) return
        lastLoadAt = now
        if (!InsightRecorder.isReady) return
        val dao = InsightRecorder.daoOrNull ?: return
        InsightRecorder.launch {
            runCatching {
                val rows = InsightRecorder.topWords(TOP_LIMIT)
                // 【A1 热词加权】近 7 天每个词出现多少次。
                // 为什么必须单独算：`word_stat` 只有累计值，分不出
                // 「昨天打了 5 次」和「半年前打了 5 次」。
                // 数据来源是 input_event（保留 90 天），所以 7 天窗口永远都在。
                val recent = HashMap<String, Int>(256)
                dao.recentWords(System.currentTimeMillis() - TAU_MS, TOP_LIMIT)
                    .forEach { recent[it.word] = it.count }

                val t = System.currentTimeMillis()
                val map = HashMap<String, Double>(rows.size)
                // 【ABCD】门槛来自画像，不再写死 —— 数据厚了它会自己降到 2。
                val minCount = UserProfile.current.minCount
                rows.forEach { w ->
                    if (w.count < minCount) return@forEach
                    val text = w.word.trim()
                    // 只要词，不要句子。长度 1 的不进（直通提交的噪音）。
                    if (text.length < 2 || text.length > 12) return@forEach
                    // 【A1 + A2】近 7 天的使用**全额计入**；
                    // 更早的使用按时间衰减打折。
                    // 注意这是"只抬不降"的：热词只会更靠前，冷词不会被压成负数。
                    val hot = (recent[text] ?: 0).toDouble()
                    val cold = (w.count - hot).coerceAtLeast(0.0)
                    val recency = exp(-(t - w.lastSeen).toDouble() / TAU_MS)
                    map[text] = hot + cold * recency
                }
                promote = map
                Timber.i(
                    "[rerank] loaded %d words (hot=%d) from %d rows",
                    map.size, recent.size, rows.size
                )
            }.onFailure {
                Timber.w(it, "[rerank] load failed")
            }
        }
    }

    /** 仅供「输入数据」页展示用。 */
    fun topLearned(limit: Int = 5): List<Pair<String, Double>> =
        promote.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
}
