/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    /**
     * 【A1 热词加权】近 N 天每个词的提交次数 —— "你**现在**爱用什么"。
     *
     * 为什么不从 `word_stat` 算：那张表只有 `count / firstSeen / lastSeen`，
     * **没有按天的时间序列**，算不出"近 7 天频率"。
     * 而 `input_event` 有完整的 `timestamp + text`，且**本身保留 90 天**
     * —— 7 天窗口永远在里面。**不用建新表、不用迁移。**
     */
    @Query(
        """SELECT text AS word, COUNT(*) AS count
           FROM ${InputEventEntity.TABLE_NAME}
           WHERE level = 1 AND text IS NOT NULL AND timestamp >= :since
           GROUP BY text
           ORDER BY count DESC
           LIMIT :limit"""
    )
    suspend fun recentWords(since: Long, limit: Int): List<WordCount>

    // ==================== 存储分层：归档 ====================
    //
    // 背景：input_event 每年涨 ~44 MB，五年 220 MB。
    // 策略：只保留最近 90 天逐条数据，更早的按天聚合进 daily_stat 后删除原始行。
    // 详见 DailyStatEntity 的文档注释。

    /** 最早一条事件的时间戳。空表返回 null。 */
    @Query("SELECT MIN(timestamp) FROM ${InputEventEntity.TABLE_NAME}")
    suspend fun oldestEventTimestamp(): Long?

    /**
     * 按天聚合（只统计 `timestamp < :before` 的）。
     *
     * 日期用 `strftime(..., 'localtime')` —— 按**用户本地日期**分天，
     * 而不是 UTC。否则晚上 8 点后的输入会被算到"第二天"。
     */
    @Query(
        """SELECT
              CAST(strftime('%Y%m%d', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) AS day,
              COUNT(*) AS events,
              IFNULL(SUM(textLength), 0) AS chars,
              IFNULL(SUM(keyCount), 0) AS keys,
              IFNULL(SUM(preeditLength), 0) AS preeditChars,
              IFNULL(SUM(durationMs), 0) AS durationMs,
              IFNULL(SUM(CASE WHEN candidateIndex = 0 THEN 1 ELSE 0 END), 0) AS candidateHits,
              IFNULL(SUM(CASE WHEN candidateIndex >= 0 THEN 1 ELSE 0 END), 0) AS candidateSamples,
              IFNULL(SUM(pageTurns), 0) AS pageTurns
           FROM ${InputEventEntity.TABLE_NAME}
           WHERE timestamp < :before
           GROUP BY day"""
    )
    suspend fun aggregateBefore(before: Long): List<DailyAggregate>

    /**
     * 某个时间窗内的高频词 —— 归档时给每一天生成快照。
     *
     * 只看 `level = 1`（明文）且长度 ≥ 2 的，跟 `bumpWord` 的口径一致。
     */
    @Query(
        """SELECT text AS word, COUNT(*) AS count
           FROM ${InputEventEntity.TABLE_NAME}
           WHERE timestamp >= :from AND timestamp < :to
             AND level = 1 AND text IS NOT NULL AND LENGTH(text) >= 2
           GROUP BY text
           ORDER BY count DESC
           LIMIT :limit"""
    )
    suspend fun topWordsBetween(from: Long, to: Long, limit: Int): List<WordCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyStats(stats: List<DailyStatEntity>)

    /** @return 删除的行数 */
    @Query("DELETE FROM ${InputEventEntity.TABLE_NAME} WHERE timestamp < :before")
    suspend fun deleteEventsBefore(before: Long): Int

    @Query("SELECT * FROM ${DailyStatEntity.TABLE_NAME} ORDER BY day DESC LIMIT :limit")
    suspend fun recentDailyStats(limit: Int): List<DailyStatEntity>

    @Query("SELECT COUNT(*) FROM ${DailyStatEntity.TABLE_NAME}")
    suspend fun dailyStatCount(): Int

    /** 归档边界：返回 (最早时间戳, 待归档条数) */
    @Query("SELECT COUNT(*) FROM ${InputEventEntity.TABLE_NAME} WHERE timestamp < :before")
    suspend fun countEventsBefore(before: Long): Int

    // ==================== C 阶段：实时镜像 ====================
    //
    // 全部返回 Flow —— 数据库一变，UI 自动重组，**不需要任何刷新按钮**。
    // 这是 C 阶段"不是年报、是实时"的技术基础。

    /** 今天（`timestamp >= :dayStart`）的统计。每次上屏都会重新发射。 */
    @Query(
        """SELECT
              COUNT(*) AS events,
              IFNULL(SUM(textLength), 0) AS chars,
              IFNULL(SUM(keyCount), 0) AS keys,
              IFNULL(SUM(preeditLength), 0) AS preeditChars,
              IFNULL(SUM(durationMs), 0) AS durationMs,
              IFNULL(SUM(CASE WHEN candidateIndex = 0 THEN 1 ELSE 0 END), 0) AS candidateHits,
              IFNULL(SUM(CASE WHEN candidateIndex >= 0 THEN 1 ELSE 0 END), 0) AS candidateSamples,
              IFNULL(SUM(pageTurns), 0) AS pageTurns
           FROM ${InputEventEntity.TABLE_NAME}
           WHERE timestamp >= :dayStart"""
    )
    fun todayStatsFlow(dayStart: Long): Flow<StatsProjection>

    /** 全部时间（热表内）的统计 */
    @Query(
        """SELECT
              COUNT(*) AS events,
              IFNULL(SUM(textLength), 0) AS chars,
              IFNULL(SUM(keyCount), 0) AS keys,
              IFNULL(SUM(preeditLength), 0) AS preeditChars,
              IFNULL(SUM(durationMs), 0) AS durationMs,
              IFNULL(SUM(CASE WHEN candidateIndex = 0 THEN 1 ELSE 0 END), 0) AS candidateHits,
              IFNULL(SUM(CASE WHEN candidateIndex >= 0 THEN 1 ELSE 0 END), 0) AS candidateSamples,
              IFNULL(SUM(pageTurns), 0) AS pageTurns
           FROM ${InputEventEntity.TABLE_NAME}"""
    )
    fun totalStatsFlow(): Flow<StatsProjection>

    /** 已归档的冷数据合计 —— 用来把"历史"和"现在"加在一起 */
    @Query(
        """SELECT
              IFNULL(SUM(events), 0) AS events,
              IFNULL(SUM(chars), 0) AS chars,
              IFNULL(SUM(keys), 0) AS keys,
              IFNULL(SUM(preeditChars), 0) AS preeditChars,
              IFNULL(SUM(durationMs), 0) AS durationMs,
              IFNULL(SUM(candidateHits), 0) AS candidateHits,
              IFNULL(SUM(candidateSamples), 0) AS candidateSamples,
              IFNULL(SUM(pageTurns), 0) AS pageTurns
           FROM ${DailyStatEntity.TABLE_NAME}"""
    )
    fun archivedStatsFlow(): Flow<StatsProjection>

    /** 高频词榜（实时） */
    @Query("SELECT * FROM ${WordStatEntity.TABLE_NAME} ORDER BY count DESC LIMIT :limit")
    fun topWordsFlow(limit: Int): Flow<List<WordStatEntity>>

    /** 分级分布 —— 展示"保护了多少敏感输入" */
    @Query(
        """SELECT level AS level, COUNT(*) AS count
           FROM ${InputEventEntity.TABLE_NAME}
           GROUP BY level"""
    )
    fun levelCountsFlow(): Flow<List<LevelCount>>

    /** 最近几天的归档（有历史后才会有数据） */
    @Query("SELECT * FROM ${DailyStatEntity.TABLE_NAME} ORDER BY day DESC LIMIT :limit")
    fun recentDailyStatsFlow(limit: Int): Flow<List<DailyStatEntity>>

    // ==================== 导出 / 清空 ====================

    @Query("DELETE FROM ${InputEventEntity.TABLE_NAME}")
    suspend fun wipeEvents()

    @Query("DELETE FROM ${WordStatEntity.TABLE_NAME}")
    suspend fun wipeWords()

    @Query("DELETE FROM ${SessionEntity.TABLE_NAME}")
    suspend fun wipeSessions()
}