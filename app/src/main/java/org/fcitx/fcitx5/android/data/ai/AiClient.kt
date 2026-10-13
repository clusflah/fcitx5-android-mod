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
                    // Gemini 3 can't turn thinking off; 3.x Flash defaults to "medium", "low" is the lowest
                    // level every 3.x Flash accepts. Flash-Lite already defaults to "minimal".
                    if (fast && model.startsWith("gemini-3") && model.contains("flash") && !model.contains("lite")) {
                        gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                    }
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
                // deepseek-flash / deepseek-v4-pro and kimi-k2.6 think by default; for quick answers
                // (AI 联想) turn it off. kimi-k3 always thinks and rejects `thinking`, so lower the effort.
                val isKimiK3 = provider == AiProvider.Kimi && model.startsWith("kimi-k3")
                val thinkingOff = fast && (provider == AiProvider.DeepSeek || provider == AiProvider.Kimi && !isKimiK3)
                if (thinkingOff) body.put("thinking", JSONObject().put("type", "disabled"))
                if (fast && isKimiK3) body.put("reasoning_effort", "low")
                // reasoning models spend tokens on thinking; only cap the output when thinking is off
                val mayThink = provider != AiProvider.Local && !thinkingOff ||
                        model.contains("reasoner") || model.contains("thinking")
                if (maxTokens != null && !mayThink) {
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

    /** A single user message to an OpenAI-compatible endpoint that needs no API key (Index-Translate). */
    suspend fun chatKeyless(baseUrl: String, model: String, userText: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
                .put("stream", false)
                .put("temperature", 0)
            post("${baseUrl.trim().trimEnd('/')}/chat/completions", body, emptyMap())
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
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
