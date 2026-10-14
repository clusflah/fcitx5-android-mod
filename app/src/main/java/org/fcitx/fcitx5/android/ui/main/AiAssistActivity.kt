/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * LLM assistant (大模型) dialog. Opened from the keyboard toolbar.
 * Input is prefilled from the clipboard; both input and reply are editable;
 * "输入" types the reply into the app the user came from.
 * Replies are usually Markdown; they can be shown as plain text, rich text or raw Markdown.
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.ClipData
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.AdapterView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.ai.AiClient
import org.fcitx.fcitx5.android.data.ai.AiConfig
import org.fcitx.fcitx5.android.data.ai.AiPendingCommit
import org.fcitx.fcitx5.android.data.ai.AiPrompt
import org.fcitx.fcitx5.android.data.ai.AiProvider
import org.fcitx.fcitx5.android.data.ai.AiTranslate
import org.fcitx.fcitx5.android.data.ai.MarkdownText
import org.fcitx.fcitx5.android.data.ai.TranslateEngine
import org.fcitx.fcitx5.android.utils.clipboardManager
import splitties.dimensions.dp

class AiAssistActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLIPBOARD = "clipboard"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TEXT = "text"
        const val MODE_TRANSLATE = "translate"

        private const val FORMAT_PLAIN = 0
        private const val FORMAT_RICH = 1
        private const val FORMAT_MARKDOWN = 2
        private val FORMAT_LABELS = listOf("纯文本", "富文本", "Markdown")
    }

    /** translate mode: the spinner picks the translation engine instead of the assistant's provider */
    private val translateEngines by lazy { TranslateEngine.all }

    private val translateMode by lazy { intent.getStringExtra(EXTRA_MODE) == MODE_TRANSLATE }
    private lateinit var directionLabel: TextView
    private var debounceJob: Job? = null

    private lateinit var providerSpinner: Spinner
    private lateinit var promptBar: LinearLayout
    private lateinit var input: EditText
    private lateinit var output: EditText
    private lateinit var sendBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var resultBox: LinearLayout
    private lateinit var formatBar: LinearLayout

    /** the model's reply as received (Markdown); [output] shows it in [format] */
    private var rawReply = ""
    private var format = FORMAT_PLAIN

    private var prompts = mutableListOf<AiPrompt>()
    private var promptId: String? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(if (translateMode) R.string.ai_translate else R.string.ai_assist)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        prompts = AiConfig.prompts(this)
        // translations are plain sentences; converting them could eat literal * or _
        format = if (translateMode) FORMAT_MARKDOWN else AiConfig.outputFormat(this)
        promptId = AiConfig.lastPrompt(this)?.takeIf { id -> prompts.any { it.id == id } }
            ?: prompts.firstOrNull()?.id

        val pad = dp(14)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }

        // provider + settings
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "模型"; setPadding(0, 0, dp(8), 0) })
        providerSpinner = Spinner(this).apply {
            if (translateMode) {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                    translateEngines.map { it.label })
                setSelection(translateEngines.indexOfFirst { it.id == AiTranslate.engine(context).id }.coerceAtLeast(0))
            } else {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                    AiProvider.entries.map { it.label })
                setSelection(AiConfig.provider(context).ordinal)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (translateMode) AiTranslate.setEngine(context, translateEngines[pos])
                    else AiConfig.setProvider(context, AiProvider.entries[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        top.addView(providerSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(smallButton("⚙ 设置") { openSettings() })
        root.addView(top)

        // prompts
        promptBar = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(promptBar)
        })

        if (translateMode) promptBar.visibility = View.GONE
        directionLabel = TextView(this).apply {
            textSize = 13f; setPadding(0, dp(6), 0, 0)
            text = AiTranslate.directionLabel(context, "")
            visibility = if (translateMode) View.VISIBLE else View.GONE
        }
        root.addView(directionLabel)

        // input
        input = EditText(this).apply {
            hint = if (translateMode) "输入要翻译的内容" else "输入内容（已自动填入剪贴板）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; maxLines = 6; gravity = Gravity.TOP
            setText(
                if (translateMode) intent.getStringExtra(EXTRA_TEXT) ?: ""
                else intent.getStringExtra(EXTRA_CLIPBOARD) ?: ""
            )
        }
        if (translateMode) {
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    val t = e?.toString().orEmpty()
                    directionLabel.text = AiTranslate.directionLabel(this@AiAssistActivity, t)
                    // translate automatically after the user stops typing
                    debounceJob?.cancel()
                    if (t.isBlank()) return
                    debounceJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(900)
                        send(auto = true)
                    }
                }
            })
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        val inputActions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        inputActions.addView(smallButton("粘贴剪贴板") { readClipboard()?.let { input.setText(it) } })
        inputActions.addView(smallButton("清空") { input.setText("") })
        inputActions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        inputActions.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)))
        sendBtn = Button(this).apply {
            text = if (translateMode) "翻译" else "发送"
            setOnClickListener { debounceJob?.cancel(); send() }
        }
        inputActions.addView(sendBtn)
        root.addView(inputActions)

        // result
        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val resultHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
        }
        resultHeader.addView(TextView(this).apply { text = "模型回复（可修改）" },
            LinearLayout.LayoutParams(0, -2, 1f))
        formatBar = LinearLayout(this).apply {
            visibility = if (translateMode) View.GONE else View.VISIBLE
        }
        resultHeader.addView(formatBar)
        resultBox.addView(resultHeader)
        renderFormats()
        output = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; maxLines = 10; gravity = Gravity.TOP
        }
        resultBox.addView(output, LinearLayout.LayoutParams(-1, -2))
        val outActions = LinearLayout(this).apply { gravity = Gravity.END }
        outActions.addView(smallButton("重新生成") { send() })
        outActions.addView(smallButton("复制") {
            clipboardManager.setPrimaryClip(clipOf(output.text))
            Toast.makeText(this@AiAssistActivity, "已复制", Toast.LENGTH_SHORT).show()
        })
        outActions.addView(Button(this).apply {
            text = "输入"
            setOnClickListener {
                val t: CharSequence = if (format == FORMAT_RICH) styledCopy(output.text) else output.text.toString()
                if (t.isNotEmpty()) {
                    // backup: also put it on the clipboard in case the target app ignores it
                    runCatching { clipboardManager.setPrimaryClip(clipOf(t)) }
                    if (intent.hasExtra(EXTRA_CLIPBOARD) || intent.hasExtra(EXTRA_TEXT)) {
                        AiPendingCommit.set(t)
                    } else {
                        // opened from settings: no editor to type into
                        Toast.makeText(this@AiAssistActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            }
        })
        resultBox.addView(outActions)
        root.addView(resultBox)

        setContentView(ScrollView(this).apply { addView(root) })
        renderPrompts()
        // selected text passed in from the keyboard: translate right away
        if (translateMode && input.text.isNotBlank()) send(auto = true)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ only allows reading the clipboard while focused; fill it in if the IME couldn't.
        if (!translateMode && hasFocus && input.text.isEmpty() && resultBox.visibility == View.GONE) {
            readClipboard()?.let { input.setText(it) }
        }
    }

    private fun readClipboard(): String? = runCatching {
        clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun smallButton(label: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.buttonStyleSmall).apply {
            text = label; isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun renderFormats() {
        formatBar.removeAllViews()
        FORMAT_LABELS.forEachIndexed { f, label ->
            formatBar.addView(smallButton(label) { setFormat(f) }.apply {
                alpha = if (f == format) 1f else 0.5f
            })
        }
    }

    private fun setFormat(f: Int) {
        if (f == format) return
        // edits made to the raw Markdown carry over; edits to converted text can't be mapped back
        if (format == FORMAT_MARKDOWN) rawReply = output.text.toString()
        format = f
        AiConfig.setOutputFormat(this, f)
        renderFormats()
        showReply()
    }

    private fun showReply() {
        output.setText(
            when (format) {
                FORMAT_PLAIN -> MarkdownText.toPlain(rawReply)
                FORMAT_RICH -> MarkdownText.render(rawReply)
                else -> rawReply
            }
        )
    }

    /** Only the styles the Markdown renderer adds, without the EditText's own spans. */
    private fun styledCopy(text: CharSequence): CharSequence {
        val copy = SpannableStringBuilder(text.toString())
        if (text is Spanned) {
            text.getSpans(0, text.length, Any::class.java).forEach { sp ->
                if (sp is StyleSpan || sp is RelativeSizeSpan || sp is TypefaceSpan ||
                    sp is StrikethroughSpan || sp is URLSpan
                ) copy.setSpan(sp, text.getSpanStart(sp), text.getSpanEnd(sp), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return copy
    }

    /** Rich text mode also puts HTML on the clipboard, so pasting into notes / mail keeps the formatting. */
    private fun clipOf(text: CharSequence): ClipData {
        val plain = text.toString()
        if (format != FORMAT_RICH) return ClipData.newPlainText("ai", plain)
        val html = HtmlCompat.toHtml(styledCopy(text) as Spanned, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
        return ClipData.newHtmlText("ai", plain, html)
    }

    private fun renderPrompts() {
        promptBar.removeAllViews()
        prompts.forEach { p ->
            promptBar.addView(smallButton(p.name) {
                promptId = p.id; AiConfig.setLastPrompt(this, p.id); renderPrompts()
            }.apply {
                alpha = if (p.id == promptId) 1f else 0.5f
                setOnLongClickListener { promptMenu(p); true }
            })
        }
        promptBar.addView(smallButton("＋") { editPrompt(null) })
    }

    private fun send(auto: Boolean = false) {
        val text = input.text.toString()
        if (text.isBlank()) {
            if (!auto) Toast.makeText(this@AiAssistActivity, "内容为空", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = if (translateMode) "" else prompts.find { it.id == promptId }?.content.orEmpty()
        val system: String
        val user: String
        if (prompt.contains("{{input}}")) {
            system = ""; user = prompt.replace("{{input}}", text)
        } else {
            system = prompt; user = text
        }
        job?.cancel()
        sendBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        job = lifecycleScope.launch {
            try {
                val ctx = this@AiAssistActivity
                val reply = if (translateMode) {
                    val r = AiTranslate.translateDetailed(ctx, text, translateEngines[providerSpinner.selectedItemPosition])
                    // say so when another engine than the selected one translated
                    directionLabel.text = AiTranslate.directionLabel(ctx, text) + (r.note?.let { "（$it）" } ?: "")
                    r.text
                } else {
                    val provider = AiProvider.entries[providerSpinner.selectedItemPosition]
                    AiClient.chat(
                        provider, AiConfig.baseUrl(ctx, provider), AiConfig.apiKey(ctx, provider),
                        AiConfig.model(ctx, provider), system, user
                    )
                }
                output.hint = ""
                rawReply = reply
                showReply()
                resultBox.visibility = View.VISIBLE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (auto) {
                    output.setText("")
                    output.hint = "翻译失败：" + (e.message ?: e.toString())
                    resultBox.visibility = View.VISIBLE
                    return@launch
                }
                AlertDialog.Builder(this@AiAssistActivity)
                    .setTitle("请求失败").setMessage(e.message ?: e.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton("设置") { _, _ -> openSettings() }
                    .show()
            } finally {
                sendBtn.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun field(hint: String, value: String, password: Boolean = false, multi: Boolean = false) =
        EditText(this).apply {
            this.hint = hint; setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or when {
                password -> InputType.TYPE_TEXT_VARIATION_PASSWORD
                multi -> InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> 0
            }
        }

    private fun openSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
        }
        val fields = AiProvider.entries.associateWith { p ->
            box.addView(TextView(this).apply {
                text = p.label; textSize = 16f; setPadding(0, dp(12), 0, 0)
            })
            val key = field("API Key", AiConfig.apiKey(this, p), password = true)
            val model = field("模型（默认 ${p.defaultModel}）", AiConfig.rawModel(this, p))
            val url = field("接口地址（默认 ${p.defaultBaseUrl}）", AiConfig.rawBaseUrl(this, p))
            box.addView(key); box.addView(model); box.addView(url)
            Triple(key, model, url)
        }
        box.addView(TextView(this).apply {
            textSize = 12f; setPadding(0, dp(12), 0, 0)
            text = "提示：Prompt 中可用 {{input}} 表示输入内容的位置；不含 {{input}} 时，Prompt 作为系统指令发送。长按 Prompt 可编辑、排序或删除。"
        })
        AlertDialog.Builder(this).setTitle("大模型设置")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                fields.forEach { (p, f) ->
                    AiConfig.saveProvider(this, p, f.first.text.toString(), f.second.text.toString(), f.third.text.toString())
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun editPrompt(p: AiPrompt?) {
        val name = field("名称，如：润色", p?.name ?: "")
        val content = field("Prompt 内容，可用 {{input}} 占位", p?.content ?: "", multi = true).apply {
            minLines = 3
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(name); addView(content)
        }
        AlertDialog.Builder(this).setTitle(if (p == null) "新建 Prompt" else "编辑 Prompt").setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                if (p == null) prompts.add(AiPrompt(name = n, content = content.text.toString()).also { promptId = it.id })
                else { p.name = n; p.content = content.text.toString() }
                AiConfig.savePrompts(this, prompts); renderPrompts()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun promptMenu(p: AiPrompt) {
        AlertDialog.Builder(this).setTitle(p.name)
            .setItems(arrayOf("编辑", "左移", "右移", "删除")) { _, which ->
                val i = prompts.indexOf(p)
                when (which) {
                    0 -> { editPrompt(p); return@setItems }
                    1 -> if (i > 0) prompts.add(i - 1, prompts.removeAt(i))
                    2 -> if (i < prompts.lastIndex) prompts.add(i + 1, prompts.removeAt(i))
                    3 -> prompts.remove(p)
                }
                AiConfig.savePrompts(this, prompts); renderPrompts()
            }.show()
    }
}
