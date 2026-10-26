/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: 快速上手. Opens once after a fresh install (once the keyboard is enabled and selected), and
 * from the settings home page any time: pick 全键盘 / 九宫格, how Chinese input works without and
 * with Rime, and where voice input, AI and the toolbar are.
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ModFreshInstall
import org.fcitx.fcitx5.android.ui.setup.SetupPage
import splitties.dimensions.dp

class ModWelcomeActivity : AppCompatActivity() {

    companion object {
        private const val RELEASES = "https://github.com/clusflah/fcitx5-android-mod/releases"

        /** called when the settings home page shows: the first time after a fresh install */
        fun maybeShow(activity: Activity) {
            if (!ModFreshInstall.isFresh(activity) || SetupPage.hasUndonePage()) return
            if (ModFreshInstall.once(activity, "welcome")) {
                activity.startActivity(Intent(activity, ModWelcomeActivity::class.java))
            }
        }
    }

    private lateinit var box: LinearLayout
    private lateinit var layoutButtons: Pair<Button, Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        section("1. 选键盘")
        text("全键盘打全拼；九宫格用数字键打拼音（需要 Rime，见下一条）。以后可以点工具栏的 ▦ 随时切换。")
        val row = LinearLayout(this)
        val full = Button(this).apply { isAllCaps = false; setOnClickListener { setT9(false) } }
        val t9 = Button(this).apply { isAllCaps = false; setOnClickListener { setT9(true) } }
        row.addView(full, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(t9, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        layoutButtons = full to t9
        refreshLayoutButtons()

        section("2. 打中文")
        val rime = rimeState()
        text(
            when (rime) {
                RimeState.BuiltIn, RimeState.Installed ->
                    "已有 Rime（中州韵）。第一次切到中州韵要“部署”词库，大约几十秒到一两分钟，这期间打不出字，之后就正常了。" +
                            "在键盘上点 🌐 或空格左边的切换键可以在拼音和英文之间切换。"
                RimeState.Missing ->
                    "现在可以直接用内置的拼音打字。想用白霜拼音、双拼、九宫格等 Rime 方案，" +
                            "请安装同一页面发布的 Rime 插件，或者直接装“全家桶”版本（所有插件都在里面）。"
            }
        )
        if (rime == RimeState.Missing) button("打开下载页面") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES)))
        }

        section("3. 工具栏")
        text("点键盘左上角的 › 打开工具栏：剪贴板（长按进常用语）、大模型、翻译、键盘选择、更多。" +
                "长按按钮有更多功能；在 更多 → 编辑工具栏 里增删、排序。右边的 ⌨↓ 收起键盘。")

        section("4. 语音输入")
        text("可以按住空格说话、松开识别，也可以在工具栏放一个 🎤。默认关闭；离线模型在手机上识别，不联网、不收费，但要先下载（一两百 MB）。")
        button("去 语音模型 设置") { startActivity(Intent(this, OfflineVoiceActivity::class.java)) }

        section("5. AI 与翻译")
        text("大模型助手、AI 联想需要你自己的 API Key（或手机上的本地模型）。" +
                "中外互译默认用 B 站免费的 Index-Translate，第一次使用时会说明文字会发到哪里。" +
                "密码框里不会把文字发给任何服务。")
        button("去 大模型助手 设置") { startActivity(Intent(this, AiSettingsActivity::class.java)) }

        box.addView(Button(this).apply {
            text = "开始使用"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        setContentWithToolbar(ScrollView(this).apply { addView(box) }, "快速上手")
    }

    private enum class RimeState { BuiltIn, Installed, Missing }

    private fun rimeState(): RimeState {
        val loaded = runCatching { DataManager.detectPlugins().loaded.map { it.packageName } }.getOrDefault(emptyList())
        return when {
            loaded.any { it == "org.fcitx.fcitx5.android.plugin.rime" } -> RimeState.BuiltIn
            loaded.any { it.startsWith("org.fcitx.fcitx5.android.plugin.rime") } -> RimeState.Installed
            else -> RimeState.Missing
        }
    }

    private fun setT9(on: Boolean) {
        AppPrefs.getInstance().keyboard.t9Keyboard.setValue(on)
        refreshLayoutButtons()
    }

    private fun refreshLayoutButtons() {
        val t9 = AppPrefs.getInstance().keyboard.t9Keyboard.getValue()
        layoutButtons.first.text = if (t9) "全键盘" else "✓ 全键盘"
        layoutButtons.second.text = if (t9) "✓ 九宫格" else "九宫格"
    }

    private fun section(title: String) = box.addView(TextView(this).apply {
        text = title
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    })

    private fun text(s: String) = box.addView(TextView(this).apply {
        text = s
        textSize = 14f
        setLineSpacing(0f, 1.15f)
    })

    private fun button(label: String, onClick: () -> Unit) = box.addView(
        Button(this, null, android.R.attr.buttonStyleSmall).apply {
            text = label; isAllCaps = false
            setOnClickListener { onClick() }
        },
        LinearLayout.LayoutParams(-2, -2)
    )
}
