/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

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
