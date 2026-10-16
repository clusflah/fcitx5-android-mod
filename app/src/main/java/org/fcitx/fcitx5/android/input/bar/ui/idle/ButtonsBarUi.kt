/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
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

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    private val prefs = AppPrefs.getInstance().keyboard

    // listeners must be kept strongly referenced, ManagedPreference holds them weakly
    private val visibilityListeners = mutableListOf<ManagedPreference.OnChangeListener<Boolean>>()

    /** Every toolbar shortcut can be shown or hidden from Settings → Virtual Keyboard. */
    private fun toolButton(@DrawableRes icon: Int, show: ManagedPreference.PBool) =
        ToolButton(ctx, icon, theme).also { button ->
            val size = ctx.dp(40)
            root.addView(button, FlexboxLayout.LayoutParams(size, size))
            button.visibility = if (show.getValue()) View.VISIBLE else View.GONE
            val listener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
                button.visibility = if (v) View.VISIBLE else View.GONE
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

    /** mod: switches between the 九宫格 and the 26-key layout */
    val t9Button = toolButton(R.drawable.ic_t9, prefs.showT9Button).apply {
        contentDescription = ctx.getString(R.string.t9_keyboard)
    }

    /** opens Bitwarden; hidden when it isn't installed */
    val passwordManagerButton =
        toolButton(R.drawable.ic_password_key, prefs.showPasswordManagerButton).apply {
            contentDescription = "Bitwarden"
            if (!PasswordManager.isInstalled(ctx)) visibility = View.GONE
        }

    val moreButton = toolButton(R.drawable.ic_baseline_more_horiz_24, prefs.showMoreButton).apply {
        contentDescription = ctx.getString(R.string.status_area)
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
    )

    private fun applyOrder(saved: String) {
        val views = ToolbarButton.ordered(saved).mapNotNull { buttonOf[it] }
        views.forEach { v ->
            val lp = v.layoutParams
            root.removeView(v)
            root.addView(v, lp)
        }
    }

    private val orderListener = ManagedPreference.OnChangeListener<String> { _, v -> applyOrder(v) }

    init {
        applyOrder(prefs.toolbarButtonOrder.getValue())
        prefs.toolbarButtonOrder.registerOnChangeListener(orderListener)
    }

}
