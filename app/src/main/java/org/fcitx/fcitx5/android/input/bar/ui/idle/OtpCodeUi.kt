/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Idle-bar strip offering the latest SMS verification code: tap the code to type it.
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui

class OtpCodeUi(override val ctx: Context, private val theme: Theme) : Ui {

    var onCodeClick: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val label = TextView(ctx).apply {
        textSize = 13f
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(theme.altKeyTextColor)
    }

    private val chip = TextView(ctx).apply {
        textSize = 17f
        isSingleLine = true
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(theme.keyTextColor)
        background = GradientDrawable().apply {
            setColor(theme.keyBackgroundColor); cornerRadius = ctx.dp(14f)
        }
        setPadding(ctx.dp(14), ctx.dp(5), ctx.dp(14), ctx.dp(5))
        setOnClickListener { onCodeClick?.invoke() }
    }

    private val close = TextView(ctx).apply {
        text = "✕"
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        contentDescription = "关闭"
        setOnClickListener { onDismiss?.invoke() }
    }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(8), 0, 0, 0)
        addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(chip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = ctx.dp(6) })
        addView(close, LinearLayout.LayoutParams(ctx.dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** A code to offer: "验证码 · 招商银行" + [ 123456 ] */
    fun showCode(code: String, source: String) {
        label.text = "验证码 · $source"
        chip.text = code
        chip.visibility = android.view.View.VISIBLE
    }

    /** Ask for the SMS permission instead of showing a code. */
    fun showPermissionPrompt() {
        label.text = "显示短信验证码需要读取短信权限"
        chip.text = "去允许"
        chip.visibility = android.view.View.VISIBLE
    }
}
