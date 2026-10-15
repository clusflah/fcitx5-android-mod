/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Rime 语法模型: download / import the 万象 grammar model and switch it on for 白霜拼音
 * (full pinyin, nine-key, double pinyin). See RimeGrammar.
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
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.rime.RimeGrammar
import org.fcitx.fcitx5.android.data.rime.RimeGrammar.Model
import java.io.File

class RimeGrammarActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentWithToolbar(
            FrameLayout(this).apply { id = R.id.rime_grammar_container },
            getString(R.string.rime_grammar)
        )
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.rime_grammar_container, RimeGrammarFragment())
                .commit()
        }
    }
}

class RimeGrammarFragment : PreferenceFragmentCompat() {

    private lateinit var statusPref: Preference
    private lateinit var switchPref: Preference
    private lateinit var downloadPref: Preference
    private lateinit var importPref: Preference
    private lateinit var deletePref: Preference

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        statusPref.summary = "正在导入…"
        importPref.isEnabled = false
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            val model = runCatching { RimeGrammar.import(ctx, uri) }.getOrNull()
            if (model == null) {
                Toast.makeText(ctx, "导入失败：请选择完整的 wanxiang-lts-zh-hans.gram（或 zh-hant）", Toast.LENGTH_LONG).show()
            } else {
                turnOn(model)
            }
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        screen.addPreference(plain(
            screen, null,
            "万象 LTS 语法模型（amzxyz/RIME-LMDG）让 Rime 打整句时参考上下文选词，更准。" +
                    "启用后对白霜拼音的全拼、九键（九宫格）和各种双拼都生效。" +
                    "设置写在 Rime 文件夹的 rime_frost*.custom.yaml 里，你自己的其他设置会保留。"
        ))
        statusPref = plain(screen, "状态", null)
        switchPref = plain(screen, "启用", null) { onSwitchClick() }
        val model = PreferenceCategory(screen.context).apply { title = "模型文件"; isIconSpaceReserved = false }
        downloadPref = plain(screen, "下载模型", null) { onDownloadClick() }
        importPref = plain(
            screen, "从文件导入",
            "选择 wanxiang-lts-zh-hans.gram（简体）或 zh-hant（繁体）。下载不了时，可以在电脑上下载后传到手机"
        ) { pickFile.launch(arrayOf("*/*")) }
        deletePref = plain(screen, "删除模型", null) { onDeleteClick() }
        screen.addPreference(statusPref)
        screen.addPreference(switchPref)
        screen.addPreference(model)
        listOf(downloadPref, importPref, deletePref).forEach { model.addPreference(it) }
        screen.addPreference(plain(
            screen, null,
            "注意：模型约 $SIZE_MB MB，启用或停用后会重新部署 Rime，要等一会儿才能打字。" +
                    "如果启用后只能输入英文或打不出字，点“停用”即可恢复。"
        ))
        preferenceScreen = screen
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

    /** whether 白霜拼音 is in the user's Rime folder or built into the app (all-in-one) */
    private fun hasRimeFrost(): Boolean {
        val name = "rime_frost.schema.yaml"
        return File(RimeGrammar.rimeDir(requireContext()), name).exists() ||
                File(DataManager.dataDir, "usr/share/rime-data/$name").exists()
    }

