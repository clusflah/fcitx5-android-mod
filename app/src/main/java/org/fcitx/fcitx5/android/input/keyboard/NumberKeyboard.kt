/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "Number"

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                NumPadKey("+", 0xffab, 23f, 0.15f, KeyDef.Appearance.Variant.Alternative),
                NumPadKey("1", 0xffb1, 30f, 0f),
                NumPadKey("2", 0xffb2, 30f, 0f),
                NumPadKey("3", 0xffb3, 30f, 0f),
                NumPadKey("/", 0xffaf, 23f, 0.15f, KeyDef.Appearance.Variant.Alternative),
            ),
            listOf(
                NumPadKey("-", 0xffad, 23f, 0.15f, KeyDef.Appearance.Variant.Alternative),
                NumPadKey("4", 0xffb4, 30f, 0f),
                NumPadKey("5", 0xffb5, 30f, 0f),
                NumPadKey("6", 0xffb6, 30f, 0f),
                MiniSpaceKey()
            ),
            listOf(
                NumPadKey("*", 0xffaa, 23f, 0.15f, KeyDef.Appearance.Variant.Alternative),
                NumPadKey("7", 0xffb7, 30f, 0f),
                NumPadKey("8", 0xffb8, 30f, 0f),
                NumPadKey("9", 0xffb9, 30f, 0f),
                BackspaceKey()
            ),
            listOf(
                LayoutSwitchKey("ABC", TextKeyboard.Name),
                NumPadKey(",", 0xffac, 23f, 0.1f, KeyDef.Appearance.Variant.Alternative),
                LayoutSwitchKey("!?#", PickerWindow.Key.Symbol.name, 0.13333f, KeyDef.Appearance.Variant.AltForeground),
                NumPadKey("0", 0xffb0, 30f, 0.23334f),
                NumPadKey("=", 0xffbd, 23f, 0.13333f, KeyDef.Appearance.Variant.AltForeground),
                NumPadKey(".", 0xffae, 23f, 0.1f, KeyDef.Appearance.Variant.Alternative),
                ReturnKey()
            )
        )

        /**
         * Returns direct-commit ASCII text for [NumberKeyboard] keys so digits and symbols
         * never enter an async input method engine (such as Rime T9) while switching layouts.
         */
        fun directCommitText(action: KeyAction): String? = when (action) {
            is KeyAction.SymAction -> when (val sym = action.sym.sym) {
                in 0xffb0..0xffb9 -> ('0' + (sym - 0xffb0)).toString()
                0xffaa -> "*"
                0xffab -> "+"
                0xffac -> ","
                0xffad -> "-"
                0xffae -> "."
                0xffaf -> "/"
                0xffbd -> "="
                FcitxKeyMapping.FcitxKey_space -> " "
                else -> null
            }
            is KeyAction.FcitxKeyAction ->
                action.act.takeIf { it.length == 1 && it[0] in '0'..'9' }
            else -> null
        }
    }

    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val space: TextKeyView by lazy { findViewById(R.id.button_mini_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    @SuppressLint("MissingSuperCall")
    override fun onPopupAction(action: PopupAction) {
        // leave empty on purpose to disable popup in NumberKeyboard
    }

}