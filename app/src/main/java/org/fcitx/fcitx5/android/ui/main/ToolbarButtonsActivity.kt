/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 工具栏按钮: one page to show / hide every toolbar shortcut, put them in order, and pin some
 * left of the hide-keyboard button so they stay visible with the toolbar collapsed.
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
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
            addView(note(getString(R.string.toolbar_buttons_list_note)))
            list = LinearLayout(this@ToolbarButtonsActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(list)
        }
        setContentWithToolbar(ScrollView(this).apply { addView(content) }, getString(R.string.toolbar_buttons))
        rebuild()
    }

    private fun order() = ToolbarButton.ordered(prefs.toolbarButtonOrder.getValue())

    private fun pinned() = ToolbarButton.pinned(prefs.toolbarPinnedButtons.getValue())

    private fun move(button: ToolbarButton, by: Int) {
        val order = order().toMutableList()
        val i = order.indexOf(button)
        val j = i + by
        if (j !in order.indices) return
        order[i] = order[j].also { order[j] = button }
        prefs.toolbarButtonOrder.setValue(ToolbarButton.encode(order))
        rebuild()
    }

    private fun togglePin(button: ToolbarButton) {
        val pinned = pinned().toMutableSet()
        if (!pinned.remove(button)) pinned.add(button)
        prefs.toolbarPinnedButtons.setValue(ToolbarButton.encodePinned(pinned))
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()
        val order = order()
        val pinned = pinned()
        order.forEachIndexed { i, b -> list.addView(row(b, i, order.size, b in pinned)) }
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.7f
        setTextColor(fg)
        setPadding(0, dp(8), 0, dp(8))
    }

    /** icon, name, pin, ↑ ↓, and a switch for showing it */
    private fun row(b: ToolbarButton, i: Int, size: Int, pinned: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        addView(ImageView(context).apply {
            setImageResource(b.icon)
            imageTintList = fg
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = getString(b.label)
            textSize = 15f
            setTextColor(fg)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(iconButton(
            if (pinned) R.drawable.ic_baseline_push_pin_24 else R.drawable.ic_outline_push_pin_24,
            true, getString(if (pinned) R.string.toolbar_button_unpin else R.string.toolbar_button_pin)
        ) { togglePin(b) }.apply { if (!pinned) alpha = 0.5f })
        addView(iconButton(R.drawable.ic_baseline_arrow_upward_24, i > 0, getString(R.string.move_up)) { move(b, -1) })
        addView(iconButton(R.drawable.ic_baseline_arrow_downward_24, i < size - 1, getString(R.string.move_down)) { move(b, 1) })
        val show = b.show(prefs)
        val switch = SwitchCompat(context).apply {
            isChecked = show.getValue()
            setOnCheckedChangeListener { _, checked -> show.setValue(checked) }
        }
        addView(switch)
        setOnClickListener { switch.toggle() }
    }

    private fun iconButton(icon: Int, enabled: Boolean, desc: String, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = fg
            background = null
            contentDescription = desc
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.25f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
        }
}
