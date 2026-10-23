/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: shorter labels for the 更多 panel tiles. Rime's switches are named "current → next"
 * (翻译关 → 翻译开, 开启预测 → 关闭预测, 简体 → 繁体); a tile only shows the current side, and for
 * on / off switches drops the 开 / 关 words and shows the state by color instead.
 * A few 白霜 switches whose state words say little (💀 / 😄, 地 / 煋) get a fixed name, and
 * 固顶 is left off the panel.
 */
package org.fcitx.fcitx5.android.input.status

object StatusSwitchLabel {

    /**
     * @param on true / false for an on-off switch, null when the label doesn't say
     * @param icon text drawn large on the tile, instead of the label's first character
     */
    data class Shown(val label: String, val on: Boolean?, val icon: String? = null)

    private val OFF = listOf("关闭", "关", "不")
    private val ON = listOf("开启", "打开", "开")

    /** @param onState the state name (from the schema's `states`) that means on */
    private class Known(val icon: String, val label: String, val onState: String)

    /** by Rime option name; fcitx5-rime names the actions "fcitx-rime-<schema>-<option>" */
    private val KNOWN = mapOf(
        "emoji" to Known("Emoji", "表情候选", "😄"),
        "mars" to Known("火", "火星文", "煋"),
        "chaifen" to Known("拆", "拆分提示", "拆分提示"),
    )

    /** options not shown on the panel */
    private val HIDDEN = setOf("pin_cand")

    private fun option(actionName: String?): String? =
        actionName?.takeIf { it.startsWith("fcitx-rime-") }?.substringAfterLast('-')

    fun hidden(actionName: String) = option(actionName) in HIDDEN

    fun of(text: String, actionName: String? = null): Shown {
        val sides = text.split('→').map { it.trim() }
        val current = sides.first()
        // not a "current → next" switch
        if (sides.size < 2 || current.isEmpty()) return Shown(text, null)
        KNOWN[option(actionName)]?.let { return Shown(it.label, current == it.onState, it.icon) }
        val next = sides[1]
        val off = OFF.any { current.contains(it) }
        // "拆分提示 → 拆分不提示": no 开 / 关 word on the current side, but the next one is off
        val on = !off && (ON.any { current.contains(it) } || OFF.any { next.contains(it) })
        if (!off && !on) return Shown(current, null)
        val stripped = (OFF + ON).fold(current) { s, w -> s.replace(w, "") }.trim()
        return Shown(stripped.ifEmpty { current }, on)
    }
}
