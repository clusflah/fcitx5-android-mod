/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * AI 联想: sends the text before the cursor to the configured LLM and shows
 * a few continuations; tapping one types it directly (we stay inside the IME,
 * so the editor connection is never lost).
 */
package org.fcitx.fcitx5.android.input.phrase

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.ai.AiSuggest
import org.fcitx.fcitx5.android.data.ai.AiConfig
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class AiPredictWindow : InputWindow.ExtendedInputWindow<AiPredictWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    override val title: String by lazy { context.getString(R.string.ai_predict) }

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var job: Job? = null

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = context.dp(8f)
    }

    override fun onCreateView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
        }
        status = TextView(context).apply {
            textSize = 13f
            setTextColor(theme.altKeyTextColor)
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        }
        root.addView(status)
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(context).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = LinearLayout(context).apply { gravity = Gravity.END }
        actions.addView(actionButton("换一批") { request() })
        actions.addView(actionButton("返回键盘") { windowManager.attachWindow(KeyboardWindow) })
        root.addView(actions)
        return root
    }

    private fun actionButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        background = rounded(theme.keyBackgroundColor)
        setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = context.dp(8) }
        setOnClickListener { onClick() }
    }

    override fun onCreateBarExtension(): View =
        ToolButton(context, R.drawable.ic_baseline_refresh_24, theme).apply {
            contentDescription = "换一批"
            layoutParams = ViewGroup.LayoutParams(context.dp(40), context.dp(40))
            setOnClickListener { request() }
        }

    override fun onAttached() {
        request()
    }

    override fun onDetached() {
        job?.cancel()
    }

    private fun request() {
        val before = runCatching {
            service.currentInputConnection?.getTextBeforeCursor(AiSuggest.MAX_CONTEXT, 0)?.toString()
        }.getOrNull().orEmpty()
        list.removeAllViews()
        if (before.isBlank()) {
            status.text = "先在输入框里写一些内容，AI 会根据上文给出续写建议"
            return
        }
        status.text = "AI 正在思考…"
        val ctx = context
        val provider = AiConfig.predictProvider(ctx) ?: AiConfig.provider(ctx)
        job?.cancel()
        job = service.lifecycleScope.launch {
            try {
                val items = AiSuggest.predict(ctx, before)
                if (items.isEmpty()) {
                    status.text = "没有得到建议，点“换一批”重试"
                    return@launch
                }
                status.text = "点选续写内容直接输入（${provider.label}）"
                items.forEach { addSuggestion(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = (e.message ?: e.toString()) + "\n可在 设置 → 大模型助手 中检查 API 配置"
            }
        }
    }

    private fun addSuggestion(text: String) {
        list.addView(TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextColor(theme.keyTextColor)
            background = rounded(theme.clipboardEntryColor)
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = context.dp(6) }
            setOnClickListener {
                service.commitText(text)
                windowManager.attachWindow(KeyboardWindow)
            }
        })
    }
}
