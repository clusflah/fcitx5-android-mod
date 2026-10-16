/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.view.View
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.T9Pinyin
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.horizontalPadding

class PreeditComponent : UniqueComponent<PreeditComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val theme by manager.theme()

    val ui by lazy {
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        val bkgColor =
            if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor
        PreeditUi(context, theme, setupTextView = {
            backgroundColor = bkgColor
            horizontalPadding = dp(8)
        }).apply {
            // TODO make it customizable
            root.alpha = 0.8f
            root.visibility = View.INVISIBLE
        }
    }

    // mod: on the 九宫格 the preedit shows the first candidate's pinyin instead of digits
    private var lastPanel = FcitxEvent.InputPanelEvent.Data()
    private var firstComment: String? = null

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        firstComment = data.candidates.firstOrNull()?.comment
        if (T9Pinyin.active) show(lastPanel)
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        lastPanel = data
        show(data)
    }

    private fun show(data: FcitxEvent.InputPanelEvent.Data) {
        val pinyin = if (T9Pinyin.active)
            T9Pinyin.displayPreedit(
                data.preedit.toString(),
                data.preedit.cursor.takeIf { it >= 0 } ?: data.preedit.toString().length,
                firstComment
            ) else null
        ui.update(
            if (pinyin == null) data
            else data.copy(preedit = FormattedText(arrayOf(pinyin.first), intArrayOf(0), pinyin.second))
        )
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.INVISIBLE
    }
}
