/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.text.TextUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalMargin
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

class ClipboardSuggestionUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val icon = imageView {
        imageDrawable = drawable(R.drawable.ic_clipboard)!!.apply {
            setTint(theme.altKeyTextColor)
        }
    }

    val text = textView {
        isSingleLine = true
        maxWidth = dp(120)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.altKeyTextColor)
    }

    private val layout = constraintLayout {
        val spacing = dp(4)
        add(icon, lParams(dp(20), dp(20)) {
            startOfParent(spacing)
            before(text)
            centerVertically()
        })
        add(text, lParams(wrapContent, wrapContent) {
            after(icon, spacing)
            endOfParent(spacing)
            centerVertically()
        })
    }

    val suggestionView = CustomGestureView(ctx).apply {
        add(layout, lParams(wrapContent, matchParent))
        background = rippleDrawable(theme.keyPressHighlightColor)
    }

    /** "↗ github.com": opens an http(s) link found in the clipboard; hidden otherwise */
    val linkView = textView {
        isSingleLine = true
        maxWidth = dp(140)
        ellipsize = TextUtils.TruncateAt.END
        gravity = android.view.Gravity.CENTER_VERTICAL
        setTextColor(theme.altKeyTextColor)
        setCompoundDrawablesRelativeWithIntrinsicBounds(
            drawable(R.drawable.ic_open_in_new)!!.mutate().apply {
                setTint(theme.altKeyTextColor)
                setBounds(0, 0, dp(18), dp(18))
            }, null, null, null
        )
        compoundDrawablePadding = dp(4)
        setPadding(dp(8), 0, dp(8), 0)
        background = rippleDrawable(theme.keyPressHighlightColor)
        visibility = android.view.View.GONE
    }

    private val row = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(suggestionView, android.widget.LinearLayout.LayoutParams(wrapContent, matchParent))
        addView(linkView, android.widget.LinearLayout.LayoutParams(wrapContent, matchParent).apply {
            marginStart = dp(4)
        })
    }

    override val root = constraintLayout {
        add(row, lParams(wrapContent, matchConstraints) {
            centerInParent()
            verticalMargin = dp(4)
        })
    }
}
