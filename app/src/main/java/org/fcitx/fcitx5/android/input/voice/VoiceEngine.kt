/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Which recognizer the hold-space voice input uses.
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class VoiceEngine(override val stringRes: Int) : ManagedPreferenceEnum {
    Azure(R.string.voice_engine_azure),
    SenseVoice(R.string.voice_engine_sensevoice);
}
