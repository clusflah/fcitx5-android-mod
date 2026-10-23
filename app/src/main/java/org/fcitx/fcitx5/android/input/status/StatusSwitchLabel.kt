/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: shorter labels for the 更多 panel tiles. Rime's switches are named "current → next"
 * (翻译关 → 翻译开, 开启预测 → 关闭预测, 简体 → 繁体); a tile only shows the current side, and for
 * on / off switches drops the 开 / 关 words and shows the state by color instead.
 */
package org.fcitx.fcitx5.android.input.status

object StatusSwitchLabel {

    /** @param on true / false for an on-off switch, null when the label doesn't say */
    data class Shown(val label: String, val on: Boolean?)

    private val OFF = listOf("关闭", "关", "不")
    private val ON = listOf("开启", "打开", "开")

    fun of(text: String): Shown {
        val current = text.split('→').first().trim()
        // not a "current → next" switch
        if (current == text.trim() || current.isEmpty()) return Shown(text, null)
        val off = OFF.any { current.contains(it) }
        val on = !off && ON.any { current.contains(it) }
        if (!off && !on) return Shown(current, null)
        val stripped = (OFF + ON).fold(current) { s, w -> s.replace(w, "") }.trim()
        return Shown(stripped.ifEmpty { current }, on)
    }
}
