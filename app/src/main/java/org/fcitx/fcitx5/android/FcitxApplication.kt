/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.insight.CandidateFilters
import org.fcitx.fcitx5.android.data.insight.CandidateReranker
import org.fcitx.fcitx5.android.data.insight.InsightMaintenance
import org.fcitx.fcitx5.android.data.insight.InsightRecorder
import org.fcitx.fcitx5.android.data.insight.LstmScorer
import org.fcitx.fcitx5.android.data.insight.PersonalDictionary
import org.fcitx.fcitx5.android.data.insight.UserProfile
import org.fcitx.fcitx5.android.data.llm.LlmModel
import org.fcitx.fcitx5.android.data.llm.LlmNative
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.userManager
import timber.log.Timber
import kotlin.system.exitProcess

class FcitxApplication : Application() {

    val coroutineScope = MainScope() + CoroutineName("FcitxApplication")

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SHUTDOWN) return
            Timber.d("Device shutting down, trying to save fcitx state...")
            val fcitx = FcitxDaemon.getFirstConnectionOrNull()
                ?: return Timber.d("No active fcitx connection, skipping")
            fcitx.runImmediately { save() }
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            if (!isDirectBootMode) return
            Timber.d("Device unlocked, app will exit now and restart to normal mode")
            FcitxDaemon.getFirstConnectionOrNull()?.also {
                // try to shutdown fcitx gracefully
                FcitxDaemon.stopFcitx()
            }
            AppUtil.exit()
        }
    }

    private val restartFcitxInstanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESTART_FCITX_INSTANCE) return
            if (FcitxDaemon.getFirstConnectionOrNull() != null) {
                Timber.i("Received broadcast '${intent.action}', try to restart fcitx instance ...")
                FcitxDaemon.restartFcitx()
            } else {
                Timber.i("Received broadcast '${intent.action}', but there's no fcitx instance")
            }
        }
    }

    /**
     * 【fainput】后台维护的触发时机。
     *
     * 只在**充电**和**熄屏**时叫一声 —— 这两个时刻用户不会因为我们的计算而卡顿。
     * 真正"该不该跑"由 `InsightMaintenance` 内部的判断树决定（见 DeviceState）。
     *
     * 用动态注册而不是 manifest：`ACTION_SCREEN_OFF` / `ACTION_POWER_CONNECTED`
     * 都不能静态注册（Android 8+ 隐式广播限制）。
     */
    private val insightMaintenanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            InsightMaintenance.trigger(intent.action ?: "unknown")
        }
    }

    var isDirectBootMode = false
        private set

    val directBootAwareContext: Context
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isDirectBootMode) {
            createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !userManager.isUserUnlocked) {
            isDirectBootMode = true
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        val ctx = directBootAwareContext

        if (!BuildConfig.DEBUG) {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                val crashTime = System.currentTimeMillis()
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
                val lastCrashTimePrefKey = "last_crash_time"
                val lastCrashTime = sharedPreferences.getLong(lastCrashTimePrefKey, -1L)
                // make sure it was written to persistent storage
                sharedPreferences.edit(commit = true) {
                    putLong(lastCrashTimePrefKey, crashTime)
                }
                if (crashTime - lastCrashTime <= 10_000L) {
                    // continuous crashes within 10 seconds, maybe in a crash loop. just bail
                    exitProcess(10)
                }
                startActivity<LogActivity> {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(LogActivity.FROM_CRASH, true)
                    // avoid transaction overflow
                    val truncated = e.stackTraceToString().let {
                        if (it.length > MAX_STACKTRACE_SIZE)
                            it.take(MAX_STACKTRACE_SIZE) + "<truncated>"
                        else
                            it
                    }
                    putExtra(LogActivity.CRASH_STACK_TRACE, truncated)
                }
                exitProcess(10)
            }
        }

        instance = this
        // we don't have AppPrefs available yet
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        Timber.setupForest(verbose = sharedPrefs.getBoolean("verbose_log", false))

        Timber.d("isDirectBootMode=$isDirectBootMode")

        AppPrefs.init(sharedPrefs)
        // record last pid for crash logs
        AppPrefs.getInstance().internal.pid.apply {
            val currentPid = Process.myPid()
            lastPid = getValue()
            Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
            setValue(currentPid)
        }
        ClipboardManager.init(ctx)
        // 【fainput】输入行为采集：本地落库，见 data/insight/
        InsightRecorder.init(ctx)
        // 【fainput / D-1'】候选重排器：把学到的词提到候选栏最前面
        CandidateReranker.init(ctx)
        // 【从 Rime 借鉴】候选后处理管道（filters）—— 和排序分开的"加工"层。
        // 必须在重排器之后：`reorder()` 的第 ② 段要用它。
        CandidateFilters.init(ctx)
        // 【fainput / ABCD】用户画像：A/B/C 三层的权重与边界。
        // 必须**在重排器之后**初始化 —— 重排器第一次跑之前画像就得是就绪的，
        // 否则会拿默认档去打第一屏分（虽然默认档也是对的，但日志会对不上）。
        UserProfile.init(ctx)
        // 【fainput / C 层】微 LM 候选打分。
        //
        // ⚠️ **时序坑**：模型是随 assets 一起同步下来的（`DataManager.sync()`），
        //    而同步发生在**引擎启动之后** —— 此刻多半还找不到文件。
        //    所以：先试一次（同步早已完成的冷启动路径），再挂回调等下一次同步。
        //    少了后一半，内置模型在**全新安装**上永远加载不了。
        LstmScorer.init(ctx)
        DataManager.addOnNextSyncedCallback { LstmScorer.init(ctx) }
        // 【fainput / D-2+D-3】个人词库：让引擎真正认识你的词
        PersonalDictionary.init(ctx)
        // 【fainput / L3】报一句 LLM 状态：**后端 + 模型分开说**。
        // 「构建成功」和「真有 LLM」是两件事 —— 前者只要 CMake 不报错，
        // 后者要求 ① llama.cpp 真编进来 ② 模型文件真在磁盘上。
        // 真正的证据是「设置 → fainput → 模型」那一页的"能载入"。
        Timber.i("[fainput] %s / 模型：%s", LlmNative.statusLine(), LlmModel.statusText())
        // 【fainput】充电 / 熄屏时叫一下后台维护（存储分层归档）
        ContextCompat.registerReceiver(
            this,
            insightMaintenanceReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ThemeManager.init(resources.configuration)
        Locales.onLocaleChange(resources.configuration)
        registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isDirectBootMode) {
            AppPrefs.getInstance().syncToDeviceEncryptedStorage()
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        ContextCompat.registerReceiver(
            this,
            restartFcitxInstanceReceiver,
            IntentFilter(ACTION_RESTART_FCITX_INSTANCE),
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.onSystemPlatteChange(newConfig)
        Locales.onLocaleChange(newConfig)
    }

    companion object {
        private var lastPid: Int? = null
        private var instance: FcitxApplication? = null
        fun getInstance() =
            instance ?: throw IllegalStateException("FcitxApplication has not been created!")

        fun getLastPid() = lastPid
        private const val MAX_STACKTRACE_SIZE = 128000

        const val ACTION_RESTART_FCITX_INSTANCE =
            "${BuildConfig.APPLICATION_ID}.action.RESTART_FCITX_INSTANCE"

        /**
         * This permission is requested by com.android.shell, makes it possible to restart
         * fcitx instance from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a org.fcitx.fcitx5.android.action.RESTART_FCITX_INSTANCE
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}