/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: preferences for iOS 26 style Liquid Glass rendering.
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import android.os.Build
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

class GlassPrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.liquid_glass, sharedPreferences) {

    enum class GlassMode(override val stringRes: Int) : ManagedPreferenceEnum {
        FollowTheme(R.string.glass_mode_follow_theme),
        Backdrop(R.string.glass_mode_backdrop),
        SeeThrough(R.string.glass_mode_see_through),
        Off(R.string.glass_mode_off);
    }

    val mode = enumList(
        R.string.glass_mode,
        "glass_mode",
        GlassMode.FollowTheme
    )

    private val enabled: Boolean
        get() = mode.getValue() != GlassMode.Off

    val blurRadius = int(
        R.string.glass_blur_radius,
        "glass_blur_radius",
        20,
        0,
        40,
        "dp"
    ) { enabled }

    val tintOpacityLight: ManagedPreference.PInt
    val tintOpacityDark: ManagedPreference.PInt

    init {
        val (light, dark) = twinInt(
            R.string.glass_tint_opacity,
            R.string.light_mode_theme,
            "glass_tint_opacity",
            45,
            R.string.dark_mode_theme,
            "glass_tint_opacity_dark",
            55,
            0,
            100,
            "%"
        ) { enabled }
        tintOpacityLight = light
        tintOpacityDark = dark
    }

    val refraction = int(
        R.string.glass_refraction,
        "glass_refraction",
        50,
        0,
        100,
        "%"
    ) { enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }

    val dispersion = int(
        R.string.glass_dispersion,
        "glass_dispersion",
        15,
        0,
        100,
        "%"
    ) { enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }

    val highlight = int(
        R.string.glass_highlight,
        "glass_highlight",
        60,
        0,
        100,
        "%"
    ) { enabled }

    val pressMorph = switch(
        R.string.glass_press_morph,
        "glass_press_morph",
        true,
        R.string.glass_press_morph_summary
    ) { enabled }

    val dynamicBackdrop = switch(
        R.string.glass_dynamic_backdrop,
        "glass_dynamic_backdrop",
        true,
        R.string.glass_dynamic_backdrop_summary
    ) { enabled }

    val animateBackdrop = switch(
        R.string.glass_animate_backdrop,
        "glass_animate_backdrop",
        false,
        R.string.glass_animate_backdrop_summary
    ) { enabled && dynamicBackdrop.getValue() }

    val powerSaveFallback = switch(
        R.string.glass_power_save_fallback,
        "glass_power_save_fallback",
        true,
        R.string.glass_power_save_fallback_summary
    ) { enabled }

    val targetKeys = switch(
        R.string.glass_target_keys,
        "glass_target_keys",
        true
    ) { enabled }

    val targetToolbar = switch(
        R.string.glass_target_toolbar,
        "glass_target_toolbar",
        true
    ) { enabled }

    val targetCandidates = switch(
        R.string.glass_target_candidates,
        "glass_target_candidates",
        true
    ) { enabled }

    val targetPopups = switch(
        R.string.glass_target_popups,
        "glass_target_popups",
        true
    ) { enabled }

    val targetPanels = switch(
        R.string.glass_target_panels,
        "glass_target_panels",
        true
    ) { enabled }
}
