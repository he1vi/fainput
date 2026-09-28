/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.data.kaomoji.Kaomoji
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment

/**
 * 【fainput / F 阶段】颜文字 —— 点一下就进系统剪贴板。
 *
 * 上游的符号面板里一个颜文字都没有（见 [Kaomoji] 的注释），
 * 而剪贴板面板是现成的"插入通道"：复制进来的东西，键盘上一点就能打出去。
 *
 * 所以这一页**只做内容，不做面板** —— 才 30 条，列表比九宫格好选。
 */
class KaomojiFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            Kaomoji.groups.forEach { (groupTitle, faces) ->
                val cat = PreferenceCategory(context).apply { title = groupTitle }
                addPreference(cat)
                faces.forEach { face ->
                    cat.addPreference(
                        Preference(context).apply {
                            title = face
                            setOnPreferenceClickListener {
                                copyToClipboard(face)
                                true
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * 复制到系统剪贴板。
     *
     * **不弹 Toast** —— Android 13+ 系统自己会在屏幕底部显示"已复制"，
     * 我们再弹一个就是两条提示叠在一起。
     */
    private fun copyToClipboard(text: String) {
        val cm = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("颜文字", text))
    }
}