/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: long-press a toolbar button and two round targets appear above it, one up-left and one
 * up-right; slide toward one (it turns green) and let go to pick it. Letting go without having moved
 * far enough cancels. The choice goes by the direction of the drag, so the finger doesn't have to land
 * exactly on a circle. The popup doesn't take the touch, so the button keeps getting the moves.
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod
import splitties.dimensions.dp

object DragChooser {

    /**
     * Tap [button] runs [onClick]; long-press shows the two [labels] (left, right) above it, and
     * releasing after sliding toward one runs [onPick] with 0 (left) or 1 (right).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(button: View, theme: Theme, labels: () -> List<String>, onClick: () -> Unit, onPick: (Int) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var popup: PopupWindow? = null
        var circles: List<TextView> = emptyList()
        var hovered = -1
        var longPressed = false
        var downX = 0f
        var downY = 0f

        fun circle(on: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) ThemePresetMod.JADE else theme.popupBackgroundColor)
            setStroke(button.dp(1), if (on) ThemePresetMod.JADE else theme.dividerColor)
        }

        fun highlight(i: Int) {
            if (i == hovered) return
            hovered = i
            circles.forEachIndexed { k, v ->
                val on = k == i
                v.background = circle(on)
                v.setTextColor(if (on) 0xffffffff.toInt() else theme.popupTextColor)
                v.animate().scaleX(if (on) 1.12f else 1f).scaleY(if (on) 1.12f else 1f).setDuration(80).start()
            }
            if (i >= 0) InputFeedbacks.hapticFeedback(button)
        }

        fun show() {
            val ctx = button.context
            val size = ctx.dp(64)
            val gap = ctx.dp(24)
            val w = size * 2 + gap + ctx.dp(16)
            val h = size + ctx.dp(16)
            val box = FrameLayout(ctx)
            circles = labels().take(2).mapIndexed { i, label ->
                TextView(ctx).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
                    setTextColor(theme.popupTextColor)
                    background = circle(false)
                    elevation = ctx.dp(4f)
                }.also {
                    box.addView(it, FrameLayout.LayoutParams(size, size, Gravity.TOP or (if (i == 0) Gravity.START else Gravity.END)).apply {
                        setMargins(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
                    })
                }
            }
            hovered = -1
            popup = PopupWindow(box, w, h, false).apply {
                isTouchable = false
                isClippingEnabled = false
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                showAsDropDown(button, (button.width - w) / 2, -(button.height + h))
            }
        }

        /** 0 = toward the left circle, 1 = the right one, -1 = not far enough yet */
        fun direction(e: MotionEvent): Int {
            val dx = e.rawX - downX
            val dy = e.rawY - downY
            val min = button.dp(20)
            if (dx * dx + dy * dy < min * min) return -1
            return if (dx < 0) 0 else 1
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
                    downX = e.rawX
                    downY = e.rawY
                    v.isPressed = true
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (longPressed) highlight(direction(e))
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    v.isPressed = false
                    if (longPressed) {
                        val picked = direction(e)
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
