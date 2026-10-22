/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: the in-keyboard translate card above the toolbar ("中英互译 ∨ … 收起" and a field the keyboard
 * types into, see TranslateDraft). 翻译 or the return key translates the draft with the engine chosen
 * in the translate settings and sends the translation to the app. Hidden unless opened from the
 * toolbar's translate button.
 */
package org.fcitx.fcitx5.android.input.translate

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.ai.AiTranslate
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePresetMod
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import splitties.dimensions.dp

class TranslatePanel(private val service: FcitxInputMethodService, private val theme: Theme) {

    private val ctx = service

    private var job: Job? = null

    private val directionText = TextView(ctx).apply {
        textSize = 14f
        setTextColor(theme.altKeyTextColor)
        setPadding(ctx.dp(8), 0, ctx.dp(4), 0)
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { chooseTarget(it) }
    }

    private val field = TextView(ctx).apply {
        textSize = 16f
        setTextColor(theme.keyTextColor)
        setHintTextColor(theme.candidateCommentColor)
        hint = "输入要翻译的内容"
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 3
        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(8), ctx.dp(10))
        // a green bar where the text cursor would be
        setCompoundDrawablesRelative(GradientDrawable().apply {
            setColor(ThemePresetMod.JADE)
            setSize(ctx.dp(2), ctx.dp(20))
        }.also { it.setBounds(0, 0, ctx.dp(2), ctx.dp(20)) }, null, null, null)
        compoundDrawablePadding = ctx.dp(4)
    }

    private val sendButton = TextView(ctx).apply {
        text = "翻译"
        textSize = 14f
        setTextColor(0xffffffff.toInt())
        gravity = Gravity.CENTER
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(14f)
            setColor(ThemePresetMod.JADE)
        }
        setOnClickListener { translate() }
    }

    val view: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(4))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(14f)
                setColor(theme.keyBackgroundColor)
            }
            clipToOutline = true
            // header: 文A 中英互译 ∨ ............ 收起
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(theme.popupBackgroundColor)
                setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_translate)
                    imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
                }, LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)))
                addView(directionText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(TextView(ctx).apply {
                    text = "收起"
                    textSize = 14f
                    setTextColor(theme.altKeyTextColor)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { close() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(40)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, ctx.dp(10), 0)
                addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(sendButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(28)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // at least one line of field
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    val isOpen get() = view.visibility == View.VISIBLE

    fun open() {
        TranslateDraft.onChange = { refresh() }
        TranslateDraft.onSubmit = { translate() }
        TranslateDraft.start()
        view.visibility = View.VISIBLE
        refresh()
    }

    fun close() {
        job?.cancel()
        targetMenu?.dismiss()
        TranslateDraft.stop()
        TranslateDraft.onChange = null
        TranslateDraft.onSubmit = null
        view.visibility = View.GONE
    }

    fun toggle() = if (isOpen) close() else open()

    private fun refresh() {
        val draft = TranslateDraft.text.toString()
        directionText.text = "中英互译 · ${AiTranslate.directionLabel(ctx, draft).substringBefore('，')}  ∨"
        val preedit = TranslateDraft.preedit
        field.text = if (draft.isEmpty() && preedit.isEmpty()) "" else SpannableStringBuilder(draft).apply {
            if (preedit.isNotEmpty()) {
                val start = length
                append(preedit)
                setSpan(UnderlineSpan(), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(ThemePresetMod.JADE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private var targetMenu: PopupWindow? = null

    /** The target language list, drawn in the keyboard theme's colors like the card itself. */
    private fun chooseTarget(anchor: View) {
        targetMenu?.dismiss()
        val current = AiTranslate.target(ctx)
        lateinit var window: PopupWindow
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(6), 0, ctx.dp(6))
            AiTranslate.TARGETS.forEach { t ->
                val selected = t == current
                addView(TextView(ctx).apply {
                    text = if (selected) "中文 → $t  ✓" else "中文 → $t"
                    textSize = 15f
                    setTextColor(if (selected) ThemePresetMod.JADE else theme.popupTextColor)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(theme.keyPressHighlightColor))
                    }
                    setOnClickListener {
                        AiTranslate.setTarget(ctx, t)
                        refresh()
                        window.dismiss()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(44)))
            }
        }
        val content = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(14f)
                setColor(theme.popupBackgroundColor)
                setStroke(ctx.dp(1), theme.dividerColor)
            }
            clipToOutline = true
            addView(list)
        }
        val width = ctx.dp(200)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        val height = minOf(list.measuredHeight, ctx.dp(320))
        window = PopupWindow(content, width, height, true).apply {
            elevation = ctx.dp(8f)
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { targetMenu = null }
        }
        targetMenu = window
        // open upwards over the app, the way the card sits on top of the keyboard
        window.showAsDropDown(anchor, 0, -(anchor.height + height + ctx.dp(4)))
        // keep the chosen language in view
        AiTranslate.TARGETS.indexOf(current).takeIf { it > 0 }?.let { i ->
            content.post { content.scrollTo(0, list.getChildAt(i).top - ctx.dp(6)) }
        }
    }

    private fun translate() {
        val text = TranslateDraft.text.toString().trim()
        if (text.isEmpty() || job?.isActive == true) return
        field.hint = "翻译中…"
        job = service.lifecycleScope.launch {
            val result = runCatching { AiTranslate.translateDetailed(ctx, text) }
            field.hint = "输入要翻译的内容"
            result.onSuccess { r ->
                r.note?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
                // the translation goes to the app, not back into the draft
                val wasActive = TranslateDraft.active
                if (wasActive) TranslateDraft.stop()
                service.commitText(r.text.toString())
                if (wasActive && isOpen) {
                    TranslateDraft.start()
                }
            }.onFailure {
                android.widget.Toast.makeText(ctx, "翻译失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
