/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.translate.Translator
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment

/**
 * 【fainput / F 阶段】翻译页 —— 纯本地模型，离线。
 *
 * 引擎与取舍见 [Translator]（**不用 ML Kit**，因为它会在权限表上开 INTERNET）。
 *
 * 这一页只干四件事：**输入 · 选目标语言 · 翻译 · 复制**。
 */
class TranslateFragment : PaddingPreferenceFragment() {

    private lateinit var pSource: Preference
    private lateinit var pResult: Preference
    private lateinit var pTarget: Preference

    private var source: String = ""
    private var result: String = ""
    private var target: Translator.Lang = Translator.Lang.EN

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            val catState = PreferenceCategory(context).apply { title = "状态" }
            addPreference(catState)

            pSource = Preference(context).apply {
                title = "原文"
                isSelectable = false
            }
            pResult = Preference(context).apply {
                title = "译文"
                setOnPreferenceClickListener {
                    copyResult()
                    true
                }
            }
            catState.addPreference(pSource)
            catState.addPreference(pResult)

            val catAct = PreferenceCategory(context).apply { title = "操作" }
            addPreference(catAct)

            catAct.addPreference(Preference(context).apply {
                title = "输入"
                setOnPreferenceClickListener {
                    askSource()
                    true
                }
            })
            pTarget = Preference(context).apply {
                title = "目标"
                setOnPreferenceClickListener {
                    target = target.next()
                    render()
                    true
                }
            }
            catAct.addPreference(pTarget)
            catAct.addPreference(Preference(context).apply {
                title = "翻译"
                setOnPreferenceClickListener {
                    doTranslate()
                    true
                }
            })
        }
        render()
    }

    private fun render() {
        pSource.summary = preview(source)
        pResult.summary = preview(result)
        pTarget.summary = target.label
    }

    /** 列表里只放一眼能看出来的长度。 */
    private fun preview(s: String): String = when {
        s.isEmpty() -> "—"
        s.length <= 24 -> s
        else -> s.take(24) + "…"
    }

    private fun askSource() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setText(source)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("原文")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                source = input.text?.toString().orEmpty().trim()
                // 目标语言跟着原文走一次 —— 中→英、英→中 是最常见的那次
                if (source.isNotEmpty()) {
                    target = if (Translator.guess(source) == Translator.Lang.ZH) {
                        Translator.Lang.EN
                    } else {
                        Translator.Lang.ZH
                    }
                }
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doTranslate() {
        if (source.isEmpty()) {
            toast("还没有原文")
            return
        }
        result = ""
        render()
        pResult.summary = "翻译中…"
        lifecycleScope.launch {
            result = Translator.translate(source, target)
                .fold({ it }, { "❌ ${it.message}" })
            render()
        }
    }

    private fun copyResult() {
        if (result.isEmpty()) return
        val cm = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("译文", result))
        toast("已复制")
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}