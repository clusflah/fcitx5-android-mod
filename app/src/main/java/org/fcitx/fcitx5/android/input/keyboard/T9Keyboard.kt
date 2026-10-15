/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 九宫格 keyboard. The keys 2–9 send digits, which Rime's nine-key schema (see RimeT9) turns into
 * pinyin; 1 separates syllables. Shown instead of the 26-key layout when Settings → Virtual Keyboard
 * → 九宫格 is on and Rime is the current input method.
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.views.imageResource

/** A letter group key: tap sends the digit; swipe types the digit itself. */
class T9Key(letters: String, digit: Int) : KeyDef(
    Appearance.AltText(
        displayText = letters,
        altText = digit.toString(),
        textSize = 20f,
        percentWidth = T9Keyboard.KEY_WIDTH
    ),
    setOf(
        Behavior.Press(KeyAction.FcitxKeyAction(digit.toString())),
        Behavior.Swipe(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + digit), NumLockState))
    ),
    arrayOf(Popup.AltPreview(letters, digit.toString()))
)

/** Left column: shows the Chinese mark, sends the ASCII one so Rime commits the input and converts it. */
class T9PunctKey(display: String, send: String) : KeyDef(
    Appearance.Text(
        displayText = display,
        textSize = 20f,
        percentWidth = T9Keyboard.SIDE_WIDTH,
        variant = Variant.Alternative
    ),
    setOf(Behavior.Press(KeyAction.FcitxKeyAction(send))),
    arrayOf(Popup.Preview(display))
)

/** Text key on the right column running an arbitrary action. */
class T9ActionKey(display: String, action: KeyAction) : KeyDef(
    Appearance.Text(
        displayText = display,
        textSize = 16f,
        textStyle = Typeface.BOLD,
        percentWidth = T9Keyboard.SIDE_WIDTH,
        variant = Variant.Alternative
    ),
    setOf(Behavior.Press(action))
)

@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "T9"

        /** LayoutSwitchAction target of the "全键" key: turns the 九宫格 setting off */
        const val ToFullKeyboard = "T9:full"

        const val SIDE_WIDTH = 0.15f
        const val KEY_WIDTH = 0.23333f

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                T9PunctKey("，", ","),
                // 1 = syllable separator while typing (the schema maps it to '), punctuation otherwise
                T9Key("分词", 1),
                T9Key("ABC", 2),
                T9Key("DEF", 3),
                BackspaceKey(SIDE_WIDTH)
            ),
            listOf(
                T9PunctKey("。", "."),
                T9Key("GHI", 4),
                T9Key("JKL", 5),
                T9Key("MNO", 6),
                // Escape clears what is being typed
                T9ActionKey("重输", KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Escape)))
            ),
            listOf(
                T9PunctKey("？", "?"),
                T9Key("PQRS", 7),
                T9Key("TUV", 8),
                T9Key("WXYZ", 9),
                T9ActionKey("全键", KeyAction.LayoutSwitchAction(ToFullKeyboard))
            ),
            listOf(
                LayoutSwitchKey("!?#", PickerWindow.Key.Symbol.name, SIDE_WIDTH, Variant.AltForeground),
                LayoutSwitchKey("123", NumberKeyboard.Name, SIDE_WIDTH),
                LanguageKey(),
                SpaceKey(),
                ReturnKey(SIDE_WIDTH)
            )
        )
    }

    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }
}
