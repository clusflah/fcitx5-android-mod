/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * The "?123" key of the 26-key layout: tap for the number keyboard, long-press for the symbol picker.
 * Kept in its own file so the upstream TextKeyboard layout only changes by one line.
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow

class NumberSymbolSwitchKey(
    percentWidth: Float = 0.15f,
    variant: Variant = Variant.Alternative
) : KeyDef(
    // "123" with a small "!?#" hint, like the symbol hints on letter keys
    Appearance.AltText(
        displayText = "123",
        altText = "!?#",
        textSize = 16f,
        textStyle = Typeface.BOLD,
        percentWidth = percentWidth,
        variant = variant
    ),
    setOf(
        // "" = the number keyboard, same as upstream's "?123"
        Behavior.Press(KeyAction.LayoutSwitchAction("")),
        // same action as the "!?#" key on the number keyboard
        Behavior.LongPress(KeyAction.LayoutSwitchAction(PickerWindow.Key.Symbol.name))
    )
)
