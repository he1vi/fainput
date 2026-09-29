/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig

/**
 * 【S 阶段】拼音设置。
 *
 * ## 为什么是"补入口"而不是"写页面"
 *
 * 模糊音（`[Fuzzy]` 段 21 项）**引擎里早就实现了** ——
 * `libime` 的 `PinyinFuzzyFlag` 13 组模糊对 + 6 个结构性容错，
 * 罚分口径 `fuzzyCost = log10(0.5)`。
 * 上游也早就有通用配置编辑器（[FcitxPreferenceFragment] + `PreferenceScreenFactory`），
 * **`AddonConfigFragment` 全文只有 39 行**，剩下全靠基类渲染整棵 `RawConfig`。
 *
 * 缺的只是**没人给 pinyin 这个入口做一条能点到它的路**。
 * 所以这里只做两件事：拿配置、过滤、交回基类。
 *
 * ## 唯一做的一件"加工"：摘掉云拼音
 *
 * `PinyinEngineConfig` 里有 `CloudPinyinEnabled` / `CloudPinyinIndex` /
 * `CloudPinyinAnimation` / `KeepCloudPinyinPlaceHolder` 四项。
 * 但**这个 APK 没有 `INTERNET` 权限**（CI 门禁守着），
 * 打开它们只会得到"没有结果" —— 与其摆一个点了没反应的开关，不如不给。
 *
 * 过滤是**按名字递归**做的，且全部用 `?.` 链 ——
 * 万一上游改了树的形状，最坏结果是"没过滤掉"，**不会崩、不会清空配置**。
 */
class PinyinSettingsFragment : FcitxPreferenceFragment() {

    override fun getPageTitle(): String = "拼音"

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        val raw = fcitx.getAddonConfig("pinyin")
        stripCloudOptions(raw.findByName("cfg"))
        stripCloudOptions(raw.findByName("desc"))
        return raw
    }

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        fcitx.setAddonConfig("pinyin", newConfig)
    }

    /**
     * 【从 Rime 借鉴 / 笔画反查】—— **只加说明，一个字节的引擎行为都不改**。
     *
     * ## 这个功能其实早就有了
     *
     * 引擎里（`im/pinyin/pinyin.cpp`）有一整套笔画 / 拆字反查，而且**数据都在包里**：
     *
     * | 文件 | 大小 | 作用 |
     * |---|---|---|
     * | `lib/arm64-v8a/libpinyinhelper.so` | 159 KB | 消费者 |
     * | `usr/share/fcitx5/pinyinhelper/py_stroke.mb` | **1.38 MB** | 笔画数据 |
     * | `usr/share/fcitx5/pinyinhelper/py_table.mb` | 367 KB | 反查表 |
     * | `usr/share/fcitx5/pinyin/chaizi.dict` | 152 KB | 拆字词典 |
     *
     * 两个开关（`StrokeCandidateEnabled` / `ChaiziEnabled`）**默认就是开的**，
     * 而且会被这一页**自动渲染出来**（它们是 `PinyinEngineConfig` 的 Option）。
     *
     * ## 唯一缺的东西：**用户不可能猜到要打 hspnz**
     *
     * `pinyin.cpp` 里的判定就一句话：
     *
     * ```cpp
     * bool isStroke(const std::string &input) {
     *     static const std::unordered_set<char> py{'h', 'p', 's', 'z', 'n'};
     *     return std::all_of(input.begin(), input.end(),
     *                        [](char c) { return py.count(c); });
     * }
     * ```
     *
     * 这五个字母对应五种笔画，但这一点**在 UI 上完全不可见** ——
     * 功能明明开着，用户却永远发现不了。这就是"可发现性"缺口。
     *
     * 所以这里加一行**说明**（不是开关 —— 开关已经有了，别重复）。
     */
    override fun decorateScreen(screen: PreferenceScreen) {
        // ⚠️ `PreferenceScreen` **没有** `addPreference(index, pref)` 这个重载
        // （只有单参数的）—— 位置要用 `Preference.order` 控制。
        // 自动生成的那些项用的是 `DEFAULT_ORDER = Int.MAX_VALUE`，
        // 所以给一个负数就一定排在它们前面。
        screen.addPreference(
            Preference(requireContext()).apply {
                title = "笔画找字"
                summary = "h横 s竖 p撇 n捺 z折 · 例 hhh = 三横"
                isSelectable = false
                order = -1
            }
        )
    }

    private fun stripCloudOptions(node: RawConfig?) {
        val items = node?.subItems ?: return
        node.subItems = items
            .filterNot {
                it.name.startsWith("CloudPinyin") || it.name == "KeepCloudPinyinPlaceHolder"
            }
            .toTypedArray()
        node.subItems?.forEach { stripCloudOptions(it) }
    }
}
