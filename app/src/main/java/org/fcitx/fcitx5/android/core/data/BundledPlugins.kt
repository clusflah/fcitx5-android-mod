/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * All-in-one build (branch all-in-one): the plugins built into the app. They are loaded like
 * installed plugins (DataManager), except that
 * - native libraries are the app's own (nativeLibraryDir stays blank),
 * - data files come from the app's assets under bundled/<name>/ (release builds; see app/build.gradle.kts),
 * - services and settings are classes of the app itself.
 */
package org.fcitx.fcitx5.android.core.data

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext

object BundledPlugins {

    private class Entry(
        val name: String,
        val domain: String?,
        @StringRes val description: Int,
        /** true: the plugin has data files (assets/bundled/<name>/), skipped if they are missing */
        val hasData: Boolean = true,
        val serviceClass: String? = null,
        val settingsActivity: String? = null
    )

    private const val CLIPBOARD_FILTER = "org.fcitx.fcitx5.android.plugin.clipboard_filter"
    private const val CLIPBOARD_SYNC = "org.fcitx.fcitx5.android.plugin.clipboard_sync"

    private val entries = listOf(
        Entry("anthy", "fcitx5-anthy", R.string.bundled_anthy),
        Entry("chewing", "fcitx5-chewing", R.string.bundled_chewing),
        Entry("hangul", "fcitx5-hangul", R.string.bundled_hangul),
        Entry("jyutping", "fcitx5-jyutping", R.string.bundled_jyutping),
        Entry("rime", "fcitx5-rime", R.string.bundled_rime),
        Entry("sayura", "fcitx5-sayura", R.string.bundled_sayura),
        Entry("thai", "fcitx5-libthai", R.string.bundled_thai),
        Entry("unikey", "fcitx5-unikey", R.string.bundled_unikey),
        Entry(
            "clipboard_filter", null, R.string.bundled_clipboard_filter, hasData = false,
            serviceClass = "$CLIPBOARD_FILTER.MainService"
        ),
        Entry(
            "clipboard_sync", null, R.string.bundled_clipboard_sync, hasData = false,
            serviceClass = "$CLIPBOARD_SYNC.MainService",
            settingsActivity = "$CLIPBOARD_SYNC.ui.SettingsActivity"
        ),
    )

    /** assets directory holding a plugin's data files and its descriptor.json */
    fun assetPrefix(name: String) = "bundled/$name/"

    private fun hasData(name: String) =
        runCatching { appContext.assets.list(assetPrefix(name).trimEnd('/')) }
            .getOrNull()?.isNotEmpty() == true

    /** The built-in plugins present in this build (release builds only, debug builds are plain). */
    fun descriptors(): List<PluginDescriptor> = entries
        .filter { BuildConfig.BUILD_TYPE == "release" }
        .filter { !it.hasData || hasData(it.name) }
        .map {
            PluginDescriptor(
                packageName = PluginDescriptor.pluginPackagePrefix + it.name,
                apiVersion = PluginDescriptor.pluginAPI,
                domain = it.domain,
                description = appContext.getString(it.description),
                hasService = it.serviceClass != null,
                versionName = "${appContext.getString(R.string.bundled_plugin)} ${BuildConfig.VERSION_NAME}",
                nativeLibraryDir = "",
                bundled = true,
                hasData = it.hasData,
                serviceClass = it.serviceClass,
                settingsActivity = it.settingsActivity
            )
        }
}
