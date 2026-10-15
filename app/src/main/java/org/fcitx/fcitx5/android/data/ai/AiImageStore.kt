/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Generated images (生成图片): kept in the cache and shared through a FileProvider so they can be
 * inserted into the editor (commitContent), copied, shared or saved to the gallery.
 */
package org.fcitx.fcitx5.android.data.ai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Own subclass so the manifest entry can't clash with other libraries' FileProviders. */
class AiImageFileProvider : FileProvider()

object AiImageStore {

    private const val DIR = "ai_images"
    private const val KEEP = 20

    private fun authority(ctx: Context) = "${ctx.packageName}.ai.images"

    fun extension(mime: String) = when (mime) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    /** Writes the image to the cache and returns its content:// uri. Old images beyond [KEEP] are removed. */
    fun save(ctx: Context, image: AiImage): Uri {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP - 1)?.forEach { it.delete() }
        val file = File(dir, "ai_${System.currentTimeMillis()}.${extension(image.mime)}")
        file.writeBytes(image.bytes)
        return FileProvider.getUriForFile(ctx, authority(ctx), file)
    }

    /** Saves a copy to Pictures/Fcitx5; Android 10+ only (older versions would need the storage permission). */
    fun saveToGallery(ctx: Context, image: AiImage): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ai_${System.currentTimeMillis()}.${extension(image.mime)}")
            put(MediaStore.Images.Media.MIME_TYPE, image.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Fcitx5")
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)!!.use { it.write(image.bytes) }
            true
        }.getOrElse {
            resolver.delete(uri, null, null); false
        }
    }
}
