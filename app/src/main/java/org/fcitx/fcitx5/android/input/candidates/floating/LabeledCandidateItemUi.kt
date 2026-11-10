/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.graphics.Color
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    setupTextView: TextView.() -> Unit
) : Ui {

    override val root = textView {
        setupTextView(this)
        org.fcitx.fcitx5.android.input.glass.Glass.applyKeyTextShadow(this, theme)
    }

    fun update(candidate: CandidateWord, active: Boolean) {
        val labelFg = if (active) theme.genericActiveForegroundColor else theme.candidateLabelColor
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        root.text = buildSpannedString {
            color(labelFg) {
                append(candidate.label)
            }
            color(fg) {
                append(candidate.text)
            }
            if (candidate.comment.isNotBlank()) {
                if (candidate.spaceBetweenComment) {
                    append(" ")
                }
                color(altFg) {
                    append(candidate.comment)
                }
            }
        }
        val bg = if (active) theme.genericActiveBackgroundColor else Color.TRANSPARENT
        if (active && org.fcitx.fcitx5.android.input.glass.Glass.style(theme).let { it.enabled && it.targetCandidates }) {
            root.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dp(6f)
                setColor(theme.genericActiveBackgroundColor)
            }
        } else {
            root.backgroundColor = bg
        }
    }
}
