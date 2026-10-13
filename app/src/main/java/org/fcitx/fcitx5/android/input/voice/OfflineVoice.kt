/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Offline speech recognition with SenseVoice (zh / yue / en / ja / ko) via sherpa-onnx.
 *
 * The model (~226 MB) is not bundled: it is downloaded from Hugging Face with the system
 * DownloadManager, or imported from files, into the app's external files dir. Audio never leaves
 * the phone. The recognizer is loaded on first use and released after a while to free memory.
 */
package org.fcitx.fcitx5.android.input.voice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OfflineVoice {

    /** k2-fsa's export of SenseVoice-Small, int8 quantized */
    private const val REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
    const val MODEL = "model.int8.onnx"
    const val TOKENS = "tokens.txt"
    private val EXPECTED_SIZES = mapOf(MODEL to 237_115_547L, TOKENS to 315_894L)
    const val DOWNLOAD_SIZE_MB = 227

    /** download sources: label → base URL ("<base>/<repo>/resolve/main/<file>") */
    val SOURCES = listOf(
        "Hugging Face（官方，需要能访问外网）" to "https://huggingface.co",
        "hf-mirror.com（国内镜像，可能不可用）" to "https://hf-mirror.com"
    )

    /** release the recognizer (~300 MB of memory) after this long without use */
    private const val IDLE_RELEASE_MS = 5 * 60 * 1000L

    fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models/sensevoice")
    private fun downloadDir(ctx: Context) = File(dir(ctx).parentFile, "sensevoice-download")

    /** both files present; an imported model may be another SenseVoice export, so only sanity-check sizes */
    fun isReady(ctx: Context): Boolean {
        val d = dir(ctx)
        return File(d, MODEL).length() > 50_000_000L && File(d, TOKENS).length() > 10_000L
    }

    fun sizeMb(ctx: Context): Long = (dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L) / 1_048_576

    fun delete(ctx: Context) {
        release()
        dir(ctx).deleteRecursively()
        downloadDir(ctx).deleteRecursively()
    }

    // ---------- download (system DownloadManager: survives leaving the page, shows a notification) ----------

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("offline_voice", Context.MODE_PRIVATE)

    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    fun startDownload(ctx: Context, baseUrl: String) {
        cancelDownload(ctx)
        val tmp = downloadDir(ctx).apply { deleteRecursively(); mkdirs() }
        val ids = EXPECTED_SIZES.keys.map { name ->
            val req = DownloadManager.Request(Uri.parse("$baseUrl/$REPO/resolve/main/$name"))
                .setTitle("离线语音模型：$name")
                .setDestinationUri(Uri.fromFile(File(tmp, name)))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
            dm(ctx).enqueue(req)
        }
        sp(ctx).edit().putString("download_ids", ids.joinToString(",")).apply()
    }

    fun cancelDownload(ctx: Context) {
        downloadIds(ctx).forEach { runCatching { dm(ctx).remove(it) } }
        sp(ctx).edit().remove("download_ids").apply()
    }

    private fun downloadIds(ctx: Context) =
        sp(ctx).getString("download_ids", null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

    sealed class DownloadState {
        object None : DownloadState()
        class Running(val doneBytes: Long, val totalBytes: Long) : DownloadState()
        class Failed(val reason: String) : DownloadState()
        object Done : DownloadState()
    }

    /** Current state; when both files finished, verify their sizes and move them into place. */
    fun downloadState(ctx: Context): DownloadState {
        val ids = downloadIds(ctx)
        if (ids.isEmpty()) return DownloadState.None
        var done = 0L
        var total = 0L
        var finished = 0
        dm(ctx).query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
            while (c.moveToNext()) {
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                total += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> finished++
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        cancelDownload(ctx)
                        return DownloadState.Failed("下载失败（代码 $reason），请检查网络或换一个下载源")
                    }
                }
            }
        }
        if (finished < ids.size) return DownloadState.Running(done, total.coerceAtLeast(EXPECTED_SIZES.values.sum()))
        // all finished: verify and install
        sp(ctx).edit().remove("download_ids").apply()
        val tmp = downloadDir(ctx)
        val bad = EXPECTED_SIZES.filter { (name, size) -> File(tmp, name).length() != size }.keys
        if (bad.isNotEmpty()) {
            tmp.deleteRecursively()
            return DownloadState.Failed("下载的文件不完整：${bad.joinToString()}，请重试")
        }
        release()
        val d = dir(ctx).apply { deleteRecursively(); mkdirs() }
        EXPECTED_SIZES.keys.forEach { File(tmp, it).renameTo(File(d, it)) }
        tmp.deleteRecursively()
        return DownloadState.Done
    }

    // ---------- import from files ----------

    /** Copy picked files; the .onnx one becomes the model, the .txt one the token list. */
    suspend fun import(ctx: Context, uris: List<Uri>): String = withContext(Dispatchers.IO) {
        fun name(u: Uri) = ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: u.lastPathSegment.orEmpty()
        val model = uris.find { name(it).endsWith(".onnx", true) }
            ?: return@withContext "没有选择 .onnx 模型文件"
        val tokens = uris.find { name(it).endsWith(".txt", true) }
            ?: return@withContext "没有选择 tokens.txt"
        release()
        val d = dir(ctx).apply { mkdirs() }
        listOf(model to MODEL, tokens to TOKENS).forEach { (u, target) ->
            val tmp = File(d, "$target.part")
            ctx.contentResolver.openInputStream(u)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(File(d, target))
        }
        if (isReady(ctx)) "导入完成" else "导入的文件不对，请选择 SenseVoice 的 model.int8.onnx 和 tokens.txt"
    }

    // ---------- recognition ----------

    private var recognizer: OfflineRecognizer? = null
    private var idleJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    private fun recognizer(ctx: Context): OfflineRecognizer {
        recognizer?.let { return it }
        val d = dir(ctx)
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply { sampleRate = PcmRecorder.SAMPLE_RATE; featureDim = 80 }
            modelConfig = OfflineModelConfig().apply {
                senseVoice = OfflineSenseVoiceModelConfig().apply {
                    model = File(d, MODEL).absolutePath
                    language = "auto"
                    // numbers as digits, with punctuation
                    useInverseTextNormalization = true
                }
                tokens = File(d, TOKENS).absolutePath
                numThreads = 4
                provider = "cpu"
                debug = false
            }
        }
        return OfflineRecognizer(null, config).also { recognizer = it }
    }

    /** Load the model in the background while the user is still talking. */
    fun preload(ctx: Context) {
        if (!isReady(ctx)) return
        scope.launch { runCatching { recognizer(ctx.applicationContext) } }
    }

    @Synchronized
    fun release() {
        idleJob?.cancel()
        recognizer?.release()
        recognizer = null
    }

    /** Recognize a 16 kHz mono 16-bit WAV recorded by [PcmRecorder]. */
    suspend fun recognize(ctx: Context, wav: ByteArray): String = withContext(Dispatchers.Default) {
        if (!isReady(ctx)) throw IllegalStateException("离线语音模型还没有下载")
        val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
        val r = recognizer(ctx.applicationContext)
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, PcmRecorder.SAMPLE_RATE)
            r.decode(stream)
            r.getResult(stream).text.trim()
        } finally {
            stream.release()
            idleJob?.cancel()
            idleJob = scope.launch { delay(IDLE_RELEASE_MS); release() }
        }
    }
}
