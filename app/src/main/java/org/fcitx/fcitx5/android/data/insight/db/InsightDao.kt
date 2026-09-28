/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 所有查询都在这里。写操作都是 suspend —— 由 [org.fcitx.fcitx5.android.data.insight.InsightRecorder]
 * 在 IO 线程调度，**永远不会阻塞打字**。
 */
@Dao
interface InsightDao {

    // ==================== session ====================

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE ${SessionEntity.TABLE_NAME} SET endTime=:endTime WHERE id=:id")
    suspend fun closeSession(id: Long, endTime: Long)

    @Query("SELECT * FROM ${SessionEntity.TABLE_NAME} ORDER BY startTime DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM ${SessionEntity.TABLE_NAME}")
    suspend fun sessionCount(): Int

    // ==================== input_event ====================

    @Insert
    suspend fun insertEvent(event: InputEventEntity): Long

    @Query("SELECT COUNT(*) FROM ${InputEventEntity.TABLE_NAME}")
    suspend fun eventCount(): Int

    @Query("SELECT * FROM ${InputEventEntity.TABLE_NAME} ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int): List<InputEventEntity>

    @Query("SELECT * FROM ${InputEventEntity.TABLE_NAME} ORDER BY timestamp ASC")
    suspend fun allEvents(): List<InputEventEntity>

    @Query("SELECT * FROM ${InputEventEntity.TABLE_NAME} WHERE level=1 AND text IS NOT NULL ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentPlainEvents(limit: Int): List<InputEventEntity>

    /** 首选命中率分子：直接选中第 1 个候选的次数 */
    @Query("SELECT COUNT(*) FROM ${InputEventEntity.TABLE_NAME} WHERE candidateIndex=0")
    suspend fun firstCandidateHits(): Int

    /** 首选命中率分母：有候选列表的提交次数 */
    @Query("SELECT COUNT(*) FROM ${InputEventEntity.TABLE_NAME} WHERE candidateIndex>=0")
    suspend fun eventsWithCandidates(): Int

    /** 翻过页的提交次数 */
    @Query("SELECT COUNT(*) FROM ${InputEventEntity.TABLE_NAME} WHERE pageTurns>0")
    suspend fun pagedEvents(): Int

    /** 总按键数 */
    @Query("SELECT IFNULL(SUM(keyCount),0) FROM ${InputEventEntity.TABLE_NAME}")
    suspend fun totalKeys(): Long

    /** 总提交字符数 */
    @Query("SELECT IFNULL(SUM(textLength),0) FROM ${InputEventEntity.TABLE_NAME}")
    suspend fun totalChars(): Long

    /** 总输入耗时（毫秒） */
    @Query("SELECT IFNULL(SUM(durationMs),0) FROM ${InputEventEntity.TABLE_NAME}")
    suspend fun totalDurationMs(): Long

    /** 平均码长（预编辑字母数 / 提交字符数），衡量"打一个字要敲几下" */
    @Query("SELECT IFNULL(SUM(preeditLength),0) FROM ${InputEventEntity.TABLE_NAME}")
    suspend fun totalPreeditChars(): Long

    /** 各级别各有多少条 —— 用来在 UI 上展示"保护了多少敏感输入" */
    @Query("SELECT COUNT(*) FROM ${InputEventEntity.TABLE_NAME} WHERE level=:level")
    suspend fun countByLevel(level: Int): Int

    // ==================== word_stat ====================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWord(word: WordStatEntity): Long

    @Query(
        """UPDATE ${WordStatEntity.TABLE_NAME}
           SET count = count + 1,
               lastSeen = :now,
               indexSum = indexSum + :indexSum,
               indexCount = indexCount + :indexCount
           WHERE word = :word"""
    )
    suspend fun bumpWord(word: String, now: Long, indexSum: Int, indexCount: Int)

    @Query("SELECT * FROM ${WordStatEntity.TABLE_NAME} ORDER BY count DESC LIMIT :limit")
    suspend fun topWords(limit: Int): List<WordStatEntity>

    @Query("SELECT COUNT(*) FROM ${WordStatEntity.TABLE_NAME}")
    suspend fun wordCount(): Int

    // ==================== 导出 / 清空 ====================

    @Query("DELETE FROM ${InputEventEntity.TABLE_NAME}")
    suspend fun wipeEvents()

    @Query("DELETE FROM ${WordStatEntity.TABLE_NAME}")
    suspend fun wipeWords()

    @Query("DELETE FROM ${SessionEntity.TABLE_NAME}")
    suspend fun wipeSessions()
}