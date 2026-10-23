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
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.StripPopup
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
import splitties.views.imageResource

/**
 * A letter group key: tap sends the digit; swipe types the digit itself; long press opens a row of
 * its letters, lower then upper case, the first capital picked (or [strip] if given).
 */
class T9Key(
    letters: String, digit: Int, display: String = letters, viewId: Int = -1,
    strip: StripPopup = StripPopup(
        (letters.lowercase() + letters.uppercase()).map { it.toString() }.toTypedArray(), letters.length
    )
) : KeyDef(
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
    arrayOf(Popup.AltPreview(display, digit.toString()), strip)
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

        /** X11 keysyms of keypad ← →, which Rime's navigator maps to moving by one letter */
        private const val KP_LEFT = 0xff96
        private const val KP_RIGHT = 0xff98

        /** shown in the symbol row when 1 is tapped with nothing typed */
        val SYMBOLS = listOf("@", "#", "*", "+", "…", "~", "（）", "、", "/")

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                T9Placeholder(LEFT_IDS[0]),
                // 1: syllable separator while typing (the schema maps it to '), symbol row otherwise
                T9Key("@#", 1, viewId = SEPARATOR_ID, strip = StripPopup(arrayOf("/", "-", "_", "@", "#", "."), 3)),
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
                ZhEnKey(SIDE_WIDTH),
                T9Placeholder(RIGHT_IDS[1])
            )
        )
    }

    // with separate keys ("按键边框"), the column is one rounded grey key with the same margins and
    // shadow as the others and thin lines between its entries; otherwise a flat grey strip
    private val bordered = ThemeManager.prefs.keyBorder.getValue()
    private val keyRadius = dp(ThemeManager.prefs.keyRadius.getValue().toFloat())
    private val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val hMargin = dp(
        (if (landscape) ThemeManager.prefs.keyHorizontalMarginLandscape else ThemeManager.prefs.keyHorizontalMargin).getValue()
    )
    private val vMargin = dp(
        (if (landscape) ThemeManager.prefs.keyVerticalMarginLandscape else ThemeManager.prefs.keyVerticalMargin).getValue()
    )

    private val columnList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (bordered) {
            dividerDrawable = GradientDrawable().apply {
                setColor(theme.dividerColor)
                setSize(0, 1.coerceAtLeast(dp(1) / 2))
            }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
    }

    /** left column over rows 1–3: the marks ，。？！, or while typing the pinyin to choose from */
    private val leftColumn = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        if (bordered) {
            background = GradientDrawable().apply {
                cornerRadius = keyRadius
                setColor(theme.altKeyBackgroundColor)
            }
            // keep the entries' press highlight inside the rounded corners
            clipToOutline = true
        } else {
            setBackgroundColor(theme.altKeyBackgroundColor)
        }
        addView(columnList)
    }

    private val leftBox = FrameLayout(context).apply {
        if (bordered) {
            setPadding(hMargin, vMargin, hMargin, vMargin)
            background = shadowedKeyBackgroundDrawable(
                theme.altKeyBackgroundColor, theme.keyShadowColor, keyRadius, dp(1), hMargin, vMargin
            )
        }
        addView(leftColumn, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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

    /** the code in Rime's input, chosen syllables included; null when nothing is typed */
    private var typedRaw: String? = null

    /** syllables chosen from the column, in order; their letters start Rime's input */
    private val chosen = mutableListOf<String>()

    /** a chosen syllable that backspace turned back into digits: highlighted in the column */
    private var reselect: String? = null

    /** a backspace rewrote the input; further ones wait for Rime's new preedit */
    private var awaitingPreedit = false

    init {
        // the key rows are the children added by BaseKeyboard; constraints need ids
        val rows = (0 until childCount).map { getChildAt(it) }
        rows.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        (LEFT_IDS + RIGHT_IDS).forEach { findViewById<View>(it)?.visibility = View.INVISIBLE }
        add(leftBox, lParams(0, 0) {
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
        background = pressHighlightDrawable(theme.keyPressHighlightColor)
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
        awaitingPreedit = false
        syncChosen(T9Pinyin.typedInput(preedit))
        val raw = T9Pinyin.pendingInput(preedit)
        if (raw == pendingRaw) return
        pendingRaw = raw
        val options = raw?.let { T9Pinyin.options(it) }.orEmpty()
        if (options.isEmpty()) {
            showMarks()
            return
        }
        // the syllable backspace gave back, while its digits are still the ones being chosen
        val digits = raw!!.dropWhile { it !in '2'..'9' }
        val highlighted = reselect?.takeIf { digits == T9Pinyin.encode(it) }
        columnList.removeAllViews()
        options.forEach { syllable ->
            columnList.addView(columnItem(syllable) { choose(syllable) }.apply {
                minHeight = dp(42)
                if (syllable == highlighted) {
                    setBackgroundColor(theme.keyBackgroundColor)
                    setTextColor(theme.keyTextColor)
                    foreground = pressHighlightDrawable(theme.keyPressHighlightColor)
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        leftColumn.scrollTo(0, 0)
    }

    /** Keep [chosen] matching the letters at the start of Rime's input (e.g. after a word was picked). */
    private fun syncChosen(raw: String?) {
        typedRaw = raw
        val letters = raw?.takeWhile { it !in '2'..'9' }.orEmpty()
        if (letters.isEmpty()) {
            chosen.clear()
            if (raw == null) reselect = null
        } else if (chosen.joinToString("") != letters) {
            chosen.clear()
            // letters typed some other way (or a ' separator): read them as pinyin
            if ('\'' !in letters) T9Pinyin.spell(letters)?.let { chosen.addAll(it) }
        }
    }

    private fun backspace() = keyActionListener?.onKeyAction(
        KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)), KeyActionListener.Source.Keyboard
    )

    /**
     * Backspace over a chosen syllable gives it back as digits, so it can be chosen again from the
     * column (where it shows highlighted): deleting the last digit after "zen me" + 7 leaves
     * "zen" + 63 rather than "zenme"; with every syllable chosen, the last one goes back to its
     * digits minus the last one, as if that digit had been deleted.
     * @return false to let the backspace through unchanged
     */
    private fun backspaceOverChosen(): Boolean {
        if (awaitingPreedit) return true
        val raw = typedRaw ?: return false
        if (chosen.isEmpty() || '\'' in raw) return false
        val digits = raw.length - raw.indexOfFirst { it in '2'..'9' }.let { if (it < 0) raw.length else it }
        val last = chosen.last()
        val typeBack = when (digits) {
            // the only digit goes; the syllable before it becomes digits again
            1 -> T9Pinyin.encode(last).also { backspace() }
            // nothing left to choose: the last chosen syllable loses its last letter
            0 -> T9Pinyin.encode(last).dropLast(1)
            else -> return false
        }
        repeat(last.length) { backspace() }
        typeBack.forEach { sendKey(it.toString()) }
        chosen.removeAt(chosen.lastIndex)
        reselect = last.takeIf { typeBack.length == last.length }
        awaitingPreedit = true
        return true
    }

    /** Replace the digits the syllable covers with its letters: delete from the end, retype. */
    private fun choose(syllable: String) {
        val raw = pendingRaw ?: return
        val (delete, type) = T9Pinyin.replacement(raw, syllable) ?: return
        val listener = keyActionListener ?: return
        chosen.add(syllable)
        reselect = null
        repeat(delete) {
            listener.onKeyAction(
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)), KeyActionListener.Source.Keyboard
            )
        }
        type.forEach { sendKey(it.toString()) }
    }

    @SuppressLint("MissingSuperCall") // the 1 key may open the symbol row instead
    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        // swiping on space while typing: Rime moves the caret a whole syllable on ← and a letter
        // on keypad ←/→, so send those to move through the pinyin one letter at a time
        if (typing && action is KeyAction.SymAction) {
            val keypad = when (action.sym.sym) {
                FcitxKeyMapping.FcitxKey_Left -> KP_LEFT
                FcitxKeyMapping.FcitxKey_Right -> KP_RIGHT
                else -> null
            }
            if (keypad != null) {
                super.onAction(KeyAction.SymAction(KeySym(keypad), action.states), source)
                return
            }
        }
        // backspace over a chosen syllable: give it back as digits to choose again
        if (typing && action is KeyAction.SymAction &&
            action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace && backspaceOverChosen()
        ) return
        // 1 with nothing typed: toggle the symbol row instead of sending it to Rime
        if (action is KeyAction.FcitxKeyAction && action.act == "1" && !typing) {
            val strip = symbolStrip
            if (strip != null) {
                if (strip.isShown) strip.hide()
                else strip.show(SYMBOLS) {
                    // a bracket pair puts the caret between the two
                    val cursor = if (it == "（）") 1 else -1
                    keyActionListener?.onKeyAction(KeyAction.CommitAction(it, cursor), source)
                    strip.hide()
                }
                return
            }
        }
        super.onAction(action, source)
    }

    override fun onDetach() {
        // T9Pinyin.active stays on: the keyboard is also detached while the expanded candidate
        // window is open, whose candidates should still hide their pinyin (KeyboardWindow sets it)
        onPreeditUpdate("")
        symbolStrip?.hide()
        super.onDetach()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        findViewById<ImageKeyView>(R.id.button_lang)?.showLanguage(ime)
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }
}
