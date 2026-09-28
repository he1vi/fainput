/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

/**
 * 【fainput 新功能】剪贴板**列表用的轻量投影** —— 绝不加载全文。
 *
 * ## 为什么必须单独一个类型，而不是复用 [ClipboardEntry]
 *
 * 列表原来直接用 [ClipboardEntry]（带 `text` 全文）。复制一本小说之后：
 *
 * - 一页 16 条 → 一次把 **16 MB** 文本读进内存
 * - `DiffUtil.ItemCallback` 每次比较都要**逐字符比 1 MB 的 String**
 * - `TextView.setText(1MB)` 虽然 `maxLines=4` 截断显示，但赋值本身就很贵
 *
 * 而列表**真正需要的只有两样**：一段预览 + 总长度。
 *
 * ## 全文什么时候才取
 *
 * 只有「**真的要粘贴 / 分享 / 查看**」时，才按 [id] 取一次
 * （`ClipboardManager.textOf(id)`）。查看超长文本走分块读
 * （`ClipboardManager.textChunk`），永远不整篇进内存。
 */
data class ClipboardRow(
    val id: Int,

    /** 预览片段 —— SQL 侧 `substr` 出来的，最多 [PREVIEW_CHARS] 字。 */
    val preview: String,

    /**
     * **全文长度**（字符数，不是字节）。
     *
     * SQL 侧 `length(text)` 拿到的，**不读内容** —— 所以面板能显示
     * 「…（8,432 字）」这种信息而完全不碰那 8 千字。
     */
    val size: Int,

    val pinned: Boolean,
    val timestamp: Long,
    val sensitive: Boolean,
) {
    companion object {
        /**
         * 预览最多取多少字。
         *
         * 面板一屏只显示 4 行（`ClipboardEntryUi` 的 `maxLines = 4`），
         * 200 字远超一屏可见量 —— 再多取只是白白搬内存。
         */
        const val PREVIEW_CHARS = 200
    }
}