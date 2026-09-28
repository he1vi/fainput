/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */

package org.fcitx.fcitx5.android.data.insight

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

/**
 * 设备状态探针 —— 决定"现在能不能跑后台计算"。
 *
 * ## 不打扰原则（项目决策）
 *
 * ```
 * 是否允许跑训练？
 * ├─ 充电中 ──────────────────→ ✅ 允许（即使正在打字）
 * ├─ 熄屏中 ──────────────────→ ✅ 允许
 * └─ 亮屏 + 未充电
 *      ├─ CPU 频率 < 最高频率 40% → ✅ 允许
 *      └─ 否则 ────────────────→ ❌ 不打扰
 * ```
 *
 * **为什么看 CPU 频率而不是"空闲"**：
 * Android 上"空闲"很难定义。而**高频 = 系统正在跑重任务** ——
 * 这时候插进去只会互相拖累。这个信号直接、可靠、代价低。
 *
 * 全部读操作，失败时一律返回**保守值**（保守 = 不跑）。
 */
object DeviceState {

    /** 低于这个比例认为"系统不忙" */
    const val CPU_BUSY_THRESHOLD = 0.4f

    // ==================== 充电 ====================

    /**
     * 是否在充电（含充满）。
     *
     * 用 sticky broadcast 而不是 `BatteryManager.isCharging` ——
     * 后者在部分 ROM 上不准，而 `ACTION_BATTERY_CHANGED` 是系统粘性广播，
     * 传 `null` receiver 直接拿最后一条。
     */
    fun isCharging(): Boolean = try {
        val intent = appContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }
    } catch (_: Throwable) {
        false
    }

    // ==================== 熄屏 ====================

    /** 屏幕是否亮着。拿不到时返回 true（保守：当作亮屏，不跑）。 */
    fun isScreenOn(): Boolean = try {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    } catch (_: Throwable) {
        true
    }

    // ==================== CPU 频率 ====================

    /**
     * 所有核心中「当前频率 ÷ 最高频率」的**最大值**。
     *
     * 取最大值而不是平均值：**只要有一个核心在高频，就说明系统在跑重任务**。
     *
     * @return 0f~1f；**读不到时返回 -1f**
     */
    fun cpuBusyRatio(): Float {
        val cpuDir = File("/sys/devices/system/cpu")
        val cores = cpuDir.listFiles { f -> CORE_NAME.matches(f.name) } ?: return -1f
        var worst = -1f
        for (core in cores) {
            val cur = readLong(File(core, "cpufreq/scaling_cur_freq")) ?: continue
            // 最高频率基本不变，缓存起来
            val max = maxFreqCache.getOrPut(core.name) {
                readLong(File(core, "cpufreq/cpuinfo_max_freq")) ?: -1L
            }
            if (max <= 0L) continue
            val ratio = cur.toFloat() / max.toFloat()
            if (ratio > worst) worst = ratio
        }
        return worst
    }

    /** 1 分钟平均负载。读不到返回 -1f。 */
    fun loadAvg1(): Float = try {
        File("/proc/loadavg").readText().trim().split(' ').firstOrNull()?.toFloat() ?: -1f
    } catch (_: Throwable) {
        -1f
    }

    // ==================== 综合判断 ====================

    /**
     * 现在允许跑计算吗？
     *
     * @param heavy 重任务（bigram 重算、词库合并）只在**充电 / 熄屏**时跑。
     *              轻任务（归档、热度衰减）条件放宽。
     */
    fun canRunNow(heavy: Boolean): Boolean {
        if (isCharging()) return true
        if (!isScreenOn()) return true
        // 亮屏 + 未充电：只有系统不忙才允许
        if (heavy) return false
        val ratio = cpuBusyRatio()
        if (ratio < 0f) {
            // 读不到 CPU 频率 → 退回负载判断；还是读不到就不跑
            val load = loadAvg1()
            return load in 0f..(Runtime.getRuntime().availableProcessors() * 0.35f)
        }
        return ratio < CPU_BUSY_THRESHOLD
    }

    // ==================== 内部 ====================

    private val CORE_NAME = Regex("cpu\\d+")
    private val maxFreqCache = HashMap<String, Long>()

    private fun readLong(f: File): Long? = try {
        if (f.canRead()) f.readText().trim().toLongOrNull() else null
    } catch (_: Throwable) {
        null
    }
}
