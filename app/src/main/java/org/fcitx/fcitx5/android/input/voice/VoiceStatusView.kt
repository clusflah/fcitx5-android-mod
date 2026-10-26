/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: push-to-talk status in the toolbar. While recording: a waveform that follows the microphone
 * level, with a short hint at the end; otherwise (识别中…, errors, 就地翻译) a line of text.
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod
import splitties.dimensions.dp

class VoiceStatusView(context: Context, theme: Theme) : FrameLayout(context) {

    private val text = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        isSingleLine = true
        setTextColor(theme.keyTextColor)
    }

    private val hint = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        isSingleLine = true
        setTextColor(theme.altKeyTextColor)
        setPadding(0, 0, dp(12), 0)
    }

    private val wave = VoiceWaveView(context, ThemePresetMod.JADE, barDp = 4f)

    init {
        addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wave, LayoutParams(LayoutParams.MATCH_PARENT, dp(28), Gravity.CENTER))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showText("")
    }

    /** a line of text, centered */
    fun showText(value: String) {
        text.text = value
        text.visibility = View.VISIBLE
        wave.visibility = View.GONE
        hint.visibility = View.GONE
    }

    /** the waveform, with [hintText] (how to finish) in small letters at the end */
    fun showWave(hintText: String) {
        hint.text = hintText
        text.visibility = View.GONE
        wave.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
    }

    /** raw microphone level, about 0..0.3 while speaking */
    fun setLevel(value: Float) = wave.setLevel(value)
}
