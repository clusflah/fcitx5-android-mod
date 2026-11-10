/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Voice input panel, opened by long-pressing the space key.
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import org.fcitx.fcitx5.android.data.ai.AiVoiceCorrect
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.glass.Glass
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

    // mod: card style: tinted gradient, green waveform, a white/glass dome at the bottom to tap when done
    private val isGlass = Glass.isDialogGlassActive(theme)
    private val cardStyle = ThemeManager.prefs.cardStyle.getValue() || isGlass
    private var wave: VoiceWaveView? = null

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun config() = AzureConfig(
        prefs.azureSpeechKey.getValue(), prefs.azureSpeechRegion.getValue(),
        prefs.azureSttLanguage.getValue(), prefs.azureSttEndpoint.getValue(),
        prefs.azureTtsVoice.getValue(), prefs.azureTtsEndpoint.getValue()
    )

    private fun cardView(): View {
        val green = ThemePresetMod.JADE
        val root = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    (green and 0x00ffffff) or (if (isGlass) 0x18000000 else 0x1f000000),
                    if (isGlass) Color.TRANSPARENT else theme.keyboardColor
                )
            )
        }
        status = TextView(context).apply {
            textSize = 16f; gravity = Gravity.CENTER
            setTextColor(theme.altKeyTextColor)
            setPadding(context.dp(16), context.dp(16), context.dp(16), 0)
            Glass.applyKeyTextShadow(this, theme)
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        wave = VoiceWaveView(context, green).apply { setOnClickListener { onMicClick() } }
        root.addView(wave, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(64), Gravity.CENTER).apply {
            bottomMargin = context.dp(40)
        })
        if (isGlass) {
            // A true 840dp circle whose top 90dp emerges across the keyboard width as a curved
            // circular meniscus glass lens (avoids stadium/capsule flattening on GlassDrawable).
            val domeSize = context.dp(840)
            val dome = FrameLayout(context).apply {
                background = Glass.panelCardBackground(
                    this, theme, 0f, theme.keyBackgroundColor, theme.altKeyTextColor,
                    isOval = true, pressMorph = true, scaleViewOnPress = false, extraOpacityBoost = 0.14f
                ) ?: GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.keyBackgroundColor) }
                setOnClickListener { onMicClick() }
                addView(TextView(context).apply {
                    text = context.getString(R.string.azure_voice_hint)
                    textSize = 12f; setTextColor(theme.altKeyTextColor); gravity = Gravity.CENTER
                    Glass.applyKeyTextShadow(this, theme)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48), Gravity.TOP).apply {
                    topMargin = context.dp(20)
                })
            }
            root.addView(dome, FrameLayout.LayoutParams(domeSize, domeSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = -context.dp(750)
            })
        } else {
            // a wide oval whose top shows as a dome; tapping it finishes like the mic button
            val dome = FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.keyBackgroundColor) }
                setOnClickListener { onMicClick() }
                addView(TextView(context).apply {
                    text = context.getString(R.string.azure_voice_hint)
                    textSize = 12f; setTextColor(theme.altKeyTextColor); gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48), Gravity.TOP).apply {
                    topMargin = context.dp(18)
                })
            }
            root.addView(dome, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(220), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                val side = -context.dp(120)
                setMargins(side, 0, side, -context.dp(130))
            })
        }
        // [mic] and [ring] are only used by the classic layout
        mic = TextView(context)
        ring = View(context)
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return root
    }

    override fun onCreateView(): View {
        if (cardStyle) return cardView()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        status = TextView(context).apply {
            textSize = 16f; gravity = Gravity.CENTER
            setTextColor(theme.keyTextColor)
            setPadding(context.dp(16), 0, context.dp(16), context.dp(16))
            Glass.applyKeyTextShadow(this, theme)
        }
        val size = context.dp(96)
        val micBox = FrameLayout(context)
        ring = View(context).apply { background = circle(theme.accentKeyBackgroundColor); alpha = 0.25f }
        mic = TextView(context).apply {
            text = "🎤"; textSize = 36f; gravity = Gravity.CENTER
            background = Glass.panelCardBackground(
                this, theme, 0f, theme.accentKeyBackgroundColor, theme.accentKeyTextColor,
                isOval = true, pressMorph = true, extraOpacityBoost = 0.20f
            ) ?: circle(theme.accentKeyBackgroundColor)
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
            Glass.applyKeyTextShadow(this, theme)
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
                wave?.setLevel(r.level)
                if (!r.isRunning) { finishRecording(); break }
                delay(60)
            }
            ring.scaleX = 1f; ring.scaleY = 1f
            wave?.setLevel(0f)
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
                val corrected = if (AiVoiceCorrect.enabled(service.currentInputEditorInfo)) {
                    status.text = "纠错中…"
                    AiVoiceCorrect.correct(service, text)
                } else text
                service.commitText(corrected)
                if (prefs.azureTtsEnabled.getValue()) {
                    runCatching { AzureSpeech.play(AzureSpeech.synthesize(config(), corrected, context.cacheDir)) }
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
