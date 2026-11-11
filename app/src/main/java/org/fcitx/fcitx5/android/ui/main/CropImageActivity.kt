/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.subMenu
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.topPadding
import timber.log.Timber
import java.io.File

class CropImageActivity : AppCompatActivity() {

    companion object {
        const val CROP_OPTIONS = "crop_options"
        const val CROP_RESULT = "crop_result"
        private const val STATE_SOURCE_URI = "state_source_uri"
        private const val STATE_TEMP_SRC_PATH = "state_temp_src_path"
    }

    sealed class CropOption : Parcelable {
        abstract val width: Int
        abstract val height: Int

        @Parcelize
        data class New(override val width: Int, override val height: Int) : CropOption()

        @Parcelize
        data class Edit(
            override val width: Int,
            override val height: Int,
            val sourceUri: Uri,
            val initialRect: Rect? = null,
            val initialRotation: Int = 0
        ) : CropOption()
    }

    sealed class CropResult : Parcelable {
        @Parcelize
        data object Fail : CropResult()

        @Parcelize
        data class Success(
            val rect: Rect,
            val rotation: Int,
            val file: File,
            val srcUri: Uri
        ) : CropResult() {
            // TODO: find some way to transfer large Bitmap without writing to file
            @IgnoredOnParcel
            private var _bitmap: Bitmap? = null
            val bitmap: Bitmap
                get() {
                    _bitmap?.let { return it }
                    return BitmapFactory.decodeFile(file.path).also {
                        _bitmap = it
                        file.delete()
                    }
                }
        }
    }

    class CropContract : ActivityResultContract<CropOption, CropResult>() {
        override fun createIntent(context: Context, input: CropOption): Intent {
            return Intent(context, CropImageActivity::class.java).putExtra(CROP_OPTIONS, input)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): CropResult {
            val result = intent?.parcelable<CropResult.Success>(CROP_RESULT)
            if (resultCode != RESULT_OK || result == null) {
                return CropResult.Fail
            }
            return result
        }
    }

    private lateinit var cropOption: CropOption

    private lateinit var root: ConstraintLayout
    private lateinit var toolbar: Toolbar
    private lateinit var cropView: CropImageView

    private lateinit var sourceImageUri: Uri
    private var tempSourceFile: File? = null
    private var isCropping = false

