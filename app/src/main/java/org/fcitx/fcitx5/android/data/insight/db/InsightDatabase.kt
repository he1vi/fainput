/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 输入洞察数据库。
 *
 * 与上游的 `ClipboardDatabase` 分开建库（文件 `insight.db`），原因：
 * - 剪贴板是"上游功能"，本库是"fainput 自有功能" —— 分开便于以后同步上游
 * - 迁移策略不同：剪贴板表结构上游会改，本库由我们自己控版本
 *
 * `exportSchema = false`：不导出 schema JSON。等表结构稳定后可以打开，
 * 那时候 KSP 会写到 `app/schemas/`。
 */
@Database(
    entities = [
        SessionEntity::class,
        InputEventEntity::class,
        WordStatEntity::class,
    ],
    version = 1,
    exportSchema = false
)
abstract class InsightDatabase : RoomDatabase() {
    abstract fun insightDao(): InsightDao
}