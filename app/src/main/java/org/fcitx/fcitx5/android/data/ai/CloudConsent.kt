/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * One-time notices before typed text leaves the phone for a service the user didn't set up with
 * their own key: Index-Translate (the default translation engine, bilibili's free API) and AI 联想
 * (sends the text before the cursor while typing). Nothing is sent until the notice is accepted.
 */
package org.fcitx.fcitx5.android.data.ai

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.CloudConsentActivity

object CloudConsent {

    enum class Kind(val key: String) { IndexTranslate("index_translate"), AiPredict("ai_predict") }

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("cloud_consent", Context.MODE_PRIVATE)

    /** a provider whose address is this phone (e.g. Pocket LLM on 127.0.0.1) */
    fun isOnDevice(ctx: Context, p: AiProvider): Boolean {
        val host = runCatching { android.net.Uri.parse(AiConfig.baseUrl(ctx, p)).host }.getOrNull()
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    fun predictProvider(ctx: Context) = AiConfig.predictProvider(ctx) ?: AiConfig.provider(ctx)

    /** true when [kind] may send text now */
    fun granted(ctx: Context, kind: Kind): Boolean {
        val sp = sp(ctx)
        if (!sp.getBoolean("migrated", false)) {
            // whoever already had AI 联想 switched on before this notice existed chose it knowingly
            val editor = sp.edit().putBoolean("migrated", true)
            if (AppPrefs.getInstance().keyboard.aiPredictAuto.getValue()) editor.putBoolean(Kind.AiPredict.key, true)
            editor.apply()
        }
        if (kind == Kind.AiPredict && isOnDevice(ctx, predictProvider(ctx))) return true
        return sp.getBoolean(kind.key, false)
    }

    fun set(ctx: Context, kind: Kind, granted: Boolean) = sp(ctx).edit().putBoolean(kind.key, granted).apply()

    /** shows the notice (from the keyboard too: it is its own small activity) */
    fun request(ctx: Context, kind: Kind) {
        ctx.startActivity(Intent(ctx, CloudConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(CloudConsentActivity.EXTRA_KIND, kind.name)
        })
    }

    fun title(kind: Kind) = when (kind) {
        Kind.IndexTranslate -> "翻译会把文字发到 B 站"
        Kind.AiPredict -> "AI 联想会把输入的文字发给大模型"
    }

    fun message(ctx: Context, kind: Kind): String = when (kind) {
        Kind.IndexTranslate ->
            "中外互译默认使用 B 站的 Index-Translate：免费、不需要 API Key，但要翻译的文字会发到 " +
                    "B 站的服务器（index-translate.bilibili.com）。B 站没有公布这个接口的隐私政策和服务期限。\n\n" +
                    "只有你点翻译（或长按“文A”）时才发送，打字过程中不会发送。密码框里不翻译。\n\n" +
                    "不想发给 B 站：在 设置 → 大模型助手 → 中外互译 里改用离线翻译，或你自己的大模型。"
        Kind.AiPredict -> {
            val p = predictProvider(ctx)
            "打开后，你打字停顿时，会把光标前最多 ${AiSuggest.MAX_CONTEXT} 个字发给 ${p.label}" +
                    "（${AiConfig.baseUrl(ctx, p)}），由它猜接下来的词。使用云端模型会消耗你的 API 额度。\n\n" +
                    "密码框、无痕模式、验证码框、邮箱和网址框里不会发送。\n\n" +
                    "想让文字不离开手机：在 设置 → 大模型助手 里把“AI 联想使用的模型”换成本地模型。"
        }
    }
}
