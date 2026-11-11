/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: Liquid Glass floating window frame and backdrop host for dialogs (AiAssistActivity & AlertDialogs).
 */
package org.fcitx.fcitx5.android.input.glass

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object GlassDialogFrame {

    val isCrossBlurActive: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && GlassCapabilities.isCrossWindowBlurEnabled

    fun applyToWindow(
        window: Window,
        theme: Theme,
        style: GlassStyle,
        explicitWidthPx: Int? = null,
        isAlert: Boolean = false
    ) {
        val ctx = window.context
        val screenW = ctx.resources.displayMetrics.widthPixels
        val defaultWidth = (screenW - ctx.dp(36)).coerceAtLeast(ctx.dp(260))
        val targetWidth = explicitWidthPx ?: if (isAlert) min(defaultWidth, ctx.dp(440)) else defaultWidth

        window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setFormat(PixelFormat.TRANSLUCENT)

        val crossBlur = isCrossBlurActive
        if (crossBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurPx = ctx.dp((style.blurRadiusDp * 1.5f).toInt().coerceIn(24, 56))
            runCatching {
                window.setBackgroundBlurRadius(blurPx)
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            dimAmount = if (crossBlur) {
                if (theme.isDark) 0.28f else 0.16f
            } else {
                if (theme.isDark) 0.45f else 0.30f
            }
        }

        val decorView = window.decorView
        val cornerRadius = ctx.dp(22f)
        val hasThemePhoto = theme is Theme.Custom && theme.backgroundImage != null && !style.seeThrough
        val glassCustomBackdrop = if (!style.seeThrough) {
            org.fcitx.fcitx5.android.data.theme.GlassCustomBackdrop.loadDrawable(theme.isDark)
        } else null
        val hasCustomPhoto = hasThemePhoto || glassCustomBackdrop != null
        val sourceDrawable: Drawable = when {
            hasThemePhoto -> theme.backgroundDrawable(keyBorder = true)
            glassCustomBackdrop != null -> glassCustomBackdrop
            else -> DynamicBackdropDrawable(theme, seeThrough = false)
        }

        val backdrop = GlassBackdrop(theme, style, decorView)
        decorView.setTag(R.id.tag_glass_backdrop, backdrop)

        val windowDrawable = GlassDialogWindowDrawable(
            theme = theme,
            style = style,
            backdrop = backdrop,
            sourceDrawable = sourceDrawable,
            cornerRadius = cornerRadius,
            crossBlur = crossBlur,
            hasCustomPhoto = hasCustomPhoto,
            density = ctx.resources.displayMetrics.density
        )
        window.setBackgroundDrawable(windowDrawable)
        window.setElevation(ctx.dp(16f))

        decorView.outlineProvider = ViewOutlineProvider.BACKGROUND
        decorView.clipToOutline = true

        // Clean up any previous drift attachment and attach if dynamic backdrop animation is on
        (decorView.getTag(R.id.tag_glass_drift) as? DriftAttachment)?.detach()
        if (style.animateBackdrop && sourceDrawable is DynamicBackdropDrawable) {
            val drift = DriftAttachment(decorView, sourceDrawable, backdrop)
            decorView.setTag(R.id.tag_glass_drift, drift)
            drift.attach()
        }
    }

    fun attachScrollRefractionInvalidator(scrollView: View, theme: Theme) {
        Glass.attachScrollInvalidator(scrollView, theme)
    }

    private fun invalidateGlassDescendants(view: View) {
        Glass.invalidateGlassDescendants(view)
    }

    private class DriftAttachment(
        private val decorView: View,
        private val sourceDrawable: DynamicBackdropDrawable,
        private val backdrop: GlassBackdrop
    ) : View.OnAttachStateChangeListener {
        private var animator: ValueAnimator? = null
        private var lastUpdateMs = 0L

        fun attach() {
            decorView.addOnAttachStateChangeListener(this)
            if (decorView.isAttachedToWindow) start()
        }

        fun detach() {
            stop()
            decorView.removeOnAttachStateChangeListener(this)
        }

        override fun onViewAttachedToWindow(v: View) = start()
        override fun onViewDetachedFromWindow(v: View) = stop()

        private fun start() {
            if (animator?.isRunning == true) return
            animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 28_000L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs < 100L) return@addUpdateListener
                    lastUpdateMs = now
                    sourceDrawable.phase = anim.animatedValue as Float
                    if (decorView.width > 0 && decorView.height > 0) {
                        backdrop.update(sourceDrawable, decorView.width, decorView.height)
                        invalidateGlassDescendants(decorView)
                        decorView.postInvalidateOnAnimation()
                    }
                }
                start()
            }
        }

        private fun stop() {
            animator?.cancel()
            animator = null
        }
    }

    private class GlassDialogWindowDrawable(
        private val theme: Theme,
        private val style: GlassStyle,
        private val backdrop: GlassBackdrop,
        private val sourceDrawable: Drawable,
        private val cornerRadius: Float,
        private val crossBlur: Boolean,
        private val hasCustomPhoto: Boolean,
        private val density: Float
    ) : Drawable() {

        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        private val rectF = RectF()
        private val rimRect = RectF()
        private val innerRimRect = RectF()
        private val clipPath = Path()
        private val bitmapMatrix = Matrix()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width()
            val h = bounds.height()
            if (w <= 0 || h <= 0) return

            if (w != backdrop.sourceWidth || h != backdrop.sourceHeight || backdrop.version == 0) {
                backdrop.update(sourceDrawable, w, h)
            }

            rectF.set(bounds)
            clipPath.reset()
            clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)

            // Convex top-to-bottom inner volume sheen
            val topSheen = if (theme.isDark) 0x20ffffff else 0x38ffffff
            val midSheen = if (theme.isDark) 0x06ffffff else 0x0effffff
            val botSheen = if (theme.isDark) 0x0c8ab4f8 else 0x10ffffff
            sheenPaint.shader = LinearGradient(
                rectF.left,
                rectF.top,
                rectF.left,
                rectF.bottom,
                intArrayOf(topSheen, midSheen, botSheen),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )

            // Outer 135-degree specular + chromatic caustic rim
            val hl = style.highlight.coerceIn(0.15f, 1.2f)
            val outerStroke = max(1.1f, density * 1.25f)
            val halfOuter = outerStroke * 0.5f
            rimRect.set(
                rectF.left + halfOuter,
                rectF.top + halfOuter,
                rectF.right - halfOuter,
                rectF.bottom - halfOuter
            )
            outerRimPaint.strokeWidth = outerStroke
            val aTopLeft = ((if (theme.isDark) 210 else 248) * min(1f, hl * 1.15f)).roundToInt().coerceIn(0, 255)
            val aCenter = ((if (theme.isDark) 48 else 85) * min(1f, hl)).roundToInt().coerceIn(0, 255)
            val aBotRight = ((if (theme.isDark) 135 else 175) * min(1f, hl)).roundToInt().coerceIn(0, 255)
            val cTopLeft = (aTopLeft shl 24) or (if (theme.isDark) 0x00edf5ff else 0x00ffffff)
            val cCenter = (aCenter shl 24) or 0x00ffffff
            // Subtle prism cyan-emerald-lavender tint on the opposite bevel
            val cBotRight = (aBotRight shl 24) or (if (theme.isDark) 0x0064d8cb else 0x00b8e6ff)
            outerRimPaint.shader = LinearGradient(
                rimRect.left,
                rimRect.top,
                rimRect.right,
                rimRect.bottom,
                intArrayOf(cTopLeft, cCenter, cBotRight),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )

            // Subtle inner bevel hairline
            val innerStroke = max(0.75f, density * 0.7f)
            val insetInner = outerStroke + innerStroke * 0.5f
            innerRimRect.set(
                rectF.left + insetInner,
                rectF.top + insetInner,
                rectF.right - insetInner,
                rectF.bottom - insetInner
            )
            innerRimPaint.strokeWidth = innerStroke
            val innerTopAlpha = if (theme.isDark) 0x24 else 0x48
            innerRimPaint.shader = LinearGradient(
                innerRimRect.left,
                innerRimRect.top,
                innerRimRect.left,
                innerRimRect.bottom,
                (innerTopAlpha shl 24) or 0x00ffffff,
                0x00ffffff,
                Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            if (rectF.isEmpty) return

            canvas.save()
            canvas.clipPath(clipPath)

            // 1. Blurred aurora / custom photo backdrop layer
            val bmp = backdrop.blurredBitmap
            if (bmp != null && !bmp.isRecycled) {
                val backdropAlpha = when {
                    !crossBlur -> 0xF2
                    style.seeThrough -> 0x3A
                    hasCustomPhoto -> 0xDC
                    else -> 0x7C
                }
                bitmapPaint.alpha = backdropAlpha
                bitmapMatrix.reset()
                bitmapMatrix.setScale(
                    rectF.width() / max(1f, bmp.width.toFloat()),
                    rectF.height() / max(1f, bmp.height.toFloat())
                )
                bitmapMatrix.postTranslate(rectF.left, rectF.top)
                canvas.drawBitmap(bmp, bitmapMatrix, bitmapPaint)
            }

            // 2. Frosted pearl / obsidian veil to guarantee text contrast over any host app
            val basePole = if (theme.isDark) 0xff121826.toInt() else 0xfff0f5fc.toInt()
            val veilRgb = ColorUtils.blendARGB(theme.keyboardColor or (0xff shl 24), basePole, 0.55f) and 0x00ffffff
            val veilFloatAlpha = when {
                !crossBlur -> (0.28f + style.tintOpacity * 0.25f).coerceIn(0.24f, 0.55f)
                style.seeThrough -> (if (theme.isDark) 0.48f else 0.52f)
                else -> (if (theme.isDark) 0.46f else 0.48f)
            }
            val veilAlpha = (veilFloatAlpha * 255f).roundToInt().coerceIn(0, 255)
            veilPaint.color = (veilAlpha shl 24) or veilRgb
            canvas.drawRect(rectF, veilPaint)

            // 3. Convex top-to-bottom glass volume sheen
            canvas.drawRect(rectF, sheenPaint)

            canvas.restore()

            // 4. Outer & inner specular caustic rims
            if (!rimRect.isEmpty) {
                val rOuter = max(0f, cornerRadius - outerRimPaint.strokeWidth * 0.5f)
                canvas.drawRoundRect(rimRect, rOuter, rOuter, outerRimPaint)
            }
            if (!innerRimRect.isEmpty) {
                val rInner = max(0f, cornerRadius - outerRimPaint.strokeWidth - innerRimPaint.strokeWidth * 0.5f)
                canvas.drawRoundRect(innerRimRect, rInner, rInner, innerRimPaint)
            }
        }

        override fun getOutline(outline: Outline) {
            if (!bounds.isEmpty) {
                outline.setRoundRect(bounds, cornerRadius)
                outline.alpha = 0.36f
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
