/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: runtime capability detection and system state listeners for Liquid Glass.
 */
package org.fcitx.fcitx5.android.input.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.utils.appContext
import java.util.function.Consumer

object GlassCapabilities {

    enum class Tier {
        /** Android 13+ (API 33+): AGSL RuntimeShader with SDF refraction, dispersion, and specular rim */
        Agsl33,
        /** Android 12–12L (API 31–32): Hardware blur / BitmapShader + multi-stop specular rim */
        Blur31,
        /** Android 6–11 (API 23–30): StackBlur backdrop + gradient specular rim */
        Legacy23
    }

    val tier: Tier = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Tier.Agsl33
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Tier.Blur31
        else -> Tier.Legacy23
    }

    @Volatile
    private var registered = false

    @Volatile
    var isPowerSaveMode: Boolean = false
        private set

    @Volatile
    var isCrossWindowBlurEnabled: Boolean = false
        private set

    fun ensureInitialized(context: Context = appContext) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
            val appCtx = context.applicationContext
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            isPowerSaveMode = pm?.isPowerSaveMode == true

            runCatching {
                appCtx.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            val nowPowerSave = pm?.isPowerSaveMode == true
                            if (nowPowerSave != isPowerSaveMode) {
                                isPowerSaveMode = nowPowerSave
                                ThemeManager.notifyGlassStateChanged()
                            }
                        }
                    },
                    IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (wm != null) {
                    isCrossWindowBlurEnabled = runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
                    runCatching {
                        wm.addCrossWindowBlurEnabledListener(
                            appCtx.mainExecutor,
                            Consumer { enabled ->
                                if (enabled != isCrossWindowBlurEnabled) {
                                    isCrossWindowBlurEnabled = enabled
                                    ThemeManager.notifyGlassStateChanged()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    val animationsEnabled: Boolean
        get() {
            if (AppPrefs.getInstance().advanced.disableAnimation.getValue()) return false
            val scale = runCatching {
                Settings.Global.getFloat(
                    appContext.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                )
            }.getOrDefault(1f)
            return scale > 0f
        }

    val isHighTextContrastEnabled: Boolean
        get() = runCatching {
            val am = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return@runCatching false
            val method = am.javaClass.getMethod("isHighTextContrastEnabled")
            method.invoke(am) as? Boolean ?: false
        }.getOrDefault(false)
}