    private val launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            setResult(RESULT_CANCELED)
            finish()
        } else {
            // mod: copy transient Photo Picker content:// URI to app cacheDir immediately while
            // CropImageActivity holds the picker URI grant; Android 16+ revokes picker_get_content
            // grants as soon as CropImageActivity finishes, which otherwise throws SecurityException
            // when the caller reads srcUri after returning.
            lifecycleScope.launch {
                val localUri = if (uri.scheme == "content") {
                    try {
                        val cached = withContext(Dispatchers.IO) {
                            pruneStaleCropCache()
                            val ext = MimeTypeMap.getSingleton()
                                .getExtensionFromMimeType(contentResolver.getType(uri))
                                ?.takeIf { it.isNotBlank() }
                            val suffix = if (ext != null) ".$ext" else ".img"
                            val dst = File.createTempFile("crop_src_", suffix, cacheDir)
                            try {
                                contentResolver.openInputStream(uri)?.use { input ->
                                    dst.outputStream().use { out -> input.copyTo(out) }
                                } ?: throw IllegalStateException("Cannot open input stream for $uri")
                                dst
                            } catch (e: Exception) {
                                dst.delete()
                                throw e
                            }
                        }
                        tempSourceFile = cached
                        Uri.fromFile(cached)
                    } catch (e: Exception) {
                        Timber.e("Exception when caching picked image: ${e.stackTraceToString()}")
                        toast(e)
                        setResult(RESULT_CANCELED)
                        finish()
                        return@launch
                    }
                } else {
                    uri
                }
                sourceImageUri = localUri
                cropView.setImageUriAsync(localUri)
            }
        }
    }

    private fun pruneStaleCropCache() {
        val cutoff = System.currentTimeMillis() - 3_600_000L
        cacheDir.listFiles()?.forEach { f ->
            if ((f.name.startsWith("crop_src_") || f.name.startsWith("cropped")) && f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cropOption = intent.parcelable<CropOption>(CROP_OPTIONS) ?: CropOption.New(1, 1)
        savedInstanceState?.parcelable<Uri>(STATE_SOURCE_URI)?.let {
            sourceImageUri = it
        }
        savedInstanceState?.getString(STATE_TEMP_SRC_PATH)?.let { path ->
            tempSourceFile = File(path).takeIf { it.exists() }
        }
        enableEdgeToEdge()
        setupRootView()
        setContentView(root)
        setupCropView(cropOption)
        onBackPressedDispatcher.addCallback {
            tempSourceFile?.delete()
            setResult(RESULT_CANCELED)
            finish()
        }
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::sourceImageUri.isInitialized) {
            outState.putParcelable(STATE_SOURCE_URI, sourceImageUri)
        }
        tempSourceFile?.let {
            outState.putString(STATE_TEMP_SRC_PATH, it.absolutePath)
        }
    }

    private fun setupRootView() {
        toolbar = view(::Toolbar) {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
            navigationIcon = DrawerArrowDrawable(context).apply { progress = 1f }
            setupToolbarMenu(menu)
        }
        cropView = CropImageView(this)
        root = constraintLayout {
            add(toolbar, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(cropView, lParams(matchParent) {
                below(toolbar)
                centerHorizontally()
                bottomOfParent()
            })
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
                bottomMargin = navBars.bottom
            }
            toolbar.topPadding = statusBars.top
            windowInsets
        }
    }

    private fun setupToolbarMenu(menu: Menu) {
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.rotate, R.drawable.ic_baseline_rotate_right_24, iconTint, true) {
            cropView.rotateImage(90)
        }
        menu.subMenu(R.string.flip, R.drawable.ic_baseline_flip_24, iconTint, true) {
            item(R.string.flip_vertically) {
                cropView.flipImageVertically()
            }
            item(R.string.flip_horizontally) {
                cropView.flipImageHorizontally()
            }
        }
        menu.item(R.string.crop, R.drawable.ic_baseline_check_24, iconTint, true) {
            onCropImage()
        }
    }

    private fun setupCropView(option: CropOption) {
        cropView.setImageCropOptions(
            CropImageOptions(
                // CropImageView
                snapRadius = 0f,
                guidelines = CropImageView.Guidelines.ON_TOUCH,
                showProgressBar = true,
                progressBarColor = styledColor(android.R.attr.colorAccent),
                // CropOverlayView
                borderLineThickness = dp(1f),
                borderCornerOffset = 0f,
            )
        )
        cropView.setAspectRatio(option.width, option.height)
        when (option) {
            is CropOption.New -> {
                if (::sourceImageUri.isInitialized) {
                    cropView.setImageUriAsync(sourceImageUri)
                } else {
                    launcher.launch("image/*")
                }
            }
            is CropOption.Edit -> {
                sourceImageUri = option.sourceUri
                // TODO: set cropRect and rotatedDegrees at the same time may not work as expected
                // maybe we need a better "CropView"
                cropView.setOnSetImageUriCompleteListener { view, _, _ ->
                    view.cropRect = option.initialRect
                    view.rotatedDegrees = option.initialRotation
                    cropView.setOnSetImageUriCompleteListener(null)
                }
                cropView.setImageUriAsync(option.sourceUri)
            }
        }
    }

    private fun onCropImage() {
        if (isCropping || !::sourceImageUri.isInitialized) return
        val rect = cropView.cropRect ?: return
        val rotation = cropView.rotatedDegrees
        isCropping = true
        lifecycleScope.withLoadingDialog(this) {
            val tempOutFile = File.createTempFile("cropped", ".png", cacheDir)
            try {
                val bitmap = cropView.getCroppedImage(
                    reqWidth = cropOption.width,
                    reqHeight = cropOption.height,
                    options = CropImageView.RequestSizeOptions.RESIZE_INSIDE,
                ) ?: throw IllegalStateException("Failed to crop image")
                withContext(Dispatchers.IO) {
                    tempOutFile.outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                val success = CropResult.Success(
                    rect = rect,
                    rotation = rotation,
                    file = tempOutFile,
                    srcUri = sourceImageUri
                )
                setResult(RESULT_OK, Intent().putExtra(CROP_RESULT, success))
            } catch (e: Exception) {
                tempOutFile.delete()
                tempSourceFile?.delete()
                Timber.e("Exception when cropping image: ${e.stackTraceToString()}")
                toast(e)
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }
}
