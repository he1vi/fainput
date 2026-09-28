/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.insight.db.InputEventEntity
import org.fcitx.fcitx5.android.data.insight.db.InsightDao
import org.fcitx.fcitx5.android.data.insight.db.InsightDatabase
import org.fcitx.fcitx5.android.data.insight.db.SessionEntity
import org.fcitx.fcitx5.android.data.insight.db.WordStatEntity
import timber.log.Timber

/**
 * 输入行为采集器 —— 整个 fainput 数据层的入口。
 *
 * ## 设计铁律
 *
 * 1. **旁路，不在主路上。** 所有 DB 写入都在 [Dispatchers.IO] 上异步做，
 *    打字路径上一个字节都不等它。采集失败只是丢一条样本，绝不能影响输入。
 * 2. **先分级，再落库。** 见 [InsightLevel]。
 * 3. **没有会话就不写。** 拿不到 EditorInfo 时按最保守处理（L3）。
 *
 * ## 数据流
 *
 * ```
 * FcitxInputMethodService.handleFcitxEvent()
 *         │
 *         ├─ KeyEvent            → keyCount++           （代价）
 *         ├─ ClientPreeditEvent  → preeditLength        （码长）
 *         ├─ PagedCandidateEvent → candidateIndex/翻页  （好不好选）
 *         │
 *         └─ CommitStringEvent   → ★ 落库一条 input_event
 *                                     + 更新 word_stat
 * ```
 *
 * ## 为什么在 CommitStringEvent 落库而不是 KeyEvent
 *
 * 按键是"过程"，提交是"结果"。用户真正关心的是**我打了什么、费了多少劲**，
 * 而不是按了哪些键。而且按键量级大 10~50 倍，全存会让库迅速膨胀。
 */
object InsightRecorder : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {

    /** 距上次提交超过这个间隔，就当作新会话。 */
    private const val SESSION_GAP_MS = 5 * 60 * 1000L

    /** 超过这个长度的提交不进词表（当句子处理）。 */
    private const val MAX_WORD_LENGTH = 32

    private var db: InsightDatabase? = null
    private var dao: InsightDao? = null

    private val writeMutex = Mutex()

    // ==================== 会话状态 ====================

    @Volatile
    private var sessionId: Long = -1L

    @Volatile
    private var pendingPkg: String = "unknown"

    @Volatile
    private var pendingInputClass: Int = 0

    @Volatile
    private var pendingInputVariation: Int = 0

    /** 默认 true —— 在拿到 EditorInfo 之前一律按敏感处理。 */
    @Volatile
    private var pendingSensitive: Boolean = true

    @Volatile
    private var sessionStart: Long = 0L

    // ==================== 单次提交之间的累积量 ====================

    private var preeditLength = 0
    private var keyCount = 0
    private var candidateCount = 0
    private var candidateIndex = -1
    private var pageTurns = 0
    private var lastPageFirstCandidate: String? = null
    private var lastCommitAt = 0L

    /**
     * 当前这一页候选词。
     *
     * 用途：提交时反查「用户选的是第几个候选」。
     * `PagedCandidateEvent.cursorIndex` 是**高亮**索引，用户用空格/数字键选词时
     * 根本没有高亮，恒为 -1 —— 所以只能靠提交文本去列表里找。
     */
    private var lastCandidates: List<String> = emptyList()

    /** 本提交周期内各事件出现的次数，用来诊断采集盲区。 */
    private val eventTap = HashMap<String, Int>()

    // ==================== 生命周期 ====================

    fun init(context: Context) {
        if (db != null) return
        val database = Room
            .databaseBuilder(context.applicationContext, InsightDatabase::class.java, "insight")
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        db = database
        dao = database.insightDao()
        Timber.i("[insight] database ready")
    }

    val isReady: Boolean
        get() = dao != null

    /**
     * 输入框聚焦。由 `FcitxInputMethodService.onStartInput` 调用。
     */
    fun onStartInput(pkgName: String, editor: EditorInfo?) {
        if (!isReady) return
        closeSession()
        pendingPkg = pkgName.ifBlank { "unknown" }
        pendingInputClass = editor?.inputType?.and(0x0000000f) ?: 0
        pendingInputVariation = editor?.inputType?.and(0x00000ff0) ?: 0
        pendingSensitive = SensitiveClassifier.isSensitiveEditor(editor)
        sessionStart = System.currentTimeMillis()
        sessionId = -1L
        resetCommitState()
        lastCommitAt = sessionStart
    }

    /** 输入框失焦。 */
    fun onFinishInput() {
        closeSession()
    }

    private fun closeSession() {
        val id = sessionId
        val d = dao ?: return
        sessionId = -1L
        if (id <= 0) return
        val end = System.currentTimeMillis()
        launch {
            runCatching { d.closeSession(id, end) }
        }
    }

