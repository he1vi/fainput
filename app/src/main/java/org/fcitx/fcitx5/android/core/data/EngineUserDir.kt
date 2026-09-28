/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.core.data

import android.content.Context
import org.fcitx.fcitx5.android.FcitxApplication
import timber.log.Timber
import java.io.File

/**
 * 【fainput】引擎用户数据的落点 —— **内部存储**。
 *
 * ## 为什么要改
 *
 * 上游把引擎用户数据放在 `getExternalFilesDir(null)`，也就是
 * `/sdcard/Android/data/<包名>/files/`。那里：
 *
 * - 用户在文件管理器里**能直接看到并读走**
 * - 有 `MANAGE_EXTERNAL_STORAGE` 的 App 也能读
 *
 * 而 `data/pinyin/` 里放的是 `user.dict` / `user.history` / `customphrase` ——
 * **全部是从你打字里长出来的**。这是这个项目最不该外露的东西。
 *
 * ## 为什么必须一次改全套
 *
 * native 侧是这么派生的：
 * ```cpp
 * config_home = extData + "/config"
 * data_home   = extData + "/data"
 * setenv("FCITX_DATA_HOME", data_home)   // ← 用户词典、码表、词典都从这里找
 * setenv("XDG_DATA_HOME",   data_home)
 * ```
 * 所以**只改 native 那一处**的话，`PinyinDictManager` / `QuickPhraseManager` /
 * `TableManager` / `ThemeFilesManager` 还会往外部写，引擎却去内部读 ——
 * 结果是「词典/码表全部消失」。**必须一起改。**
 *
 * ## 目录一致性：一律用 directBootAwareContext
 *
 * 输入法必须在**解锁前**就能用，所以引擎是用 directBootAwareContext 启动的。
 * 而 `directBootAwareContext.filesDir` 是 `/data/user_de/0/<pkg>/files`，
 * 普通 context 是 `/data/user/0/<pkg>/files` —— **两个完全不同的目录**。
 * 一旦混用，引擎写一个、管理界面读另一个。
 * ⇒ 本对象**固定只认 directBootAwareContext**，谁调用都一样。
 */
object EngineUserDir {

    private const val INTERNAL_DIR_NAME = "fcitx5-user"

    /** 迁移完成标记，防止每次启动都重拷。 */
    private const val MIGRATION_MARK = ".migrated-from-external"

    /**
     * **打字衍生**的文件 —— 迁移完成后从外部存储删掉。
     *
     * 注意这里**故意不删** `config/` 和 `theme/`：
     * - 它们不是打字数据（不涉及隐私）
     * - 删错了用户会「设置全丢」，代价太大
     * ⇒ 只把真正敏感的那几个搬走并抹掉。
     */
    private val SENSITIVE_RELATIVE = listOf(
        "data/pinyin/user.dict",
        "data/pinyin/user.history",
        "data/pinyin/customphrase",
    )

    @Volatile
    private var cached: File? = null

    /**
     * 引擎用户数据的根目录（内部存储）。
     *
     * 第一次调用时会把外部存储里的旧数据整体拷过来 ——
     * **否则覆盖安装后用户会发现「词库没了、设置重置了」**。
     */
    fun base(): File {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ctx = FcitxApplication.getInstance().directBootAwareContext
            val internal = File(ctx.filesDir, INTERNAL_DIR_NAME)
            if (!internal.exists()) internal.mkdirs()
            val external = runCatching { ctx.getExternalFilesDir(null) }.getOrNull()
            if (external != null && !File(internal, MIGRATION_MARK).exists()) {
                runCatching { migrate(internal, external) }
                    .onFailure { Timber.w(it, "[userdir] 迁移失败，继续用内部空目录") }
                runCatching { File(internal, MIGRATION_MARK).createNewFile() }
            }
            cached = internal
            return internal
        }
    }

    /** `data/pinyin/` —— 引擎用户词典与自定义短语都在这。 */
    fun pinyinDir(): File = File(base(), "data/pinyin").also {
        if (!it.exists()) it.mkdirs()
    }

    /** `data/pinyin/customphrase` —— 我们写「个人词库」的地方。 */
    fun customPhraseFile(): File = File(pinyinDir(), "customphrase")

    // ==================== 迁移 ====================

    private fun migrate(internal: File, external: File) {
        var copied = 0
        listOf("config", "data", "theme").forEach { name ->
            val src = File(external, name)
            if (!src.exists()) return@forEach
            val dst = File(internal, name)
            // 内部已经有内容就不覆盖（只做一次性迁移）
            if (dst.exists() && dst.list()?.isNotEmpty() == true) return@forEach
            runCatching {
                src.copyRecursively(dst, overwrite = false)
                copied++
            }.onFailure { Timber.w(it, "[userdir] 拷贝 $name 失败") }
        }
        // 关键的一步：把打字衍生的文件从外部**删掉**
        var removed = 0
        SENSITIVE_RELATIVE.forEach { rel ->
            val f = File(external, rel)
            if (f.exists() && f.delete()) removed++
        }
        runCatching { File(external, "data/pinyin").delete() } // 空目录顺手清掉
        Timber.i("[userdir] 迁移完成：拷了 %d 个目录，抹掉 %d 个敏感文件", copied, removed)
    }

    /** 仅供测试 / 排障。 */
    fun isMigrated(): Boolean =
        runCatching { File(base(), MIGRATION_MARK).exists() }.getOrDefault(false)

    /** 兼容旧调用的写法（外部目录），只在迁移时用到。 */
    internal fun legacyExternal(ctx: Context): File? =
        runCatching { ctx.getExternalFilesDir(null) }.getOrNull()
}
