/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.icu.text.BreakIterator
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerOn
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageDrawable

class StatusAreaEntryUi(override val ctx: Context, private val theme: Theme) : Ui {

    /** mod: a rounded tile with the icon above a grey label, instead of a circle */
    private val card = ThemeManager.prefs.cardStyle.getValue()

    private val cardDrawable = GradientDrawable().apply { cornerRadius = ctx.dp(18f) }

    private val bkgDrawable = ShapeDrawable(OvalShape())

    val bkg = frameLayout {
        background = bkgDrawable
    }

    val icon = imageView {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    val textIcon = view(::AutoScaleTextView) {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        // keep original typeface, apply textStyle only
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 600 = Semi Bold, 700 = Bold which is too heavy
            typeface = Typeface.create(typeface, 600, false)
        } else {
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    val label = textView {
        textSize = 12f
        gravity = gravityCenter
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(if (card) theme.altKeyTextColor else theme.keyTextColor)
    }

    private fun cardContent() = constraintLayout {
        background = cardDrawable
        add(bkg, lParams(dp(28), dp(28)) {
            topOfParent(dp(14))
            centerHorizontally()
        })
        add(icon, lParams(dp(26), dp(26)) {
            centerOn(bkg)
        })
        add(textIcon, lParams(wrapContent, wrapContent) {
            centerOn(bkg)
        })
        add(label, lParams(matchConstraints, wrapContent) {
            below(bkg, dp(8))
            centerHorizontally()
            setMargins(dp(4), dp(8), dp(4), 0)
        })
    }

    override val root = object : CustomGestureView(ctx) {
        val content = if (card) cardContent() else constraintLayout {
            add(bkg, lParams(dp(48), dp(48)) {
                topOfParent(dp(4))
                centerHorizontally()
                above(label)
            })
            add(icon, lParams {
                centerOn(bkg)
            })
            add(textIcon, lParams(wrapContent, wrapContent) {
                centerOn(bkg)
            })
            add(label, lParams(wrapContent, wrapContent) {
                below(bkg, dp(6))
                centerHorizontally()
            })
        }

        init {
            if (card) {
                add(content, lParams(matchParent, matchParent) { setMargins(dp(7), dp(7), dp(7), dp(7)) })
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92))
            } else {
                add(content, lParams(matchParent, matchParent))
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96))
            }
        }
    }

    fun setEntry(entry: StatusAreaEntry) {
        // mod: a tile shows only the current side of a "current → next" switch, on / off by color
        val shown = if (card) StatusSwitchLabel.of(entry.label) else StatusSwitchLabel.Shown(entry.label, null)
        val on = entry.active || shown.on == true
        val contentColor = when {
            // mod: on a tile the active state is a green icon and label
            card && on -> theme.genericActiveBackgroundColor
            card -> theme.keyTextColor
            entry.active -> theme.genericActiveForegroundColor
            else -> theme.keyTextColor
        }
        if (entry.icon != 0) {
            icon.visibility = View.VISIBLE
            textIcon.visibility = View.GONE
            icon.imageDrawable = ctx.drawable(entry.icon)!!.apply {
                setTint(contentColor)
            }
        } else {
            icon.visibility = View.GONE
            textIcon.visibility = View.VISIBLE
            textIcon.text = getFirstCharacter(shown.label)
            textIcon.setTextColor(contentColor)
        }
        if (card) {
            bkgDrawable.paint.color = android.graphics.Color.TRANSPARENT
            // light tiles on the keyboard color; switched-on ones get a green tint
            cardDrawable.setColor(
                if (on) blend(theme.keyBackgroundColor, theme.genericActiveBackgroundColor, 0.16f)
                else theme.keyBackgroundColor
            )
            label.setTextColor(if (on) theme.genericActiveBackgroundColor else theme.altKeyTextColor)
        } else {
            bkgDrawable.paint.color =
                if (entry.active) theme.genericActiveBackgroundColor else theme.keyBackgroundColor
        }
        label.text = shown.label
    }

    private fun blend(base: Int, tint: Int, amount: Float): Int {
        fun ch(shift: Int) = ((base shr shift and 0xff) * (1 - amount) + (tint shr shift and 0xff) * amount).toInt()
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun getFirstCharacter(s: String): String {
        if (s.isEmpty()) return ""
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val iterator = BreakIterator.getCharacterInstance()
            iterator.setText(s)
            s.substring(iterator.first(), iterator.next())
        } else {
            s.substring(0, s.offsetByCodePoints(0, 1))
        }
    }
}
