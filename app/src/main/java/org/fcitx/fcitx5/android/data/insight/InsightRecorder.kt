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
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
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

    /**
     * 【D-2】当前这次拼音串**本身**（不只是长度）。
     *
     * 为什么要留它：D-2 要把「用户打的拼音码 → 最终上屏的词」喂给个人词库。
     * 只存长度的话这个映射永远建不起来 —— 而没有它，
     * 引擎就永远学不会你的名字 / 术语 / 新词。
     *
     * 只保留**最近一次非空**的串：提交后引擎会再发一个空 preedit，
     * 直接赋值会把刚打出来的码冲掉。
     */
    @Volatile
    private var preeditText: String = ""

    /** 【D-3】上一次提交，用来判断「打完又删重打」。 */
    @Volatile
    private var lastCommitCode: String = ""

    @Volatile
    private var lastCommitWord: String = ""

    @Volatile
    private var lastCommitAtMs: Long = 0L

    /** 【D-3】刚被撤销掉的那次提交（撤销窗口内有效）。 */
    @Volatile
    private var undoneCode: String = ""

    @Volatile
    private var undoneWord: String = ""

    /** 【D-3】提交后多久内的删除算「我打错了」。 */
    private const val UNDO_WINDOW_MS = 3000L

    // ==================== 生命周期 ====================

    fun init(context: Context) {
        if (db != null) return
        val database = Room
            .databaseBuilder(context.applicationContext, InsightDatabase::class.java, "insight")
            // ★ 必须挂迁移：v1 → v2 加了 daily_stat（存储分层的冷表）。
            //   不加的话 Room 会抛异常，加了 destructive 就会清空用户数据 ——
            //   而"数据不能丢"正是这个项目存在的理由。
            .addMigrations(*InsightDatabase.MIGRATIONS)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        db = database
        val d = database.insightDao()
        dao = d
        Timber.i("[insight] database ready")
        // 存储分层归档器（机会式触发，详见 InsightMaintenance）
        InsightMaintenance.init(database, d)
        InsightMaintenance.trigger("app-start")
    }

    val isReady: Boolean
        get() = dao != null

    /**
     * 供 UI（C 阶段「输入数据」页）**只读**使用。
     *
     * 为什么只暴露读：所有写入都必须走本对象，才能保证
     * 「先分级、再落库」这条铁律不被绕过。
     */
    val daoOrNull: InsightDao?
        get() = dao

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

    /**
     * 上游在 KeyEvent 分支里**单独处理**的四个导航键 ——
     * 它们不算"直通提交"，不能当成字符记下来。
     *
     * ⚠️ 这个列表必须和 `FcitxInputMethodService.handleFcitxEvent` 里的
     *    `when (it.sym.sym)` 保持一致，否则会把退格/回车当成普通字符记进库。
     */
    private fun isNavKey(sym: Int): Boolean = when (sym) {
        FcitxKeyMapping.FcitxKey_BackSpace,
        FcitxKeyMapping.FcitxKey_Return,
        FcitxKeyMapping.FcitxKey_Left,
        FcitxKeyMapping.FcitxKey_Right -> true
        else -> false
    }

    /**
     * 【落库唯一入口】任何上屏的文本都会经过这里。
     *
     * 由 `FcitxInputMethodService.commitText()` 调用 ——
     * 那是所有上屏路径的公共收口：
     * 引擎提交 · 直通提交 · 符号页 CommitAction · 剪贴板 · 表情面板。
     *
     * 为什么不再分散在各事件分支里：`183&&@&@7` 整串丢失的教训 ——
     * 符号页的提交既不产生 KeyEvent 也不产生 CommitStringEvent，
     * 事件分支根本看不见它。
     */
    fun onTextCommitted(text: String) {
        if (!isReady || text.isEmpty()) return
        record(text)
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
                val d = event.data
                if (d.states.virtual && !d.up && d.unicode > 0 && !isNavKey(d.sym.sym)) {
                    // ★★ 关键补漏 ★★
                    //
                    // 引擎**没消费**这个键时，上游会用
                    //     commitText(Character.toString(it.unicode))
                    // **直接提交** —— 它根本不会发 CommitStringEvent。
                    //
                    // 后果（实测）：用户打的 `1263826%#*@&...` 和 `-361816+9/ *8-60`
                    // **一个字都没进库**，整整漏掉 40 个字符。
                    //
                    // 所以在这里补一刀，把直通提交也当成一次提交记下来。
                    // ⚠️ 这里**不再落库** —— 落库统一挪到
                    //    `FcitxInputMethodService.commitText()`（唯一出口）。
                    //    原因见 onTextCommitted 的注释。
                    tap("Key:direct")
                    return
                }
                // 只算按下，不然一次按键算两遍。
                //
                // ⚠️ 注意语义：**拼音输入的键被引擎消费掉，根本不会到这里**。
                //    实测拼音连续输入时 keyCount 恒为 0。
                //    所以「码长」必须看 preeditLength，不能看 keyCount。
                //    keyCount 只反映"引擎放行的键"。
                if (!d.up) keyCount++
                tap(if (d.states.virtual) "Key:virtual" else "Key:physical")
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
                // 【D-2】顺手把拼音串本身也留下来 —— 「码 → 词」的映射靠它。
                // 同样只在非空时覆盖，理由和上面完全一样。
                if (len > 0) {
                    val t = event.data.preedit.strings.joinToString("")
                    if (t.isNotEmpty()) preeditText = t
                }
                tap("InputPanel")
            }

            is FcitxEvent.ClientPreeditEvent -> {
                val len = event.data.strings.sumOf { it.length }
                if (len > preeditLength) preeditLength = len
                tap("ClientPreedit")
            }

            // ---- 候选 ----
            is FcitxEvent.CandidateListEvent -> {
                // ★ 实测修正：fcitx5-android **只发 CandidateList，不发 PagedCandidate**
                //   （探针 tap={PagedCandidate=0} 已证实）。
                //   所以候选页的真正来源在这里，不是 PagedCandidate。
                //
                //   用 candidates.size 而不是 total ——
                //   total 是"引擎能提供的候选总数"（实测「你好」= 606），
                //   真实但对"好不好选"毫无意义；
                //   candidates.size 才是"用户这一页看到几个"。
                val list = event.data.candidates
                if (list.isNotEmpty()) {
                    candidateCount = list.size
                    // 存下来，提交时反查"用户选了第几个"
                    lastCandidates = list.map { it.text }
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

            is FcitxEvent.DeleteSurroundingEvent -> {
                tap("DeleteSurrounding")
                // 【D-3】「打完又删」= 我打错了。
                // 这里只**标记**，要等下一次提交才能凑成 (码, 错词, 对词)。
                val t = System.currentTimeMillis()
                if (lastCommitWord.isNotEmpty() && t - lastCommitAtMs <= UNDO_WINDOW_MS) {
                    undoneCode = lastCommitCode
                    undoneWord = lastCommitWord
                }
            }
            is FcitxEvent.IMChangeEvent -> tap("IMChange")
            is FcitxEvent.StatusAreaEvent -> tap("StatusArea")

            is FcitxEvent.CommitStringEvent -> {
                // 不在这里落库 —— 上游拿到它之后会调 `commitText(...)`，
                // 落库统一在那边做（唯一出口，见 onTextCommitted）。
                tap("CommitString")
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
        // 【D-2】这次用到的拼音码也要在重置前快照下来
        val snapshotCode = preeditText
        // 【D-2】引擎**这次有没有给出这个词** —— 决定要不要写进词库。
        // 必须在这里快照：下面 resetCommitState() 会把 lastCandidates 清空。
        val snapshotOfferedByEngine = lastCandidates.contains(text)
        // 【D-3】取出「刚被撤销的那次提交」，取完就清 —— 只用一次
        val undoneCodeSnapshot = undoneCode
        val undoneWordSnapshot = undoneWord
        undoneCode = ""
        undoneWord = ""
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
                        // 【D-2】喂给个人词库：「这次打的拼音码 → 上屏的词」。
                        // 只累积不写盘 —— 要攒够次数才够格进引擎词库。
                        PersonalDictionary.observe(
                            snapshotCode, text,
                            offeredByEngine = snapshotOfferedByEngine
                        )
                        // 【D-3】上一次提交刚被删掉、这次出的是别的词 →
                        // 记一条纠错对。**只记，不自动改词库**：
                        // 由用户在界面上决定要不要采纳（用户原话：提示用户要不要纠错）。
                        if (undoneWordSnapshot.isNotEmpty() && undoneWordSnapshot != text) {
                            PersonalDictionary.observeCorrection(
                                undoneCodeSnapshot, undoneWordSnapshot, text
                            )
                        }
                    }
                    // 【D-3】记下这一次，供下一轮判断「打完又删」
                    lastCommitCode = snapshotCode
                    lastCommitWord = text
                    lastCommitAtMs = now
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
        // 长度 1 的不进词表：直通提交会把单个数字/符号/字母刷进来，
        // 一串密码般的符号就能产生几十行噪音。
        // 词表要的是"词"，不是字符。
        if (word.length < 2 || word.length > MAX_WORD_LENGTH) return
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