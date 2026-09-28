/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 【M·L1】词搭配（bigram）：`a` 后面常跟 `b`。
 *
 * ## 为什么要单独一张表
 *
 * D 阶段做的是「**这个词**你常用」；这张表做的是「**这对词**你常用」。
 *
 * ```
 * 你常打「输入法」
 * → 打完 shuru 选了「输入」，再打 fa
 * → 「法」自己排到最前 —— 因为你历史上「输入」后面跟的就是「法」
 * ```
 *
 * 数据本来能从 `input_event` 自连接算出来（同一个 session 里相邻两次提交），
 * 但那样每次都要扫大表。这里**聚合一次、查一次就走索引**。
 *
 * ## 只统计 L1（明文）
 *
 * 和 [WordStatEntity] 同一条纪律：L2/L3 不参与 ——
 * 只有哈希的话既没法配对，也不该把"你的验证码后面跟什么"变成统计特征。
 *
 * ## 主键是 (a, b)
 *
 * 同一对词靠 `ON CONFLICT` 累加，不产生重复行。
 */
@Entity(
    tableName = WordBigramEntity.TABLE_NAME,
    primaryKeys = ["a", "b"]
)
data class WordBigramEntity(
    /** 左边的词（上一个上屏的词）。 */
    val a: String,

    /** 右边的词（这次上屏的词）。 */
    val b: String,

    @ColumnInfo(defaultValue = "0")
    val count: Int = 0,

    val firstSeen: Long,
    val lastSeen: Long,
) {
    companion object {
        const val TABLE_NAME = "word_bigram"
    }
}