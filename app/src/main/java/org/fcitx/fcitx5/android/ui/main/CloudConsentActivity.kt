/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * The one-time notice of CloudConsent, as a small dialog window: the keyboard can't show dialogs
 * of its own over other apps.
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.fcitx.fcitx5.android.data.ai.CloudConsent
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import splitties.dimensions.dp

class CloudConsentActivity : Activity() {

    companion object {
        const val EXTRA_KIND = "kind"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val styled = AiDialogStyle.enabled
        if (styled) requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        val kind = runCatching { CloudConsent.Kind.valueOf(intent.getStringExtra(EXTRA_KIND)!!) }
            .getOrElse { finish(); return }
        if (!styled) {
            title = CloudConsent.title(kind)
        }
        setFinishOnTouchOutside(false)
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, if (styled) dp(16) else dp(8), pad, if (styled) dp(14) else dp(8))
        }
        val headerView = if (styled) {
            TextView(this).apply {
                text = CloudConsent.title(kind)
                setPadding(0, 0, 0, dp(8))
            }.also { box.addView(it) }
        } else null
        box.addView(TextView(this).apply {
            text = CloudConsent.message(this@CloudConsentActivity, kind)
            textSize = 15f
            setLineSpacing(0f, 1.15f)
        })
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "不用了"
            setOnClickListener { decide(kind, false) }
        })
        buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "同意并继续"
            tag = AiDialogStyle.PRIMARY
            setOnClickListener { decide(kind, true) }
        })
        box.addView(buttons)
        val scroll = ScrollView(this).apply { addView(box) }
        setContentView(scroll)
        if (styled) {
            AiDialogStyle.apply(scroll)
            headerView?.let { AiDialogStyle.title(it) }
            AiDialogStyle.styleWindow(window, isAlert = true)
        }
    }

    private fun decide(kind: CloudConsent.Kind, ok: Boolean) {
        CloudConsent.set(this, kind, ok)
        when (kind) {
            CloudConsent.Kind.AiPredict -> {
                AppPrefs.getInstance().keyboard.aiPredictAuto.setValue(ok)
                if (ok) Toast.makeText(this, "AI 联想已开启，打字停顿后出现", Toast.LENGTH_SHORT).show()
            }
            CloudConsent.Kind.IndexTranslate ->
                if (ok) Toast.makeText(this, "已同意，请再点一次翻译", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        decide(CloudConsent.Kind.valueOf(intent.getStringExtra(EXTRA_KIND)!!), false)
    }
}
