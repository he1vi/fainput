/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight.db

/**
 * 「按天聚合」的投影结果 —— 不是表，只是 SQL 的返回类型。
 *
 * 字段和 [DailyStatEntity] 一一对应（少 `topWords` 和 `archivedAt`），
 * 因为「当天高频词」需要单独一次查询，不能在同一个 GROUP BY 里出来。
 */
data class DailyAggregate(
    /** `yyyyMMdd` */
    val day: Int,
    val events: Int,
    val chars: Long,
    val keys: Long,
    val preeditChars: Long,
    val durationMs: Long,
    val candidateHits: Int,
    val candidateSamples: Int,
    val pageTurns: Int,
)

/** 「某个词出现了几次」—— 归档时生成当天高频词快照用。 */
data class WordCount(
    val word: String,
    val count: Int,
)

/**
 * C 阶段「实时概览」用的投影。
 *
 * 和 [DailyAggregate] 的区别：**没有 `day`** ——
 * 它既用于"今天"（`WHERE timestamp >= 今日零点`），
 * 也用于"全部时间"（无 WHERE），所以不能绑死在某一天上。
 *
 * 所有字段都是可累加的，方便把「今天 + 历史归档」加在一起。
 */
data class StatsProjection(
    val events: Int,
    val chars: Long,
    val keys: Long,
    val preeditChars: Long,
    val durationMs: Long,
    val candidateHits: Int,
    val candidateSamples: Int,
    val pageTurns: Int,
) {
    companion object {
        val Empty = StatsProjection(0, 0L, 0L, 0L, 0L, 0, 0, 0)

        fun plus(a: StatsProjection, b: StatsProjection) = StatsProjection(
            events = a.events + b.events,
            chars = a.chars + b.chars,
            keys = a.keys + b.keys,
            preeditChars = a.preeditChars + b.preeditChars,
            durationMs = a.durationMs + b.durationMs,
            candidateHits = a.candidateHits + b.candidateHits,
            candidateSamples = a.candidateSamples + b.candidateSamples,
            pageTurns = a.pageTurns + b.pageTurns,
        )
    }
}

/** 「各分级各有多少条」—— 展示"保护了多少敏感输入"。 */
data class LevelCount(
    val level: Int,
    val count: Int,
)