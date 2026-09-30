/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.insight.db.DailyStatEntity
import org.fcitx.fcitx5.android.data.insight.db.InsightDao
import org.fcitx.fcitx5.android.data.insight.db.InsightDatabase
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.util.Calendar

/**
 * 后台维护 —— 目前只做一件事：**存储分层归档**。
 *
 * ## 为什么不用 WorkManager
 *
 * WorkManager 会引入一个新依赖，且它的调度精度对"每天归档一次"来说过剩。
 * 这里改成**机会式触发**：在若干自然时机被叫一下，内部自己判断该不该跑。
 *
 * 时机（全部由 [trigger] 触发）：
 * - App/输入法进程启动
 * - 屏幕关闭
 * - 接入电源
 *
 * **够用** —— 因为如果用户根本没用键盘，就没有新数据要归档。
 *
 * ## 不打扰
 *
 * 每次跑之前先过 [DeviceState.canRunNow]：
 * 充电 / 熄屏 直接放行；亮屏未充电时要求 CPU 不忙。
 */
object InsightMaintenance {

    /** 热表保留天数，更早的归档进冷表 */
    private const val RETENTION_DAYS = 90

    /** 两次维护的最小间隔 —— 防抖，避免频繁触发 */
    private const val MIN_INTERVAL_MS = 6 * 3600_000L

    /** 每天快照多少个高频词 */
    private const val WORDS_PER_DAY = 50

