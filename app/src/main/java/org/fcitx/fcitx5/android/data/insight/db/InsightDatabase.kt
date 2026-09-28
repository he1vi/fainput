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
        WordBigramEntity::class,
    ],
    version = 3,
    /**
     * **打开 schema 导出**（2026-09-28）。
     *
     * 原注释说"等表结构稳定后可以打开" —— 现在有三张用户攒出来的表了，
     * 而且 `MIGRATION_2_3` 是我手写的 DDL：
     * 打开后每次构建会在 `app/schemas/` 生成 Room 的**权威期望 DDL**，
     * 手写迁移可以逐字比对，不再靠猜。
     */
    exportSchema = true
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

        /**
         * v2 → v3：新增 `word_bigram`（**M·L1 词搭配**）。
         *
         * 和 v1→v2 同一条纪律：上面那张表是用户攒出来的，**不能清库**。
         *
         * ⚠️ DDL 必须和 Room 从 [WordBigramEntity] 生成的**完全一致**
         *    （列名 / 列序 / NOT NULL / DEFAULT / 主键顺序都要对上），
         *    否则 Room 打开数据库时校验 schema 会直接抛异常。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `word_bigram` (
                        `a` TEXT NOT NULL,
                        `b` TEXT NOT NULL,
                        `count` INTEGER NOT NULL DEFAULT 0,
                        `firstSeen` INTEGER NOT NULL,
                        `lastSeen` INTEGER NOT NULL,
                        PRIMARY KEY(`a`, `b`)
                    )"""
                )
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}