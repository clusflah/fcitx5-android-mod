/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Pictures inside an assistant reply: ![alt](https://…) / data: / file:// images in the Markdown,
 * and the images Gemini returns as inlineData (saved to the cache and referenced by file://).
 * MarkdownText.render(images = true) leaves "[图片]" + MarkdownText.Image where each one goes;
 * [load] fetches the picture, [exportText] turns the reply back into text other apps can take.
 */
package org.fcitx.fcitx5.android.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object MarkdownImages {

    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val CACHE_DIR = "ai_reply_images"
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    // a reply's pictures are decoded once, also when switching between 纯文本 and 富文本
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** an image Gemini sent inline: stored in the cache (kept a day), returned as a file:// url */
    fun saveInline(base64: String, mime: String): String? = runCatching {
        val dir = File(appContext.cacheDir, CACHE_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > KEEP_MS) it.delete() }
        val ext = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
        "file://${file.absolutePath}"
    }.getOrNull()

    /** the picture scaled down to at most [maxWidth] px wide, or null when it can't be had */
    suspend fun load(url: String, maxWidth: Int): Bitmap? {
        val key = "$maxWidth|$url"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = bytesOf(url) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    private fun bytesOf(url: String): ByteArray? = when {
        url.startsWith("data:", ignoreCase = true) ->
            Base64.decode(url.substringAfter(','), Base64.DEFAULT)
        url.startsWith("file://") -> {
            // only our own cache: a reply must not be able to show arbitrary files of the app
            val f = File(url.removePrefix("file://")).canonicalFile
            val dir = File(appContext.cacheDir, CACHE_DIR).canonicalFile
            if (f.parentFile == dir && f.length() in 1..MAX_BYTES) f.readBytes() else null
        }
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true) -> {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.use { ins ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) return@use null
                    }
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        else -> null
    }

    private fun isWeb(url: String) =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /**
     * The reply as text for typing / copying: each picture becomes its web address, or "[图片]"
     * for ones only this phone has. With [keepWebImages] a web picture keeps its ImageSpan, so the
     * HTML put on the clipboard carries an <img>.
     */
    fun exportText(text: CharSequence, keepWebImages: Boolean = false): SpannableStringBuilder {
        val out = SpannableStringBuilder(text)
        out.getSpans(0, out.length, MarkdownText.Image::class.java)
            .sortedByDescending { out.getSpanStart(it) }
            .forEach { img ->
                val start = out.getSpanStart(img)
                val end = out.getSpanEnd(img)
                if (start < 0) return@forEach
                out.getSpans(start, end, ImageSpan::class.java).forEach { out.removeSpan(it) }
                out.removeSpan(img)
                if (keepWebImages && isWeb(img.url)) {
                    out.replace(start, end, "￼")
                    // only its source matters here: HtmlCompat writes it as <img src="…">
                    val blank = ColorDrawable(0).apply { setBounds(0, 0, 1, 1) }
                    out.setSpan(ImageSpan(blank, img.url), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    out.replace(start, end, if (isWeb(img.url)) img.url else img.alt.ifBlank { MarkdownText.IMAGE_PLACEHOLDER })
                }
            }
        return out
    }
}
