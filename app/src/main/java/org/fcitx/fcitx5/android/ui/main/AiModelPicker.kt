/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * "获取模型列表": fetches the provider's model list and lets the user pick one (with a search box).
 */
package org.fcitx.fcitx5.android.ui.main

import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.ai.AiClient
import org.fcitx.fcitx5.android.data.ai.AiProvider
import splitties.dimensions.dp

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
                AlertDialog.Builder(activity).setTitle("获取模型列表失败")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val wanted = if (image) all.filter(::isImageModel) else all.filter(::isChatOnly)
            // unknown naming (relay services, local servers): show everything, best matches first
            val models = when {
                wanted.isEmpty() -> all
                provider == AiProvider.Gemini -> wanted
                else -> wanted + (all - wanted.toSet())
            }
            pick(activity, provider, models, onPick)
        }
    }

    private fun pick(activity: AppCompatActivity, provider: AiProvider, models: List<String>, onPick: (String) -> Unit) {
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, models.toMutableList())
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
