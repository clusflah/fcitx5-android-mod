/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: settings tab for Liquid Glass in ThemeFragment, with custom backdrop image picker & live preview.
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
        const val KEY_BACKDROP_IMAGE = "glass_backdrop_image"
        var pendingOpenBackdropPicker = false
    }

    private lateinit var cropLauncher: ActivityResultLauncher<CropOption>
    private var pendingIsNewSource = true
    private var backdropPref: Preference? = null

    @Keep
    private val onGlassPrefChange = ManagedPreferenceProvider.OnChangeListener {
        refreshBackdropPreference()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cropLauncher = registerForActivityResult(CropContract()) { result ->
            if (result is CropResult.Success) {
                val ctx = requireContext()
                val isNew = pendingIsNewSource
                lifecycleScope.withLoadingDialog(ctx) {
                    GlassCustomBackdrop.saveFromCropResult(ctx, result, isNew)
                    refreshBackdropPreference()
                }
            }
        }
        ThemeManager.glassPrefs.registerOnChangeListener(onGlassPrefChange)
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = screen.context
        // Order existing items sequentially leaving slot 1 right below glass_mode for custom backdrop image
        for (i in 0 until screen.preferenceCount) {
            screen.getPreference(i).order = if (i == 0) 0 else i + 1
        }
        val pref = Preference(ctx).apply {
            key = KEY_BACKDROP_IMAGE
            order = 1
            isIconSpaceReserved = false
            setTitle(R.string.glass_backdrop_image)
            setOnPreferenceClickListener {
                onBackdropPreferenceClicked()
                true
            }
        }
        backdropPref = pref
        screen.addPreference(pref)
        refreshBackdropPreference()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (pendingOpenBackdropPicker) {
            pendingOpenBackdropPicker = false
            view.post { onBackdropPreferenceClicked() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBackdropPreference()
        if (pendingOpenBackdropPicker) {
            pendingOpenBackdropPicker = false
            view?.post { onBackdropPreferenceClicked() }
        }
    }

    override fun onDestroy() {
        ThemeManager.glassPrefs.unregisterOnChangeListener(onGlassPrefChange)
        super.onDestroy()
    }

    private fun refreshBackdropPreference() {
        val pref = backdropPref ?: return
        val enabled = ThemeManager.glassPrefs.mode.getValue() != GlassPrefs.GlassMode.Off
        val hasImg = GlassCustomBackdrop.hasImage()
        pref.isEnabled = enabled
        pref.setSummary(
            if (hasImg) R.string.glass_backdrop_image_set
            else R.string.glass_backdrop_image_unset
        )
        findPreference<Preference>("glass_backdrop_brightness")?.isEnabled = enabled && hasImg
        findPreference<Preference>("glass_dynamic_backdrop")?.isEnabled = enabled && !hasImg
        findPreference<Preference>("glass_animate_backdrop")?.isEnabled =
            enabled && !hasImg && ThemeManager.glassPrefs.dynamicBackdrop.getValue()
    }

    private fun resolveTargetCropSize(): Pair<Int, Int> {
        val fromPreview = (parentFragment as? ThemeFragment)?.previewSize()
        if (fromPreview != null && fromPreview.first > 0 && fromPreview.second > 0) {
            return fromPreview
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to (dm.heightPixels * 0.36f).toInt().coerceAtLeast(240)
    }

    fun onBackdropPreferenceClicked() {
        if (!isAdded) return
        val (w, h) = resolveTargetCropSize()
        if (!GlassCustomBackdrop.hasImage()) {
            pendingIsNewSource = true
            cropLauncher.launch(CropOption.New(w, h))
            return
        }
        val ctx = requireContext()
        val srcFile = GlassCustomBackdrop.sourceFile()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += getString(R.string.choose_image) to {
            pendingIsNewSource = true
            cropLauncher.launch(CropOption.New(w, h))
        }
        if (srcFile != null) {
            items += getString(R.string.recrop_image) to {
                pendingIsNewSource = false
                cropLauncher.launch(
                    CropOption.Edit(
                        width = w,
                        height = h,
                        sourceUri = Uri.fromFile(srcFile),
                        initialRect = GlassCustomBackdrop.initialCropRect(),
                        initialRotation = GlassCustomBackdrop.initialCropRotation()
                    )
                )
            }
        }
        items += getString(R.string.glass_backdrop_clear) to {
            GlassCustomBackdrop.clear()
            refreshBackdropPreference()
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.glass_backdrop_image)
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                items[which].second.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
