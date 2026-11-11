/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: unified facade for Liquid Glass effects across keyboard views, bars, popups, and panels.
 */
package org.fcitx.fcitx5.android.input.glass

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager

object Glass {

    fun style(theme: Theme = ThemeManager.activeTheme): GlassStyle =
        GlassStyle.resolve(theme)

    fun isActive(theme: Theme = ThemeManager.activeTheme): Boolean =
        style(theme).enabled

    /**
     * Translucent active/selected glass tint that preserves alpha on both light and dark themes
     * (prevents dark-theme translucent white base colors from turning opaque pastel white).
     */
    @ColorInt
    fun activeTint(theme: Theme, @ColorInt accent: Int = theme.genericActiveBackgroundColor): Int {
        if (!isActive(theme)) {
            val r = (((theme.keyBackgroundColor shr 16) and 0xff) * 0.84f + ((accent shr 16) and 0xff) * 0.16f).toInt()
            val g = (((theme.keyBackgroundColor shr 8) and 0xff) * 0.84f + ((accent shr 8) and 0xff) * 0.16f).toInt()
            val b = ((theme.keyBackgroundColor and 0xff) * 0.84f + (accent and 0xff) * 0.16f).toInt()
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val rgbAccent = accent or (0xff shl 24)
        return if (theme.isDark) {
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(0xff182232.toInt(), rgbAccent, 0.38f), 0x72)
        } else {
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(0xffffffff.toInt(), rgbAccent, 0.20f), 0x90)
        }
    }

    /**
     * Recursively invalidates all descendant views whose background is a [GlassDrawable] so
     * hardware RenderNodes re-record with updated backdrop coordinates on scroll/swipe/drift.
     */
    fun invalidateGlassDescendants(view: View) {
        if (view.background is GlassDrawable) {
            view.invalidate()
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                invalidateGlassDescendants(view.getChildAt(i))
            }
        }
    }

    /**
     * Attaches a scroll/page listener to [view] ([RecyclerView], [ViewPager2], `ScrollView`,
     * `HorizontalScrollView`) so scrolling updates AGSL refraction coordinates in real time.
     */
    fun attachScrollInvalidator(view: View, theme: Theme = ThemeManager.activeTheme) {
        if (!isActive(theme)) return
        when (view) {
            is RecyclerView -> {
                view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (dx != 0 || dy != 0) invalidateGlassDescendants(recyclerView)
                    }
                })
            }
            is ViewPager2 -> {
                view.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                        invalidateGlassDescendants(view)
                    }
                })
            }
            else -> {
                view.setOnScrollChangeListener { v, _, _, _, _ ->
                    invalidateGlassDescendants(v)
                }
            }
        }
    }

    /**
     * Attaches a standalone [GlassBackdrop] to a separate `PopupWindow` root or floating view
     * (`CardPopupMenu`, `DragChooser`, `CandidatesView`) that has no parent link to `InputView`.
     */
    fun attachStandaloneBackdrop(rootView: View, theme: Theme = ThemeManager.activeTheme): GlassBackdrop? {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled) return null
        val hasThemePhoto = theme is Theme.Custom && theme.backgroundImage != null && !style.seeThrough
        val glassCustomBackdrop = if (!style.seeThrough) {
            org.fcitx.fcitx5.android.data.theme.GlassCustomBackdrop.loadDrawable(theme.isDark)
        } else null
        val sourceDrawable: Drawable = when {
            hasThemePhoto -> theme.backgroundDrawable(keyBorder = true)
            glassCustomBackdrop != null -> glassCustomBackdrop
            else -> DynamicBackdropDrawable(theme, seeThrough = style.seeThrough)
        }
        val backdrop = GlassBackdrop(theme, style, rootView)
        rootView.setTag(R.id.tag_glass_backdrop, backdrop)
        if (rootView.width > 0 && rootView.height > 0) {
            backdrop.update(sourceDrawable, rootView.width, rootView.height)
        }
        rootView.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != backdrop.sourceWidth || h != backdrop.sourceHeight || backdrop.version == 0)) {
                backdrop.update(sourceDrawable, w, h)
                invalidateGlassDescendants(v)
                v.invalidate()
            }
        }
        return backdrop
    }

    /**
     * Configures [backgroundImageView] and attaches a shared [GlassBackdrop] to [rootContainer]
     * (and its parent if present) so descendant [GlassDrawable] instances can sample it.
     */
    fun attachBackdrop(
        rootContainer: View,
        backgroundImageView: ImageView,
        theme: Theme,
        keyBorder: Boolean,
        customDrawable: Drawable? = null
    ): GlassBackdrop? {
        val style = GlassStyle.resolve(theme)
        // Clean up any old layout listener / drift animator
        (backgroundImageView.getTag(R.id.tag_glass_backdrop) as? BackdropAttachment)?.detach()

        if (!style.enabled) {
            rootContainer.setTag(R.id.tag_glass_backdrop, null)
            (rootContainer.parent as? View)?.setTag(R.id.tag_glass_backdrop, null)
            backgroundImageView.setImageDrawable(customDrawable ?: theme.backgroundDrawable(keyBorder))
            return null
        }

        val hasCustomImage = customDrawable != null || (theme is Theme.Custom && theme.backgroundImage != null)
        val glassCustomBackdrop = if (!style.seeThrough) {
            org.fcitx.fcitx5.android.data.theme.GlassCustomBackdrop.loadDrawable(theme.isDark)
        } else null
        val sourceDrawable: Drawable = when {
            customDrawable != null -> customDrawable
            hasCustomImage -> theme.backgroundDrawable(keyBorder)
            glassCustomBackdrop != null -> glassCustomBackdrop
            style.dynamicBackdrop -> DynamicBackdropDrawable(theme, seeThrough = style.seeThrough)
            else -> theme.backgroundDrawable(keyBorder)
        }

        backgroundImageView.setImageDrawable(sourceDrawable)

        val backdrop = GlassBackdrop(theme, style, backgroundImageView)
        rootContainer.setTag(R.id.tag_glass_backdrop, backdrop)
        (rootContainer.parent as? View)?.setTag(R.id.tag_glass_backdrop, backdrop)

        val attachment = BackdropAttachment(backgroundImageView, rootContainer, sourceDrawable, backdrop, style)
        attachment.attach()
        backgroundImageView.setTag(R.id.tag_glass_backdrop, attachment)

        if (backgroundImageView.width > 0 && backgroundImageView.height > 0) {
            backdrop.update(sourceDrawable, backgroundImageView.width, backgroundImageView.height)
        }
        return backdrop
    }

    private class BackdropAttachment(
        private val imageView: ImageView,
        private val rootContainer: View,
        private val sourceDrawable: Drawable,
        private val backdrop: GlassBackdrop,
        private val style: GlassStyle
    ) : View.OnLayoutChangeListener, View.OnAttachStateChangeListener {

        private var driftAnimator: ValueAnimator? = null
        private var lastUpdateMs = 0L

        fun attach() {
            imageView.addOnLayoutChangeListener(this)
            imageView.addOnAttachStateChangeListener(this)
            if (imageView.isAttachedToWindow) {
                startDriftIfNeeded()
            }
        }

        fun detach() {
            stopDrift()
            imageView.removeOnLayoutChangeListener(this)
            imageView.removeOnAttachStateChangeListener(this)
        }

        override fun onLayoutChange(
            v: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
        ) {
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != backdrop.sourceWidth || h != backdrop.sourceHeight || backdrop.version == 0)) {
                backdrop.update(sourceDrawable, w, h)
                invalidateGlassDescendants(rootContainer)
                rootContainer.invalidate()
            }
        }

        override fun onViewAttachedToWindow(v: View) {
            startDriftIfNeeded()
        }

        override fun onViewDetachedFromWindow(v: View) {
            stopDrift()
        }

        private fun startDriftIfNeeded() {
            if (!style.animateBackdrop || sourceDrawable !is DynamicBackdropDrawable) return
            if (driftAnimator?.isRunning == true) return
            driftAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 28_000L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val now = System.currentTimeMillis()
                    // Cap backdrop re-blur to ~10 fps to keep battery impact minimal
                    if (now - lastUpdateMs < 100L) return@addUpdateListener
                    lastUpdateMs = now
                    sourceDrawable.phase = anim.animatedValue as Float
                    if (imageView.width > 0 && imageView.height > 0) {
                        backdrop.update(sourceDrawable, imageView.width, imageView.height)
                        invalidateGlassDescendants(rootContainer)
                        rootContainer.postInvalidateOnAnimation()
                    }
                }
                start()
            }
        }

        private fun stopDrift() {
            driftAnimator?.cancel()
            driftAnimator = null
        }
    }

    /**
     * Key background drawable (for [org.fcitx.fcitx5.android.input.keyboard.KeyView],
     * [org.fcitx.fcitx5.android.input.editing.TextEditingButton], [org.fcitx.fcitx5.android.input.keyboard.T9Keyboard]).
     */
    fun keyBackground(
        view: View,
        theme: Theme,
        @ColorInt bkgColor: Int,
        @ColorInt textColor: Int = theme.keyTextColor,
        radius: Float,
        hInset: Int = 0,
        vInset: Int = 0,
        isOval: Boolean = false,
        pressMorph: Boolean = true,
        scaleViewOnPress: Boolean = false
    ): Drawable? {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled || !style.targetKeys) return null
        return GlassDrawable(
            hostView = view,
            theme = theme,
            style = style,
            baseColor = bkgColor,
            activeColor = bkgColor,
            textColor = textColor,
            cornerRadius = radius,
            isOval = isOval,
            hInset = hInset,
            vInset = vInset,
            refractionScale = 1f,
            enablePressMorph = pressMorph,
            scaleViewOnPress = scaleViewOnPress
        )
    }

    fun statefulKeyBackground(
        view: View,
        theme: Theme,
        @ColorInt normalColor: Int,
        @ColorInt activeColor: Int,
        @ColorInt textColor: Int = theme.keyTextColor,
        radius: Float,
        hInset: Int = 0,
        vInset: Int = 0
    ): Drawable? {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled || !style.targetKeys) return null
        return GlassDrawable(
            hostView = view,
            theme = theme,
            style = style,
            baseColor = normalColor,
            activeColor = activeColor,
            textColor = textColor,
            cornerRadius = radius,
            hInset = hInset,
            vInset = vInset,
            refractionScale = 1f,
            enablePressMorph = true
        )
    }

    /**
     * Adds a subtle 1dp soft shadow to labels when see-through or low-opacity glass is active
     * to guarantee readability against arbitrary backgrounds (§3.5).
     */
    fun applyKeyTextShadow(textView: TextView, theme: Theme) {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled) return
        if (style.seeThrough || style.tintOpacity < 0.65f) {
            val shadowColor = if (theme.isDark) 0x88000000.toInt() else 0x55ffffff
            textView.setShadowLayer(2.2f, 0f, 1f, shadowColor)
        }
    }

    fun barBackground(view: View, theme: Theme, keyBorder: Boolean): Drawable? {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled || !style.targetToolbar) return null
        if (keyBorder) return ColorDrawable(Color.TRANSPARENT)
        return GlassDrawable(
            hostView = view,
            theme = theme,
            style = style,
            baseColor = theme.barColor,
            textColor = theme.keyTextColor,
            cornerRadius = 0f,
            refractionScale = 0.6f
        )
    }

    fun barButtonBackground(
        view: View,
        theme: Theme,
        insetPx: Int,
        @ColorInt bkgColor: Int = theme.keyBackgroundColor,
        @ColorInt textColor: Int = theme.altKeyTextColor
    ): Drawable? {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled || !style.targetToolbar) return null
        return GlassDrawable(
            hostView = view,
            theme = theme,
            style = style,
            baseColor = bkgColor,
            textColor = textColor,
            isOval = true,
            hInset = insetPx,
            vInset = insetPx,
            refractionScale = 0.9f,
            enablePressMorph = true,
            scaleViewOnPress = true
        )
    }

    fun candidateBackground(
        view: View,
        theme: Theme,
        radius: Float,
        @ColorInt bkgColor: Int = theme.backgroundColor
    ): Drawable? {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled || !style.targetCandidates) return null
        return GlassDrawable(
            hostView = view,
            theme = theme,
            style = style,
            baseColor = bkgColor,
            textColor = theme.candidateTextColor,
            cornerRadius = radius,
            refractionScale = 1.05f,
            extraOpacityBoost = 0.18f,
            outlineAlpha = 0.28f
        )
    }

    fun popupBackground(
        view: View,
        theme: Theme,
        radius: Float,
        @ColorInt bkgColor: Int = theme.popupBackgroundColor,
        @ColorInt textColor: Int = theme.popupTextColor,
        isOval: Boolean = false,
        hInset: Int = 0,
        vInset: Int = 0,
        pressMorph: Boolean = false,
        scaleViewOnPress: Boolean = false
    ): Drawable? {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled || !style.targetPopups) return null
        return GlassDrawable(
            hostView = view,
            theme = theme,
            style = style,
            baseColor = bkgColor,
            textColor = textColor,
            cornerRadius = radius,
            isOval = isOval,
            hInset = hInset,
            vInset = vInset,
            refractionScale = 1.25f,
            extraOpacityBoost = 0.22f,
            enablePressMorph = pressMorph,
            scaleViewOnPress = scaleViewOnPress,
            outlineAlpha = 0.28f
        )
    }

    fun animatePopupIn(view: View, theme: Theme) {
        val style = GlassStyle.resolve(theme)
        if (style.enabled && style.targetPopups) {
            GlassPressAnimator.animatePopupIn(view, style)
        }
    }

    fun animateCandidateJelly(view: View, theme: Theme) {
        val style = GlassStyle.resolve(theme)
        if (style.enabled && style.targetCandidates) {
            GlassPressAnimator.animateJelly(view, style)
        }
    }

    fun panelCardBackground(
        view: View,
        theme: Theme,
        radius: Float,
        @ColorInt bkgColor: Int = theme.clipboardEntryColor,
        @ColorInt textColor: Int = theme.keyTextColor,
        hInset: Int = 0,
        vInset: Int = 0,
        isOval: Boolean = false,
        pressMorph: Boolean = false,
        scaleViewOnPress: Boolean = pressMorph,
        extraOpacityBoost: Float = 0.08f
    ): Drawable? {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled || !style.targetPanels) return null
        return GlassDrawable(
            hostView = view,
            theme = theme,
            style = style,
            baseColor = bkgColor,
            textColor = textColor,
            cornerRadius = radius,
            isOval = isOval,
            hInset = hInset,
            vInset = vInset,
            refractionScale = 1f,
            extraOpacityBoost = extraOpacityBoost,
            enablePressMorph = pressMorph,
            scaleViewOnPress = scaleViewOnPress
        )
    }

    fun isDialogGlassActive(theme: Theme = ThemeManager.activeTheme): Boolean =
        GlassStyle.resolve(theme).let { it.enabled && it.targetPanels }

    fun styleDialogWindow(
        window: android.view.Window,
        theme: Theme = ThemeManager.activeTheme,
        explicitWidthPx: Int? = null,
        isAlert: Boolean = false
    ): Boolean {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled || !style.targetPanels) return false
        GlassDialogFrame.applyToWindow(window, theme, style, explicitWidthPx, isAlert)
        return true
    }

    fun dialogControlBackground(
        view: View,
        theme: Theme,
        radius: Float,
        @ColorInt bkgColor: Int,
        @ColorInt textColor: Int = theme.keyTextColor,
        refractionScale: Float = 1f,
        extraOpacityBoost: Float = 0.08f,
        pressMorph: Boolean = false,
        scaleViewOnPress: Boolean = false,
        crossBlurAlpha: Float = 0.70f
    ): Drawable? {
        val style = GlassStyle.resolve(theme)
        if (!style.enabled || !style.targetPanels) return null
        val surfaceAlpha = if (GlassDialogFrame.isCrossBlurActive) crossBlurAlpha else 1f
        return GlassDrawable(
            hostView = view,
            theme = theme,
            style = style,
            baseColor = bkgColor,
            textColor = textColor,
            cornerRadius = radius,
            refractionScale = refractionScale,
            extraOpacityBoost = extraOpacityBoost,
            enablePressMorph = pressMorph,
            scaleViewOnPress = scaleViewOnPress,
            surfaceAlpha = surfaceAlpha
        )
    }
}
