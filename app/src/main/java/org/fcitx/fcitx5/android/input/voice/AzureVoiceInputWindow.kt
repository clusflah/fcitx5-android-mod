/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Voice input panel, opened by long-pressing the space key.
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.VoicePermissionActivity
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class AzureVoiceInputWindow : InputWindow.ExtendedInputWindow<AzureVoiceInputWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val prefs = AppPrefs.getInstance().voice

    override val title: String by lazy { context.getString(R.string.azure_voice_input) }

    private enum class State { Idle, Recording, Recognizing, Error }

    private var state = State.Idle
    private var recorder: PcmRecorder? = null
    private var job: Job? = null
    private var levelJob: Job? = null

    private lateinit var status: TextView
    private lateinit var mic: TextView
    private lateinit var ring: View

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun config() = AzureConfig(
        prefs.azureSpeechKey.getValue(), prefs.azureSpeechRegion.getValue(),
        prefs.azureSttLanguage.getValue(), prefs.azureSttEndpoint.getValue(),
        prefs.azureTtsVoice.getValue(), prefs.azureTtsEndpoint.getValue()
    )

    override fun onCreateView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        status = TextView(context).apply {
            textSize = 16f; gravity = Gravity.CENTER
            setTextColor(theme.keyTextColor)
            setPadding(context.dp(16), 0, context.dp(16), context.dp(16))
        }
        val size = context.dp(96)
        val micBox = FrameLayout(context)
        ring = View(context).apply { background = circle(theme.accentKeyBackgroundColor); alpha = 0.25f }
        mic = TextView(context).apply {
            text = "🎤"; textSize = 36f; gravity = Gravity.CENTER
            background = circle(theme.accentKeyBackgroundColor)
            setOnClickListener { onMicClick() }
        }
        micBox.addView(ring, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        micBox.addView(mic, FrameLayout.LayoutParams((size * 0.75).toInt(), (size * 0.75).toInt(), Gravity.CENTER))
        root.addView(status)
        root.addView(micBox, LinearLayout.LayoutParams(size, size))
        root.addView(TextView(context).apply {
            text = context.getString(R.string.azure_voice_hint)
            textSize = 12f; setTextColor(theme.altKeyTextColor); gravity = Gravity.CENTER
            setPadding(0, context.dp(16), 0, 0)
        })
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return root
    }

    override fun onAttached() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showError(context.getString(R.string.azure_voice_need_permission))
            service.startActivity(Intent(context, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        startRecording()
    }

    override fun onDetached() {
        job?.cancel(); levelJob?.cancel()
        recorder?.cancel(); recorder = null
        state = State.Idle
    }

    private fun onMicClick() {
        when (state) {
            State.Recording -> finishRecording()
            State.Idle, State.Error -> onAttached()
            State.Recognizing -> {}
        }
    }

    private fun startRecording() {
        try {
            recorder = PcmRecorder().also { it.start() }
        } catch (e: Exception) {
            showError(e.message ?: "录音失败"); return
        }
        state = State.Recording
        status.text = context.getString(R.string.azure_voice_listening)
        levelJob = service.lifecycleScope.launch {
            while (isActive && state == State.Recording) {
                val r = recorder ?: break
                val scale = 0.8f + (r.level * 3f).coerceAtMost(1f) * 0.4f
                ring.scaleX = scale; ring.scaleY = scale
                if (!r.isRunning) { finishRecording(); break }
                delay(60)
            }
            ring.scaleX = 1f; ring.scaleY = 1f
        }
    }

    private fun finishRecording() {
        val r = recorder ?: return
        recorder = null
        state = State.Recognizing
        status.text = context.getString(R.string.azure_voice_recognizing)
        val wav = r.stop()
        job = service.lifecycleScope.launch {
            try {
                val text = AzureSpeech.recognize(config(), wav)
                if (text.isEmpty()) {
                    showError(context.getString(R.string.azure_voice_no_match)); return@launch
                }
                service.commitText(text)
                if (prefs.azureTtsEnabled.getValue()) {
                    runCatching { AzureSpeech.play(AzureSpeech.synthesize(config(), text, context.cacheDir)) }
                }
                windowManager.attachWindow(KeyboardWindow)
            } catch (e: Exception) {
                showError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun showError(msg: String) {
        state = State.Error
        status.text = msg + "\n" + context.getString(R.string.azure_voice_tap_retry)
    }
}
