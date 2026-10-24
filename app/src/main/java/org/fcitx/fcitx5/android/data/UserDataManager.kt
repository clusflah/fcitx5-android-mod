/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object UserDataManager {

    private val json = Json { prettyPrint = true }

    private const val OFFICIAL_PACKAGE = "org.fcitx.fcitx5.android"
    private const val COMMON_PHRASES = "common_phrases.json"

    private fun isCompatiblePackage(pkg: String) =
        pkg == OFFICIAL_PACKAGE || pkg.startsWith("$OFFICIAL_PACKAGE.") &&
                !pkg.startsWith("$OFFICIAL_PACKAGE.plugin")

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    /** @param skip top-level folders of [srcDir] to leave out */
    private fun writeFileTree(srcDir: File, destPrefix: String, dest: ZipOutputStream, skip: Set<String> = emptySet()) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().onEnter { it.relativeTo(srcDir).path !in skip }.forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.getExternalFilesDir(null)!!
    private val recentlyUsedDir = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs
            writeFileTree(sharedPrefsDir, "shared_prefs", zipStream)
            // databases
            writeFileTree(dataBasesDir, "databases", zipStream)
            // external
            // mod: not the offline voice models (hundreds of MB, downloaded again from the settings)
            writeFileTree(externalDir, "external", zipStream, skip = setOf("models"))
            // common phrases (常用语)
            File(appContext.filesDir, COMMON_PHRASES).takeIf { it.isFile }?.let { f ->
                zipStream.putNextEntry(ZipEntry("files/"))
                zipStream.putNextEntry(ZipEntry("files/$COMMON_PHRASES"))
                f.inputStream().use { it.copyTo(zipStream) }
            }
            // recently_used moved to SharedPreference and shoud not be exported
            // metadata
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val metadata = Metadata(
                pkgInfo.packageName,
                pkgInfo.versionCodeCompat,
                Const.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun copyDir(source: File, target: File) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            source.copyRecursively(target, overwrite = true)
        } else {
            Timber.w("Cannot import user data: path='${source.path}', exists=$exists, isDir=$isDir")
        }
    }

    fun import(src: InputStream) = runCatching {
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val extracted = zipStream.extract(tempDir)
                val metadataFile = extracted.find { it.name == "metadata.json" }
                    ?: errorRuntime(R.string.exception_user_data_metadata)
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
                // accept backups from the official build and its variants (.debug / .mod)
                if (!isCompatiblePackage(metadata.packageName))
                    errorRuntime(R.string.exception_user_data_package_name_mismatch)
                // default SharedPreferences file is named "<packageName>_preferences.xml";
                // rename it so settings from another variant apply to this one
                val prefsSrc = File(tempDir, "shared_prefs")
                if (metadata.packageName != BuildConfig.APPLICATION_ID) {
                    val from = File(prefsSrc, "${metadata.packageName}_preferences.xml")
                    val to = File(prefsSrc, "${BuildConfig.APPLICATION_ID}_preferences.xml")
                    if (from.exists()) {
                        to.delete()
                        from.renameTo(to)
                    }
                }
                copyDir(prefsSrc, sharedPrefsDir)
                // common phrases (常用语)
                File(tempDir, "files/$COMMON_PHRASES").takeIf { it.isFile }
                    ?.copyTo(File(appContext.filesDir, COMMON_PHRASES), overwrite = true)
                copyDir(File(tempDir, "databases"), dataBasesDir)
                copyDir(File(tempDir, "external"), externalDir)
                // keep importing recently_used for backwords compatibility
                copyDir(File(tempDir, "recently_used"), recentlyUsedDir)
                metadata
            }
        }
    }
}