/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: manages separate Light and Dark custom backdrop images for Liquid Glass themes
 * (storage, Direct Boot mirror, cross-slot copy, and in-memory caching).
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
import timber.log.Timber
import java.io.File

object GlassCustomBackdrop {

    private const val CROPPED_LIGHT = "glass_backdrop_cropped.png"
    private const val SRC_LIGHT = "glass_backdrop_src"

    private const val CROPPED_DARK = "glass_backdrop_dark_cropped.png"
    private const val SRC_DARK = "glass_backdrop_dark_src"

    @Volatile
    private var cachedLightBitmap: Bitmap? = null

    @Volatile
    private var cachedDarkBitmap: Bitmap? = null

    private fun croppedName(isDark: Boolean) = if (isDark) CROPPED_DARK else CROPPED_LIGHT
    private fun srcName(isDark: Boolean) = if (isDark) SRC_DARK else SRC_LIGHT

    private fun hasPref(isDark: Boolean) =
        if (isDark) ThemeManager.glassPrefs.hasCustomBackdropDark
        else ThemeManager.glassPrefs.hasCustomBackdrop

    private fun rectPref(isDark: Boolean) =
        if (isDark) ThemeManager.glassPrefs.backdropCropRectDark
        else ThemeManager.glassPrefs.backdropCropRect

    private fun rotationPref(isDark: Boolean) =
        if (isDark) ThemeManager.glassPrefs.backdropCropRotationDark
        else ThemeManager.glassPrefs.backdropCropRotation

    private fun brightnessPref(isDark: Boolean) =
        if (isDark) ThemeManager.glassPrefs.backdropBrightnessDark
        else ThemeManager.glassPrefs.backdropBrightnessLight

    private fun getCached(isDark: Boolean): Bitmap? =
        if (isDark) cachedDarkBitmap else cachedLightBitmap

    private fun setCached(isDark: Boolean, bmp: Bitmap?) {
        if (isDark) cachedDarkBitmap = bmp else cachedLightBitmap = bmp
    }

