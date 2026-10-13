/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 离线语音模型: download / import / delete the SenseVoice model used by offline voice input.
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.voice.OfflineVoice
import org.fcitx.fcitx5.android.input.voice.VoiceEngine
import splitties.dimensions.dp

class OfflineVoiceActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var downloadBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var importBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var useBtn: Button

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        status.text = "正在导入…"
        importBtn.isEnabled = false
        lifecycleScope.launch {
            val msg = runCatching { OfflineVoice.import(this@OfflineVoiceActivity, uris) }
                .getOrElse { "导入失败：${it.message}" }
            Toast.makeText(this@OfflineVoiceActivity, msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, pad)
        }
        fun text(s: String, size: Float = 14f) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, dp(6), 0, dp(6))
        }
        box.addView(text(
            "离线语音识别使用阿里开源的 SenseVoice 模型（通过 sherpa-onnx 运行），支持普通话、粤语、英语、日语、韩语，" +
                    "自动加标点。录音只在手机上识别，不联网、不收费。\n\n" +
                    "模型约 ${OfflineVoice.DOWNLOAD_SIZE_MB} MB，需要下载一次。识别时会占用约 300 MB 内存，" +
                    "闲置 5 分钟后自动释放。"
        ))
        status = text("", 16f).apply { setPadding(0, dp(12), 0, dp(6)) }
        box.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        progressText = text("", 13f)
        box.addView(progress)
        box.addView(progressText)

        box.addView(text("下载源", 13f).apply { setPadding(0, dp(12), 0, 0) })
        sourceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                OfflineVoice.SOURCES.map { it.first })
        }
        box.addView(sourceSpinner)
        downloadBtn = Button(this).apply {
            text = "下载模型（约 ${OfflineVoice.DOWNLOAD_SIZE_MB} MB）"
            setOnClickListener {
                OfflineVoice.startDownload(this@OfflineVoiceActivity, OfflineVoice.SOURCES[sourceSpinner.selectedItemPosition].second)
                refresh()
            }
        }
        cancelBtn = Button(this).apply {
            text = "取消下载"
            setOnClickListener { OfflineVoice.cancelDownload(this@OfflineVoiceActivity); refresh() }
        }
        importBtn = Button(this).apply {
            text = "从文件导入（model.int8.onnx + tokens.txt）"
            setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        }
        useBtn = Button(this).apply {
            text = "设为语音识别引擎"
            setOnClickListener {
                val kb = AppPrefs.getInstance().keyboard
                kb.azureVoiceEnabled.setValue(true)
                kb.voiceEngine.setValue(VoiceEngine.SenseVoice)
                Toast.makeText(this@OfflineVoiceActivity, "已启用：按住空格说话，使用离线识别", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        deleteBtn = Button(this).apply {
            text = "删除模型"
            setOnClickListener {
                AlertDialog.Builder(this@OfflineVoiceActivity)
                    .setMessage("删除离线语音模型（${OfflineVoice.sizeMb(this@OfflineVoiceActivity)} MB）？")
                    .setPositiveButton(android.R.string.ok) { _, _ -> OfflineVoice.delete(this@OfflineVoiceActivity); refresh() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        listOf(downloadBtn, cancelBtn, useBtn, importBtn, deleteBtn).forEach { box.addView(it) }
        box.addView(text(
            "下载不了？在电脑上从 huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09 " +
                    "下载 model.int8.onnx 和 tokens.txt，传到手机后用“从文件导入”。\n\n" +
                    "下载由系统下载器完成，离开这个页面也会继续，通知栏可以看到进度。", 12f
        ))
        setContentWithToolbar(ScrollView(this).apply { addView(box) }, "离线语音模型")

        // poll the download while the page is visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(700)
                }
            }
        }
    }

    private fun refresh() {
        val state = OfflineVoice.downloadState(this)
        val ready = OfflineVoice.isReady(this)
        val engine = AppPrefs.getInstance().keyboard.voiceEngine.getValue()
        val enabled = AppPrefs.getInstance().keyboard.azureVoiceEnabled.getValue()
        val running = state is OfflineVoice.DownloadState.Running
        status.text = when {
            running -> "⬇️ 正在下载…"
            ready && enabled && engine == VoiceEngine.SenseVoice -> "✅ 已安装并正在使用（${OfflineVoice.sizeMb(this)} MB）"
            ready -> "✅ 已安装（${OfflineVoice.sizeMb(this)} MB），还没有设为语音识别引擎"
            else -> "未安装"
        }
        if (state is OfflineVoice.DownloadState.Failed) {
            Toast.makeText(this, state.reason, Toast.LENGTH_LONG).show()
        }
        if (state is OfflineVoice.DownloadState.Done) {
            Toast.makeText(this, "下载完成", Toast.LENGTH_SHORT).show()
        }
        progress.visibility = if (running) View.VISIBLE else View.GONE
        progressText.visibility = progress.visibility
        if (state is OfflineVoice.DownloadState.Running && state.totalBytes > 0) {
            progress.progress = (state.doneBytes * 1000 / state.totalBytes).toInt()
            progressText.text = "${state.doneBytes / 1_048_576} / ${state.totalBytes / 1_048_576} MB"
        }
        sourceSpinner.isEnabled = !running
        downloadBtn.visibility = if (running) View.GONE else View.VISIBLE
        downloadBtn.text = if (ready) "重新下载" else "下载模型（约 ${OfflineVoice.DOWNLOAD_SIZE_MB} MB）"
        cancelBtn.visibility = if (running) View.VISIBLE else View.GONE
        importBtn.isEnabled = !running
        useBtn.visibility = if (ready && !(enabled && engine == VoiceEngine.SenseVoice)) View.VISIBLE else View.GONE
        deleteBtn.visibility = if (ready && !running) View.VISIBLE else View.GONE
    }
}
