/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.insight.DeviceState
import org.fcitx.fcitx5.android.data.insight.BigramModel
import org.fcitx.fcitx5.android.data.insight.CandidateReranker
import org.fcitx.fcitx5.android.data.insight.InsightMaintenance
import org.fcitx.fcitx5.android.data.insight.InsightRecorder
import org.fcitx.fcitx5.android.data.insight.PersonalDictionary
import org.fcitx.fcitx5.android.data.insight.db.LevelCount
import org.fcitx.fcitx5.android.data.insight.db.StatsProjection
import org.fcitx.fcitx5.android.data.insight.db.WordStatEntity
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.setup
import java.util.Calendar
import java.util.Locale

/**
 * 【fainput】「输入数据」页 —— C 阶段的核心。
 *
 * ## 这不是年报，是实时镜像
 *
 * 所有数字都来自 `Flow` 订阅：**你打一个字，这一页的数字就跳。**
 * 没有刷新按钮，也不需要重新进页面。
 *
 * ## 三个区块
 *
 * 1. **今天** —— 字数 / 码长 / 首选命中率 / 速度（每次上屏都会变）
 * 2. **学习状态** —— 输入法在不在学、下一步什么时候学、**为什么现在没学**
 * 3. **最常用的词** —— 实时词频榜
 *
 * ## 为什么"为什么没在学"很重要
 *
 * 如果用户看不见输入法在学，"它会越用越懂你"就只是一句宣传语 ——
 * 和大厂的吹嘘没有区别。所以这里要**明确给出判断依据**：
 * 「CPU 频率 68% > 40%，暂不打扰」比「待机中」有用一百倍。
 */
class InsightFragment : PaddingPreferenceFragment() {

    // ---- 今天 ----
    private lateinit var pTodayChars: Preference
    private lateinit var pTodayEvents: Preference
    private lateinit var pCodeLen: Preference
    private lateinit var pFirstHit: Preference
    private lateinit var pSpeed: Preference
    private lateinit var pPaged: Preference

    // ---- 累计 ----
    private lateinit var pTotalChars: Preference
    private lateinit var pTotalEvents: Preference

    // ---- 学习状态 ----
    private lateinit var pLearnState: Preference
    private lateinit var pWhy: Preference
    private lateinit var pLastRun: Preference
    private lateinit var pSamples: Preference
    private lateinit var pArchived: Preference

    // ---- 隐私 ----
    private lateinit var pLevels: Preference

    // ---- 词表 ----
    private lateinit var catWords: PreferenceCategory
    // ---- 【D-2 / D-3】词库 / 屏蔽 / 纠错 ----
    private lateinit var catBlocked: PreferenceCategory
    private lateinit var catCorrections: PreferenceCategory
    private lateinit var catSearchResult: PreferenceCategory
    // ---- 操作 ----
    private lateinit var pRunNow: Preference
    private lateinit var pWipe: Preference

