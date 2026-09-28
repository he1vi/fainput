/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardRow
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.ClipboardTextViewerActivity
import org.fcitx.fcitx5.android.utils.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 【fainput 新功能】剪贴板**历史** —— 面板只给最近 2 小时，这里给全部。
 *
 * ## 为什么需要这一页
 *
 * 面板（候选栏上方那个）现在只显示最近 **2 小时**，而且**条目一条都没删**。
 * 那"更早的东西去哪找" —— 就是这一页。
 *
 * ## 懒加载（和面板同一条纪律）
 *
 * | 动作 | 取什么 | 代价 |
 * |---|---|---|
 * | 列表 | `substr(text,1,200)` + `length(text)` | 几百条也只搬几百×200 字 |
 * | 搜索 | SQL `instr` 匹配 + 只回命中片段 | **全文永不进内存** |
 * | 点一条 | `textOf(id)` 取一次全文 → 复制 | 只有这一刻搬全文 |
 *
 * ## 为什么点一下是"复制"
 *
 * 来这一页的人是想**取回**某段旧文字 —— 复制到系统剪贴板就能贴到任何地方。
 * 编辑 / 删除仍然在 IME 面板里（那是它的地盘）。
 */
class ClipboardHistoryFragment : PaddingPreferenceFragment() {

    private companion object {
        const val PAGE = 100
        const val MENU_SEARCH = 1

        /** 列表里一行最多显示多少字（只是**显示**，和存储无关）。 */
        const val ROW_HEAD = 60
    }

    private var query: String = ""
    private var loaded = 0
    private var exhausted = false
    private var loading = false

    private lateinit var catRows: PreferenceCategory
    private lateinit var pFooter: Preference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            catRows = PreferenceCategory(context).apply { title = "历史记录" }
            addPreference(catRows)
            pFooter = Preference(context).apply {
                setOnPreferenceClickListener { loadMore(); true }
            }
            addPreference(pFooter)
        }
        reload()
    }

    // ==================== 搜索 ====================

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "搜索")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SEARCH) {
            askSearch()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun askSearch() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(query)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("搜索剪贴板历史")
            .setView(input)
            .setNeutralButton("全部") { _, _ ->
                query = ""
                reload()
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("搜索") { _, _ ->
                query = input.str.trim()
                reload()
            }
            .show()
    }

    // ==================== 列表 ====================

    private fun reload() {
        loaded = 0
        exhausted = false
        loading = false
        catRows.removeAll()
        pFooter.title = if (query.isEmpty()) "全部记录" else "搜索结果「$query」"
        pFooter.summary = null
        loadMore()
    }

    private fun loadMore() {
        if (loading || exhausted) return
        loading = true
        val isSearch = query.isNotEmpty()
        val offset = loaded
        lifecycleScope.launch {
            val rows = runCatching {
                if (isSearch) {
                    // 搜索：SQL 侧匹配 + 只回片段，最多一屏
                    ClipboardManager.search(query, PAGE)
                } else {
                    // 浏览：分页取，一次 100 条
                    ClipboardManager.rowsPage(PAGE, offset)
                }
            }.getOrElse { emptyList() }
            rows.forEach { catRows.addPreference(rowOf(it)) }
            loaded += rows.size
            if (rows.size < PAGE) exhausted = true
            pFooter.isVisible = !exhausted || loaded == 0
            pFooter.title = when {
                loaded == 0 -> if (isSearch) "没有匹配" else "暂无记录"
                exhausted -> "共 $loaded 条"
                else -> "加载更多（已显示 $loaded 条）"
            }
            pFooter.summary = null
            pFooter.isSelectable = !exhausted
            loading = false
        }
    }

    private fun rowOf(r: ClipboardRow): Preference = Preference(requireContext()).apply {
        val head = r.preview.replace('\n', ' ').replace('\r', ' ')
            .take(ROW_HEAD)
            .let { if (r.sensitive) "•".repeat(it.length) else it }
        val ellipsis = if (r.size > ROW_HEAD) "…" else ""
        title = head + ellipsis
        summary = buildString {
            append(r.size)
            append(" 字 · ")
            append(timeText(r.timestamp))
            if (r.pinned) append(" · 已置顶")
            if (r.sensitive) append(" · 敏感")
        }
        setOnPreferenceClickListener {
            // 【用户要求】点一行 = **打开查看**，不再是直接复制。
            // 查看器里能看全文、能选中某一段精确复制、也能一键复制全部。
            startActivity(
                Intent(requireContext(), ClipboardTextViewerActivity::class.java)
                    .putExtra(ClipboardTextViewerActivity.EXTRA_ID, r.id)
            )
            true
        }
    }

    private fun timeText(ts: Long): String {
        val delta = System.currentTimeMillis() - ts
        return when {
            delta < 60_000L -> "刚刚"
            delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
            delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        }
    }
}