    private fun resetCommitState() {
        preeditLength = 0
        keyCount = 0
        candidateCount = 0
        candidateIndex = -1
        pageTurns = 0
        lastPageFirstCandidate = null
        lastCandidates = emptyList()
        eventTap.clear()
    }

    /**
     * 事件探针：本提交周期内各类事件出现了几次。
     *
     * 为什么需要它：实测 `keyCount` 大量为 0，说明有些事件根本没到我们手上。
     * **与其猜，不如把"到底来了哪些事件"记下来。**
     * 每次落库时打一行 logcat（tag `fainput`），打字后
     * `logcat -s fainput` 就能看清采集盲区在哪。
     */
    private fun tap(name: String) {
        eventTap[name] = (eventTap[name] ?: 0) + 1
    }

    // ==================== 事件入口 ====================

    /**
     * 由 `FcitxInputMethodService.handleFcitxEvent` 转发。
     *
     * **这个方法必须在主线程上跑得极快** —— 只做累加，不碰数据库。
     */
    fun onFcitxEvent(event: FcitxEvent<*>) {
        if (!isReady) return
        when (event) {
            is FcitxEvent.KeyEvent -> {
                // 只算按下，不然一次按键算两遍
                if (!event.data.up) keyCount++
                tap(if (event.data.states.virtual) "Key:virtual" else "Key:physical")
            }

            // ---- 预编辑（拼音串）----
            // ★ 关键修正：Android 输入法把预编辑显示在**自己的候选栏**上，
            //   所以 fcitx5 走的是 InputPanelEvent，而不是 ClientPreeditEvent
            //   （后者只在"客户端自己渲染预编辑"时才发）。
            //   实测数据里 preeditLength 全是 0，就是漏了这一路。
            is FcitxEvent.InputPanelEvent -> {
                val len = event.data.preedit.strings.sumOf { it.length }
                // 取本周期内的**最大值**：提交之后 fcitx5 会再发一个空 preedit，
                // 直接赋值会把刚打出来的码长冲掉。
                if (len > preeditLength) preeditLength = len
                tap("InputPanel")
            }

            is FcitxEvent.ClientPreeditEvent -> {
                val len = event.data.strings.sumOf { it.length }
                if (len > preeditLength) preeditLength = len
                tap("ClientPreedit")
            }

            // ---- 候选 ----
            is FcitxEvent.CandidateListEvent -> {
                // 只在 PagedCandidate 没来过时兜底。
                // ★ 用 candidates.size 而不是 total ——
                //   total 是"引擎能提供的候选总数"（实测「你好」= 606），
                //   那是真实数字但对"好不好选"毫无意义。
                if (candidateCount == 0 && event.data.candidates.isNotEmpty()) {
                    candidateCount = event.data.candidates.size
                }
                tap("CandidateList")
            }

            is FcitxEvent.PagedCandidateEvent -> {
                val d = event.data
                if (d.candidates.isNotEmpty()) {
                    candidateCount = d.candidates.size
                    lastCandidates = d.candidates.map { it.text }
                }
                // cursorIndex 是**高亮**索引。用户用空格/数字键选词时根本没有高亮，
                // 所以它恒为 -1 —— 实测数据全 -1 就是这个原因。
                // 真正"选了第几个"要在提交时用文本反查（见 record()）。
                candidateIndex = d.cursorIndex
                // 用「首页候选词是否变化」判断真的翻页了，避免重复计数
                val first = d.candidates.firstOrNull()?.text
                if (first != null && first != lastPageFirstCandidate) {
                    lastPageFirstCandidate = first
                    // hasPrev=true 表示当前不在第一页 —— 也就是用户往后翻过
                    if (d.hasPrev) pageTurns++
                }
                tap("PagedCandidate")
            }

            is FcitxEvent.DeleteSurroundingEvent -> tap("DeleteSurrounding")
            is FcitxEvent.IMChangeEvent -> tap("IMChange")
            is FcitxEvent.StatusAreaEvent -> tap("StatusArea")

            is FcitxEvent.CommitStringEvent -> {
                record(event.data.text)
            }

            else -> Unit
        }
    }

    // ==================== 落库 ====================

