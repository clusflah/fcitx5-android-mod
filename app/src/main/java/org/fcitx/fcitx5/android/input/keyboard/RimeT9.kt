/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 九宫格: switches Rime to its nine-key schema while the T9 keyboard is shown, and back afterwards.
 * The schema itself comes with the user's Rime config (白霜拼音 ships "rime_frost_t9", named 中文九键); it spells every pinyin with digits too, so the keys 2–9 type Chinese directly.
 * Schemas are picked through the Rime addon's status-area menu, by their display name.
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

object RimeT9 {

    const val RIME = "rime"

    /** a schema whose name contains one of these is the nine-key one */
    private val T9_NAMES = listOf("九键", "九宮", "九宫", "T9", "t9")

    /** schema name to go back to after the T9 keyboard; blank = we didn't switch */
    private var previousSchema by AppPrefs.getInstance().internal.t9PreviousSchema

    fun isT9Schema(name: String) = T9_NAMES.any { name.contains(it) }

    /** The Rime "current schema" action; its menu lists the schemas. Only present while Rime is active. */
    private suspend fun FcitxAPI.rimeAction(): Action? =
        statusArea().firstOrNull { it.name == "fcitx-rime-im" }

    /** The schema Rime is on, from a status-area update; null while Rime isn't active or not ready. */
    fun currentSchema(actions: Array<Action>): String? =
        actions.firstOrNull { it.name == "fcitx-rime-im" }?.longText?.takeIf { it.isNotEmpty() }

    /**
     * Selects the nine-key schema. Returns false if the user has no nine-key schema (the keyboard
     * still works then, the keys just send digits), null if Rime isn't the current input method or
     * hasn't loaded its schemas yet (right after fcitx starts, e.g. when switching from another
     * keyboard app): KeyboardWindow tries again when the status area changes.
     */
    suspend fun FcitxAPI.enterT9(): Boolean? {
        if (inputMethodEntryCached.uniqueName != RIME) return null
        val rime = rimeAction() ?: return null
        val current = rime.longText
        if (isT9Schema(current)) return true
        val schemas = rime.menu?.filter { !it.isSeparator }
        if (current.isEmpty() || schemas.isNullOrEmpty()) return null
        val t9 = schemas.firstOrNull { isT9Schema(it.shortText) } ?: return false
        previousSchema = current
        activateAction(t9.id)
        return true
    }

    /** Goes back to the schema used before [enterT9], if we switched. */
    suspend fun FcitxAPI.leaveT9() {
        val previous = previousSchema
        if (previous.isBlank() || inputMethodEntryCached.uniqueName != RIME) return
        val rime = rimeAction() ?: return
        if (isT9Schema(rime.longText)) {
            rime.menu?.firstOrNull { !it.isSeparator && it.shortText == previous }
                ?.let { activateAction(it.id) }
        }
        previousSchema = ""
    }
}
