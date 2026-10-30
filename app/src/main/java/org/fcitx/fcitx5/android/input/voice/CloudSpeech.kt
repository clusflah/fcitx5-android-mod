/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Cloud speech recognition besides Azure: one short WAV recording in, its text out, over plain
 * HTTPS (no SDKs). Each engine's settings live in AppPrefs.voiceMore and are edited on the 语音模型
 * page from [ENGINES] (Azure is listed there too, with its own settings in AppPrefs.voice).
 */
package org.fcitx.fcitx5.android.input.voice

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class CloudSpeechException(msg: String) : Exception(msg)

object CloudSpeech {

    /** one setting of an engine, as shown in its settings dialog */
    class Field(val label: String, val pref: ManagedPreference.PString, val hint: String = "", val secret: Boolean = false)

    /** @param fields the first one is the key: the engine counts as set up once it is filled */
    class Engine(val engine: VoiceEngine, val name: String, val description: String, val fields: List<Field>) {
        val configured get() = fields.first().pref.getValue().isNotBlank()
    }

    val ENGINES: List<Engine>
        get() {
            val az = AppPrefs.getInstance().voice
            val p = AppPrefs.getInstance().voiceMore
            return listOf(
                Engine(
                    VoiceEngine.Azure, "Azure", "微软 Azure 语音，多语种；也用于朗读识别结果", listOf(
                        Field("密钥", az.azureSpeechKey, secret = true),
                        Field("区域", az.azureSpeechRegion, "如 eastasia"),
                        Field("识别语言", az.azureSttLanguage, "如 zh-CN、en-US"),
                        Field("自定义识别地址（可选）", az.azureSttEndpoint),
                    )
                ),
                Engine(
                    VoiceEngine.Doubao, "火山豆包", "字节跳动豆包语音，中文口语效果好；新账号开通「流式语音识别 2.0」即可", listOf(
                        Field("Access Key / API Key", p.doubaoKey, "新版控制台只填 API Key", secret = true),
                        Field("App ID（旧版控制台）", p.doubaoAppId, "新版控制台留空"),
                    )
                ),
                Engine(
                    VoiceEngine.Qwen, "阿里百炼 Qwen3-ASR", "阿里云百炼，中文、方言、多语种", listOf(
                        Field("API Key", p.qwenKey, "sk-…", secret = true),
                        Field("模型", p.qwenModel, "qwen3-asr-flash"),
                        Field("语言（可选）", p.qwenLanguage, "留空自动识别，或 zh、en…"),
                        Field("接口地址", p.qwenBaseUrl, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                    )
                ),
                Engine(
                    VoiceEngine.ElevenLabs, "ElevenLabs", "Scribe 模型，多语种准确率高", listOf(
                        Field("API Key", p.elevenKey, secret = true),
                        Field("模型", p.elevenModel, "scribe_v2"),
                        Field("语言（可选）", p.elevenLanguage, "留空自动识别，或 zh、en…"),
                    )
                ),
                Engine(
                    VoiceEngine.Deepgram, "Deepgram", "Nova 模型，英语又快又便宜", listOf(
                        Field("API Key", p.deepgramKey, secret = true),
                        Field("模型", p.deepgramModel, "nova-3"),
                        Field("语言", p.deepgramLanguage, "multi（多语种）、en；中文用 nova-2 + zh"),
                    )
                ),
                Engine(
                    VoiceEngine.OpenAI, "OpenAI 兼容", "Whisper 等转写接口；也可填 Groq、硅基流动等兼容服务", listOf(
                        Field("API Key", p.openaiKey, secret = true),
                        Field("接口地址", p.openaiBaseUrl, "https://api.openai.com/v1"),
                        Field("模型", p.openaiModel, "whisper-1、gpt-4o-mini-transcribe…"),
                        Field("语言（可选）", p.openaiLanguage, "留空自动识别，或 zh、en…"),
                    )
                ),
            )
        }

    fun engine(e: VoiceEngine) = ENGINES.find { it.engine == e }

    // ---------- HTTP ----------

    private class Response(val code: Int, val body: String, val headers: Map<String, List<String>>) {
        fun header(name: String) = headers.entries.find { it.key.equals(name, true) }?.value?.firstOrNull()
    }

    private fun post(url: String, headers: Map<String, String>, contentType: String, body: ByteArray): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 40_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, text, conn.headerFields.filterKeys { it != null })
        } finally {
            conn.disconnect()
        }
    }

    private fun Response.ok(service: String): JSONObject {
        if (code !in 200..299) throw CloudSpeechException(
            when (code) {
                401, 403 -> "$service：密钥无效或没有权限 ($code)"
                429 -> "$service：请求过于频繁或额度不足 (429)"
                else -> "$service 返回错误 $code ${body.take(160)}"
            }
        )
        return JSONObject(body)
    }

    private class Multipart {
        val boundary = "----fcitx${UUID.randomUUID().toString().replace("-", "")}"
        private val out = ByteArrayOutputStream()
        fun field(name: String, value: String) = apply {
            if (value.isBlank()) return@apply
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        fun file(name: String, filename: String, type: String, data: ByteArray) = apply {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
            out.write(data)
            out.write("\r\n".toByteArray())
        }
        fun bytes(): ByteArray = out.toByteArray() + "--$boundary--\r\n".toByteArray()
        val contentType get() = "multipart/form-data; boundary=$boundary"
    }

    private fun need(value: String, what: String) =
        value.trim().ifEmpty { throw CloudSpeechException("请先在 设置 → 语音模型 中填写$what") }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- engines ----------

    suspend fun recognize(engine: VoiceEngine, wav: ByteArray): String = withContext(Dispatchers.IO) {
        val p = AppPrefs.getInstance().voiceMore
        when (engine) {
            VoiceEngine.Doubao -> doubao(need(p.doubaoKey.getValue(), "火山豆包的 Key"), p.doubaoAppId.getValue().trim(), wav)
            VoiceEngine.Qwen -> qwen(
                need(p.qwenKey.getValue(), "阿里百炼的 API Key"), p.qwenBaseUrl.getValue(),
                p.qwenModel.getValue(), p.qwenLanguage.getValue(), wav
            )
            VoiceEngine.ElevenLabs -> elevenLabs(
                need(p.elevenKey.getValue(), "ElevenLabs 的 API Key"), p.elevenModel.getValue(), p.elevenLanguage.getValue(), wav
            )
            VoiceEngine.Deepgram -> deepgram(
                need(p.deepgramKey.getValue(), "Deepgram 的 API Key"), p.deepgramModel.getValue(), p.deepgramLanguage.getValue(), wav
            )
            VoiceEngine.OpenAI -> openai(
                need(p.openaiKey.getValue(), "OpenAI 兼容接口的 API Key"), p.openaiBaseUrl.getValue(),
                p.openaiModel.getValue(), p.openaiLanguage.getValue(), wav
            )
            else -> throw IllegalArgumentException(engine.name)
        }
    }

    /** keys whose account has no 极速版 (new accounts only get the 2.0 services): go straight to [VolcStream] */
    @Volatile
    private var doubaoNoTurbo: String? = null

    /**
     * 豆包语音 录音文件识别大模型 极速版: one call, the result is in the body, status in a header.
     * 极速版 is a 1.0 service new accounts can't get (45000030); then 流式语音识别 2.0 does it instead.
     */
    private fun doubao(key: String, appId: String, wav: ByteArray): String {
        val auth = if (appId.isNotEmpty()) mapOf("X-Api-App-Key" to appId, "X-Api-Access-Key" to key)
        else mapOf("X-Api-Key" to key)
        if (doubaoNoTurbo == key) return VolcStream.recognize(auth, key, wav.copyOfRange(minOf(44, wav.size), wav.size))
        val body = JSONObject()
            .put("user", JSONObject().put("uid", "fcitx5-android"))
            .put("audio", JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
            .put("request", JSONObject().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true))
        val r = post(
            "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash",
            auth + mapOf(
                "X-Api-Resource-Id" to "volc.bigasr.auc_turbo",
                "X-Api-Request-Id" to UUID.randomUUID().toString(),
                "X-Api-Sequence" to "-1",
            ),
            "application/json", body.toString().toByteArray()
        )
        when (val status = r.header("X-Api-Status-Code")) {
            "20000000", null -> {}
            // silence
            "20000003" -> return ""
            VolcStream.NOT_GRANTED_CODE -> {
                doubaoNoTurbo = key
                return VolcStream.recognize(auth, key, wav.copyOfRange(minOf(44, wav.size), wav.size))
            }
            else -> throw CloudSpeechException("火山豆包返回错误 $status ${r.header("X-Api-Message").orEmpty()}")
        }
        return r.ok("火山豆包").optJSONObject("result")?.optString("text").orEmpty().trim()
    }

    /** Qwen3-ASR through 百炼's OpenAI-compatible chat API, the audio as a data URI */
    private fun qwen(key: String, base: String, model: String, language: String, wav: ByteArray): String {
        val audio = JSONObject().put("type", "input_audio").put(
            "input_audio", JSONObject().put("data", "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP))
        )
        val options = JSONObject().put("enable_itn", true)
        if (language.isNotBlank()) options.put("language", language.trim())
        val body = JSONObject()
            .put("model", model.ifBlank { "qwen3-asr-flash" })
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(audio))))
            .put("stream", false)
            .put("asr_options", options)
        val url = base.trim().trimEnd('/').ifEmpty { "https://dashscope.aliyuncs.com/compatible-mode/v1" } + "/chat/completions"
        val json = post(url, mapOf("Authorization" to "Bearer $key"), "application/json", body.toString().toByteArray())
            .ok("阿里百炼")
        return json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
    }

    private fun elevenLabs(key: String, model: String, language: String, wav: ByteArray): String {
        val form = Multipart()
            .field("model_id", model.ifBlank { "scribe_v2" })
            .field("language_code", language.trim())
            .field("tag_audio_events", "false")
            .file("file", "audio.wav", "audio/wav", wav)
        val json = post("https://api.elevenlabs.io/v1/speech-to-text", mapOf("xi-api-key" to key), form.contentType, form.bytes())
            .ok("ElevenLabs")
        return json.optString("text").trim()
    }

    private fun deepgram(key: String, model: String, language: String, wav: ByteArray): String {
        val lang = language.trim()
        val query = "model=${enc(model.ifBlank { "nova-3" })}&smart_format=true&punctuate=true" +
                if (lang.isEmpty()) "&detect_language=true" else "&language=${enc(lang)}"
        val json = post("https://api.deepgram.com/v1/listen?$query", mapOf("Authorization" to "Token $key"), "audio/wav", wav)
            .ok("Deepgram")
        return json.optJSONObject("results")?.optJSONArray("channels")?.optJSONObject(0)
            ?.optJSONArray("alternatives")?.optJSONObject(0)?.optString("transcript").orEmpty().trim()
    }

    /** OpenAI's /audio/transcriptions, also served by Groq, 硅基流动 and others */
    private fun openai(key: String, base: String, model: String, language: String, wav: ByteArray): String {
        val form = Multipart()
            .field("model", model.ifBlank { "whisper-1" })
            .field("language", language.trim())
            .field("response_format", "json")
            .file("file", "audio.wav", "audio/wav", wav)
        val url = base.trim().trimEnd('/').ifEmpty { "https://api.openai.com/v1" } + "/audio/transcriptions"
        val json = post(url, mapOf("Authorization" to "Bearer $key"), form.contentType, form.bytes()).ok("OpenAI 兼容接口")
        return json.optString("text").trim()
    }
}
