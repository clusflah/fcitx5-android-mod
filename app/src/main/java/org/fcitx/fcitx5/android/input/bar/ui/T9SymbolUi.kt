/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 九宫格: the row of symbols shown in place of the toolbar when 1 is tapped with nothing typed.
 * Scrolls sideways; ✕ closes it. KawaiiBarComponent shows it, T9Keyboard decides when.
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view

class T9SymbolUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val scroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, matchParent))
    }

    val closeButton = ToolButton(ctx, R.drawable.ic_baseline_close_24, theme).apply {
        contentDescription = ctx.getString(android.R.string.cancel)
    }

    private val divider = view(::View) {
        setBackgroundColor(theme.dividerColor)
    }

    override val root = ctx.constraintLayout {
        add(closeButton, lParams(dp(40), dp(40)) {
            centerVertically()
            endOfParent()
        })
        add(divider, lParams(dp(1), dp(24)) {
            centerVertically()
            before(closeButton)
        })
        add(scroll, lParams(matchConstraints, matchConstraints) {
            centerVertically()
            startOfParent()
            before(divider)
        })
    }

    fun setSymbols(symbols: List<String>, onPick: (String) -> Unit) {
        row.removeAllViews()
        symbols.forEach { symbol ->
            row.addView(TextView(ctx).apply {
                text = symbol
                textSize = 20f
                gravity = Gravity.CENTER
                minWidth = dp(48)
                setPadding(dp(8), 0, dp(8), 0)
                setTextColor(theme.candidateTextColor)
                background = pressHighlightDrawable(theme.keyPressHighlightColor)
                setOnClickListener {
                    InputFeedbacks.hapticFeedback(it)
                    InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
                    onPick(symbol)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, matchParent))
        }
        scroll.scrollTo(0, 0)
    }
}
