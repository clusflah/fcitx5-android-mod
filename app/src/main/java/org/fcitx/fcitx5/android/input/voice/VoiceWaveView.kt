/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: the voice panel's waveform in the card style: a row of rounded green bars, tallest in the
 * middle, whose height follows the microphone level.
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.sin

class VoiceWaveView(context: Context, color: Int, barDp: Float = 6f) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val rect = RectF()
    private val barWidth = dp(barDp)
    private val gap = dp(barDp)
    private val minHeight = dp(barDp)

    /** smoothed 0..1 */
    private var level = 0f
    private var phase = 0f

    /** @param value raw microphone level, about 0..0.3 while speaking */
    fun setLevel(value: Float) {
        level += ((value * 3f).coerceIn(0f, 1f) - level) * 0.4f
        phase += 0.6f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = BARS
        val total = n * barWidth + (n - 1) * gap
        var x = (width - total) / 2f
        val cy = height / 2f
        val maxH = height.toFloat()
        for (i in 0 until n) {
            // tallest in the middle, with a little ripple so it moves while the level holds
            val center = 1f - abs(i - (n - 1) / 2f) / ((n - 1) / 2f)
            val ripple = 0.75f + 0.25f * sin(phase + i * 0.9f)
            val h = (minHeight + (maxH - minHeight) * level * (0.25f + 0.75f * center) * ripple)
                .coerceAtLeast(minHeight)
            rect.set(x, cy - h / 2, x + barWidth, cy + h / 2)
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
            x += barWidth + gap
        }
    }

    companion object {
        private const val BARS = 13
    }
}
