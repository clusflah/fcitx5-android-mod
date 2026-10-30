/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 豆包语音「流式语音识别」的流式输入模式（bigmodel_nostream）：一段录音发完，一次拿回全文。
 * 给没开通录音文件极速版的账号用：极速版是 1.0 服务，新账号只能开通 2.0（会报 45000030），
 * 而录音文件识别 2.0 只收音频网址，不能直接传录音。
 *
 * 协议是 WebSocket 上的二进制包：4 字节头（版本、头长、消息类型、标志、序列化、压缩）+ 4 字节长度 + 内容。
 * 只有这一处用 WebSocket，所以没有引入库，用系统的 SSLSocket 写了一个最小的客户端。
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object VolcStream {

    private const val HOST = "openspeech.bytedance.com"
    private const val PATH = "/api/v3/sauc/bigmodel_nostream"

    /** 先 2.0（新账号只能开通 2.0），没开通再试 1.0 */
    private val RESOURCES = listOf("volc.seedasr.sauc.duration", "volc.bigasr.sauc.duration")

    const val NOT_GRANTED_CODE = "45000030"

    /** 这个 Key 试出来能用的资源，下次直接用 */
    @Volatile
    private var granted: Pair<String, String>? = null

    private class NotGranted(message: String) : Exception(message)

    /** 发一包的音频长度：200 毫秒（16kHz 16bit 单声道） */
    private const val PACKET_BYTES = 6400

    /** 开头先冲几秒，之后最快 2 倍速（流式接口按实时音频设计，发太快会被拒） */
    private const val BURST_MS = 3000L
    private const val PACE = 2

    private const val FULL_CLIENT_REQUEST = 0b0001
    private const val AUDIO_ONLY = 0b0010
    private const val FULL_SERVER_RESPONSE = 0b1001
    private const val SERVER_ERROR = 0b1111
    private const val LAST_PACKET = 0b0010

    /**
     * @param key 只用来记住哪个资源能用
     * @param pcm 16kHz 单声道 16bit 小端 PCM
     */
    fun recognize(auth: Map<String, String>, key: String, pcm: ByteArray): String {
        val known = granted?.takeIf { it.first == key }?.second
        val order = if (known != null) listOf(known) + (RESOURCES - known) else RESOURCES
        var detail = ""
        for (resource in order) {
            try {
                return once(auth, resource, pcm).also { granted = key to resource }
            } catch (e: NotGranted) {
                detail = e.message.orEmpty()
            }
        }
        throw CloudSpeechException(
            "火山豆包：账号没开通可用的语音识别服务（45000030）。到豆包语音控制台（console.volcengine.com/speech/new）" +
                    "开通「流式语音识别 2.0」；「录音文件识别 2.0」只能识别网上的音频文件，这里用不上。$detail"
        )
    }

    private fun once(auth: Map<String, String>, resource: String, pcm: ByteArray): String {
        val socket = SSLSocketFactory.getDefault().createSocket(HOST, 443) as SSLSocket
        socket.use {
            socket.soTimeout = 20_000
            socket.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, socket.session)) {
                throw IOException("火山豆包：证书和域名不符")
            }
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = socket.getOutputStream().buffered()
            handshake(input, output, auth + mapOf("X-Api-Resource-Id" to resource))

            val request = JSONObject()
                .put("user", JSONObject().put("uid", "fcitx5-android"))
                .put("audio", JSONObject().put("format", "pcm").put("codec", "raw").put("rate", 16000).put("bits", 16).put("channel", 1))
                .put("request", JSONObject().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true))
            sendPacket(output, FULL_CLIENT_REQUEST, 0, 0b0001, request.toString().toByteArray())

            val start = SystemClock.elapsedRealtime()
            var offset = 0
            try {
                while (offset < pcm.size) {
                    val n = minOf(PACKET_BYTES, pcm.size - offset)
                    val last = offset + n >= pcm.size
                    sendPacket(output, AUDIO_ONLY, if (last) LAST_PACKET else 0, 0, pcm.copyOfRange(offset, offset + n))
                    offset += n
                    val audioMs = offset / 32L
                    val wait = (audioMs - BURST_MS) / PACE - (SystemClock.elapsedRealtime() - start)
                    if (wait > 0) Thread.sleep(wait)
                }
                if (pcm.isEmpty()) sendPacket(output, AUDIO_ONLY, LAST_PACKET, 0, ByteArray(0))
            } catch (e: IOException) {
                // 服务端出错后会关掉连接：下面读出它给的错误码
            }
            return readResult(input, output)
        }
    }

    private fun handshake(input: DataInputStream, output: OutputStream, headers: Map<String, String>) {
        val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val request = StringBuilder()
            .append("GET $PATH HTTP/1.1\r\nHost: $HOST\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n")
            .append("Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n")
            .append("X-Api-Connect-Id: ${UUID.randomUUID()}\r\n")
        headers.forEach { (k, v) -> request.append("$k: $v\r\n") }
        output.write(request.append("\r\n").toString().toByteArray())
        output.flush()

        val status = readLine(input)
        val response = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        if (code == 101) return
        val length = response["content-length"]?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
        val body = runCatching { ByteArray(length).also { input.readFully(it) }.decodeToString() }.getOrDefault("")
        if (response["x-api-status-code"] == NOT_GRANTED_CODE || NOT_GRANTED_CODE in body) throw NotGranted(body.take(200))
        throw CloudSpeechException(
            when (code) {
                401, 403 -> "火山豆包：Key 无效或没有权限 ($code) ${body.take(160)}"
                else -> "火山豆包流式识别连接失败 $code ${response["x-api-message"].orEmpty()} ${body.take(160)}"
            }
        )
    }

    private fun readLine(input: DataInputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("火山豆包：连接被关闭")
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
        }
        return line.toString("UTF-8")
    }

    private fun sendPacket(output: OutputStream, type: Int, flags: Int, serialization: Int, payload: ByteArray) {
        val packet = ByteBuffer.allocate(8 + payload.size)
            .put(0x11).put(((type shl 4) or flags).toByte()).put((serialization shl 4).toByte()).put(0)
            .putInt(payload.size).put(payload).array()
        sendFrame(output, 0x2, packet)
    }

    /** 客户端发出的 WebSocket 帧要加掩码 */
    private fun sendFrame(output: OutputStream, opcode: Int, data: ByteArray) {
        output.write(0x80 or opcode)
        when {
            data.size < 126 -> output.write(0x80 or data.size)
            data.size < 65536 -> {
                output.write(0x80 or 126)
                output.write(data.size shr 8)
                output.write(data.size and 0xff)
            }
            else -> {
                output.write(0x80 or 127)
                output.write(ByteBuffer.allocate(8).putLong(data.size.toLong()).array())
            }
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        output.write(mask)
        output.write(ByteArray(data.size) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() })
        output.flush()
    }

    /** 读到最后一包结果（或错误）为止 */
    private fun readResult(input: DataInputStream, output: OutputStream): String {
        var text = ""
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return text
            val opcode = b0 and 0x0f
            val b1 = input.readUnsignedByte()
            val length = when (val n = b1 and 0x7f) {
                126 -> input.readUnsignedShort().toLong()
                127 -> input.readLong()
                else -> n.toLong()
            }
            if (length > 4 shl 20) throw IOException("火山豆包：返回的数据过大")
            val payload = ByteArray(length.toInt()).also { input.readFully(it) }
            when (opcode) {
                0x8 -> return text // 关闭
                0x9 -> runCatching { sendFrame(output, 0xA, payload) } // ping → pong
                0x2 -> {
                    if (payload.size < 8) continue
                    val buf = ByteBuffer.wrap(payload)
                    val headerSize = (payload[0].toInt() and 0x0f) * 4
                    val type = (payload[1].toInt() shr 4) and 0x0f
                    val flags = payload[1].toInt() and 0x0f
                    val gzip = (payload[2].toInt() and 0x0f) == 1
                    buf.position(headerSize)
                    if (type == SERVER_ERROR) {
                        val code = buf.int
                        val size = buf.int
                        val message = String(payload, buf.position(), minOf(size, payload.size - buf.position()))
                        if (code.toString() == NOT_GRANTED_CODE) throw NotGranted(message)
                        throw CloudSpeechException("火山豆包识别出错 $code $message")
                    }
                    if (type != FULL_SERVER_RESPONSE) continue
                    if (flags and 0x01 != 0) buf.int // 序号
                    val size = buf.int
                    var body = payload.copyOfRange(buf.position(), minOf(buf.position() + size, payload.size))
                    if (gzip) body = GZIPInputStream(ByteArrayInputStream(body)).readBytes()
                    runCatching { JSONObject(String(body)) }.getOrNull()
                        ?.optJSONObject("result")?.optString("text")?.let { text = it.trim() }
                    // 负序号 / 最后一包：识别结束
                    if (flags and 0x02 != 0) return text
                }
            }
        }
    }
}
