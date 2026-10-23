/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: "键盘选择" panel, opened by the toolbar's keyboard button: a rounded card with one tile per
 * layout (九宫格拼音 / 全键盘拼音 on Rime, then every other enabled input method) and 键盘管理.
 * The tile in use is white with a green icon and label.
 */
package org.fcitx.fcitx5.android.input.status

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.RimeT9
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class KeyboardPickerWindow : InputWindow.ExtendedInputWindow<KeyboardPickerWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val t9 = AppPrefs.getInstance().keyboard.t9Keyboard

    private class Tile(val label: String, val icon: Int, val selected: Boolean, val onClick: () -> Unit)

    private lateinit var card: LinearLayout

    override fun onCreateView(): View {
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = context.dp(16f)
                setColor(theme.popupBackgroundColor)
            }
            val p = context.dp(6)
            setPadding(p, p, p, p)
        }
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(theme.keyboardColor)
            addView(FrameLayout(context).apply {
                val m = context.dp(12)
                setPadding(m, m, m, m)
                addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    override fun onAttached() {
        fcitx.launchOnReady { api ->
            val enabled = api.enabledIme().toList()
            val current = api.currentIme().uniqueName
            service.lifecycleScope.launch { show(enabled, current) }
        }
    }

    override fun onDetached() {}

    private fun pick(block: suspend (org.fcitx.fcitx5.android.core.FcitxAPI) -> Unit) {
        fcitx.launchOnReady { api ->
            block(api)
            service.lifecycleScope.launch { windowManager.attachWindow(KeyboardWindow) }
        }
    }

    private fun show(enabled: List<InputMethodEntry>, current: String) {
        val tiles = mutableListOf<Tile>()
        val rime = enabled.find { it.uniqueName == RimeT9.RIME }
        if (rime != null) {
            val onRime = current == RimeT9.RIME
            tiles += Tile("九宫格拼音", R.drawable.ic_t9, onRime && t9.getValue()) {
                t9.setValue(true)
                pick { it.activateIme(RimeT9.RIME) }
            }
            tiles += Tile("全键盘拼音", R.drawable.ic_baseline_keyboard_24, onRime && !t9.getValue()) {
                t9.setValue(false)
                pick { it.activateIme(RimeT9.RIME) }
            }
        }
        enabled.filter { it !== rime }.forEach { entry ->
            tiles += Tile(entry.displayName, R.drawable.ic_baseline_language_24, current == entry.uniqueName) {
                pick { it.activateIme(entry.uniqueName) }
            }
        }
        tiles += Tile("键盘管理", R.drawable.ic_baseline_settings_24, false) {
            AppUtil.launchMainToInputMethodList(context)
        }
        card.removeAllViews()
        tiles.chunked(COLUMNS).forEach { row ->
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { addView(tileView(it), LinearLayout.LayoutParams(0, context.dp(66), 1f).apply {
                    setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
                }) }
                // keep the last row's tiles the same width as the others
                repeat(COLUMNS - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            })
        }
    }

    private fun tileView(tile: Tile): View {
        val color = if (tile.selected) theme.genericActiveBackgroundColor else theme.keyTextColor
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = context.dp(12f)
                setColor(if (tile.selected) theme.keyBackgroundColor else android.graphics.Color.TRANSPARENT)
            }
            foreground = pressHighlightDrawable(theme.keyPressHighlightColor)
            addView(ImageView(context).apply {
                setImageResource(tile.icon)
                imageTintList = ColorStateList.valueOf(color)
            }, LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            // full width and centered, so the label sits right under the icon whatever its length
            addView(TextView(context).apply {
                text = tile.label
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                includeFontPadding = false
                setTextColor(if (tile.selected) color else theme.altKeyTextColor)
                setPadding(context.dp(2), context.dp(6), context.dp(2), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                InputFeedbacks.hapticFeedback(it)
                tile.onClick()
            }
        }
    }

    companion object {
        private const val COLUMNS = 4
    }
}
