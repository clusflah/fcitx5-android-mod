/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: iOS 26 style multi-orb luminous aurora backdrop drawable for Liquid Glass.
 */
package org.fcitx.fcitx5.android.input.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class DynamicBackdropDrawable(
    private val theme: Theme,
    private val seeThrough: Boolean = false
) : Drawable() {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaints = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }

    var phase: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                rebuildOrbs(bounds)
                invalidateSelf()
            }
        }

    private val isGlassPreset = ThemePresetMod.isGlassPreset(theme)

    @ColorInt
    private val baseColors: IntArray = if (theme.isDark) {
        if (isGlassPreset) {
            intArrayOf(0xff0e1320.toInt(), 0xff151d30.toInt(), 0xff101726.toInt())
        } else {
            val b = theme.backgroundColor or (0xff shl 24)
            val k = theme.keyboardColor or (0xff shl 24)
            intArrayOf(
                ColorUtils.blendARGB(b, 0xff0d1322.toInt(), 0.35f),
                ColorUtils.blendARGB(k, theme.accentKeyBackgroundColor or (0xff shl 24), 0.14f),
                b
            )
        }
    } else {
        if (isGlassPreset) {
            intArrayOf(0xffe4eefb.toInt(), 0xffd8e5f7.toInt(), 0xffe6e2f8.toInt())
        } else {
            val b = theme.backgroundColor or (0xff shl 24)
            val k = theme.keyboardColor or (0xff shl 24)
            intArrayOf(
                ColorUtils.blendARGB(b, 0xffe4eefb.toInt(), 0.4f),
                ColorUtils.blendARGB(k, theme.accentKeyBackgroundColor or (0xff shl 24), 0.12f),
                b
            )
        }
    }

    @ColorInt
    private val orbCenterColors: IntArray = if (theme.isDark) {
        val accent = (theme.accentKeyBackgroundColor and 0x00ffffff) or (0x6e shl 24)
        val active = (theme.genericActiveBackgroundColor and 0x00ffffff) or (0x66 shl 24)
        intArrayOf(
            if (isGlassPreset) 0x751e88e5.toInt() else accent,
            if (isGlassPreset) 0x702fbf8f.toInt() else active,
            0x666e48aa.toInt(),
            0x6015998e.toInt()
        )
    } else {
        val accent = (theme.accentKeyBackgroundColor and 0x00ffffff) or (0x78 shl 24)
        val active = (theme.genericActiveBackgroundColor and 0x00ffffff) or (0x68 shl 24)
        intArrayOf(
            if (isGlassPreset) 0x9478d5ff.toInt() else accent,
            if (isGlassPreset) 0x8242dfb0.toInt() else active,
            0x7aa99bff.toInt(),
            0x72ffc2e2.toInt()
        )
    }

    private val orbAnchors = arrayOf(
        floatArrayOf(0.18f, 0.22f),
        floatArrayOf(0.82f, 0.76f),
        floatArrayOf(0.78f, 0.20f),
        floatArrayOf(0.24f, 0.82f)
    )

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        basePaint.shader = LinearGradient(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            baseColors,
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        rebuildOrbs(bounds)
    }

    private fun rebuildOrbs(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val maxDim = max(w, h)
        val radius = maxDim * 0.62f
        for (i in 0 until 4) {
            val anchor = orbAnchors[i]
            val angle = phase + i * 1.57f
            val dx = if (phase == 0f) 0f else cos(angle) * 0.11f
            val dy = if (phase == 0f) 0f else sin(angle * 1.3f) * 0.10f
            val cx = bounds.left + (anchor[0] + dx) * w
            val cy = bounds.top + (anchor[1] + dy) * h
            val c0 = orbCenterColors[i]
            val c1 = c0 and 0x00ffffff
            orbPaints[i].shader = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(c0, ColorUtils.setAlphaComponent(c0, Color.alpha(c0) / 3), c1),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (seeThrough) {
            // In cross-window see-through mode, draw a light translucent veil so system blur shines through
            val veilAlpha = if (theme.isDark) 0x52 else 0x44
            basePaint.alpha = veilAlpha
            canvas.drawRect(b, basePaint)
            for (p in orbPaints) {
                p.alpha = 0x48
                canvas.drawRect(b, p)
            }
            return
        }
        basePaint.alpha = 0xff
        canvas.drawRect(b, basePaint)
        for (p in orbPaints) {
            p.alpha = 0xff
            canvas.drawRect(b, p)
        }
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        orbPaints.forEach { it.colorFilter = colorFilter }
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (seeThrough) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}
