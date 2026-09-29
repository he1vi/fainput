/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

/**
 * 【ABCD 互联】用户画像 —— **四层唯一共享的东西**。
 *
 * ## 这就是"既是独立个体，也是一个整体"的落地点
 *
 * 四层各自算各自的（互不依赖、任一层挂了其余照常），
 * 但**调参靠同一份画像**。所以它们不会各说各话。
 *
 * | 层 | 对画像的关系 |
 * |---|---|
 * | **A** 词级打分 | 读者：用 [Snapshot.wFreq] / [Snapshot.wPair] / [Snapshot.minCount] |
 * | **B** 句级打分 | 读者：用 [Snapshot.wSeq]（**为 0 = 整句层不参与**）|
 * | **C** 融合排序 | 读者：用 [Snapshot.maxPromote]（一次最多动几个）|
 * | **D** 后台整理 | **唯一写者** —— 充电/熄屏时按统计数据算出来 |
 *
 * ## 为什么是 SharedPreferences 而不是数据库
 *
 * 打字路径（`CandidateReranker.reorder()`）**在主线程**读它 ⇒ 必须无锁、无 IO。
 * 所以：D 在后台算好 → 写 prefs → 这里缓存成一个 `@Volatile` 的**不可变**对象。
 *
 * ## 档位 = "越用越敢动"的形状
 *
 * 新装 / 刚清空数据时是 [Tier.COLD]：门槛高、只动 2 个、整句层直接关掉。
 * 数据越长档位越高，画像越敢动。**这正是"越用越懂你"该有的曲线** ——
 * 而不是一上来就拿几百条噪音去改候选栏。
 *
 * > 用户原话：「**要有限度，不然频繁推荐反而适得其反**」。
 * > 档位就是这句话在代码里的形状。
 */
object UserProfile {

    private const val PREFS_NAME = "fainput_profile"

    // ---- 档位阈值（词表条数）----

    /** 低于这个数 = 冷启动，画像极度保守。 */
    private const val WARM_AT = 80

    /** 高于这个数 = 数据够厚，整句层可以全量参与。 */
    private const val HOT_AT = 400

    /**
     * 一份**不可变**画像快照。
     *
     * 打字路径上只读 `UserProfile.current` 这一个引用（`@Volatile`，无锁无 IO）。
     */
    data class Snapshot(
        /** A 层·词频权重。 */
        val wFreq: Double,
        /** A 层·词搭配权重。 */
        val wPair: Double,
        /** A 层·整句统计权重。**0.0 = 整句统计不参与**（冷启动就是这样）。 */
        val wSeq: Double,
        /**
         * **B 层**·个人纠错权重。
         *
         * 这一层**零模型成本**（纯查表），所以默认就开着 ——
         * 它是唯一"今天就能让打错也能算对"的一层。
         */
        val wFix: Double,
        /**
         * **C 层**·神经打分权重（LSTM）。
         *
         * `0.0` = 模型没装 / 没数据 / 超预算 —— **整层不参与**。
         * 默认 0 是刻意的：装 App 时没有模型，行为必须和没有这一层完全一样。
         */
        val wLm: Double,
        /** A 层·"够格被提升"的门槛（至少提交过几次）。 */
        val minCount: Int,
        /** 融合层·一次最多动几个候选（所有层加起来的效果上限）。 */
        val maxPromote: Int,
        /** 档位（给 UI 显示用）。 */
        val tier: Tier,
        /** 算出这份画像时的词表条数。 */
        val words: Int,
        /** 算出这份画像时的搭配条数。 */
        val pairs: Int,
        /** 算出时刻（0 = 从未算过，用的是默认值）。 */
        val updatedAt: Long,
    ) {
        /** 这份画像是不是"还没学过任何东西"。 */
        val isDefault: Boolean get() = updatedAt == 0L

        companion object {
            /**
             * 默认 = 冷启动档。
             *
             * 和 fainput 之前写死的常量**完全一致**（`MIN_COUNT=3` / `MAX_PROMOTE=2`），
             * 所以升级到画像体系**不会改变现有行为** —— 只有 D 跑过之后才会变。
             */
            val Default = Snapshot(
                wFreq = 1.0,
                wPair = 1.0,
                wSeq = 0.0,
                wFix = 1.0,
                wLm = 0.0,
                minCount = 3,
                maxPromote = 2,
                tier = Tier.COLD,
                words = 0,
                pairs = 0,
                updatedAt = 0L,
            )
        }
    }

    /**
     * 数据量档位。**`label` 是给用户看的**（§零.1：UI 文案简洁，≤4 字）。
     */
    enum class Tier(val label: String) {
        /** 冷启动：几乎不干预。 */
        COLD("冷启"),

        /** 有点数据了：允许整句层小权重参与。 */
        WARM("起步"),

        /** 数据够厚：整句层全量参与，可以多动几个候选。 */
        HOT("熟练"),
    }

    @Volatile
    private var snapshot: Snapshot = Snapshot.Default

    private var prefs: SharedPreferences? = null

