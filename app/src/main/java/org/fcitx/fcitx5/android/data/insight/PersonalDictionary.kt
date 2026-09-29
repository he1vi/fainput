/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import android.content.SharedPreferences
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.data.EngineUserDir
import org.fcitx.fcitx5.android.core.reloadPinyinCustomPhrase
import org.fcitx.fcitx5.android.data.insight.db.InsightDao
import timber.log.Timber
import java.io.File

/**
 * 【D-2 / D-3】个人词库 —— 让**引擎自己**认识你的词。
 *
 * ## 和 D-1' 的区别（这是关键）
 *
 * | | D-1' 候选栏注入 | D-2 个人词库 |
 * |---|---|---|
 * | 能做什么 | 把**引擎已经给出**的候选提前 | 让引擎**从无到有**产出这个词 |
 * | 适用 | 「输入法」这种本来就在词典里的词 | 你的名字、术语、新词 |
 * | 载体 | 内存里的一次重排 | `customphrase` 文件 + 热重载 |
 *
 * ## 文件格式（`customphrase.cpp:87` 的解析器说了算）
 *
 * ```
 * 字母,序号=内容
 * ```
 * - **字母**：只允许 ASCII 字母，遇 `,` 结束
 * - **序号**：非 0 整数。**≤ 0 → 这个词直接不出现**；越小越靠前
 * - 引擎自己的「作为自订字词置顶」用的就是 `1`（`customphrase.cpp:500`）
 *
 * 所以我们写进去的也是 [PIN_ORDER] = 1，行为等价于**用户手动置顶**——
 * 已有实现可参照，不是我们发明的语义。
 *
 * ## D-3 纠错对：**只记，不自动写**
 *
 * 用户原话：「提示用户要不要进行单独纠错、修改啥的」。
 * 所以这里只把「打完又删重打」的配对攒起来，**等用户在界面里点采纳**才落盘。
 * 自动改用户词库是最容易让人反感的行为之一，不做。
 */
object PersonalDictionary {

    private const val PREFS = "fainput_personal"

    /** 词频键前缀：`c|<码>|<词>` → 出现次数 */
    private const val K_WORD = "c|"

    /** 纠错键前缀：`x|<码>|<错词>|<对词>` → 出现次数 */
    private const val K_CORR = "x|"

    /**
     * 写进文件的序号 —— **与引擎自己的「置顶」同值**。
     * 见 `fcitx5-chinese-addons/im/pinyin/customphrase.cpp:500`。
     */
    private const val PIN_ORDER = 1

    /** 同一个「码 → 词」见到几次才够格进词库。 */
    private const val MIN_CONFIRM = 3

    /**
     * **自动学习**路径的拼音码最小长度。
     *
     * 为什么必须有这道闸（来自 `WindInput` 记录的真实事故）：
     * `customphrase` 的「序号」是一条**绝对优先级轴** ——
     * 写 1 就等于引擎自己的「置顶」，会跨过权重轴把别的词全压下去。
     * 而短码（`de` / `shi` / `le`）对应的恰恰是**权重极高**的常用词，
     * 一旦被顶掉，用户会觉得"输入法坏了"。
     *
     * 文档原话：旧实现因为"用过就赢"的布尔闸门，
     * **只用过一次的「的样子」把权重 1.54e7 的「的」挤了下去**。
     *
     * ⇒ 短码一律交给 D-1' 的**位置提升**（有界、可预测），不走绝对轴。
     * 长码（≥4）才可能是"引擎真的给不出"的词（名字、术语、新词）。
     */
    private const val MIN_AUTO_CODE_LEN = 4

    /** 纠错对门槛（只用来决定"值不值得提示"，不自动写）。 */
    private const val MIN_CORRECTION = 2

    private const val MAX_CODE_LEN = 12
    private const val MAX_WORD_LEN = 12

    // ==================== 【M·L2】整句 ====================

    /**
     * 整句路径的 prefs 前缀。
     * 和单词路径（`c|`）**分开存**，因为两者判据相反、阈值也不同。
     */
    private const val K_SENT = "g|"

    /** 整句：码至少这么长。够长的全拼几乎不会和别的词撞车。 */
    private const val MIN_SENT_CODE = 8

    /** 整句：至少这么多个字（4 字以下算词，走单词路径）。 */
    private const val MIN_SENT_WORD = 4

