/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: search all settings by name. The app's own preference pages are indexed from their
 * definitions (AppPrefs, ThemePrefs); the pages of their own (大模型, 语音模型, …) by name and keywords.
 * A result opens its page and scrolls to the item.
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import splitties.dimensions.dp

class SettingsSearchActivity : AppCompatActivity() {

    /** one searchable item: opens [route] (scrolling to [key]) or the activity [activity] */
    private class Entry(
        val title: String,
        val page: String,
        val keywords: String = "",
        val route: SettingsRoute? = null,
        val key: String? = null,
        val activity: Class<*>? = null
    ) {
        val haystack = "$title $keywords".lowercase()
    }

    private val entries by lazy { buildIndex() }
    private var shown = listOf<Entry>()
    private lateinit var adapter: BaseAdapter

    private fun pageEntries(provider: ManagedPreferenceProvider, page: Int, route: SettingsRoute?, activity: Class<*>?) =
        provider.managedPreferencesUi.mapNotNull { ui ->
            val pref = runCatching { ui.createUi(this) }.getOrNull() ?: return@mapNotNull null
            val title = pref.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(title, getString(page), pref.summary?.toString().orEmpty(), route, ui.key, activity)
        }

    private fun buildIndex(): List<Entry> {
        val prefs = AppPrefs.getInstance()
        val ai = getString(R.string.ai_assist)
        val voice = getString(R.string.offline_voice)
        return listOf(
            Entry(getString(R.string.input_methods), getString(R.string.input_methods),
                "拼音 中州韵 rime 双拼 五笔 英文 添加输入法", route = SettingsRoute.InputMethodList),
            Entry(getString(R.string.global_options), getString(R.string.global_options),
                "快捷键 切换输入法 热键", route = SettingsRoute.GlobalConfig),
            Entry(getString(R.string.addons), getString(R.string.addons),
                "标点 快速输入 云拼音 简繁 拼写 附加组件", route = SettingsRoute.AddonList),
            Entry(getString(R.string.plugins), getString(R.string.plugins),
                "插件 剪贴板同步 rime anthy", route = SettingsRoute.Plugin),
            Entry(ai, ai, "大模型 api key gemini deepseek kimi 本地模型 prompt 提示词 润色 画图 图片 翻译 中外互译 index-translate 离线翻译 ai 联想 数据去向",
                activity = AiSettingsActivity::class.java),
            Entry(voice, voice, "语音 话筒 麦克风 按住空格 离线 sensevoice whisper azure 豆包 识别 纠错 朗读",
                activity = OfflineVoiceActivity::class.java),
            Entry(getString(R.string.common_phrase), getString(R.string.common_phrase), "常用语 短语 地址 邮箱 分组",
                activity = CommonPhraseActivity::class.java),
            Entry(getString(R.string.rime_grammar), getString(R.string.rime_grammar), "万象 语法模型 整句 简繁 rime",
                activity = RimeGrammarActivity::class.java),
            Entry(getString(R.string.setup_check), getString(R.string.setup_check), "换手机 备份 恢复 检查 权限",
                activity = SetupCheckActivity::class.java),
            Entry(getString(R.string.mod_welcome), getString(R.string.mod_welcome), "入门 上手 介绍 九宫格 全键盘",
                activity = ModWelcomeActivity::class.java),
            Entry(getString(R.string.toolbar_buttons), getString(R.string.virtual_keyboard),
                "工具栏 按钮 撤销 重做 剪贴板 翻译 键盘选择 bitwarden 排序 常驻",
                activity = ToolbarButtonsActivity::class.java),
        ) +
                pageEntries(prefs.keyboard, R.string.virtual_keyboard, SettingsRoute.VirtualKeyboard, null) +
                pageEntries(prefs.voice, R.string.offline_voice, null, OfflineVoiceActivity::class.java) +
                pageEntries(prefs.candidates, R.string.candidates_window, SettingsRoute.CandidatesWindow, null) +
                pageEntries(prefs.clipboard, R.string.clipboard, SettingsRoute.Clipboard, null) +
                pageEntries(prefs.symbols, R.string.emoji_and_symbols, SettingsRoute.Symbol, null) +
                pageEntries(prefs.advanced, R.string.advanced, SettingsRoute.Advanced, null) +
                pageEntries(ThemeManager.prefs, R.string.theme, SettingsRoute.Theme, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val query = EditText(this).apply {
            hint = getString(R.string.settings_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val e = shown[position]
                v.findViewById<TextView>(android.R.id.text1).text = e.title
                v.findViewById<TextView>(android.R.id.text2).text = e.page
                return v
            }
        }
        val list = ListView(this).apply {
            this.adapter = this@SettingsSearchActivity.adapter
            setOnItemClickListener { _, _, position, _ -> open(shown[position]) }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
            addView(query)
            addView(list, LinearLayout.LayoutParams(-1, -1))
        }
        setContentWithToolbar(box, getString(R.string.settings_search))
        query.requestFocus()
    }

    private fun filter(q: String) {
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        shown = if (words.isEmpty()) emptyList()
        else entries.filter { e -> words.all { it in e.haystack } }
            // names that match come before keyword-only matches
            .sortedBy { e -> if (words.all { it in e.title.lowercase() }) 0 else 1 }
        adapter.notifyDataSetChanged()
    }

    private fun open(e: Entry) {
        when {
            e.activity != null -> startActivity(Intent(this, e.activity))
            e.route != null -> {
                pendingScrollKey = e.key
                startActivity(Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_RUN
                    putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, e.route)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
        }
        finish()
    }

    companion object {
        /** the preference a search result points at; the page scrolls to it once (ManagedPreferenceFragment) */
        var pendingScrollKey: String? = null
    }
}
