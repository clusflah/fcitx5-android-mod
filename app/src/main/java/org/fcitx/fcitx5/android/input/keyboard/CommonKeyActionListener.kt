/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.voice.VoicePushToTalk
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()

    /** mod: nothing is being typed but candidates are shown = prediction words after a commit */
    private fun isPredictionShown() =
        AppPrefs.getInstance().keyboard.spaceClearsPrediction.getValue() &&
                preeditState.isEmpty &&
                horizontalCandidate.adapter.total > 0

    private fun isSpaceOverPrediction(action: SymAction) =
        action.sym.sym == FcitxKeyMapping.FcitxKey_space && isPredictionShown()

    /** a symbol / punctuation / digit key (one non-letter character) while predictions are shown */
    private fun isSymbolOverPrediction(action: FcitxKeyAction) =
        action.act.codePointCount(0, action.act.length) == 1 &&
                !Character.isLetter(action.act.codePointAt(0)) &&
                isPredictionShown()
    private val windowManager: InputWindowManager by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior

    private var backspaceSwipeState = Stopped

    // there should be a new fcitx API for this
    private suspend fun FcitxAPI.commitAndReset() {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Chinese: select 1st candidate, except prediction candidates
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                // preedit not empty, maybe there are candidates to select ...
                select(0)
            }
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    val listener by lazy {
        KeyActionListener { action, _ ->
            when (action) {
                is FcitxKeyAction -> {
                    // mod: drop prediction words first, or the engine picks the first one before the symbol
                    val clearPrediction = isSymbolOverPrediction(action)
                    service.postFcitxJob {
                        if (clearPrediction) reset()
                        sendKey(action.act, action.states.states, action.code)
                    }
                }
                is SymAction -> if (isSpaceOverPrediction(action)) {
                    // mod: nothing is being typed but candidates are shown = prediction words after a
                    // commit; clear them and type a space instead of picking the first one
                    service.postFcitxJob {
                        reset()
                        service.lifecycleScope.launch { service.commitText(" ") }
                    }
                } else service.postFcitxJob {
                    sendKey(action.sym, action.states)
                }
                is CommitAction -> service.postFcitxJob {
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(action.text) }
                }
                is QuickPhraseAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerQuickPhrase()
                }
                is UnicodeAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerUnicode()
                }
                is LangSwitchAction -> {
                    when (langSwitchKeyBehavior) {
                        LangSwitchBehavior.Enumerate -> {
                            service.postFcitxJob {
                                if (enabledIme().size < 2) {
                                    service.lifecycleScope.launch {
                                        service.showDialog(AddMoreInputMethodsPrompt.build(context))
                                    }
                                } else {
                                    enumerateIme()
                                }
                            }
                        }
                        LangSwitchBehavior.ToggleActivate -> {
                            service.postFcitxJob {
                                toggleIme()
                            }
                        }
                        LangSwitchBehavior.NextInputMethodApp -> {
                            service.switchToNextIME()
                        }
                    }
                }
                is ShowInputMethodPickerAction -> showInputMethodPicker()
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    // never record in password fields: fall back to the normal long-press behavior
                    val isPassword = service.currentInputEditorInfo?.let {
                        CapabilityFlags.fromEditorInfo(it).has(CapabilityFlag.Password)
                    } ?: false
                    if (kbdPrefs.azureVoiceEnabled.getValue() && !isPassword) {
                        // hold to talk: recording stops when the space key is released (see BaseKeyboard)
                        // called on the main thread from the long-press handler; start synchronously so a
                        // quick release right after the long press can't miss the recorder
                        VoicePushToTalk.start(service)
                        return@KeyActionListener
                    }
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                    }
                }
                else -> {}
            }
        }
    }
}
