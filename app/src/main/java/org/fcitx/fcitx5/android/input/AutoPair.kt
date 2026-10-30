/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: typing the opening half of a bracket or quote ("(", "（", "【", "《", "“" …) commits the whole
 * pair with the caret in between; typing the closing half right before the same character just steps
 * over it. With text selected, the pair wraps the selection. Kept in its own file so
 * FcitxInputMethodService.commitText only gains one line.
 */
package org.fcitx.fcitx5.android.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

object AutoPair {

    /** opening → closing; "<" and "'" are left out: less-than and apostrophes are typed alone far more often */
    private val PAIRS = mapOf(
        "(" to ")", "[" to "]", "{" to "}", "\"" to "\"",
        "（" to "）", "［" to "］", "｛" to "｝", "【" to "】", "〔" to "〕",
        "《" to "》", "〈" to "〉", "「" to "」", "『" to "』", "“" to "”", "‘" to "’",
    )
    private val CLOSINGS = PAIRS.values.toSet()

    /**
     * Commits [text] as a pair (or steps over its closing half) when it is one half of a pair.
     * @return true when it handled the commit, false to commit [text] as usual
     */
    fun commit(service: FcitxInputMethodService, ic: InputConnection, info: EditorInfo?, text: String): Boolean {
        if (!plainText(info)) return false
        val close = PAIRS[text]
        // closing half typed right before the same character (the one put there with its opening half): step over it
        if (text in CLOSINGS && ic.getTextAfterCursor(1, 0)?.toString() == text && ic.getSelectedText(0).isNullOrEmpty()) {
            val (start, _) = service.currentInputSelection
            ic.setSelection(start + 1, start + 1)
            return true
        }
        if (close == null) return false
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) service.commitText(text + selected + close)
        else service.commitText(text + close, text.length)
        return true
    }

    /** only in ordinary text fields: never in passwords, numbers, phone numbers or dates */
    private fun plainText(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }
}
