/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * LLM assistant (大模型) configuration: providers, API keys, custom prompts.
 */
package org.fcitx.fcitx5.android.data.ai

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AiProvider(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String
) {
    Gemini("Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
    DeepSeek("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    Kimi("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),

    /** Any OpenAI-compatible server, e.g. Pocket LLM's LAN server running on this phone */
    Local("本地模型", "http://127.0.0.1:8080/v1", "qwen3_litert");
}

data class AiPrompt(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var content: String
)

object AiConfig {

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("ai_assist", Context.MODE_PRIVATE)

    fun provider(ctx: Context): AiProvider =
        runCatching { AiProvider.valueOf(sp(ctx).getString("provider", null)!!) }
            .getOrDefault(AiProvider.DeepSeek)

    fun setProvider(ctx: Context, p: AiProvider) = sp(ctx).edit().putString("provider", p.name).apply()

    /** Model used by AI 联想; null = same as the default model */
    fun predictProvider(ctx: Context): AiProvider? =
        runCatching { AiProvider.valueOf(sp(ctx).getString("predict_provider", null)!!) }.getOrNull()

    fun setPredictProvider(ctx: Context, p: AiProvider?) =
        sp(ctx).edit().putString("predict_provider", p?.name).apply()

    fun apiKey(ctx: Context, p: AiProvider) = sp(ctx).getString("key_${p.name}", "")!!
    fun model(ctx: Context, p: AiProvider) =
        sp(ctx).getString("model_${p.name}", "")!!.ifBlank { p.defaultModel }
    fun baseUrl(ctx: Context, p: AiProvider) =
        sp(ctx).getString("url_${p.name}", "")!!.ifBlank { p.defaultBaseUrl }
    fun rawModel(ctx: Context, p: AiProvider) = sp(ctx).getString("model_${p.name}", "")!!
    fun rawBaseUrl(ctx: Context, p: AiProvider) = sp(ctx).getString("url_${p.name}", "")!!

    fun saveProvider(ctx: Context, p: AiProvider, key: String, model: String, url: String) {
        sp(ctx).edit()
            .putString("key_${p.name}", key.trim())
            .putString("model_${p.name}", model.trim())
            .putString("url_${p.name}", url.trim())
            .apply()
    }


    fun lastPrompt(ctx: Context) = sp(ctx).getString("last_prompt", null)
    fun setLastPrompt(ctx: Context, id: String?) = sp(ctx).edit().putString("last_prompt", id).apply()

    private val defaults = listOf(
        AiPrompt(name = "润色", content = "请润色下面的文字，使其通顺自然，保持原意和语言，只输出润色后的结果。"),
        AiPrompt(name = "翻译成英文", content = "把下面的内容翻译成地道的英文，只输出译文。"),
        AiPrompt(name = "翻译成中文", content = "把下面的内容翻译成简体中文，只输出译文。"),
        AiPrompt(name = "帮我回复", content = "下面是别人发给我的消息，请帮我写一条得体、简洁的回复，只输出回复内容。"),
        AiPrompt(name = "总结", content = "用简洁的中文总结下面内容的要点。"),
        AiPrompt(name = "直接提问", content = "")
    )

    fun prompts(ctx: Context): MutableList<AiPrompt> {
        val raw = sp(ctx).getString("prompts", null) ?: return defaults.map { it.copy() }.toMutableList()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                AiPrompt(o.getString("id"), o.getString("name"), o.getString("content"))
            }
        }.getOrElse { defaults.map { it.copy() }.toMutableList() }
    }

    fun savePrompts(ctx: Context, list: List<AiPrompt>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("content", it.content)) }
        sp(ctx).edit().putString("prompts", arr.toString()).apply()
    }
}

/**
 * Text the assistant wants typed into the app the user came from.
 * The assistant runs in its own Activity, so the text is committed when that app's editor regains focus.
 */
object AiPendingCommit {
    private var text: String? = null
    private var time = 0L

    fun set(t: String) { text = t; time = SystemClock.elapsedRealtime() }

    fun take(): String? {
        val t = text ?: return null
        text = null
        return if (SystemClock.elapsedRealtime() - time < 60_000) t else null
    }
}
