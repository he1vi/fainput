/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次「提交」（`CommitStringEvent`）的完整快照。
 *
 * 这是整个数据层的**事实表** —— 所有分析都从这张表推出来。
 *
 * 字段分三类：
 * - 内容： [text] / [textHash] / [textLength]，三选一由 [level] 决定
 * - 上下文： [preeditLength] / [candidateIndex] / [candidateCount] / [pageTurns]
 * - 代价： [keyCount] / [durationMs]
 */
@Entity(
    tableName = InputEventEntity.TABLE_NAME,
    indices = [Index("sessionId"), Index("timestamp"), Index("textHash")]
)
data class InputEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 关联的会话。0 表示没有会话（异常路径）。 */
    val sessionId: Long = 0,

    val timestamp: Long,

    /** [org.fcitx.fcitx5.android.data.insight.InsightLevel.code] */
    val level: Int,

    /** L1 才有：明文。其他级别为 null。 */
    val text: String? = null,

    /** L2 才有：`SHA-256(每设备盐 + 原文)`。L1/L3 为 null。 */
    val textHash: String? = null,

    /** 提交内容的字符数。**所有级别都有**，因为长度本身不敏感。 */
    val textLength: Int,

    /**
     * 提交前预编辑串的长度（拼音字母数）。
     *
     * 用途：`preeditLength / textLength` = **码长效率**。
     * 打「你好」用 5 个字母 vs 用 8 个字母，能看出你打字的"笨拙度"。
     */
    @ColumnInfo(defaultValue = "0")
    val preeditLength: Int = 0,

    /** 选中的候选序号（0 = 首选）。-1 = 没有候选列表（直接上屏）。 */
    @ColumnInfo(defaultValue = "-1")
    val candidateIndex: Int = -1,

    /** 提交那一刻候选列表里有多少个词。 */
    @ColumnInfo(defaultValue = "0")
    val candidateCount: Int = 0,

    /** 这次提交之前翻了几页候选。 */
    @ColumnInfo(defaultValue = "0")
    val pageTurns: Int = 0,

    /** 从上次提交到这次提交之间按了多少次键（只算按下）。 */
    @ColumnInfo(defaultValue = "0")
    val keyCount: Int = 0,

    /** 这次提交距上次提交的毫秒数。 */
    @ColumnInfo(defaultValue = "0")
    val durationMs: Long = 0,
) {
    companion object {
        const val TABLE_NAME = "input_event"
    }
}