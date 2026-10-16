/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 九宫格 keyboard. The keys 2–9 send digits, which Rime's nine-key schema (see RimeT9) turns into
 * pinyin; 1 separates syllables. Shown instead of the 26-key layout when Settings → Virtual Keyboard
 * → 九宫格 is on and Rime is the current input method.
 * While typing, the left column lists the pinyin the digits can stand for (see T9Pinyin); tapping
 * one replaces those digits with its letters in Rime's input. The preedit line shows the first
 * candidate's pinyin instead of digits, and candidates don't repeat it (PreeditComponent,
 * CandidateItemUi).
 * With nothing typed, 1 opens a row of symbols over the toolbar (SymbolStrip, KawaiiBarComponent).
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
import splitties.views.imageResource

/** A letter group key: tap sends the digit; swipe types the digit itself. */
class T9Key(letters: String, digit: Int, display: String = letters, viewId: Int = -1) : KeyDef(
    Appearance.Text(
        displayText = display,
        textSize = 20f,
        percentWidth = T9Keyboard.KEY_WIDTH,
        viewId = viewId
    ),
    setOf(
        Behavior.Press(KeyAction.FcitxKeyAction(digit.toString())),
        Behavior.Swipe(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + digit), NumLockState))
    ),
    arrayOf(Popup.AltPreview(display, digit.toString()))
)

/** Text key running an arbitrary action. */
class T9ActionKey(
    display: String, action: KeyAction, longPress: KeyAction? = null, width: Float = T9Keyboard.SIDE_WIDTH,
    variant: Variant = Variant.Alternative
) : KeyDef(
    Appearance.Text(
        displayText = display,
        textSize = 16f,
        percentWidth = width,
        variant = variant
    ),
    setOfNotNull(Behavior.Press(action), longPress?.let { Behavior.LongPress(it) })
)

/** Empty cell under one of the views that span several rows (left column, return key). */
class T9Placeholder(viewId: Int) : KeyDef(
    Appearance.Text(
        displayText = "",
        textSize = 16f,
        percentWidth = T9Keyboard.SIDE_WIDTH,
        variant = Variant.Alternative,
        viewId = viewId
    ),
    emptySet()
)

/**
 * Laid out like the common Chinese nine-key keypads:
 * ```
 *  ，  | @#   | ABC  | DEF  | ⌫
 *  。  | GHI  | JKL  | MNO  | 重输
 *  ？  | PQRS | TUV  | WXYZ | ⏎
 *  ！  |      |      |      | ⏎
 * 符号 | 123  | space       | 中/英 | ⏎
 * ```
 * The left column (four marks over three rows, or the pinyin list while typing) and the return key
 * (two rows) span rows, which BaseKeyboard can't do: they are views laid over placeholder keys.
 */
