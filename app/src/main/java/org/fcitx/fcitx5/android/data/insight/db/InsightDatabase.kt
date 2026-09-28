/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        DailyStatEntity::class,
    ],
    version = 2,
    exportSchema = false
)
abstract class InsightDatabase : RoomDatabase() {
    abstract fun insightDao(): InsightDao

    companion object {

        /**
         * v1 → v2：新增 `daily_stat`（存储分层的冷表）。
         *
         * 这是**第一次真正的 schema 升级**，所以必须写迁移 ——
         * 用 `fallbackToDestructiveMigration` 会把用户积累的数据全清掉，
         * 那恰恰是这个项目最不能做的事。
         *
         * ⚠️ 下面的 CREATE TABLE 必须和 Room 从 [DailyStatEntity] 生成的**完全一致**，
         *    否则 Room 打开数据库时校验 schema 会失败。
         *    改实体时记得同步改这里。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `daily_stat` (
                        `day` INTEGER NOT NULL,
                        `events` INTEGER NOT NULL,
                        `chars` INTEGER NOT NULL,
                        `keys` INTEGER NOT NULL,
                        `preeditChars` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `candidateHits` INTEGER NOT NULL,
                        `candidateSamples` INTEGER NOT NULL,
                        `pageTurns` INTEGER NOT NULL,
                        `topWords` TEXT NOT NULL,
                        `archivedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`day`)
                    )"""
                )
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}