/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: dresses the 大模型助手 / 中外互译 dialog in the keyboard's colors when the card style is on:
 * a rounded keyboard-colored card, white rounded "keys" for buttons, a green primary button
 * (发送 / 翻译 / 输入), white rounded text fields with a green cursor, and prompt chips that are
 * white with green text when selected, like the tiles in the keyboard's panels.
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.dimensions.dp

object AiDialogStyle {

    /** buttons tagged with this are drawn green */
    const val PRIMARY = "ai_primary"

    val enabled get() = ThemeManager.prefs.cardStyle.getValue()

    val theme: Theme get() = ThemeManager.activeTheme

    private fun View.rounded(color: Int, radius: Float = dp(10f)) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    /** a rounded keyboard-colored card in place of the dialog's own frame */
    fun styleWindow(window: Window) {
        val t = theme
        val ctx = window.context
        window.setBackgroundDrawable(InsetDrawable(GradientDrawable().apply {
            cornerRadius = ctx.dp(20f)
            setColor(t.keyboardColor)
        }, ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(16)))
    }

    fun title(view: TextView) {
        view.textSize = 18f
        view.typeface = Typeface.DEFAULT_BOLD
        view.setTextColor(theme.keyTextColor)
    }

    /** walk the dialog's views once after they are built */
    fun apply(root: View) {
        val t = theme
        when (root) {
            is ViewGroup -> {
                if (root is Spinner) {
                    root.background = root.rounded(t.keyBackgroundColor)
                    root.setPadding(root.dp(12), 0, root.dp(12), 0)
                    // the shown item in the keyboard's text color (the dropdown keeps the system look)
                    (root.adapter as? ArrayAdapter<*>)?.let { old ->
                        val items = (0 until old.count).map { old.getItem(it).toString() }
                        val selected = root.selectedItemPosition
                        root.adapter = object : ArrayAdapter<String>(
                            root.context, android.R.layout.simple_spinner_dropdown_item, items
                        ) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                                super.getView(position, convertView, parent).also {
                                    (it as? TextView)?.setTextColor(t.keyTextColor)
                                }
                        }
                        root.setSelection(selected, false)
                    }
                    return
                }
                for (i in 0 until root.childCount) apply(root.getChildAt(i))
            }
            is EditText -> {
                root.background = root.rounded(t.keyBackgroundColor, root.dp(12f))
                root.setPadding(root.dp(12), root.dp(8), root.dp(12), root.dp(8))
                root.textSize = 15f
                root.setTextColor(t.keyTextColor)
                root.setHintTextColor(t.candidateCommentColor)
                root.highlightColor = (ThemePresetMod.JADE and 0x00ffffff) or 0x40000000
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    root.textCursorDrawable = GradientDrawable().apply {
                        setColor(ThemePresetMod.JADE)
                        setSize(root.dp(2), root.dp(20))
                    }
                }
                (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.setMargins(0, root.dp(4), 0, root.dp(2))
                    root.layoutParams = it
                }
            }
            is Button -> button(root, primary = root.tag == PRIMARY)
            is ProgressBar -> root.indeterminateTintList = ColorStateList.valueOf(ThemePresetMod.JADE)
            is TextView -> root.setTextColor(t.altKeyTextColor)
        }
    }

    fun button(b: Button, primary: Boolean = false) {
        val t = theme
        b.isAllCaps = false
        b.stateListAnimator = null
        b.textSize = 14f
        b.minHeight = b.dp(34)
        b.minimumHeight = b.dp(34)
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(b.dp(12), 0, b.dp(12), 0)
        b.background = b.rounded(if (primary) ThemePresetMod.JADE else t.keyBackgroundColor)
        b.foreground = pressHighlightDrawable(t.keyPressHighlightColor)
        b.setTextColor(if (primary) 0xffffffff.toInt() else t.keyTextColor)
        b.alpha = 1f
        (b.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.setMargins(b.dp(3), b.dp(3), b.dp(3), b.dp(3))
            b.layoutParams = it
        }
    }

    /**
     * prompt / format chips: the selected one is white with green text, the others grey;
     * [big] for the prompts, which are the main choice of the dialog
     */
    fun chip(b: Button, selected: Boolean, big: Boolean = false) {
        button(b)
        if (big) {
            b.textSize = 16f
            b.minHeight = b.dp(44)
            b.minimumHeight = b.dp(44)
            b.setPadding(b.dp(18), 0, b.dp(18), 0)
            (b.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.setMargins(b.dp(4), b.dp(4), b.dp(4), b.dp(4))
                b.layoutParams = it
            }
        }
        val t = theme
        b.background = b.rounded(if (selected) t.keyBackgroundColor else t.altKeyBackgroundColor.withAlpha(0x80))
        b.setTextColor(if (selected) ThemePresetMod.JADE else t.altKeyTextColor)
    }

    /** the model picker and ⚙ in the top right corner: small, quiet */
    fun compact(spinner: Spinner, settings: Button) {
        val t = theme
        spinner.background = spinner.rounded(t.altKeyBackgroundColor.withAlpha(0x80), spinner.dp(15f))
        spinner.setPadding(spinner.dp(10), 0, spinner.dp(10), 0)
        (spinner.adapter as? ArrayAdapter<*>)?.let { old ->
            val items = (0 until old.count).map { old.getItem(it).toString() }
            val selected = spinner.selectedItemPosition
            spinner.adapter = object : ArrayAdapter<String>(
                spinner.context, android.R.layout.simple_spinner_dropdown_item, items
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also {
                        (it as? TextView)?.apply {
                            textSize = 13f
                            setTextColor(t.altKeyTextColor)
                            setPadding(0, 0, 0, 0)
                            minHeight = 0
                        }
                    }
            }
            spinner.setSelection(selected, false)
        }
        button(settings)
        settings.setPadding(0, 0, 0, 0)
        settings.minHeight = 0
        settings.minimumHeight = 0
        settings.textSize = 15f
        settings.background = settings.rounded(t.altKeyBackgroundColor.withAlpha(0x80), settings.dp(15f))
        settings.setTextColor(t.altKeyTextColor)
        (settings.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.setMargins(settings.dp(6), 0, 0, 0)
            settings.layoutParams = it
        }
    }

    private fun Int.withAlpha(alpha: Int) = (this and 0x00ffffff) or (alpha shl 24)
}
