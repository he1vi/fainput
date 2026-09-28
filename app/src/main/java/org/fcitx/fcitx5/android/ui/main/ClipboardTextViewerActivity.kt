/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.ClipData
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager

/**
 * 【fainput 新功能】**超长文本的只读分块阅读器**。
 *
 * ## 为什么必须有它
 *
 * 一本小说塞进 `EditText` 会卡死主线程；而"分块 append 到可见的 EditText"
 * 更糟 —— 那是 **O(n²)**（每次 append 都重排全文）。
 *
 * ⇒ 正确做法：**每一块自己一个 View**（RecyclerView 的一项）。
 * 于是每一块只排版自己的 8000 字，滚动到哪加载到哪，
 * **全文永远不会同时存在于内存里**。
 *
 * ## 懒加载怎么走
 *
 * ```
 * onCreate       → textLength(id)          ← 只问长度，不读内容（O(1)）
 *                 算出块数，列表先铺 null
 * bind 到某一块   → textChunk(id, off, 8000)  ← 只为**看得见的那几块**取内容
 * ```
 *
 * 数据层是 SQLite 的 `substr(text, :offset, :len)` —— 服务端切片，
 * 不经过完整字符串。
 */
class ClipboardTextViewerActivity : Activity() {

    companion object {
        /** 传条目 id 用的 key。 */
        const val EXTRA_ID = "id"

        /** 每块多少字。8000 字 ≈ 16KB，一屏远看不完 —— 再大就浪费了。 */
        const val CHUNK = 8_000
    }

    private val scope = MainScope()

    private var entryId = -1
    private var total = 0

    /** 每一块的内容：`null` = 还没要过，`""` = 正在要，其余 = 已拿到。 */
    private val chunks = ArrayList<String?>()

    private lateinit var adapter: ChunkAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entryId = intent.getIntExtra(EXTRA_ID, -1)

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ClipboardTextViewerActivity)
        }
        adapter = ChunkAdapter()
        list.adapter = adapter

        // 【用户要求】要能「全部复制」。
        // 这是个裸 Activity（没有 ActionBar），菜单项没地方挂 ——
        // 直接在最上面放一个按钮，比藏进菜单更好找。
        val copyAll = Button(this).apply {
            text = "复制全部"
            setOnClickListener { copyAllToClipboard() }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    copyAll,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                addView(
                    list,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                )
            }
        )

        scope.launch {
            total = runCatching { ClipboardManager.textLength(entryId) }.getOrNull() ?: 0
            title = "只读 · 共 $total 字"
            val n = if (total <= 0) 0 else (total + CHUNK - 1) / CHUNK
            chunks.clear()
            repeat(n) { chunks.add(null) }
            adapter.notifyDataSetChanged()
        }
    }

    /**
     * 把**整篇**复制到系统剪贴板。
     *
     * 走**分块读**而不是 `textOf(id)`：后者会把整篇一次性拉进内存，
     * 一本小说的量级就是几十 MB 的单次分配。
     * （拼出来的字符串和原文一样大 —— 剪贴板本来就要完整内容 ——
     *   但峰值可控，而且拼的过程在 IO 线程。）
     *
     * 另外：每个块都 `setTextIsSelectable(true)`，所以**精确复制某一段**
     * 直接在里面长按选中就行，不用另做功能。
     */
    private fun copyAllToClipboard() {
        if (entryId < 0) return
        scope.launch {
            val full = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                var offset = 1                       // ⚠️ SQLite 的 substr 是 1-based
                while (true) {
                    val chunk = runCatching {
                        ClipboardManager.textChunk(entryId, offset, CHUNK)
                    }.getOrNull() ?: break
                    if (chunk.isEmpty()) break
                    sb.append(chunk)
                    offset += chunk.length
                }
                sb.toString()
            }
            if (full.isEmpty()) {
                Toast.makeText(this@ClipboardTextViewerActivity, "没有内容", Toast.LENGTH_SHORT).show()
                return@launch
            }
            getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("", full))
            Toast.makeText(
                this@ClipboardTextViewerActivity, "已复制 ${full.length} 字", Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 取第 [i] 块。**一次只取一块**，且不重复请求。 */
    private fun ensureChunk(i: Int) {
        if (i < 0 || i >= chunks.size) return
        if (chunks.getOrNull(i) != null) return
        // 先占位，避免同一块被 bind 两次时发两个请求
        chunks[i] = ""
        scope.launch {
            // ⚠️ SQLite 的 substr 是 **1-based** —— 第 0 块从 1 开始
            val text = runCatching {
                ClipboardManager.textChunk(entryId, i * CHUNK + 1, CHUNK)
            }.getOrNull() ?: ""
            chunks[i] = text
            adapter.notifyItemChanged(i)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private inner class ChunkAdapter : RecyclerView.Adapter<ChunkAdapter.VH>() {

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                // 允许长按选中复制 —— 这一页是只读的，选区是不会被写回的
                setTextIsSelectable(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return VH(tv)
        }

        override fun getItemCount(): Int = chunks.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            when (val text = chunks.getOrNull(position)) {
                null -> {
                    holder.tv.gravity = Gravity.CENTER
                    holder.tv.text = "…"
                    ensureChunk(position)
                }

                "" -> {
                    holder.tv.gravity = Gravity.CENTER
                    holder.tv.text = ""
                }

                else -> {
                    holder.tv.gravity = Gravity.START
                    holder.tv.text = text
                }
            }
        }
    }
}