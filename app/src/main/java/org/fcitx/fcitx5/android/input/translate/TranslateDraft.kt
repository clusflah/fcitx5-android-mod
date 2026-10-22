/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: while the in-keyboard translate panel is open, what the keyboard types goes into this draft
 * instead of the app: FcitxInputMethodService hands commits, the inline preedit, backspace and
 * return to it first. Return translates the draft and sends the translation to the app.
 */
package org.fcitx.fcitx5.android.input.translate

object TranslateDraft {

    var active = false
        private set

    val text = StringBuilder()

    /** fcitx's preedit, shown after [text] while typing */
    var preedit = ""
        private set

    /** redraw the panel */
    var onChange: (() -> Unit)? = null

    /** translate and send; set by the panel */
    var onSubmit: (() -> Unit)? = null

    /** open / close the panel of the current input view; set by InputView */
    var togglePanel: (() -> Unit)? = null
    var closePanel: (() -> Unit)? = null

    fun start() {
        active = true
        clear()
    }

    fun stop() {
        active = false
        clear()
    }

    fun clear() {
        text.clear()
        preedit = ""
        onChange?.invoke()
    }

    /** @return true when the text was taken, i.e. must not reach the app */
    fun commit(s: String): Boolean {
        if (!active) return false
        text.append(s)
        preedit = ""
        onChange?.invoke()
        return true
    }

    fun composing(s: String): Boolean {
        if (!active) return false
        preedit = s
        onChange?.invoke()
        return true
    }

    fun backspace(): Boolean {
        if (!active) return false
        if (text.isNotEmpty()) {
            val cp = text.codePointBefore(text.length)
            text.setLength(text.length - Character.charCount(cp))
            onChange?.invoke()
        }
        return true
    }

    fun submit(): Boolean {
        if (!active) return false
        onSubmit?.invoke()
        return true
    }
}
