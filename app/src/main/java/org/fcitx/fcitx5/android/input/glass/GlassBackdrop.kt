/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: shared downscaled blurred backdrop bitmap and O(1) adaptive contrast luminance grid.
 */
package org.fcitx.fcitx5.android.input.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class GlassBackdrop(
    val theme: Theme,
    val style: GlassStyle,
    backdropView: View
) {
    private val hostViewRef = WeakReference(backdropView)

    val hostView: View?
        get() = hostViewRef.get()

    var sourceWidth: Int = 0
        private set

    var sourceHeight: Int = 0
        private set

    var downscaleX: Float = DOWNSCALE
        private set

    var downscaleY: Float = DOWNSCALE
        private set

    var blurredBitmap: Bitmap? = null
        private set

    var bitmapShader: BitmapShader? = null
        private set

    var version: Int = 0
        private set

    private val luminanceGrid = FloatArray(GRID_W * GRID_H) { if (theme.isDark) 0.12f else 0.82f }

    fun update(source: Drawable?, width: Int, height: Int) {
        if (source == null || width <= 0 || height <= 0) return
        sourceWidth = width
        sourceHeight = height

        val targetW = (width * DOWNSCALE).roundToInt().coerceIn(48, MAX_BITMAP_DIM)
        val targetH = (height * DOWNSCALE).roundToInt().coerceIn(36, MAX_BITMAP_DIM)
        downscaleX = targetW.toFloat() / width.toFloat()
        downscaleY = targetH.toFloat() / height.toFloat()

        var bmp = blurredBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != targetW || bmp.height != targetH) {
            bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            blurredBitmap = bmp
        } else {
            bmp.eraseColor(Color.TRANSPARENT)
        }

        val canvas = Canvas(bmp)
        val view = hostView
        if (view is ImageView && source.intrinsicWidth > 0 && source.intrinsicHeight > 0 &&
            view.scaleType == ImageView.ScaleType.CENTER_CROP
        ) {
            // Match ImageView.ScaleType.CENTER_CROP when drawing custom photo backgrounds
            val dWidth = source.intrinsicWidth.toFloat()
            val dHeight = source.intrinsicHeight.toFloat()
            val scale = max(targetW / dWidth, targetH / dHeight)
            val dx = (targetW - dWidth * scale) * 0.5f
            val dy = (targetH - dHeight * scale) * 0.5f
            canvas.save()
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            source.setBounds(0, 0, source.intrinsicWidth, source.intrinsicHeight)
            source.draw(canvas)
            canvas.restore()
        } else {
            val oldBounds = source.copyBounds()
            source.setBounds(0, 0, targetW, targetH)
            source.draw(canvas)
            source.bounds = oldBounds
        }

        val blurRadiusPx = if (view != null) {
            (view.dp(style.blurRadiusDp) * ((downscaleX + downscaleY) * 0.5f)).roundToInt().coerceIn(1, 28)
        } else {
            (style.blurRadiusDp * 0.65f).roundToInt().coerceIn(1, 28)
        }
        if (style.blurRadiusDp > 0) {
            StackBlur.blur(bmp, blurRadiusPx)
        }

        computeLuminanceGrid(bmp)
        bitmapShader = BitmapShader(bmp, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        version++
    }

    private fun computeLuminanceGrid(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return
        for (gy in 0 until GRID_H) {
            val py = ((gy + 0.5f) * h / GRID_H).toInt().coerceIn(0, h - 1)
            for (gx in 0 until GRID_W) {
                val px = ((gx + 0.5f) * w / GRID_W).toInt().coerceIn(0, w - 1)
                val c = bmp.getPixel(px, py)
                luminanceGrid[gy * GRID_W + gx] = fastLuminance(c)
            }
        }
    }

    fun sampleLuminance(xPx: Float, yPx: Float): Float {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return if (theme.isDark) 0.12f else 0.82f
        }
        val mx = kotlin.math.abs(xPx)
        val my = kotlin.math.abs(yPx)
        val gx = ((mx / sourceWidth) * GRID_W).toInt().coerceIn(0, GRID_W - 1)
        val gy = ((my / sourceHeight) * GRID_H).toInt().coerceIn(0, GRID_H - 1)
        return luminanceGrid[gy * GRID_W + gx]
    }

    /**
     * Adjusts [baseTint] alpha (and luminance if needed) so [textColor] maintains >= 4.5:1 WCAG contrast
     * against the refracted backdrop at ([centerX], [centerY]).
     */
    @ColorInt
    fun resolveAdaptiveTint(
        @ColorInt baseTint: Int,
        @ColorInt textColor: Int,
        centerX: Float,
        centerY: Float,
        extraOpacityBoost: Float = 0f
    ): Int {
        val rawAlpha = Color.alpha(baseTint) / 255f
        // Combine theme's alpha with user's tintOpacity preference
        val baseOpacity = if (rawAlpha < 0.98f) {
            (rawAlpha * (0.55f + style.tintOpacity * 0.9f)).coerceIn(0.08f, 0.95f)
        } else {
            style.tintOpacity.coerceIn(0.08f, 0.95f)
        }
        var targetOpacity = (baseOpacity + extraOpacityBoost).coerceIn(0.08f, 0.96f)
        if (style.highContrast) {
            targetOpacity = targetOpacity.coerceAtLeast(0.75f)
        }

        val bgLum = sampleLuminance(centerX, centerY)
        val tintLum = fastLuminance(baseTint)
        val textLum = fastLuminance(textColor)

        val compositeLum = bgLum * (1f - targetOpacity) + tintLum * targetOpacity
        val contrast = contrastRatio(compositeLum, textLum)

        var rgb = baseTint and 0x00ffffff
        if (contrast < 4.5f) {
            // Boost tint opacity first
            targetOpacity = max(targetOpacity, min(0.86f, targetOpacity + (4.5f - contrast) * 0.11f))
            val newCompositeLum = bgLum * (1f - targetOpacity) + tintLum * targetOpacity
            if (contrastRatio(newCompositeLum, textLum) < 4.2f) {
                // Gently shift tint RGB away from text luminance to guarantee readability
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(rgb or (0xff shl 24), hsl)
                if (hsl[1] > 0.35f) {
                    hsl[2] = if (textLum > 0.5f) (hsl[2] * 0.78f).coerceAtLeast(0.22f)
                    else (hsl[2] + (1f - hsl[2]) * 0.35f).coerceAtMost(0.88f)
                    rgb = ColorUtils.HSLToColor(hsl) and 0x00ffffff
                } else {
                    val pole = if (textLum < 0.45f) 0xffffffff.toInt() else 0xff10141e.toInt()
                    rgb = ColorUtils.blendARGB(rgb or (0xff shl 24), pole, 0.25f) and 0x00ffffff
                }
            }
        }

        val alphaByte = (targetOpacity * 255f).roundToInt().coerceIn(0, 255)
        return (alphaByte shl 24) or rgb
    }

    companion object {
        const val DOWNSCALE = 0.25f
        private const val MAX_BITMAP_DIM = 480
        private const val GRID_W = 16
        private const val GRID_H = 8

        fun fastLuminance(@ColorInt color: Int): Float {
            val r = ((color shr 16) and 0xff) / 255f
            val g = ((color shr 8) and 0xff) / 255f
            val b = (color and 0xff) / 255f
            return 0.2126f * r * r + 0.7152f * g * g + 0.0722f * b * b
        }

        fun contrastRatio(l1: Float, l2: Float): Float {
            val lighter = max(l1, l2)
            val darker = min(l1, l2)
            return (lighter + 0.05f) / (darker + 0.05f)
        }
    }
}
