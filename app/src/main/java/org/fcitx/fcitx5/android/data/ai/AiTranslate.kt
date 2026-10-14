/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 中外互译: Chinese → the target language chosen in settings, anything else → Simplified Chinese.
 * Uses Index-Translate (bilibili's free translation model, no API key) or one of the LLM providers.
 * Text is only sent when the user asks for a translation.
 */
package org.fcitx.fcitx5.android.data.ai

import android.content.Context
import android.content.SharedPreferences

/** Who translates: Index-Translate, or one of the assistant's providers. */
sealed class TranslateEngine(val id: String, val label: String) {
    object IndexTranslate : TranslateEngine("index_translate", "Index-Translate（B站免费）")
    /** the separate Offline Translator app; text never leaves the phone */
    object Offline : TranslateEngine("offline_translator", "离线翻译（Offline Translator App）")
    class Provider(val provider: AiProvider) : TranslateEngine(provider.name, provider.label)

    companion object {
        val all: List<TranslateEngine>
            get() = listOf(IndexTranslate, Offline) + AiProvider.entries.map { Provider(it) }
        fun of(id: String?): TranslateEngine = all.find { it.id == id } ?: IndexTranslate
    }
}

object AiTranslate {

    /** https://github.com/bilibili/Index-Translate — free hosted API, OpenAI compatible, no key */
    const val INDEX_BASE_URL = "https://index-translate.bilibili.com/v1"
    const val INDEX_MODEL = "Index-Translate-35B-A3B"

    /** target languages for Chinese text, as named in the prompt */
    val TARGETS = listOf(
        "英语", "日语", "韩语", "法语", "德语", "西班牙语", "俄语", "葡萄牙语",
        "意大利语", "越南语", "泰语", "印尼语", "阿拉伯语", "繁体中文"
    )
    private const val DEFAULT_TARGET = "英语"
    private const val CHINESE = "简体中文"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("ai_assist", Context.MODE_PRIVATE)

    fun engine(ctx: Context): TranslateEngine = TranslateEngine.of(sp(ctx).getString("translate_engine", null))
    fun setEngine(ctx: Context, e: TranslateEngine) = sp(ctx).edit().putString("translate_engine", e.id).apply()

    fun target(ctx: Context): String =
        sp(ctx).getString("translate_target", null)?.takeIf { it in TARGETS } ?: DEFAULT_TARGET
    fun setTarget(ctx: Context, t: String) = sp(ctx).edit().putString("translate_target", t).apply()

    /** Han characters without kana → treat as Chinese. */
    fun looksChinese(text: String): Boolean {
        var han = 0; var letters = 0; var kana = 0
        text.forEach { c ->
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HAN -> { han++; letters++ }
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> { kana++; letters++ }
                else -> if (Character.isLetter(c)) letters++
            }
        }
        if (letters == 0 || kana > 0) return false
        return han * 3 >= letters
    }

    /** the language [text] will be translated into */
    fun targetFor(ctx: Context, text: String): String = if (looksChinese(text)) target(ctx) else CHINESE

    /** "中文 → 日语" / "外文 → 中文" */
    fun directionLabel(ctx: Context, text: String): String =
        if (text.isBlank()) "中文 → ${target(ctx)}，外文 → 中文（目标语言在设置中选择）"
        else if (looksChinese(text)) "中文 → ${target(ctx)}" else "外文 → 中文"

    /** Index-Translate's documented instruction format, which general models follow as well. */
    private fun prompt(target: String, text: String) =
        "请将以下文本翻译为$target，直接输出翻译结果，不要进行任何解释。" +
                "保留原文的格式、人名、数字、网址和代码。\n\n$text"

    /**
     * Translate [text] with [engine] (default: the one chosen in settings). If Index-Translate fails
     * (it is a free service that may be rate limited or change), fall back to the default provider.
     */
    suspend fun translate(ctx: Context, text: String, engine: TranslateEngine = engine(ctx)): String =
        translateDetailed(ctx, text, engine).text

    /** [note]: set when another engine than the chosen one produced the translation */
    class Result(val text: String, val note: String? = null)

    /**
     * Like [translate], but tells which fallback was used. Fallbacks only go from online to offline
     * (Offline Translator, if installed and "在线服务不可用时改用离线模型" is on); for Index-Translate
     * the default provider is tried after that, as before.
     */
    suspend fun translateDetailed(ctx: Context, text: String, engine: TranslateEngine = engine(ctx)): Result {
        val userText = prompt(targetFor(ctx, text), text)
        return when (engine) {
            TranslateEngine.IndexTranslate -> try {
                Result(AiClient.chatKeyless(INDEX_BASE_URL, INDEX_MODEL, userText))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                offlineOr(ctx, text, e) {
                    val fallback = AiConfig.provider(ctx)
                    if (AiConfig.apiKey(ctx, fallback).isBlank()) throw AiException("Index-Translate 不可用：${e.message}")
                    Result(withProvider(ctx, fallback, userText), "Index-Translate 不可用，已改用 ${fallback.label}")
                }
            }
            // no fallback to a cloud service: offline was chosen so the text stays on the phone
            TranslateEngine.Offline -> Result(offline(ctx, text))
            is TranslateEngine.Provider -> try {
                Result(withProvider(ctx, engine.provider, userText))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                offlineOr(ctx, text, e) { throw e }
            }
        }
    }

    private suspend fun offline(ctx: Context, text: String) =
        OfflineTranslator.translate(ctx, text, targetFor(ctx, text), fromChinese = looksChinese(text))

    /** after [error] from an online engine: offline translation if allowed and installed, else [otherwise] */
    private suspend fun offlineOr(ctx: Context, text: String, error: Exception, otherwise: suspend () -> Result): Result {
        val allowed = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().voice.offlineFallback.getValue()
        if (allowed && OfflineTranslator.isInstalled(ctx)) {
            try {
                return Result(offline(ctx, text), "在线翻译不可用，已改用离线翻译")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw AiException("在线翻译不可用（${error.message}），离线翻译也失败了：${e.message}")
            }
        }
        return otherwise()
    }

    // fast = true: translation needs no deep thinking, keep it quick
    private suspend fun withProvider(ctx: Context, p: AiProvider, userText: String) = AiClient.chat(
        p, AiConfig.baseUrl(ctx, p), AiConfig.apiKey(ctx, p), AiConfig.model(ctx, p),
        systemPrompt = "", userText = userText, temperature = 0.2, fast = true
    )
}
