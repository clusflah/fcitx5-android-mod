/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextKeyboardDigitSwipeTest {

    @Test
    fun swipe_and_popup_digits_convert_to_commit_action() {
        for (digit in '0'..'9') {
            val raw = KeyAction.FcitxKeyAction(digit.toString())

            val (fromSwipeNone, resetNone) = TextKeyboard.transformKeyAction(
                raw,
                KeyActionListener.Source.Keyboard,
                TextKeyboard.CapsState.None
            )
            assertEquals(KeyAction.CommitAction(digit.toString()), fromSwipeNone)
            assertFalse(resetNone)

            val (fromSwipeOnce, resetOnce) = TextKeyboard.transformKeyAction(
                raw,
                KeyActionListener.Source.Keyboard,
                TextKeyboard.CapsState.Once
            )
            assertEquals(KeyAction.CommitAction(digit.toString()), fromSwipeOnce)
            assertTrue(resetOnce)

            val (fromPopupOnce, resetPopupOnce) = TextKeyboard.transformKeyAction(
                raw,
                KeyActionListener.Source.Popup,
                TextKeyboard.CapsState.Once
            )
            assertEquals(KeyAction.CommitAction(digit.toString()), fromPopupOnce)
            assertTrue(resetPopupOnce)
        }
    }

    @Test
    fun letters_and_punctuation_preserve_caps_and_engine_routing() {
        val letter = KeyAction.FcitxKeyAction("Q")

        val (lower, resetLower) = TextKeyboard.transformKeyAction(
            letter,
            KeyActionListener.Source.Keyboard,
            TextKeyboard.CapsState.None
        )
        assertEquals(KeyAction.FcitxKeyAction("q", letter.code), lower)
        assertFalse(resetLower)

        val (upperOnce, resetOnce) = TextKeyboard.transformKeyAction(
            letter,
            KeyActionListener.Source.Keyboard,
            TextKeyboard.CapsState.Once
        )
        assertEquals(
            KeyAction.FcitxKeyAction(
                "Q",
                letter.code,
                KeyStates(KeyState.Virtual, KeyState.Shift)
            ),
            upperOnce
        )
        assertTrue(resetOnce)

        val punct = KeyAction.FcitxKeyAction("@")
        val (punctOut, resetPunct) = TextKeyboard.transformKeyAction(
            punct,
            KeyActionListener.Source.Keyboard,
            TextKeyboard.CapsState.None
        )
        assertEquals(KeyAction.FcitxKeyAction("@", punct.code), punctOut)
        assertFalse(resetPunct)
    }
}
