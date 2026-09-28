/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.transfer

import android.net.Uri
import android.util.Base64
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.insight.db.InsightDatabase
import org.fcitx.fcitx5.android.utils.appContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 【fainput / H】**加密整机转移包** —— 三条原则里「数据能带走」那一条。
 *
 * ## 为什么不是"导出 CSV"
 *
 * 早期的想法是"导出词库"，后来改成**整机转移包**：换手机时**一次搬完**
 * —— 学过的词、搭配、整句、统计、引擎自己的自订字词、以及设置。
 *
 * ## 格式（`FAINPUT1`）
 *
 * ```
 * ┌────────┬─────┬──────────┬────────┬───────────────────────┐
 * │ magic  │ ver │ salt(16) │ iv(12) │ AES-256-GCM( gzip(JSON) ) │
 * └────────┴─────┴──────────┴────────┴───────────────────────┘
 * ```
 *
 * - 密钥：`PBKDF2WithHmacSHA256`（§11.5 定的）+ 20 万次迭代；
 *   老设备（API < 26）退回 `PBKDF2WithHmacSHA1`，导入时**两个都试**
 * - GCM 自带认证 ⇒ **密码错 = 解密直接抛异常**，不存在"解出半个文件"
 * - gzip 是为了那 1000 条 `input_event` 不把包撑大（JSON 压缩率很高）
 *
 * ## 为什么走"通用表遍历"
 *
 * 导出/导入都**不逐字段抄代码**，而是：
 *
 * - 导出：`SELECT * FROM t` → 用 Cursor 的列名 + 类型逐列取值
 * - 导入：从 JSON 里的列名动态拼 `INSERT OR REPLACE`
 *
 * ⇒ 以后**加表 / 加字段不用改这个文件**（只往 `TABLES` 里补个名字）。
 */
object TransferPack {

    private const val MAGIC = "FNPUT1"

    private const val VERSION = 1

    /** DB 里要搬的表。加表只改这里。 */
    private val TABLES = listOf(
        "input_event", "word_stat", "word_bigram", "session", "daily_stat"
    )

    /** 引擎自己的用户目录（`customphrase` 等就住在这里）。 */
    private fun engineDir(): File = File(appContext.filesDir, "fcitx5-user")

    /** 超过这个大小的文件不进包（别把几 MB 的二进制塞进来）。 */
    private const val FILE_MAX = 2 * 1024 * 1024

    private const val ITERATIONS = 200_000

    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 一次导出/导入的结果 —— 只用来显示数字。 */
    data class Stats(
        val rows: Int,
        val files: Int,
        val prefs: Int,
        val bytes: Int,
    )

    // ==================== 导出 ====================

