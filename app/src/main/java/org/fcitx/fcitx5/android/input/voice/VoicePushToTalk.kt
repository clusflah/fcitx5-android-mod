/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Push-to-talk: hold the space key to record, release to recognize and type.
 * The keyboard stays on screen (switching windows mid-touch would cancel the gesture),
 * progress is shown in the toolbar via [listener].
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.ai.AiVoiceCorrect
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.OfflineVoiceActivity
import org.fcitx.fcitx5.android.ui.main.VoicePermissionActivity

object VoicePushToTalk {

    /** status text for the toolbar; null hides it. While [isRecording] it is just the short hint
     *  on how to finish, shown next to a waveform fed by [levelListener]. */
    var listener: ((String?) -> Unit)? = null

    /** microphone level while recording, about 0..0.3 when speaking */
    var levelListener: ((Float) -> Unit)? = null

    private var service: FcitxInputMethodService? = null
    private var recorder: PcmRecorder? = null
    private var startedAt = 0L
    private var watchJob: Job? = null
    private var recognizeJob: Job? = null

    val isRecording get() = recorder != null

    private fun engine() = AppPrefs.getInstance().voice.voiceEngine.getValue()

    fun config(): AzureConfig {
        val p = AppPrefs.getInstance().voice
        return AzureConfig(
            p.azureSpeechKey.getValue(), p.azureSpeechRegion.getValue(),
            p.azureSttLanguage.getValue(), p.azureSttEndpoint.getValue(),
            p.azureTtsVoice.getValue(), p.azureTtsEndpoint.getValue()
        )
    }

    /** how to finish, shown while listening: release space, or tap the toolbar 🎤 again */
    private var stopHint = "松开发送"

    /**
     * Called on space long-press ([tapMode] = false: release the key to finish), or by the toolbar
     * 🎤 button ([tapMode] = true: tap it again to finish).
     */
    fun start(svc: FcitxInputMethodService, tapMode: Boolean = false) {
        stopHint = if (tapMode) "再点结束" else "松开发送"
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(svc, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener?.invoke("需要麦克风权限，授权后再试一次")
            svc.startActivity(Intent(svc, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            hideLater(svc, 2500)
            return
        }
        val offline = engine() == VoiceEngine.SenseVoice
        if (offline && !OfflineVoice.isReady(svc)) {
            listener?.invoke("离线语音模型 ${OfflineVoice.current.name} 还没有下载，请在设置 → 语音模型中下载")
            svc.startActivity(Intent(svc, OfflineVoiceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            hideLater(svc, 3000)
            return
        }
        recognizeJob?.cancel()
        // load the offline model while the user talks, so recognition starts right on release
        if (offline) OfflineVoice.preload(svc)
        val r = PcmRecorder()
        try {
            r.start()
        } catch (e: Exception) {
            listener?.invoke("无法录音：${e.message}")
            hideLater(svc, 2500)
            return
        }
        service = svc
        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        // mod: feel that recording started, and say once how to switch hold-to-talk off
        svc.window.window?.decorView?.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
            else android.view.HapticFeedbackConstants.LONG_PRESS
        )
        if (!tapMode && org.fcitx.fcitx5.android.data.prefs.ModFreshInstall.once(svc, "push_to_talk")) {
            android.widget.Toast.makeText(
                svc, "按住空格说话，松开就识别。不需要的话，在 设置 → 语音模型 里关掉“按住空格语音输入”",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        listener?.invoke(stopHint)
        // feed the waveform, and stop automatically at the 60 s limit
        watchJob = svc.lifecycleScope.launch {
            while (isActive) {
                val rec = recorder ?: break
                if (!rec.isRunning) { release(); break }
                levelListener?.invoke(rec.level)
                delay(60)
            }
        }
    }

    /** Called when the finger leaves the space key (up or cancel). */
    fun release() {
        val r = recorder ?: return
        val svc = service ?: return
        recorder = null
        watchJob?.cancel()
        val wav = r.stop()
        if (SystemClock.elapsedRealtime() - startedAt < 400) {
            listener?.invoke(null) // too short, treat as accidental
            return
        }
        listener?.invoke("识别中…")
        recognizeJob = svc.lifecycleScope.launch {
            try {
                var fellBack = false
                val engine = engine()
                val cloudName = CloudSpeech.engine(engine)?.name ?: engine.name
                val text = when (engine) {
                    VoiceEngine.SenseVoice -> OfflineVoice.recognize(svc, wav)
                    else -> try {
                        if (engine == VoiceEngine.Azure) AzureSpeech.recognize(config(), wav)
                        else CloudSpeech.recognize(engine, wav)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // service unreachable (or misconfigured): recognize the same recording offline
                        if (!AppPrefs.getInstance().voice.offlineFallback.getValue() || !OfflineVoice.isReady(svc)) throw e
                        listener?.invoke("$cloudName 不可用，改用离线识别…")
                        fellBack = true
                        OfflineVoice.recognize(svc, wav)
                    }
                }
                if (text.isEmpty()) {
                    listener?.invoke("没有听清，请再试一次")
                    hideLater(svc, 1800)
                    return@launch
                }
                // mod: optional LLM correction (homophones, typos, punctuation); keeps the original on failure
                val corrected = if (AiVoiceCorrect.enabled(svc.currentInputEditorInfo)) {
                    listener?.invoke("纠错中…")
                    AiVoiceCorrect.correct(svc, text)
                } else text
                svc.commitText(corrected)
                if (fellBack) {
                    listener?.invoke("$cloudName 不可用，已用离线识别")
                    hideLater(svc, 2000)
                } else {
                    listener?.invoke(null)
                }
                if (AppPrefs.getInstance().voice.azureTtsEnabled.getValue()) {
                    runCatching { AzureSpeech.play(AzureSpeech.synthesize(config(), corrected, svc.cacheDir)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener?.invoke("识别失败：${e.message ?: e.javaClass.simpleName}")
                hideLater(svc, 3000)
            }
        }
    }

    fun cancel() {
        watchJob?.cancel()
        recorder?.cancel()
        recorder = null
        recognizeJob?.cancel()
        listener?.invoke(null)
    }

    private fun hideLater(svc: FcitxInputMethodService, ms: Long) {
        svc.lifecycleScope.launch {
            delay(ms)
            if (recorder == null) listener?.invoke(null)
        }
    }
}
