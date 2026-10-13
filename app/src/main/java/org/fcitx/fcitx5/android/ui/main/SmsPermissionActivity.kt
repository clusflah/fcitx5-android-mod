/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * An input method cannot request runtime permissions itself, so this transparent dialog asks for
 * READ_SMS on behalf of the 短信验证码 feature.
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.otp.OtpCodeManager

class SmsPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            finish(); return
        }
        requestPermissions(arrayOf(Manifest.permission.READ_SMS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (ok) OtpCodeManager.ensureObserving(this)
        Toast.makeText(
            this,
            if (ok) R.string.otp_permission_granted else R.string.otp_permission_denied,
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}
