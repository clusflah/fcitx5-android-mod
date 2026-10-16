/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * A 🎤 on the space key while holding space records voice (the “按住空格语音输入” setting), so the
 * gesture can be found. Hidden in password fields, where holding space doesn't record
 * (CommonKeyActionListener). Centered on a blank space key (九宫格), else left of its label.
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import java.lang.ref.WeakReference

object SpaceVoiceIcon {

    private val voiceEnabled = AppPrefs.getInstance().keyboard.azureVoiceEnabled

    private val icons = mutableListOf<WeakReference<ImageView>>()

    /** false in password fields; set by KeyboardWindow when an input starts */
    var allowed = true
        set(value) {
            field = value
            refresh()
        }

    // ManagedPreference holds listeners weakly
    @Keep
    private val listener = ManagedPreference.OnChangeListener<Boolean> { _, _ -> refresh() }

    init {
        voiceEnabled.registerOnChangeListener(listener)
    }

    private val visibility get() = if (voiceEnabled.getValue() && allowed) View.VISIBLE else View.GONE

    fun attach(key: TextKeyView, theme: Theme, centered: Boolean) {
        val label = key.mainText
        val parent = label.parent as? ConstraintLayout ?: return
        if (label.id == View.NO_ID) label.id = View.generateViewId()
        val icon = ImageView(key.context).apply {
            setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
            imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
            isClickable = false
            isFocusable = false
            visibility = this@SpaceVoiceIcon.visibility
        }
        val size = key.dp(20)
        parent.addView(icon, ConstraintLayout.LayoutParams(size, size).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            if (centered) {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            } else {
                endToStart = label.id
                marginEnd = key.dp(4)
            }
        })
        icons.removeAll { it.get() == null }
        icons.add(WeakReference(icon))
    }

    private fun refresh() {
        val v = visibility
        icons.forEach { it.get()?.visibility = v }
    }
}
