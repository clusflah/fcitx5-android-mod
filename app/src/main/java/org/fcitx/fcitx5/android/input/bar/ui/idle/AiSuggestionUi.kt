/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Horizontal strip of AI 联想 suggestions shown in the idle bar.
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui

class AiSuggestionUi(override val ctx: Context, private val theme: Theme) : Ui {

    var onSuggestionClick: ((String) -> Unit)? = null

    private val chips = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val icon = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_ai_predict)
        setColorFilter(theme.altKeyTextColor)
    }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon, LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).apply { marginStart = ctx.dp(4) })
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    fun setLoading() {
        chips.removeAllViews()
        chips.addView(TextView(ctx).apply {
            text = "…"
            textSize = 16f
            setTextColor(theme.altKeyTextColor)
            setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
        })
    }

    fun setSuggestions(items: List<String>) {
        chips.removeAllViews()
        items.forEach { s ->
            chips.addView(TextView(ctx).apply {
                text = s
                textSize = 16f
                isSingleLine = true
                setTextColor(theme.keyTextColor)
                background = GradientDrawable().apply {
                    setColor(theme.keyBackgroundColor); cornerRadius = ctx.dp(14f)
                }
                setPadding(ctx.dp(12), ctx.dp(5), ctx.dp(12), ctx.dp(5))
                setOnClickListener { onSuggestionClick?.invoke(s) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = ctx.dp(6) })
        }
    }
}
