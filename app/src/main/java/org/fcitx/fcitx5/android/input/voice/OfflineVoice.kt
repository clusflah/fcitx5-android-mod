/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Offline speech recognition via sherpa-onnx, with a choice of models ([MODELS]: SenseVoice,
 * Paraformer, Whisper, Parakeet, Moonshine, Dolphin). The one in use is AppPrefs.voiceMore.offlineModel.
 *
 * Models are not bundled: each is downloaded from Hugging Face (k2-fsa's sherpa-onnx exports) with
 * the system DownloadManager, or imported from files, into the app's external files dir. Audio never
 * leaves the phone. The recognizer is loaded on first use and released after a while to free memory.
 */
package org.fcitx.fcitx5.android.input.voice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OfflineVoice {

    /**
     * One downloadable model.
     * @param files file name → size in bytes of the Hugging Face download (checked after downloading)
     * @param configure fills in the recognizer's model config from the model's folder
     */
    class Model(
        val id: String,
        val name: String,
        val description: String,
        private val repo: String,
        val files: Map<String, Long>,
        val configure: OfflineModelConfig.(File) -> Unit
    ) {
        val sizeMb get() = files.values.sum() / 1_048_576
        fun url(base: String, file: String) = "$base/$repo/resolve/main/$file"
    }

    val MODELS = listOf(
        Model(
            "sensevoice", "SenseVoice", "中 / 英 / 日 / 韩 / 粤，速度快，自带标点（推荐）",
            "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            mapOf("model.int8.onnx" to 237_115_547L, "tokens.txt" to 315_894L)
        ) { d ->
            senseVoice = OfflineSenseVoiceModelConfig().apply {
                model = File(d, "model.int8.onnx").absolutePath
                language = "auto"
                // numbers as digits, with punctuation
                useInverseTextNormalization = true
            }
            tokens = File(d, "tokens.txt").absolutePath
        },
        Model(
            "paraformer_zh", "Paraformer 中文", "阿里 FunASR，普通话准确率高；不带标点",
            "csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09",
            mapOf("model.int8.onnx" to 227_330_205L, "tokens.txt" to 75_354L)
        ) { d ->
            paraformer = OfflineParaformerModelConfig().apply { model = File(d, "model.int8.onnx").absolutePath }
            tokens = File(d, "tokens.txt").absolutePath
        },
        Model(
            "whisper_base", "Whisper base", "OpenAI，99 种语言，覆盖面最广",
            "csukuangfj/sherpa-onnx-whisper-base",
            mapOf(
                "base-encoder.int8.onnx" to 29_120_534L, "base-decoder.int8.onnx" to 130_672_026L,
                "base-tokens.txt" to 816_730L
            )
        ) { d ->
            whisper = OfflineWhisperModelConfig().apply {
                encoder = File(d, "base-encoder.int8.onnx").absolutePath
                decoder = File(d, "base-decoder.int8.onnx").absolutePath
                // detect the language
                language = ""
                task = "transcribe"
            }
            tokens = File(d, "base-tokens.txt").absolutePath
            modelType = "whisper"
        },
        Model(
            "parakeet_v3", "Parakeet TDT 0.6B v3", "NVIDIA，英语及 25 种欧洲语言，准确率高；体积大",
            "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            mapOf(
                "encoder.int8.onnx" to 652_184_281L, "decoder.int8.onnx" to 11_845_275L,
                "joiner.int8.onnx" to 6_355_277L, "tokens.txt" to 93_939L
            )
        ) { d ->
            transducer = OfflineTransducerModelConfig().apply {
                encoder = File(d, "encoder.int8.onnx").absolutePath
                decoder = File(d, "decoder.int8.onnx").absolutePath
                joiner = File(d, "joiner.int8.onnx").absolutePath
            }
            tokens = File(d, "tokens.txt").absolutePath
            modelType = "nemo_transducer"
        },
        Model(
            "moonshine_base", "Moonshine base", "仅英语，速度快，适合低端机",
            "csukuangfj/sherpa-onnx-moonshine-base-en-int8",
            mapOf(
                "preprocess.onnx" to 14_077_290L, "encode.int8.onnx" to 50_311_494L,
                "uncached_decode.int8.onnx" to 122_120_451L, "cached_decode.int8.onnx" to 99_983_837L,
                "tokens.txt" to 436_688L
            )
        ) { d ->
            moonshine = OfflineMoonshineModelConfig().apply {
                preprocessor = File(d, "preprocess.onnx").absolutePath
                encoder = File(d, "encode.int8.onnx").absolutePath
                uncachedDecoder = File(d, "uncached_decode.int8.onnx").absolutePath
                cachedDecoder = File(d, "cached_decode.int8.onnx").absolutePath
            }
            tokens = File(d, "tokens.txt").absolutePath
        },
        Model(
            "dolphin_base", "Dolphin base", "东亚、东南亚多种语言及中文方言；体积小",
            "csukuangfj/sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02",
            mapOf("model.int8.onnx" to 103_729_802L, "tokens.txt" to 504_662L)
        ) { d ->
            dolphin = OfflineDolphinModelConfig().apply { model = File(d, "model.int8.onnx").absolutePath }
            tokens = File(d, "tokens.txt").absolutePath
        },
    )

    /** download sources: label → base URL ("<base>/<repo>/resolve/main/<file>") */
    val SOURCES = listOf(
        "Hugging Face（官方，需要能访问外网）" to "https://huggingface.co",
        "hf-mirror.com（国内镜像，可能不可用）" to "https://hf-mirror.com"
    )

    /** release the recognizer (hundreds of MB of memory) after this long without use */
    private const val IDLE_RELEASE_MS = 5 * 60 * 1000L

    private val modelPref get() = AppPrefs.getInstance().voiceMore.offlineModel

    /** the model in use */
    val current: Model get() = MODELS.find { it.id == modelPref.getValue() } ?: MODELS.first()

    fun select(m: Model) {
        if (m.id == modelPref.getValue()) return
        release()
        modelPref.setValue(m.id)
    }

    // SenseVoice keeps the folder it had when it was the only model
    fun dir(ctx: Context, m: Model = current) =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models/${m.id}")

    private fun downloadDir(ctx: Context, m: Model) = File(dir(ctx, m).parentFile, "${m.id}-download")

    /** all files present; an imported model may be another export, so only check they aren't tiny */
    fun isReady(ctx: Context, m: Model = current): Boolean {
        val d = dir(ctx, m)
        return m.files.keys.all { File(d, it).length() > 1000L }
    }

    fun sizeMb(ctx: Context, m: Model = current): Long =
        (dir(ctx, m).listFiles()?.sumOf { it.length() } ?: 0L) / 1_048_576

    fun delete(ctx: Context, m: Model = current) {
        if (m.id == loadedId) release()
        dir(ctx, m).deleteRecursively()
        downloadDir(ctx, m).deleteRecursively()
    }

    // ---------- download (system DownloadManager: survives leaving the page, shows a notification) ----------

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("offline_voice", Context.MODE_PRIVATE)

    // SenseVoice keeps the key it had when it was the only model
    private fun idsKey(m: Model) = if (m.id == "sensevoice") "download_ids" else "download_ids_${m.id}"

    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    fun startDownload(ctx: Context, m: Model, baseUrl: String) {
        cancelDownload(ctx, m)
        val tmp = downloadDir(ctx, m).apply { deleteRecursively(); mkdirs() }
        val ids = m.files.keys.map { name ->
            val req = DownloadManager.Request(Uri.parse(m.url(baseUrl, name)))
                .setTitle("离线语音模型 ${m.name}：$name")
                .setDestinationUri(Uri.fromFile(File(tmp, name)))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
            dm(ctx).enqueue(req)
        }
        sp(ctx).edit().putString(idsKey(m), ids.joinToString(",")).apply()
    }

    fun cancelDownload(ctx: Context, m: Model) {
        downloadIds(ctx, m).forEach { runCatching { dm(ctx).remove(it) } }
        sp(ctx).edit().remove(idsKey(m)).apply()
    }

    private fun downloadIds(ctx: Context, m: Model) =
        sp(ctx).getString(idsKey(m), null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

    sealed class DownloadState {
        object None : DownloadState()
        class Running(val doneBytes: Long, val totalBytes: Long) : DownloadState()
        class Failed(val reason: String) : DownloadState()
        object Done : DownloadState()
    }

    /** Current state; when all files finished, verify their sizes and move them into place. */
    fun downloadState(ctx: Context, m: Model): DownloadState {
        val ids = downloadIds(ctx, m)
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
                        cancelDownload(ctx, m)
                        return DownloadState.Failed("${m.name} 下载失败（代码 $reason），请检查网络或换一个下载源")
                    }
                }
            }
        }
        if (finished < ids.size) return DownloadState.Running(done, total.coerceAtLeast(m.files.values.sum()))
        // all finished: verify and install
        sp(ctx).edit().remove(idsKey(m)).apply()
        val tmp = downloadDir(ctx, m)
        val bad = m.files.filter { (name, size) -> File(tmp, name).length() != size }.keys
        if (bad.isNotEmpty()) {
            tmp.deleteRecursively()
            return DownloadState.Failed("${m.name} 下载的文件不完整：${bad.joinToString()}，请重试")
        }
        if (m.id == loadedId) release()
        val d = dir(ctx, m).apply { deleteRecursively(); mkdirs() }
        m.files.keys.forEach { File(tmp, it).renameTo(File(d, it)) }
        tmp.deleteRecursively()
        return DownloadState.Done
    }

    // ---------- import from files ----------

    /**
     * Copy picked files into [m]'s folder: files named as in the download are taken as they are;
     * for a model of one .onnx and one .txt, any .onnx / .txt will do.
     */
    suspend fun import(ctx: Context, m: Model, uris: List<Uri>): String = withContext(Dispatchers.IO) {
        fun name(u: Uri) = ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: u.lastPathSegment.orEmpty()
        val picked = uris.associateBy { name(it) }
        val targets = mutableMapOf<String, Uri>()
        m.files.keys.forEach { target -> picked[target]?.let { targets[target] = it } }
        val onnx = m.files.keys.filter { it.endsWith(".onnx") }
        val txt = m.files.keys.filter { it.endsWith(".txt") }
        if (onnx.size == 1 && txt.size == 1) {
            if (onnx[0] !in targets) picked.entries.find { it.key.endsWith(".onnx", true) }?.let { targets[onnx[0]] = it.value }
            if (txt[0] !in targets) picked.entries.find { it.key.endsWith(".txt", true) }?.let { targets[txt[0]] = it.value }
        }
        val missing = m.files.keys - targets.keys - dir(ctx, m).list().orEmpty().toSet()
        if (targets.isEmpty()) return@withContext "没有选到 ${m.name} 需要的文件：${m.files.keys.joinToString()}"
        if (m.id == loadedId) release()
        val d = dir(ctx, m).apply { mkdirs() }
        targets.forEach { (target, u) ->
            val tmp = File(d, "$target.part")
            ctx.contentResolver.openInputStream(u)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(File(d, target))
        }
        when {
            isReady(ctx, m) -> "导入完成"
            missing.isNotEmpty() -> "还缺少：${missing.joinToString()}"
            else -> "导入的文件不对，请选择 ${m.files.keys.joinToString()}"
        }
    }

    // ---------- recognition ----------

    private var recognizer: OfflineRecognizer? = null
    private var loadedId: String? = null
    private var idleJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    private fun recognizer(ctx: Context): OfflineRecognizer {
        val m = current
        if (loadedId == m.id) recognizer?.let { return it }
        release()
        val d = dir(ctx, m)
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply { sampleRate = PcmRecorder.SAMPLE_RATE; featureDim = 80 }
            modelConfig = OfflineModelConfig().apply {
                m.configure(this, d)
                numThreads = 4
                provider = "cpu"
                debug = false
            }
        }
        return OfflineRecognizer(null, config).also {
            recognizer = it
            loadedId = m.id
        }
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
        loadedId = null
    }

    /** Recognize a 16 kHz mono 16-bit WAV recorded by [PcmRecorder]. */
    suspend fun recognize(ctx: Context, wav: ByteArray): String = withContext(Dispatchers.Default) {
        if (!isReady(ctx)) throw IllegalStateException("离线语音模型 ${current.name} 还没有下载")
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
