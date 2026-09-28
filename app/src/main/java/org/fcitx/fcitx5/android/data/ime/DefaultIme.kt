/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.data.ime

import android.content.Context
import android.content.SharedPreferences
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.data.EngineUserDir
import java.io.File

/**
 * 【fainput】默认输入法 = **拼音**，英文并存。
 *
 * ## 为什么需要这个类
 *
 * fcitx5 的输入法列表里，**第 0 项是「直接输入」槽**（见 `instance.cpp`）：
 *
 * ```cpp
 * auto idx = std::distance(imList.begin(), iter);
 * if (idx != 0) { ... setGlobalDefaultInputMethod(name); }   // 非第 0 项才当"当前输入法"
 * else          { inputState->setActive(false); }            // 第 0 项 = 不激活
 * ```
 *
 * 而出厂 profile 是 `Items/0=pinyin` + `DefaultIM=keyboard-us` ——
 * **拼音被塞进了英文槽，默认却指向英文**，于是每换一个输入框都回到英文。
 *
 * ## 为什么不能只用 setEnabledIme
 *
 * JNI 的 `setEnabledInputMethods` 会重建整个 group，但
 * `InputMethodManager::setGroup` 在 defaultInputMethod 为空时**继承旧值**：
 *
 * ```cpp
 * if (defaultInputMethod.empty()) defaultInputMethod = group->defaultInputMethod();
 * ```
 *
 * ⇒ 从列表接口**永远改不动默认**。
 *
 * ## 走 enumerate（现成 JNI，不动 C++）
 *
 * `Instance::enumerate`（就是「切换输入法」那个动作）里有一句：
 *
 * ```cpp
 * if (idx != 0) { imManager.setDefaultInputMethod(imList[idx].name()); ... }
 * ```
 *
 * ⇒ **切到拼音，拼音就成了全局默认。** 前提是 `enumerateSkipFirst=false`
 * （fcitx5 的默认值，`globalconfig.cpp:118`）。
 *
 * 它只改内存，所以最后要 `fcitx.save()` 才落进 `config/profile`。
 *
 * ## 幂等
 *
 * 先读 `config/profile` 里的真值：**已经对了就直接退出**，
 * 不会把用户后来手动改的默认又扭回去（`done_v1` 只写一次）。
 */
object DefaultIme {

    private const val PREFS = "fainput_default_ime"
    private const val K_DONE = "done_v1"

    /** 第 0 项 = 直接输入（英文）。 */
    private const val DIRECT = "keyboard-us"

    /** 要当默认的那个。 */
    private const val CHINESE = "pinyin"

    @Volatile
    private var applied = false

    /**
     * 只做一次。**必须在有活跃输入框时调用** ——
     * `enumerate` 需要 InputContext，否则不会生效。
     */
    suspend fun applyIfNeeded(context: Context, fcitx: FcitxAPI) {
        if (applied) return
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(K_DONE, false)) {
            applied = true
            return
        }
        // 引擎里没有拼音（不该发生）→ 什么都不动
        if (fcitx.availableIme().none { it.uniqueName == CHINESE }) return
        // 已经是拼音（含用户自己改过的）→ 收工
        if (profileDefault() == CHINESE) {
            markDone(prefs)
            return
        }

        // ① 拼音不能待在第 0 项，否则永远激活不了；英文顺位补上
        val enabled = fcitx.enabledIme().map { it.uniqueName }
        val want = ArrayList<String>(enabled.size + 1)
        want += DIRECT
        enabled.filterTo(want) { it != DIRECT && it != CHINESE }
        want += CHINESE
        if (enabled != want) fcitx.setEnabledIme(want.toTypedArray())

        // ② 切到拼音 —— 这一步顺带把全局默认改掉
        var hops = 0
        while (fcitx.currentIme().uniqueName != CHINESE && hops++ < 6) {
            fcitx.enumerateIme(true)
        }

        // ③ enumerate 只改内存，要 save 才留得住
        if (hops > 0) fcitx.save()

        if (profileDefault() == CHINESE) markDone(prefs)
    }

    private fun markDone(prefs: SharedPreferences) {
        prefs.edit().putBoolean(K_DONE, true).apply()
        applied = true
    }

    /** 直接读 `config/profile` —— 比猜引擎内存状态可靠。 */
    private fun profileDefault(): String? = runCatching {
        val f = File(EngineUserDir.base(), "config/profile")
        if (!f.exists()) return@runCatching null
        f.readLines()
            .firstOrNull { it.startsWith("DefaultIM=") }
            ?.removePrefix("DefaultIM=")
            ?.trim()
    }.getOrNull()
}