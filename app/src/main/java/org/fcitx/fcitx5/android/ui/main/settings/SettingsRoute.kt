/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Parcelable
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.createGraph
import androidx.navigation.fragment.fragment
import androidx.savedstate.SavedState
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhrase
import org.fcitx.fcitx5.android.ui.main.AboutFragment
import org.fcitx.fcitx5.android.ui.main.DeveloperFragment
import org.fcitx.fcitx5.android.ui.main.InsightFragment
import org.fcitx.fcitx5.android.ui.main.LicensesFragment
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.main.PluginFragment
import org.fcitx.fcitx5.android.ui.main.settings.addon.AddonConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.addon.AddonListFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.AdvancedSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.CandidatesSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardHistoryFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SymbolSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.global.GlobalConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodListFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeFragment
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.parcelable
import kotlin.reflect.typeOf

@Parcelize
sealed class SettingsRoute : Parcelable {

    /* ========== Index ========== */

    @Serializable
    data object Index : SettingsRoute()

    /* ========== Fcitx ========== */

    @Serializable
    data object GlobalConfig : SettingsRoute()

    @Serializable
    data object InputMethodList : SettingsRoute()

    @Serializable
    data class InputMethodConfig(val name: String, val uniqueName: String) : SettingsRoute()

    @Serializable
    data object AddonList : SettingsRoute()

    @Serializable
    data class AddonConfig(val name: String, val uniqueName: String) : SettingsRoute()

    /* ========== Android ========== */

    @Serializable
    data object Theme : SettingsRoute()

    @Serializable
    data object VirtualKeyboard : SettingsRoute()

    @Serializable
    data object CandidatesWindow : SettingsRoute()

    @Serializable
    data object Clipboard : SettingsRoute()

    @Serializable
    data object Symbol : SettingsRoute()

    @Serializable
    data object Plugin : SettingsRoute()

    @Serializable
    data object Advanced : SettingsRoute()

    /** 【fainput】「输入数据」—— C 阶段：实时镜像 + 学习状态 + 高频词 */
    @Serializable
    data object Insight : SettingsRoute()

    /** 【fainput / S】拼音设置 —— 模糊音 / 常见错拼等 21 项（上游已实现，我们补入口） */
    @Serializable
    data object PinyinSettings : SettingsRoute()

    /** 【fainput】剪贴板历史 —— 面板只给最近 2 小时，这里给**全部**（+ 跨条目搜索） */
    @Serializable
    data object ClipboardHistory : SettingsRoute()

    /** 【fainput / H】备份 —— 导出 / 导入 / 清空（加密整机转移包） */
    @Serializable
    data object Transfer : SettingsRoute()

    /**
     * 【fainput】模型页 —— **只有微 LM**。
     *
     * ## 2026-09-30：大模型那条链整体拆掉
     *
     * 用户原话「**那不要这个了**」。
     *
     * LLM 原本只用来把统计结果写成一句「习惯总结」，而为了这一句话：
     *   · APK 里要常驻 **3.3 MB** 的 `libnative-llm.so`
     *   · 每次 CI 还要多拉一遍 llama.cpp 源码（拖慢构建）
     *   · 它**不在打字路径上** —— 对输入体验零贡献
     *
     * ## 微 LM 是**内置**的
     *
     * `assets/lstm/model.fnlstm` 随 `DataManager` 同步到数据目录，
     * **不需要导入、也没有导入入口**。这一页只做**诊断**：
     * 看状态 / 载入 / 试算 / 释放。
     */
    @Serializable
    data object LstmModel : SettingsRoute()

    @Serializable
    data object Developer : SettingsRoute()

    @Serializable
    data object License : SettingsRoute()

    @Serializable
    data object About : SettingsRoute()

    /* ========== External ========== */

    @Serializable
    data class ListConfig(val params: Params) : SettingsRoute() {
        @Parcelize
        @Serializable
        data class Params(val cfg: RawConfig, val desc: ConfigDescriptor<*, *>) : Parcelable {
            companion object {
                // https://developer.android.com/guide/navigation/design/kotlin-dsl#custom-types
                val NavType = object : NavType<Params>(isNullableAllowed = false) {
                    override fun put(bundle: SavedState, key: String, value: Params) {
                        bundle.putParcelable(key, value)
                    }

                    override fun get(bundle: SavedState, key: String): Params? {
                        return bundle.parcelable<Params>(key)
                    }

                    override fun serializeAsValue(value: Params): String {
                        // Serialized values must always be Uri encoded
                        return Uri.encode(Json.encodeToString(value))
                    }

                    override fun parseValue(value: String): Params {
                        // Navigation decodes the string before passing it to parseValue()
                        return Json.decodeFromString(value)
                    }
                }
            }
        }

        constructor(cfg: RawConfig, desc: ConfigDescriptor<*, *>) : this(Params(cfg, desc))

