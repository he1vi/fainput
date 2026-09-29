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
import org.fcitx.fcitx5.android.data.insight.LstmNative
import org.fcitx.fcitx5.android.data.insight.LstmScorer
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment

/**
 * 【fainput / C 层】微 LM 模型页。
 *
 * ## 为什么非要有这一页
 *
 * 模型放在 `filesDir/lstm/` —— 应用私有目录。**非 root 设备上写不进去**：
 * `cp` 没权限、`run-as` 要 debuggable 包、`adb push` 认不了这个路径。
 *
 * 所以在补上这个入口之前，「把模型装上去」这件事**在真机上根本做不到**：
 * 训练脚本产出 7MB 的 `.fnlstm`，然后卡在门口。
 *
 * 这一页给一条**系统文件选择器**的路：从下载目录挑一个 `.fnlstm`，
 * 代码负责拷进私有目录 —— 和 LLM 那个模型页同构。
 *
 * ## 三个状态分开显示
 *
 * | 看起来 | 其实 |
 * |---|---|
 * | 装上了 | `native-lib` 可能没编进来 |
 * | 有文件 | 可能是个写了一半的 `.part` |
 * | 载入成功 | 只说明文件没坏，不代表打分对 |
 *
 * ⇒ 所以「内核 / 文件 / 已载入」各占一行，再加一个**试算** ——
 * 它跑一遍完整的 `tokenize → LSTM → logit`，数字合理才算真的通了。
 *
 * ## ⚠️ 状态**现算**，绝不存字段
 *
 * 和 LLM 页踩的是同一个坑：把"已载入"存进 Fragment 字段 ⇒ 退出重进
 * 就显示"未载入"，用户看到的是"**模型不见了**"。真相在 native 静态状态里，
 * 不在 Fragment 里。所以每次 `onResume` 都重新问一遍。
 */
class LstmModelFragment : PaddingPreferenceFragment() {

    private lateinit var pCore: Preference
    private lateinit var pFile: Preference
    private lateinit var pLoaded: Preference
    private lateinit var pResult: Preference

    /** 上次试算的结果。**只放结果**，不放"模型在不在"。 */
    private var lastResult: String = ""

    private var loading: Boolean = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            val catState = PreferenceCategory(context).apply { title = "状态" }
            addPreference(catState)
            pCore = infoRow("推理内核")
            pFile = infoRow("模型文件")
            pLoaded = infoRow("已载入")
            pResult = infoRow("试算")
            catState.addPreference(pCore)
            catState.addPreference(pFile)
            catState.addPreference(pLoaded)
            catState.addPreference(pResult)

            val catActions = PreferenceCategory(context).apply { title = "操作" }
            addPreference(catActions)
            // 【fainput / C 层】模型是**内置**在 APK 里的（`assets/lstm/model.fnlstm`），
            // 由 `DataManager` 同步到数据目录 —— **没有「导入」这条路**。
            // 这里只留诊断：载入 / 试算 / 释放。
            catActions.addPreference(Preference(context).apply {
                title = "载入模型"
                setOnPreferenceClickListener {
                    loadModel(manual = true)
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "试算"
                setOnPreferenceClickListener {
                    trialRun()
                    true
                }
            })
            catActions.addPreference(Preference(context).apply {
                title = "释放模型"
                setOnPreferenceClickListener {
                    LstmScorer.release()
                    render()
                    true
                }
            })
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        // 回到这一页就把模型载回来 —— 载入过的东西不该"退出重进就没了"。
        // （文件一直躺在 files/lstm/，丢的只是内存里那个实例。）
        loadModel(manual = false)
    }

    private fun infoRow(title: String): Preference = Preference(requireContext()).apply {
        this.title = title
        isSelectable = false
    }

    /** 状态一律**现算**：内核问 native，文件问磁盘，载入状态问 native。 */
    private fun render() {
        pCore.summary = if (LstmNative.isAvailable) "已编入" else "未编入"
        pFile.summary = LstmScorer.currentModel(requireContext())?.let {
            "${it.name} · ${it.length() / 1024 / 1024} MB"
        } ?: "暂无"
        pLoaded.summary = when {
            loading -> "载入中…"
            !LstmScorer.isReady -> "暂无"
            else -> LstmNative.dimsText()
        }
        pResult.summary = lastResult.ifEmpty { "暂无" }
    }

    /** [manual] = 用户点的（失败要弹提示）；自动载入静默失败。 */
    private fun loadModel(manual: Boolean) {
        if (loading) return
        if (!LstmNative.isAvailable) {
            if (manual) toast("内核未编入")
            return
        }
        if (LstmScorer.isReady) return
        if (LstmScorer.currentModel(requireContext()) == null) {
            if (manual) toast("没有模型文件")
            return
        }
        // 手动载入 = 明确要求重来一次，所以先清掉上次的失败记录
        if (manual) LstmScorer.invalidate()
        // ⚠️ 先把 context 抓出来：协程可能跑在 Fragment 分离之后，
        //    那时 requireContext() 会抛 IllegalStateException。
        val appCtx = requireContext().applicationContext
        loading = true
        render()
        lifecycleScope.launch {
            // 读几 MB 文件，必须离开主线程
            withContext(Dispatchers.IO) { LstmScorer.init(appCtx) }
            loading = false
            if (!LstmScorer.isReady && manual) lastResult = "❌ 载入失败"
            render()
        }
    }

    /**
     * 跑一次打分。
     *
     * 「能载入」只说明文件没坏。**「分数算得出来」才说明整条链路是通的** ——
     * 词表 / 嵌入 / 两层 LSTM / 输出投影，以及 JNI 传参和字符串切分，
     * 中间任何一环写错，表现都是"载入成功但分数是 0"。
     */
    private fun trialRun() {
        if (!LstmScorer.isReady) {
            toast("还没载入")
            return
        }
        lastResult = "正在算…"
        render()
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                // 同一个上文下比两个候选：合理的那个分数应该更高
                val a = LstmNative.score("今天天气", "很好")
                val b = LstmNative.score("今天天气", "喵呜")
                "很好 %+.2f · 喵呜 %+.2f".format(a, b)
            }
            lastResult = out
            render()
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}