    /**
     * 跳引擎自带的「自定义短语」编辑器、以及删除词条后热重载，
     * 都需要一条 fcitx 连接 —— 和上游那些编辑器用同一个 ViewModel。
     */
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {

            addCategory("今天") {
                pTodayChars = info("字数", "—")
                pTodayEvents = info("提交", "—")
                pCodeLen = info("平均码长", "—")
                pFirstHit = info("首选命中率", "—")
                pSpeed = info("速度", "—")
                pPaged = info("翻页率", "—")
            }
            addCategory("累计") {
                pTotalChars = info("字数", "—")
                pTotalEvents = info("提交", "—")
            }
            addCategory("学习状态") {
                pLearnState = info("状态", "—")
                pWhy = info("当前判断", "—")
                pLastRun = info("上次整理", "—")
                pSamples = info("样本", "—")
                pArchived = info("归档", "—")
            }
            addCategory("隐私保护") {
                pLevels = info("分级", "—")
            }

            catWords = PreferenceCategory(context).apply { setTitle("最常用的词") }
            addPreference(catWords)

            // ============ 【D-2 / D-3】词库 · 屏蔽 · 纠错 ============

            // ① 我的词库
            //    「搜索」在我们这儿做；「管理」跳引擎自带的编辑器 ——
            //    上游那套编辑器其实是完整的（新增 / 修改 / 删除 / 停用），
            //    唯一缺的就是**没有任何入口**。我们只补入口，不重复造一个。
            addCategory("词库") {
                Preference(context).apply {
                    setup("搜索", "")
                    setOnPreferenceClickListener { askSearch(); true }
                }.also { addPreference(it) }
                Preference(context).apply {
                    setup("编辑", "")
                    setOnPreferenceClickListener {
                        navigateWithAnim(SettingsRoute.PinyinCustomPhrase)
                        true
                    }
                }.also { addPreference(it) }
                // 【№5 合并开源词库】上游那一页其实是**完整的**：
                //   列表 / 导入（.dict · .scel · .txt）/ 启停 / 删除 / 批量删
                //   + `libscel2org5.so` 搜狗转换 + 离开页面时 `reloadDict()` 让引擎重载。
                //   连 manifest 的 ACTION_VIEW 都配好了（文件管理器里"用 fainput 打开"就能导）。
                //   缺的只有**入口** —— 原来它埋在「拼音 → 配置 → 词典管理」里。
                Preference(context).apply {
                    setup("导入词库", "")
                    setOnPreferenceClickListener {
                        navigateWithAnim(SettingsRoute.PinyinDict())
                        true
                    }
                }.also { addPreference(it) }
            }

            catSearchResult = PreferenceCategory(context).apply { setTitle("搜索结果") }
            addPreference(catSearchResult)

            // ② 被「暂时不要推荐」屏蔽的词
            //    恢复入口按用户拍板放这里 —— **不放长按菜单**：
            //    长按只做"立即动作"，恢复属于管理。
            catBlocked = PreferenceCategory(context).apply { setTitle("暂时屏蔽的词") }
            addPreference(catBlocked)

            // ③ 纠错建议 —— 只提示，用户点了才写进词库
            catCorrections = PreferenceCategory(context).apply { setTitle("纠错建议") }
            addPreference(catCorrections)

            // 初始渲染（读的都是 SharedPreferences，同步、不碰数据库）
            renderBlocked()
            renderCorrections()

            addCategory("操作") {
                // 【fainput / D-1'】候选智能排序的总开关。
                // 为什么必须给开关：这是唯一一处**会改变用户看到的东西**的功能，
                // 用户必须能一句话关掉它。关掉 = 完全回到引擎原序。
                fun rerankText(): String =
                    if (CandidateReranker.isEnabled) "开 · ${CandidateReranker.learnedWordCount} 词" else "关"

                val pRerank = Preference(context).apply {
                    setup("候选排序", rerankText())
                    setOnPreferenceClickListener {
                        CandidateReranker.setEnabled(!CandidateReranker.isEnabled)
                        summary = rerankText()
                        true
                    }
                }
                addPreference(pRerank)

                pRunNow = Preference(context).apply {
                    setup("立即整理", "")
                    setOnPreferenceClickListener {
                        InsightMaintenance.forceRun()
                        true
                    }
                }
                addPreference(pRunNow)

                pWipe = Preference(context).apply {
                    setup("清空数据", "")
                    setOnPreferenceClickListener { confirmWipe(); true }
                }
                addPreference(pWipe)
            }
        }
    }

    /** 纯展示行：不可点，避免用户以为能按。 */
    private fun PreferenceCategory.info(title: String, summary: String): Preference {
        val p = Preference(context).apply {
            setup(title, summary)
            isSelectable = false
        }
        addPreference(p)
        return p
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dao = InsightRecorder.daoOrNull ?: run {
            catWords.addPreference("数据层还没就绪", "请先启用一次输入法")
            return
        }
        val dayStart = todayStart()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // ★ 这三条是"实时"的来源：数据库一变就重新发射
                launch { dao.todayStatsFlow(dayStart).collect { renderToday(it) } }
                launch { dao.totalStatsFlow().collect { renderTotal(it) } }
                launch { dao.topWordsFlow(30).collect { renderWords(it) } }
                launch { dao.levelCountsFlow().collect { renderLevels(it) } }

                // 设备状态不是数据库内容（充电 / 熄屏 / CPU 频率），定时读
                launch {
                    while (true) {
                        renderTraining()
                        delay(1500)
                    }
                }
            }
        }
    }

    // ==================== 渲染 ====================

    private fun renderToday(s: StatsProjection) {
        pTodayChars.summary = "${s.chars} 字"
        pTodayEvents.summary = "${s.events} 次"

        pCodeLen.summary = if (s.chars > 0) {
            String.format(Locale.US, "%.2f 字母/字  (共 %d 字母)", s.preeditChars.toDouble() / s.chars, s.preeditChars)
        } else "—"

        pFirstHit.summary = if (s.candidateSamples > 0) {
            String.format(
                Locale.US, "%.1f%%  (%d / %d)",
                100.0 * s.candidateHits / s.candidateSamples, s.candidateHits, s.candidateSamples
            )
        } else "暂无"

        pSpeed.summary = if (s.durationMs > 0) {
            String.format(Locale.US, "%.0f 字/分钟", s.chars * 60_000.0 / s.durationMs)
        } else "—"

        pPaged.summary = if (s.events > 0) {
            String.format(Locale.US, "%.1f%%  (%d / %d)", 100.0 * s.pageTurns / s.events, s.pageTurns, s.events)
        } else "—"
    }

    private fun renderTotal(s: StatsProjection) {
        pTotalChars.summary = "${s.chars} 字"
        pTotalEvents.summary = "${s.events} 次"
    }

    private fun renderLevels(list: List<LevelCount>) {
        if (list.isEmpty()) {
            pLevels.summary = "还没有数据"
            return
        }
        val by = list.associate { it.level to it.count }
        val plain = by[1] ?: 0
        val hashed = by[2] ?: 0
        val only = by[3] ?: 0
        pLevels.summary = buildString {
            append("明文 $plain 条")
            if (hashed > 0) append(" · 只存哈希 $hashed 条")
            if (only > 0) append(" · 只记计数 $only 条")
        }
    }

    private fun renderWords(words: List<WordStatEntity>) {
        catWords.removeAll()
        if (words.isEmpty()) {
            catWords.addPreference("还没有数据", "打几个字就会出现在这里")
            return
        }
        words.forEach { w ->
            val avg = if (w.indexCount > 0)
                String.format(Locale.US, "%.1f", w.indexSum.toDouble() / w.indexCount)
            else "—"
            // Fragment.context 是 Context?（可空），Preference 要非空 —— 用 requireContext()
            // 这里是 STARTED 状态下的订阅，Fragment 一定已附着，所以不会抛。
            val p = Preference(requireContext()).apply {
                setup("${w.word}   ×${w.count}", "平均选到第 $avg 个候选")
                isSelectable = false
            }
            catWords.addPreference(p)
        }
    }

    /**
     * 学习状态 —— **本页最有用的一段**。
     *
     * 它回答的是一句话：「你到底在不在学我？」
     */
    private suspend fun renderTraining() {
        val dao = InsightRecorder.daoOrNull ?: return
        val now = System.currentTimeMillis()
        val running = InsightMaintenance.isRunning()
        val last = InsightMaintenance.lastRunAt()
        val charging = DeviceState.isCharging()
        val screenOn = DeviceState.isScreenOn()
        val cpu = DeviceState.cpuBusyRatio()

        pLearnState.summary = when {
            running -> "🟢 正在整理"
            last == 0L -> "⚪ 还没跑过"
            else -> "🟡 待机"
        }

        pWhy.summary = when {
            running -> "正在跑，稍等"
            charging -> "在充电 → 随时可以跑"
            !screenOn -> "屏幕已关 → 可以跑"
            cpu < 0f -> "读不到 CPU 频率 → 只在充电 / 熄屏时跑"
            cpu < DeviceState.CPU_BUSY_THRESHOLD ->
                String.format(Locale.US, "CPU 空闲（%.0f%%）→ 可以跑", cpu * 100)
            else -> String.format(
                Locale.US, "CPU 忙（%.0f%% > %.0f%%）→ 暂不打扰",
                cpu * 100, DeviceState.CPU_BUSY_THRESHOLD * 100
            )
        }

        pLastRun.summary = if (last == 0L) "从未" else ago(now - last)

        runCatching {
            pSamples.summary = "${dao.eventCount()} 条"
            pArchived.summary = "${dao.dailyStatCount()} 天"
        }
    }

    private fun ago(delta: Long): String = when {
        delta < 60_000L -> "刚刚"
        delta < 3600_000L -> "${delta / 60_000L} 分钟前"
        delta < 86_400_000L -> "${delta / 3600_000L} 小时前"
        else -> "${delta / 86_400_000L} 天前"
    }

    // ==================== 【D-2 / D-3】词库 · 屏蔽 · 纠错 ====================

    /** 只读行：不可点，避免用户以为能按。 */
    private fun plain(title: String, summary: String): Preference =
        Preference(requireContext()).apply {
            setup(title, summary)
            isSelectable = false
        }

    // ---- 暂时屏蔽的词 ----

    private fun renderBlocked() {
        catBlocked.removeAll()
        val list = CandidateReranker.suppressedWords()
        if (list.isEmpty()) {
            catBlocked.addPreference(plain("暂无", ""))
            return
        }
        catBlocked.addPreference(Preference(requireContext()).apply {
            setup("全部恢复", "")
            setOnPreferenceClickListener {
                CandidateReranker.clearSuppressed()
                renderBlocked()
                true
            }
        })
        val now = System.currentTimeMillis()
        list.forEach { (word, until) ->
            catBlocked.addPreference(Preference(requireContext()).apply {
                val h = (until - now).coerceAtLeast(0L) / 3600_000L
                setup(word, "${h} 小时后恢复")
                setOnPreferenceClickListener {
                    CandidateReranker.unsuppress(word)
                    renderBlocked()
                    true
                }
            })
        }
    }

    // ---- 纠错建议 ----

    private fun renderCorrections() {
        catCorrections.removeAll()
        val list = PersonalDictionary.pendingCorrections()
        if (list.isEmpty()) {
            catCorrections.addPreference(plain("暂无", ""))
            return
        }
        list.take(20).forEach { c ->
            catCorrections.addPreference(Preference(requireContext()).apply {
                setup("「${c.wrong}」→「${c.right}」", "${c.code} · ${c.count} 次")
                setOnPreferenceClickListener { askCorrection(c); true }
            })
        }
    }

    private fun askCorrection(c: PersonalDictionary.Correction) {
        AlertDialog.Builder(requireContext())
            .setTitle("记住这个改法？")
            .setMessage("以后打「${c.code}」，把「${c.right}」放最前面。")
            .setNeutralButton("取消", null)
            .setNegativeButton("忽略") { _, _ ->
                PersonalDictionary.dismissCorrection(c)
                renderCorrections()
            }
            .setPositiveButton("记住") { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        viewModel.fcitx.runOnReady { PersonalDictionary.acceptCorrection(this, c) }
                    }
                    renderCorrections()
                }
            }
            .show()
    }

    // ---- 搜索词库 ----

    private fun askSearch() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "拼音码或词"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(ctx)
            .setTitle("搜索我的词库")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("搜索") { _, _ -> renderSearch(input.text.toString()) }
            .show()
    }

    private fun renderSearch(keyword: String) {
        catSearchResult.removeAll()
        val kw = keyword.trim()
        catSearchResult.setTitle(if (kw.isEmpty()) "搜索结果" else "搜索「$kw」")
        val hits = PersonalDictionary.search(kw)
        if (hits.isEmpty()) {
            catSearchResult.addPreference(plain("无匹配", ""))
            return
        }
        catSearchResult.addPreference(plain("${hits.size} 条", ""))
        hits.take(50).forEach { e ->
            catSearchResult.addPreference(Preference(requireContext()).apply {
                setup(e.value, "码 ${e.code} · 序号 ${e.order}")
                setOnPreferenceClickListener { confirmDeleteEntry(e); true }
            })
        }
    }

    private fun confirmDeleteEntry(e: PersonalDictionary.Entry) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除「${e.value}」？")
            .setMessage("移除码 ${e.code} 这一条。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        viewModel.fcitx.runOnReady { PersonalDictionary.removeEntry(this, e) }
                    }
                    catSearchResult.removeAll()
                    catSearchResult.addPreference(plain("已删除", ""))
                }
            }
            .show()
    }

    // ==================== 操作 ====================

    private fun confirmWipe() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空全部数据？")
            .setMessage("这会删除所有输入记录、词频和归档，且不可恢复。\n\n输入法设置不会被动。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                val dao = InsightRecorder.daoOrNull ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        dao.wipeEvents()
                        dao.wipeWords()
                        dao.wipeSessions()
                        // 【M·L1】搭配表 + 内存里那份续词加权一起清 ——
                        // 否则清空之后，重排还会按旧搭配提前候选。
                        dao.wipeBigrams()
                        BigramModel.clear()
                    }
                }
            }
            .show()
    }

    /** 今日零点（本地时区） */
    private fun todayStart(): Long = Calendar.getInstance().run {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }
}