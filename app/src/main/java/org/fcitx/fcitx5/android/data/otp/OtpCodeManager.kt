/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 短信验证码: watch the SMS inbox and extract one-time codes, so the keyboard can offer them.
 *
 * Privacy: codes stay in memory on this device. Nothing here is logged, stored or sent anywhere;
 * the keyboard also keeps AI 联想 off in code fields and after a code has been typed.
 */
package org.fcitx.fcitx5.android.data.otp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.inputmethod.EditorInfo

data class OtpCode(val code: String, val source: String, val receivedAt: Long)

object OtpCodeManager {

    /** Codes older than this are not offered. */
    const val MAX_AGE_MS = 5 * 60 * 1000L

    /** Auto-fill only uses codes this fresh. */
    const val AUTO_FILL_MAX_AGE_MS = 60 * 1000L

    private val KEYWORD = Regex(
        "验证码|校验码|确认码|动态码|动态密码|随机码|认证码|安全码|登录码|激活码|授权码|" +
                "verification code|security code|one[- ]time|passcode|\\bcode\\b|\\bOTP\\b",
        RegexOption.IGNORE_CASE
    )

    /** 4–8 digits, or 4–8 letters+digits mixed (e.g. "A3B5C9"); not part of a longer token or a decimal */
    private val CANDIDATE = Regex(
        "(?<![0-9A-Za-z.])((?=[A-Za-z]*[0-9])(?=[0-9]*[A-Za-z])[A-Za-z0-9]{4,8}|[0-9]{4,8})(?![0-9A-Za-z]|\\.[0-9])"
    )

    /** numbers that are amounts, durations, card / phone tails, dates… rather than codes */
    private val NOT_A_CODE_AFTER = Regex("^\\s*(元|块|分钟|分|秒|小时|天|年|月|日|%|次|笔|位)")
    private val NOT_A_CODE_BEFORE = Regex("(尾号|末四位|卡号|账号|帐号|手机号|¥|￥|\\$|RMB|金额)\\s*[:：]?\\s*$", RegexOption.IGNORE_CASE)

    private val SIGNATURE = Regex("[【\\[]([^】\\]]{1,24})[】\\]]")

    /** The one-time code in an SMS body, or null if it doesn't look like a verification message. */
    fun extract(body: String): String? {
        val keywords = KEYWORD.findAll(body).map { it.range }.toList()
        if (keywords.isEmpty()) return null
        return CANDIDATE.findAll(body)
            .filterNot { m ->
                NOT_A_CODE_AFTER.containsMatchIn(body.substring(m.range.last + 1)) ||
                        NOT_A_CODE_BEFORE.containsMatchIn(body.substring(0, m.range.first))
            }
            // closest to a keyword wins, e.g. "验证码 123456，有效期 5 分钟"
            .minByOrNull { m ->
                keywords.minOf { k ->
                    when {
                        m.range.first > k.last -> m.range.first - k.last
                        k.first > m.range.last -> k.first - m.range.last
                        else -> 0
                    }
                }
            }
            ?.value
    }

    /** "【招商银行】…" at the start or end of the message, else the sender number */
    fun sourceOf(body: String, address: String?): String =
        SIGNATURE.find(body)?.groupValues?.get(1)?.trim()
            ?: address?.takeIf { it.isNotBlank() }
            ?: "短信"

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Newest verification code received within [MAX_AGE_MS], or null. */
    fun latest(ctx: Context): OtpCode? {
        if (!hasPermission(ctx)) return null
        val since = System.currentTimeMillis() - MAX_AGE_MS
        return runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                var found: OtpCode? = null
                var n = 0
                while (found == null && n++ < 5 && c.moveToNext()) {
                    val body = c.getString(1) ?: continue
                    val code = extract(body) ?: continue
                    found = OtpCode(code, sourceOf(body, c.getString(0)), c.getLong(2))
                }
                found
            }
        }.getOrNull()
    }

    // ---------- watching the inbox ----------

    /** Set by the keyboard toolbar; called on the main thread when the newest code may have changed. */
    var listener: (() -> Unit)? = null

    private var observer: ContentObserver? = null

    /** Start watching the SMS database (once per process); no-op without permission. */
    fun ensureObserving(ctx: Context) {
        if (observer != null || !hasPermission(ctx)) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                listener?.invoke()
            }
        }
        runCatching {
            ctx.applicationContext.contentResolver
                .registerContentObserver(Telephony.Sms.CONTENT_URI, true, o)
            observer = o
        }
    }

    fun stopObserving(ctx: Context) {
        observer?.let { runCatching { ctx.applicationContext.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    // ---------- codes already used or dismissed ----------

    private val handled = mutableSetOf<Pair<String, Long>>()

    fun isHandled(c: OtpCode) = (c.code to c.receivedAt) in handled

    fun markHandled(c: OtpCode) {
        if (handled.size > 50) handled.clear()
        handled += c.code to c.receivedAt
    }

    // ---------- code fields ----------

    private val FIELD_HINT = Regex(
        "验证码|校验码|动态码|动态密码|短信码|otp|one[-_ ]?time|verif|captcha|sms[-_ ]?code|yzm|smscode|authcode",
        RegexOption.IGNORE_CASE
    )

    /** Whether the focused field is probably where a verification code goes. */
    fun looksLikeCodeField(info: EditorInfo): Boolean =
        listOfNotNull(info.hintText?.toString(), info.label?.toString(), info.fieldName)
            .any { FIELD_HINT.containsMatchIn(it) }
}
