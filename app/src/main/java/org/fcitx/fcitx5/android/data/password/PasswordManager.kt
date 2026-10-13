/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Bitwarden integration through Android's own mechanisms only: the autofill framework (Bitwarden fills
 * the app directly and hands the keyboard inline suggestion chips to display) and launching the app.
 * The keyboard never reads the vault, usernames or passwords.
 */
package org.fcitx.fcitx5.android.data.password

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.autofill.AutofillManager

object PasswordManager {

    /** Bitwarden (Play / F-Droid / GitHub builds share this id; also used with Vaultwarden) */
    const val BITWARDEN = "com.x8bit.bitwarden"

    fun isInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(BITWARDEN, 0) }.isSuccess

    /** Open Bitwarden; false if it isn't installed. */
    fun launch(ctx: Context): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(BITWARDEN) ?: return false
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Package of the system autofill service, or null if none / unknown. */
    fun autofillServicePackage(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            ctx.getSystemService(AutofillManager::class.java)?.autofillServiceComponentName?.packageName
        }.getOrNull()
    }
}
