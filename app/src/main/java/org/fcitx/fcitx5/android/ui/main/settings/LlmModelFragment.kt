/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.llm.LlmModel
import org.fcitx.fcitx5.android.data.llm.LlmNative
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment

/**
 * 【fainput / L3】模型页 —— 让"模型到底在不在、能不能用"变成**看得见**的。
 *
 * ## 这一页为什么必须存在
 *
 * 模型这件事有三个独立的失败点，而且长得都不像失败：
 *
 * | 看起来 | 其实 |
 * |---|---|
 * | 构建成功 | 可能 `native-llm` 编成了桩（llama.cpp 没拉到） |
 * | 装上了 | 可能没有内置模型（`model_mode=none` 构建的） |
 * | 有模型文件 | 可能是半个文件（下载中断） |
 *
 * ⇒ 所以这一页把**后端 / 模型文件 / 是否真能载入**三件事分开显示，
 * 而不是笼统给一个"LLM 可用"。**能载入才算数。**
 *
 * ## 两条路，一份代码
 *
 * - 内置（定版 APK）：首启解压到私有目录，这里显示"内置"
 * - 导入（平时）：用系统文件选择器挑一个 `.gguf`
 *
 * 上层只认一个 `File` —— 页面逻辑只有一套。
 */
class LlmModelFragment : PaddingPreferenceFragment() {

    private lateinit var catState: PreferenceCategory
    private lateinit var pModel: Preference
    private lateinit var pBackend: Preference
    private lateinit var pLoaded: Preference
    private lateinit var catActions: PreferenceCategory

    private var loadedDesc: String = ""

    /** 系统文件选择器。`.gguf` 没有注册 mime，只能放宽到所有文件。 */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            catState = PreferenceCategory(context).apply { title = "状态" }
            addPreference(catState)

            pBackend = infoRow("推理后端")
            pModel = infoRow("模型文件")
            pLoaded = infoRow("已载入")
            catState.addPreference(pBackend)
            catState.addPreference(pModel)
            catState.addPreference(pLoaded)

            catActions = PreferenceCategory(context).apply { title = "操作" }
            addPreference(catActions)

            catActions.addPreference(Preference(context).apply {
                title = "导入模型"
                summary = "选一个 .gguf（没有内置模型时用）"
                setOnPreferenceClickListener {
                    picker.launch(arrayOf("*/*"))
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "载入模型"
                summary = "后台线程加载，可能要几秒"
                setOnPreferenceClickListener {
                    loadModel()
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "试跑一句"
                summary = "让它写一句话 —— 这才是「整条链路都对」的证据"
                setOnPreferenceClickListener {
                    trialRun()
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "释放模型"
                setOnPreferenceClickListener {
                    LlmNative.release()
                    loadedDesc = ""
                    render()
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "删除导入的模型"
                summary = "内置的那个不受影响"
                setOnPreferenceClickListener {
                    val n = LlmModel.removeImported()
                    LlmNative.release()
                    loadedDesc = ""
                    render()
                    android.widget.Toast.makeText(
                        context, "已删除 $n 个文件", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            })
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun infoRow(title: String): Preference = Preference(requireContext()).apply {
        this.title = title
        isSelectable = false
    }

    private fun render() {
        // ① 后端：llama.cpp 到底编进来没有
        pBackend.summary = if (LlmNative.isAvailable) {
            LlmNative.systemInfoText().take(60)
        } else {
            "未编入（这个 APK 是用 model_mode=none 之类构建的，或者拉取 llama.cpp 失败）"
        }
        // ② 模型文件：磁盘上有没有
        val file = LlmModel.current()
        pModel.summary = if (file == null) {
            "没有。可以「导入模型」，或者用 model_mode=bundle 重新构建一个内置模型的版本。"
        } else {
            "${file.name} · ${file.length() / 1024 / 1024} MB"
        }
        // ③ 已载入：**这才是"能不能用"的唯一证据**
        pLoaded.summary = if (loadedDesc.isNotEmpty()) {
            loadedDesc
        } else {
            "未载入"
        }
    }

    private fun loadModel() {
        val file = LlmModel.current()
        if (file == null) {
            toast("没有模型文件")
            return
        }
        val ctx = requireContext()
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                // 内置的还没解压就先解压（幂等）
                LlmModel.ensureExtracted()
                LlmNative.loadModel(file.absolutePath)
            }
            loadedDesc = if (err == null) {
                val desc = LlmNative.describe()
                val mb = LlmNative.sizeBytes() / 1024 / 1024
                val params = LlmNative.paramCount() / 1_000_000
                "✅ $desc · ${params}M 参数 · ${mb} MB"
            } else {
                "❌ $err"
            }
            render()
        }
    }

    /**
     * 试跑一次生成。
     *
     * 为什么必须有这个按钮：**「能载入」只证明模型文件没坏**，
     * 而「能生成」才证明 tokenize → decode → 采样 → detokenize **整条链路都对**。
     * 中间任何一环写错，表现都是"载入成功但什么都不输出" —— 那种失败最难查。
     */
    private fun trialRun() {
        when {
            !LlmNative.isAvailable -> {
                toast("后端未编入（这个 APK 没带 llama.cpp）")
                return
            }

            LlmModel.current() == null -> {
                toast("没有模型文件 —— 先用 model_mode=artifact 构建并导入")
                return
            }
        }
        pLoaded.summary = "正在生成…（0.5B 模型大概几秒到几十秒）"
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                // 还没载入就先载入（幂等）
                if (LlmNative.describe().isEmpty()) {
                    LlmModel.current()?.let { LlmNative.loadModel(it.absolutePath) }
                }
                LlmNative.generateText(
                    LlmNative.chatml(
                        "你是输入法的后台助手。只用一句中文回答，不要解释。",
                        "用一句话说明：一个数据不出设备的输入法，对用户意味着什么？"
                    ),
                    maxTokens = 64
                )
            }
            loadedDesc = if (out == null) {
                "❌ 生成失败（细节看 logcat 的 fainput-llm）"
            } else {
                "✅ $out"
            }
            render()
        }
    }

    private fun importModel(uri: Uri) {
        val name = queryName(uri)
        lifecycleScope.launch {
            toast("正在导入…（大文件要一会儿）")
            val result = LlmModel.import(uri, name)
            result.onSuccess {
                loadedDesc = ""
                render()
                toast("已导入：${it.name}（${it.length() / 1024 / 1024} MB）")
            }.onFailure {
                toast("导入失败：${it.message}")
            }
        }
    }

    /** 从 Uri 取显示名 —— 用来给模型文件起名。 */
    private fun queryName(uri: Uri): String? = runCatching {
        requireContext().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}