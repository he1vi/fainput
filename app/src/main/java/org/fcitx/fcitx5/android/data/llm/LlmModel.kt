/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.llm

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File

/**
 * 【fainput / L3】模型**从哪来** —— 内置优先，导入兜底。
 *
 * ## 两条路，一份代码
 *
 * | 路 | 什么时候 | APK 大小 |
 * |---|---|---|
 * | **内置** | 定版构建（CI 的 `model_mode=bundle`）把 `.gguf` 放进 `assets/models/` | ~470 MB |
 * | **导入** | 平时迭代（默认 `none`）：用户用系统文件选择器挑一个 `.gguf` | 68 MB |
 *
 * 对上层来说两条路**没有区别** —— 都是 [current] 返回一个 `File`。
 *
 * ## 为什么内置的必须解压出来
 *
 * llama.cpp 要的是**文件路径**（它自己 mmap）。而 assets 在 APK 的 zip 里，
 * 给不出一个真实路径。所以首启解压到 `filesDir/llm/`。
 *
 * 代价是**磁盘上会有一份重复**（APK 里一份 + 解压出来一份）。
 * 这是"零网络 + 内置模型"唯一的路 —— 没有别的办法把文件交给 native。
 *
 * ## 幂等靠安装时间
 *
 * 解压标记里带上 APK 的 `lastUpdateTime`：**每次覆盖安装都会失效**，
 * 于是新模型会被重新解压；而平时启动不会白搬 400MB。
 */
object LlmModel {

    /** 内置模型放在 assets 的这个目录下。 */
    private const val ASSET_DIR = "models"

    private const val EXT = ".gguf"

    /** 解压/导入后的落点。 */
    fun dir(): File = File(appContext.filesDir, "llm").apply { it.mkdirs() }

    /**
     * 现在能用的模型文件。
     *
     * 多个候选时取**最大的那个** —— 内置的模型总是比用户随手导入的大。
     */
    fun current(): File? = dir().listFiles()
        ?.filter { it.isFile && it.name.endsWith(EXT, ignoreCase = true) }
        ?.maxByOrNull { it.length() }

    /** 内置模型的 asset 文件名；没有内置就返回 null。 */
    private fun builtinAssetName(): String? = runCatching {
        appContext.assets.list(ASSET_DIR)
            ?.firstOrNull { it.endsWith(EXT, ignoreCase = true) }
    }.getOrNull()

    /** 这次安装的唯一标记 —— 覆盖安装后会变，于是重新解压。 */
    private fun installStamp(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.lastUpdateTime.toString()
    }.getOrDefault("0")

    /**
     * 首启把内置模型解压出来（幂等）。**没有内置就是空操作。**
     *
     * 调用点应当在后台线程 / 低优先级时机 —— 400MB 的复制不该抢打字时的时间。
     */
    suspend fun ensureExtracted(): File? = withContext(Dispatchers.IO) {
        val name = builtinAssetName() ?: return@withContext current()
        val target = File(dir(), name)
        val marker = File(dir(), ".extracted")
        val stamp = "$name@${installStamp()}"
        if (target.isFile && marker.isFile && marker.readText() == stamp) {
            return@withContext target
        }
        runCatching {
            // 先写 .part 再改名：中途被杀不会留下半个模型当成完整的用
            val tmp = File(dir(), "$name.part")
            appContext.assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
            marker.writeText(stamp)
            Timber.i("[llm] 内置模型已解压：%s（%d 字节）", name, target.length())
            target
        }.getOrElse {
            Timber.w(it, "[llm] 内置模型解压失败")
            null
        }
    }

    /**
     * 从用户选的文件导入。
     *
     * 拷进私有目录之后就**不再依赖那个 Uri** —— 外部文件被删也不影响。
     */
    suspend fun import(uri: Uri, displayName: String?): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = displayName?.takeIf { it.isNotBlank() } ?: "model$EXT"
                val name = (if (raw.endsWith(EXT, ignoreCase = true)) raw else raw + EXT)
                    .replace('/', '_')
                    .replace('\\', '_')
                val target = File(dir(), name)
                val tmp = File(dir(), "$name.part")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                } ?: error("打不开这个文件")
                if (target.exists()) target.delete()
                tmp.renameTo(target)
                Timber.i("[llm] 模型已导入：%s（%d 字节）", name, target.length())
                target
            }
        }

    /** 删掉所有非内置的模型（内置的会随 APK 重新解压出来）。 */
    fun removeImported(): Int {
        val builtin = builtinAssetName()
        var n = 0
        dir().listFiles()?.forEach { f ->
            if (f.name == builtin || f.name == ".extracted") return@forEach
            if (f.delete()) n++
        }
        return n
    }

    /** 给日志 / 设置页用的一句话。 */
    fun statusText(): String {
        val f = current()
        return if (f == null) {
            if (builtinAssetName() != null) "内置模型待解压" else "没有模型"
        } else {
            "${f.name} · ${f.length() / 1024 / 1024} MB"
        }
    }
}