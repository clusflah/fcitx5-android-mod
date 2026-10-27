/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: the landscape floating keyboard (设置 → 虚拟键盘 → 横屏悬浮键盘, or the toolbar button).
 * InputView keeps its layout and only places / sizes its keyboardView with this geometry;
 * the bar at the bottom of the keyboard moves it (middle) and resizes it (the two corners).
 */
package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import splitties.dimensions.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FloatingKeyboard(
    private val context: Context,
    private val theme: Theme,
    /** called when the geometry changed and the keyboard has to be laid out again */
    private val onGeometryChange: () -> Unit
) {
    private val prefs = AppPrefs.getInstance().keyboard

    val enabled: Boolean
        get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                prefs.floatingKeyboardLandscape.getValue()

    /** left edge of the keyboard, px */
    var keyboardX = 0
        private set

    /** gap between the keyboard and the bottom of the window, px */
    var keyboardBottom = 0
        private set

    var keyboardWidth = 0
        private set

    /** height of the keys area (the keyboard minus toolbar and this bar), px */
    var keysHeight = 0
        private set

    val handleHeight = context.dp(24)

    private val minWidth = context.dp(240)
    private val minKeysHeight = context.dp(110)
    private val otherHeight = context.dp(KawaiiBarComponent.HEIGHT) + handleHeight

    private var parentWidth = 0
    private var parentHeight = 0
    private var loaded = false

    /** loads the saved geometry (or the default: one-hand size, lower right) and keeps it inside the window */
    fun fit(parentWidth: Int, parentHeight: Int) {
        val metrics = context.resources.displayMetrics
        this.parentWidth = if (parentWidth > 0) parentWidth else metrics.widthPixels
        this.parentHeight = if (parentHeight > 0) parentHeight else metrics.heightPixels
        if (loaded) {
            clamp()
            return
        }
        loaded = true
        keyboardWidth = prefs.floatingKeyboardWidth.getValue().takeIf { it > 0 }
            ?: min(context.dp(400), this.parentWidth / 2)
        keysHeight = prefs.floatingKeyboardHeight.getValue().takeIf { it > 0 }
            ?: (keyboardWidth * 0.58f).roundToInt()
        keyboardX = prefs.floatingKeyboardX.getValue().takeIf { it >= 0 }
            ?: (this.parentWidth - keyboardWidth - context.dp(24))
        keyboardBottom = prefs.floatingKeyboardBottom.getValue().takeIf { it >= 0 } ?: context.dp(16)
        clamp()
    }

    private fun clamp() {
        keyboardWidth = keyboardWidth.coerceIn(min(minWidth, parentWidth), parentWidth)
        val maxKeysHeight = max(minKeysHeight, (parentHeight * 0.8f).roundToInt() - otherHeight)
        keysHeight = keysHeight.coerceIn(minKeysHeight, maxKeysHeight)
        keyboardX = keyboardX.coerceIn(0, max(0, parentWidth - keyboardWidth))
        keyboardBottom = keyboardBottom.coerceIn(0, max(0, parentHeight - keysHeight - otherHeight))
    }

    private fun save() {
        prefs.floatingKeyboardX.setValue(keyboardX)
        prefs.floatingKeyboardBottom.setValue(keyboardBottom)
        prefs.floatingKeyboardWidth.setValue(keyboardWidth)
        prefs.floatingKeyboardHeight.setValue(keysHeight)
    }

    /** rounded corners for the floating keyboard */
    val outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, context.dp(16f))
        }
    }

    val elevation = context.dp(8f)

    private enum class Drag { Move, ResizeLeft, ResizeRight }

    /** the bar at the bottom: a pill in the middle to move, resize grips in both corners */
    @SuppressLint("ViewConstructor")
    private inner class Handle : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.keyTextColor
            alpha = 0x66
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(2f)
        }
        private val gripWidth = dp(44)

        private var drag = Drag.Move
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startBottom = 0
        private var startWidth = 0
        private var startKeysHeight = 0

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            paint.strokeWidth = dp(4f)
            canvas.drawLine(cx - dp(18), cy, cx + dp(18), cy, paint)
            paint.strokeWidth = dp(2f)
            // "◢" grips, kept clear of the rounded corners
            val inset = dp(14f)
            val b = height - dp(6f)
            val r = width - inset
            canvas.drawLine(r - dp(10), b, r, b - dp(10), paint)
            canvas.drawLine(r - dp(5), b, r, b - dp(5), paint)
            canvas.drawLine(inset + dp(10), b, inset, b - dp(10), paint)
            canvas.drawLine(inset + dp(5), b, inset, b - dp(5), paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag = when {
                        event.x < gripWidth -> Drag.ResizeLeft
                        event.x > width - gripWidth -> Drag.ResizeRight
                        else -> Drag.Move
                    }
                    downX = event.rawX
                    downY = event.rawY
                    startX = keyboardX
                    startBottom = keyboardBottom
                    startWidth = keyboardWidth
                    startKeysHeight = keysHeight
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    when (drag) {
                        Drag.Move -> {
                            keyboardX = startX + dx
                            keyboardBottom = startBottom - dy
                        }
                        // the top edge stays put: dragging a corner down makes the keys taller
                        Drag.ResizeRight -> {
                            keyboardWidth = startWidth + dx
                            keysHeight = startKeysHeight + dy
                            keyboardBottom = startBottom - dy
                        }
                        Drag.ResizeLeft -> {
                            val w = (startWidth - dx).coerceIn(min(minWidth, parentWidth), parentWidth)
                            keyboardX = startX + startWidth - w
                            keyboardWidth = w
                            keysHeight = startKeysHeight + dy
                            keyboardBottom = startBottom - dy
                        }
                    }
                    if (drag != Drag.Move) {
                        // a resize that hits a limit must not move the top edge
                        val top = startBottom + startKeysHeight
                        clamp()
                        keyboardBottom = max(0, top - keysHeight)
                    }
                    clamp()
                    onGeometryChange()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> save()
            }
            return true
        }
    }

    val handle: View = Handle()

    companion object {
        /**
         * Where the floating keyboard sits across the screen, (left, width) in px, or null when it isn't
         * floating. Used by windows the keyboard opens (the AI assistant) to take the same size.
         */
        fun horizontalBounds(context: Context): Pair<Int, Int>? {
            val prefs = AppPrefs.getInstance().keyboard
            if (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE ||
                !prefs.floatingKeyboardLandscape.getValue()
            ) return null
            val screen = context.resources.displayMetrics.widthPixels
            val width = (prefs.floatingKeyboardWidth.getValue().takeIf { it > 0 }
                ?: min(context.dp(400), screen / 2)).coerceAtMost(screen)
            val x = (prefs.floatingKeyboardX.getValue().takeIf { it >= 0 }
                ?: (screen - width - context.dp(24))).coerceIn(0, max(0, screen - width))
            return x to width
        }
    }
}
