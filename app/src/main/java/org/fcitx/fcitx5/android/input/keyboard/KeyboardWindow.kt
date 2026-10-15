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
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
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
            T9Keyboard.Name to T9Keyboard(context, theme)
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

    /** re-pick 26-key / 九宫格 when the setting or the input method changed */
    private fun refreshTextLayout() {
        if (currentKeyboardName == TextKeyboard.Name || currentKeyboardName == T9Keyboard.Name) {
            switchLayout(TextKeyboard.Name, remember = false)
        }
    }

    /** the "no nine-key schema" hint is shown once per process */
    private var t9MissingWarned = false

    /** select Rime's nine-key schema for 九宫格, and the previous one again for the 26-key layout */
    private fun syncRimeSchema(layout: String) {
        when (layout) {
            T9Keyboard.Name -> service.postFcitxJob {
                if (!enterT9() && !t9MissingWarned) {
                    t9MissingWarned = true
                    ContextCompat.getMainExecutor(service).execute {
                        android.widget.Toast.makeText(
                            service, "没有找到 Rime 的九键方案（如白霜拼音的“中文九键”），九宫格只能输入数字",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            TextKeyboard.Name -> service.postFcitxJob { leaveT9() }
        }
    }
    private var currentKeyboardName = ""
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
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
        currentKeyboardName = target
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
        ContextCompat.getMainExecutor(service).execute {
            val target = if (requested == TextKeyboard.Name && useT9()) T9Keyboard.Name else requested
            if (keyboards.containsKey(target)) {
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
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        t9Allowed = (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
                variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS &&
                variation != InputType.TYPE_TEXT_VARIATION_URI
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout, remember = false)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        currentKeyboard?.onInputMethodUpdate(ime)
        // 九宫格 only works with Rime: switching to e.g. English brings back the 26-key layout
        if (t9Pref.getValue()) refreshTextLayout()
    }

    // mod: the 九宫格's pinyin column follows Rime's preedit. With "commit preview" the code is in the
    // input panel and the client preedit holds Chinese text; otherwise only the client one is set.
    private var panelPreedit = ""
    private var clientPreedit = ""

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        panelPreedit = data.preedit.toString()
        updateT9Preedit()
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        clientPreedit = data.toString()
        updateT9Preedit()
    }

    private fun updateT9Preedit() {
        (currentKeyboard as? T9Keyboard)?.onPreeditUpdate(panelPreedit.ifEmpty { clientPreedit })
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