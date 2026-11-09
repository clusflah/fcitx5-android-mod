/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: resolved immutable Liquid Glass configuration for a given Theme and device state.
 */
package org.fcitx.fcitx5.android.input.glass

import org.fcitx.fcitx5.android.data.theme.GlassPrefs
import org.fcitx.fcitx5.android.data.theme.GlassPrefs.GlassMode
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod

data class GlassStyle(
    val enabled: Boolean,
    val seeThrough: Boolean,
    val blurRadiusDp: Int,
    val tintOpacity: Float,
    val refraction: Float,
    val dispersion: Float,
    val highlight: Float,
    val pressMorph: Boolean,
    val dynamicBackdrop: Boolean,
    val animateBackdrop: Boolean,
    val highContrast: Boolean,
    val targetKeys: Boolean,
    val targetToolbar: Boolean,
    val targetCandidates: Boolean,
    val targetPopups: Boolean,
    val targetPanels: Boolean
) {
    companion object {
        val Disabled = GlassStyle(
            enabled = false,
            seeThrough = false,
            blurRadiusDp = 0,
            tintOpacity = 1f,
            refraction = 0f,
            dispersion = 0f,
            highlight = 0f,
            pressMorph = false,
            dynamicBackdrop = false,
            animateBackdrop = false,
            highContrast = false,
            targetKeys = false,
            targetToolbar = false,
            targetCandidates = false,
            targetPopups = false,
            targetPanels = false
        )

        fun resolve(theme: Theme, prefs: GlassPrefs = ThemeManager.glassPrefs): GlassStyle {
            GlassCapabilities.ensureInitialized()
            val mode = prefs.mode.getValue()
            if (mode == GlassMode.Off) return Disabled

            if (prefs.powerSaveFallback.getValue() && GlassCapabilities.isPowerSaveMode) {
                return Disabled
            }

            val isGlassPreset = ThemePresetMod.isGlassPreset(theme)
            val active = when (mode) {
                GlassMode.FollowTheme -> isGlassPreset
                GlassMode.Backdrop, GlassMode.SeeThrough -> true
                GlassMode.Off -> false
            }
            if (!active) return Disabled

            val seeThrough = mode == GlassMode.SeeThrough && GlassCapabilities.isCrossWindowBlurEnabled
            val highContrast = GlassCapabilities.isHighTextContrastEnabled

            val rawOpacity = (if (theme.isDark) prefs.tintOpacityDark else prefs.tintOpacityLight)
                .getValue()
                .coerceIn(0, 100) / 100f

            val effectiveOpacity = when {
                highContrast -> rawOpacity.coerceAtLeast(0.72f)
                seeThrough -> rawOpacity.coerceAtLeast(0.42f)
                else -> rawOpacity
            }

            return GlassStyle(
                enabled = true,
                seeThrough = seeThrough,
                blurRadiusDp = prefs.blurRadius.getValue().coerceIn(0, 40),
                tintOpacity = effectiveOpacity,
                refraction = prefs.refraction.getValue().coerceIn(0, 100) / 100f,
                dispersion = (prefs.dispersion.getValue().coerceIn(0, 100) / 100f) * 0.35f,
                highlight = prefs.highlight.getValue().coerceIn(0, 100) / 100f,
                pressMorph = prefs.pressMorph.getValue() && GlassCapabilities.animationsEnabled,
                dynamicBackdrop = prefs.dynamicBackdrop.getValue() || isGlassPreset,
                animateBackdrop = prefs.animateBackdrop.getValue() && GlassCapabilities.animationsEnabled,
                highContrast = highContrast,
                targetKeys = prefs.targetKeys.getValue(),
                targetToolbar = prefs.targetToolbar.getValue(),
                targetCandidates = prefs.targetCandidates.getValue(),
                targetPopups = prefs.targetPopups.getValue(),
                targetPanels = prefs.targetPanels.getValue()
            )
        }
    }
}
