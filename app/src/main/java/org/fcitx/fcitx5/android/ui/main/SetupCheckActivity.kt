/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * "新手机检查": after restoring a backup on a new phone, walk through everything
 * the backup can't carry over (IME enabled, plugins, permissions, API config, local model, Rime deploy).
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.ai.AiConfig
import org.fcitx.fcitx5.android.data.ai.CleartextGuard
import org.fcitx.fcitx5.android.data.ai.AiProvider
import org.fcitx.fcitx5.android.data.otp.OtpCodeManager
import org.fcitx.fcitx5.android.data.password.PasswordManager
import org.fcitx.fcitx5.android.input.voice.OfflineVoice
import org.fcitx.fcitx5.android.input.voice.VoiceEngine
import android.os.Build
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import splitties.dimensions.dp
import java.net.HttpURLConnection
import java.net.URL

class SetupCheckActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "setup_check"
        private const val KEY_PENDING = "show_after_import"

        /** Called right after a backup is imported; the check opens on the next app launch. */
        fun markPending(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PENDING, true).commit()
        }

        /** Returns true (and clears the flag) if the check should be shown now. */
        fun consumePending(ctx: Context): Boolean {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pending = sp.getBoolean(KEY_PENDING, false)
            if (pending) sp.edit().putBoolean(KEY_PENDING, false).apply()
            return pending
        }
    }

    private enum class Status(val mark: String) { Ok("✅"), Warn("⚠️"), Bad("❌"), Info("ℹ️") }

    private lateinit var list: LinearLayout
    private var generation = 0 // drop async results from an older refresh

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        setContentWithToolbar(ScrollView(this).apply { addView(list) }, "新手机检查")
    }

    override fun onResume() {
        super.onResume()
        refresh() // re-check after returning from system settings
    }

    private fun refresh() {
        generation++
        list.removeAllViews()
        list.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
            text = "备份已恢复设置、常用语、大模型配置和 Rime 文件夹。下面是备份带不走、需要在这台手机上处理的项目，" +
                    "照着按钮逐项完成即可。返回本页会自动刷新。"
        })
        checkIme()
        checkPlugins()
        checkVoice()
        checkOtp()
        checkPasswordManager()
        checkAi()
        checkLocalModel()
        checkRime()
    }

    private fun row(status: Status, title: String, detail: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        box.addView(TextView(this).apply {
            text = "${status.mark}  $title"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = detail
            textSize = 13f
            setPadding(dp(28), dp(2), 0, 0)
        })
        if (action != null && onAction != null) {
            box.addView(Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = action
                isAllCaps = false
                setOnClickListener { onAction() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(24)
                gravity = Gravity.START
            })
        }
        list.addView(box)
    }

    // ---------- 1. 输入法 ----------
    private fun checkIme() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        if (!enabled) {
            row(Status.Bad, "启用输入法", "系统还没有启用小企鹅输入法。", "去系统设置启用") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            return
        }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        if (current.startsWith("$packageName/")) {
            row(Status.Ok, "输入法", "已启用并设为默认输入法。")
        } else {
            row(Status.Warn, "设为默认输入法", "已启用，但当前默认的不是小企鹅。", "切换输入法") {
                imm.showInputMethodPicker()
            }
        }
    }

    // ---------- 2. 插件 ----------
    private fun installedPlugins(): Set<String> = runCatching {
        packageManager.queryIntentActivities(Intent(DataManager.PLUGIN_INTENT), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    private fun checkPlugins() {
        // plugins carry the same suffix as the main app (.mod / .debug)
        val suffix = BuildConfig.APPLICATION_ID.removePrefix("org.fcitx.fcitx5.android")
        val installed = installedPlugins()
        fun has(name: String) = "org.fcitx.fcitx5.android.plugin.$name$suffix" in installed
        val rimeFiles = getExternalFilesDir(null)?.resolve("data/rime")?.listFiles()?.isNotEmpty() == true
        when {
            has("rime") -> row(Status.Ok, "Rime 插件", "已安装。")
            rimeFiles -> row(Status.Bad, "Rime 插件",
                "备份里有 Rime 用户文件（白霜拼音等），但还没有安装 Rime 插件。请安装用同一签名编译的 rime 插件 APK（包名带 $suffix）。")
            else -> row(Status.Info, "Rime 插件", "未安装（不使用中州韵可忽略）。")
        }
        if (has("clipboard_sync")) {
            row(Status.Warn, "剪贴板同步插件", "已安装。它的服务器地址、账号密码不在备份里，请打开插件重新填写。",
                "打开插件设置") {
                packageManager.getLaunchIntentForPackage("org.fcitx.fcitx5.android.plugin.clipboard_sync$suffix")
                    ?.let { startActivity(it) }
                    ?: Toast.makeText(this, "找不到插件设置入口", Toast.LENGTH_SHORT).show()
            }
        } else {
            row(Status.Info, "剪贴板同步插件", "未安装（不需要同步剪贴板可忽略）。")
        }
        row(Status.Info, "插件管理", "安装插件后，回到设置主页点击顶部的“检测到插件更改”提示以重新加载。")
    }

    // ---------- 3. 语音 ----------
    private fun checkVoice() {
        val kb = AppPrefs.getInstance().keyboard
        val voice = AppPrefs.getInstance().voice
        if (!kb.azureVoiceEnabled.getValue() && !kb.showVoiceInputButton.getValue()) {
            row(Status.Info, "语音输入", "未开启（设置 → 虚拟键盘 → 按住空格语音输入 / 显示语音输入按钮）。")
            return
        }
        if (voice.voiceEngine.getValue() == VoiceEngine.SenseVoice) {
            if (OfflineVoice.isReady(this)) {
                row(Status.Ok, "离线语音模型", "已安装（${OfflineVoice.sizeMb(this)} MB）。")
            } else {
                row(Status.Bad, "离线语音模型", "语音识别引擎是离线的 SenseVoice，但模型没有下载（备份不包含模型）。", "下载模型") {
                    startActivity(Intent(this, OfflineVoiceActivity::class.java))
                }
            }
        } else if (voice.azureSpeechKey.getValue().isBlank()) {
            row(Status.Bad, "语音输入", "已开启，但没有填写 Azure 语音密钥（设置 → 语音模型）。", "打开语音模型") {
                startActivity(Intent(this, OfflineVoiceActivity::class.java))
            }
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            row(Status.Ok, "麦克风权限", "已授权，按住空格即可说话。")
        } else {
            row(Status.Bad, "麦克风权限", "语音输入需要麦克风权限。", "授权麦克风") {
                startActivity(Intent(this, VoicePermissionActivity::class.java))
            }
        }
    }

    // ---------- 短信验证码 ----------
    private fun checkOtp() {
        if (!AppPrefs.getInstance().keyboard.otpCodeEnabled.getValue()) {
            row(Status.Info, "短信验证码", "未开启（设置 → 虚拟键盘 → 短信验证码）。")
            return
        }
        if (OtpCodeManager.hasPermission(this)) {
            row(Status.Ok, "短信验证码", "已允许读取短信，收到验证码会显示在工具栏上。")
        } else {
            row(Status.Bad, "短信验证码", "已开启，但还没有读取短信的权限。", "允许读取短信") {
                startActivity(Intent(this, SmsPermissionActivity::class.java))
            }
        }
    }

    // ---------- Bitwarden 自动填充 ----------
    private fun checkPasswordManager() {
        if (!PasswordManager.isInstalled(this)) {
            row(Status.Info, "Bitwarden", "未安装（不使用密码管理器可忽略）。")
            return
        }
        val service = PasswordManager.autofillServicePackage(this)
        if (service == PasswordManager.BITWARDEN) {
            row(Status.Ok, "Bitwarden 自动填充", "已设为系统自动填充服务。")
        } else {
            row(Status.Warn, "Bitwarden 自动填充",
                "系统自动填充服务" + (if (service == null) "未设置" else "不是 Bitwarden（当前：$service）") +
                        "。在 Bitwarden → 设置 → 自动填充 中打开“自动填充服务”。", "打开 Bitwarden") {
                PasswordManager.launch(this)
            }
        }
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                row(Status.Info, "键盘内联建议", "需要 Android 11 及以上，这台手机只能用 Bitwarden 的弹出式自动填充。")
            !AppPrefs.getInstance().keyboard.inlineSuggestions.getValue() ->
                row(Status.Warn, "键盘内联建议", "已关闭，Bitwarden 的账号建议不会显示在工具栏上（设置 → 虚拟键盘 → 内联建议）。")
            else ->
                row(Status.Info, "键盘内联建议",
                    "已开启。请同时在 Bitwarden → 设置 → 自动填充 中打开“使用内联自动填充”，账号建议才会出现在工具栏上。")
        }
    }

    // ---------- 4. 大模型 ----------
    private fun checkAi() {
        val main = AiConfig.provider(this)
        val predict = AiConfig.predictProvider(this) ?: main
        val missing = setOf(main, predict).filter { AiConfig.apiKey(this, it).isBlank() }
        if (missing.isEmpty()) {
            row(Status.Ok, "大模型配置",
                "默认模型：${main.label}（${AiConfig.model(this, main)}）；" +
                        "AI 联想：${predict.label}（${AiConfig.predictModel(this, predict)}），API Key 已填写。")
        } else {
            row(Status.Warn, "大模型配置", "${missing.joinToString("、") { it.label }} 还没有填写 API Key（不用大模型功能可忽略）。",
                "打开大模型设置") {
                startActivity(Intent(this, AiSettingsActivity::class.java))
            }
        }
        if (AppPrefs.getInstance().keyboard.aiPredictAuto.getValue()) {
            row(Status.Info, "AI 联想", "自动联想已开启，打字停顿后会请求 ${predict.label}。")
        }
    }

    // ---------- 5. 本地模型 ----------
    private fun checkLocalModel() {
        val usesLocal = AiConfig.provider(this) == AiProvider.Local ||
                AiConfig.predictProvider(this) == AiProvider.Local
        if (!usesLocal) return
        val base = AiConfig.baseUrl(this, AiProvider.Local).trimEnd('/')
        val key = AiConfig.apiKey(this, AiProvider.Local)
        val model = if (AiConfig.provider(this) == AiProvider.Local) AiConfig.model(this, AiProvider.Local)
        else AiConfig.predictModel(this, AiProvider.Local)
        val gen = generation
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!CleartextGuard.allowed(base)) throw java.io.IOException(CleartextGuard.MESSAGE)
                    val conn = URL("$base/models").openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("Authorization", "Bearer $key")
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()
                    conn.disconnect()
                    code to body
                }
            }
            if (gen != generation) return@launch
            result.onSuccess { (code, body) ->
                when {
                    code == 401 || code == 403 ->
                        row(Status.Bad, "本地模型", "能连上 $base，但密码不对。请在大模型设置里填写 Pocket LLM 的 LAN Server 密码。",
                            "打开大模型设置") { startActivity(Intent(this@SetupCheckActivity, AiSettingsActivity::class.java)) }
                    code in 200..299 && body.contains("\"$model\"") ->
                        row(Status.Ok, "本地模型", "已连通，模型 $model 可用。")
                    code in 200..299 ->
                        row(Status.Warn, "本地模型", "已连通，但没有找到模型 $model。服务返回：${body.take(160)}")
                    else -> row(Status.Bad, "本地模型", "连接 $base 返回 $code。")
                }
            }.onFailure {
                row(Status.Bad, "本地模型",
                    "连不上 $base。请安装并打开 Pocket LLM，加载模型后启动 LAN Server，并允许它后台运行、不受电池优化限制。")
            }
        }
    }

    // ---------- 6. Rime ----------
    private fun checkRime() {
        if (getExternalFilesDir(null)?.resolve("data/rime")?.exists() != true) return
        row(Status.Info, "Rime 部署",
            "恢复的 Rime 文件需要重新部署一次。可以点下面的按钮重启输入法引擎（会自动检测变化并部署），" +
                    "或者切换到中州韵后在菜单里点“重新部署”。部署大词库时需要等一两分钟。",
            "重启输入法引擎") {
            lifecycleScope.launch(Dispatchers.Default) { FcitxDaemon.restartFcitx() }
            Toast.makeText(this, "正在重启，请稍候…", Toast.LENGTH_SHORT).show()
        }
    }
}
