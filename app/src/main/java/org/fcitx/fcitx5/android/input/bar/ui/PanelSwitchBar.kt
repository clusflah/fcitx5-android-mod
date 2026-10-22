/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: toolbar of the 剪贴板 / 常用语 panels in the card style: ∧ on the left, a "剪贴板 | 常用语"
 * segmented switch in the middle and the panel's own buttons on the right.
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

object PanelSwitchBar {

    /**
     * @param labels the segments, e.g. 剪贴板 and 常用语
     * @param selected index of the segment of this panel
     * @param onSelect called with the index of another segment
     * @param actions buttons shown on the right
     */
    fun create(
        ctx: Context, theme: Theme, labels: List<String>, selected: Int,
        onBack: () -> Unit, onSelect: (Int) -> Unit, actions: List<View>
    ): View = FrameLayout(ctx).apply {
        addView(ToolButton(ctx, R.drawable.ic_baseline_expand_less_24, theme).apply {
            contentDescription = ctx.getString(R.string.back_to_keyboard)
            setOnClickListener { onBack() }
        }, FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(40), Gravity.START or Gravity.CENTER_VERTICAL))

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(10f)
                setColor(theme.altKeyBackgroundColor.withAlpha(0x80))
            }
            setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            labels.forEachIndexed { i, label ->
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
                    setTextColor(if (i == selected) theme.keyTextColor else theme.altKeyTextColor)
                    if (i == selected) background = GradientDrawable().apply {
                        cornerRadius = ctx.dp(8f)
                        setColor(theme.keyBackgroundColor)
                    }
                    setOnClickListener { if (i != selected) onSelect(i) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(34), Gravity.CENTER))

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            actions.forEach {
                (it.parent as? ViewGroup)?.removeView(it)
                addView(it, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun Int.withAlpha(alpha: Int) = (this and 0x00ffffff) or (alpha shl 24)
}
