/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * The toolbar's shortcut buttons: which are shown and in what order, both set on one page
 * (设置 → 虚拟键盘 → 工具栏按钮, ToolbarButtonsActivity).
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference

enum class ToolbarButton(
    val id: String,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val show: (AppPrefs.Keyboard) -> ManagedPreference.PBool
) {
    Undo("undo", R.drawable.ic_baseline_undo_24, R.string.undo, { it.showUndoButton }),
    Redo("redo", R.drawable.ic_baseline_redo_24, R.string.redo, { it.showRedoButton }),
    CursorMove("cursor", R.drawable.ic_cursor_move, R.string.text_editing, { it.showCursorMoveButton }),
    Clipboard("clipboard", R.drawable.ic_clipboard, R.string.clipboard, { it.showClipboardButton }),
    AiAssist("ai_assist", R.drawable.ic_ai_assist, R.string.ai_assist, { it.showAiAssistButton }),
    AiPredict("ai_predict", R.drawable.ic_ai_predict, R.string.ai_predict, { it.showAiPredictButton }),
    Translate("translate", R.drawable.ic_translate, R.string.ai_translate, { it.showTranslateButton }),
    T9("t9", R.drawable.ic_t9, R.string.t9_keyboard, { it.showT9Button }),
    PasswordManager("bitwarden", R.drawable.ic_password_key, R.string.toolbar_button_bitwarden, { it.showPasswordManagerButton }),
    More("more", R.drawable.ic_baseline_more_horiz_24, R.string.status_area, { it.showMoreButton });

    companion object {
        /** buttons in the user's order; ones the saved order doesn't name keep their default place at the end */
        fun ordered(saved: String): List<ToolbarButton> {
            val byId = entries.associateBy { it.id }
            val listed = saved.split(',').mapNotNull { byId[it.trim()] }.distinct()
            return listed + entries.filter { it !in listed }
        }

        fun encode(order: List<ToolbarButton>) = order.joinToString(",") { it.id }
    }
}
