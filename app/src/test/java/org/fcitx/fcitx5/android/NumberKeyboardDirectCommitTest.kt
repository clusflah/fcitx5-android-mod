/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.NumLockState
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberKeyboardDirectCommitTest {

    @Test
    fun digits_and_keypad_operators_commit_directly() {
        for (digit in 0..9) {
            assertEquals(
                digit.toString(),
                NumberKeyboard.directCommitText(
                    KeyAction.SymAction(KeySym(0xffb0 + digit), NumLockState)
                )
            )
            assertEquals(
                digit.toString(),
                NumberKeyboard.directCommitText(KeyAction.FcitxKeyAction(digit.toString()))
            )
        }

        val operators = mapOf(
            0xffaa to "*",
            0xffab to "+",
            0xffac to ",",
            0xffad to "-",
            0xffae to ".",
            0xffaf to "/",
            0xffbd to "="
        )
        operators.forEach { (sym, expected) ->
            assertEquals(
                expected,
                NumberKeyboard.directCommitText(KeyAction.SymAction(KeySym(sym), NumLockState))
            )
        }

        assertEquals(
            " ",
            NumberKeyboard.directCommitText(
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space))
            )
        )
    }

    @Test
    fun non_commit_keys_return_null() {
        assertNull(
            NumberKeyboard.directCommitText(
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))
            )
        )
        assertNull(
            NumberKeyboard.directCommitText(
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return))
            )
        )
        assertNull(NumberKeyboard.directCommitText(KeyAction.FcitxKeyAction("a")))
    }
}
