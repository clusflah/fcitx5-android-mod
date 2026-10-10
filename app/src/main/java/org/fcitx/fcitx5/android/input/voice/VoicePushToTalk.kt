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
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.VoicePermissionActivity

object VoicePushToTalk {

    /** status text for the toolbar; null hides it */
    var listener: ((String?) -> Unit)? = null

    private var service: FcitxInputMethodService? = null
    private var recorder: PcmRecorder? = null
    private var startedAt = 0L
    private var watchJob: Job? = null
    private var recognizeJob: Job? = null

    val isRecording get() = recorder != null

    fun config(): AzureConfig {
        val p = AppPrefs.getInstance().keyboard
        return AzureConfig(
            p.azureSpeechKey.getValue(), p.azureSpeechRegion.getValue(),
            p.azureSttLanguage.getValue(), p.azureSttEndpoint.getValue(),
            p.azureTtsVoice.getValue(), p.azureTtsEndpoint.getValue()
        )
    }

    /** Called on space long-press. */
    fun start(svc: FcitxInputMethodService) {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(svc, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener?.invoke("需要麦克风权限，授权后再长按空格")
            svc.startActivity(Intent(svc, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            hideLater(svc, 2500)
            return
        }
        recognizeJob?.cancel()
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
        listener?.invoke("🎤 正在聆听… 松开发送")
        // reflect volume, and stop automatically at the 60 s limit
        watchJob = svc.lifecycleScope.launch {
            val bars = arrayOf("▁", "▂", "▃", "▄", "▅", "▆", "▇")
            while (isActive) {
                val rec = recorder ?: break
                if (!rec.isRunning) { release(); break }
                val i = (rec.level * 3f * bars.size).toInt().coerceIn(0, bars.size - 1)
                listener?.invoke("🎤 ${bars[i]} 正在聆听… 松开发送")
                delay(120)
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
                val text = AzureSpeech.recognize(config(), wav)
                if (text.isEmpty()) {
                    listener?.invoke("没有听清，请再试一次")
                    hideLater(svc, 1800)
                    return@launch
                }
                svc.commitText(text)
                listener?.invoke(null)
                if (AppPrefs.getInstance().keyboard.azureTtsEnabled.getValue()) {
                    runCatching { AzureSpeech.play(AzureSpeech.synthesize(config(), text, svc.cacheDir)) }
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
