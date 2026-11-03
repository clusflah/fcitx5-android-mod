/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 填入验证码后把那条短信标为已读：输入法不是默认短信 App，改不了短信数据库，
 * 所以借通知使用权，替用户点一下默认短信 App 通知上的「标为已读」。
 *
 * Privacy: only the default SMS app's notifications are looked at, only right after a code was
 * typed, and only to find the one containing that code. Nothing is stored, logged or sent.
 */
package org.fcitx.fcitx5.android.data.otp

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService

class OtpNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    companion object {
        @Volatile
        private var instance: OtpNotificationListener? = null

        /** 「标为已读」「Mark as read」等，各家短信 App 的写法 */
        private val MARK_READ = Regex("已读|已讀|mark.*read|read", RegexOption.IGNORE_CASE)

        fun component(ctx: Context) = ComponentName(ctx, OtpNotificationListener::class.java)

        fun hasAccess(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component(ctx) } == true

        /**
         * Press "mark as read" on the default SMS app's notification that contains [code].
         * Returns false if there is no access, no such notification, or it has no such action.
         */
        fun markRead(ctx: Context, code: String): Boolean {
            if (!hasAccess(ctx)) return false
            val listener = instance ?: run {
                // 有权限但被系统断开了（例如更新后）：请求重新连接，这次就算了
                runCatching { requestRebind(component(ctx)) }
                return false
            }
            val sms = Telephony.Sms.getDefaultSmsPackage(ctx) ?: return false
            val active = runCatching { listener.activeNotifications }.getOrNull() ?: return false
            val n = active.filter { it.packageName == sms }
                .sortedByDescending { it.postTime }
                .firstOrNull { code in textOf(it.notification.extras) }
                ?: return false
            val action = n.notification.actions.orEmpty().firstOrNull {
                it.remoteInputs.isNullOrEmpty() && MARK_READ.containsMatchIn(it.title ?: "")
            } ?: return false
            return runCatching { action.actionIntent.send(); true }.getOrDefault(false)
        }

        /** 通知里能看到的文字：标题、正文、展开的正文，和对话式通知里的每条消息 */
        private fun textOf(extras: Bundle): String = buildString {
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT).forEach {
                extras.getCharSequence(it)?.let { s -> append(s).append('\n') }
            }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { append(it).append('\n') }
            val messages = if (Build.VERSION.SDK_INT >= 33)
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            else @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            messages?.forEach { (it as? Bundle)?.getCharSequence("text")?.let { s -> append(s).append('\n') } }
        }
    }
}
