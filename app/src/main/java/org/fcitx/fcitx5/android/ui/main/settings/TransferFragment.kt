/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.transfer.TransferPack
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 【fainput / H】「备份」页 —— 三条原则里**最后一条**：**数据能带走**。
 *
 * ## 这一页在回答什么
 *
 * | 原则 | 之前 |
 * |---|---|
 * | 数据不出设备 | ✅ 早就成立 |
 * | 敏感内容不落盘 | ✅ 早就成立 |
 * | **数据能带走** | ❌ 直到这一页 |
 *
 * 三个动作：**导出**（加密成一个文件）· **导入**（换机搬回来）· **清空**。
 *
 * ## 密码是唯一的口令
 *
 * 加密细节在 [TransferPack]。这一页只负责问密码、选文件、显示结果。
 * **不存密码、不做密码找回** —— 忘了就没了，这是加密的代价，也是它的意义。
 */
class TransferFragment : PaddingPreferenceFragment() {

    private lateinit var pCounts: Preference
    private lateinit var pLast: Preference

    /** 最近一次动作的结果。**只放结果**，不放"有没有数据"（那个现算）。 */
    private var last: String = ""

    private val exportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) askPass(uri, export = true)
        }

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) askPass(uri, export = false)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            val catState = PreferenceCategory(context).apply { title = "状态" }
            addPreference(catState)
            pCounts = infoRow("库里")
            pLast = infoRow("最近")
            catState.addPreference(pCounts)
            catState.addPreference(pLast)

            val catAct = PreferenceCategory(context).apply { title = "操作" }
            addPreference(catAct)

            catAct.addPreference(Preference(context).apply {
                title = "导出"
                setOnPreferenceClickListener {
                    val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    exportPicker.launch("fainput-$stamp.fainput")
                    true
                }
            })
            catAct.addPreference(Preference(context).apply {
                title = "导入"
                setOnPreferenceClickListener {
                    importPicker.launch(arrayOf("*/*"))
                    true
                }
            })
            catAct.addPreference(Preference(context).apply {
                title = "清空"
                setOnPreferenceClickListener {
                    confirmWipe()
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
        pLast.summary = last.ifEmpty { "—" }
        lifecycleScope.launch {
            val c = TransferPack.counts()
            pCounts.summary = if (c.isEmpty()) "暂无" else {
                val events = c["input_event"] ?: 0
                val words = c["word_stat"] ?: 0
                val pairs = c["word_bigram"] ?: 0
                String.format(Locale.US, "%,d 事件 · %,d 词 · %,d 搭配", events, words, pairs)
            }
        }
    }

    /** 导出 = 设一个新密码；导入 = 输入这个包原本那个。 */
    private fun askPass(uri: Uri, export: Boolean) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "密码"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (export) "设置密码" else "输入密码")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val pass = input.text?.toString().orEmpty()
                if (pass.length < 6) {
                    toast("密码至少 6 位")
                } else if (export) {
                    doExport(uri, pass)
                } else {
                    doImport(uri, pass)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExport(uri: Uri, pass: String) {
        last = "正在导出…"
        render()
        lifecycleScope.launch {
            last = TransferPack.export(uri, pass)
                .fold({ String.format(Locale.US, "已导出 %,d 行", it.rows) }, { "❌ ${it.message}" })
            render()
        }
    }

    private fun doImport(uri: Uri, pass: String) {
        last = "正在导入…"
        render()
        lifecycleScope.launch {
            last = TransferPack.import(uri, pass)
                .fold({ String.format(Locale.US, "已导入 %,d 行", it.rows) }, { "❌ ${it.message}" })
            render()
        }
    }

    private fun confirmWipe() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空所有数据？")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val n = TransferPack.wipe()
                    last = "已清空 $n 项"
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}