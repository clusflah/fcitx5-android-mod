/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: dresses the 大模型助手 / 中外互译 dialog in the keyboard's colors when the card style is on:
 * a rounded keyboard-colored card, white rounded "keys" for buttons, a green primary button
 * (发送 / 翻译 / 输入), white rounded text fields with a green cursor, and prompt chips that are
 * white with green text when selected, like the tiles in the keyboard's panels.
 * When Liquid Glass is active, upgrades the dialog card and all inner controls to real-time
 * cross-window blurred & AGSL-refracted Liquid Glass.
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
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod
import org.fcitx.fcitx5.android.input.glass.Glass
import org.fcitx.fcitx5.android.input.glass.GlassDialogFrame
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.dimensions.dp

object AiDialogStyle {

    /** buttons tagged with this are drawn green */
    const val PRIMARY = "ai_primary"

    /** text views tagged with this are section headings: dark and bold */
    const val HEADING = "ai_heading"

    val enabled get() = ThemeManager.prefs.cardStyle.getValue() || Glass.isDialogGlassActive()

    val theme: Theme get() = ThemeManager.activeTheme

    private fun View.rounded(color: Int, radius: Float = dp(10f)) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    /** a rounded keyboard-colored (or Liquid Glass) card in place of the dialog's own frame */
    fun styleWindow(window: Window, explicitWidthPx: Int? = null, isAlert: Boolean = false) {
        val t = theme
        if (Glass.styleDialogWindow(window, t, explicitWidthPx, isAlert)) return
        val ctx = window.context
        window.setBackgroundDrawable(InsetDrawable(GradientDrawable().apply {
            cornerRadius = ctx.dp(20f)
            setColor(t.keyboardColor)
        }, ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12)))
    }

    fun title(view: TextView) {
        val t = theme
        view.textSize = 18f
        view.typeface = Typeface.DEFAULT_BOLD
        view.setTextColor(t.keyTextColor)
        if (Glass.isDialogGlassActive(t)) {
            val shadowColor = if (t.isDark) 0x66000000 else 0x55ffffff
            view.setShadowLayer(2f, 0f, 1f, shadowColor)
        }
    }

    /** walk the dialog's views once after they are built */
    fun apply(root: View) {
        val t = theme
        when (root) {
            is ViewGroup -> {
                if (root is Spinner) {
                    root.background = Glass.dialogControlBackground(
                        view = root,
                        theme = t,
                        radius = root.dp(10f),
                        bkgColor = t.keyBackgroundColor,
                        textColor = t.keyTextColor,
                        refractionScale = 1f,
                        extraOpacityBoost = 0.08f,
                        pressMorph = true,
                        scaleViewOnPress = true,
                        crossBlurAlpha = 0.68f
                    ) ?: root.rounded(t.keyBackgroundColor)
                    root.setPadding(root.dp(12), 0, root.dp(12), 0)
                    root.setPopupBackgroundDrawable(GradientDrawable().apply {
                        cornerRadius = root.dp(12f)
                        setColor(t.popupBackgroundColor)
                        setStroke(root.dp(1), t.dividerColor)
                    })
                    // the shown item in the keyboard's text color
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

                            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                                super.getDropDownView(position, convertView, parent).also {
                                    (it as? TextView)?.setTextColor(
                                        if (position == root.selectedItemPosition) ThemePresetMod.JADE else t.popupTextColor
                                    )
                                }
                        }
                        root.setSelection(selected, false)
                    }
                    return
                }
                if (root is ScrollView || root is HorizontalScrollView || root is androidx.recyclerview.widget.RecyclerView) {
                    Glass.attachScrollInvalidator(root, t)
                }
                for (i in 0 until root.childCount) apply(root.getChildAt(i))
            }
            is EditText -> {
                root.background = Glass.dialogControlBackground(
                    view = root,
                    theme = t,
                    radius = root.dp(13f),
                    bkgColor = t.keyBackgroundColor,
                    textColor = t.keyTextColor,
                    refractionScale = 0.7f,
                    extraOpacityBoost = 0.16f,
                    pressMorph = false,
                    crossBlurAlpha = 0.74f
                ) ?: root.rounded(t.keyBackgroundColor, root.dp(12f))
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
            is TextView -> {
                if (root.tag == HEADING) {
                    root.setTextColor(t.keyTextColor)
                    root.typeface = Typeface.DEFAULT_BOLD
                } else {
                    root.setTextColor(t.altKeyTextColor)
                }
                if (Glass.isDialogGlassActive(t)) {
                    val shadowColor = if (t.isDark) 0x55000000 else 0x44ffffff
                    root.setShadowLayer(1.8f, 0f, 1f, shadowColor)
                }
            }
        }
    }

    /** the settings / prompt / error dialogs opened from the assistant: same card, keys and fields */
    fun alert(d: AlertDialog) {
        val t = theme
        d.window?.let { styleWindow(it, isAlert = true) }
        d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.let { title(it) }
        d.findViewById<TextView>(android.R.id.message)?.setTextColor(t.keyTextColor)
        d.findViewById<View>(androidx.appcompat.R.id.scrollView)?.let {
            GlassDialogFrame.attachScrollRefractionInvalidator(it, t)
        }
        d.findViewById<View>(androidx.appcompat.R.id.custom)?.let { apply(it) }
        d.listView?.divider = null
        listOf(
            AlertDialog.BUTTON_POSITIVE to true,
            AlertDialog.BUTTON_NEGATIVE to false,
            AlertDialog.BUTTON_NEUTRAL to false
        ).forEach { (which, primary) ->
            d.getButton(which)?.takeIf { it.visibility == View.VISIBLE }?.let { button(it, primary) }
        }
    }

    /** Styles platform `android.app.AlertDialog` instances attached to the IME window (`showDialog`). */
    fun platformAlert(d: android.app.AlertDialog) {
        val t = theme
        d.window?.let { styleWindow(it, isAlert = true) }
        val titleId = d.context.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) d.findViewById<TextView>(titleId)?.let { title(it) }
        d.findViewById<TextView>(android.R.id.message)?.let {
            it.setTextColor(t.keyTextColor)
            Glass.applyKeyTextShadow(it, t)
        }
        val scrollId = d.context.resources.getIdentifier("scrollView", "id", "android")
        if (scrollId != 0) d.findViewById<View>(scrollId)?.let { Glass.attachScrollInvalidator(it, t) }
        val customId = d.context.resources.getIdentifier("custom", "id", "android")
        if (customId != 0) d.findViewById<View>(customId)?.let { root ->
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    Glass.attachScrollInvalidator(root.getChildAt(i), t)
                }
            }
        }
        d.listView?.divider = null
        listOf(
            android.app.AlertDialog.BUTTON_POSITIVE to true,
            android.app.AlertDialog.BUTTON_NEGATIVE to false,
            android.app.AlertDialog.BUTTON_NEUTRAL to false
        ).forEach { (which, primary) ->
            d.getButton(which)?.takeIf { it.visibility == View.VISIBLE }?.let { button(it, primary) }
        }
    }

    /** list items in the keyboard's text color, for dialogs styled with [alert] */
    fun <T> textAdapter(ctx: android.content.Context, layout: Int, items: MutableList<T>) =
        object : ArrayAdapter<T>(ctx, layout, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also {
                    if (enabled) (it as? TextView)?.setTextColor(theme.keyTextColor)
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
        val glassBg = Glass.dialogControlBackground(
            view = b,
            theme = t,
            radius = b.dp(11f),
            bkgColor = if (primary) ThemePresetMod.JADE else t.keyBackgroundColor,
            textColor = if (primary) 0xffffffff.toInt() else t.keyTextColor,
            refractionScale = if (primary) 1.25f else 1.05f,
            extraOpacityBoost = if (primary) 0.26f else 0.08f,
            pressMorph = true,
            scaleViewOnPress = true,
            crossBlurAlpha = if (primary) 0.86f else 0.68f
        )
        b.background = glassBg ?: b.rounded(if (primary) ThemePresetMod.JADE else t.keyBackgroundColor)
        b.foreground = if (glassBg != null) null else pressHighlightDrawable(t.keyPressHighlightColor)
        b.setTextColor(if (primary) 0xffffffff.toInt() else t.keyTextColor)
        if (glassBg != null && primary) {
            b.setShadowLayer(2f, 0f, 1f, 0x44000000)
        } else {
            b.setShadowLayer(0f, 0f, 0f, 0)
        }
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
            // one low row: a little bigger than the other buttons, but not taller
            b.textSize = 15f
            b.minHeight = b.dp(34)
            b.minimumHeight = b.dp(34)
            b.setPadding(b.dp(16), 0, b.dp(16), 0)
            (b.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.setMargins(b.dp(3), b.dp(3), b.dp(3), b.dp(3))
                b.layoutParams = it
            }
        }
        val t = theme
        val glassBg = Glass.dialogControlBackground(
            view = b,
            theme = t,
            radius = b.dp(11f),
            bkgColor = if (selected) t.keyBackgroundColor else t.altKeyBackgroundColor.withAlpha(0x80),
            textColor = if (selected) ThemePresetMod.JADE else t.altKeyTextColor,
            refractionScale = if (selected) 1.2f else 0.85f,
            extraOpacityBoost = if (selected) 0.24f else -0.10f,
            pressMorph = true,
            scaleViewOnPress = true,
            crossBlurAlpha = if (selected) 0.84f else 0.46f
        )
        b.background = glassBg ?: b.rounded(if (selected) t.keyBackgroundColor else t.altKeyBackgroundColor.withAlpha(0x80))
        b.foreground = if (glassBg != null) null else pressHighlightDrawable(t.keyPressHighlightColor)
        b.setTextColor(if (selected) ThemePresetMod.JADE else t.altKeyTextColor)
        b.typeface = if (selected && glassBg != null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** the model picker and ⚙ in the top right corner: small, quiet */
    fun compact(spinner: Spinner, settings: Button) {
        val t = theme
        spinner.background = Glass.dialogControlBackground(
            view = spinner,
            theme = t,
            radius = spinner.dp(15f),
            bkgColor = t.altKeyBackgroundColor.withAlpha(0x80),
            textColor = t.altKeyTextColor,
            refractionScale = 0.95f,
            extraOpacityBoost = -0.02f,
            pressMorph = true,
            scaleViewOnPress = true,
            crossBlurAlpha = 0.56f
        ) ?: spinner.rounded(t.altKeyBackgroundColor.withAlpha(0x80), spinner.dp(15f))
        spinner.setPadding(spinner.dp(10), 0, spinner.dp(10), 0)
        spinner.setPopupBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = spinner.dp(12f)
            setColor(t.popupBackgroundColor)
            setStroke(spinner.dp(1), t.dividerColor)
        })
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

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getDropDownView(position, convertView, parent).also {
                        (it as? TextView)?.setTextColor(
                            if (position == spinner.selectedItemPosition) ThemePresetMod.JADE else t.popupTextColor
                        )
                    }
            }
            spinner.setSelection(selected, false)
        }
        button(settings)
        settings.setPadding(0, 0, 0, 0)
        settings.minHeight = 0
        settings.minimumHeight = 0
        settings.textSize = 15f
        val glassSettingsBg = Glass.dialogControlBackground(
            view = settings,
            theme = t,
            radius = settings.dp(15f),
            bkgColor = t.altKeyBackgroundColor.withAlpha(0x80),
            textColor = t.altKeyTextColor,
            refractionScale = 0.95f,
            extraOpacityBoost = -0.02f,
            pressMorph = true,
            scaleViewOnPress = true,
            crossBlurAlpha = 0.56f
        )
        settings.background = glassSettingsBg ?: settings.rounded(t.altKeyBackgroundColor.withAlpha(0x80), settings.dp(15f))
        if (glassSettingsBg != null) settings.foreground = null
        settings.setTextColor(t.altKeyTextColor)
        (settings.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.setMargins(settings.dp(6), 0, 0, 0)
            settings.layoutParams = it
        }
    }

    private fun Int.withAlpha(alpha: Int) = (this and 0x00ffffff) or (alpha shl 24)
}