        val desc: ConfigDescriptor<*, *>
            get() = params.desc
        val cfg: RawConfig
            get() = params.cfg
    }

    @Serializable
    data class PinyinDict(val uri: String? = null) : SettingsRoute() {
        constructor(uri: Uri) : this(uri.toString())
    }

    @Serializable
    data class Punctuation(val title: String, val lang: String? = null) : SettingsRoute()

    @Serializable
    data object QuickPhraseList : SettingsRoute()

    @Serializable
    data class QuickPhraseEdit(val param: Param) : SettingsRoute() {
        constructor(quickPhrase: QuickPhrase) : this(Param(quickPhrase))

        @Serializable
        @Parcelize
        data class Param(val quickPhrase: QuickPhrase) : Parcelable {
            companion object {
                val NavType = object : NavType<Param>(isNullableAllowed = false) {
                    override fun put(bundle: SavedState, key: String, value: Param) {
                        bundle.putParcelable(key, value)
                    }

                    override fun get(bundle: SavedState, key: String): Param? {
                        return bundle.parcelable<Param>(key)
                    }

                    override fun serializeAsValue(value: Param): String {
                        return Uri.encode(Json.encodeToString(value))
                    }

                    override fun parseValue(value: String): Param {
                        return Json.decodeFromString(value)
                    }
                }
            }
        }
    }

    @Serializable
    data object TableInputMethods : SettingsRoute()

    @Serializable
    data object PinyinCustomPhrase : SettingsRoute()

    companion object {
        fun createGraph(controller: NavController) = controller.createGraph(Index) {
            val ctx = controller.context

            /* ========== Index ========== */

            fragment<MainFragment, Index> {
                label = ctx.getString(R.string.app_name)
            }

            /* ========== Fcitx ========== */

            fragment<GlobalConfigFragment, GlobalConfig>()
            fragment<InputMethodListFragment, InputMethodList> {
                label = ctx.getString(R.string.input_methods)
            }
            fragment<InputMethodConfigFragment, InputMethodConfig>()
            fragment<AddonListFragment, AddonList> {
                label = ctx.getString(R.string.addons)
            }
            fragment<AddonConfigFragment, AddonConfig>()

            /* ========== Android ========== */

            fragment<ThemeFragment, Theme> {
                label = ctx.getString(R.string.theme)
            }
            fragment<KeyboardSettingsFragment, VirtualKeyboard> {
                label = ctx.getString(R.string.virtual_keyboard)
            }
            fragment<CandidatesSettingsFragment, CandidatesWindow> {
                label = ctx.getString(R.string.candidates_window)
            }
            fragment<ClipboardSettingsFragment, Clipboard> {
                label = ctx.getString(R.string.clipboard)
            }
            fragment<ClipboardHistoryFragment, ClipboardHistory> {
                label = "剪贴板历史"
            }
            fragment<LstmModelFragment, LstmModel> {
                label = "模型"
            }
            fragment<TransferFragment, Transfer> {
                label = "备份"
            }
            fragment<SymbolSettingsFragment, Symbol> {
                label = ctx.getString(R.string.emoji_and_symbols)
            }
            fragment<PluginFragment, Plugin> {
                label = ctx.getString(R.string.plugins)
            }
            fragment<AdvancedSettingsFragment, Advanced> {
                label = ctx.getString(R.string.advanced)
            }
            fragment<InsightFragment, Insight> {
                label = "输入数据"
            }
            fragment<PinyinSettingsFragment, PinyinSettings> {
                label = "拼音"
            }
            fragment<DeveloperFragment, Developer> {
                label = ctx.getString(R.string.developer)
            }
            fragment<LicensesFragment, License> {
                label = ctx.getString(R.string.license)
            }
            fragment<AboutFragment, About> {
                label = ctx.getString(R.string.about)
            }

            /* ========== External ========== */

            fragment<ListFragment, ListConfig>(
                typeMap = mapOf(typeOf<ListConfig.Params>() to ListConfig.Params.NavType)
            )
            fragment<PinyinDictionaryFragment, PinyinDict> {
                label = ctx.getString(R.string.pinyin_dict)
            }
            fragment<PunctuationEditorFragment, Punctuation>()
            fragment<QuickPhraseListFragment, QuickPhraseList> {
                label = ctx.getString(R.string.quickphrase_editor)
            }
            fragment<QuickPhraseEditFragment, QuickPhraseEdit>(
                typeMap = mapOf(typeOf<QuickPhraseEdit.Param>() to QuickPhraseEdit.Param.NavType)
            )
            fragment<TableInputMethodFragment, TableInputMethods> {
                label = ctx.getString(R.string.table_im)
            }
            fragment<PinyinCustomPhraseFragment, PinyinCustomPhrase>()
        }
    }
}
