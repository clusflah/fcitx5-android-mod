/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

open class CustomGestureView(ctx: Context) : FrameLayout(ctx) {

    enum class SwipeAxis { X, Y }

    enum class GestureType { Down, Move, Up }

    data class Event(
        val type: GestureType,
        val consumed: Boolean,
        val x: Float,
        val y: Float,
        val countX: Int,
        val countY: Int,
        val totalX: Int,
        val totalY: Int
    )

    fun interface OnGestureListener {
        fun onGesture(view: View, event: Event): Boolean

        companion object {
            val Empty = OnGestureListener { _, _ -> false }
        }
    }

    private val lifecycleScope by lazy {
        findViewTreeLifecycleOwner()?.lifecycleScope!!
    }

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var swipeDownX = 0f
    private var swipeDownY = 0f

    @Volatile
    private var touchMovedOutside = false

    @Volatile
    private var longPressTriggered = false
    var longPressEnabled = false
    private var longPressJob: Job? = null

    @Volatile
    var longPressFeedbackEnabled = true

    @Volatile
    private var repeatStarted = false
    var repeatEnabled = false
    private var repeatJob: Job? = null

    var swipeEnabled = false
    var swipeRepeatEnabled = false
    var swipeThresholdX = 24f
    var swipeThresholdY = 24f

    private var swipeRepeatTriggered = false
    private var swipeLastX = -1f
    private var swipeLastY = -1f
    private var swipeXUnconsumed = 0f
    private var swipeYUnconsumed = 0f
    private var swipeTotalX = 0
    private var swipeTotalY = 0
    private var gestureConsumed = false

    var doubleTapEnabled = false
    private var lastClickTime = 0L
    private var maybeDoubleTap = false

    var onDoubleTapListener: ((View) -> Unit)? = null
    var onRepeatListener: ((View) -> Unit)? = null
    var onGestureListener: OnGestureListener? = null

    var soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard

