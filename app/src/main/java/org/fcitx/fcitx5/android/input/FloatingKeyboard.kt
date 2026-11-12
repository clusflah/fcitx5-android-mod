/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: floating keyboard in both portrait and landscape (设置 → 虚拟键盘 → 竖屏/横屏悬浮键盘,
 * toolbar button, or 更多 panel). InputView keeps its layout and only places / sizes its
 * keyboardView with this geometry; the bar at the bottom moves it (middle) and resizes it (corners).
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

    private val isLandscape: Boolean
        get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val enabled: Boolean
        get() = isEnabled(context)

    /** 0.20..1.00 user-configured floating opacity */
    val opacity: Float
        get() = prefs.floatingKeyboardOpacity.getValue().coerceIn(20, 100) / 100f

    val isTranslucent: Boolean
        get() = enabled && opacity < 0.99f

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

    private val minWidth = context.dp(210)
    private val minKeysHeight = context.dp(84)
    val otherHeight = context.dp(KawaiiBarComponent.HEIGHT) + handleHeight
    private val topSafeMargin = context.dp(36)

    val totalHeight: Int
        get() = keysHeight + otherHeight

    /**
     * Uniform UI scale factor (0.42..1.00) so toolbar buttons, key letters, icons, and margins
     * shrink proportionally when the floating keyboard is smaller than a normal phone keyboard.
     */
    val uiScale: Float
        get() {
            if (!enabled || keyboardWidth <= 0 || keysHeight <= 0) return 1f
            val metrics = context.resources.displayMetrics
            val shortSide = min(metrics.widthPixels, metrics.heightPixels)
            val refWidth = shortSide.coerceIn(context.dp(340), context.dp(400)).toFloat()
            val refHeight = context.dp(256).toFloat()
            return min(1f, min(keyboardWidth / refWidth, totalHeight / refHeight)).coerceAtLeast(0.42f)
        }

    /** Internal unscaled keys height so (otherHeight + unscaledKeysHeight) * uiScale == totalHeight */
    val unscaledKeysHeight: Int
        get() {
            val s = uiScale
            val innerTotal = (totalHeight / s).roundToInt()
            return max(context.dp(140), innerTotal - otherHeight)
        }

    private var parentWidth = 0
    private var parentHeight = 0
    private var loadedOrientation = Configuration.ORIENTATION_UNDEFINED

    private fun xPref(landscape: Boolean = isLandscape) =
        if (landscape) prefs.floatingKeyboardX else prefs.floatingKeyboardPortraitX

    private fun bottomPref(landscape: Boolean = isLandscape) =
        if (landscape) prefs.floatingKeyboardBottom else prefs.floatingKeyboardPortraitBottom

    private fun widthPref(landscape: Boolean = isLandscape) =
        if (landscape) prefs.floatingKeyboardWidth else prefs.floatingKeyboardPortraitWidth

    private fun heightPref(landscape: Boolean = isLandscape) =
        if (landscape) prefs.floatingKeyboardHeight else prefs.floatingKeyboardPortraitHeight

    fun reload() {
        loadedOrientation = Configuration.ORIENTATION_UNDEFINED
    }

    /** loads the saved geometry (or orientation-appropriate defaults) and keeps it inside the window */
    fun fit(parentWidth: Int, parentHeight: Int) {
        val metrics = context.resources.displayMetrics
        this.parentWidth = if (parentWidth > 0) parentWidth else metrics.widthPixels
        this.parentHeight = if (parentHeight > 0) parentHeight else metrics.heightPixels
        val orientation = context.resources.configuration.orientation
        if (loadedOrientation == orientation) {
            clamp()
            return
        }
        loadedOrientation = orientation
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            keyboardWidth = widthPref(true).getValue().takeIf { it > 0 }
                ?: min(context.dp(400), this.parentWidth / 2)
            keysHeight = heightPref(true).getValue().takeIf { it > 0 }
                ?: (keyboardWidth * 0.58f).roundToInt()
            keyboardX = xPref(true).getValue().takeIf { it >= 0 }
                ?: (this.parentWidth - keyboardWidth - context.dp(24))
            keyboardBottom = bottomPref(true).getValue().takeIf { it >= 0 } ?: context.dp(16)
        } else {
            keyboardWidth = widthPref(false).getValue().takeIf { it > 0 }
                ?: defaultPortraitWidth(context, this.parentWidth)
            keysHeight = heightPref(false).getValue().takeIf { it > 0 }
                ?: (keyboardWidth * 0.65f).roundToInt()
            keyboardX = xPref(false).getValue().takeIf { it >= 0 }
                ?: defaultPortraitX(context, this.parentWidth, keyboardWidth)
            keyboardBottom = bottomPref(false).getValue().takeIf { it >= 0 } ?: context.dp(48)
        }
        clamp()
    }

    private fun clamp() {
        keyboardWidth = keyboardWidth.coerceIn(min(minWidth, parentWidth), parentWidth)
        val maxKeysHeight = max(
            minKeysHeight,
            min((parentHeight * 0.8f).roundToInt(), parentHeight - topSafeMargin) - otherHeight
        )
        keysHeight = keysHeight.coerceIn(minKeysHeight, maxKeysHeight)
        keyboardX = keyboardX.coerceIn(0, max(0, parentWidth - keyboardWidth))
        keyboardBottom = keyboardBottom.coerceIn(
            0,
            max(0, parentHeight - keysHeight - otherHeight - topSafeMargin)
        )
    }

    private fun save() {
        xPref().setValue(keyboardX)
        bottomPref().setValue(keyboardBottom)
        widthPref().setValue(keyboardWidth)
        heightPref().setValue(keysHeight)
    }

    /** rounded corners for the floating keyboard */
    val outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, context.dp(16f))
            // Suppress hardware RenderNode interior shadow fill when floating translucency is active
            outline.alpha = when {
                isTranslucent -> 0f
                org.fcitx.fcitx5.android.input.glass.Glass.isActive(theme) -> 0.35f
                else -> 1f
            }
        }
    }

    val elevation: Float
        get() = if (org.fcitx.fcitx5.android.input.glass.Glass.style(theme).seeThrough || isTranslucent) 0f else context.dp(8f)

    private enum class Drag { Move, ResizeLeft, ResizeRight }

    /** the bar at the bottom: a pill in the middle to move, resize grips in both corners */
    @SuppressLint("ViewConstructor")
    private inner class Handle : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.keyTextColor
            alpha = 0x80
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
        private fun defaultPortraitWidth(context: Context, screenW: Int): Int =
            min(context.dp(340), (screenW * 0.86f).roundToInt()).coerceAtLeast(min(context.dp(220), screenW))

        private fun defaultPortraitX(context: Context, screenW: Int, kbWidth: Int): Int =
            if (screenW >= context.dp(520)) {
                screenW - kbWidth - context.dp(24)
            } else {
                (screenW - kbWidth) / 2
            }

        fun isEnabled(context: Context): Boolean {
            val prefs = AppPrefs.getInstance().keyboard
            return if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                prefs.floatingKeyboardLandscape.getValue()
            } else {
                prefs.floatingKeyboardPortrait.getValue()
            }
        }

        fun toggle(context: Context): Boolean {
            val prefs = AppPrefs.getInstance().keyboard
            val pref = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                prefs.floatingKeyboardLandscape
            } else {
                prefs.floatingKeyboardPortrait
            }
            val next = !pref.getValue()
            pref.setValue(next)
            return next
        }

        fun resetGeometry(context: Context) {
            val prefs = AppPrefs.getInstance().keyboard
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                prefs.floatingKeyboardX.setValue(-1)
                prefs.floatingKeyboardBottom.setValue(-1)
                prefs.floatingKeyboardHeight.setValue(-1)
                prefs.floatingKeyboardWidth.setValue(-1)
            } else {
                prefs.floatingKeyboardPortraitX.setValue(-1)
                prefs.floatingKeyboardPortraitBottom.setValue(-1)
                prefs.floatingKeyboardPortraitHeight.setValue(-1)
                prefs.floatingKeyboardPortraitWidth.setValue(-1)
            }
        }

        /**
         * Where the floating keyboard sits across the screen, (left, width) in px, when there is room
         * to place an auxiliary card (the AI assistant) side-by-side horizontally; null otherwise.
         */
        fun horizontalBounds(context: Context): Pair<Int, Int>? {
            if (!isEnabled(context)) return null
            val prefs = AppPrefs.getInstance().keyboard
            val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val screen = context.resources.displayMetrics.widthPixels
            if (isLandscape) {
                val width = (prefs.floatingKeyboardWidth.getValue().takeIf { it > 0 }
                    ?: min(context.dp(400), screen / 2)).coerceAtMost(screen)
                val x = (prefs.floatingKeyboardX.getValue().takeIf { it >= 0 }
                    ?: (screen - width - context.dp(24))).coerceIn(0, max(0, screen - width))
                return x to width
            }
            val width = (prefs.floatingKeyboardPortraitWidth.getValue().takeIf { it > 0 }
                ?: defaultPortraitWidth(context, screen)).coerceAtMost(screen)
            // On wide portrait displays (tablets / unfolded foldables), allow side-by-side placement too
            if (screen < width * 2 + context.dp(16)) return null
            val x = (prefs.floatingKeyboardPortraitX.getValue().takeIf { it >= 0 }
                ?: defaultPortraitX(context, screen, width)).coerceIn(0, max(0, screen - width))
            return x to width
        }

        /**
         * Vertical screen bounds (topY, bottomY) in px of the portrait floating keyboard when side-by-side
         * horizontal placement is not available, so floating dialogs (e.g. AI assistant) can sit above or
         * below the floating keyboard instead of being covered by it.
         */
        fun portraitVerticalBounds(context: Context): Pair<Int, Int>? {
            if (!isEnabled(context)) return null
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return null
            val prefs = AppPrefs.getInstance().keyboard
            val dm = context.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val minW = min(context.dp(220), screenW)
            val width = (prefs.floatingKeyboardPortraitWidth.getValue().takeIf { it > 0 }
                ?: defaultPortraitWidth(context, screenW)).coerceIn(minW, screenW)
            val otherH = context.dp(KawaiiBarComponent.HEIGHT) + context.dp(24)
            val topSafe = context.dp(36)
            val minKeysH = context.dp(110)
            val maxKeysH = max(minKeysH, min((screenH * 0.8f).roundToInt(), screenH - topSafe) - otherH)
            val keysH = (prefs.floatingKeyboardPortraitHeight.getValue().takeIf { it > 0 }
                ?: (width * 0.65f).roundToInt()).coerceIn(minKeysH, maxKeysH)
            val totalH = keysH + otherH
            val bottomGap = (prefs.floatingKeyboardPortraitBottom.getValue().takeIf { it >= 0 }
                ?: context.dp(48)).coerceIn(0, max(0, screenH - totalH - topSafe))
            val bottomY = screenH - bottomGap
            val topY = (bottomY - totalH).coerceAtLeast(0)
            return topY to bottomY
        }
    }
}
