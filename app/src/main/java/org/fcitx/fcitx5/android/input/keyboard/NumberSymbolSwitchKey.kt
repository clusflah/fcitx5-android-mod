/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * The "?123" key of the 26-key layout: tap for the symbol picker, long-press for the number keyboard.
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
    // "!?#" with a small "123" hint (the long-press action), like the symbol hints on letter keys
    Appearance.AltText(
        displayText = "!?#",
        altText = "123",
        textSize = 16f,
        textStyle = Typeface.BOLD,
        percentWidth = percentWidth,
        variant = variant
    ),
    setOf(
        // same action as the "!?#" key on the number keyboard
        Behavior.Press(KeyAction.LayoutSwitchAction(PickerWindow.Key.Symbol.name)),
        // always the number keyboard; upstream's "" means "the last one used"
        Behavior.LongPress(KeyAction.LayoutSwitchAction(NumberKeyboard.Name))
    )
)
