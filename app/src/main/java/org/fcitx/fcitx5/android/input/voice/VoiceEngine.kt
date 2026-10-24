/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Which recognizer the hold-space voice input uses. SenseVoice stands for the offline models (the
 * name is kept so saved settings carry over; which model is OfflineVoice.current).
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class VoiceEngine(override val stringRes: Int) : ManagedPreferenceEnum {
    Azure(R.string.voice_engine_azure),
    SenseVoice(R.string.voice_engine_sensevoice),
    Doubao(R.string.voice_engine_doubao),
    Qwen(R.string.voice_engine_qwen),
    ElevenLabs(R.string.voice_engine_elevenlabs),
    Deepgram(R.string.voice_engine_deepgram),
    OpenAI(R.string.voice_engine_openai);
}
