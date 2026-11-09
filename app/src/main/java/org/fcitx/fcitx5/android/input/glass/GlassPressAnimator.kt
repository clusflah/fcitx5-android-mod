/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: elastic liquid droplet press morph, popup grow-in, and candidate jelly animations.
 */
package org.fcitx.fcitx5.android.input.glass

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

class GlassPressAnimator(
    private val enabled: Boolean,
    private val scaleTarget: View? = null,
    private val maxScale: Float = 1.035f,
    private val onProgress: (Float) -> Unit
) {
    var progress: Float = 0f
        private set

    private var pressed: Boolean = false
    private var animator: ValueAnimator? = null

    fun onPressedChanged(isPressed: Boolean) {
        if (pressed == isPressed) return
        pressed = isPressed
        animator?.cancel()

        if (!enabled || !GlassCapabilities.animationsEnabled) {
            progress = if (isPressed) 1f else 0f
            applyScale(progress)
            onProgress(progress)
            return
        }

        val target = if (isPressed) 1f else 0f
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = if (isPressed) 95L else 210L
            interpolator = if (isPressed) PressInterpolator else ReleaseInterpolator
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                applyScale(progress)
                onProgress(progress)
            }
            start()
        }
    }

    private fun applyScale(p: Float) {
        val v = scaleTarget ?: return
        val s = 1f + (maxScale - 1f) * p
        v.scaleX = s
        v.scaleY = s
    }

    companion object {
        private val PressInterpolator = DecelerateInterpolator(1.6f)
        private val ReleaseInterpolator = OvershootInterpolator(2.2f)
        private val PopupGrowInterpolator = OvershootInterpolator(1.35f)

        fun animatePopupIn(view: View, style: GlassStyle) {
            if (!style.enabled || !style.pressMorph || !GlassCapabilities.animationsEnabled) return
            view.animate().cancel()
            view.scaleX = 0.82f
            view.scaleY = 0.82f
            view.alpha = 0f
            view.post {
                view.pivotX = view.width * 0.5f
                view.pivotY = view.height.toFloat()
            }
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(135L)
                .setInterpolator(PopupGrowInterpolator)
                .start()
        }

        fun animateJelly(view: View, style: GlassStyle) {
            if (!style.enabled || !style.pressMorph || !GlassCapabilities.animationsEnabled) return
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280L
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    // Damped harmonic wobble
                    val wave = kotlin.math.sin(t * Math.PI * 2.5).toFloat() * (1f - t) * 0.08f
                    view.scaleX = 1f + wave
                    view.scaleY = 1f - wave * 0.85f
                }
                start()
            }
        }
    }
}
