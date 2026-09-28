/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 词频统计。**只统计 L1（明文）** —— L2/L3 不参与，因为：
 * - 没法按词聚合（只有哈希）
 * - 就算能，也不该把"你的验证码长什么样"变成统计特征
 *
 * 主键直接是 [word]：同一次提交的同一个词，靠 `ON CONFLICT` 累加。
 */
@Entity(tableName = WordStatEntity.TABLE_NAME)
data class WordStatEntity(
    @PrimaryKey
    val word: String,

    @ColumnInfo(defaultValue = "0")
    val count: Int = 0,

    val firstSeen: Long,

    val lastSeen: Long,

    /**
     * 历次提交时"选中的候选序号"之和。
     * 配合 [indexCount] 可以算 **平均要翻到第几个才选中** —— 越小越顺手。
     */
    @ColumnInfo(defaultValue = "0")
    val indexSum: Int = 0,

    /** 有候选信息的提交次数。用于算平均值（避免除零）。 */
    @ColumnInfo(defaultValue = "0")
    val indexCount: Int = 0,
) {
    /** 平均选中位置。没有样本时返回 -1。 */
    val averageIndex: Double
        get() = if (indexCount <= 0) -1.0 else indexSum.toDouble() / indexCount

    companion object {
        const val TABLE_NAME = "word_stat"
    }
}