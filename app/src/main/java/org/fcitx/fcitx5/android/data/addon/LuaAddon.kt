/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.addon

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.fcitx.fcitx5.android.core.data.EngineUserDir
import java.io.File

/**
 * 【fainput】把用户自己的 `.lua` 脚本装成一个 fcitx5 **Lua 附加组件**。
 *
 * ## 为什么是 Lua，不是 `.so`
 *
 * fcitx5 的原生外挂是 `.so`。但 Android 从 API 24 起**禁止 App 从自己的可写目录
 * `dlopen`**（`library ... is not accessible for the namespace "classloader-namespace"`）。
 * 想装 `.so`，要么编进 APK，要么走 `android_dlopen_ext` + `android_create_namespace`
 * 这类**私有接口** —— 都不干净。
 *
 * Lua 脚本由解释器读文件，**不经过链接器** ⇒ 它是唯一能"运行时导入"又干净的形态。
 *
 * ## 落地位置（两条都是 fcitx5 自己认的路径）
 *
 * ```
 * <引擎数据>/data/fcitx5/lua/<名字>/<名字>.lua    ← 脚本本体
 * <引擎数据>/data/fcitx5/addon/<名字>.conf        ← 描述（Category=Module + Type=Lua）
 * ```
 *
 * 依据（都在本地源码里）：
 * - `fcitx5-lua/src/addonloader/luaaddonstate.cpp:132`
 *   `StandardPaths::locate(PkgData, joinPath("lua", name, library))`
 * - `fcitx5-lua/src/addonloader/luaaddonloader.cpp:63` —— `category() == Module` 是硬条件
 * - `fcitx5-lua.conf` 的真身：`Category=Module` + `Type=Lua` + `Library=<脚本名>`
 *
 * 引擎数据目录用 [EngineUserDir]（内部存储）：外挂能读到的东西和用户词典同级，
 * 都不该躺在 `/sdcard/Android/data/` 里被任何 App 读走。
 *
 * ## 生命周期
 *
 * 引擎**只在启动时**加载一次 addon 列表（没有 IPC 能触发重扫）⇒
 * **导完要重启输入法才生效**。所以这里如实返回结果，由调用方告诉用户，
 * 而不是假装"导入成功就能用"。
 */
object LuaAddon {

    private fun luaRoot(): File = File(EngineUserDir.base(), "data/fcitx5/lua")
    private fun confRoot(): File = File(EngineUserDir.base(), "data/fcitx5/addon")

    /** 已导入的外挂名（只认我们自己写的那种 `.conf`）。 */
    fun list(): List<String> = runCatching {
        confRoot().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".conf") }
            ?.filter { it.readText().contains("Type=Lua") }
            ?.map { it.name.removeSuffix(".conf") }
            ?.sorted()
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * 装一个脚本，返回装出来的外挂名（= 文件名去掉 `.lua`，只留 `[a-z0-9_-]`）。
     *
     * 名字必须是 fcitx 认的 uniqueName（小写字母 / 数字 / `-` / `_`），
     * 所以这里做一次规整 —— 用户随手起的文件名不该让外挂静默失效。
     */
    fun install(context: Context, uri: Uri): Result<String> = runCatching {
        val raw = queryName(context, uri)?.removeSuffix(".lua")?.trim().orEmpty()
        val name = raw.lowercase()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifEmpty { "addon" }

        val src = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("读不到文件")
        if (src.isEmpty()) error("空文件")

        File(luaRoot(), name).mkdirs()
        File(File(luaRoot(), name), "$name.lua").writeBytes(src)

        confRoot().mkdirs()
        File(confRoot(), "$name.conf").writeText(
            buildString {
                appendLine("[Addon]")
                appendLine("Name=${raw.ifEmpty { name }}")
                appendLine("Category=Module")
                appendLine("Version=1.0")
                appendLine("Type=Lua")
                appendLine("OnDemand=False")
                appendLine("Configurable=False")
                appendLine("Library=$name.lua")
                appendLine()
                appendLine("[Addon/Dependencies]")
                appendLine("0=luaaddonloader")
            }
        )
        name
    }

    /** 删掉导入的外挂（脚本 + 描述一起清）。 */
    fun remove(name: String): Boolean {
        if (name.isBlank() || name.contains('/')) return false
        val script = File(luaRoot(), name)
        val conf = File(confRoot(), "$name.conf")
        val a = if (script.exists()) script.deleteRecursively() else true
        val b = if (conf.exists()) conf.delete() else true
        return a && b
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}