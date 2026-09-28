/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.addon

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.addon.LuaAddon
import org.fcitx.fcitx5.android.core.AddonInfo
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.CheckBoxListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.settings.ProgressFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class AddonListFragment : ProgressFragment(), OnItemChangedListener<AddonInfo> {

    private lateinit var ui: BaseDynamicListUi<AddonInfo>

    private val addonDisplayNames = mutableMapOf<String, String>()

    /**
     * 【fainput】右上角「导入」—— 选一个 `.lua`，装成 Lua **附加组件**。
     *
     * 为什么只收脚本：`.so` 外挂要从 App 私有目录 `dlopen`，Android 从 API 24 起禁止，
     * 想装 `libxxx.so` 只能编进 APK。脚本由 Lua 解释器读文件，**不经过链接器**。
     *
     * 装完**要重启输入法**才生效 —— 引擎只在启动时读一次外挂列表，没有热重扫。
     * 所以提示里必须说这句，不能假装"导完就能用"。
     */
    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importLuaAddon(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.addon_list, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.import_lua -> {
                importPicker.launch(arrayOf("*/*"))
                return true
            }

            R.id.manage_lua -> {
                showImported()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun importLuaAddon(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { LuaAddon.install(requireContext(), uri) }
            result.fold(
                { name ->
                    Toast.makeText(requireContext(), "已导入 $name", Toast.LENGTH_SHORT).show()
                    askRestart()
                },
                {
                    Toast.makeText(requireContext(), "导入失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    /**
     * 装完让**用户选**要不要重启 —— 不偷偷重启。
     *
     * 引擎只在启动时读一次外挂列表（没有热重扫）⇒ 不重启就不生效；
     * 但重启会打断正在输入的会话，所以不能替用户决定。
     */
    private fun askRestart() {
        AlertDialog.Builder(requireContext())
            .setTitle("重启输入法？")
            .setMessage("重启后生效")
            .setPositiveButton("重启") { _, _ -> FcitxDaemon.restartFcitx() }
            .setNegativeButton("稍后", null)
            .show()
    }

    /**
     * 已导入的外挂 —— 点一下删掉。
     *
     * 和「数据能带走」同一条道理：**能力也得能拿走**。删就删干净（脚本 + 描述），
     * 不留在磁盘上。删除同样要重启才生效（列表是启动时读的）。
     */
    private fun showImported() {
        val names = LuaAddon.list()
        if (names.isEmpty()) {
            Toast.makeText(requireContext(), "没有导入过", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("已导入")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                AlertDialog.Builder(requireContext())
                    .setTitle("删除 $name？")
                    .setPositiveButton("删除") { _, _ ->
                        val ok = LuaAddon.remove(name)
                        Toast.makeText(
                            requireContext(),
                            if (ok) "已删除 $name · 重启后生效" else "删除失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun updateAddonState() {
        if (!isInitialized) return
        val ids = ui.entries.map { it.uniqueName }.toTypedArray()
        val state = ui.entries.map { it.enabled }.toBooleanArray()
        fcitx.launchOnReady {
            it.setAddonState(ids, state)
        }
    }

    private fun disableAddon(entry: AddonInfo, reset: () -> Unit) {
        val dependents = fcitx.runImmediately { getAddonReverseDependencies(entry.uniqueName) }
        if (dependents.isNotEmpty()) {
            fun f(depTy: FcitxAPI.AddonDep) =
                dependents.mapNotNull {
                    it.takeIf { x -> x.second == depTy }
                        ?.first
                        ?.let { u -> it.first to (addonDisplayNames[u] ?: u) }
                }

            fun mkStr(list: List<Pair<String, String>>, @StringRes template: Int) =
                list.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { it.second }
                    ?.let { getString(template, it) }

            val dep = f(FcitxAPI.AddonDep.Required)
            val depU = dep.map { it.first }.toSet()
            val depStr = mkStr(dep, R.string.disable_addon_warn_dep)
            val optDepStr = mkStr(f(FcitxAPI.AddonDep.Optional), R.string.disable_addon_warn_optdep)

            if (depStr != null || optDepStr != null) {
                val msg = buildString {
                    appendLine(getString(R.string.disable_addon_warn_name, entry.displayName))
                    depStr?.let {
                        append("- ")
                        appendLine(it)
                    }
                    optDepStr?.let {
                        append("- ")
                        appendLine(it)
                    }
                    appendLine(getString(R.string.disable_addon_warn_confirm))
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.disable_addon_warn_title))
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setMessage(msg)
                    .setCancelable(false)
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        reset()
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        ui.entries.forEachIndexed { idx, addonInfo ->
                            // TODO: combine update addon states
                            if (addonInfo.uniqueName in depU) {
                                ui.updateItem(idx, addonInfo.copy(enabled = false))
                            }
                        }
                        ui.updateItem(ui.indexItem(entry), entry.copy(enabled = false))
                    }
                    .show()
            } else {
                ui.updateItem(ui.indexItem(entry), entry.copy(enabled = false))
            }
        } else {
            ui.updateItem(ui.indexItem(entry), entry.copy(enabled = false))
        }
    }

    override suspend fun initialize(): View {
        ui = requireContext().CheckBoxListUi(
            initialEntries = fcitx.runOnReady {
                addons()
                    .sortedBy { it.uniqueName }
                    .onEach { addonDisplayNames[it.uniqueName] = it.displayName }
            },
            initCheckBox = { entry ->
                // our addon shouldn't be disabled
                isEnabled = entry.uniqueName != "androidfrontend"
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    if (!isChecked)
                        disableAddon(entry) { this.isChecked = true }
                    else
                        ui.updateItem(ui.indexItem(entry), entry.copy(enabled = true))
                }
            },
            initSettingsButton = { entry ->
                visibility =
                    if (entry.isConfigurable &&
                        entry.enabled &&
                        // we disable clipboard addon config since we take over the control
                        entry.uniqueName != "clipboard"
                    ) View.VISIBLE else View.INVISIBLE
                setOnClickListener {
                    navigateWithAnim(
                        SettingsRoute.AddonConfig(entry.displayName, entry.uniqueName)
                    )
                }
            },
            show = { it.displayName }
        )
        ui.addOnItemChangedListener(this)
        return ui.root
    }

    override fun onItemUpdated(idx: Int, old: AddonInfo, new: AddonInfo) {
        updateAddonState()
    }

    override fun onDestroy() {
        if (isInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }

}