/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 工具栏按钮: one page to show / hide every toolbar shortcut and put them in order,
 * plus the 🎤 that sits left of the hide-keyboard button.
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import splitties.dimensions.dp

class ToolbarButtonsActivity : AppCompatActivity() {

    private val prefs = AppPrefs.getInstance().keyboard
    private lateinit var list: LinearLayout
    private lateinit var fg: ColorStateList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        fg = a.getColorStateList(0) ?: ColorStateList.valueOf(0xff000000.toInt())
        a.recycle()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(header(getString(R.string.toolbar_buttons_voice_header)))
            addView(row(R.drawable.ic_baseline_keyboard_voice_24, getString(R.string.show_voice_input_button),
                prefs.showVoiceInputButton, null))
            addView(note(getString(R.string.show_voice_input_button_summary)))
            addView(header(getString(R.string.toolbar_buttons_list_header)))
            addView(note(getString(R.string.toolbar_buttons_list_note)))
            list = LinearLayout(this@ToolbarButtonsActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(list)
        }
        setContentWithToolbar(ScrollView(this).apply { addView(content) }, getString(R.string.toolbar_buttons))
        rebuild()
    }

    private fun order() = ToolbarButton.ordered(prefs.toolbarButtonOrder.getValue())

    private fun move(button: ToolbarButton, by: Int) {
        val order = order().toMutableList()
        val i = order.indexOf(button)
        val j = i + by
        if (j !in order.indices) return
        order[i] = order[j].also { order[j] = button }
        prefs.toolbarButtonOrder.setValue(ToolbarButton.encode(order))
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()
        val order = order()
        order.forEachIndexed { i, b ->
            list.addView(row(b.icon, getString(b.label), b.show(prefs), b to i to order.size))
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(colorAccent())
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun colorAccent(): Int {
        val a = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorAccent))
        val c = a.getColor(0, fg.defaultColor)
        a.recycle()
        return c
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.7f
        setTextColor(fg)
        setPadding(0, 0, 0, dp(4))
    }

    /** icon, name, optional ↑ ↓, and a switch bound to [show] */
    private fun row(
        icon: Int, label: String, show: ManagedPreference.PBool,
        position: Pair<Pair<ToolbarButton, Int>, Int>?
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = fg
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        addView(TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(fg)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (position != null) {
            val (bi, size) = position
            val (button, i) = bi
            addView(arrow(R.drawable.ic_baseline_arrow_upward_24, i > 0, getString(R.string.move_up)) { move(button, -1) })
            addView(arrow(R.drawable.ic_baseline_arrow_downward_24, i < size - 1, getString(R.string.move_down)) { move(button, 1) })
        }
        val switch = SwitchCompat(context).apply {
            isChecked = show.getValue()
            setOnCheckedChangeListener { _, checked -> show.setValue(checked) }
        }
        addView(switch)
        setOnClickListener { switch.toggle() }
    }

    private fun arrow(icon: Int, enabled: Boolean, desc: String, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = fg
            background = null
            contentDescription = desc
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.25f
            setOnClickListener { onClick() }
        }.also { it.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
}
