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

    /** 纠错对门槛（只用来决定"值不值得提示"，不自动写）。 */
    private const val MIN_CORRECTION = 2

    private const val MAX_CODE_LEN = 12
    private const val MAX_WORD_LEN = 12
    private const val MAX_KEYS = 3000
    private const val MIN_WRITE_INTERVAL_MS = 20_000L

    private const val HEADER = "; fainput 从你的输入里学到的词 —— 可以直接改，也可以整行删掉"

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
     */
    fun observe(code: String, word: String) {
        val c = normalizeCode(code) ?: return
        val w = normalizeWord(word) ?: return
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

    private fun bump(key: String, threshold: Int) {
        val p = prefs ?: return
        val n = (p.getInt(key, 0) + 1).coerceAtMost(9999)
        p.edit().putInt(key, n).apply()
        // 刚刚够到门槛的那一刻才置脏，避免每次提交都写盘
        if (n == threshold) dirty = true
        if (p.all.size > MAX_KEYS) prune()
    }

    /** 记录太多就丢掉只出现过 1 次的（那些本来也没资格进词库）。 */
    private fun prune() {
        val p = prefs ?: return
        val editor = p.edit()
        var removed = 0
        p.all.forEach { (k, v) ->
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
        if (!force) {
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
            if (!key.startsWith(K_WORD)) return@forEach
            val count = value as? Int ?: return@forEach
            if (count < MIN_CONFIRM) return@forEach
            val body = key.removePrefix(K_WORD)
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

    // ==================== 改（给设置页用） ====================

    /** 采纳一条纠错：把「码 → 对词」按置顶写入。 */
    suspend fun acceptCorrection(api: FcitxAPI, c: Correction) {
        val p = prefs ?: return
        p.edit()
            .putInt(K_WORD + c.code + "|" + c.right, MIN_CONFIRM)
            .remove(K_CORR + c.code + "|" + c.wrong + "|" + c.right)
            .apply()
        dirty = true
        publishIfNeeded(api, force = true)
    }

    /** 忽略一条纠错：只删提示，不动词库。 */
    fun dismissCorrection(c: Correction) {
        prefs?.edit()?.remove(K_CORR + c.code + "|" + c.wrong + "|" + c.right)?.apply()
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
    private fun normalizeCode(raw: String): String? {
        val c = raw.trim().lowercase()
        if (c.length < 2 || c.length > MAX_CODE_LEN) return null
        if (!c.all { it in 'a'..'z' || it == '\'' }) return null
        return c
    }

    /** 只要「词」：全汉字，2~12 字。数字、符号、英文串一律不要（那是噪音）。 */
    private fun normalizeWord(raw: String): String? {
        val w = raw.trim()
        if (w.length < 2 || w.length > MAX_WORD_LEN) return null
        if (!w.all { it.code in 0x4E00..0x9FFF }) return null
        return w
    }
}