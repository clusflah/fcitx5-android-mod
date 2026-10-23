/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: long-press a toolbar button, slide onto one of the options shown above it and let go to pick
 * it (letting go anywhere else cancels). Drawn like CardPopupMenu; the popup doesn't take the touch,
 * so the button keeps getting the finger's moves.
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod
import splitties.dimensions.dp

object DragChooser {

    /**
     * Tap [button] runs [onClick]; long-press shows [labels] above it, and releasing over one runs
     * [onPick] with its index.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(button: View, theme: Theme, labels: () -> List<String>, onClick: () -> Unit, onPick: (Int) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var popup: PopupWindow? = null
        var items: List<TextView> = emptyList()
        var hovered = -1
        var longPressed = false

        fun highlight(i: Int) {
            if (i == hovered) return
            hovered = i
            items.forEachIndexed { k, v ->
                val on = k == i
                v.setTextColor(if (on) 0xffffffff.toInt() else theme.popupTextColor)
                v.background = if (on) GradientDrawable().apply {
                    cornerRadius = v.dp(10f)
                    setColor(ThemePresetMod.JADE)
                } else null
            }
            if (i >= 0) InputFeedbacks.hapticFeedback(button)
        }

        fun show() {
            val ctx = button.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f)
                    setColor(theme.popupBackgroundColor)
                    setStroke(dp(1), theme.dividerColor)
                }
            }
            items = labels().map { label ->
                TextView(ctx).apply {
                    text = label
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                    setTextColor(theme.popupTextColor)
                }.also { row.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(44))) }
            }
            hovered = -1
            row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = maxOf(row.measuredWidth, ctx.dp(150))
            val h = row.measuredHeight
            popup = PopupWindow(row, w, h, false).apply {
                isTouchable = false
                elevation = ctx.dp(8f)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                showAsDropDown(button, (button.width - w) / 2, -(button.height + h + ctx.dp(6)))
            }
        }

        fun indexAt(rawX: Float, rawY: Float): Int {
            val r = Rect()
            items.forEachIndexed { i, v ->
                if (v.getGlobalVisibleRect(r) && r.contains(rawX.toInt(), rawY.toInt())) return i
            }
            return -1
        }

        val longPress = Runnable {
            longPressed = true
            // keep the finger's moves even when they go sideways (the toolbar row scrolls)
            button.parent?.requestDisallowInterceptTouchEvent(true)
            InputFeedbacks.hapticFeedback(button)
            show()
        }

        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    v.isPressed = true
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (longPressed) highlight(indexAt(e.rawX, e.rawY))
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    v.isPressed = false
                    if (longPressed) {
                        val picked = indexAt(e.rawX, e.rawY)
                        popup?.dismiss(); popup = null
                        if (picked >= 0) onPick(picked)
                    } else {
                        v.performClick()
                        onClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    v.isPressed = false
                    popup?.dismiss(); popup = null
                }
            }
            true
        }
    }
}
