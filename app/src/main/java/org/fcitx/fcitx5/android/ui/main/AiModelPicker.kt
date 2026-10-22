/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * "获取模型列表": fetches the provider's model list and lets the user pick one (with a search box).
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.view.WindowManager
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.ai.AiClient
import org.fcitx.fcitx5.android.data.ai.AiProvider
import splitties.dimensions.dp

/**
 * For dialogs shown after a network request: the user may have closed the dialog activity meanwhile,
 * and showing a dialog on a dead window crashes the app. Falls back to a toast with [fallback].
 */
internal fun AlertDialog.Builder.showIfAlive(activity: Activity, fallback: String) {
    if (!activity.isFinishing && !activity.isDestroyed) {
        try {
            // mod: dialogs over the assistant look like it (card style)
            show().also { if (activity is AiAssistActivity && AiDialogStyle.enabled) AiDialogStyle.alert(it) }
            return
        } catch (e: WindowManager.BadTokenException) {
            // window went away between the check and show()
        }
    }
    Toast.makeText(activity.applicationContext, fallback, Toast.LENGTH_LONG).show()
}

object AiModelPicker {

    private fun isImageModel(id: String) = id.contains("image") || id.contains("banana") ||
            id.contains("dall-e") || id.contains("flux") || id.contains("imagen")

    /** Gemini also lists speech / audio models; they can't answer text prompts */
    private fun isChatOnly(id: String) = !isImageModel(id) && !id.contains("tts") && !id.contains("audio")

    /**
     * @param image true: picking the image generation model (image models first / only)
     * @param apiKey / baseUrl: what is currently typed in the settings, so it works before saving
     */
    fun show(
        activity: AppCompatActivity, provider: AiProvider, baseUrl: String, apiKey: String,
        image: Boolean, onPick: (String) -> Unit
    ) {
        Toast.makeText(activity, "正在获取 ${provider.label} 的模型列表…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val all = try {
                AiClient.listModels(provider, baseUrl.ifBlank { provider.defaultBaseUrl }, apiKey.trim())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                AlertDialog.Builder(activity).setTitle("获取模型列表失败")
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .showIfAlive(activity, "获取模型列表失败：$msg")
                return@launch
            }
            val wanted = if (image) all.filter(::isImageModel) else all.filter(::isChatOnly)
            // unknown naming (relay services, local servers): show everything, best matches first
            val models = when {
                wanted.isEmpty() -> all
                provider == AiProvider.Gemini -> wanted
                else -> wanted + (all - wanted.toSet())
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            pick(activity, provider, models, onPick)
        }
    }

    private fun pick(activity: AppCompatActivity, provider: AiProvider, models: List<String>, onPick: (String) -> Unit) {
        val styled = activity is AiAssistActivity && AiDialogStyle.enabled
        val adapter = if (styled) AiDialogStyle.textAdapter(activity, android.R.layout.simple_list_item_1, models.toMutableList())
        else ArrayAdapter(activity, android.R.layout.simple_list_item_1, models.toMutableList())
        val search = EditText(activity).apply { hint = "搜索（共 ${models.size} 个）"; isSingleLine = true }
        val list = ListView(activity).apply { this.adapter = adapter }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(-1, dp(360)))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("选择 ${provider.label} 模型")
            .setView(box).setNegativeButton(android.R.string.cancel, null).show()
            .also { if (styled) AiDialogStyle.alert(it) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val q = e?.toString()?.trim().orEmpty()
                adapter.clear()
                adapter.addAll(if (q.isEmpty()) models else models.filter { it.contains(q, ignoreCase = true) })
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            adapter.getItem(pos)?.let(onPick)
            dialog.dismiss()
        }
    }
}