    private const val PREFS_NAME = "fainput_insight"
    private const val KEY_LAST_RUN = "maintenance_last_run"

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("InsightMaintenance"))

    @Volatile
    private var database: InsightDatabase? = null

    @Volatile
    private var dao: InsightDao? = null

    @Volatile
    private var running = false

    fun init(db: InsightDatabase, d: InsightDao) {
        database = db
        dao = d
    }

    /**
     * 叫一下维护器。**立即返回**，实际工作在线程池里跑。
     *
     * @param reason 只用于日志，方便排查"为什么没跑"
     */
    fun trigger(reason: String) {
        if (running) return
        val d = dao ?: return
        scope.launch {
            try {
                if (!shouldRunNow()) return@launch
                runOnce(d, reason)
            } catch (e: Exception) {
                // 维护失败不影响打字，也不影响已采集的数据
                Timber.w(e, "[insight] maintenance failed ($reason)")
            }
        }
    }

    /**
     * 手动强制跑一次 —— **忽略节流与设备状态**。
     *
     * 由「设置 → 输入数据 → 立即整理」按钮调用。
     *
     * 绕开闸门的方式是**直接调 [runOnce]**（不经过 [trigger] 里的
     * `shouldRunNow()`），而不是靠某个 `force` 参数 ——
     * 那个参数从来没被读过，已删除。
     */
    fun forceRun() {
        val d = dao ?: return
        scope.launch {
            runCatching { runOnce(d, "manual") }
                .onFailure { Timber.w(it, "[insight] forced maintenance failed") }
        }
    }

    // ==================== 给 UI 的只读状态 ====================

    /** 上次维护完成的时间戳（0 = 从未跑过）。 */
    fun lastRunAt(): Long =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_RUN, 0L)

    /** 现在是否正在跑维护。 */
    fun isRunning(): Boolean = running

    /** 两次维护之间的最小间隔（毫秒）—— UI 用来算"下次大概什么时候"。 */
    fun minIntervalMs(): Long = MIN_INTERVAL_MS

    /** 热表保留天数 —— UI 用来解释"为什么这里没有更早的数据"。 */
    fun retentionDays(): Int = RETENTION_DAYS

    // ==================== 【ABCD / D 层】叙述（已删除）====================
    //
    // 2026-09-30 用户决定**去掉大模型**（原话「那不要这个了」），
    // 这一整段跟着删了。
    //
    // 它原来做的是：把「学过 N 个词、M 对搭配、档位 X」交给 0.5B 模型，
    // 让它写一句「习惯总结」显示在「输入数据」页。
    //
    // 删掉的理由：
    //   · 为了这一句话，APK 里要常驻 **3.3 MB** 的 `libnative-llm.so`
    //   · 每次 CI 还要多拉一遍 llama.cpp 源码（拖慢构建）
    //   · 而它**不在打字路径上** —— 对输入体验零贡献
    //
    // ⚠️ **删掉的只是「叙述」。D 层的另一半（归档 / 长新词 / 算画像）
    //    全是确定性算法，一行没动** —— 四层架构的反馈环依然完整。
    //
    // `lastWords` / `lastPairs` 也一起删了 —— 它们只服务于叙述。

    // ==================== 内部 ====================

    private fun shouldRunNow(): Boolean {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_RUN, 0L)
        val now = System.currentTimeMillis()
        if (now - last < MIN_INTERVAL_MS) return false
        // 归档是轻任务：充电/熄屏放行，亮屏时看 CPU 忙不忙
        if (!DeviceState.canRunNow(heavy = false)) {
            Timber.d("[insight] maintenance skipped: device busy")
            return false
        }
        return true
    }

    /**
     * 真正干活的。
     *
     * ⚠️ **节流（6 小时）和设备状态检查不在这个函数里** —— 由调用方负责：
     *
     * | 入口 | 闸门 |
     * |---|---|
     * | [trigger]  | **有**（`shouldRunNow()`）—— 自动触发走这条 |
     * | [forceRun] | **无** —— 用户点「立即整理」走这条 |
     *
     * 所以这里只防**重入**，不防"跑得太勤"。
     * （原先有个 `force: Boolean` 参数想表达这件事，但它从没被读过 —— 已删。）
     */
    private suspend fun runOnce(d: InsightDao, reason: String) {
        if (running) return
        running = true
        try {
            val now = System.currentTimeMillis()
            val cutoff = now - RETENTION_DAYS * 24 * 3600_000L

            val pending = d.countEventsBefore(cutoff)
            Timber.i("[insight] maintenance($reason) 开始：待归档 $pending 条")

            if (pending > 0) {
                val aggregates = d.aggregateBefore(cutoff)
                val rows = ArrayList<DailyStatEntity>(aggregates.size)
                for (a in aggregates) {
                    val (dayStart, dayEnd) = dayRange(a.day)
                    val words = d.topWordsBetween(dayStart, dayEnd, WORDS_PER_DAY)
                        .joinToString("|") { "${escape(it.word)}:${it.count}" }
                    rows += DailyStatEntity(
                        day = a.day,
                        events = a.events,
                        chars = a.chars,
                        keys = a.keys,
                        preeditChars = a.preeditChars,
                        durationMs = a.durationMs,
                        candidateHits = a.candidateHits,
                        candidateSamples = a.candidateSamples,
                        pageTurns = a.pageTurns,
                        topWords = words,
                        archivedAt = now,
                    )
                }
                d.insertDailyStats(rows)
                val deleted = d.deleteEventsBefore(cutoff)
                Timber.i("[insight] maintenance($reason) 完成：归档 ${rows.size} 天 / 删除 $deleted 条")

                // 让文件真正缩小。VACUUM 不能在事务里跑，失败也无所谓 ——
                // SQLite 会复用释放出来的页，所以即使不 VACUUM 也不会继续涨。
                runCatching {
                    database?.openHelper?.writableDatabase?.execSQL("VACUUM")
                }.onFailure { Timber.d(it, "[insight] vacuum skipped") }
            }

            // 【M·L3】后台整理：从你**反复打的词搭配**里长出新词。
            // 这里**只标脏、不写盘** —— 写盘和引擎热重载交给 D-2 那条既有通道
            // （`FcitxInputMethodService` 提交时调 publishIfNeeded，带 20 秒节流）。
            // 放在归档之后：搭配表不受 90 天保留期影响，什么时候整理都可以。
            runCatching { PersonalDictionary.organize(d) }
                .onSuccess { if (it > 0) Timber.i("[insight] L3 整理：新词 %d 条", it) }
                .onFailure { Timber.w(it, "[insight] L3 整理失败") }

            // 【ABCD / D 层】算**用户画像** —— 四层互联的**唯一写者**。
            //
            // 它读的是「现在有多少词 / 多少搭配」，算出的档位决定
            // A/B/C 三层的权重与边界（见 `UserProfile.derive`）。
            // 这就是那条反馈环：**数据越长 → 画像越敢动 → 候选栏越像你**。
            //
            // 放在最后：前面刚做完归档和整理，此刻的数据是最新的。
            runCatching {
                val words = d.wordCount()
                val pairs = d.bigramCount()
                // ⚠️ 原来这里还有两行 `lastWords = words` / `lastPairs = pairs`，
                // 那是给**已删除的叙述**当输入的。叙述删了，字段也删了，
                // 所以这两行一起去掉（留着会 Unresolved reference）。
                UserProfile.publish(UserProfile.derive(words, pairs, System.currentTimeMillis()))
            }.onFailure { Timber.w(it, "[insight] 画像计算失败") }

            // 【ABCD / D 层】**叙述层已删除**（大模型那条链整体拆掉）。
            // 见文件上方「叙述（已删除）」那段注释。
            // D 层现在只做**确定性**的部分：归档 / 长新词 / 算画像。

            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_RUN, now)
                .apply()
        } finally {
            running = false
        }
    }

    /**
     * `yyyyMMdd` → 当天 `[00:00, 次日00:00)` 的毫秒区间。
     *
     * 用 [Calendar] 而不是自己算 —— 闰年、夏令时、时区都交给它。
     */
    private fun dayRange(day: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(day / 10000, day / 100 % 100 - 1, day % 100)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to cal.timeInMillis
    }

    /** 词里可能有 `:` 或 `|`，快照格式用它们做分隔符，所以先转义。 */
    private fun escape(word: String): String =
        word.replace("\\", "\\\\").replace(":", "\\c").replace("|", "\\p")
}