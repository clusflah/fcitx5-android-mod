/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import android.os.SystemClock
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.keyboard.RimeT9.enterT9
import org.fcitx.fcitx5.android.input.keyboard.RimeT9.leaveT9
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout

    private val keyboards: HashMap<String, BaseKeyboard> by lazy {
        hashMapOf(
            TextKeyboard.Name to TextKeyboard(context, theme),
            NumberKeyboard.Name to NumberKeyboard(context, theme),
            T9Keyboard.Name to T9Keyboard(context, theme).apply { symbolStrip = bar.t9SymbolStrip }
        )
    }

    // mod: 九宫格 replaces the 26-key layout while Rime is active (see T9Keyboard / RimeT9)
    private val t9Pref = AppPrefs.getInstance().keyboard.t9Keyboard

    /** false in password / e-mail / URL fields, which keep the 26-key layout */
    private var t9Allowed = true

    // ManagedPreference holds listeners weakly, and R8 drops a field only used in the constructor:
    // without @Keep the listener is collected and the toolbar button only takes effect next time
    @Keep
    private val t9Listener = ManagedPreference.OnChangeListener<Boolean> { _, _ -> refreshTextLayout() }

    init {
        t9Pref.registerOnChangeListener(t9Listener)
    }

    private fun useT9() = t9Pref.getValue() && t9Allowed &&
            fcitx.runImmediately { inputMethodEntryCached }.uniqueName == RimeT9.RIME

    private val focusChangeResetKeyboard by AppPrefs.getInstance().keyboard.focusChangeResetKeyboard

    /** re-pick 26-key / 九宫格 when the setting or the input method changed */
    private fun refreshTextLayout() {
        val active = activeLayoutName()
        if (active == TextKeyboard.Name || active == T9Keyboard.Name) {
            switchLayout(TextKeyboard.Name, remember = false)
        }
    }

    /** the "no nine-key schema" hint is shown once per process */
    private var t9MissingWarned = false

    /** the last Rime schema sync found Rime not ready yet: try again on the next status-area update */
    @Volatile
    private var t9SyncPending = false

    /** select Rime's nine-key schema for 九宫格, and restore non-T9 schema for 26-key / number layout */
    private fun syncRimeSchema(layout: String) {
        when (layout) {
            T9Keyboard.Name -> service.postFcitxJob {
                if (activeLayoutName() != T9Keyboard.Name || !t9Pref.getValue() || !t9Allowed) {
                    return@postFcitxJob
                }
                val entered = enterT9()
                if (activeLayoutName() != T9Keyboard.Name || !t9Pref.getValue() || !t9Allowed) {
                    if (entered == true) leaveT9()
                    return@postFcitxJob
                }
                t9SyncPending = entered == null
                if (entered == false && !t9MissingWarned) {
                    t9MissingWarned = true
                    ContextCompat.getMainExecutor(service).execute {
                        android.widget.Toast.makeText(
                            service, "没有找到 Rime 的九键方案（如白霜拼音的“中文九键”），九宫格只能输入数字",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            NumberKeyboard.Name -> service.postFcitxJob {
                if (activeLayoutName() != NumberKeyboard.Name) return@postFcitxJob
                if (!isEmpty()) reset()
                t9SyncPending = leaveT9() == null
            }
            // only when 九宫格 is really off here: while fcitx starts, the input method isn't known
            // to be Rime yet and the 26-key layout shows for a moment; leaving then would switch Rime
            // back to the 26-key schema right before the 九宫格 comes up, and its keys type digits
            TextKeyboard.Name -> if (!t9Pref.getValue() || !t9Allowed) {
                service.postFcitxJob {
                    if (activeLayoutName() == T9Keyboard.Name) return@postFcitxJob
                    t9SyncPending = leaveT9() == null
                }
            } else {
                t9SyncPending = false
            }
        }
    }

    // mod: Rime may not have its schemas yet when the layout first asks to enter or leave nine-key
    // mode (fcitx just started, e.g. coming from another keyboard app); re-sync once status area arrives
    override fun onStatusAreaUpdate(actions: Array<Action>) {
        if (!t9SyncPending) return
        val schema = RimeT9.currentSchema(actions) ?: return
        val active = activeLayoutName()
        when {
            active == T9Keyboard.Name && !RimeT9.isT9Schema(schema) ->
                syncRimeSchema(T9Keyboard.Name)
            (active == NumberKeyboard.Name ||
                (active == TextKeyboard.Name && (!t9Pref.getValue() || !t9Allowed))) &&
                RimeT9.isT9Schema(schema) ->
                syncRimeSchema(active)
            else -> t9SyncPending = false
        }
    }

    @Volatile
    private var currentKeyboardName = ""

    @Volatile
    private var targetKeyboardName = ""

    private var layoutSwitchSeq = 0
    var restartingInput = false
    private var lastEditorPackage: String? = null
    private var lastEditorFieldId = 0
    private var lastEditorInputType = 0
    private var manualNumberSwitchUptime = 0L

    private fun activeLayoutName(): String = targetKeyboardName.ifEmpty { currentKeyboardName }

    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    private fun dispatchNumberKeyAction(action: KeyAction, source: KeyActionListener.Source) {
        val text = NumberKeyboard.directCommitText(action)
        if (text != null) {
            service.commitText(text)
            return
        }
        if (action is KeyAction.SymAction) {
            when (action.sym.sym) {
                FcitxKeyMapping.FcitxKey_BackSpace -> {
                    service.handleBackspaceKey()
                    return
                }
                FcitxKeyMapping.FcitxKey_Return -> {
                    service.handleReturnKey()
                    return
                }
            }
        }
        commonKeyActionListener.listener.onKeyAction(action, source)
    }

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
        } else if (activeLayoutName() == NumberKeyboard.Name) {
            dispatchNumberKeyAction(it, source)
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachLayout(TextKeyboard.Name)
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        targetKeyboardName = target
        currentKeyboardName = target
        // mod: 九宫格 display rules (pinyin preedit, no pinyin beside candidates) follow the layout
        T9Pinyin.active = target == T9Keyboard.Name
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
        }
        updateT9Preedit()
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        val requested = to.ifEmpty { lastSymbolType }
        if (requested == T9Keyboard.ToFullKeyboard) {
            // "全键" on the 九宫格: turn the setting off; the listener switches the layout
            t9Pref.setValue(false)
            return
        }
        val seq = ++layoutSwitchSeq
        val initialTarget =
            if (requested == TextKeyboard.Name && useT9()) T9Keyboard.Name else requested
        if (keyboards.containsKey(initialTarget)) {
            // Mark target layout immediately on the main thread so rapid taps or async callbacks
            // do not race against the posted layout swap.
            targetKeyboardName = initialTarget
            if (remember && initialTarget == NumberKeyboard.Name) {
                manualNumberSwitchUptime = SystemClock.uptimeMillis()
            }
            if (initialTarget == NumberKeyboard.Name) {
                T9Pinyin.active = false
                panelPreedit = ""
                clientPreedit = ""
                currentKeyboard?.rimeComposing = false
                (keyboards[T9Keyboard.Name] as? T9Keyboard)?.onPreeditUpdate("")
                service.clearComposing()
            }
        } else {
            targetKeyboardName = currentKeyboardName
        }
        ContextCompat.getMainExecutor(service).execute {
            if (seq != layoutSwitchSeq) return@execute
            val target =
                if (requested == TextKeyboard.Name && useT9()) T9Keyboard.Name else requested
            if (keyboards.containsKey(target)) {
                targetKeyboardName = target
                if (remember && target != TextKeyboard.Name && target != T9Keyboard.Name) {
                    lastSymbolType = target
                }
                syncRimeSchema(target)
                if (target == currentKeyboardName) return@execute
                detachCurrentLayout()
                attachLayout(target)
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                targetKeyboardName = currentKeyboardName
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        val restarting = restartingInput
        restartingInput = false
        val sameEditor = info.packageName == lastEditorPackage &&
            info.fieldId == lastEditorFieldId &&
            info.inputType == lastEditorInputType
        lastEditorPackage = info.packageName
        lastEditorFieldId = info.fieldId
        lastEditorInputType = info.inputType
        SpaceVoiceIcon.allowed = !capFlags.has(CapabilityFlag.Password)
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        // 26-key layout only for passwords and e-mail addresses. URL fields stay 九宫格 (browser
        // address bars are mostly used to search), and so do fields without a type (TYPE_NULL, e.g.
        // many WebView / game / terminal inputs), which used to fall back to 26 keys "at random"
        t9Allowed = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
                    variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
                    variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
                    variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
                    variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            InputType.TYPE_NULL -> true
            else -> false
        }
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        val preserveNumberLayout = restarting && sameEditor &&
            activeLayoutName() == NumberKeyboard.Name &&
            (!focusChangeResetKeyboard ||
                SystemClock.uptimeMillis() - manualNumberSwitchUptime < 800L)
        if (!preserveNumberLayout) {
            switchLayout(targetLayout, remember = false)
        }
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        if (activeLayoutName() == NumberKeyboard.Name) return
        currentKeyboard?.onInputMethodUpdate(ime)
        // 九宫格 only works with Rime: switching to e.g. English brings back the 26-key layout
        if (t9Pref.getValue()) refreshTextLayout()
    }

    // mod: the 九宫格's pinyin column follows Rime's preedit. With "commit preview" the code is in the
    // input panel and the client preedit holds Chinese text; otherwise only the client one is set.
    private var panelPreedit = ""
    private var clientPreedit = ""

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        if (activeLayoutName() == NumberKeyboard.Name) {
            panelPreedit = ""
            updateT9Preedit()
            return
        }
        panelPreedit = data.preedit.toString()
        updateT9Preedit()
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        if (activeLayoutName() == NumberKeyboard.Name) {
            clientPreedit = ""
            updateT9Preedit()
            return
        }
        clientPreedit = data.toString()
        updateT9Preedit()
    }

    private fun updateT9Preedit() {
        val preedit = panelPreedit.ifEmpty { clientPreedit }
        // mod: swiping on space moves Rime's caret one letter at a time (see BaseKeyboard)
        currentKeyboard?.rimeComposing = preedit.isNotEmpty() &&
                fcitx.runImmediately { inputMethodEntryCached }.uniqueName == RimeT9.RIME
        (currentKeyboard as? T9Keyboard)?.onPreeditUpdate(preedit)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
    }

    override fun onDetached() {
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }
}