    /** 整句要**你特意选中**几次才置顶（比单词的 3 次低一档：长句本来就少）。 */
    private const val MIN_SENT_CONFIRM = 2

    /** 整句的长度上限 —— D-2 那两个 12 就是整句进不来的原因。 */
    private const val MAX_SENT_CODE = 48
    private const val MAX_SENT_WORD = 32

    // ==================== 【M·L3】后台整理 ====================

    /**
     * 「词 → 拼音码」的记忆前缀。
     *
     * 只在 `observe()` 里记，**不过那两道闸** —— 闸管的是"要不要写进引擎词库"，
     * 这里只是记个映射：有了它，后台才能把一对搭配拼成一个整词的码。
     */
    private const val K_CODE = "k|"

    /** 一对搭配至少共现这么多次，才够格长成新词。 */
    private const val MIN_PAIR_WRITE = 5

    /**
     * 组合码至少这么长才走绝对优先级轴。
     *
     * **从 8 降到 6 的原因**（自查时发现）：最典型的场景是「输入」+「法」
     * —— `shuru` + `fa` = **7** 个字母，卡在 8 下面，正好被自己的门槛拦掉。
     *
     * 6 仍然能滤掉「的」+「了」这类短组合，而真正的撞车风险本来就由
     * [MIN_PAIR_WRITE]（≥5 次）兜着 —— **你反复这么打，就说明它是个真词组。**
     */
    private const val MIN_PAIR_CODE = 6

    /** 一次整理最多处理多少对搭配。 */
    private const val PAIR_SCAN = 200
    private const val MAX_KEYS = 3000
    private const val MIN_WRITE_INTERVAL_MS = 20_000L

    private const val HEADER = "; fainput 从你的输入里学到的词和整句 —— 可以直接改，也可以整行删掉"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var dirty = false

    @Volatile
    private var lastWriteAt = 0L

