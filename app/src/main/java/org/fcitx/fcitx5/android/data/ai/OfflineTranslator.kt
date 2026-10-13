/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Offline translation through the separate "Offline Translator" app (dev.davidv.translator, GPL-3.0),
 * which runs Mozilla's Bergamot models on the phone: https://github.com/DavidVentura/offline-translator
 *
 * It exposes a bound service. Its AIDL files are not copied here; the two calls are written out with
 * Parcel/Binder directly, following the published interface:
 *   ITranslationService.translate(String text, String from, String to, ITranslationCallback cb)
 *   oneway ITranslationCallback.onTranslationResult(String) / onTranslationError(TranslationError)
 */
package org.fcitx.fcitx5.android.data.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object OfflineTranslator {

    const val PACKAGE = "dev.davidv.translator"
    private const val SERVICE_ACTION = "dev.davidv.translator.ITranslationService"
    private const val SERVICE_DESCRIPTOR = "dev.davidv.translator.ITranslationService"
    private const val CALLBACK_DESCRIPTOR = "dev.davidv.translator.ITranslationCallback"

    /** target language names used in settings → Offline Translator language codes */
    private val CODES = mapOf(
        "简体中文" to "zh", "繁体中文" to "zh_hant", "英语" to "en", "日语" to "ja", "韩语" to "ko",
        "法语" to "fr", "德语" to "de", "西班牙语" to "es", "俄语" to "ru", "葡萄牙语" to "pt",
        "意大利语" to "it", "越南语" to "vi", "泰语" to "th", "印尼语" to "id", "阿拉伯语" to "ar"
    )

    fun codeOf(languageName: String): String? = CODES[languageName]

    fun isInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /**
     * Translate [text] into [target] (a name from [AiTranslate.TARGETS] or "简体中文").
     * [fromChinese]: the source is Chinese; otherwise the app detects the language itself.
     */
    suspend fun translate(ctx: Context, text: String, target: String, fromChinese: Boolean): String {
        if (!isInstalled(ctx)) throw AiException("没有安装 Offline Translator（离线翻译 App）")
        val to = codeOf(target) ?: throw AiException("离线翻译不支持目标语言：$target")
        val from = if (fromChinese) "zh" else ""
        val app = ctx.applicationContext
        val connected = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent(SERVICE_ACTION).setPackage(PACKAGE)
        if (!app.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            throw AiException("无法连接 Offline Translator，请先打开一次这个 App")
        }
        try {
            // the first translation loads the models, so allow some time
            return withTimeout(60_000) {
                val service = connected.await()
                val result = CompletableDeferred<String>()
                val callback = Callback(result)
                withContext(Dispatchers.IO) { call(service, text, from, to, callback) }
                result.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AiException("离线翻译超时。请打开 Offline Translator，确认已下载对应的语言包")
        } finally {
            runCatching { app.unbindService(conn) }
        }
    }

    /** ITranslationService.translate — the interface's first (and only) method */
    private fun call(service: IBinder, text: String, from: String, to: String, callback: Binder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeString(text)
            data.writeString(from)
            data.writeString(to)
            data.writeStrongBinder(callback)
            service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** ITranslationCallback: 1 = onTranslationResult(String), 2 = onTranslationError(TranslationError) */
    private class Callback(private val result: CompletableDeferred<String>) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK_DESCRIPTOR)
                return true
            }
            if (code != FIRST_CALL_TRANSACTION && code != FIRST_CALL_TRANSACTION + 1) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(CALLBACK_DESCRIPTOR)
            if (code == FIRST_CALL_TRANSACTION) {
                result.complete(data.readString().orEmpty().trim())
            } else {
                result.completeExceptionally(AiException(readError(data)))
            }
            return true
        }

        /** TranslationError { ErrorType type; String language; String message } (best effort) */
        private fun readError(data: Parcel): String = runCatching {
            if (data.readInt() == 0) return@runCatching null
            data.readInt() // parcelable size header
            val type = data.readByte().toInt()
            val language = data.readString()
            val message = data.readString()
            when (type) {
                0 -> "离线翻译无法识别原文的语言"
                1 -> "Offline Translator 里还没有下载「${language ?: "原文语言"}」的语言包"
                else -> message
            }
        }.getOrNull() ?: "离线翻译失败"
    }
}
