/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: stateful Liquid Glass drawable for keys, toolbar buttons, popups, and panels.
 */
package org.fcitx.fcitx5.android.input.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class GlassDrawable(
    hostView: View?,
    private val theme: Theme,
    private val style: GlassStyle,
    @ColorInt private val baseColor: Int,
    @ColorInt private val activeColor: Int = baseColor,
    @ColorInt private val textColor: Int = theme.keyTextColor,
    private val cornerRadius: Float = 0f,
    private val isOval: Boolean = false,
    private val hInset: Int = 0,
    private val vInset: Int = 0,
    private val refractionScale: Float = 1f,
    private val extraOpacityBoost: Float = 0f,
    private val enablePressMorph: Boolean = false,
    scaleViewOnPress: Boolean = false,
    private val surfaceAlpha: Float = 1f,
    private val outlineAlpha: Float = 0f
) : Drawable() {

    private val viewRef = if (hostView != null) WeakReference(hostView) else null
    private var backdropRef: WeakReference<GlassBackdrop>? = null

    private val renderer = GlassRenderer(theme.isDark, style)
    private val drawRect = RectF()
    private val offsetTmp = FloatArray(2)

    private var isActivatedState = false

    private val pressAnimator = GlassPressAnimator(
        enabled = enablePressMorph && style.pressMorph,
        scaleTarget = if (scaleViewOnPress) hostView else null
    ) {
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        var pressed = false
        var activated = false
        for (s in state) {
            if (s == android.R.attr.state_pressed) pressed = true
            if (s == android.R.attr.state_activated || s == android.R.attr.state_selected) activated = true
        }
        val actChanged = isActivatedState != activated
        isActivatedState = activated
        pressAnimator.onPressedChanged(pressed)
        if (actChanged) invalidateSelf()
        return actChanged || enablePressMorph
    }

    private fun findBackdrop(view: View?): GlassBackdrop? {
        backdropRef?.get()?.let { return it }
        var cur: View? = view
        while (cur != null) {
            val tagged = cur.getTag(R.id.tag_glass_backdrop) as? GlassBackdrop
            if (tagged != null) {
                backdropRef = WeakReference(tagged)
                return tagged
            }
            val p = cur.parent
            cur = if (p is View) p else null
        }
        return null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        val view = viewRef?.get()
        val density = view?.resources?.displayMetrics?.density ?: 2.75f
        val press = pressAnimator.progress.coerceIn(-0.25f, 1.35f)

        // Expand droplet into its margin inset when pressed for elastic liquid morph
        val morphExpandX = if (enablePressMorph && hInset > 0) min(hInset.toFloat() * 0.55f, density * 2.2f) * press else 0f
        val morphExpandY = if (enablePressMorph && vInset > 0) min(vInset.toFloat() * 0.50f, density * 2.6f) * press else 0f

        val left = b.left + hInset - morphExpandX
        val top = b.top + vInset - morphExpandY
        val right = b.right - hInset + morphExpandX
        val bottom = b.bottom - vInset + morphExpandY
        if (right <= left || bottom <= top) return
        drawRect.set(left, top, right, bottom)

        val r = if (isOval) {
            min(drawRect.width(), drawRect.height()) * 0.5f
        } else {
            cornerRadius + max(0f, morphExpandX * 0.5f)
        }

        val backdrop = findBackdrop(view)
        val backdropHost = backdrop?.hostView
        var offsetX = 0f
        var offsetY = 0f
        if (view != null && backdropHost != null) {
            computeOffsetRelativeTo(view, backdropHost, offsetTmp)
            offsetX = offsetTmp[0]
            offsetY = offsetTmp[1]
        }

        val rawColor = if (isActivatedState) activeColor else baseColor
        val centerX = offsetX + drawRect.centerX()
        val centerY = offsetY + drawRect.centerY()

        val resolvedTint = if (backdrop != null && !style.seeThrough) {
            backdrop.resolveAdaptiveTint(rawColor, textColor, centerX, centerY, extraOpacityBoost)
        } else {
            val rawAlpha = Color.alpha(rawColor) / 255f
            val baseOpacity = if (rawAlpha < 0.98f) {
                (rawAlpha * (0.6f + style.tintOpacity * 0.85f)).coerceIn(0.12f, 0.95f)
            } else {
                style.tintOpacity.coerceIn(0.12f, 0.95f)
            }
            val op = (baseOpacity + extraOpacityBoost).coerceIn(0.12f, 0.96f)
            ColorUtils.setAlphaComponent(rawColor, (op * 255f).roundToInt())
        }

        // Blend press highlight into tint when pressed
        val finalTint = if (press > 0.01f) {
            val hlAlpha = (Color.alpha(theme.keyPressHighlightColor) / 255f * press.coerceAtMost(1f) * 0.65f)
                .coerceIn(0f, 0.45f)
            ColorUtils.blendARGB(resolvedTint, theme.keyPressHighlightColor or (0xff shl 24), hlAlpha)
        } else {
            resolvedTint
        }

        renderer.draw(
            canvas = canvas,
            rect = drawRect,
            radius = r,
            backdrop = backdrop,
            backdropOffsetX = offsetX,
            backdropOffsetY = offsetY,
            tintColor = finalTint,
            shadowColor = theme.keyShadowColor,
            pressProgress = max(0f, press),
            refractionScale = refractionScale,
            density = density,
            surfaceAlpha = surfaceAlpha
        )
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        val left = (b.left + hInset).coerceAtMost(b.right)
        val top = (b.top + vInset).coerceAtMost(b.bottom)
        val right = (b.right - hInset).coerceAtLeast(left)
        val bottom = (b.bottom - vInset).coerceAtLeast(top)
        if (isOval) {
            outline.setOval(left, top, right, bottom)
        } else {
            outline.setRoundRect(left, top, right, bottom, cornerRadius)
        }
        outline.alpha = outlineAlpha
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val winLoc1 = IntArray(2)
        private val winLoc2 = IntArray(2)

        fun computeOffsetRelativeTo(view: View, backdrop: View, out: FloatArray) {
            var vx = 0f
            var vy = 0f
            var cur: View? = view
            var viewRoot: View? = null
            while (cur != null) {
                viewRoot = cur
                val p = cur.parent
                if (p is View) {
                    vx += cur.left + cur.translationX - p.scrollX
                    vy += cur.top + cur.translationY - p.scrollY
                    cur = p
                } else {
                    break
                }
            }
            var bx = 0f
            var by = 0f
            cur = backdrop
            var backdropRoot: View? = null
            while (cur != null) {
                backdropRoot = cur
                val p = cur.parent
                if (p is View) {
                    bx += cur.left + cur.translationX - p.scrollX
                    by += cur.top + cur.translationY - p.scrollY
                    cur = p
                } else {
                    break
                }
            }
            if (viewRoot === backdropRoot && viewRoot != null) {
                out[0] = vx - bx
                out[1] = vy - by
                return
            }
            // Fallback across separate PopupWindows
            if (view.isAttachedToWindow && backdrop.isAttachedToWindow) {
                view.getLocationOnScreen(winLoc1)
                backdrop.getLocationOnScreen(winLoc2)
                out[0] = (winLoc1[0] - winLoc2[0]).toFloat()
                out[1] = (winLoc1[1] - winLoc2[1]).toFloat()
            } else {
                out[0] = 0f
                out[1] = 0f
            }
        }
    }
}