@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "T9"

        /** LayoutSwitchAction target that turns the 九宫格 setting off (the toolbar button does the same) */
        const val ToFullKeyboard = "T9:full"

        const val SIDE_WIDTH = 0.15f
        const val KEY_WIDTH = 0.23333f

        /** left column marks: shown Chinese, sent ASCII so Rime commits the input and converts them */
        private val MARKS = listOf("，" to ",", "。" to ".", "？" to "?", "！" to "!")

        private val LEFT_IDS = IntArray(3) { View.generateViewId() }
        private val RIGHT_IDS = IntArray(2) { View.generateViewId() }

        /** the 1 key: shows "@#", or "'词" while typing */
        private val SEPARATOR_ID = View.generateViewId()

        /** shown in the symbol row when 1 is tapped with nothing typed */
        val SYMBOLS = listOf("@", "#", "*", "+", "…", "~", "（", "、", "/")

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                T9Placeholder(LEFT_IDS[0]),
                // 1: syllable separator while typing (the schema maps it to '), symbol row otherwise
                T9Key("@#", 1, viewId = SEPARATOR_ID),
                T9Key("ABC", 2),
                T9Key("DEF", 3),
                BackspaceKey(SIDE_WIDTH)
            ),
            listOf(
                T9Placeholder(LEFT_IDS[1]),
                T9Key("GHI", 4),
                T9Key("JKL", 5),
                T9Key("MNO", 6),
                // Escape clears what is being typed
                T9ActionKey("重输", KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Escape)))
            ),
            listOf(
                T9Placeholder(LEFT_IDS[2]),
                T9Key("PQRS", 7),
                T9Key("TUV", 8),
                T9Key("WXYZ", 9),
                T9Placeholder(RIGHT_IDS[0])
            ),
            listOf(
                LayoutSwitchKey("符号", PickerWindow.Key.Symbol.name, SIDE_WIDTH),
                LayoutSwitchKey("123", NumberKeyboard.Name, SIDE_WIDTH, Variant.Normal),
                SpaceKey(),
                // 中/英: next input method (e.g. English, which brings back the 26-key layout)
                T9ActionKey(
                    "中/英", KeyAction.LangSwitchAction, KeyAction.ShowInputMethodPickerAction,
                    variant = Variant.Normal
                ),
                T9Placeholder(RIGHT_IDS[1])
            )
        )
    }

    private val columnList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** left column over rows 1–3: the marks ，。？！, or while typing the pinyin to choose from */
    private val leftColumn = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        setBackgroundColor(theme.altKeyBackgroundColor)
        addView(columnList)
    }

    /** return key spanning the last two rows */
    val `return` = ImageKeyView(context, theme, ReturnKey(SIDE_WIDTH).appearance as KeyDef.Appearance.Image).apply {
        soundEffect = InputFeedbacks.SoundEffect.Return
        setOnClickListener {
            keyActionListener?.onKeyAction(
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return)), KeyActionListener.Source.Keyboard
            )
        }
    }

    private val separatorKey get() = findViewById<TextKeyView>(SEPARATOR_ID)

    /** The symbol row over the toolbar; KeyboardWindow sets it to KawaiiBarComponent's. */
    interface SymbolStrip {
        val isShown: Boolean
        fun show(symbols: List<String>, onPick: (String) -> Unit)
        fun hide()
    }

    var symbolStrip: SymbolStrip? = null

    /** whether Rime has input being typed */
    private var typing = false

    /** Rime input not converted yet, as last read from the preedit */
    private var pendingRaw: String? = null

    init {
        // the key rows are the children added by BaseKeyboard; constraints need ids
        val rows = (0 until childCount).map { getChildAt(it) }
        rows.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        (LEFT_IDS + RIGHT_IDS).forEach { findViewById<View>(it)?.visibility = View.INVISIBLE }
        add(leftColumn, lParams(0, 0) {
            topToTop = rows[0].id
            bottomToBottom = rows[2].id
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentWidth = SIDE_WIDTH
        })
        add(`return`, lParams(0, 0) {
            topToTop = rows[2].id
            bottomToBottom = rows[3].id
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
            matchConstraintPercentWidth = SIDE_WIDTH
        })
        showMarks()
    }

    private fun columnItem(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        setOnClickListener {
            InputFeedbacks.hapticFeedback(it)
            InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
            onClick()
        }
    }

    private fun sendKey(key: String) =
        keyActionListener?.onKeyAction(KeyAction.FcitxKeyAction(key), KeyActionListener.Source.Keyboard)

    /** ，。？！ filling the column */
    private fun showMarks() {
        columnList.removeAllViews()
        MARKS.forEach { (shown, sent) ->
            columnList.addView(columnItem(shown) { sendKey(sent) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        leftColumn.scrollTo(0, 0)
    }

    /** Called with Rime's preedit (the code being typed) whenever it changes. */
    fun onPreeditUpdate(preedit: String) {
        typing = preedit.isNotEmpty()
        separatorKey?.mainText?.text = if (typing) "'词" else "@#"
        if (typing) symbolStrip?.hide()
        val raw = T9Pinyin.pendingInput(preedit)
        if (raw == pendingRaw) return
        pendingRaw = raw
        val options = raw?.let { T9Pinyin.options(it) }.orEmpty()
        if (options.isEmpty()) {
            showMarks()
            return
        }
        columnList.removeAllViews()
        options.forEach { syllable ->
            columnList.addView(columnItem(syllable) { choose(syllable) }.apply { minHeight = dp(42) },
                LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        leftColumn.scrollTo(0, 0)
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
        type.forEach { sendKey(it.toString()) }
    }

    @SuppressLint("MissingSuperCall") // the 1 key may open the symbol row instead
    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        // 1 with nothing typed: toggle the symbol row instead of sending it to Rime
        if (action is KeyAction.FcitxKeyAction && action.act == "1" && !typing) {
            val strip = symbolStrip
            if (strip != null) {
                if (strip.isShown) strip.hide()
                else strip.show(SYMBOLS) {
                    keyActionListener?.onKeyAction(KeyAction.CommitAction(it), source)
                    strip.hide()
                }
                return
            }
        }
        super.onAction(action, source)
    }

    override fun onAttach() {
        super.onAttach()
        T9Pinyin.active = true
    }

    override fun onDetach() {
        T9Pinyin.active = false
        onPreeditUpdate("")
        symbolStrip?.hide()
        super.onDetach()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }
}
