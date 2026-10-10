/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * An input method cannot request runtime permissions itself, so this transparent dialog does it.
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import org.fcitx.fcitx5.android.R

class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish(); return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (ok) R.string.azure_voice_permission_granted else R.string.azure_voice_permission_denied,
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}
