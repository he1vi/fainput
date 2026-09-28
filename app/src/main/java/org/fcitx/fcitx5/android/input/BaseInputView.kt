/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.insight.CandidateReranker
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import splitties.views.dsl.core.withTheme
import kotlin.math.max

abstract class BaseInputView(
    val service: FcitxInputMethodService,
    val fcitx: FcitxConnection,
    val theme: Theme
) : ConstraintLayout(service) {

    /**
     * Update UI (from cached events in FcitxAPI) to match fcitx's state, before ready to receive real events
     */
    protected abstract fun onStartHandleFcitxEvent()

    protected abstract fun handleFcitxEvent(it: FcitxEvent<*>)

    private var eventHandlerJob: Job? = null

    private fun setupFcitxEventHandler() {
        eventHandlerJob = service.lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
    }

    var handleEvents = false
        set(value) {
            field = value
            if (field) {
                onStartHandleFcitxEvent()
                if (eventHandlerJob == null) {
                    setupFcitxEventHandler()
                }
            } else {
                eventHandlerJob?.cancel()
                eventHandlerJob = null
            }
        }

    private fun triggerCandidateAction(idx: Int, actionIdx: Int) {
        fcitx.runIfReady { triggerCandidateAction(idx, actionIdx) }
    }

    private var candidateActionMenu: PopupWindow? = null

    val themedContext = context.withTheme(R.style.Theme_InputViewTheme)

    /**
     * 长按候选词的动作菜单。
     *
     * 【2026-09-28 修复：弹窗盖住键盘】
     *
     * 原来这里是 `PopupMenu(...).show()` —— 位置完全交给框架，而框架的规则是
     * **「能往下弹就往下弹」**。键盘在屏幕底部，所以菜单永远落在键盘上，
     * 左半边键盘被整块挡住（用户实测截图）。
     * `PopupMenu` 没有任何偏移 / 方向 API，调参救不了 —— 只能换成自绘 `PopupWindow`。
     *
     * 现在的位置规则很简单：**贴在长按的那个候选词正上方**。
     * 候选条本来就在键盘上沿，所以菜单天然落在键盘之外。
     *
     * 【新增】菜单最后一项是 [CandidateReranker.suppress] 的入口 ——
     * D-3「长按屏蔽推荐」：不删除，只是**暂时不提前**，24 小时后自动恢复。
     */
    fun showCandidateActionMenu(idx: Int, text: String, view: View) {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        service.lifecycleScope.launch {
            // ⚠️ idx 是**引擎下标**：候选条的 adapter 已经把引擎下标写进 holder.idx
            //    （见 HorizontalCandidateViewAdapter.onBindViewHolder），
            //    所以这里不需要任何反向映射。
            val actions = fcitx.runOnReady { getCandidateActions(idx) }
            InputFeedbacks.hapticFeedback(view, longPress = true)
            val items = ArrayList<Pair<String, () -> Unit>>(actions.size + 1)
            actions.forEach { action ->
                items += action.text to { triggerCandidateAction(idx, action.id) }
            }
            items += "暂时不要推荐（24 小时）" to { CandidateReranker.suppress(text) }
            candidateActionMenu = showMenuAboveAnchor(text, items, view)
        }
    }

    /**
     * 把菜单贴在 [anchor] 正上方弹出。
     *
     * 为什么不用 `PopupMenu` / `ListPopupWindow`：它们的垂直位置由框架的
     * drop-down 逻辑决定（优先向下），没有可靠的「向上弹」开关。
     */
    private fun showMenuAboveAnchor(
        header: String,
        items: List<Pair<String, () -> Unit>>,
        anchor: View
    ): PopupWindow {
        val d = resources.displayMetrics.density
        val padH = (20 * d).toInt()
        val padV = (10 * d).toInt()
        val container = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(theme.popupBackgroundColor)
                cornerRadius = 14 * d
            }
            addView(TextView(themedContext).apply {
                text = header
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(theme.genericActiveForegroundColor)
                textSize = 17f
                setPadding(0, padV, 0, padV)
            })
            items.forEach { (label, action) ->
                addView(TextView(themedContext).apply {
                    text = label
                    setTextColor(theme.popupTextColor)
                    textSize = 16f
                    setPadding(0, padV, 0, padV)
                    isClickable = true
                    setOnClickListener {
                        candidateActionMenu?.dismiss()
                        action()
                    }
                })
            }
        }
        val dm = resources.displayMetrics
        val maxWidth = (dm.widthPixels * 0.72f).toInt()
        container.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = container.measuredWidth.coerceAtLeast((140 * d).toInt())
        val h = container.measuredHeight
        val gap = (8 * d).toInt()
        val popup = PopupWindow(container, w, h).apply {
            isFocusable = false
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 12 * d
            setOnDismissListener { candidateActionMenu = null }
        }
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        var x = loc[0] + anchor.width / 2 - w / 2
        if (x < gap) x = gap
        if (x + w > dm.widthPixels - gap) x = dm.widthPixels - w - gap
        // 向上弹：菜单底边 = 锚点顶边 - gap
        var y = loc[1] - h - gap
        if (y < gap) y = gap
        if (y + h > dm.heightPixels) y = dm.heightPixels - h
        popup.showAtLocation(this, Gravity.TOP or Gravity.START, x, y)
        return popup
    }

    private val navbarBackground by ThemeManager.prefs.navbarBackground

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navbarBackground != ThemePrefs.NavbarBackground.Full) {
            return 0
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        // use navigation bar insets when available
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        // in case navigation bar insets goes wrong (eg. on LineageOS 21+ with gesture navigation)
        // use mandatory system gesture insets
        val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        var insetsBottom = max(navBars.bottom, mandatory.bottom)
        if (insetsBottom <= 0) {
            // check system gesture insets and fallback to navigation_bar_frame_height just in case
            val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            if (gesturesBottom > 0) {
                insetsBottom = max(gesturesBottom, context.navbarFrameHeight())
            }
        }
        return insetsBottom
    }

    private val ignoreSystemWindowInsets by AppPrefs.getInstance().advanced.ignoreSystemWindowInsets

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ignoreSystemWindowInsets) {
            // suppress view's own onApplyWindowInsets
            setOnApplyWindowInsetsListener { _, insets -> insets }
        } else {
            // on API 35+, we must call requestApplyInsets() manually after replacing views,
            // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
            requestApplyInsets()
        }
    }

    override fun onDetachedFromWindow() {
        handleEvents = false
        super.onDetachedFromWindow()
    }
}
