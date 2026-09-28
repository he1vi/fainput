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
 * ⇒ 所以这一页把**后端 / 模型文件 / 是否真能载入**三件事分开显示。
 * **能载入才算数。**
 *
 * ## 两条路，一份代码
 *
 * - 内置（定版 APK）：首启解压到私有目录
 * - 导入（平时）：用系统文件选择器挑一个 `.gguf`
 *
 * 上层只认一个 `File` —— 页面逻辑只有一套。
 *
 * ## ⚠️ 状态**现算**，绝不存字段
 *
 * 踩过的坑：把"已载入"的真相存进 Fragment 字段 ⇒ 退出重进就显示"未载入"，
 * 用户看到的是"**模型不见了**"（其实文件一直在 `files/llm/`，丢的只是内存里那个实例）。
 *
 * **Fragment 会被销毁重建，native 静态状态不会。** 所以：
 *
 * | 问什么 | 问谁 |
 * |---|---|
 * | 后端编进来没 | `LlmNative.isAvailable` |
 * | 模型文件在不在 | `LlmModel.current()`（扫磁盘）|
 * | 载入了没 | `LlmNative.describe()`（空串 = 没载入）|
 *
 * 再加上 `onResume` 里一次**自动载回** —— 于是"退出重进"看到的就是"已载入"。
 */
class LlmModelFragment : PaddingPreferenceFragment() {

    private lateinit var catState: PreferenceCategory
    private lateinit var pModel: Preference
    private lateinit var pBackend: Preference
    private lateinit var pLoaded: Preference
    private lateinit var pResult: Preference
    private lateinit var catActions: PreferenceCategory

    /** 上次「测试」跑出来的句子 / 报错。**只放结果**，不放"模型在不在"。 */
    private var lastResult: String = ""

    /** 正在后台载入 —— 防止 `onResume` 重复触发。 */
    private var loading: Boolean = false

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
            pResult = infoRow("结果")
            catState.addPreference(pBackend)
            catState.addPreference(pModel)
            catState.addPreference(pLoaded)
            catState.addPreference(pResult)

            catActions = PreferenceCategory(context).apply { title = "操作" }
            addPreference(catActions)

            catActions.addPreference(Preference(context).apply {
                title = "导入模型"
                setOnPreferenceClickListener {
                    picker.launch(arrayOf("*/*"))
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "载入模型"
                setOnPreferenceClickListener {
                    loadModel(manual = true)
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "测试"
                setOnPreferenceClickListener {
                    trialRun()
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "释放模型"
                setOnPreferenceClickListener {
                    LlmNative.release()
                    render()
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "删除导入的模型"
                setOnPreferenceClickListener {
                    val n = LlmModel.removeImported()
                    LlmNative.release()
                    render()
                    toast("已删除 $n 个文件")
                    true
                }
            })
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        // 回到这一页就把模型载回来 —— **载入过的东西不该"退出重进就没了"。**
        // （模型文件一直躺在 files/llm/，丢的只是内存里那个实例。）
        autoLoad()
    }

    private fun infoRow(title: String): Preference = Preference(requireContext()).apply {
        this.title = title
        isSelectable = false
    }

    /** 状态一律**现算**：后端问 native，模型文件问磁盘，载入状态问 native。 */
    private fun render() {
        pBackend.summary = if (LlmNative.isAvailable) {
            LlmNative.systemInfoText().take(60)
        } else {
            "未编入"
        }
        pModel.summary = LlmModel.current()?.let {
            "${it.name} · ${it.length() / 1024 / 1024} MB"
        } ?: "没有"
        pLoaded.summary = when {
            loading -> "载入中…"
            LlmNative.describe().isEmpty() -> "未载入"
            else -> {
                val mb = LlmNative.sizeBytes() / 1024 / 1024
                val params = LlmNative.paramCount() / 1_000_000
                "${LlmNative.describe()} · ${params}M 参数 · ${mb} MB"
            }
        }
        pResult.summary = lastResult.ifEmpty { "—" }
    }

    /**
     * 磁盘上有模型、内存里没载入 ⇒ **自动载入一次**。
     *
     * 这就是"退出重进还是已载入"的原因。
     * 幂等：已经载入 / 正在载入 / 没文件，都是空操作。
     */
    private fun autoLoad() {
        if (loading) return
        if (!LlmNative.isAvailable) return
        if (LlmNative.describe().isNotEmpty()) return
        if (LlmModel.current() == null) return
        loadModel(manual = false)
    }

    /** [manual] = 用户点的（失败要弹提示）；自动载入静默失败。 */
    private fun loadModel(manual: Boolean) {
        val file = LlmModel.current()
        if (file == null) {
            if (manual) toast("没有模型文件")
            return
        }
        loading = true
        render()
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                // 内置的还没解压就先解压（幂等）
                LlmModel.ensureExtracted()
                LlmNative.loadModel(file.absolutePath)
            }
            loading = false
            if (err != null) lastResult = "❌ $err"
            render()
        }
    }

    /**
     * 跑一次生成。
     *
     * 「能载入」只证明模型文件没坏；「能生成」才证明
     * tokenize → decode → 采样 → detokenize **整条链路都对**。
     * 中间任何一环写错，表现都是"载入成功但什么都不输出" —— 那种失败最难查。
     */
    private fun trialRun() {
        when {
            !LlmNative.isAvailable -> {
                toast("后端未编入")
                return
            }

            LlmModel.current() == null -> {
                toast("没有模型文件")
                return
            }
        }
        lastResult = "正在生成…"
        render()
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
            lastResult = out ?: "❌ 生成失败"
            render()
        }
    }

    private fun importModel(uri: Uri) {
        val name = queryName(uri)
        lifecycleScope.launch {
            toast("正在导入…")
            LlmModel.import(uri, name).onSuccess { file ->
                lastResult = ""
                render()
                toast("已导入：${file.name}（${file.length() / 1024 / 1024} MB）")
                loadModel(manual = false)
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