    private fun isUserUnlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = appContext.getSystemService(UserManager::class.java)
        return um?.isUserUnlocked != false
    }

    private fun themeDir(): File? =
        if (isUserUnlocked()) appContext.getExternalFilesDir(null)?.let { File(it, "theme").apply { mkdirs() } }
        else null

    private fun primaryCroppedFile(isDark: Boolean): File? =
        themeDir()?.let { File(it, croppedName(isDark)) }

    fun sourceFile(isDark: Boolean): File? =
        themeDir()?.let { File(it, srcName(isDark)) }?.takeIf { it.exists() }

    private fun directBootCroppedFile(isDark: Boolean): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            File(appContext.createDeviceProtectedStorageContext().filesDir, croppedName(isDark))
        } else null

    fun hasImage(isDark: Boolean): Boolean {
        if (getCached(isDark) != null) return true
        val pref = hasPref(isDark)
        val primary = primaryCroppedFile(isDark)
        if (primary != null && primary.exists()) {
            if (!pref.getValue()) {
                pref.setValue(true)
            }
            return true
        }
        val deFile = directBootCroppedFile(isDark)
        return pref.getValue() && deFile?.exists() == true
    }

    fun hasAnyImage(): Boolean = hasImage(false) || hasImage(true)

    fun initialCropRect(isDark: Boolean): Rect? {
        val raw = rectPref(isDark).getValue()
        if (raw.isBlank()) return null
        val parts = raw.split(',')
        if (parts.size != 4) return null
        val l = parts[0].toIntOrNull() ?: return null
        val t = parts[1].toIntOrNull() ?: return null
        val r = parts[2].toIntOrNull() ?: return null
        val b = parts[3].toIntOrNull() ?: return null
        return Rect(l, t, r, b)
    }

    fun initialCropRotation(isDark: Boolean): Int = rotationPref(isDark).getValue()

    suspend fun saveFromCropResult(
        context: Context,
        isDark: Boolean,
        result: CropImageActivity.CropResult.Success,
        isNewSource: Boolean
    ) {
        val bmp = withContext(Dispatchers.IO) {
            val tempCropped = result.file
            val decoded = if (tempCropped.exists()) {
                primaryCroppedFile(isDark)?.let { out ->
                    runCatching { tempCropped.copyTo(out, overwrite = true) }
                    directBootCroppedFile(isDark)?.let { de ->
                        runCatching { out.copyTo(de, overwrite = true) }
                    }
                }
                val b = BitmapFactory.decodeFile(tempCropped.absolutePath)
                tempCropped.delete()
                b
            } else {
                runCatching { result.bitmap }.getOrNull()?.also { b ->
                    primaryCroppedFile(isDark)?.let { out ->
                        out.delete()
                        out.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        directBootCroppedFile(isDark)?.let { de ->
                            runCatching { out.copyTo(de, overwrite = true) }
                        }
                    }
                }
            } ?: return@withContext null

            if (isNewSource) {
                themeDir()?.let { dir ->
                    val dstSrc = File(dir, srcName(isDark))
                    val srcUri = result.srcUri
                    runCatching {
                        if (srcUri.scheme == "file") {
                            val srcFile = srcUri.path?.let(::File)
                            if (srcFile != null && srcFile.exists() && srcFile.canonicalPath != dstSrc.canonicalPath) {
                                srcFile.copyTo(dstSrc, overwrite = true)
                                if (srcFile.parentFile == context.cacheDir && srcFile.name.startsWith("crop_src_")) {
                                    srcFile.delete()
                                }
                            }
                        } else {
                            context.contentResolver.openInputStream(srcUri)?.use { input ->
                                dstSrc.outputStream().use { out -> input.copyTo(out) }
                            }
                        }
                    }.onFailure { e ->
                        Timber.w(e, "Failed to persist backdrop source image from $srcUri")
                    }
                }
            }
            decoded
        } ?: return
        setCached(isDark, bmp)
        rectPref(isDark).setValue("${result.rect.left},${result.rect.top},${result.rect.right},${result.rect.bottom}")
        rotationPref(isDark).setValue(result.rotation)
        hasPref(isDark).setValue(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        ThemeManager.notifyGlassStateChanged()
    }

    /** Copies the cropped backdrop, original source file, and crop transform from `!targetIsDark` into `targetIsDark`. */
    suspend fun copyFromOtherMode(targetIsDark: Boolean) {
        val srcIsDark = !targetIsDark
        if (!hasImage(srcIsDark)) return
        val bmp = withContext(Dispatchers.IO) {
            val fromCropped = primaryCroppedFile(srcIsDark)?.takeIf { it.exists() }
                ?: directBootCroppedFile(srcIsDark)?.takeIf { it.exists() }
                ?: return@withContext null
            primaryCroppedFile(targetIsDark)?.let { toCropped ->
                runCatching { fromCropped.copyTo(toCropped, overwrite = true) }
                directBootCroppedFile(targetIsDark)?.let { de ->
                    runCatching { toCropped.copyTo(de, overwrite = true) }
                }
            }
            sourceFile(srcIsDark)?.let { fromSrc ->
                themeDir()?.let { dir ->
                    runCatching { fromSrc.copyTo(File(dir, srcName(targetIsDark)), overwrite = true) }
                }
            }
            runCatching { BitmapFactory.decodeFile(fromCropped.absolutePath) }.getOrNull()
        } ?: return
        setCached(targetIsDark, bmp)
        rectPref(targetIsDark).setValue(rectPref(srcIsDark).getValue())
        rotationPref(targetIsDark).setValue(rotationPref(srcIsDark).getValue())
        hasPref(targetIsDark).setValue(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        ThemeManager.notifyGlassStateChanged()
    }

    fun clear(isDark: Boolean) {
        setCached(isDark, null)
        runCatching { primaryCroppedFile(isDark)?.delete() }
        runCatching { themeDir()?.let { File(it, srcName(isDark)).delete() } }
        runCatching { directBootCroppedFile(isDark)?.delete() }
        hasPref(isDark).setValue(false)
        rectPref(isDark).setValue("")
        rotationPref(isDark).setValue(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        ThemeManager.notifyGlassStateChanged()
    }

    fun loadDrawable(isDark: Boolean): Drawable? {
        if (!hasImage(isDark)) return null
        var bmp = getCached(isDark)
        if (bmp == null || bmp.isRecycled) {
            val file = primaryCroppedFile(isDark)?.takeIf { it.exists() }
                ?: directBootCroppedFile(isDark)?.takeIf { it.exists() }
                ?: return null
            bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
            setCached(isDark, bmp)
        }
        val brightness = brightnessPref(isDark).getValue().coerceIn(10, 100)
        return BitmapDrawable(appContext.resources, bmp).apply {
            if (brightness < 100) {
                colorFilter = DarkenColorFilter(100 - brightness)
            }
        }
    }
}