    /** 打字路径读这一个引用就够。 */
    val current: Snapshot
        get() = snapshot

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        snapshot = read(p)
        Timber.i(
            "[profile] init: tier=%s words=%d pairs=%d (wSeq=%.2f)",
            snapshot.tier, snapshot.words, snapshot.pairs, snapshot.wSeq
        )
    }

    /**
     * D 层算完后发布。**只有 [InsightMaintenance] 该调这个。**
     *
     * 写入顺序：先落盘再换内存 —— 崩在中间最坏情况是"画像旧一点"，
     * 而不是"内存新、磁盘旧"导致下次启动回退。
     */
    fun publish(s: Snapshot) {
        val p = prefs ?: return
        p.edit()
            .putFloat(K_W_FREQ, s.wFreq.toFloat())
            .putFloat(K_W_PAIR, s.wPair.toFloat())
            .putFloat(K_W_SEQ, s.wSeq.toFloat())
            .putFloat(K_W_FIX, s.wFix.toFloat())
            .putFloat(K_W_LM, s.wLm.toFloat())
            .putInt(K_MIN_COUNT, s.minCount)
            .putInt(K_MAX_PROMOTE, s.maxPromote)
            .putString(K_TIER, s.tier.name)
            .putInt(K_WORDS, s.words)
            .putInt(K_PAIRS, s.pairs)
            .putLong(K_UPDATED, s.updatedAt)
            .apply()
        snapshot = s
        Timber.i(
            "[profile] publish: tier=%s words=%d pairs=%d (wFreq=%.2f wPair=%.2f wSeq=%.2f min=%d max=%d)",
            s.tier, s.words, s.pairs, s.wFreq, s.wPair, s.wSeq, s.minCount, s.maxPromote
        )
    }

    /**
     * 按数据量算出该用哪一档。
     *
     * **纯函数** —— 不碰 IO，所以可以在任何地方调、也能直接单测。
     * 它是"D 层 → A/B/C 层"这条反馈环的全部逻辑。
     */
    fun derive(words: Int, pairs: Int, now: Long): Snapshot {
        val tier = when {
            words >= HOT_AT -> Tier.HOT
            words >= WARM_AT -> Tier.WARM
            else -> Tier.COLD
        }
        return when (tier) {
            // 冷启动：**和升级前完全一样**，不动任何东西。
            Tier.COLD -> Snapshot.Default.copy(
                tier = tier, words = words, pairs = pairs, updatedAt = now
            )

            // 有点数据：门槛降 1，允许整句统计**小权重**参与。
            // 为什么 wSeq 只给 0.5：整句统计是最容易"看着对但错"的一层，
            // 数据不够时给它满权重，就是在用噪音覆盖引擎的排序。
            // wLm 同理只给 0.5 —— **神经层的输出更需要被数据量背书**。
            Tier.WARM -> Snapshot(
                wFreq = 1.0,
                wPair = 1.2,
                wSeq = 0.5,
                wFix = 1.0,
                wLm = 0.5,
                minCount = 2,
                maxPromote = 3,
                tier = tier, words = words, pairs = pairs, updatedAt = now
            )

            // 数据够厚：整句统计 + 神经层都满权重，可以多动几个候选。
            Tier.HOT -> Snapshot(
                wFreq = 1.0,
                wPair = 1.4,
                wSeq = 1.0,
                wFix = 1.0,
                wLm = 1.0,
                minCount = 2,
                maxPromote = 4,
                tier = tier, words = words, pairs = pairs, updatedAt = now
            )
        }
    }

    /** 「清空全部数据」时调用 —— 画像必须跟着回到冷启动。 */
    fun reset() {
        publish(Snapshot.Default)
    }

    // ==================== 落盘 ====================

    private const val K_W_FREQ = "w_freq"
    private const val K_W_PAIR = "w_pair"
    private const val K_W_SEQ = "w_seq"
    private const val K_W_FIX = "w_fix"
    private const val K_W_LM = "w_lm"
    private const val K_MIN_COUNT = "min_count"
    private const val K_MAX_PROMOTE = "max_promote"
    private const val K_TIER = "tier"
    private const val K_WORDS = "words"
    private const val K_PAIRS = "pairs"
    private const val K_UPDATED = "updated_at"

    private fun read(p: SharedPreferences): Snapshot {
        val updated = p.getLong(K_UPDATED, 0L)
        if (updated == 0L) return Snapshot.Default
        val tier = runCatching { Tier.valueOf(p.getString(K_TIER, null) ?: "") }
            .getOrDefault(Tier.COLD)
        return Snapshot(
            wFreq = p.getFloat(K_W_FREQ, 1f).toDouble(),
            wPair = p.getFloat(K_W_PAIR, 1f).toDouble(),
            wSeq = p.getFloat(K_W_SEQ, 0f).toDouble(),
            wFix = p.getFloat(K_W_FIX, 1f).toDouble(),
            wLm = p.getFloat(K_W_LM, 0f).toDouble(),
            minCount = p.getInt(K_MIN_COUNT, 3),
            maxPromote = p.getInt(K_MAX_PROMOTE, 2),
            tier = tier,
            words = p.getInt(K_WORDS, 0),
            pairs = p.getInt(K_PAIRS, 0),
            updatedAt = updated,
        )
    }
}
