/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Minimal chat clients for Gemini and OpenAI-compatible APIs (DeepSeek, Kimi/Moonshot).
 */
package org.fcitx.fcitx5.android.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiException(msg: String) : Exception(msg)

object AiClient {

    suspend fun chat(
        provider: AiProvider, baseUrl: String, apiKey: String, model: String,
        systemPrompt: String, userText: String,
        maxTokens: Int? = null, temperature: Double? = null, fast: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException(
            if (provider == AiProvider.Local) "请先在设置中填写本地模型的密码（Pocket LLM 的 LAN Server 密码）"
            else "请先在设置中填写 ${provider.label} 的 API Key"
        )
        val base = baseUrl.trim().trimEnd('/')
        when (provider) {
            AiProvider.Gemini -> {
                val body = JSONObject().apply {
                    if (systemPrompt.isNotBlank()) put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    )
                    put("contents", JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", userText)))
                    ))
                    val gen = JSONObject()
                    temperature?.let { gen.put("temperature", it) }
                    // thinking tokens count toward the output limit; skip the limit when thinking can't be disabled
                    val canDisableThinking = fast && model.startsWith("gemini-2.5") && model.contains("flash")
                    if (canDisableThinking) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    if (maxTokens != null && (canDisableThinking || model.startsWith("gemini-2.0") || model.startsWith("gemini-1.5"))) {
                        gen.put("maxOutputTokens", maxTokens)
                    }
                    if (gen.length() > 0) put("generationConfig", gen)
                }
                val resp = post(
                    "$base/models/$model:generateContent", body,
                    mapOf("x-goog-api-key" to apiKey)
                )
                val cands = resp.optJSONArray("candidates")
                    ?: throw AiException("Gemini 没有返回内容：" + resp.optJSONObject("promptFeedback"))
                val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    ?: throw AiException("Gemini 返回为空（可能被安全策略拦截）")
                buildString {
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (!p.optBoolean("thought")) append(p.optString("text"))
                    }
                }.trim()
            }
            AiProvider.DeepSeek, AiProvider.Kimi, AiProvider.Local -> {
                val messages = JSONArray()
                if (systemPrompt.isNotBlank())
                    messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                messages.put(JSONObject().put("role", "user").put("content", userText))
                val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
                // reasoning models spend tokens on thinking; don't cap them
                if (maxTokens != null && !model.contains("reasoner") && !model.contains("thinking")) {
                    body.put("max_tokens", maxTokens)
                }
                temperature?.let { body.put("temperature", it) }
                val resp = post(
                    "$base/chat/completions", body,
                    mapOf("Authorization" to "Bearer $apiKey")
                )
                resp.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
                    // reasoning models (Qwen3, DeepSeek R1 distills) may inline their thinking
                    .replace(Regex("(?s)<think>.*?</think>"), "")
                    .replace(Regex("(?s)^.*</think>"), "")
                    .trim()
            }
        }
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    val o = JSONObject(text)
                    o.optJSONObject("error")?.optString("message") ?: o.optString("message")
                }.getOrNull().orEmpty().ifBlank { text.take(200) }
                throw AiException(
                    when (code) {
                        401, 403 -> "API Key 无效或无权限 ($code)：$msg"
                        402 -> "账户余额不足 (402)"
                        429 -> "请求过于频繁或额度用完 (429)：$msg"
                        else -> "请求失败 $code：$msg"
                    }
                )
            }
            return JSONObject(text)
        } catch (e: java.io.IOException) {
            throw AiException("网络错误：${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
