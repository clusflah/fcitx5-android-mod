/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Minimal Azure Speech REST client (STT short-audio + TTS). No SDK dependency.
 */
package org.fcitx.fcitx5.android.input.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class AzureConfig(
    val key: String,
    val region: String,
    val language: String,
    val sttEndpoint: String,
    val ttsVoice: String,
    val ttsEndpoint: String
)

class AzureException(msg: String) : Exception(msg)

/** Records 16 kHz / 16-bit / mono PCM. */
class PcmRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 60 // Azure short-audio REST limit
    }

    private var record: AudioRecord? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile var level = 0f; private set
    @Volatile private var running = false
    private var thread: Thread? = null

    @Suppress("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); throw AzureException("无法打开麦克风")
        }
        record = r
        buffer.reset()
        running = true
        r.startRecording()
        thread = Thread {
            val chunk = ByteArray(minBuf)
            val maxBytes = SAMPLE_RATE * 2 * MAX_SECONDS
            while (running) {
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                buffer.write(chunk, 0, n)
                var peak = 0
                var i = 0
                while (i + 1 < n) {
                    val s = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
                    peak = maxOf(peak, abs(s.toShort().toInt())); i += 2
                }
                level = peak / 32768f
                if (buffer.size() >= maxBytes) running = false
            }
        }.also { it.start() }
    }

    val isRunning get() = running

    /** Stops and returns a WAV file's bytes. */
    fun stop(): ByteArray {
        running = false
        thread?.join(500)
        record?.run { runCatching { stop() }; release() }
        record = null
        return wav(buffer.toByteArray())
    }

    fun cancel() { runCatching { stop() } }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(SAMPLE_RATE); h.putInt(SAMPLE_RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}

object AzureSpeech {

    private fun check(c: AzureConfig) {
        if (c.key.isBlank()) throw AzureException("请先在设置中填写 Azure 语音密钥")
        if (c.region.isBlank() && (c.sttEndpoint.isBlank() || c.ttsEndpoint.isBlank()))
            throw AzureException("请先在设置中填写 Azure 区域")
    }

    private fun http(url: String, key: String, contentType: String, body: ByteArray, extra: Map<String, String> = emptyMap()): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Ocp-Apim-Subscription-Key", key)
            conn.setRequestProperty("Content-Type", contentType)
            extra.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw AzureException(
                    when (code) {
                        401 -> "密钥无效或与区域不匹配 (401)"
                        403 -> "无权访问 (403)"
                        429 -> "请求过于频繁 (429)"
                        else -> "Azure 返回错误 $code ${err.take(120)}"
                    }
                )
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun recognize(c: AzureConfig, wav: ByteArray): String = withContext(Dispatchers.IO) {
        check(c)
        val base = c.sttEndpoint.trim().trimEnd('/').ifEmpty {
            "https://${c.region.trim()}.stt.speech.microsoft.com"
        }
        val url = if (base.contains("/speech/recognition")) base
        else "$base/speech/recognition/conversation/cognitiveservices/v1"
        val sep = if (url.contains('?')) '&' else '?'
        val full = "$url${sep}language=${URLEncoder.encode(c.language.ifBlank { "zh-CN" }, "UTF-8")}&format=simple"
        val resp = http(
            full, c.key, "audio/wav; codecs=audio/pcm; samplerate=16000", wav,
            mapOf("Accept" to "application/json")
        )
        val json = JSONObject(String(resp))
        when (val status = json.optString("RecognitionStatus")) {
            "Success" -> json.optString("DisplayText")
            "NoMatch", "InitialSilenceTimeout", "BabbleTimeout" -> ""
            else -> throw AzureException("识别失败：$status")
        }
    }

    suspend fun synthesize(c: AzureConfig, text: String, cacheDir: File): File = withContext(Dispatchers.IO) {
        check(c)
        val url = c.ttsEndpoint.trim().trimEnd('/').ifEmpty {
            "https://${c.region.trim()}.tts.speech.microsoft.com/cognitiveservices/v1"
        }
        val voice = c.ttsVoice.ifBlank { "zh-CN-XiaoxiaoNeural" }
        val lang = voice.split('-').take(2).joinToString("-")
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val ssml = "<speak version='1.0' xml:lang='$lang'><voice name='$voice'>$escaped</voice></speak>"
        val audio = http(
            url, c.key, "application/ssml+xml", ssml.toByteArray(),
            mapOf(
                "X-Microsoft-OutputFormat" to "audio-24khz-48kbitrate-mono-mp3",
                "User-Agent" to "fcitx5-android"
            )
        )
        File(cacheDir, "azure_tts.mp3").apply { writeBytes(audio) }
    }

    private var player: MediaPlayer? = null

    fun play(file: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare(); start()
        }
    }
}