    private fun record(text: String) {
        val d = dao ?: return
        val now = System.currentTimeMillis()

        // ---- 先快照，再重置（顺序不能反） ----
        val snapshotPreedit = preeditLength
        val snapshotKeys = keyCount
        val snapshotCandidateCount = candidateCount
        val snapshotPageTurns = pageTurns
        // 候选序号：cursorIndex 只在"有高亮"时才有值，用空格/数字键选词时恒为 -1。
        // 所以拿提交的文本去当前候选页里反查 —— 这才是真正的"选了第几个"。
        //   0 = 首选命中；-1 = 找不到（整句提交 / 标点 / 英文等）
        val snapshotCandidateIndex = candidateIndex.takeIf { it >= 0 }
            ?: lastCandidates.indexOf(text).takeIf { it >= 0 }
            ?: -1
        val snapshotTap = eventTap.toMap()
        val snapshotPkg = pendingPkg
        val snapshotClass = pendingInputClass
        val snapshotVariation = pendingInputVariation
        val snapshotSensitive = pendingSensitive
        val snapshotSessionStart = sessionStart
        val prevCommitAt = lastCommitAt
        val duration = if (prevCommitAt > 0) now - prevCommitAt else 0
        val expired = prevCommitAt > 0 && duration > SESSION_GAP_MS

        lastCommitAt = now
        resetCommitState()

        // ---- 分级 ----
        val level = if (snapshotSensitive) {
            InsightLevel.COUNT_ONLY
        } else {
            SensitiveClassifier.classify(text)
        }

        launch {
            writeMutex.withLock {
                try {
                    if (expired) {
                        sessionId.takeIf { it > 0 }?.let { d.closeSession(it, prevCommitAt) }
                        sessionId = -1L
                    }
                    val sid = ensureSession(
                        d, snapshotPkg, snapshotClass, snapshotVariation,
                        snapshotSensitive, snapshotSessionStart
                    )
                    d.insertEvent(
                        InputEventEntity(
                            sessionId = sid,
                            timestamp = now,
                            level = level.code,
                            text = if (level == InsightLevel.PLAIN) text else null,
                            textHash = if (level == InsightLevel.HASHED) {
                                SensitiveClassifier.hash(text)
                            } else null,
                            textLength = text.length,
                            preeditLength = snapshotPreedit,
                            candidateIndex = snapshotCandidateIndex,
                            candidateCount = snapshotCandidateCount,
                            pageTurns = snapshotPageTurns,
                            keyCount = snapshotKeys,
                            durationMs = duration,
                        )
                    )
                    // 诊断日志（tag=fainput）：看清本次提交到底收到了哪些事件。
                    // **故意不打 text 本身** —— 日志也是数据，不该泄露内容。
                    Timber.i(
                        "[insight] level=%d len=%d preedit=%d keys=%d candIdx=%d candN=%d pages=%d dur=%dms tap=%s",
                        level.code, text.length, snapshotPreedit, snapshotKeys,
                        snapshotCandidateIndex, snapshotCandidateCount,
                        snapshotPageTurns, duration, snapshotTap
                    )
                    if (level == InsightLevel.PLAIN) {
                        bumpWord(d, text.trim(), now, snapshotCandidateIndex)
                    }
                } catch (e: Exception) {
                    // 采集失败只丢样本，绝不上抛
                    Timber.w(e, "[insight] failed to record commit")
                }
            }
        }
    }

    private suspend fun ensureSession(
        d: InsightDao,
        pkg: String,
        inputClass: Int,
        inputVariation: Int,
        sensitive: Boolean,
        start: Long,
    ): Long {
        sessionId.takeIf { it > 0 }?.let { return it }
        val id = d.insertSession(
            SessionEntity(
                startTime = start,
                endTime = start,
                pkgName = pkg,
                inputClass = inputClass,
                inputVariation = inputVariation,
                sensitive = sensitive,
            )
        )
        sessionId = id
        return id
    }

    private suspend fun bumpWord(d: InsightDao, word: String, now: Long, index: Int) {
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return
        val indexSum = if (index >= 0) index else 0
        val indexCount = if (index >= 0) 1 else 0
        val rowId = d.insertWord(
            WordStatEntity(
                word = word,
                count = 1,
                firstSeen = now,
                lastSeen = now,
                indexSum = indexSum,
                indexCount = indexCount,
            )
        )
        // IGNORE 策略下，已存在时返回 -1
        if (rowId == -1L) {
            d.bumpWord(word, now, indexSum, indexCount)
        }
    }

    // ==================== 供 UI / 导出使用 ====================

    suspend fun eventCount(): Int = dao?.eventCount() ?: 0

    suspend fun sessionCount(): Int = dao?.sessionCount() ?: 0

    suspend fun wordCount(): Int = dao?.wordCount() ?: 0

    suspend fun countByLevel(level: InsightLevel): Int = dao?.countByLevel(level.code) ?: 0

    suspend fun topWords(limit: Int = 100): List<WordStatEntity> =
        dao?.topWords(limit) ?: emptyList()

    suspend fun recentPlainEvents(limit: Int = 200): List<InputEventEntity> =
        dao?.recentPlainEvents(limit) ?: emptyList()

    suspend fun allEvents(): List<InputEventEntity> = dao?.allEvents() ?: emptyList()

    /** 一键清空 —— 原则 3 的一部分。 */
    suspend fun wipeAll() {
        val d = dao ?: return
        writeMutex.withLock {
            d.wipeEvents()
            d.wipeWords()
            d.wipeSessions()
        }
        sessionId = -1L
    }
}