    private val touchSlop: Float = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            isPressed = false
        }
    }

    private fun pointInView(x: Float, y: Float): Boolean {
        return -touchSlop <= x &&
                -touchSlop <= y &&
                x < (width + touchSlop) &&
                y < (height + touchSlop)
    }

    private fun resetState() {
        touchMovedOutside = false
        if (longPressEnabled) {
            longPressTriggered = false
            longPressJob?.cancel()
            longPressJob = null
        }
        if (repeatEnabled) {
            repeatStarted = false
            repeatJob?.cancel()
            repeatJob = null
        }
        if (swipeEnabled) {
            if (swipeRepeatEnabled) {
                swipeRepeatTriggered = false
            }
            swipeXUnconsumed = 0f
            swipeYUnconsumed = 0f
            swipeTotalX = 0
            swipeTotalY = 0
            gestureConsumed = false
        }
        // double tap state should be preserved on touch up
    }

    fun cancelGestures() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        isPressed = false
        resetState()
        // reset double tap state on cancel
        if (doubleTapEnabled) {
            maybeDoubleTap = false
            lastClickTime = 0
        }
    }

    private fun startTouchDown(pointerId: Int, x: Float, y: Float) {
        activePointerId = pointerId
        swipeDownX = x
        swipeDownY = y
        drawableHotspotChanged(x, y)
        isPressed = true
        InputFeedbacks.hapticFeedback(this)
        InputFeedbacks.soundEffect(soundEffect)
        dispatchGestureEvent(GestureType.Down, x, y)
        if (longPressEnabled) {
            longPressJob?.cancel()
            longPressJob = lifecycleScope.launch {
                delay(longPressDelay.toLong())
                if (longPressFeedbackEnabled) {
                    InputFeedbacks.hapticFeedback(this@CustomGestureView, true)
                }
                longPressTriggered = performLongClick()
            }
        }
        if (repeatEnabled) {
            repeatJob?.cancel()
            repeatJob = lifecycleScope.launch {
                delay(longPressDelay.toLong())
                repeatStarted = true
                var lastTriggerTime: Long
                while (isActive && isEnabled) {
                    lastTriggerTime = SystemClock.uptimeMillis()
                    onRepeatListener?.invoke(this@CustomGestureView)
                    val t = lastTriggerTime + RepeatInterval - SystemClock.uptimeMillis()
                    if (t > 0) delay(t)
                }
            }
        }
        if (swipeEnabled) {
            swipeLastX = x
            swipeLastY = y
        }
    }

    private fun finishTouchUp(x: Float, y: Float) {
        isPressed = false
        if (!touchMovedOutside && !pointInView(x, y)) {
            touchMovedOutside = true
        }
        // Settle the final touch-up delta before dispatching GestureType.Up so fast flicks
        // whose displacement lands in ACTION_UP are recognized as swipes rather than taps.
        if (swipeEnabled && !longPressTriggered && !repeatStarted) {
            val countX = consumeSwipe(x, SwipeAxis.X)
            val countY = consumeSwipe(y, SwipeAxis.Y)
            if (countX != 0 || countY != 0) {
                dispatchGestureEvent(GestureType.Move, x, y, countX, countY)
            }
            swipeLastX = x
            swipeLastY = y
        }
        InputFeedbacks.hapticFeedback(this, longPress = true, keyUp = true)
        dispatchGestureEvent(GestureType.Up, x, y)
        val shouldPerformClick = !(touchMovedOutside ||
                longPressTriggered ||
                repeatStarted ||
                swipeRepeatTriggered ||
                gestureConsumed)
        resetState()
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (shouldPerformClick) {
            if (doubleTapEnabled) {
                val now = System.currentTimeMillis()
                if (maybeDoubleTap && now - lastClickTime <= longPressDelay) {
                    maybeDoubleTap = false
                    onDoubleTapListener?.invoke(this)
                } else {
                    maybeDoubleTap = true
                    performClick()
                }
                lastClickTime = now
            } else {
                performClick()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                startTouchDown(event.getPointerId(0), x, y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isEnabled) return false
                val newIndex = event.actionIndex
                val newPointerId = event.getPointerId(newIndex)
                val oldIndex = event.findPointerIndex(activePointerId)
                if (oldIndex >= 0) {
                    finishTouchUp(event.getX(oldIndex), event.getY(oldIndex))
                } else {
                    cancelGestures()
                }
                startTouchDown(newPointerId, event.getX(newIndex), event.getY(newIndex))
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                if (event.getPointerId(upIndex) == activePointerId) {
                    finishTouchUp(event.getX(upIndex), event.getY(upIndex))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val upIndex = event.findPointerIndex(activePointerId)
                    val ux = if (upIndex >= 0) event.getX(upIndex) else x
                    val uy = if (upIndex >= 0) event.getY(upIndex) else y
                    finishTouchUp(ux, uy)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return true
                val mx = event.getX(pointerIndex)
                val my = event.getY(pointerIndex)
                drawableHotspotChanged(mx, my)
                if (!touchMovedOutside && !pointInView(mx, my)) {
                    touchMovedOutside = true
                    if (longPressEnabled) {
                        longPressJob?.cancel()
                        longPressJob = null
                    }
                    if (repeatEnabled) {
                        repeatJob?.cancel()
                        repeatJob = null
                    }
                    if (repeatStarted || !swipeEnabled) {
                        isPressed = false
                    }
                }
                if (swipeEnabled && longPressEnabled && !longPressTriggered &&
                    swipeThresholdY < swipeThresholdX && (my - swipeDownY) > touchSlop * 1.5f
                ) {
                    longPressJob?.cancel()
                    longPressJob = null
                }
                if (!swipeEnabled || longPressTriggered || repeatStarted) return true
                val countX = consumeSwipe(mx, SwipeAxis.X)
                val countY = consumeSwipe(my, SwipeAxis.Y)
                dispatchGestureEvent(GestureType.Move, mx, my, countX, countY)
                swipeLastX = mx
                swipeLastY = my
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dispatchGestureEvent(GestureType.Up, event.x, event.y)
                cancelGestures()
                return true
            }
        }
        return true
    }

    private fun dispatchGestureEvent(
        type: GestureType,
        x: Float,
        y: Float,
        countX: Int = 0,
        countY: Int = 0
    ) {
        val event = Event(type, gestureConsumed, x, y, countX, countY, swipeTotalX, swipeTotalY)
        val consumed = onGestureListener?.onGesture(this, event) ?: return
        if (consumed && !gestureConsumed) {
            gestureConsumed = true
        }
    }

    private fun consumeSwipe(current: Float, axis: SwipeAxis): Int {
        val unconsumed: Float
        val threshold: Float
        when (axis) {
            SwipeAxis.X -> {
                unconsumed = current - swipeLastX + swipeXUnconsumed
                threshold = swipeThresholdX
            }
            SwipeAxis.Y -> {
                unconsumed = current - swipeLastY + swipeYUnconsumed
                threshold = swipeThresholdY
            }
        }
        val remains: Float = unconsumed % threshold
        val count: Int = (unconsumed / threshold).toInt()
        if (count != 0) {
            if (swipeRepeatEnabled && !swipeRepeatTriggered) {
                swipeRepeatTriggered = true
            }
            if (longPressEnabled && !longPressTriggered) {
                longPressJob?.cancel()
                longPressJob = null
            }
            if (repeatEnabled && !repeatStarted) {
                repeatJob?.cancel()
                repeatJob = null
            }
        }
        when (axis) {
            SwipeAxis.X -> {
                swipeXUnconsumed = remains
                swipeTotalX += count
            }
            SwipeAxis.Y -> {
                swipeYUnconsumed = remains
                swipeTotalY += count
            }
        }
        return count
    }

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        longPressEnabled = l != null
        super.setOnLongClickListener(l)
    }

    companion object {
        val longPressDelay by AppPrefs.getInstance().keyboard.longPressDelay
        const val RepeatInterval = 50L
    }
}
