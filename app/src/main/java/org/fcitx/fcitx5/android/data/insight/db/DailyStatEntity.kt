/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 日聚合表 —— 存储分层的**冷表**。
 *
 * ## 为什么需要它
 *
 * `input_event` 是逐条事实表。按每天 600 条、每条约 200 字节算：
 *
 * ```
 * 120 KB/天  →  44 MB/年  →  五年就是 220 MB
 * ```
 *
 * **不算爆炸，但会一直涨，且查询会越来越慢。**
 *
 * ## 分层策略
 *
 * ```
 * input_event（热，逐条）   ← 只保留最近 90 天，可全文检索
 *        ↓ 每天归档（安静时跑）
 * daily_stat（冷，聚合）    ← 永久保留，一行 = 一天
 *        ↓
 *   DELETE 原始行 + VACUUM（把文件真正缩小）
 * ```
 *
 * ## 结果
 *
 * | 表 | 大小 | 保留 |
 * |---|---|---|
 * | `input_event` | ~11 MB | 90 天滚动 |
 * | `daily_stat` | ~18 MB/年 | 永久 |
 *
 * **年报所需的全部数据都在 `daily_stat` 里，一条不丢。**
 */
@Entity(tableName = DailyStatEntity.TABLE_NAME)
data class DailyStatEntity(
    /** `yyyyMMdd`，例如 20260928 —— 直接用整数，方便范围查询和排序 */
    @PrimaryKey val day: Int,

    /** 当天提交次数 */
    val events: Int,

    /** 当天上屏字符数 */
    val chars: Long,

    /** 当天"引擎放行的键"数（注意：拼音输入时这个值是 0，见 InsightRecorder 注释） */
    val keys: Long,

    /** 当天预编辑字符总数 —— **这是真正的"码长代价"** */
    val preeditChars: Long,

    /** 当天所有提交间隔之和 */
    val durationMs: Long,

    /** 首选命中次数（candidateIndex == 0） */
    val candidateHits: Int,

    /** 有候选信息的样本数（candidateIndex >= 0）—— 上面那个数的分母 */
    val candidateSamples: Int,

    /** 翻页次数 */
    val pageTurns: Int,

    /**
     * 当天高频词快照，格式 `词:次数|词:次数|…`（最多 50 个）。
     *
     * 为什么不建关联表：这是**冷数据里的展示用快照**，
     * 累计词频已经有 `word_stat` 了，这里只需要"那一天"的。
     * 用字符串存省一张表、省一次 JOIN。
     */
    val topWords: String,

    /** 归档时间 */
    val archivedAt: Long,
) {
    companion object {
        const val TABLE_NAME = "daily_stat"
    }
}