    // ==================== 生命周期 ====================

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        Timber.i("[pdict] init，%d 条候选记录", p.all.size)
    }

    val isReady: Boolean
        get() = prefs != null

    // ==================== 输入信号 ====================

    /**
     * 【D-2】一次正常的「拼音码 → 上屏词」。
     *
     * 由 `InsightRecorder` 在每次提交时调用（它知道 preedit 与上屏文本）。
     *
     * @param offeredByEngine 引擎**这一次**有没有把这个词作为候选给出。
     *        给过 → 我们不写（不能走绝对优先级轴）。
     */
    fun observe(
        code: String,
        word: String,
        offeredByEngine: Boolean,
        candidateIndex: Int = -1,
    ) {
        val w = normalizeWord(word, MAX_SENT_WORD) ?: return
        val c = normalizeCode(code, MAX_SENT_CODE) ?: return

        // 【M·L3】先记下「词 → 码」。
        // **刻意放在下面那两道闸之前** —— 闸管的是"要不要写进引擎词库"，
        // 而这里只是记个映射：后台整理要靠它把一对搭配拼成一个整词的码。
        if (w.length <= MAX_WORD_LEN && c.length <= MAX_CODE_LEN) {
            val key = K_CODE + w
            // ⚠️ 只在**内容真的变了**时失效快照。
            //    同一个词还是同一个码 ⇒ 重建出来的表一模一样，
            //    但重建要扫几百条 prefs 键 —— 每键扫一次就是灾难。
            if (prefs?.getString(key, null) != c) {
                prefs?.edit()?.putString(key, c)?.apply()
                invalidateScoring()
            }
        }

        // 【M·L2】整句路径 —— 判据和单词路径**正好相反**：
        //
        //   单词：引擎**给不出**才写。引擎给得出的交给 D-1' 位置提升 ——
        //        写 order=1 就是"布尔闸门"，那正是 WindInput 记录的事故。
        //   整句：你**特意选了**才写（candidateIndex ≥ 1 ⇒ 引擎的 N-best 没把它排在前）。
        //
        // 整句用的是「整串全拼」，撞车概率极低，不构成对常用词权重轴的干扰。
        // 这就是 L2 的落点：**用你的选择覆盖引擎的 N-best。**
        if (w.length >= MIN_SENT_WORD && c.length >= MIN_SENT_CODE && candidateIndex >= 1) {
            bump(K_SENT + c + "|" + w, MIN_SENT_CONFIRM)
            return
        }
        // 太长的不走单词路径
        if (w.length > MAX_WORD_LEN || c.length > MAX_CODE_LEN) return
        // ① 引擎自己能给出的词，**绝不写进 customphrase**。
        //    customphrase 的序号是绝对优先级轴，我们无从知道引擎内部权重尺度，
        //    写 order=1 就是"布尔闸门" —— 那正是 WindInput 记录的事故。
        //    要"提前"就交给 D-1' 的位置提升：有界、可预测、不动别人的权重。
        if (offeredByEngine) return
        // ② 短码一律不走绝对轴（de/shi/le 这些码对应的都是极高权重常用词）
        if (c.length < MIN_AUTO_CODE_LEN) return
        bump(K_WORD + c + "|" + w, MIN_CONFIRM)
    }

    /**
     * 【D-3】检测到「打完又删重打」：同一个码，先出 [wrong] 后被换成 [right]。
     *
     * **只累积，不落盘。** 等用户在界面里采纳（[acceptCorrection]）。
     */
    fun observeCorrection(code: String, wrong: String, right: String) {
        val c = normalizeCode(code) ?: return
        val w1 = normalizeWord(wrong) ?: return
        val w2 = normalizeWord(right) ?: return
        if (w1 == w2) return
        bump(K_CORR + c + "|" + w1 + "|" + w2, MIN_CORRECTION)
    }

    /**
     * 每提交这么多次才检查一次"记录是不是太多了"。
     *
     * ⚠️ 为什么必须节流：`SharedPreferences.getAll()` 会**复制整个表**
     * （几千键时约 1ms），而 [bump] 在**每次选词**时都会跑（主线程）。
     * 剪枝是 housekeeping，晚几百次做完全无所谓。
     */
    private const val KEY_CHECK_EVERY = 128

    /** 距上次检查过了多少次提交。**只是启发式计数器，不必精确。** */
    private var bumpTick = 0

    private fun bump(key: String, threshold: Int) {
        val p = prefs ?: return
        val n = (p.getInt(key, 0) + 1).coerceAtMost(9999)
        p.edit().putInt(key, n).apply()
        // 刚刚够到门槛的那一刻才置脏，避免每次提交都写盘
        if (n == threshold) {
            dirty = true
            // 【B 层】纠错计数刚够门槛 ⇒ 它**现在才**出现在快照里，必须失效。
            // 别的键（个人词库条目）不进快照，不用管。
            if (key.startsWith(K_CORR)) invalidateScoring()
        }
        // 记录太多就剪一次枝 —— **每 KEY_CHECK_EVERY 次提交才查一次**，
        // 因为查一次要把整个 prefs 表拷一份（见 KEY_CHECK_EVERY 的注释）。
        if (++bumpTick >= KEY_CHECK_EVERY) {
            bumpTick = 0
            if (p.all.size > MAX_KEYS) prune()
        }
    }

    /** 记录太多就丢掉只出现过 1 次的（那些本来也没资格进词库）。 */
    private fun prune() {
        val p = prefs ?: return
        val editor = p.edit()
        var removed = 0
        p.all.forEach { (k, v) ->
            // ⚠️ String 型键（`k|` 记的「词 → 码」）不是计数器：
            //    `as? Int` 会得到 null → 0，正好落进"只出现过 1 次"里被误删。
            if (k.startsWith(K_CODE)) return@forEach
            if (removed < 500 && (v as? Int ?: 0) <= 1) {
                editor.remove(k)
                removed++
            }
        }
        editor.apply()
        Timber.i("[pdict] 记录过多，清理 %d 条弱信号", removed)
    }

    // ==================== 写盘 + 热重载 ====================

    /**
     * 把够格的词写进 `customphrase`，并让引擎**立刻重读**。
     *
     * 热重载通道：`FcitxAPI.reloadPinyinCustomPhrase()`
     * → native `setSubConfig("pinyin","customphrase")`
     * → `PinyinEngine::loadCustomPhrase()`（`pinyin.cpp:2414`）。
     */
    suspend fun publishIfNeeded(api: FcitxAPI, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // 每个进程**第一次**调用强制跑一遍：
        // ① 自愈 —— 上游的「管理自定义短语」编辑器保存时会整文件重写，
        //    有可能把我们追加的行冲掉；这里会把它们补回来。
        // ② 保证重启后内存记录和文件一致。
        // （`lastWriteAt == 0L` 就是"本进程还没写过"，不用额外加字段。）
        val first = lastWriteAt == 0L
        if (!force && !first) {
            if (!dirty) return
            if (now - lastWriteAt < MIN_WRITE_INTERVAL_MS) return
        }
        lastWriteAt = now
        dirty = false
        val added = runCatching { writeFile() }.getOrElse {
            Timber.w(it, "[pdict] 写词库失败")
            dirty = true
            return
        }
        if (added <= 0) return
        runCatching { api.reloadPinyinCustomPhrase() }
            .onSuccess { Timber.i("[pdict] 新增 %d 条，引擎已热重载", added) }
            .onFailure { Timber.w(it, "[pdict] 热重载失败（下次启动会生效）") }
    }

    /**
     * 只**追加**不给已有的行动刀 ——
     * 用户可能手工编辑过，引擎自己也会往这个文件写（置顶 / 忘记候选词）。
     * 追加式写入永远不会互相覆盖。
     */
    private fun writeFile(): Int {
        val p = prefs ?: return 0
        val file: File = EngineUserDir.customPhraseFile()
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()

        val seen = HashSet<String>()
        lines.forEach { line -> parse(line)?.let { seen.add(it.code + "|" + it.value) } }

        var added = 0
        p.all.forEach { (key, value) ->
            // 【M·L2】两条路径：前缀不同、门槛也不同
            val threshold = when {
                key.startsWith(K_WORD) -> MIN_CONFIRM
                key.startsWith(K_SENT) -> MIN_SENT_CONFIRM
                else -> return@forEach
            }
            val count = value as? Int ?: return@forEach
            if (count < threshold) return@forEach
            // 前缀都是 2 个字符（`c|` / `g|`）
            val body = key.substring(2)
            val sep = body.indexOf('|')
            if (sep <= 0) return@forEach
            val code = body.substring(0, sep)
            val word = body.substring(sep + 1)
            if (!seen.add(code + "|" + word)) return@forEach
            lines += "$code,$PIN_ORDER=$word"
            added++
        }
        if (added == 0) return 0
        if (lines.none { it.startsWith(";") }) lines.add(0, HEADER)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return added
    }

    // ==================== 读（给设置页用） ====================

    data class Entry(val code: String, val order: Int, val value: String)

    data class Correction(val code: String, val wrong: String, val right: String, val count: Int)

    /** 词库当前全部条目（直接读引擎那个文件，所见即所得）。 */
    fun entries(): List<Entry> =
        runCatching {
            val f = EngineUserDir.customPhraseFile()
            if (!f.exists()) emptyList()
            else f.readLines().mapNotNull { parse(it) }.sortedBy { it.code }
        }.getOrElse {
            Timber.w(it, "[pdict] 读词库失败")
            emptyList()
        }

    fun search(keyword: String): List<Entry> {
        val k = keyword.trim()
        if (k.isEmpty()) return entries()
        return entries().filter { it.code.contains(k, true) || it.value.contains(k) }
    }

    /** 【M·L3】查一个词的拼音码（由 [observe] 记下来的）。 */
    private fun codeOf(word: String): String? = prefs?.getString(K_CODE + word, null)

    /**
     * 【M·L3】后台整理：从你的**词搭配**里长出新词。
     *
     * ```
     * 你 5 次以上都是「输入」后面跟「法」
     *   → 把 `shurufa,1=输入法` 写进引擎词库
     *   → 引擎自己就认识这个组合了，不再依赖 D-1' 每次帮你提前
     * ```
     *
     * ## 只在充电 / 熄屏时跑
     *
     * 由 `InsightMaintenance.runOnce` 调用 —— **绝不进打字路径**。
     *
     * ## 门槛为什么这么高
     *
     * `customphrase` 的序号是**绝对优先级轴**，`order=1` 就是布尔闸门
     * （WindInput 的事故）。所以必须**同时**满足：
     *
     * - 你**反复**这么打（≥ [MIN_PAIR_WRITE] 次）
     * - 组合码**长到几乎不会撞车**（≥ [MIN_PAIR_CODE] 个字母）
     *
     * 任一条不满足就什么都不做 —— 宁可漏，不可错。
     *
     * @return 这次新长出来的词条数（0 表示没有可整理的东西）
     */
    suspend fun organize(dao: InsightDao): Int {
        val p = prefs ?: return 0
        val pairs = runCatching { dao.strongBigrams(MIN_PAIR_WRITE, PAIR_SCAN) }
            .getOrNull() ?: return 0
        if (pairs.isEmpty()) return 0
        var added = 0
        pairs.forEach { pair ->
            val ca = codeOf(pair.a) ?: return@forEach
            val cb = codeOf(pair.b) ?: return@forEach
            val code = ca + cb
            if (code.length < MIN_PAIR_CODE) return@forEach
            val phrase = pair.a + pair.b
            if (phrase.length > MAX_WORD_LEN) return@forEach
            // 走和单词**同一条**写入通道：prefs 里记够门槛，下次 writeFile 带出去
            val key = K_WORD + code + "|" + phrase
            if (p.getInt(key, 0) >= MIN_CONFIRM) return@forEach
            p.edit().putInt(key, MIN_CONFIRM).apply()
            added++
        }
        if (added > 0) {
            dirty = true
            Timber.i("[pdict] L3 整理：从 %d 对搭配里长出 %d 个新词", pairs.size, added)
        }
        return added
    }

    /** 攒够次数、值得让用户确认的纠错对。 */
    fun pendingCorrections(): List<Correction> {
        val p = prefs ?: return emptyList()
        val out = ArrayList<Correction>()
        p.all.forEach { (key, value) ->
            if (!key.startsWith(K_CORR)) return@forEach
            val count = value as? Int ?: return@forEach
            if (count < MIN_CORRECTION) return@forEach
            val parts = key.removePrefix(K_CORR).split('|')
            if (parts.size != 3) return@forEach
            out += Correction(parts[0], parts[1], parts[2], count)
        }
        return out.sortedByDescending { it.count }
    }

    // ==================== 【B 层】给打分器用的内存快照 ====================

    /**
     * 【B 层 / 个人纠错】两张表的内存形式。
     *
     * ## 为什么必须有快照，不能让打分器直接读 prefs
     *
     * `CandidateReranker.reorder()` 跑在**主线程**（候选列表事件里）。
     * 而 `prefs.all` 是**全量读 + 逐条拆字符串** —— 每按一个键做一次，
     * 键盘直接就卡了。
     *
     * ⇒ **写的时候失效，读的时候重建一次，打字路径只查内存表。**
     *
     * ## 两张表分别是什么
     *
     * | 表 | 来源 | 含义 |
     * |---|---|---|
     * | [codeWords] | `k|<词>` = 码 | **你用这个码打过这个词** |
     * | [corrections] | `x|<码>|<错>|<对>` = 次数 | **你在这个码下把「错」改成了「对」** |
     *
     * 后者是**最强信号** —— 那不是"猜的"，是你亲口纠正过的。
     */
    data class ScoringSnapshot(
        /** 码 → 该码下你历史上选过的词 */
        val codeWords: Map<String, Set<String>>,
        /** 码 → (被纠正的错词 → 你改成的对词) */
        val corrections: Map<String, Map<String, String>>,
    ) {
        val isEmpty: Boolean get() = codeWords.isEmpty() && corrections.isEmpty()

        companion object {
            val Empty = ScoringSnapshot(emptyMap(), emptyMap())
        }
    }

    @Volatile
    private var scoringSnap: ScoringSnapshot? = null

    /**
     * 取快照。**第一次访问时构建**（可能扫几百条 prefs 键），之后直接返回。
     *
     * 任何写入路径都必须调 [invalidateScoring] —— 否则学了新词却用不上。
     */
    fun scoringSnapshot(): ScoringSnapshot {
        scoringSnap?.let { return it }
        synchronized(this) {
            scoringSnap?.let { return it }
            val s = buildScoringSnapshot()
            scoringSnap = s
            return s
        }
    }

    /** 让快照失效，下次读取时重建。**任何写 prefs 的地方都要叫它。** */
    private fun invalidateScoring() {
        scoringSnap = null
    }

    private fun buildScoringSnapshot(): ScoringSnapshot {
        val p = prefs ?: return ScoringSnapshot.Empty
        val codeWords = HashMap<String, MutableSet<String>>()
        val corr = HashMap<String, MutableMap<String, String>>()
        p.all.forEach { (k, v) ->
            when {
                k.startsWith(K_CODE) -> {
                    val word = k.removePrefix(K_CODE)
                    val code = v as? String ?: return@forEach
                    if (word.isNotEmpty() && code.isNotEmpty()) {
                        codeWords.getOrPut(code) { HashSet(4) } += word
                    }
                }
                k.startsWith(K_CORR) -> {
                    val count = v as? Int ?: return@forEach
                    if (count < MIN_CORRECTION) return@forEach
                    val parts = k.removePrefix(K_CORR).split('|')
                    if (parts.size != 3) return@forEach
                    corr.getOrPut(parts[0]) { HashMap(4) }[parts[1]] = parts[2]
                }
            }
        }
        Timber.i(
            "[pdict] B 层快照：%d 个码 / %d 组纠错",
            codeWords.size, corr.values.sumOf { it.size }
        )
        return ScoringSnapshot(codeWords, corr)
    }

    // ==================== 改（给设置页用） ====================

    /** 采纳一条纠错：把「码 → 对词」按置顶写入。 */
    suspend fun acceptCorrection(api: FcitxAPI, c: Correction) {
        val p = prefs ?: return
        p.edit()
            .putInt(K_WORD + c.code + "|" + c.right, MIN_CONFIRM)
            .remove(K_CORR + c.code + "|" + c.wrong + "|" + c.right)
            .apply()
        invalidateScoring()
        dirty = true
        publishIfNeeded(api, force = true)
    }

    /** 忽略一条纠错：只删提示，不动词库。 */
    fun dismissCorrection(c: Correction) {
        prefs?.edit()?.remove(K_CORR + c.code + "|" + c.wrong + "|" + c.right)?.apply()
        invalidateScoring()
    }

    /** 删除一条词库条目（直接改文件）。 */
    suspend fun removeEntry(api: FcitxAPI, entry: Entry) {
        rewrite { lines ->
            lines.filterNot { line ->
                val e = parse(line)
                e != null && e.code == entry.code && e.value == entry.value
            }
        }
        suspendReload(api)
    }

    /** 修改一条（改序号 = 改它在候选里的位置；改内容注意别写坏格式）。 */
    suspend fun updateEntry(api: FcitxAPI, old: Entry, newCode: String, newValue: String, newOrder: Int) {
        rewrite { lines ->
            lines.map { line ->
                val e = parse(line)
                if (e != null && e.code == old.code && e.value == old.value) {
                    "$newCode,$newOrder=$newValue"
                } else line
            }
        }
        suspendReload(api)
    }

    private inline fun rewrite(crossinline transform: (List<String>) -> List<String>) {
        runCatching {
            val f = EngineUserDir.customPhraseFile()
            val lines = if (f.exists()) f.readLines() else emptyList()
            f.writeText(transform(lines).joinToString("\n", postfix = "\n"))
        }.onFailure { Timber.w(it, "[pdict] 改词库失败") }
    }

    private suspend fun suspendReload(api: FcitxAPI) {
        runCatching { api.reloadPinyinCustomPhrase() }
            .onFailure { Timber.w(it, "[pdict] 热重载失败") }
    }

    // ==================== 工具 ====================

    /**
     * 解析 `字母,序号=内容`。
     * 引擎保存时会用引号包住转义过的内容，这里顺手剥掉。
     */
    private fun parse(line: String): Entry? {
        val s = line.trim()
        if (s.isEmpty() || s.startsWith(";") || s.startsWith("#")) return null
        val comma = s.indexOf(',')
        if (comma <= 0) return null
        val eq = s.indexOf('=', comma + 1)
        if (eq < 0) return null
        val code = s.substring(0, comma)
        if (!code.all { it in 'a'..'z' || it in 'A'..'Z' }) return null
        val order = s.substring(comma + 1, eq).trim().toIntOrNull() ?: return null
        var value = s.substring(eq + 1)
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        if (value.isEmpty()) return null
        return Entry(code, order, value)
    }

    /** 拼音码：纯小写字母，2~12 位。 */
    private fun normalizeCode(raw: String, max: Int = MAX_CODE_LEN): String? {
        val c = raw.trim().lowercase()
        if (c.length < 2 || c.length > max) return null
        if (!c.all { it in 'a'..'z' || it == '\'' }) return null
        return c
    }

    /** 只要「词」：全汉字，2~12 字。数字、符号、英文串一律不要（那是噪音）。 */
    private fun normalizeWord(raw: String, max: Int = MAX_WORD_LEN): String? {
        val w = raw.trim()
        if (w.length < 2 || w.length > max) return null
        if (!w.all { it.code in 0x4E00..0x9FFF }) return null
        return w
    }
}