    private fun chooseModel(models: List<Model>, title: String, then: (Model) -> Unit) {
        if (models.size == 1) return then(models[0])
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(models.map { "${it.label}（${it.fileName}）" }.toTypedArray()) { _, which -> then(models[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onSwitchClick() {
        val ctx = requireContext()
        if (RimeGrammar.enabled(ctx) != null) {
            RimeGrammar.disable(ctx)
            redeploy("已停用万象语法模型")
            refresh()
            return
        }
        val installed = RimeGrammar.installed(ctx)
        if (installed.isEmpty()) {
            Toast.makeText(ctx, "请先下载或导入模型", Toast.LENGTH_SHORT).show()
            return
        }
        chooseModel(installed, "使用哪个模型") { turnOn(it) }
    }

    private fun turnOn(model: Model) {
        val ctx = context ?: return
        val failed = RimeGrammar.enable(ctx, model)
        if (failed.isNotEmpty()) {
            Toast.makeText(
                ctx, "这些文件的 patch 格式无法自动修改，请手动添加：${failed.joinToString()}", Toast.LENGTH_LONG
            ).show()
        }
        redeploy("已启用万象语法模型（${model.label}）")
        refresh()
    }

    /** Restarting fcitx makes Rime redeploy, since its custom.yaml files changed. */
    private fun redeploy(message: String) {
        Toast.makeText(requireContext(), "$message，正在重新部署 Rime，请稍等", Toast.LENGTH_LONG).show()
        lifecycleScope.launch(Dispatchers.Default) { FcitxDaemon.restartFcitx() }
    }

    private fun onDownloadClick() {
        val ctx = requireContext()
        if (RimeGrammar.downloadState(ctx) is RimeGrammar.DownloadState.Running) {
            RimeGrammar.cancelDownload(ctx)
            refresh()
            return
        }
        chooseModel(Model.entries, "下载哪个模型（约 $SIZE_MB MB，从 GitHub 下载）") { model ->
            RimeGrammar.startDownload(ctx, model)
            Toast.makeText(ctx, "已开始下载，通知栏可以看到进度", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun onDeleteClick() {
        val ctx = requireContext()
        chooseModel(RimeGrammar.installed(ctx), "删除哪个模型") { model ->
            AlertDialog.Builder(ctx)
                .setMessage("删除 ${model.fileName}（${RimeGrammar.sizeMb(ctx, model)} MB）？" +
                        if (RimeGrammar.enabled(ctx) == model) "\n正在使用它，会同时停用。" else "")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val wasOn = RimeGrammar.enabled(ctx) == model
                    RimeGrammar.delete(ctx, model)
                    if (wasOn) redeploy("已删除并停用万象语法模型")
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun refresh() {
        val ctx = context ?: return
        if (!::statusPref.isInitialized) return
        val state = RimeGrammar.downloadState(ctx)
        when (state) {
            is RimeGrammar.DownloadState.Failed -> Toast.makeText(ctx, state.reason, Toast.LENGTH_LONG).show()
            // a finished download is switched on right away
            is RimeGrammar.DownloadState.Done -> turnOn(state.model)
            else -> {}
        }
        val running = state is RimeGrammar.DownloadState.Running
        val installed = RimeGrammar.installed(ctx)
        val enabled = RimeGrammar.enabled(ctx)
        statusPref.summary = buildString {
            append(
                when {
                    enabled != null && enabled in installed -> "✅ 已启用（${enabled.label}）"
                    enabled != null -> "⚠️ 已启用，但模型文件 ${enabled.fileName} 不在了，请重新下载或停用"
                    installed.isNotEmpty() -> "已有模型（${installed.joinToString("、") { it.label }}），未启用"
                    running -> "正在下载…"
                    else -> "未安装"
                }
            )
            if (!hasRimeFrost()) append("\n没有找到白霜拼音（rime_frost）方案：设置仍会写入，放入白霜并重新部署后生效。")
        }
        switchPref.title = if (enabled != null) "停用" else "启用"
        switchPref.summary = if (enabled != null) "恢复白霜默认的小模型" else "对白霜的全拼、九键、双拼生效"
        switchPref.isEnabled = enabled != null || installed.isNotEmpty()
        if (state is RimeGrammar.DownloadState.Running) {
            downloadPref.title = "取消下载"
            downloadPref.summary = "${state.doneBytes / 1_048_576} / ${state.totalBytes / 1_048_576} MB" +
                    (if (state.totalBytes > 0) "（${state.doneBytes * 100 / state.totalBytes}%）" else "") +
                    "，离开这个页面也会继续下载，回来后自动启用"
        } else {
            downloadPref.title = if (installed.isNotEmpty()) "重新下载模型" else "下载模型"
            downloadPref.summary = "约 $SIZE_MB MB，从 GitHub 下载（国内可能需要代理）"
        }
        importPref.isEnabled = !running
        deletePref.isVisible = installed.isNotEmpty() && !running
        deletePref.summary = installed.joinToString("、") { "${it.fileName}（${RimeGrammar.sizeMb(ctx, it)} MB）" }
    }

    companion object {
        private const val SIZE_MB = RimeGrammar.DOWNLOAD_SIZE_MB
    }
}
