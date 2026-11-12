/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: multi-tier Liquid Glass renderer (AGSL RuntimeShader on API 33+, BitmapShader + specular rim on API 23–32).
 */
package org.fcitx.fcitx5.android.input.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class GlassRenderer(private val isDark: Boolean, private val style: GlassStyle) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()
    private val shadowRect = RectF()
    private val rimRect = RectF()
    private val clipPath = Path()

    private var cachedBitmap: Bitmap? = null
    private var localBitmapShader: BitmapShader? = null

    private var lastSheenTop = Float.NaN
    private var lastSheenBottom = Float.NaN
    private var lastRimLeft = Float.NaN
    private var lastRimTop = Float.NaN
    private var lastRimRight = Float.NaN
    private var lastRimBottom = Float.NaN
    private var lastRimHighlight = Float.NaN

    private val agslHelper: AgslHelper? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !style.seeThrough) {
            AgslHelper.create()
        } else null

    fun draw(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        backdrop: GlassBackdrop?,
        backdropOffsetX: Float,
        backdropOffsetY: Float,
        @ColorInt tintColor: Int,
        @ColorInt shadowColor: Int,
        pressProgress: Float,
        refractionScale: Float,
        density: Float,
        surfaceAlpha: Float = 1f
    ) {
        if (rect.isEmpty) return

        // 1. Subtle refractive drop shadow beneath the glass element
        val shadowOffset = max(1f, density * 1.1f)
        val rawShadowAlpha = Color.alpha(shadowColor)
        val shadowAlpha = if (rawShadowAlpha in 1..240) {
            (rawShadowAlpha * 0.65f).roundToInt()
        } else {
            if (isDark) 0x38 else 0x22
        }
        if (shadowAlpha > 0) {
            shadowPaint.color = (shadowColor and 0x00ffffff) or (shadowAlpha shl 24)
            shadowRect.set(rect.left, rect.top + shadowOffset * 0.5f, rect.right, rect.bottom + shadowOffset)
            val isTranslucentSurface = style.seeThrough || surfaceAlpha < 0.99f
            if (isTranslucentSurface && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Clip out interior so shadow doesn't muddy translucent glass droplets
                clipPath.reset()
                clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
                canvas.save()
                canvas.clipOutPath(clipPath)
                canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)
                canvas.restore()
            } else if (!isTranslucentSurface) {
                canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)
            }
        }

        // 2. Try AGSL RuntimeShader path on API 33+ when backdrop bitmap is available
        val bmp = backdrop?.blurredBitmap
        val sharedShader = backdrop?.bitmapShader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            agslHelper != null &&
            !style.seeThrough &&
            bmp != null &&
            !bmp.isRecycled &&
            sharedShader != null
        ) {
            val refractionPx = style.refraction * refractionScale * density * 14f
            val drawn = agslHelper.draw(
                canvas = canvas,
                rect = rect,
                radius = radius,
                backdropShader = sharedShader,
                backdropOffsetX = backdropOffsetX,
                backdropOffsetY = backdropOffsetY,
                bitmapW = bmp.width.toFloat(),
                bitmapH = bmp.height.toFloat(),
                downscale = (backdrop.downscaleX + backdrop.downscaleY) * 0.5f,
                refractionPx = refractionPx,
                dispersion = style.dispersion,
                highlight = style.highlight,
                press = pressProgress,
                tintColor = tintColor,
                isDark = isDark,
                surfaceAlpha = surfaceAlpha.coerceIn(0.08f, 1f)
            )
            if (drawn) return
        }

        // 3. Fallback / SeeThrough path (API 23–32, or SeeThrough cross-window blur mode)
        val clampedSurfaceAlpha = surfaceAlpha.coerceIn(0.08f, 1f)
        if (!style.seeThrough && bmp != null && !bmp.isRecycled) {
            var bs = localBitmapShader
            if (bs == null || cachedBitmap !== bmp) {
                bs = BitmapShader(bmp, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
                localBitmapShader = bs
                cachedBitmap = bmp
            }
            // Slight lens zoom on press
            val pressZoom = 1f + pressProgress * 0.04f
            val invScaleX = (1f / max(0.01f, backdrop.downscaleX)) * pressZoom
            val invScaleY = (1f / max(0.01f, backdrop.downscaleY)) * pressZoom
            shaderMatrix.reset()
            shaderMatrix.setScale(invScaleX, invScaleY)
            shaderMatrix.postTranslate(-backdropOffsetX * pressZoom, -backdropOffsetY * pressZoom)
            bs.setLocalMatrix(shaderMatrix)
            bodyPaint.shader = bs
            bodyPaint.alpha = (clampedSurfaceAlpha * 255f).roundToInt()
            canvas.drawRoundRect(rect, radius, radius, bodyPaint)
        }

        // 4. Frosted surface tint
        if (clampedSurfaceAlpha < 0.99f) {
            val scaledAlpha = (Color.alpha(tintColor) * clampedSurfaceAlpha).roundToInt().coerceIn(0, 255)
            tintPaint.color = (tintColor and 0x00ffffff) or (scaledAlpha shl 24)
        } else {
            tintPaint.color = tintColor
        }
        canvas.drawRoundRect(rect, radius, radius, tintPaint)

        // 5. Internal top-to-bottom sheen gradient
        val sheenScale = if (clampedSurfaceAlpha < 0.99f) (0.35f + 0.65f * clampedSurfaceAlpha) else 1f
        val topSheenAlpha = ((if (isDark) 0x16 else 0x28) * (1f + pressProgress * 0.5f) * sheenScale).roundToInt().coerceIn(0, 255)
        val bottomSheenAlpha = ((if (isDark) 0x04 else 0x06) * sheenScale).roundToInt().coerceIn(0, 255)
        if (rect.top != lastSheenTop || rect.bottom != lastSheenBottom || pressProgress > 0f || clampedSurfaceAlpha < 0.99f) {
            lastSheenTop = rect.top
            lastSheenBottom = rect.bottom
            sheenPaint.shader = LinearGradient(
                rect.left,
                rect.top,
                rect.left,
                rect.bottom,
                (topSheenAlpha shl 24) or 0x00ffffff,
                (bottomSheenAlpha shl 24) or 0x00ffffff,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, sheenPaint)

        // 6. Anisotropic specular rim highlight (top-left bright, center soft, bottom-right secondary caustics)
        val hl = (style.highlight * (1f + pressProgress * 0.35f)).coerceIn(0f, 1.25f)
        if (hl > 0.02f) {
            val strokeW = max(1f, density * 1.05f)
            val halfStroke = strokeW * 0.5f
            rimRect.set(
                rect.left + halfStroke,
                rect.top + halfStroke,
                rect.right - halfStroke,
                rect.bottom - halfStroke
            )
            if (!rimRect.isEmpty) {
                rimPaint.strokeWidth = strokeW
                if (rimRect.left != lastRimLeft ||
                    rimRect.top != lastRimTop ||
                    rimRect.right != lastRimRight ||
                    rimRect.bottom != lastRimBottom ||
                    hl != lastRimHighlight
                ) {
                    lastRimLeft = rimRect.left
                    lastRimTop = rimRect.top
                    lastRimRight = rimRect.right
                    lastRimBottom = rimRect.bottom
                    lastRimHighlight = hl

                    val aPrimary = ((if (isDark) 195 else 235) * min(1f, hl)).roundToInt().coerceIn(0, 255)
                    val aMid = ((if (isDark) 38 else 64) * min(1f, hl)).roundToInt().coerceIn(0, 255)
                    val aSecondary = ((if (isDark) 96 else 130) * min(1f, hl)).roundToInt().coerceIn(0, 255)
                    val rgbSpec = if (isDark) 0x00ebf4ff else 0x00ffffff
                    rimPaint.shader = LinearGradient(
                        rimRect.left,
                        rimRect.top,
                        rimRect.right,
                        rimRect.bottom,
                        intArrayOf(
                            (aPrimary shl 24) or rgbSpec,
                            (aMid shl 24) or rgbSpec,
                            (aSecondary shl 24) or rgbSpec
                        ),
                        floatArrayOf(0f, 0.52f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                val rimRadius = max(0f, radius - halfStroke)
                canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class AgslHelper(private val runtimeShader: RuntimeShader) {
        private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = runtimeShader
        }

        fun draw(
            canvas: Canvas,
            rect: RectF,
            radius: Float,
            backdropShader: BitmapShader,
            backdropOffsetX: Float,
            backdropOffsetY: Float,
            bitmapW: Float,
            bitmapH: Float,
            downscale: Float,
            refractionPx: Float,
            dispersion: Float,
            highlight: Float,
            press: Float,
            @ColorInt tintColor: Int,
            isDark: Boolean,
            surfaceAlpha: Float
        ): Boolean = runCatching {
            runtimeShader.setInputShader("uBackdrop", backdropShader)
            runtimeShader.setFloatUniform("uOrigin", rect.left, rect.top)
            runtimeShader.setFloatUniform("uSize", rect.width(), rect.height())
            runtimeShader.setFloatUniform("uRadius", radius)
            runtimeShader.setFloatUniform("uBackdropOffset", backdropOffsetX, backdropOffsetY)
            runtimeShader.setFloatUniform("uBackdropBitmapSize", bitmapW, bitmapH)
            runtimeShader.setFloatUniform("uDownscale", downscale)
            runtimeShader.setFloatUniform("uRefractionPx", refractionPx)
            runtimeShader.setFloatUniform("uDispersion", dispersion)
            runtimeShader.setFloatUniform("uHighlight", highlight)
            runtimeShader.setFloatUniform("uPress", press)
            runtimeShader.setFloatUniform(
                "uTintColor",
                Color.red(tintColor) / 255f,
                Color.green(tintColor) / 255f,
                Color.blue(tintColor) / 255f,
                Color.alpha(tintColor) / 255f
            )
            runtimeShader.setFloatUniform("uIsDark", if (isDark) 1f else 0f)
            runtimeShader.setFloatUniform("uSurfaceAlpha", surfaceAlpha)
            canvas.drawRoundRect(rect, radius, radius, shaderPaint)
            true
        }.getOrDefault(false)

        companion object {
            @Volatile
            private var cachedSource: String? = null

            @Volatile
            private var shaderFailed: Boolean = false

            private fun loadShaderSource(): String {
                cachedSource?.let { return it }
                synchronized(this) {
                    cachedSource?.let { return it }
                    val src = appContext.resources.openRawResource(R.raw.liquid_glass)
                        .bufferedReader()
                        .use { it.readText() }
                    cachedSource = src
                    return src
                }
            }

            fun create(): AgslHelper? {
                if (shaderFailed) return null
                return runCatching {
                    AgslHelper(RuntimeShader(loadShaderSource()))
                }.onFailure {
                    shaderFailed = true
                }.getOrNull()
            }
        }
    }
}
