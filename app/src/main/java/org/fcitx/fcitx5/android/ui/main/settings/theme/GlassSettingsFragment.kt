/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: settings tab for Liquid Glass in ThemeFragment, with separate Light & Dark custom backdrop pickers.
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.GlassCustomBackdrop
import org.fcitx.fcitx5.android.data.theme.GlassPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropContract
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropOption
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropResult

class GlassSettingsFragment : ManagedPreferenceFragment(ThemeManager.glassPrefs) {

    companion object {
        const val KEY_BACKDROP_IMAGE_LIGHT = "glass_backdrop_image_light"
        const val KEY_BACKDROP_IMAGE_DARK = "glass_backdrop_image_dark"
        private const val STATE_TARGET_IS_DARK = "state_target_is_dark"
        private const val STATE_IS_NEW_SOURCE = "state_is_new_source"
        var pendingOpenBackdropDark: Boolean? = null
    }

    private lateinit var cropLauncher: ActivityResultLauncher<CropOption>
    private var pendingTargetIsDark = false
    private var pendingIsNewSource = true
    private var backdropLightPref: Preference? = null
    private var backdropDarkPref: Preference? = null

    @Keep
    private val onGlassPrefChange = ManagedPreferenceProvider.OnChangeListener {
        refreshBackdropPreferences()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            pendingTargetIsDark = savedInstanceState.getBoolean(STATE_TARGET_IS_DARK, false)
            pendingIsNewSource = savedInstanceState.getBoolean(STATE_IS_NEW_SOURCE, true)
        }
        cropLauncher = registerForActivityResult(CropContract()) { result ->
            if (result is CropResult.Success) {
                val ctx = requireContext()
                val targetIsDark = pendingTargetIsDark
                val isNew = pendingIsNewSource
                lifecycleScope.withLoadingDialog(ctx) {
                    GlassCustomBackdrop.saveFromCropResult(ctx, targetIsDark, result, isNew)
                    refreshBackdropPreferences()
                    (parentFragment as? ThemeFragment)?.previewBackdropSlot(targetIsDark)
                }
            }
        }
        ThemeManager.glassPrefs.registerOnChangeListener(onGlassPrefChange)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TARGET_IS_DARK, pendingTargetIsDark)
        outState.putBoolean(STATE_IS_NEW_SOURCE, pendingIsNewSource)
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = screen.context
        // Leave slots 1 & 2 right below glass_mode for Light and Dark custom backdrop images
        for (i in 0 until screen.preferenceCount) {
            screen.getPreference(i).order = if (i == 0) 0 else i + 2
        }
        val lightPref = Preference(ctx).apply {
            key = KEY_BACKDROP_IMAGE_LIGHT
            order = 1
            isIconSpaceReserved = false
            setTitle(R.string.glass_backdrop_image_light)
            setOnPreferenceClickListener {
                onBackdropPreferenceClicked(isDark = false)
                true
            }
        }
        val darkPref = Preference(ctx).apply {
            key = KEY_BACKDROP_IMAGE_DARK
            order = 2
            isIconSpaceReserved = false
            setTitle(R.string.glass_backdrop_image_dark)
            setOnPreferenceClickListener {
                onBackdropPreferenceClicked(isDark = true)
                true
            }
        }
        backdropLightPref = lightPref
        backdropDarkPref = darkPref
        screen.addPreference(lightPref)
        screen.addPreference(darkPref)
        refreshBackdropPreferences()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        consumePendingOpen(view)
    }

    override fun onResume() {
        super.onResume()
        refreshBackdropPreferences()
        view?.let { consumePendingOpen(it) }
    }

    private fun consumePendingOpen(view: View) {
        val targetDark = pendingOpenBackdropDark ?: return
        pendingOpenBackdropDark = null
        view.post { onBackdropPreferenceClicked(targetDark) }
    }

    override fun onDestroy() {
        ThemeManager.glassPrefs.unregisterOnChangeListener(onGlassPrefChange)
        super.onDestroy()
    }

    private fun refreshBackdropPreferences() {
        val gp = ThemeManager.glassPrefs
        val enabled = gp.mode.getValue() != GlassPrefs.GlassMode.Off
        val hasLight = GlassCustomBackdrop.hasImage(false)
        val hasDark = GlassCustomBackdrop.hasImage(true)
        val hasAny = hasLight || hasDark
        val hasBoth = hasLight && hasDark

        backdropLightPref?.apply {
            isEnabled = enabled
            setSummary(
                if (hasLight) R.string.glass_backdrop_image_set
                else R.string.glass_backdrop_image_unset_light
            )
        }
        backdropDarkPref?.apply {
            isEnabled = enabled
            setSummary(
                if (hasDark) R.string.glass_backdrop_image_set
                else R.string.glass_backdrop_image_unset_dark
            )
        }
        findPreference<Preference>("glass_backdrop_brightness")?.isEnabled = enabled && hasAny
        findPreference<Preference>("glass_dynamic_backdrop")?.isEnabled = enabled && !hasBoth
        findPreference<Preference>("glass_animate_backdrop")?.isEnabled =
            enabled && !hasBoth && gp.dynamicBackdrop.getValue()
    }

    private fun resolveTargetCropSize(): Pair<Int, Int> {
        val fromPreview = (parentFragment as? ThemeFragment)?.previewSize()
        if (fromPreview != null && fromPreview.first > 0 && fromPreview.second > 0) {
            return fromPreview
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to (dm.heightPixels * 0.36f).toInt().coerceAtLeast(240)
    }

    fun onBackdropPreferenceClicked(isDark: Boolean) {
        if (!isAdded) return
        (parentFragment as? ThemeFragment)?.previewBackdropSlot(isDark)
        val (w, h) = resolveTargetCropSize()
        val hasCurrent = GlassCustomBackdrop.hasImage(isDark)
        val hasOther = GlassCustomBackdrop.hasImage(!isDark)

        // When neither slot has an image yet, open the system photo picker directly
        if (!hasCurrent && !hasOther) {
            pendingTargetIsDark = isDark
            pendingIsNewSource = true
            cropLauncher.launch(CropOption.New(w, h))
            return
        }

        val ctx = requireContext()
        val titleRes = if (isDark) R.string.glass_backdrop_image_dark else R.string.glass_backdrop_image_light
        val copyLabelRes = if (isDark) R.string.glass_backdrop_copy_from_light else R.string.glass_backdrop_copy_from_dark
        val items = mutableListOf<Pair<String, () -> Unit>>()

        items += getString(R.string.choose_image) to {
            pendingTargetIsDark = isDark
            pendingIsNewSource = true
            cropLauncher.launch(CropOption.New(w, h))
        }
        if (hasCurrent) {
            val srcFile = GlassCustomBackdrop.sourceFile(isDark)
            if (srcFile != null) {
                items += getString(R.string.recrop_image) to {
                    pendingTargetIsDark = isDark
                    pendingIsNewSource = false
                    cropLauncher.launch(
                        CropOption.Edit(
                            width = w,
                            height = h,
                            sourceUri = Uri.fromFile(srcFile),
                            initialRect = GlassCustomBackdrop.initialCropRect(isDark),
                            initialRotation = GlassCustomBackdrop.initialCropRotation(isDark)
                        )
                    )
                }
            }
        }
        if (hasOther) {
            items += getString(copyLabelRes) to {
                lifecycleScope.launch {
                    GlassCustomBackdrop.copyFromOtherMode(isDark)
                    refreshBackdropPreferences()
                    (parentFragment as? ThemeFragment)?.previewBackdropSlot(isDark)
                }
            }
        }
        if (hasCurrent) {
            items += getString(R.string.glass_backdrop_clear) to {
                GlassCustomBackdrop.clear(isDark)
                refreshBackdropPreferences()
                (parentFragment as? ThemeFragment)?.previewBackdropSlot(isDark)
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle(titleRes)
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                items[which].second.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
