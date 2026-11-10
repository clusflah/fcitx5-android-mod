/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: manages the custom backdrop image for Liquid Glass themes (storage, Direct Boot mirror, and caching).
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.ui.main.CropImageActivity
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

object GlassCustomBackdrop {

    private const val CROPPED_NAME = "glass_backdrop_cropped.png"
    private const val SRC_NAME = "glass_backdrop_src"

    @Volatile
    private var cachedBitmap: Bitmap? = null

    private fun isUserUnlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = appContext.getSystemService(UserManager::class.java)
        return um?.isUserUnlocked != false
    }

    private fun themeDir(): File? =
        if (isUserUnlocked()) appContext.getExternalFilesDir(null)?.let { File(it, "theme").apply { mkdirs() } }
        else null

    private fun primaryCroppedFile(): File? = themeDir()?.let { File(it, CROPPED_NAME) }

    fun sourceFile(): File? = themeDir()?.let { File(it, SRC_NAME) }?.takeIf { it.exists() }

    private fun directBootCroppedFile(): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            File(appContext.createDeviceProtectedStorageContext().filesDir, CROPPED_NAME)
        } else null

    fun hasImage(): Boolean {
        if (cachedBitmap != null) return true
        val primary = primaryCroppedFile()
        if (primary != null && primary.exists()) {
            if (!ThemeManager.glassPrefs.hasCustomBackdrop.getValue()) {
                ThemeManager.glassPrefs.hasCustomBackdrop.setValue(true)
            }
            return true
        }
        val deFile = directBootCroppedFile()
        return ThemeManager.glassPrefs.hasCustomBackdrop.getValue() && deFile?.exists() == true
    }

    fun initialCropRect(): Rect? {
        val raw = ThemeManager.glassPrefs.backdropCropRect.getValue()
        if (raw.isBlank()) return null
        val parts = raw.split(',')
        if (parts.size != 4) return null
        val l = parts[0].toIntOrNull() ?: return null
        val t = parts[1].toIntOrNull() ?: return null
        val r = parts[2].toIntOrNull() ?: return null
        val b = parts[3].toIntOrNull() ?: return null
        return Rect(l, t, r, b)
    }

    fun initialCropRotation(): Int = ThemeManager.glassPrefs.backdropCropRotation.getValue()

    suspend fun saveFromCropResult(
        context: Context,
        result: CropImageActivity.CropResult.Success,
        isNewSource: Boolean
    ) {
        val bmp = withContext(Dispatchers.IO) {
            val decoded = result.bitmap
            primaryCroppedFile()?.let { out ->
                out.delete()
                out.outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) }
                directBootCroppedFile()?.let { de ->
                    runCatching { out.copyTo(de, overwrite = true) }
                }
            }
            if (isNewSource) {
                themeDir()?.let { dir ->
                    val src = File(dir, SRC_NAME)
                    context.contentResolver.openInputStream(result.srcUri)?.use { input ->
                        src.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }
            decoded
        }
        cachedBitmap = bmp
        val gp = ThemeManager.glassPrefs
        gp.backdropCropRect.setValue("${result.rect.left},${result.rect.top},${result.rect.right},${result.rect.bottom}")
        gp.backdropCropRotation.setValue(result.rotation)
        gp.hasCustomBackdrop.setValue(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        ThemeManager.notifyGlassStateChanged()
    }

    fun clear() {
        cachedBitmap = null
        runCatching { primaryCroppedFile()?.delete() }
        runCatching { themeDir()?.let { File(it, SRC_NAME).delete() } }
        runCatching { directBootCroppedFile()?.delete() }
        val gp = ThemeManager.glassPrefs
        gp.hasCustomBackdrop.setValue(false)
        gp.backdropCropRect.setValue("")
        gp.backdropCropRotation.setValue(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        ThemeManager.notifyGlassStateChanged()
    }

    fun loadDrawable(): Drawable? {
        if (!hasImage()) return null
        var bmp = cachedBitmap
        if (bmp == null || bmp.isRecycled) {
            val file = primaryCroppedFile()?.takeIf { it.exists() }
                ?: directBootCroppedFile()?.takeIf { it.exists() }
                ?: return null
            bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
            cachedBitmap = bmp
        }
        val brightness = ThemeManager.glassPrefs.backdropBrightness.getValue().coerceIn(10, 100)
        return BitmapDrawable(appContext.resources, bmp).apply {
            if (brightness < 100) {
                colorFilter = DarkenColorFilter(100 - brightness)
            }
        }
    }
}
