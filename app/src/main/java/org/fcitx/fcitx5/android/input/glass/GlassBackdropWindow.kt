/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: Tier B cross-window blur sub-window positioned behind keyboardView on Android 12+ (API 31+).
 */
package org.fcitx.fcitx5.android.input.glass

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import kotlin.math.roundToInt

class GlassBackdropWindow(
    private val context: Context,
    private val theme: Theme,
    private val style: GlassStyle
) {
    private var dialog: Dialog? = null
    private var backgroundDrawable: GradientDrawable? = null
    private var failed = false
    private var imeWindowShown = false
    private val loc = IntArray(2)

    private val isSupported: Boolean
        get() = !failed &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                style.enabled &&
                style.seeThrough &&
                GlassCapabilities.isCrossWindowBlurEnabled

    fun onWindowShown(keyboardView: View, isFloating: Boolean) {
        imeWindowShown = true
        if (!isSupported) return
        keyboardView.post {
            if (imeWindowShown) {
                ensureShownAndSync(keyboardView, isFloating)
            }
        }
    }

    fun onWindowHidden() {
        imeWindowShown = false
        dismiss()
    }

    fun syncBounds(keyboardView: View, isFloating: Boolean) {
        if (!imeWindowShown || !isSupported) {
            dismiss()
            return
        }
        ensureShownAndSync(keyboardView, isFloating)
    }

    private fun ensureShownAndSync(keyboardView: View, isFloating: Boolean) {
        if (!keyboardView.isAttachedToWindow || keyboardView.width <= 0 || keyboardView.height <= 0) {
            return
        }
        val token = keyboardView.windowToken ?: return
        keyboardView.getLocationOnScreen(loc)
        val x = loc[0]
        val y = loc[1]
        val w = keyboardView.width
        val h = keyboardView.height
        val cornerRadius = if (isFloating) context.dp(16f) else 0f
        val blurPx = context.dp(style.blurRadiusDp.coerceAtLeast(12))

        runCatching {
            var dlg = dialog
            if (dlg == null) {
                dlg = Dialog(context).apply {
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    setCancelable(false)
                    setCanceledOnTouchOutside(false)
                    setContentView(View(context))
                }
                val win = dlg.window ?: return
                val bg = GradientDrawable().apply {
                    val veilAlpha = ((if (theme.isDark) 0.42f else 0.34f) * 255f).roundToInt()
                    val veilRgb = theme.backgroundColor and 0x00ffffff
                    setColor((veilAlpha shl 24) or veilRgb)
                    this.cornerRadius = cornerRadius
                }
                backgroundDrawable = bg
                win.setBackgroundDrawable(bg)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    win.setBackgroundBlurRadius(blurPx)
                }
                win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                win.attributes = win.attributes.apply {
                    this.token = token
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA
                    format = PixelFormat.TRANSLUCENT
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    gravity = Gravity.TOP or Gravity.LEFT
                    windowAnimations = 0
                    this.x = x
                    this.y = y
                    width = w
                    height = h
                }
                dlg.show()
                dialog = dlg
            } else {
                val win = dlg.window ?: return
                backgroundDrawable?.cornerRadius = cornerRadius
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    win.setBackgroundBlurRadius(blurPx)
                }
                val lp = win.attributes
                if (lp.x != x || lp.y != y || lp.width != w || lp.height != h) {
                    lp.x = x
                    lp.y = y
                    lp.width = w
                    lp.height = h
                    win.attributes = lp
                }
            }
        }.onFailure {
            failed = true
            dismiss()
        }
    }

    fun dismiss() {
        val dlg = dialog ?: return
        dialog = null
        backgroundDrawable = null
        runCatching {
            if (dlg.isShowing) dlg.dismiss()
        }
    }
}
