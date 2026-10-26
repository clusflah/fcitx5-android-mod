/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 语音模型: the speech recognition engine on one page, in two groups that fold open with a tap:
 * 本地模型 (the offline models: pick, download / import / delete) and 在线模型 (Azure and the other
 * cloud engines: pick, settings dialog). The one in use is marked ●. Below, Azure's read-aloud
 * settings.
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import org.fcitx.fcitx5.android.input.voice.CloudSpeech
import org.fcitx.fcitx5.android.input.voice.OfflineVoice
import org.fcitx.fcitx5.android.input.voice.VoiceEngine
import splitties.dimensions.dp

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

    private val voice = AppPrefs.getInstance().voice

    private lateinit var offlineHeader: Preference
    private lateinit var onlineHeader: Preference
    private val modelRows = mutableMapOf<String, Preference>()
    private val engineRows = mutableMapOf<VoiceEngine, Preference>()

    private var offlineOpen = true
    private var onlineOpen = true

    /** the model the file picker is importing into */
    private var importing: OfflineVoice.Model? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val m = importing ?: return@registerForActivityResult
        if (uris.isEmpty()) return@registerForActivityResult
        modelRows[m.id]?.summary = "正在导入…"
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            val msg = runCatching { OfflineVoice.import(ctx, m, uris) }.getOrElse { "导入失败：${it.message}" }
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

    private fun category(screen: PreferenceScreen, title: String?) =
        PreferenceCategory(screen.context).apply { this.title = title; isIconSpaceReserved = false }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        // the engine is picked from the rows below, Azure's recognition settings in its dialog;
        // its read-aloud settings get their own group at the end
        val all = (0 until screen.preferenceCount).map { screen.getPreference(it) }
        val stt = setOf("voice_engine", "azure_speech_key", "azure_speech_region", "azure_stt_language", "azure_stt_endpoint")
        all.filter { it.key in stt }.forEach { screen.removePreference(it) }
        val tts = all.filter { it.key?.startsWith("azure_tts_") == true }
        tts.forEach { screen.removePreference(it) }

        val usingOffline = voice.voiceEngine.getValue() == VoiceEngine.SenseVoice
        offlineOpen = usingOffline
        onlineOpen = !usingOffline

        val offline = category(screen, null)
        screen.addPreference(offline)
        offlineHeader = plain(screen, null, null) { offlineOpen = !offlineOpen; refresh() }
        offline.addPreference(offlineHeader)
        OfflineVoice.MODELS.forEach { m ->
            plain(screen, null, null) { onModelClick(m) }.also {
                modelRows[m.id] = it
                offline.addPreference(it)
            }
        }

        val online = category(screen, null)
        screen.addPreference(online)
        onlineHeader = plain(screen, null, null) { onlineOpen = !onlineOpen; refresh() }
        online.addPreference(onlineHeader)
        CloudSpeech.ENGINES.forEach { e ->
            plain(screen, null, null) { onEngineClick(e) }.also {
                engineRows[e.engine] = it
                online.addPreference(it)
            }
        }

        val read = category(screen, "朗读识别结果（Azure）")
        screen.addPreference(read)
        tts.forEach { read.addPreference(it) }
        read.addPreference(plain(screen, null, "朗读只支持 Azure，用的是“在线模型 → Azure”里填写的密钥和区域，用其他引擎识别时也一样。"))
        refresh()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // follow the system downloads while the page is visible
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(700)
                }
            }
        }
    }

    private fun using(e: VoiceEngine) = voice.voiceEngine.getValue() == e

    private fun use(e: VoiceEngine) {
        voice.voiceEngine.setValue(e)
        refresh()
    }

    // ---------- offline models ----------

    private fun onModelClick(m: OfflineVoice.Model) {
        val ctx = requireContext()
        val state = OfflineVoice.downloadState(ctx, m)
        val ready = OfflineVoice.isReady(ctx, m)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!(using(VoiceEngine.SenseVoice) && OfflineVoice.current.id == m.id)) {
            actions += "使用这个模型" to {
                OfflineVoice.select(m)
                use(VoiceEngine.SenseVoice)
                if (!ready) Toast.makeText(ctx, "${m.name} 还没有下载，下载完成后才能识别", Toast.LENGTH_LONG).show()
            }
        }
        if (state is OfflineVoice.DownloadState.Running) {
            actions += "取消下载" to { OfflineVoice.cancelDownload(ctx, m); refresh() }
        } else {
            actions += (if (ready) "重新下载" else "下载（约 ${m.sizeMb} MB）") to { download(m) }
            actions += "从文件导入" to {
                importing = m
                Toast.makeText(ctx, "选择这些文件：${m.files.keys.joinToString()}", Toast.LENGTH_LONG).show()
                pickFiles.launch(arrayOf("*/*"))
            }
        }
        if (ready && state !is OfflineVoice.DownloadState.Running) {
            actions += "删除（释放 ${OfflineVoice.sizeMb(ctx, m)} MB）" to { confirmDelete(m) }
        }
        AlertDialog.Builder(ctx)
            .setTitle(m.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun download(m: OfflineVoice.Model) {
        val ctx = requireContext()
        // mod: hundreds of MB; say so when the phone is on mobile data
        val metered = runCatching {
            (ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .isActiveNetworkMetered
        }.getOrDefault(false)
        AlertDialog.Builder(ctx)
            .setTitle(
                if (metered) "约 ${m.sizeMb} MB，当前在用移动数据，建议连 Wi-Fi 再下载。选择下载源："
                else "选择下载源（约 ${m.sizeMb} MB）"
            )
            .setItems(OfflineVoice.SOURCES.map { it.first }.toTypedArray()) { _, which ->
                OfflineVoice.startDownload(ctx, m, OfflineVoice.SOURCES[which].second)
                Toast.makeText(ctx, "已开始下载，通知栏可以看到进度", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(m: OfflineVoice.Model) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setMessage("删除离线语音模型 ${m.name}（${OfflineVoice.sizeMb(ctx, m)} MB）？")
            .setPositiveButton(android.R.string.ok) { _, _ -> OfflineVoice.delete(ctx, m); refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- online engines ----------

    private fun onEngineClick(e: CloudSpeech.Engine) {
        val ctx = requireContext()
        val inputs = e.fields.map { f ->
            EditText(ctx).apply {
                setText(f.pref.getValue())
                hint = f.hint
                isSingleLine = true
                inputType = if (f.secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
        }
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = ctx.dp(20)
            setPadding(p, ctx.dp(8), p, 0)
            addView(TextView(ctx).apply { text = e.description; setPadding(0, 0, 0, ctx.dp(8)) })
            e.fields.zip(inputs).forEach { (f, input) ->
                addView(TextView(ctx).apply { text = f.label; setPadding(0, ctx.dp(8), 0, 0) })
                addView(input)
            }
        }
        fun save() = e.fields.zip(inputs).forEach { (f, input) -> f.pref.setValue(input.text.toString().trim()) }
        AlertDialog.Builder(ctx)
            .setTitle(e.name)
            .setView(android.widget.ScrollView(ctx).apply { addView(form) })
            .setPositiveButton("保存并使用") { _, _ -> save(); use(e.engine) }
            .setNeutralButton("仅保存") { _, _ -> save(); refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- state ----------

    private fun refresh() {
        val ctx = context ?: return
        if (!::offlineHeader.isInitialized) return
        val engine = voice.voiceEngine.getValue()
        val current = OfflineVoice.current

        offlineHeader.title = (if (offlineOpen) "▾ " else "▸ ") + "本地模型（离线，免费）"
        offlineHeader.summary = "在手机上识别，不联网、不收费；可选 ${OfflineVoice.MODELS.size} 种模型" +
                if (engine == VoiceEngine.SenseVoice) " · 正在使用 ${current.name}" else ""
        OfflineVoice.MODELS.forEach { m ->
            val row = modelRows[m.id] ?: return@forEach
            row.isVisible = offlineOpen
            val state = OfflineVoice.downloadState(ctx, m)
            when (state) {
                is OfflineVoice.DownloadState.Failed -> Toast.makeText(ctx, state.reason, Toast.LENGTH_LONG).show()
                is OfflineVoice.DownloadState.Done -> Toast.makeText(ctx, "${m.name} 下载完成", Toast.LENGTH_SHORT).show()
                else -> {}
            }
            val inUse = engine == VoiceEngine.SenseVoice && m.id == current.id
            val ready = OfflineVoice.isReady(ctx, m)
            val status = when {
                state is OfflineVoice.DownloadState.Running ->
                    "下载中 ${state.doneBytes / 1_048_576} / ${state.totalBytes / 1_048_576} MB" +
                            if (state.totalBytes > 0) "（${state.doneBytes * 100 / state.totalBytes}%）" else ""
                ready -> "✅ 已下载"
                else -> "${m.sizeMb} MB"
            }
            row.title = (if (inUse) "● " else "○ ") + m.name + "　" + status
            row.summary = m.description
        }

        onlineHeader.title = (if (onlineOpen) "▾ " else "▸ ") + "在线模型（需要密钥）"
        onlineHeader.summary = "录音发到所选服务识别，按服务商收费" +
                (CloudSpeech.engine(engine)?.let { " · 正在使用 ${it.name}" } ?: "")
        CloudSpeech.ENGINES.forEach { e ->
            val row = engineRows[e.engine] ?: return@forEach
            row.isVisible = onlineOpen
            row.title = (if (engine == e.engine) "● " else "○ ") + e.name + "　" + (if (e.configured) "已配置" else "未配置")
            row.summary = e.description
        }
    }
}
