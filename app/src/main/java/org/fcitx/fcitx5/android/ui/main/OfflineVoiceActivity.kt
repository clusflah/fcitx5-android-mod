/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 语音模型: the speech recognition engine, the offline SenseVoice model (download / import / delete)
 * and the Azure settings, on one page.
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.OfflineVoice

class OfflineVoiceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentWithToolbar(
            FrameLayout(this).apply { id = R.id.voice_model_container },
            getString(R.string.offline_voice)
        )
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.voice_model_container, VoiceModelFragment())
                .commit()
        }
    }
}

class VoiceModelFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {

    private lateinit var statusPref: Preference
    private lateinit var downloadPref: Preference
    private lateinit var importPref: Preference
    private lateinit var deletePref: Preference

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        statusPref.summary = "正在导入…"
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            val msg = runCatching { OfflineVoice.import(ctx, uris) }.getOrElse { "导入失败：${it.message}" }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun plain(screen: PreferenceScreen, title: CharSequence?, summary: CharSequence?, onClick: (() -> Unit)? = null) =
        Preference(screen.context).apply {
            this.title = title
            this.summary = summary
            isIconSpaceReserved = false
            isSingleLineTitle = false
            if (onClick == null) isSelectable = false
            else setOnPreferenceClickListener { onClick(); true }
        }

    private fun category(screen: PreferenceScreen, title: String) =
        PreferenceCategory(screen.context).apply { this.title = title; isIconSpaceReserved = false }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        // the category's own items were added in order: engine, then azure_*; group the Azure ones
        val azure = (0 until screen.preferenceCount).map { screen.getPreference(it) }
            .filter { it.key?.startsWith("azure_") == true }
        azure.forEach { screen.removePreference(it) }

        val sense = category(screen, "SenseVoice（离线，免费）")
        screen.addPreference(sense)
        sense.addPreference(plain(
            screen, null,
            "阿里开源的 SenseVoice 模型，通过 sherpa-onnx 在手机上运行：普通话、粤语、英语、日语、韩语，" +
                    "自动加标点。录音只在手机上识别，不联网、不收费。识别时占用约 300 MB 内存，闲置 5 分钟后释放。"
        ))
        statusPref = plain(screen, "模型状态", null)
        downloadPref = plain(screen, "下载模型", null) { onDownloadClick() }
        importPref = plain(screen, "从文件导入", "选择 model.int8.onnx 和 tokens.txt（下载不了时，可以在电脑上下载后传到手机）") {
            pickFiles.launch(arrayOf("*/*"))
        }
        deletePref = plain(screen, "删除模型", null) { onDeleteClick() }
        listOf(statusPref, downloadPref, importPref, deletePref).forEach { sense.addPreference(it) }

        val az = category(screen, "Azure（云端，需要密钥）")
        screen.addPreference(az)
        azure.forEach { az.addPreference(it) }
        az.addPreference(plain(screen, null, "“朗读识别结果”目前只支持 Azure，用离线识别时也会用这里的密钥朗读。"))
        refresh()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // follow the system download while the page is visible
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(700)
                }
            }
        }
    }

    private fun onDownloadClick() {
        val ctx = requireContext()
        if (OfflineVoice.downloadState(ctx) is OfflineVoice.DownloadState.Running) {
            OfflineVoice.cancelDownload(ctx)
            refresh()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("选择下载源（约 ${OfflineVoice.DOWNLOAD_SIZE_MB} MB）")
            .setItems(OfflineVoice.SOURCES.map { it.first }.toTypedArray()) { _, which ->
                OfflineVoice.startDownload(ctx, OfflineVoice.SOURCES[which].second)
                Toast.makeText(ctx, "已开始下载，通知栏可以看到进度", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onDeleteClick() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setMessage("删除离线语音模型（${OfflineVoice.sizeMb(ctx)} MB）？")
            .setPositiveButton(android.R.string.ok) { _, _ -> OfflineVoice.delete(ctx); refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        val ctx = context ?: return
        if (!::statusPref.isInitialized) return
        val state = OfflineVoice.downloadState(ctx)
        val ready = OfflineVoice.isReady(ctx)
        when (state) {
            is OfflineVoice.DownloadState.Failed -> Toast.makeText(ctx, state.reason, Toast.LENGTH_LONG).show()
            is OfflineVoice.DownloadState.Done -> Toast.makeText(ctx, "下载完成", Toast.LENGTH_SHORT).show()
            else -> {}
        }
        val running = state is OfflineVoice.DownloadState.Running
        statusPref.summary = when {
            running -> "正在下载…"
            ready -> "✅ 已安装（${OfflineVoice.sizeMb(ctx)} MB）"
            else -> "未安装"
        }
        if (state is OfflineVoice.DownloadState.Running) {
            downloadPref.title = "取消下载"
            downloadPref.summary = "${state.doneBytes / 1_048_576} / ${state.totalBytes / 1_048_576} MB" +
                    (if (state.totalBytes > 0) "（${state.doneBytes * 100 / state.totalBytes}%）" else "") +
                    "，离开这个页面也会继续下载"
        } else {
            downloadPref.title = if (ready) "重新下载模型" else "下载模型"
            downloadPref.summary = "约 ${OfflineVoice.DOWNLOAD_SIZE_MB} MB，从 Hugging Face 下载（国内可能需要代理）"
        }
        importPref.isEnabled = !running
        deletePref.isVisible = ready && !running
        deletePref.summary = if (ready) "释放 ${OfflineVoice.sizeMb(ctx)} MB 空间" else null
    }
}
