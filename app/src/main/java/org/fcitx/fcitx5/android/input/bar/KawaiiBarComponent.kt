/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Color
import android.os.Build
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.data.clipboard.ClipboardLink
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToAttachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToDetachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.bar.ui.IdleUi
import org.fcitx.fcitx5.android.input.bar.ui.T9SymbolUi
import org.fcitx.fcitx5.android.input.bar.ui.TitleUi
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.expanded.window.FlexboxExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.window.T9CandidateWindow
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.phrase.CommonPhraseWindow
import org.fcitx.fcitx5.android.input.phrase.AiPredictWindow
import org.fcitx.fcitx5.android.ui.main.AiAssistActivity
import org.fcitx.fcitx5.android.ui.main.SmsPermissionActivity
import org.fcitx.fcitx5.android.data.ai.AiSuggest
import org.fcitx.fcitx5.android.data.ai.AiConfig
import org.fcitx.fcitx5.android.data.ai.AiPromptRunner
import org.fcitx.fcitx5.android.data.ai.AiTranslate
import org.fcitx.fcitx5.android.data.ai.CloudConsent
import org.fcitx.fcitx5.android.data.otp.OtpCode
import org.fcitx.fcitx5.android.data.otp.OtpCodeManager
import org.fcitx.fcitx5.android.data.password.PasswordManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.voice.VoicePushToTalk
import org.fcitx.fcitx5.android.utils.toast
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editing.TextEditingWindow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.T9Pinyin
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class KawaiiBarComponent : UniqueViewComponent<KawaiiBarComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()

    private val prefs = AppPrefs.getInstance()

    private val clipboardSuggestion = prefs.clipboard.clipboardSuggestion
    private val clipboardItemTimeout = prefs.clipboard.clipboardItemTimeout
    private val clipboardMaskSensitive by prefs.clipboard.clipboardMaskSensitive
    private val expandedCandidateStyle by prefs.keyboard.expandedCandidateStyle
    private val expandToolbarByDefault by prefs.keyboard.expandToolbarByDefault
    private val toolbarNumRowOnPassword by prefs.keyboard.toolbarNumRowOnPassword
    private val showVoiceInputButton by prefs.keyboard.showVoiceInputButton
    private val preferredVoiceInput by prefs.keyboard.preferredVoiceInput

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false

    /** http(s) link in the clipboard suggestion, opened by its "打开链接" button */
    private var clipboardLink: String? = null

    // ---------- AI 联想 (auto mode) ----------
    private val aiPredictAuto = prefs.keyboard.aiPredictAuto
    private var isAiFresh = false
    private var isVoiceStatus = false
    private var aiBlocked = false          // password / incognito fields
    private var preeditEmpty = true
    private var candidatesEmpty = true
    private var aiJob: Job? = null
    private var aiLastContext: String? = null

    /** AI suggestions tapped in a row; reset when the user types or moves the cursor themselves */
    private var aiChainCount = 0
    private var lastAiCommitAt = 0L

    private fun clearAiSuggestion() {
        aiJob?.cancel()
        aiJob = null
        if (isAiFresh) {
            isAiFresh = false
            evalIdleUiState()
        }
    }

    // ---------- 短信验证码 ----------
    private val otpCodeEnabled = prefs.keyboard.otpCodeEnabled
    private val otpAutoFill by prefs.keyboard.otpAutoFill
    private var otpCode: OtpCode? = null              // code offered on the bar
    private var otpNeedsPermission = false            // offer "去允许" instead
    private var otpPromptDismissed = false            // user closed the permission prompt
    private var otpExpireJob: Job? = null
    private var otpTypedThisSession = false           // keep AI 联想 from sending the code anywhere
    private var currentEditorInfo: EditorInfo? = null
    private val otpClipboard = prefs.keyboard.otpClipboard
    private var clipboardOtp: OtpCode? = null         // 6–8 digit code just copied (e.g. Bitwarden TOTP)

    private fun OtpCode.isFresh() = System.currentTimeMillis() - receivedAt < maxAgeMs

    /**
     * Look up the newest code from SMS and the clipboard; with [autoFill], type it right away into
     * an empty code field.
     */
    private fun refreshOtp(autoFill: Boolean) {
        val smsOn = otpCodeEnabled.getValue()
        val smsAllowed = smsOn && OtpCodeManager.hasPermission(context)
        otpNeedsPermission = smsOn && !smsAllowed && !otpPromptDismissed
        if (smsAllowed) OtpCodeManager.ensureObserving(context)
        service.lifecycleScope.launch {
            val sms = if (smsAllowed) withContext(Dispatchers.IO) { OtpCodeManager.latest(context) } else null
            val code = listOfNotNull(sms, clipboardOtp.takeIf { otpClipboard.getValue() })
                .filter { it.isFresh() && !OtpCodeManager.isHandled(it) }
                .maxByOrNull { it.receivedAt }
            val info = currentEditorInfo
            if (code != null && autoFill && otpAutoFill && info != null &&
                OtpCodeManager.looksLikeCodeField(info) &&
                System.currentTimeMillis() - code.receivedAt < OtpCodeManager.AUTO_FILL_MAX_AGE_MS &&
                isFieldEmpty()
            ) {
                typeOtp(code)
            } else {
                showOtp(code)
            }
        }
    }

    private fun showOtp(code: OtpCode?) {
        otpCode = code
        otpExpireJob?.cancel()
        otpExpireJob = null
        if (code == null && otpNeedsPermission) idleUi.otpCodeUi.showPermissionPrompt()
        if (code != null) {
            idleUi.otpCodeUi.showCode(code.code, code.source)
            val left = code.receivedAt + code.maxAgeMs - System.currentTimeMillis()
            otpExpireJob = service.lifecycleScope.launch {
                delay(left.coerceAtLeast(0L))
                otpCode = null
                evalIdleUiState()
            }
        }
        evalIdleUiState()
    }

    private fun typeOtp(code: OtpCode) {
        OtpCodeManager.markHandled(code)
        otpTypedThisSession = true
        clearAiSuggestion()
        service.commitText(code.code)
        showOtp(null)
    }

    private fun isFieldEmpty(): Boolean = runCatching {
        val ic = service.currentInputConnection ?: return false
        ic.getTextBeforeCursor(1, 0).isNullOrEmpty() && ic.getTextAfterCursor(1, 0).isNullOrEmpty()
    }.getOrDefault(false)

    @Keep
    private val onOtpCodeEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        refreshOtp(autoFill = false)
    }

    // ---------- 就地翻译: long-press 文A ----------
    private var translateStatus: String? = null
    private var translateJob: Job? = null
    private var isPasswordField = false

    private fun showTranslateStatus(text: String?) {
        translateStatus = text
        if (text != null) idleUi.voiceStatus.text = text
        evalIdleUiState()
    }

    /**
     * Replace the selection, or the whole field when nothing is selected, with its translation
     * (Chinese → the target language in settings, anything else → Chinese). Nothing is replaced if
     * the field changed while waiting for the translation.
     */
    /**
     * Long-press on the Bitwarden button: generate a username or password, type it and put it on
     * the clipboard (marked sensitive: masked by the system and skipped by clipboard plugins, see
     * ClipboardEntry), so it can still be found if the app doesn't keep it. A password is only
     * typed into password fields; elsewhere it just goes to the clipboard.
     */
    private fun generateCredential(password: Boolean) {
        val value = if (password) org.fcitx.fcitx5.android.data.password.CredentialGenerator.password()
        else org.fcitx.fcitx5.android.data.password.CredentialGenerator.username()
        val clip = android.content.ClipData.newPlainText(if (password) "password" else "username", value)
        if (password && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.content.ClipDescription.EXTRA_IS_SENSITIVE
                    else "android.content.extra.IS_SENSITIVE", true
                )
            }
        }
        runCatching { service.clipboardManager.setPrimaryClip(clip) }
        if (!password || isPasswordField) {
            service.commitText(value)
            context.toast(if (password) "已生成密码并复制到剪贴板" else "已生成用户名并复制到剪贴板")
        } else {
            context.toast("这里不是密码框，密码只复制到了剪贴板")
        }
    }

    private fun translateInPlace() {
        if (isPasswordField) { context.toast("密码框里不翻译"); return }
        val engine = AiTranslate.engine(context)
        replaceInPlace(
            emptyMessage = "输入框里没有要翻译的文字",
            status = { "翻译中… → ${AiTranslate.targetFor(context, it)}（${engine.label}）" },
            failure = "翻译失败"
        ) { text ->
            val detailed = AiTranslate.translateDetailed(context, text, engine)
            detailed.note?.let { context.toast(it) }
            detailed.text
        }
    }

    /**
     * Long-press on the 大模型 button: pick a prompt, run it on the selection (or the whole field)
     * with the default model and replace the text with the reply. Never in password / incognito fields.
     */
    private fun aiPromptInPlace(anchor: View) {
        if (aiBlocked) { context.toast("密码框和无痕模式下不使用大模型"); return }
        val prompts = AiConfig.prompts(context)
        if (prompts.isEmpty()) { context.toast("还没有提示词，请在大模型设置里添加"); return }
        val last = AiConfig.lastPrompt(context)
        // mod: the same keyboard-colored card as the translate target menu
        org.fcitx.fcitx5.android.input.bar.ui.CardPopupMenu.show(
            anchor, theme, prompts.map { it.name }, prompts.indexOfFirst { it.id == last }
        ) { i ->
            val prompt = prompts[i]
            AiConfig.setLastPrompt(context, prompt.id)
            val provider = AiConfig.provider(context)
            replaceInPlace(
                emptyMessage = "输入框里没有文字",
                status = { "${prompt.name}中…（${provider.label}）" },
                failure = "${prompt.name}失败"
            ) { text -> AiPromptRunner.run(context, prompt, text) }
        }
    }

    /**
     * Sends the selection, or the whole field when nothing is selected, through [transform] and puts
     * the result in its place. Nothing is replaced if the field changed while waiting.
     */
    private fun replaceInPlace(
        emptyMessage: String, status: (String) -> String, failure: String,
        transform: suspend (String) -> CharSequence
    ) {
        if (!preeditEmpty) { context.toast("请先完成正在输入的内容"); return }
        val ic = service.currentInputConnection ?: return
        val info = currentEditorInfo
        val selected = runCatching { ic.getSelectedText(0)?.toString() }.getOrNull().orEmpty()
        val before = if (selected.isEmpty())
            runCatching { ic.getTextBeforeCursor(MAX_TRANSLATE, 0)?.toString() }.getOrNull().orEmpty() else ""
        val after = if (selected.isEmpty())
            runCatching { ic.getTextAfterCursor(MAX_TRANSLATE, 0)?.toString() }.getOrNull().orEmpty() else ""
        val text = selected.ifEmpty { before + after }
        if (text.isBlank()) { context.toast(emptyMessage); return }
        translateJob?.cancel()
        clearAiSuggestion()
        showTranslateStatus(status(text))
        translateJob = service.lifecycleScope.launch {
            try {
                val result = transform(text)
                val ic2 = service.currentInputConnection
                val unchanged = ic2 != null && currentEditorInfo === info && runCatching {
                    if (selected.isNotEmpty()) ic2.getSelectedText(0)?.toString() == selected
                    else ic2.getTextBeforeCursor(MAX_TRANSLATE, 0)?.toString().orEmpty() == before &&
                            ic2.getTextAfterCursor(MAX_TRANSLATE, 0)?.toString().orEmpty() == after
                }.getOrDefault(false)
                if (!unchanged) {
                    context.toast("输入框内容已变化，没有替换")
                    return@launch
                }
                ic2!!.beginBatchEdit()
                if (selected.isEmpty()) ic2.deleteSurroundingText(before.length, after.length)
                ic2.commitText(result, 1)
                ic2.endBatchEdit()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                context.toast("$failure：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                showTranslateStatus(null)
            }
        }
    }

    /** Called whenever the editor settles (no composing text, no candidates). */
    private fun scheduleAiPredict() {
        if (!aiPredictAuto.getValue() || aiBlocked || otpTypedThisSession || isInlineSuggestionPresent ||
            !preeditEmpty || !candidatesEmpty
        ) return
        // mod: not before the one-time notice was accepted (asked when switching it on, not while typing)
        if (!CloudConsent.granted(context, CloudConsent.Kind.AiPredict)) return
        // after this many suggestions tapped in a row, wait for the user to type again
        val chainLimit = prefs.keyboard.aiPredictChainLimit.getValue()
        if (chainLimit > 0 && aiChainCount > chainLimit) {
            clearAiSuggestion()
            return
        }
        aiJob?.cancel()
        aiJob = service.lifecycleScope.launch {
            // short wait so the several selection updates of one commit collapse into one request;
            // adjustable in Settings → Virtual Keyboard → AI 联想延迟
            delay(prefs.keyboard.aiPredictDelay.getValue().toLong())
            val before = runCatching {
                service.currentInputConnection
                    ?.getTextBeforeCursor(AiSuggest.MAX_CONTEXT, 0)?.toString()
            }.getOrNull().orEmpty()
            if (before.isBlank() || before.endsWith("\n")) {
                aiLastContext = null
                if (isAiFresh) { isAiFresh = false; evalIdleUiState() }
                return@launch
            }
            if (before == aiLastContext && isAiFresh) return@launch
            aiLastContext = before
            isAiFresh = true
            idleUi.aiSuggestionUi.setLoading()
            evalIdleUiState()
            try {
                val items = AiSuggest.predict(context, before)
                if (items.isEmpty()) {
                    isAiFresh = false
                } else {
                    idleUi.aiSuggestionUi.setSuggestions(items)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                isAiFresh = false   // fail quietly in auto mode
            }
            evalIdleUiState()
        }
    }

    @Keep
    private val onAiPredictAutoListener = ManagedPreference.OnChangeListener<Boolean> { _, on ->
        if (on && !CloudConsent.granted(context, CloudConsent.Kind.AiPredict)) {
            // mod: explain where the text goes first; the notice switches it back on when accepted
            aiPredictAuto.setValue(false)
            CloudConsent.request(context, CloudConsent.Kind.AiPredict)
            return@OnChangeListener
        }
        if (on) scheduleAiPredict() else clearAiSuggestion()
        idleUi.buttonsUi.aiPredictButton.alpha = if (on) 1f else 0.4f
    }
    private var isInlineSuggestionPresent: Boolean = false
    private var isCapabilityFlagsPassword: Boolean = false
    private var isKeyboardLayoutNumber: Boolean = false
    private var isToolbarManuallyToggled: Boolean = false

    private enum class NumberRowState { Auto, ForceShow, ForceHide }

    private var numberRowState = NumberRowState.Auto

    @Keep
    private val onClipboardUpdateListener =
        ClipboardManager.OnClipboardUpdateListener {
            // a copied 6–8 digit code (e.g. the TOTP Bitwarden copies after autofill) goes to the code strip
            if (otpClipboard.getValue()) {
                val code = OtpCodeManager.fromClipboard(it.text, it.sensitive, it.timestamp)
                if (code != null) {
                    service.lifecycleScope.launch {
                        clipboardOtp = code
                        isClipboardFresh = false
                        refreshOtp(autoFill = true)
                    }
                    return@OnClipboardUpdateListener
                }
            }
            if (!clipboardSuggestion.getValue()) return@OnClipboardUpdateListener
            service.lifecycleScope.launch {
                if (it.text.isEmpty()) {
                    isClipboardFresh = false
                } else {
                    idleUi.clipboardUi.text.text = if (it.sensitive && clipboardMaskSensitive) {
                        ClipboardEntry.BULLET.repeat(min(42, it.text.length))
                    } else {
                        it.text.take(42)
                    }
                    // "打开链接" for a copied http(s) link; never for sensitive entries (e.g. passwords)
                    clipboardLink = if (it.sensitive) null else ClipboardLink.find(it.text)
                    idleUi.clipboardUi.linkView.apply {
                        text = clipboardLink?.let { url -> ClipboardLink.host(url) }
                        visibility = if (clipboardLink != null) View.VISIBLE else View.GONE
                    }
                    isClipboardFresh = true
                    launchClipboardTimeoutJob()
                }
                evalIdleUiState()
            }
        }

    @Keep
    private val onClipboardSuggestionUpdateListener =
        ManagedPreference.OnChangeListener<Boolean> { _, it ->
            if (!it) {
                isClipboardFresh = false
                evalIdleUiState()
                clipboardTimeoutJob?.cancel()
                clipboardTimeoutJob = null
            }
        }

    @Keep
    private val onClipboardTimeoutUpdateListener =
        ManagedPreference.OnChangeListener<Int> { _, _ ->
            when (idleUi.currentState) {
                IdleUi.State.Clipboard -> {
                    // renew timeout when clipboard suggestion is present
                    launchClipboardTimeoutJob()
                }
                else -> {}
            }
        }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardItemTimeout.getValue() * 1000L
        // never transition to ClipboardTimedOut state when timeout < 0
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
        }
    }

    private fun evalIdleUiState(fromUser: Boolean = false) {
        val newState = when {
            isVoiceStatus || translateStatus != null -> IdleUi.State.VoiceStatus
            numberRowState == NumberRowState.ForceShow -> IdleUi.State.NumberRow
            otpCode != null -> IdleUi.State.OtpCode
            // autofill (e.g. Bitwarden) on a login form beats AI 联想 and clipboard suggestions
            isInlineSuggestionPresent -> IdleUi.State.InlineSuggestion
            otpNeedsPermission -> IdleUi.State.OtpCode
            isAiFresh -> IdleUi.State.AiSuggestion
            isClipboardFresh -> IdleUi.State.Clipboard
            isCapabilityFlagsPassword && !isKeyboardLayoutNumber && numberRowState != NumberRowState.ForceHide -> IdleUi.State.NumberRow
            /**
             * state matrix:
             *                               expandToolbarByDefault
             *                          |   \   |    true |   false
             * isToolbarManuallyToggled |  true |   Empty | Toolbar
             *                          | false | Toolbar |   Empty
             */
            expandToolbarByDefault == isToolbarManuallyToggled -> IdleUi.State.Empty
            else -> IdleUi.State.Toolbar
        }
        if (newState == idleUi.currentState) return
        idleUi.updateState(newState, fromUser)
    }

    private val hideKeyboardCallback = View.OnClickListener {
        service.requestHideSelf(0)
    }

    private val swipeDownExpandCallback = CustomGestureView.OnGestureListener { _, e ->
        if (e.type == CustomGestureView.GestureType.Up && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    // Combined gesture: determine primary direction by comparing totalX and totalY.
    // - If horizontal is dominant and left, show number row (when allowed).
    // - If vertical is dominant and down, hide keyboard.
    private val swipeHideKeyboardCallback = CustomGestureView.OnGestureListener { v, e ->
        require(v is ToolButton)
        val numberRowAvailable = isCapabilityFlagsPassword && !isKeyboardLayoutNumber
        if (numberRowAvailable) {
            val dir = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1 else -1
            // `e.x` and `e.y` are relative to the view's top-left corner
            val centerX = e.x - v.width / 2f
            val centerY = e.y - v.height / 2f

            val distance = hypot(centerX, centerY)
            // the button is ↓, so apply -90 degrees offset
            var angle = atan2(-centerX, centerY) * (180f / PI.toFloat())

            when (e.type) {
                CustomGestureView.GestureType.Move -> {
                    angle = if (angle in -45f..45f) {
                        angle.coerceIn(-10f, 10f)
                    } else abs(angle).coerceIn(90f - 10f, 90f + 10f) * dir
                    v.iconRotation = angle
                }
                CustomGestureView.GestureType.Up -> {
                    val handled = when (angle) {
                        in -45f..45f if distance > v.swipeThresholdX -> {
                            service.requestHideSelf(0)
                            true
                        }
                        !in -45f..45f if distance > v.swipeThresholdY -> {
                            v.iconRotation = 90f * dir
                            numberRowState = NumberRowState.ForceShow
                            evalIdleUiState(fromUser = true)
                            true
                        }
                        else -> false
                    }
                    v.iconRotation = 0f
                    return@OnGestureListener handled
                }
                else -> {}
            }
        }

        if (e.type == CustomGestureView.GestureType.Up && abs(e.totalY) > abs(e.totalX) && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    private var voiceInputSubtype: Pair<String, InputMethodSubtype>? = null

    private val switchToVoiceInputCallback = View.OnClickListener {
        val (id, subtype) = voiceInputSubtype ?: return@OnClickListener
        InputMethodUtil.switchInputMethod(service, id, subtype)
    }

    /**
     * mod: the toolbar 🎤 uses the mod's own voice input (engine from Settings → 语音识别引擎, e.g.
     * offline SenseVoice) instead of switching to a system voice IME: tap to start, tap again to stop.
     */
    private val modVoiceInputCallback = View.OnClickListener {
        if (VoicePushToTalk.isRecording) VoicePushToTalk.release()
        else VoicePushToTalk.start(service, tapMode = true)
    }

    /** mod: a toolbar button's tap (键盘选择, AI 联想), run from its tile in the 更多 panel */
    fun runToolbarButton(b: ToolbarButton) {
        idleUi.buttonsUi.button(b)?.performClick()
    }

    private val idleUi: IdleUi by lazy {
        IdleUi(context, theme, popup, commonKeyActionListener).apply {
            menuButton.setOnClickListener {
                when (idleUi.currentState) {
                    IdleUi.State.Empty -> {
                        isToolbarManuallyToggled = !expandToolbarByDefault
                        evalIdleUiState(fromUser = true)
                        org.fcitx.fcitx5.android.data.prefs.ModFreshInstall.toolbarHint(context)
                    }
                    IdleUi.State.Toolbar -> {
                        isToolbarManuallyToggled = expandToolbarByDefault
                        evalIdleUiState(fromUser = true)
                    }
                    else -> {
                        isToolbarManuallyToggled = !expandToolbarByDefault
                        isAiFresh = false
                        aiJob?.cancel()
                        // the code comes back on the next input field unless it was used or dismissed
                        otpCode = null
                        otpNeedsPermission = false
                        idleUi.updateState(IdleUi.State.Toolbar, fromUser = true)
                    }
                }
                // reset timeout timer (if present) when user switch layout
                if (clipboardTimeoutJob != null) {
                    launchClipboardTimeoutJob()
                }
            }
            buttonsUi.voiceButton.setOnClickListener(modVoiceInputCallback)
            hideKeyboardButton.apply {
                setOnClickListener(hideKeyboardCallback)
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                swipeThresholdX = swipeThresholdY
                onGestureListener = swipeHideKeyboardCallback
            }
            buttonsUi.apply {
                undoButton.setOnClickListener {
                    service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true)
                }
                redoButton.setOnClickListener {
                    service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
                }
                cursorMoveButton.setOnClickListener {
                    windowManager.attachWindow(TextEditingWindow())
                }
                clipboardButton.setOnClickListener {
                    windowManager.attachWindow(ClipboardWindow())
                }
                // mod: long press goes straight to 常用语
                clipboardButton.setOnLongClickListener {
                    windowManager.attachWindow(CommonPhraseWindow())
                    true
                }
                aiPredictButton.alpha = if (aiPredictAuto.getValue()) 1f else 0.4f
                aiPredictButton.setOnClickListener {
                    val on = !aiPredictAuto.getValue()
                    if (on && !CloudConsent.granted(context, CloudConsent.Kind.AiPredict)) {
                        CloudConsent.request(context, CloudConsent.Kind.AiPredict)
                        return@setOnClickListener
                    }
                    aiPredictAuto.setValue(on)
                    context.toast(if (on) "AI 联想：已开启（打字停顿后自动出现）" else "AI 联想：已关闭")
                }
                aiPredictButton.setOnLongClickListener {
                    windowManager.attachWindow(AiPredictWindow())
                    true
                }
                translateButton.setOnLongClickListener {
                    translateInPlace()
                    true
                }
                translateButton.setOnClickListener {
                    // mod: card style: open the translate card above the toolbar instead
                    if (ThemeManager.prefs.cardStyle.getValue()) {
                        if (isPasswordField) context.toast("密码框里不翻译")
                        else org.fcitx.fcitx5.android.input.translate.TranslateDraft.togglePanel?.invoke()
                        return@setOnClickListener
                    }
                    // translate the selection if there is one, replacing it on "输入"
                    val selected = runCatching {
                        service.currentInputConnection?.getSelectedText(0)?.toString()
                    }.getOrNull()
                    service.startActivity(
                        android.content.Intent(service, AiAssistActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(AiAssistActivity.EXTRA_MODE, AiAssistActivity.MODE_TRANSLATE)
                            putExtra(AiAssistActivity.EXTRA_TEXT, selected ?: "")
                        }
                    )
                }
                aiAssistButton.setOnLongClickListener {
                    aiPromptInPlace(it)
                    true
                }
                aiAssistButton.setOnClickListener {
                    val clip = runCatching {
                        service.clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }
                            // mod: never offer a copied password (its preview shows on the paste button)
                            ?.takeUnless { c ->
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                                        c.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
                            }
                            ?.getItemAt(0)?.coerceToText(service)?.toString()
                    }.getOrNull() ?: ClipboardManager.lastEntry?.takeUnless { it.sensitive }?.text
                    service.startActivity(
                        android.content.Intent(service, AiAssistActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(AiAssistActivity.EXTRA_CLIPBOARD, clip ?: "")
                        }
                    )
                }
                // mod: tap picks a layout / input method in the 键盘选择 panel, long-press flips 九宫格
                t9Button.setOnClickListener {
                    windowManager.attachWindow(org.fcitx.fcitx5.android.input.status.KeyboardPickerWindow())
                }
                t9Button.setOnLongClickListener {
                    val t9 = AppPrefs.getInstance().keyboard.t9Keyboard
                    t9.setValue(!t9.getValue())
                    context.toast(if (t9.getValue()) "九宫格（Rime 时生效）" else "全键盘")
                    true
                }
                // mod: tap opens Bitwarden; long-press and slide onto 生成用户名 / 生成密码
                org.fcitx.fcitx5.android.input.bar.ui.DragChooser.attach(
                    passwordManagerButton, theme,
                    labels = { listOf("用户名", "密码") },
                    onClick = { if (!PasswordManager.launch(service)) context.toast("没有安装 Bitwarden") },
                    onPick = { generateCredential(password = it == 1) }
                )
                moreButton.setOnClickListener {
                    windowManager.attachWindow(StatusAreaWindow())
                }
            }
            // the strip shows a code if there is one, otherwise the SMS permission prompt
            otpCodeUi.onCodeClick = {
                val code = otpCode
                if (code != null) {
                    typeOtp(code)
                } else if (otpNeedsPermission) {
                    otpNeedsPermission = false
                    service.startActivity(
                        android.content.Intent(service, SmsPermissionActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    evalIdleUiState()
                }
            }
            otpCodeUi.onDismiss = {
                val code = otpCode
                if (code != null) {
                    OtpCodeManager.markHandled(code)
                    showOtp(null)
                } else if (otpNeedsPermission) {
                    otpPromptDismissed = true
                    otpNeedsPermission = false
                    evalIdleUiState()
                }
            }
            aiSuggestionUi.onSuggestionClick = { text ->
                isAiFresh = false
                aiChainCount++
                lastAiCommitAt = android.os.SystemClock.uptimeMillis()
                service.commitText(text)
                // the commit moves the cursor; onSelectionUpdate will request the next suggestions
                evalIdleUiState()
            }
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    ClipboardManager.lastEntry?.let {
                        service.commitText(it.text)
                    }
                    clipboardTimeoutJob?.cancel()
                    clipboardTimeoutJob = null
                    isClipboardFresh = false
                    evalIdleUiState()
                }
                setOnLongClickListener {
                    ClipboardManager.lastEntry?.let {
                        AppUtil.launchClipboardEdit(context, it.id, true)
                    }
                    true
                }
            }
            clipboardUi.linkView.setOnClickListener {
                val url = clipboardLink ?: return@setOnClickListener
                try {
                    service.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: android.content.ActivityNotFoundException) {
                    context.toast("没有可以打开这个链接的应用")
                }
            }
            numberRow.apply {
                onCollapseListener = {
                    numberRowState = NumberRowState.ForceHide
                    evalIdleUiState(fromUser = true)
                }
            }
        }
    }

    private val candidateUi by lazy {
        CandidateUi(context, theme, horizontalCandidate.view).apply {
            expandButton.apply {
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                onGestureListener = swipeDownExpandCallback
            }
        }
    }

    private val titleUi by lazy {
        TitleUi(context, theme)
    }

    private val barStateMachine = KawaiiBarStateMachine.new {
        switchUiByState(it)
    }

    val expandButtonStateMachine = ExpandButtonStateMachine.new {
        when (it) {
            ClickToAttachWindow -> {
                setExpandButtonToAttach()
                setExpandButtonEnabled(true)
            }
            ClickToDetachWindow -> {
                setExpandButtonToDetach()
                setExpandButtonEnabled(true)
            }
            Hidden -> {
                setExpandButtonEnabled(false)
            }
        }
    }

    // set expand candidate button to create expand candidate
    private fun setExpandButtonToAttach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(
                // mod: the 九宫格 always has its own 5×5 list (pinyin column, 词频 / 单字)
                if (T9Pinyin.active) T9CandidateWindow()
                else when (expandedCandidateStyle) {
                    ExpandedCandidateStyle.Grid -> GridExpandedCandidateWindow()
                    ExpandedCandidateStyle.Flexbox -> FlexboxExpandedCandidateWindow()
                    ExpandedCandidateStyle.Keypad -> T9CandidateWindow()
                }
            )
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_more_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.expand_candidates_list)
    }

    // set expand candidate button to close expand candidate
    private fun setExpandButtonToDetach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_less_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.hide_candidates_list)
    }

    // should be used with setExpandButtonToAttach or setExpandButtonToDetach
    private fun setExpandButtonEnabled(enabled: Boolean) {
        candidateUi.expandButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    // mod: 九宫格 symbol row, shown over the toolbar until closed or the bar's state changes
    private val t9SymbolUi: T9SymbolUi by lazy {
        T9SymbolUi(context, theme).apply {
            closeButton.setOnClickListener { t9SymbolStrip.hide() }
        }
    }

    val t9SymbolStrip = object : T9Keyboard.SymbolStrip {
        override val isShown get() = view.displayedChild == T9_SYMBOL_INDEX

        override fun show(symbols: List<String>, onPick: (String) -> Unit) {
            t9SymbolUi.setSymbols(symbols, onPick)
            view.displayedChild = T9_SYMBOL_INDEX
        }

        override fun hide() {
            if (isShown) view.displayedChild = barStateMachine.currentState.ordinal
        }
    }

    private fun switchUiByState(state: KawaiiBarStateMachine.State) {
        val index = state.ordinal
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != titleUi.root) {
            titleUi.setReturnButtonOnClickListener { }
            titleUi.setTitle("")
            titleUi.removeExtension()
        }
        view.displayedChild = index
    }

    override val view by lazy {
        ViewAnimator(context).apply {
            backgroundColor =
                if (ThemeManager.prefs.keyBorder.getValue()) Color.TRANSPARENT
                else theme.barColor
            add(idleUi.root, lParams(matchParent, matchParent))
            add(candidateUi.root, lParams(matchParent, matchParent))
            add(titleUi.root, lParams(matchParent, matchParent))
            add(t9SymbolUi.root, lParams(matchParent, matchParent))
        }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        ClipboardManager.lastEntry?.let {
            val now = System.currentTimeMillis()
            val clipboardTimeout = clipboardItemTimeout.getValue() * 1000L
            if (now - it.timestamp < clipboardTimeout) {
                onClipboardUpdateListener.onUpdate(it)
            }
        }
        ClipboardManager.addOnUpdateListener(onClipboardUpdateListener)
        clipboardSuggestion.registerOnChangeListener(onClipboardSuggestionUpdateListener)
        clipboardItemTimeout.registerOnChangeListener(onClipboardTimeoutUpdateListener)
        CloudConsent.granted(context, CloudConsent.Kind.AiPredict) // settles who had it on before the notice existed
        aiPredictAuto.registerOnChangeListener(onAiPredictAutoListener)
        otpCodeEnabled.registerOnChangeListener(onOtpCodeEnabledListener)
        // a new SMS arrived: offer its code, or fill it into the code field the user is waiting in
        OtpCodeManager.listener = { refreshOtp(autoFill = true) }
        VoicePushToTalk.listener = { text ->
            isVoiceStatus = text != null
            if (text != null) idleUi.voiceStatus.text = text
            evalIdleUiState()
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            idleUi.privateMode(info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        }
        isCapabilityFlagsPassword = toolbarNumRowOnPassword && capFlags.has(CapabilityFlag.Password)
        // also keep AI 联想 out of verification-code fields and account fields (email / URL)
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isAccountField = (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_URI
                )
        aiBlocked = capFlags.has(CapabilityFlag.Password) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)) ||
                OtpCodeManager.looksLikeCodeField(info) || isAccountField
        currentEditorInfo = info
        otpTypedThisSession = false
        isPasswordField = capFlags.has(CapabilityFlag.Password)
        aiChainCount = 0
        aiJob?.cancel()
        isAiFresh = false
        aiLastContext = null
        isInlineSuggestionPresent = false
        numberRowState = NumberRowState.Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            idleUi.inlineSuggestionsBar.clear()
        }
        voiceInputSubtype = InputMethodUtil.findVoiceSubtype(preferredVoiceInput)
        // mod: no system voice IME needed; the button records with the mod's own voice input
        // no recording in password fields
        idleUi.buttonsUi.setVoiceAllowed(!capFlags.has(CapabilityFlag.Password))
        t9SymbolStrip.hide()
        evalIdleUiState()
        refreshOtp(autoFill = true)
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        barStateMachine.push(PreeditUpdated, PreeditEmpty to empty)
        preeditEmpty = empty
        if (!empty) {
            aiChainCount = 0   // the user is typing again
            clearAiSuggestion()
        }
    }

    override fun onCandidateUpdate(data: CandidateListEvent.Data) {
        barStateMachine.push(CandidatesUpdated, CandidateEmpty to data.candidates.isEmpty())
        candidatesEmpty = data.candidates.isEmpty()
        if (!candidatesEmpty) aiJob?.cancel()
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        // a cursor change long after the last tapped suggestion is the user's own typing / editing
        // (one commit causes several updates in quick succession, so allow a short window)
        if (android.os.SystemClock.uptimeMillis() - lastAiCommitAt > 500) aiChainCount = 0
        if (start != end) { clearAiSuggestion(); return }
        scheduleAiPredict()
    }

    override fun onWindowAttached(window: InputWindow) {
        when (window) {
            is InputWindow.ExtendedInputWindow<*> -> {
                titleUi.setTitle(window.title)
                window.onCreateBarExtension()?.let { titleUi.addExtension(it, window.showTitle) }
                titleUi.setReturnButtonOnClickListener {
                    windowManager.attachWindow(KeyboardWindow)
                }
                barStateMachine.push(ExtendedWindowAttached)
            }
            else -> {}
        }
    }

    override fun onWindowDetached(window: InputWindow) {
        barStateMachine.push(WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(HEIGHT))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            evalIdleUiState()
            idleUi.inlineSuggestionsBar.clear()
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            idleUi.inlineSuggestionsBar.setPinnedView(
                pinned?.let { inflateInlineContentView(it) }
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            idleUi.inlineSuggestionsBar.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        // a login form: drop AI 联想 so it neither hides the autofill chips nor sees the account
        aiJob?.cancel()
        isAiFresh = false
        evalIdleUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? {
        return suspendCancellableCoroutine { c ->
            // callback view might be null
            suggestion.inflate(context, suggestionSize, directExecutor) { v ->
                c.resume(v)
            }
        }
    }

    companion object {
        const val HEIGHT = 40

        /** child of [view] after the three KawaiiBarStateMachine states */
        private const val T9_SYMBOL_INDEX = 3

        /** longest text (each side of the cursor) that in-place translation reads from the field */
        private const val MAX_TRANSLATE = 5000
    }

    fun onKeyboardLayoutSwitched(isNumber: Boolean) {
        isKeyboardLayoutNumber = isNumber
        evalIdleUiState()
    }

}
