/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ClipboardDao {
    @Insert
    suspend fun insert(clipboardEntry: ClipboardEntry): Long

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET pinned=:pinned WHERE id=:id")
    suspend fun updatePinStatus(id: Int, pinned: Boolean)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET text=:text WHERE id=:id")
    suspend fun updateText(id: Int, text: String)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET timestamp=:timestamp WHERE id=:id")
    suspend fun updateTime(id: Int, timestamp: Long)

    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0")
    suspend fun itemCount(): Int

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE id=:id AND deleted=0 LIMIT 1")
    suspend fun get(id: Int): ClipboardEntry?

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE rowId=:rowId AND deleted=0 LIMIT 1")
    suspend fun get(rowId: Long): ClipboardEntry?

    @Query("SELECT EXISTS(SELECT 1 FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0)")
    suspend fun haveUnpinned(): Boolean

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0")
    suspend fun getAllUnpinned(): List<ClipboardEntry>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY pinned DESC, timestamp DESC")
    fun allEntries(): PagingSource<Int, ClipboardEntry>

    // ==================== 【fainput 新功能】懒加载原语 ====================
    //
    // 原则：**列表永远不加载全文**。SQLite 的 substr / length / instr
    // 都能在服务端做，所以预览、长度、搜索都不必把整篇文本搬进内存。

    /**
     * 列表用：只取「预览 + 长度」。
     *
     * `since` 是时间窗（面板只显示最近一段）。**置顶的不受时间窗限制** ——
     * 置顶的语义就是"别让它过期"。
     */
    @Query(
        """
        SELECT id,
               substr(text, 1, ${ClipboardRow.PREVIEW_CHARS}) AS preview,
               length(text) AS size,
               pinned, timestamp, sensitive
        FROM ${ClipboardEntry.TABLE_NAME}
        WHERE deleted=0 AND (pinned=1 OR timestamp >= :since)
        ORDER BY pinned DESC, timestamp DESC
        """
    )
    fun rowsSince(since: Long): PagingSource<Int, ClipboardRow>

    /**
     * 历史页用：**全部**条目（无时间窗），一页一页取。
     *
     * 和 [rowsSince] 一样只带「预览 + 长度」—— 历史页列几百条也不会搬全文。
     */
    @Query(
        """
        SELECT id,
               substr(text, 1, ${ClipboardRow.PREVIEW_CHARS}) AS preview,
               length(text) AS size,
               pinned, timestamp, sensitive
        FROM ${ClipboardEntry.TABLE_NAME}
        WHERE deleted=0
        ORDER BY pinned DESC, timestamp DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun rowsAll(limit: Int, offset: Int): List<ClipboardRow>

    /** 全文长度（字符数）—— 不读内容。 */
    @Query("SELECT length(text) FROM ${ClipboardEntry.TABLE_NAME} WHERE id=:id")
    suspend fun textLength(id: Int): Int?

    /**
     * 分块读全文 —— 超长文本**懒加载**的基础。
     *
     * 一次只取 [len] 个字，从 [offset] 开始。想看下一屏就再取一块。
     */
    @Query("SELECT substr(text, :offset, :len) FROM ${ClipboardEntry.TABLE_NAME} WHERE id=:id")
    suspend fun textChunk(id: Int, offset: Int, len: Int): String?

    /**
     * 在**所有条目**里搜 —— 匹配在 SQL 侧做（`instr`），
     * 只回「命中位置附近的一小段预览」，**全文永不进内存**。
     */
    @Query(
        """
        SELECT id,
               substr(text, max(1, instr(text, :q) - 20), ${ClipboardRow.PREVIEW_CHARS}) AS preview,
               length(text) AS size,
               pinned, timestamp, sensitive
        FROM ${ClipboardEntry.TABLE_NAME}
        WHERE deleted=0 AND instr(text, :q) > 0
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchRows(q: String, limit: Int): List<ClipboardRow>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE text=:text AND sensitive=:sensitive AND deleted=0 LIMIT 1")
    suspend fun find(text: String, sensitive: Boolean = false): ClipboardEntry?

    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0")
    suspend fun findAllIds(): IntArray

    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0")
    suspend fun findUnpinnedIds(): IntArray

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=1 WHERE id in (:ids)")
    suspend fun markAsDeleted(vararg ids: Int)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET DELETED=1 WHERE timestamp<:timestamp AND pinned=0 AND deleted=0")
    suspend fun markUnpinnedAsDeletedEarlierThan(timestamp: Long)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=0 WHERE id in (:ids) AND deleted=1")
    suspend fun undoDelete(vararg ids: Int)

    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=1")
    suspend fun realDelete()
}