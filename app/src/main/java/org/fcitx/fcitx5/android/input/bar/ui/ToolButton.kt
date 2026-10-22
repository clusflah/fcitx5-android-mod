/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.ViewPropertyAnimator
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import org.fcitx.fcitx5.android.utils.circlePressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageResource
import splitties.views.padding

class ToolButton(context: Context) : CustomGestureView(context) {

    companion object {
        val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation
        // mod
        val roundButtons by ThemeManager.prefs.cardStyle
    }

    /** mod: key-colored circle behind the icon, drawn when [roundButtons] is on */
    private var circleColor: Int? = null

    private val image = imageView {
        isClickable = false
        isFocusable = false
        padding = dp(10)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    var iconRotation: Float
        get() = image.rotation
        set(value) {
            image.rotation = value
        }

    constructor(context: Context, @DrawableRes icon: Int, theme: Theme) : this(context) {
        image.imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        setIcon(icon)
        if (roundButtons) circleColor = theme.keyBackgroundColor
        setPressHighlightColor(theme.keyPressHighlightColor)
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun iconAnimate(): ViewPropertyAnimator = image.animate()

    fun setIcon(@DrawableRes icon: Int) {
        image.imageResource = icon
    }

    fun setPressHighlightColor(@ColorInt color: Int) {
        val highlight = if (disableAnimation) {
            circlePressHighlightDrawable(color)
        } else {
            borderlessRippleDrawable(color, dp(20))
        }
        val circle = circleColor
        if (circle == null) {
            background = highlight
        } else {
            // the circle stays a little inside the button, so neighbours don't touch
            background = InsetDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(circle)
            }, dp(2))
            foreground = highlight
        }
    }
}
