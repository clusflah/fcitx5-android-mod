/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: a menu drawn in the keyboard theme instead of the system PopupMenu: a rounded card in the
 * popup color, one row per item, the current item green with a ✓. It opens upwards over the app,
 * since its anchors sit at the top of the keyboard.
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod
import splitties.dimensions.dp

object CardPopupMenu {

    /**
     * @param selected index of the item in use, or -1
     * @param onPick called with the index of the tapped item
     * @return the window, e.g. to dismiss it when the anchor goes away
     */
    fun show(
        anchor: View, theme: Theme, labels: List<String>, selected: Int,
        width: Int = anchor.dp(200), onPick: (Int) -> Unit
    ): PopupWindow {
        val ctx = anchor.context
        lateinit var window: PopupWindow
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(6), 0, ctx.dp(6))
            labels.forEachIndexed { i, label ->
                val isSelected = i == selected
                addView(TextView(ctx).apply {
                    text = if (isSelected) "$label  ✓" else label
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(if (isSelected) ThemePresetMod.JADE else theme.popupTextColor)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(theme.keyPressHighlightColor))
                    }
                    setOnClickListener {
                        window.dismiss()
                        onPick(i)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(44)))
            }
        }
        val content = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(14f)
                setColor(theme.popupBackgroundColor)
                setStroke(ctx.dp(1), theme.dividerColor)
            }
            clipToOutline = true
            addView(list)
        }
        list.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        val height = minOf(list.measuredHeight, ctx.dp(320))
        window = PopupWindow(content, width, height, true).apply {
            elevation = ctx.dp(8f)
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        window.showAsDropDown(anchor, 0, -(anchor.height + height + ctx.dp(4)))
        // keep the item in use in view
        if (selected > 0) content.post { content.scrollTo(0, list.getChildAt(selected).top - ctx.dp(6)) }
        return window
    }
}
