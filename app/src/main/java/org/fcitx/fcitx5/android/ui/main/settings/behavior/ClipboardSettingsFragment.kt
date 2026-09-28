/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {

    /**
     * 【fainput 新功能】在这一页底部加一行「历史记录」。
     *
     * 为什么放这：IME 里那个面板现在**只显示最近 2 小时**，
     * 所以必须有地方能翻到更早的。而用户想到"剪贴板"时来的就是这一页 —— 位置最顺。
     */
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(
            Preference(screen.context).apply {
                title = "历史记录"
                summary = "全部记录 · 可搜索"
                setOnPreferenceClickListener {
                    findNavController().navigateWithAnim(SettingsRoute.ClipboardHistory)
                    true
                }
            }
        )
    }
}