    suspend fun export(uri: Uri, passphrase: String): Result<Stats> =
        withContext(Dispatchers.IO) {
            runCatching {
                val db = openDb()
                val root = JSONObject()
                root.put("version", VERSION)
                root.put("exportedAt", System.currentTimeMillis())

                val tables = JSONObject()
                var rows = 0
                for (t in TABLES) {
                    val dump = dumpTable(db, t)
                    rows += dump.second
                    tables.put(t, dump.first)
                }
                root.put("tables", tables)

                val files = dumpFiles()
                root.put("files", files.first)

                val prefs = dumpPrefs()
                root.put("prefs", prefs.first)
                // §11.5 的硬要求：**盐必须一起打包**，否则恢复后 L2 哈希全部失去意义。
                // 盐住在 SharedPreferences（`SensitiveClassifier.KEY_SALT = "hash_salt"`），
                // 而上面把 `shared_prefs/*.xml` 全打包了 ⇒ 它跟着走。这里只做**可观测**。
                Timber.i(
                    "[transfer] 导出：%d 行 / %d 文件 / %d 偏好，盐随包走：%s",
                    rows, files.second, prefs.second, if (saltCarried()) "是" else "否"
                )

                val sealed = seal(gzip(root.toString().toByteArray(Charsets.UTF_8)), passphrase)
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(sealed) }
                    ?: error("打不开目标文件")

                Stats(rows, files.second, prefs.second, sealed.size)
            }.onFailure { Timber.w(it, "[transfer] 导出失败") }
        }

    // ==================== 导入 ====================

    suspend fun import(uri: Uri, passphrase: String): Result<Stats> =
        withContext(Dispatchers.IO) {
            runCatching {
                val blob = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("打不开这个文件")
                val json = JSONObject(String(ungzip(open(blob, passphrase)), Charsets.UTF_8))

                val db = openDb()
                val tables = json.optJSONObject("tables") ?: JSONObject()
                var rows = 0
                for (t in TABLES) {
                    val dump = tables.optJSONObject(t) ?: continue
                    rows += restoreTable(db, t, dump)
                }

                val files = json.optJSONObject("files")
                val nFiles = restoreFiles(files)

                val prefs = json.optJSONObject("prefs")
                val nPrefs = restorePrefs(prefs)

                Stats(rows, nFiles, nPrefs, blob.size)
            }.onFailure { Timber.w(it, "[transfer] 导入失败") }
        }

    // ==================== 清空 ====================

    /**
     * 清空"我们学到的、以及引擎自己的"数据。
     *
     * **不碰**上游设置（键盘高度、主题那些）—— 那是另一回事，清掉只会让人困惑。
     */
    suspend fun wipe(): Int = withContext(Dispatchers.IO) {
        var n = 0
        runCatching {
            val db = openDb()
            db.beginTransaction()
            try {
                for (t in TABLES) {
                    db.execSQL("DELETE FROM $t")
                    n++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            n += wipeFiles()
            n += wipePrefs()
        }.onFailure { Timber.w(it, "[transfer] 清空失败") }
        n
    }

    /** 当前库里有多少行 —— 状态页显示用。 */
    suspend fun counts(): Map<String, Int> = withContext(Dispatchers.IO) {
        runCatching {
            val db = openDb()
            TABLES.associateWith { t ->
                db.query("SELECT COUNT(*) FROM $t").use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** 文件名对不对 —— 只做提示，不做拦截。 */
    fun looksLikePack(name: String?): Boolean =
        name?.endsWith(".fainput", ignoreCase = true) == true || name?.endsWith(".bin") == true

    // ==================== DB ====================

    private fun openDb(): androidx.sqlite.db.SupportSQLiteDatabase =
        Room.databaseBuilder(appContext, InsightDatabase::class.java, "insight")
            .addMigrations(*InsightDatabase.MIGRATIONS)
            .build()
            .openHelper.writableDatabase

    /** 一张表 → `{"cols":[...], "rows":[[...]]}`，并返回行数。 */
    private fun dumpTable(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): Pair<JSONObject, Int> {
        val out = JSONObject()
        val rows = JSONArray()
        db.query("SELECT * FROM $table").use { c ->
            val cols = c.columnNames
            out.put("cols", JSONArray(cols.toList()))
            while (c.moveToNext()) {
                val row = JSONArray()
                for (i in cols.indices) row.put(readCell(c, i))
                rows.put(row)
            }
        }
        out.put("rows", rows)
        return out to rows.length()
    }

    /** 按 Cursor 自己的类型取值 —— 不认识的一律丢成 null，不让一行坏数据毁掉整个包。 */
    private fun readCell(c: android.database.Cursor, i: Int): Any? = when (c.getType(i)) {
        android.database.Cursor.FIELD_TYPE_NULL -> null
        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        android.database.Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)
        else -> c.getString(i)
    }

    private fun restoreTable(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        dump: JSONObject,
    ): Int {
        val colsArr = dump.optJSONArray("cols") ?: return 0
        val cols = (0 until colsArr.length()).map { colsArr.getString(it) }
        if (cols.isEmpty()) return 0
        val rows = dump.optJSONArray("rows") ?: return 0

        val placeholders = cols.joinToString(", ") { "?" }
        val sql = "INSERT OR REPLACE INTO $table (${cols.joinToString(", ")}) VALUES ($placeholders)"

        var n = 0
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $table")
            val st = db.compileStatement(sql)
            for (r in 0 until rows.length()) {
                val row = rows.optJSONArray(r) ?: continue
                st.clearBindings()
                for (i in cols.indices) {
                    when (val v = if (i < row.length()) row.opt(i) else null) {
                        null, JSONObject.NULL -> st.bindNull(i + 1)
                        is Int -> st.bindLong(i + 1, v.toLong())
                        is Long -> st.bindLong(i + 1, v)
                        is Double -> st.bindDouble(i + 1, v)
                        is Boolean -> st.bindLong(i + 1, if (v) 1L else 0L)
                        is String -> {
                            // BLOB 那一列在导出时编成了 base64，这里解回来
                            if (cols[i].endsWith("blob", ignoreCase = true)) {
                                st.bindBlob(i + 1, Base64.decode(v, Base64.NO_WRAP))
                            } else {
                                st.bindString(i + 1, v)
                            }
                        }

                        else -> st.bindString(i + 1, v.toString())
                    }
                }
                st.executeInsert()
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    // ==================== 文件 / 偏好 ====================

    private fun dumpFiles(): Pair<JSONObject, Int> {
        val out = JSONObject()
        val base = engineDir()
        if (!base.isDirectory) return out to 0
        var n = 0
        base.walkTopDown().filter { it.isFile && it.length() <= FILE_MAX }.forEach {
            out.put(it.relativeTo(base).path, Base64.encodeToString(it.readBytes(), Base64.NO_WRAP))
            n++
        }
        return out to n
    }

    private fun restoreFiles(obj: JSONObject?): Int {
        if (obj == null) return 0
        val base = engineDir().apply { mkdirs() }
        var n = 0
        obj.keys().forEach { rel ->
            runCatching {
                val f = File(base, rel)
                f.parentFile?.mkdirs()
                f.writeBytes(Base64.decode(obj.getString(rel), Base64.NO_WRAP))
                n++
            }
        }
        return n
    }

    private fun prefsDir(): File = File(appContext.filesDir.parentFile, "shared_prefs")

    /**
     * 盐在不在包里 —— 直接查磁盘上的偏好文件。
     *
     * 不去翻编码后的 JSON：值是 base64 的 XML，搜不到明文键名。
     */
    private fun saltCarried(): Boolean = runCatching {
        prefsDir().listFiles()?.any { f ->
            f.isFile && f.name.endsWith(".xml") && f.readText().contains("hash_salt")
        } ?: false
    }.getOrDefault(false)

    private fun dumpPrefs(): Pair<JSONObject, Int> {
        val out = JSONObject()
        var n = 0
        prefsDir().listFiles()?.forEach { f ->
            if (!f.isFile || !f.name.endsWith(".xml")) return@forEach
            if (f.length() > FILE_MAX) return@forEach
            out.put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            n++
        }
        return out to n
    }

    private fun restorePrefs(obj: JSONObject?): Int {
        if (obj == null) return 0
        val dir = prefsDir().apply { mkdirs() }
        var n = 0
        obj.keys().forEach { name ->
            runCatching {
                File(dir, name).writeBytes(Base64.decode(obj.getString(name), Base64.NO_WRAP))
                n++
            }
        }
        return n
    }

    private fun wipeFiles(): Int {
        val base = engineDir()
        if (!base.isDirectory) return 0
        var n = 0
        base.walkTopDown().filter { it.isFile && it.length() <= FILE_MAX }.forEach { if (it.delete()) n++ }
        return n
    }

    /** 只清我们自己的偏好（`fainput_*`）。 */
    private fun wipePrefs(): Int {
        var n = 0
        prefsDir().listFiles()?.forEach { f ->
            if (f.name.startsWith("fainput_") && f.delete()) n++
        }
        return n
    }

    // ==================== 加解密 ====================

    private fun seal(plain: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, sealKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        val ct = cipher.doFinal(plain)
        return ByteArrayOutputStream().apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            write(VERSION)
            write(salt)
            write(iv)
            write(ct)
        }.toByteArray()
    }

    private fun open(blob: ByteArray, passphrase: String): ByteArray {
        val head = MAGIC.length + 1 + SALT_LEN + IV_LEN
        if (blob.size <= head) error("这个文件不是转移包")
        if (String(blob, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) error("这个文件不是转移包")
        val salt = blob.copyOfRange(MAGIC.length + 1, MAGIC.length + 1 + SALT_LEN)
        val iv = blob.copyOfRange(MAGIC.length + 1 + SALT_LEN, head)
        val ct = blob.copyOfRange(head, blob.size)
        // 新包是 SHA256 做的；老设备（API < 26）只写得出 SHA1 的包。
        // 两个都试一遍 —— GCM 带认证，所以"试错"只会干净地失败。
        for (algo in KEY_ALGOS) {
            val r = runCatching {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(
                        Cipher.DECRYPT_MODE,
                        key(passphrase, salt, algo),
                        GCMParameterSpec(TAG_BITS, iv)
                    )
                    doFinal(ct)
                }
            }
            if (r.isSuccess) return r.getOrThrow()
        }
        error("密码不对，或文件损坏")
    }

    /**
     * 密钥派生算法，**按顺序试**。
     *
     * §11.5 定的是 SHA256，但 **minSdk 23** —— `PBKDF2WithHmacSHA256` 要 API 26。
     * 与其只认一个（要么老机器全崩，要么新包配不上文档），不如两个都留：
     * 导出优先 SHA256，导入两个都试。
     */
    private val KEY_ALGOS = listOf("PBKDF2WithHmacSHA256", "PBKDF2WithHmacSHA1")

    private fun key(passphrase: String, salt: ByteArray, algo: String): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance(algo).generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun sealKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        for (algo in KEY_ALGOS) {
            runCatching { return key(passphrase, salt, algo) }
        }
        return key(passphrase, salt, KEY_ALGOS.last())
    }

    // ==================== gzip ====================

    private fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        GZIPOutputStream(this).use { it.write(data) }
    }.toByteArray()

    private fun ungzip(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }
}