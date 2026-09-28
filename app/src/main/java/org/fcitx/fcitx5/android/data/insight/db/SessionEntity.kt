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
 * 一次「聚焦到某个输入框」的会话。
 *
 * 粒度：`onStartInput` 到 `onFinishInput`（或包名/敏感状态变化）。
 * 作用：把散落的 input_event 归拢成"在哪个 App、什么类型的框里、打了多久"。
 *
 * 注意 [inputClass] / [inputVariation] 只存**掩码后的类型**，
 * 不存原始 inputType —— 因为原始值里可能带 `TYPE_TEXT_FLAG_*`，
 * 虽然不敏感，但没必要存全。
 */
@Entity(
    tableName = SessionEntity.TABLE_NAME,
    indices = [Index("startTime"), Index("pkgName")]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val startTime: Long,

    /** 会话结束时间。未结束时等于 startTime。 */
    @ColumnInfo(defaultValue = "0")
    val endTime: Long = startTime,

    /** 在哪个 App 里打字。拿不到时为 "unknown"。 */
    @ColumnInfo(defaultValue = "unknown")
    val pkgName: String = "unknown",

    /** `inputType & TYPE_MASK_CLASS` */
    @ColumnInfo(defaultValue = "0")
    val inputClass: Int = 0,

    /** `inputType & TYPE_MASK_VARIATION` */
    @ColumnInfo(defaultValue = "0")
    val inputVariation: Int = 0,

    /** 是否密码/支付类输入框。true 时本会话的所有 event 都降为 COUNT_ONLY。 */
    @ColumnInfo(defaultValue = "0")
    val sensitive: Boolean = false,
) {
    companion object {
        const val TABLE_NAME = "session"
    }
}