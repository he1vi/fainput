/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.databinding.ActivityClipboardEditBinding
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.str

class ClipboardEditActivity : Activity() {

    private val scope: CoroutineScope = MainScope()

    private lateinit var editText: EditText

    private var entryId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        val binding = ActivityClipboardEditBinding.inflate(layoutInflater).apply {
            editText = clipboardEditText
            clipboardEditCancel.setOnClickListener { finish() }
            clipboardEditOk.setOnClickListener { finishEditing() }
            clipboardEditCopy.setOnClickListener { finishEditing(copy = true) }
        }
        setContentView(binding.root)
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        processIntent(intent)
    }

    private fun finishEditing(copy: Boolean = false) {
        val str = editText.str
        scope.launch {
            ClipboardManager.updateText(entryId, str)
            if (copy) {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("", str))
            }
        }
        finish()
    }

    /**
     * 【fainput 新功能·懒加载】超过这么多字，就**不整篇塞进 EditText** 了。
     *
     * - 10 万字 ≈ 200KB：`setText` 只排一次版，还能接受
     * - 一本小说 1MB 起步：塞进 EditText 会明显卡主线程
     *
     * ⚠️ 注意"分块 append 到 EditText"**不是**解法 —— 那是 **O(n²)**
     *    （每次 append 都重排全文），比一次 setText 还糟。
     *    超长的一律交给 [ClipboardTextViewerActivity]：每块一个 View，滚动到哪加载到哪。
     *
     * （写成 `val` 而不是 `const val`：`const` 只允许出现在**顶层或 companion object**，
     *   而这个类只有一个实例，不值得为它单开一个 companion。）
     */
    private val FULL_LOAD_LIMIT = 100_000

    private fun setEntry(entry: ClipboardEntry) {
        entryId = entry.id
        val text = entry.text
        if (text.length <= FULL_LOAD_LIMIT) {
            editText.setText(text)
            return
        }
        // ---- 超长：交给只读分块阅读器，本页直接退出 ----
        // 于是 EditText 永远拿不到一本小说，"只读 vs 可编辑"那套状态也就不需要了 ——
        // 保存出口只剩一条，而且它手上一定是**完整文本**。
        startActivity(
            Intent(this, ClipboardTextViewerActivity::class.java)
                .putExtra(ClipboardTextViewerActivity.EXTRA_ID, entry.id)
        )
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        scope.launch {
            intent.run {
                if (getBooleanExtra(LAST_ENTRY, false)) {
                    ClipboardManager.lastEntry
                } else {
                    ClipboardManager.get(getIntExtra(ENTRY_ID, -1))
                }
            }?.let { setEntry(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ENTRY_ID = "id"
        const val LAST_ENTRY = "last_entry"
    }
}
