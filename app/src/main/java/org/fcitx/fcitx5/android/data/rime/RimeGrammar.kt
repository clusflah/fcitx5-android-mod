/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Rime 语法模型: the 万象 LTS grammar model (amzxyz/RIME-LMDG) for 白霜拼音, set up from the app.
 *
 * The model (~380 MB) is downloaded with the system DownloadManager (or imported from a file) into
 * the Rime user folder, and the grammar settings are written into <schema>.custom.yaml for every
 * 白霜 schema that uses a grammar model: full pinyin, the nine-key schema (it includes
 * rime_frost.schema.yaml as is, so it needs its own patch) and the double pinyin schemas.
 * The lines are kept between markers, so they can be removed again without touching the rest.
 */
package org.fcitx.fcitx5.android.data.rime

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RimeGrammar {

    enum class Model(val label: String, val language: String) {
        Hans("简体", "wanxiang-lts-zh-hans"),
        Hant("繁体", "wanxiang-lts-zh-hant");

        val fileName get() = "$language.gram"
        val url get() = "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/$fileName"
    }

    const val DOWNLOAD_SIZE_MB = 380

    /** a smaller file is not a complete model */
    private const val MIN_SIZE = 100_000_000L

    /** 白霜 schemas with a grammar section, see rime-frost's *.schema.yaml */
    val SCHEMAS = listOf(
        "rime_frost",
        "rime_frost_t9",
        "rime_frost_double_pinyin",
        "rime_frost_double_pinyin_abc",
        "rime_frost_double_pinyin_flypy",
        "rime_frost_double_pinyin_mspy",
        "rime_frost_double_pinyin_sogou",
        "rime_frost_double_pinyin_ziguang",
    )

    private const val BEGIN = "# >>> 万象语法模型（由小企鹅输入法 设置 → Rime 语法模型 添加，可以整段删除）"
    private const val END = "# <<< 万象语法模型"
    private const val CREATED = "# 此文件由小企鹅输入法创建（Rime 语法模型）"

    /** values recommended by rime-frost's documentation for the 万象 model */
    private fun settings(language: String) = listOf(
        "grammar/language" to language,
        "grammar/collocation_max_length" to "5",
        "grammar/collocation_min_length" to "2",
        "grammar/collocation_penalty" to "-14",
        "grammar/non_collocation_penalty" to "-4",
        "translator/contextual_suggestions" to "true",
    )

    fun rimeDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "data/rime")

    private fun downloadDir(ctx: Context) =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "rime-grammar-download")

    fun modelFile(ctx: Context, model: Model) = File(rimeDir(ctx), model.fileName)

    /** models present in the Rime folder */
    fun installed(ctx: Context) = Model.entries.filter { modelFile(ctx, it).length() > MIN_SIZE }

    /** the model the schemas are set to use, read from rime_frost.custom.yaml */
    fun enabled(ctx: Context): Model? {
        val text = File(rimeDir(ctx), "rime_frost.custom.yaml").takeIf { it.exists() }?.readText() ?: return null
        val block = text.substringAfter(BEGIN, "").substringBefore(END)
        return Model.entries.find { block.contains("grammar/language: ${it.language}") }
    }

    fun sizeMb(ctx: Context, model: Model) = modelFile(ctx, model).length() / 1_048_576

    // ---------- settings in <schema>.custom.yaml ----------

    /** Points every 白霜 schema at [model]; returns the files that couldn't be changed. */
    fun enable(ctx: Context, model: Model): List<String> {
        val dir = rimeDir(ctx).apply { mkdirs() }
        val failed = mutableListOf<String>()
        SCHEMAS.forEach { id ->
            val file = File(dir, "$id.custom.yaml")
            val old = if (file.exists()) file.readText() else "$CREATED\npatch:\n"
            val new = addBlock(removeBlock(old), settings(model.language))
            if (new == null) failed.add(file.name) else file.writeText(new)
        }
        return failed
    }

    /** Removes the lines [enable] added; deletes files it created that hold nothing else. */
    fun disable(ctx: Context) {
        SCHEMAS.forEach { id ->
            val file = File(rimeDir(ctx), "$id.custom.yaml")
            if (!file.exists()) return@forEach
            val text = dropEmptyPatch(removeBlock(file.readText()))
            val rest = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            if (text.startsWith(CREATED) && rest.isEmpty()) file.delete()
            else file.writeText(text)
        }
    }

    /** A `patch:` left without entries would be null, which Rime doesn't take as a patch. */
    private fun dropEmptyPatch(text: String): String {
        val lines = text.lines()
        val patch = lines.indexOfFirst { it.trimEnd() == "patch:" }
        if (patch < 0) return text
        val entries = lines.drop(patch + 1).takeWhile { it.isBlank() || it.first().isWhitespace() }
        if (entries.any { it.isNotBlank() && !it.trimStart().startsWith("#") }) return text
        return (lines.take(patch) + lines.drop(patch + 1 + entries.size)).joinToString("\n")
    }

    private fun removeBlock(text: String): String {
        val lines = text.lines()
        val begin = lines.indexOfFirst { it.trim() == BEGIN }
        if (begin < 0) return text
        val end = lines.indexOfFirst { it.trim() == END }.takeIf { it > begin } ?: return text
        return (lines.take(begin) + lines.drop(end + 1)).joinToString("\n")
    }

    /**
     * Puts the settings right after the top-level `patch:` (adding one if missing), indented like
     * the entries already there, and drops the user's own lines for the same keys. Null if the
     * patch is written in a form this doesn't handle (e.g. `patch: { ... }`).
     */
    private fun addBlock(text: String, settings: List<Pair<String, String>>): String? {
        val lines = text.lines().toMutableList()
        var patch = lines.indexOfFirst { it.startsWith("patch:") }
        if (patch < 0) {
            if (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
            lines.add("patch:")
            patch = lines.lastIndex
        } else if (lines[patch].removePrefix("patch:").substringBefore('#').isNotBlank()) {
            return null
        }
        // the patch's entries: the indented lines after it
        var end = patch + 1
        while (end < lines.size && (lines[end].isBlank() || lines[end].first().isWhitespace())) end++
        val entries = lines.subList(patch + 1, end)
        val indent = entries.firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            ?.takeWhile { it.isWhitespace() } ?: "  "
        val keys = settings.map { it.first }.toSet()
        entries.removeAll { line ->
            line.startsWith(indent) && !line.startsWith("$indent ") &&
                    line.trim().substringBefore(':').trim('"', '\'', ' ') in keys
        }
        val block = listOf("$indent$BEGIN") + settings.map { (k, v) -> "$indent$k: $v" } + "$indent$END"
        lines.addAll(patch + 1, block)
        return lines.joinToString("\n").trimEnd('\n') + "\n"
    }

    // ---------- download (system DownloadManager) and import ----------

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("rime_grammar", Context.MODE_PRIVATE)

    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    fun startDownload(ctx: Context, model: Model) {
        cancelDownload(ctx)
        val tmp = downloadDir(ctx).apply { deleteRecursively(); mkdirs() }
        val req = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Rime 语法模型：${model.fileName}")
            .setDestinationUri(Uri.fromFile(File(tmp, model.fileName)))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
        val id = dm(ctx).enqueue(req)
        sp(ctx).edit().putLong("download_id", id).putString("download_model", model.name).apply()
    }

    fun cancelDownload(ctx: Context) {
        downloadId(ctx)?.let { runCatching { dm(ctx).remove(it) } }
        sp(ctx).edit().remove("download_id").remove("download_model").apply()
    }

    private fun downloadId(ctx: Context) = sp(ctx).getLong("download_id", -1L).takeIf { it >= 0 }

    sealed class DownloadState {
        object None : DownloadState()
        class Running(val doneBytes: Long, val totalBytes: Long) : DownloadState()
        class Failed(val reason: String) : DownloadState()
        class Done(val model: Model) : DownloadState()
    }

    /** Current state; once finished, checks the file and moves it into the Rime folder. */
    fun downloadState(ctx: Context): DownloadState {
        val id = downloadId(ctx) ?: return DownloadState.None
        val model = sp(ctx).getString("download_model", null)
            ?.let { name -> Model.entries.find { it.name == name } } ?: Model.Hans
        var done = 0L
        var total = 0L
        var status = DownloadManager.STATUS_PENDING
        var reason = 0
        dm(ctx).query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) {
                cancelDownload(ctx)
                return DownloadState.Failed("下载已被取消")
            }
            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        }
        when (status) {
            DownloadManager.STATUS_FAILED -> {
                cancelDownload(ctx)
                return DownloadState.Failed("下载失败（代码 $reason），请检查网络，或在电脑上下载后用“从文件导入”")
            }
            DownloadManager.STATUS_SUCCESSFUL -> {}
            else -> return DownloadState.Running(done, if (total > 0) total else DOWNLOAD_SIZE_MB * 1_048_576L)
        }
        sp(ctx).edit().remove("download_id").remove("download_model").apply()
        val tmp = File(downloadDir(ctx), model.fileName)
        if (tmp.length() < MIN_SIZE || (total > 0 && tmp.length() != total)) {
            downloadDir(ctx).deleteRecursively()
            return DownloadState.Failed("下载的文件不完整，请重试")
        }
        val target = modelFile(ctx, model).apply { parentFile?.mkdirs(); delete() }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        downloadDir(ctx).deleteRecursively()
        return DownloadState.Done(model)
    }

    /** Copies a picked .gram file into the Rime folder; returns the model, or null if it isn't one. */
    suspend fun import(ctx: Context, uri: Uri): Model? = withContext(Dispatchers.IO) {
        val name = ctx.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
        val model = if (name.contains("hant", ignoreCase = true)) Model.Hant else Model.Hans
        if (!name.endsWith(".gram", ignoreCase = true)) return@withContext null
        val target = modelFile(ctx, model).apply { parentFile?.mkdirs() }
        val part = File(target.path + ".part")
        ctx.contentResolver.openInputStream(uri)?.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
        if (part.length() < MIN_SIZE) {
            part.delete()
            return@withContext null
        }
        target.delete()
        part.renameTo(target)
        model
    }

    /** Turns the model off if it was in use, then deletes it. */
    fun delete(ctx: Context, model: Model) {
        if (enabled(ctx) == model) disable(ctx)
        modelFile(ctx, model).delete()
    }
}
