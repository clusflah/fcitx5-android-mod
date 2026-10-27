/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.password.PasswordManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    /** the buttons, spread out while they fit */
    private val bar = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    // mod: when the buttons don't fit they keep their size and the row scrolls sideways
    override val root = android.widget.HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun barParams(size: Int) = FlexboxLayout.LayoutParams(size, size).apply { flexShrink = 0f }

    private val prefs = AppPrefs.getInstance().keyboard

    // listeners must be kept strongly referenced, ManagedPreference holds them weakly
    private val visibilityListeners = mutableListOf<ManagedPreference.OnChangeListener<Boolean>>()

    /** mod: buttons pinned in Settings sit here, left of the hide-keyboard button, toolbar collapsed or not */
    val pinnedRoot = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Every toolbar shortcut can be shown or hidden from Settings → Virtual Keyboard. */
    private fun toolButton(
        @DrawableRes icon: Int, show: ManagedPreference.PBool, allowed: () -> Boolean = { true }
    ) =
        ToolButton(ctx, icon, theme).also { button ->
            val size = ctx.dp(40)
            bar.addView(button, barParams(size))
            button.visibility = if (show.getValue() && allowed()) View.VISIBLE else View.GONE
            val listener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
                button.visibility = if (v && allowed()) View.VISIBLE else View.GONE
            }
            visibilityListeners.add(listener)
            show.registerOnChangeListener(listener)
        }

    val undoButton = toolButton(R.drawable.ic_baseline_undo_24, prefs.showUndoButton).apply {
        contentDescription = ctx.getString(R.string.undo)
    }

    val redoButton = toolButton(R.drawable.ic_baseline_redo_24, prefs.showRedoButton).apply {
        contentDescription = ctx.getString(R.string.redo)
    }

    val cursorMoveButton = toolButton(R.drawable.ic_cursor_move, prefs.showCursorMoveButton).apply {
        contentDescription = ctx.getString(R.string.text_editing)
    }

    val clipboardButton = toolButton(R.drawable.ic_clipboard, prefs.showClipboardButton).apply {
        contentDescription = ctx.getString(R.string.clipboard)
    }

    val aiAssistButton = toolButton(R.drawable.ic_ai_assist, prefs.showAiAssistButton).apply {
        contentDescription = ctx.getString(R.string.ai_assist)
    }

    val aiPredictButton = toolButton(R.drawable.ic_ai_predict, prefs.showAiPredictButton).apply {
        contentDescription = ctx.getString(R.string.ai_predict)
    }

    val translateButton = toolButton(R.drawable.ic_translate, prefs.showTranslateButton).apply {
        contentDescription = ctx.getString(R.string.ai_translate)
    }

    /** mod: opens the 键盘选择 panel; long press switches between the 九宫格 and the 26-key layout */
    val t9Button = toolButton(R.drawable.ic_t9, prefs.showT9Button).apply {
        contentDescription = ctx.getString(R.string.keyboard_picker)
    }

    /** opens Bitwarden; hidden when it isn't installed */
    val passwordManagerButton =
        toolButton(R.drawable.ic_password_key, prefs.showPasswordManagerButton, { PasswordManager.isInstalled(ctx) }).apply {
            contentDescription = "Bitwarden"
        }

    // mod: 更多 is the first button of the bar (IdleUi.menuButton), so never shown in the row
    val moreButton = toolButton(R.drawable.ic_toolbar_more, prefs.showMoreButton, { false }).apply {
        contentDescription = ctx.getString(R.string.status_area)
    }

    /** false in password fields: no recording there */
    private var voiceAllowed = true

    /** mod: records with the mod's own voice input */
    val voiceButton =
        toolButton(R.drawable.ic_baseline_keyboard_voice_24, prefs.showVoiceInputButton, { voiceAllowed }).apply {
            contentDescription = ctx.getString(R.string.switch_to_voice_input)
        }

    /** mod: floats the keyboard (FloatingKeyboard); only offered in landscape */
    val floatingButton = toolButton(R.drawable.ic_floating_keyboard, prefs.showFloatingButton, {
        ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }).apply {
        contentDescription = ctx.getString(R.string.floating_keyboard_landscape)
    }

    fun setVoiceAllowed(allowed: Boolean) {
        voiceAllowed = allowed
        voiceButton.visibility =
            if (allowed && prefs.showVoiceInputButton.getValue()) View.VISIBLE else View.GONE
    }

    // mod: put the buttons in the order chosen in Settings → Virtual Keyboard → Toolbar buttons
    private val buttonOf = mapOf(
        ToolbarButton.Undo to undoButton,
        ToolbarButton.Redo to redoButton,
        ToolbarButton.CursorMove to cursorMoveButton,
        ToolbarButton.Clipboard to clipboardButton,
        ToolbarButton.AiAssist to aiAssistButton,
        ToolbarButton.AiPredict to aiPredictButton,
        ToolbarButton.Translate to translateButton,
        ToolbarButton.T9 to t9Button,
        ToolbarButton.PasswordManager to passwordManagerButton,
        ToolbarButton.More to moreButton,
        ToolbarButton.Voice to voiceButton,
        ToolbarButton.Floating to floatingButton,
    )

    fun button(b: ToolbarButton): View? = buttonOf[b]

    /** the order chosen in Settings; pinned buttons go to [pinnedRoot] (same relative order) */
    private fun applyLayout() {
        val pinned = ToolbarButton.pinned(prefs.toolbarPinnedButtons.getValue())
        val size = ctx.dp(40)
        ToolbarButton.ordered(prefs.toolbarButtonOrder.getValue()).forEach { b ->
            val v = buttonOf[b] ?: return@forEach
            (v.parent as? ViewGroup)?.removeView(v)
            if (b in pinned) pinnedRoot.addView(v, LinearLayout.LayoutParams(size, size))
            else bar.addView(v, barParams(size))
        }
    }

    private val layoutListener = ManagedPreference.OnChangeListener<String> { _, _ -> applyLayout() }

    init {
        applyLayout()
        prefs.toolbarButtonOrder.registerOnChangeListener(layoutListener)
        prefs.toolbarPinnedButtons.registerOnChangeListener(layoutListener)
    }

}
