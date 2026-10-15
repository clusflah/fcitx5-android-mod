/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Settings page for the LLM assistant (大模型): API keys / models / endpoints, image generation
 * and custom prompts. Model names can be picked from the provider's model list.
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.ai.AiConfig
import org.fcitx.fcitx5.android.data.ai.AiPrompt
import org.fcitx.fcitx5.android.data.ai.AiProvider
import org.fcitx.fcitx5.android.data.ai.AiTranslate
import org.fcitx.fcitx5.android.data.ai.TranslateEngine
import splitties.dimensions.dp

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var providerSpinner: Spinner
    private lateinit var predictSpinner: Spinner
    private lateinit var translateSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var fields: Map<AiProvider, Triple<EditText, EditText, EditText>>
    private lateinit var promptList: LinearLayout
    private var prompts = mutableListOf<AiPrompt>()

    /** AI 联想 model name per provider; the field below the spinner edits the selected provider's entry */
    private lateinit var predictModelField: EditText
    private val predictModels = mutableMapOf<AiProvider, String>()
    private var shownPredictProvider: AiProvider? = null

    /** image generation model per provider, edited the same way as the AI 联想 model */
    private lateinit var imageSpinner: Spinner
    private lateinit var imageModelField: EditText
    private val imageModels = mutableMapOf<AiProvider, String>()
    private var shownImageProvider: AiProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prompts = AiConfig.prompts(this)
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(4), pad, pad)
        }

        box.addView(header("默认模型"))
        providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                AiProvider.entries.map { it.label })
            setSelection(AiConfig.provider(context).ordinal)
        }
        box.addView(providerSpinner)

        box.addView(header("AI 联想使用的模型"))
        predictSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf("与默认模型相同") + AiProvider.entries.map { it.label })
            setSelection(AiConfig.predictProvider(context)?.let { it.ordinal + 1 } ?: 0)
        }
        box.addView(predictSpinner)
        AiProvider.entries.forEach { p ->
            predictModels[p] = AiConfig.rawPredictModel(this, p).takeUnless { AiConfig.isRetired(it.trim()) } ?: ""
        }
        predictModelField = field("", "")
        box.addView(predictModelField)
        box.addView(listButton {
            val p = selectedPredictProvider()
            pickModel(p, image = false) { predictModelField.setText(it) }
        })
        box.addView(TextView(this).apply {
            textSize = 12f
            text = "联想要求快，留空时默认使用该服务最快的模型；助手默认使用最好的模型。" +
                    "本地模型留空时与下面填写的模型相同。"
        })

        // ---- 图片生成 ----
        box.addView(header("图片生成"))
        imageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                AiProvider.entries.map { it.label })
            setSelection(AiConfig.imageProvider(context).ordinal)
        }
        box.addView(imageSpinner)
        AiProvider.entries.forEach { p -> imageModels[p] = AiConfig.rawImageModel(this, p) }
        imageModelField = field("", "")
        box.addView(imageModelField)
        box.addView(listButton {
            val p = AiProvider.entries[imageSpinner.selectedItemPosition]
            pickModel(p, image = true) { imageModelField.setText(it) }
        })
        box.addView(TextView(this).apply {
            textSize = 12f
            text = "在大模型对话框里点“画图”，按输入的描述生成图片，使用下面填写的 API Key 和接口地址。" +
                    "Gemini 用图片模型（Nano Banana 系列，按张收费）；其他服务走 OpenAI 兼容的 /images/generations 接口，" +
                    "DeepSeek 和 Kimi 官方没有图片接口，把它们的接口地址改成支持生图的中转服务才能用。" +
                    "模型名经常更新，建议点“获取模型列表”选择。"
        })

        // ---- 中外互译 ----
        box.addView(header("中外互译"))
        box.addView(TextView(this).apply { text = "翻译使用"; textSize = 13f })
        translateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                TranslateEngine.all.map { it.label })
            setSelection(TranslateEngine.all.indexOfFirst { it.id == AiTranslate.engine(context).id }.coerceAtLeast(0))
        }
        box.addView(translateSpinner)
        box.addView(TextView(this).apply { text = "中文翻译成"; textSize = 13f; setPadding(0, dp(8), 0, 0) })
        targetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, AiTranslate.TARGETS)
            setSelection(AiTranslate.TARGETS.indexOf(AiTranslate.target(context)).coerceAtLeast(0))
        }
        box.addView(targetSpinner)
        // same setting as on the 语音模型 page; covers both voice and translation
        box.addView(android.widget.CheckBox(this).apply {
            text = "在线翻译不可用时改用离线翻译（需要已安装 Offline Translator）"
            val pref = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().voice.offlineFallback
            isChecked = pref.getValue()
            setOnCheckedChangeListener { _, checked -> pref.setValue(checked) }
        })
        box.addView(TextView(this).apply {
            textSize = 12f
            text = "外文一律翻译成简体中文。Index-Translate 是 B 站开源的翻译模型，免费、不需要 API Key；" +
                    "翻译时文字会发送到 B 站的服务器，不可用时自动改用默认模型。" +
                    "离线翻译需要另外安装开源的 Offline Translator App（GitHub: DavidVentura/offline-translator）" +
                    "并在其中下载语言包，文字不会离开手机。" +
                    "工具栏“文A”：点按打开翻译窗口，长按把输入框里的文字（或选中的文字）直接替换成译文。"
        })

        fields = AiProvider.entries.associateWith { p ->
            box.addView(header(p.label))
            if (p == AiProvider.Local) box.addView(TextView(this).apply {
                textSize = 12f
                text = "OpenAI 兼容的本地服务，例如 Pocket LLM：在它的 LAN Server 里设置密码并启动，" +
                        "这里的 API Key 填该密码，模型填 /v1/models 返回的模型 id（如 qwen3_litert）。"
            })
            val key = field(if (p == AiProvider.Local) "密码 / API Key" else "API Key", AiConfig.apiKey(this, p), password = true)
            // a shut-down model name is ignored (the default is used), so don't show it
            val model = field(
                "模型（默认 ${p.defaultModel}）",
                AiConfig.rawModel(this, p).takeUnless { AiConfig.isRetired(it.trim()) } ?: ""
            )
            val url = field("接口地址（默认 ${p.defaultBaseUrl}）", AiConfig.rawBaseUrl(this, p))
            box.addView(key); box.addView(model)
            box.addView(listButton { pickModel(p, image = false) { model.setText(it) } })
            box.addView(url)
            Triple(key, model, url)
        }

        box.addView(header("Prompt"))
        box.addView(TextView(this).apply {
            textSize = 12f
            text = "点按编辑，长按排序或删除。Prompt 中可用 {{input}} 表示输入内容的位置；" +
                    "不含 {{input}} 时，Prompt 作为系统指令发送。"
        })
        promptList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(promptList)
        box.addView(Button(this).apply {
            text = "添加 Prompt"
            setOnClickListener { editPrompt(null) }
        })

        setContentWithToolbar(ScrollView(this).apply { addView(box) }, "大模型设置")
        renderPrompts()

        val onSelect = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                refreshPredictModelField()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        providerSpinner.onItemSelectedListener = onSelect
        predictSpinner.onItemSelectedListener = onSelect
        refreshPredictModelField()
        imageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                refreshImageModelField()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshImageModelField()
    }

    private fun refreshImageModelField() {
        shownImageProvider?.let { imageModels[it] = imageModelField.text.toString() }
        val p = AiProvider.entries[imageSpinner.selectedItemPosition]
        if (p == shownImageProvider) return
        shownImageProvider = p
        imageModelField.hint = if (p == AiProvider.Gemini) "${p.label} 图片模型（默认 ${AiConfig.DEFAULT_GEMINI_IMAGE_MODEL}）"
        else "${p.label} 图片模型（必填，如 gpt-image-1）"
        imageModelField.setText(imageModels[p].orEmpty())
    }

    private fun listButton(onClick: () -> Unit) =
        Button(this, null, android.R.attr.buttonStyleSmall).apply {
            text = "获取模型列表"; isAllCaps = false
            setOnClickListener { onClick() }
        }

    /** Fetches with the key / URL currently typed in, so it works before saving. */
    private fun pickModel(p: AiProvider, image: Boolean, onPick: (String) -> Unit) {
        val f = fields[p] ?: return
        AiModelPicker.show(this, p, f.third.text.toString(), f.first.text.toString(), image, onPick)
    }

    private fun selectedPredictProvider(): AiProvider =
        predictSpinner.selectedItemPosition.takeIf { it > 0 }?.let { AiProvider.entries[it - 1] }
            ?: AiProvider.entries[providerSpinner.selectedItemPosition]

    /** Show the AI 联想 model of the provider currently selected for prediction. */
    private fun refreshPredictModelField() {
        shownPredictProvider?.let { predictModels[it] = predictModelField.text.toString() }
        val p = selectedPredictProvider()
        if (p == shownPredictProvider) return
        shownPredictProvider = p
        predictModelField.hint = p.defaultPredictModel?.let { "${p.label} 联想模型（默认 $it）" }
            ?: "${p.label} 联想模型（留空则与下面的模型相同）"
        predictModelField.setText(predictModels[p].orEmpty())
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text; textSize = 16f
        setPadding(0, dp(18), 0, dp(4))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun field(hint: String, value: String, password: Boolean = false, multi: Boolean = false) =
        EditText(this).apply {
            this.hint = hint; setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or when {
                password -> InputType.TYPE_TEXT_VARIATION_PASSWORD
                multi -> InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> 0
            }
            if (!password && !multi) {
                // wrap long hints / URLs instead of cutting them off. The input type stays single-line
                // (no MULTI_LINE flag), so Enter still can't insert a newline.
                setHorizontallyScrolling(false)
                maxLines = 4
            }
        }

    private fun save() {
        AiConfig.setProvider(this, AiProvider.entries[providerSpinner.selectedItemPosition])
        AiConfig.setPredictProvider(
            this, predictSpinner.selectedItemPosition.takeIf { it > 0 }?.let { AiProvider.entries[it - 1] }
        )
        fields.forEach { (p, f) ->
            AiConfig.saveProvider(this, p, f.first.text.toString(), f.second.text.toString(), f.third.text.toString())
        }
        shownPredictProvider?.let { predictModels[it] = predictModelField.text.toString() }
        predictModels.forEach { (p, m) -> AiConfig.setPredictModel(this, p, m) }
        AiConfig.setImageProvider(this, AiProvider.entries[imageSpinner.selectedItemPosition])
        shownImageProvider?.let { imageModels[it] = imageModelField.text.toString() }
        imageModels.forEach { (p, m) -> AiConfig.setImageModel(this, p, m) }
        AiTranslate.setEngine(this, TranslateEngine.all[translateSpinner.selectedItemPosition])
        AiTranslate.setTarget(this, AiTranslate.TARGETS[targetSpinner.selectedItemPosition])
        AiConfig.savePrompts(this, prompts)
    }

    override fun onPause() {
        super.onPause()
        save() // auto-save when leaving
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "保存").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            save(); Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show(); return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun renderPrompts() {
        promptList.removeAllViews()
        prompts.forEach { p ->
            promptList.addView(TextView(this).apply {
                text = if (p.content.isBlank()) p.name else "${p.name}：${p.content.take(40)}"
                textSize = 15f
                maxLines = 2
                setPadding(0, dp(10), 0, dp(10))
                val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = a.getDrawable(0); a.recycle()
                setOnClickListener { editPrompt(p) }
                setOnLongClickListener { promptMenu(p); true }
            })
        }
    }

    private fun editPrompt(p: AiPrompt?) {
        val name = field("名称，如：润色", p?.name ?: "")
        val content = field("Prompt 内容，可用 {{input}} 占位", p?.content ?: "", multi = true).apply { minLines = 3 }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(name); addView(content)
        }
        AlertDialog.Builder(this).setTitle(if (p == null) "新建 Prompt" else "编辑 Prompt").setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                if (p == null) prompts.add(AiPrompt(name = n, content = content.text.toString()))
                else { p.name = n; p.content = content.text.toString() }
                AiConfig.savePrompts(this, prompts); renderPrompts()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun promptMenu(p: AiPrompt) {
        AlertDialog.Builder(this).setTitle(p.name)
            .setItems(arrayOf("编辑", "上移", "下移", "删除")) { _, which ->
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
