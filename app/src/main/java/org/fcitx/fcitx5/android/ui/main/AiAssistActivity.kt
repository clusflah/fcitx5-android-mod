/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * LLM assistant (大模型) dialog. Opened from the keyboard toolbar.
 * Input is prefilled from the clipboard; both input and reply are editable;
 * "输入" types the reply into the app the user came from.
 * Replies are usually Markdown; they can be shown as plain text, rich text or raw Markdown.
 * "画图" generates an image from the input instead; it can be inserted, copied, saved or shared.
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
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
import android.widget.ImageView
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
import org.fcitx.fcitx5.android.data.ai.AiImage
import org.fcitx.fcitx5.android.data.ai.AiImageStore
import org.fcitx.fcitx5.android.data.ai.AiPendingCommit
import org.fcitx.fcitx5.android.data.ai.AiPrompt
import org.fcitx.fcitx5.android.data.ai.AiPromptRunner
import org.fcitx.fcitx5.android.data.ai.AiProvider
import org.fcitx.fcitx5.android.data.ai.AiTranslate
import org.fcitx.fcitx5.android.data.ai.MarkdownText
import org.fcitx.fcitx5.android.data.ai.TranslateEngine
import org.fcitx.fcitx5.android.utils.clipboardManager
import splitties.dimensions.dp

class AiAssistActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLIPBOARD = "clipboard"
        private const val CHIP_SELECTED = "selected"
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
    private lateinit var promptBar: ViewGroup
    private lateinit var input: EditText
    private lateinit var output: EditText
    private lateinit var sendBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var resultBox: LinearLayout
    private lateinit var formatBar: LinearLayout

    /** the model's reply as received (Markdown); [output] shows it in [format] */
    private var rawReply = ""
    private var format = FORMAT_PLAIN

    // image generation (画图)
    private lateinit var imageBtn: Button
    private lateinit var resultLabel: TextView
    private lateinit var imageView: ImageView
    private lateinit var imageActions: LinearLayout
    private var image: AiImage? = null
    private var imageUri: Uri? = null
    /** what 重新生成 repeats */
    private var lastWasImage = false

    private var prompts = mutableListOf<AiPrompt>()
    private var promptId: String? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(if (translateMode) R.string.ai_translate else R.string.ai_assist)
        // mod: card style: the keyboard's colors and shapes, with our own title inside the card
        val styled = AiDialogStyle.enabled
        if (styled) supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        prompts = AiConfig.prompts(this)
        // translations are plain sentences; converting them could eat literal * or _
        format = if (translateMode) FORMAT_MARKDOWN else AiConfig.outputFormat(this)
        promptId = AiConfig.lastPrompt(this)?.takeIf { id -> prompts.any { it.id == id } }
            ?: prompts.firstOrNull()?.id

        val pad = if (styled) dp(10) else dp(14)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        // card style: title on the left, a compact model picker and ⚙ in the top right corner
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (styled) {
            header.addView(TextView(this).apply {
                setText(if (translateMode) R.string.ai_translate else R.string.ai_assist)
                AiDialogStyle.title(this)
                setPadding(dp(4), dp(2), 0, dp(2))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(header)
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
        if (styled) {
            header.addView(providerSpinner, LinearLayout.LayoutParams(-2, dp(30)))
            header.addView(smallButton("⚙") { openSettings() }.apply {
                contentDescription = "设置"
            }, LinearLayout.LayoutParams(dp(34), dp(30)))
        } else {
            top.addView(providerSpinner, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(smallButton("⚙ 设置") { openSettings() })
            root.addView(top)
        }

        // prompts: one row that scrolls sideways (the card style keeps it low, see AiDialogStyle.chip)
        promptBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
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
            hint = if (translateMode) "输入要翻译的内容" else "输入内容，或点下面的“粘贴剪贴板”"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; maxLines = 6; gravity = Gravity.TOP
            // mod: the clipboard is no longer filled in by itself (it may be unrelated, or private);
            // the paste button below shows what it holds
            setText(if (translateMode) intent.getStringExtra(EXTRA_TEXT) ?: "" else "")
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
        val clip = intent.getStringExtra(EXTRA_CLIPBOARD)?.takeIf { it.isNotBlank() }
        val pasteLabel = clip?.let { c ->
            val one = c.replace('\n', ' ').trim()
            "粘贴剪贴板：" + if (one.length > 10) one.take(10) + "…" else one
        } ?: "粘贴剪贴板"
        inputActions.addView(smallButton(pasteLabel) { (readClipboard() ?: clip)?.let { input.setText(it) } })
        inputActions.addView(smallButton("清空") { input.setText("") })
        inputActions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        inputActions.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)))
        imageBtn = smallButton("画图") { generateImage() }.apply {
            visibility = if (translateMode) View.GONE else View.VISIBLE
        }
        inputActions.addView(imageBtn)
        sendBtn = Button(this).apply {
            text = if (translateMode) "翻译" else "发送"
            tag = AiDialogStyle.PRIMARY
            setOnClickListener { debounceJob?.cancel(); send() }
        }
        inputActions.addView(sendBtn)
        root.addView(inputActions)

        // result
        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val resultHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
        }
        resultLabel = TextView(this).apply { text = "模型回复（可修改）" }
        resultHeader.addView(resultLabel, LinearLayout.LayoutParams(0, -2, 1f))
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
        imageView = ImageView(this).apply {
            adjustViewBounds = true; maxHeight = dp(360); visibility = View.GONE
            setPadding(0, dp(6), 0, dp(6))
        }
        resultBox.addView(imageView, LinearLayout.LayoutParams(-1, -2))
        imageActions = LinearLayout(this).apply { gravity = Gravity.END; visibility = View.GONE }
        imageActions.addView(smallButton("保存到相册") { saveImage() })
        imageActions.addView(smallButton("分享") { shareImage() })
        resultBox.addView(imageActions)
        val outActions = LinearLayout(this).apply { gravity = Gravity.END }
        outActions.addView(smallButton("重新生成") { if (lastWasImage) generateImage() else send() })
        outActions.addView(smallButton("复制") {
            val uri = imageUri
            if (lastWasImage && uri != null) {
                clipboardManager.setPrimaryClip(ClipData.newUri(contentResolver, "ai image", uri))
            } else {
                clipboardManager.setPrimaryClip(clipOf(output.text))
            }
            Toast.makeText(this@AiAssistActivity, "已复制", Toast.LENGTH_SHORT).show()
        })
        outActions.addView(Button(this).apply {
            tag = AiDialogStyle.PRIMARY
            text = "输入"
            setOnClickListener {
                if (lastWasImage) { insertImage(); return@setOnClickListener }
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
        if (styled) {
            AiDialogStyle.styleWindow(window)
            // sit lower on the screen, nearer the keyboard and the thumb: a quarter of the screen
            // below the middle, less half the card's own height once it is measured
            val lower = resources.displayMetrics.heightPixels / 4
            window.attributes = window.attributes.apply { y = lower }
            window.decorView.post {
                window.attributes = window.attributes.apply {
                    y = (lower - window.decorView.height / 2).coerceAtLeast(0)
                }
            }
            AiDialogStyle.apply(root)
            styleChips(formatBar)
            AiDialogStyle.compact(providerSpinner, header.getChildAt(header.childCount - 1) as Button)
            // keep the card low: the field grows as text is typed or pasted
            input.minLines = 1
        }
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
                tag = if (f == format) CHIP_SELECTED else null
            })
        }
        styleChips(formatBar)
    }

    /** mod: card style look for the prompt / format chips, see AiDialogStyle.chip */
    private fun styleChips(bar: ViewGroup) {
        if (!AiDialogStyle.enabled) return
        val big = bar === promptBar
        for (i in 0 until bar.childCount) {
            (bar.getChildAt(i) as? Button)?.let { AiDialogStyle.chip(it, it.tag == CHIP_SELECTED, big) }
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
                tag = if (p.id == promptId) CHIP_SELECTED else null
                setOnLongClickListener { promptMenu(p); true }
            })
        }
        promptBar.addView(smallButton("＋") { editPrompt(null) })
        styleChips(promptBar)
    }

    private fun send(auto: Boolean = false) {
        val text = input.text.toString()
        if (text.isBlank()) {
            if (!auto) Toast.makeText(this@AiAssistActivity, "内容为空", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = if (translateMode) "" else prompts.find { it.id == promptId }?.content.orEmpty()
        val (system, user) = AiPromptRunner.messages(prompt, text)
        job?.cancel()
        sendBtn.isEnabled = false
        imageBtn.isEnabled = false
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
                showTextResult()
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
                val msg = e.message ?: e.toString()
                AlertDialog.Builder(this@AiAssistActivity)
                    .setTitle("请求失败").setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton("设置") { _, _ -> openSettings() }
                    .showIfAlive(this@AiAssistActivity, "大模型请求失败：$msg")
            } finally {
                sendBtn.isEnabled = true
                imageBtn.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun showTextResult() {
        lastWasImage = false
        resultLabel.text = "模型回复（可修改）"
        output.visibility = View.VISIBLE
        formatBar.visibility = if (translateMode) View.GONE else View.VISIBLE
        imageView.visibility = View.GONE
        imageActions.visibility = View.GONE
        resultBox.visibility = View.VISIBLE
    }

    /** 画图: the input (plus the selected prompt, if it uses {{input}}) describes the image. */
    private fun generateImage() {
        val text = input.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "请先输入图片描述", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = prompts.find { it.id == promptId }?.content.orEmpty()
        val description = if (prompt.contains("{{input}}")) prompt.replace("{{input}}", text) else text
        val provider = AiConfig.imageProvider(this)
        job?.cancel()
        sendBtn.isEnabled = false
        imageBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        job = lifecycleScope.launch {
            try {
                val ctx = this@AiAssistActivity
                val img = AiClient.generateImage(
                    provider, AiConfig.baseUrl(ctx, provider), AiConfig.apiKey(ctx, provider),
                    AiConfig.imageModel(ctx, provider), description
                )
                val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AiImageStore.save(ctx, img)
                }
                val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    decodeScaled(img.bytes, 1280)
                } ?: throw Exception("图片无法显示（格式不支持）")
                image = img; imageUri = uri
                lastWasImage = true
                imageView.setImageBitmap(bitmap)
                resultLabel.text = "生成的图片" + if (img.text.isNotBlank()) "：" + img.text.take(80) else ""
                output.visibility = View.GONE
                formatBar.visibility = View.GONE
                imageView.visibility = View.VISIBLE
                imageActions.visibility = View.VISIBLE
                resultBox.visibility = View.VISIBLE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                AlertDialog.Builder(this@AiAssistActivity)
                    .setTitle("生成图片失败").setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton("设置") { _, _ ->
                        startActivity(Intent(this@AiAssistActivity, AiSettingsActivity::class.java))
                    }
                    .showIfAlive(this@AiAssistActivity, "生成图片失败：$msg")
            } finally {
                sendBtn.isEnabled = true
                imageBtn.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    /** Downsampled so a 4K image doesn't take 64 MB of memory in the preview. */
    private fun decodeScaled(bytes: ByteArray, maxSide: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Inserted by the keyboard into the editor (if it accepts images); always copied as a backup. */
    private fun insertImage() {
        val uri = imageUri ?: return
        val img = image ?: return
        runCatching { clipboardManager.setPrimaryClip(ClipData.newUri(contentResolver, "ai image", uri)) }
        if (intent.hasExtra(EXTRA_CLIPBOARD) || intent.hasExtra(EXTRA_TEXT)) {
            AiPendingCommit.setImage(uri, img.mime)
        } else {
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun saveImage() {
        val img = image ?: return
        val ok = runCatching { AiImageStore.saveToGallery(this, img) }.getOrDefault(false)
        Toast.makeText(
            this,
            if (ok) "已保存到 相册 / Pictures/Fcitx5"
            else if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) "Android 10 以下请用“分享”保存"
            else "保存失败",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareImage() {
        val uri = imageUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = image?.mime ?: "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "ai image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "分享图片"))
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
                tag = AiDialogStyle.HEADING
            })
            val key = field("API Key", AiConfig.apiKey(this, p), password = true)
            val model = field("模型（默认 ${p.defaultModel}）", AiConfig.rawModel(this, p))
            val url = field("接口地址（默认 ${p.defaultBaseUrl}）", AiConfig.rawBaseUrl(this, p))
            box.addView(key); box.addView(model)
            box.addView(smallButton("获取模型列表") {
                AiModelPicker.show(this, p, url.text.toString(), key.text.toString(), image = false) { model.setText(it) }
            })
            box.addView(url)
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
            .setNegativeButton(android.R.string.cancel, null).showStyled()
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
            .setNegativeButton(android.R.string.cancel, null).showStyled()
    }

    private fun promptMenu(p: AiPrompt) {
        AlertDialog.Builder(this).setTitle(p.name)
            .setAdapter(
                AiDialogStyle.textAdapter(this, android.R.layout.simple_list_item_1, mutableListOf("编辑", "左移", "右移", "删除"))
            ) { _, which ->
                val i = prompts.indexOf(p)
                when (which) {
                    0 -> { editPrompt(p); return@setAdapter }
                    1 -> if (i > 0) prompts.add(i - 1, prompts.removeAt(i))
                    2 -> if (i < prompts.lastIndex) prompts.add(i + 1, prompts.removeAt(i))
                    3 -> prompts.remove(p)
                }
                AiConfig.savePrompts(this, prompts); renderPrompts()
            }.showStyled()
    }

    /** mod: show, dressed like the assistant itself when the card style is on */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        show().also { if (AiDialogStyle.enabled) AiDialogStyle.alert(it) }
}
