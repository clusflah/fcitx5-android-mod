/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: "Jade" themes, the default look of this fork: a light grey keyboard with white letter keys,
 * grey function keys (return included), round white toolbar buttons and a jade green accent,
 * like the common Chinese phone keyboards. JadeDark is the same layout for dark mode.
 */
package org.fcitx.fcitx5.android.data.theme

object ThemePresetMod {

    /** the green of selected tabs, cursors and the voice waveform */
    const val JADE = 0xff2fbf8f.toInt()

    val JadeLight = Theme.Builtin(
        name = "JadeLight",
        isDark = false,
        backgroundColor = 0xffe3e5ea,
        barColor = 0xffe3e5ea,
        keyboardColor = 0xffe3e5ea,
        keyBackgroundColor = 0xfffcfcfc,
        keyTextColor = 0xff1f1f1f,
        candidateTextColor = 0xff1f1f1f,
        candidateLabelColor = 0xff1f1f1f,
        candidateCommentColor = 0xff8a8d93,
        altKeyBackgroundColor = 0xffb9bdc6,
        altKeyTextColor = 0xff2e2f33,
        // return is a plain grey function key with a text label
        accentKeyBackgroundColor = 0xffb9bdc6,
        accentKeyTextColor = 0xff1f1f1f,
        keyPressHighlightColor = 0x1a000000,
        keyShadowColor = 0xffc4c6cc,
        popupBackgroundColor = 0xfff5f6f8,
        popupTextColor = 0xff1f1f1f,
        spaceBarColor = 0xfffcfcfc,
        dividerColor = 0x14000000,
        clipboardEntryColor = 0xffffffff,
        genericActiveBackgroundColor = JADE,
        genericActiveForegroundColor = 0xffffffff
    )

    val JadeDark = Theme.Builtin(
        name = "JadeDark",
        isDark = true,
        backgroundColor = 0xff1b1c1f,
        barColor = 0xff1b1c1f,
        keyboardColor = 0xff1b1c1f,
        keyBackgroundColor = 0xff3a3c41,
        keyTextColor = 0xffeaeaec,
        candidateTextColor = 0xffeaeaec,
        candidateLabelColor = 0xffeaeaec,
        candidateCommentColor = 0xff8f9298,
        altKeyBackgroundColor = 0xff2a2c30,
        altKeyTextColor = 0xffd5d6d9,
        accentKeyBackgroundColor = 0xff2a2c30,
        accentKeyTextColor = 0xffeaeaec,
        keyPressHighlightColor = 0x24ffffff,
        keyShadowColor = 0xff121315,
        popupBackgroundColor = 0xff2a2c30,
        popupTextColor = 0xffeaeaec,
        spaceBarColor = 0xff3a3c41,
        dividerColor = 0x1fffffff,
        clipboardEntryColor = 0xff2e3034,
        genericActiveBackgroundColor = JADE,
        genericActiveForegroundColor = 0xffffffff
    )
}
