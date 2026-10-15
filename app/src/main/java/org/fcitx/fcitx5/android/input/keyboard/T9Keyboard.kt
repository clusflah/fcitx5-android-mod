/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 九宫格 keyboard. The keys 2–9 send digits, which Rime's nine-key schema (see RimeT9) turns into
 * pinyin; 1 separates syllables. Shown instead of the 26-key layout when Settings → Virtual Keyboard
 * → 九宫格 is on and Rime is the current input method.
 * While typing, the left column lists the pinyin the digits can stand for (see T9Pinyin); tapping
 * one replaces those digits with its letters in Rime's input.
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
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

    private val pinyinList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** covers the punctuation column (rows 1–3) while there are pinyin to choose from */
    private val pinyinColumn = ScrollView(context).apply {
        visibility = View.GONE
        isVerticalScrollBarEnabled = false
        setBackgroundColor(theme.altKeyBackgroundColor)
        addView(pinyinList)
    }

    /** Rime input not converted yet, as last read from the preedit */
    private var pendingRaw: String? = null

    init {
        // the key rows are the children added by BaseKeyboard; constraints need ids
        val rows = (0 until childCount).map { getChildAt(it) }
        rows.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        add(pinyinColumn, lParams(0, 0) {
            topToTop = rows[0].id
            bottomToBottom = rows[2].id
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentWidth = SIDE_WIDTH
        })
    }

    /** Called with Rime's preedit (the code being typed) whenever it changes. */
    fun onPreeditUpdate(preedit: String) {
        val raw = T9Pinyin.pendingInput(preedit)
        if (raw == pendingRaw) return
        pendingRaw = raw
        val options = raw?.let { T9Pinyin.options(it) }.orEmpty()
        pinyinList.removeAllViews()
        options.forEach { syllable ->
            pinyinList.addView(TextView(context).apply {
                text = syllable
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(theme.altKeyTextColor)
                minHeight = dp(42)
                setOnClickListener { choose(syllable) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        pinyinColumn.scrollTo(0, 0)
        pinyinColumn.visibility = if (options.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Replace the digits the syllable covers with its letters: delete from the end, retype. */
    private fun choose(syllable: String) {
        val raw = pendingRaw ?: return
        val (delete, type) = T9Pinyin.replacement(raw, syllable) ?: return
        val listener = keyActionListener ?: return
        repeat(delete) {
            listener.onKeyAction(
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)), KeyActionListener.Source.Keyboard
            )
        }
        type.forEach {
            listener.onKeyAction(KeyAction.FcitxKeyAction(it.toString()), KeyActionListener.Source.Keyboard)
        }
    }

    override fun onAttach() {
        super.onAttach()
        T9Pinyin.active = true
    }

    override fun onDetach() {
        T9Pinyin.active = false
        onPreeditUpdate("")
        super.onDetach()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }
}
