/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: defaults and one-time hints for people who just installed the app. Updating users keep
 * everything as it was.
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences

object ModFreshInstall {

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("mod_ux", Context.MODE_PRIVATE)

    /** installed and never updated since */
    fun isFresh(ctx: Context): Boolean = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)

    /**
     * A lighter toolbar for new installs: 撤销 / 重做 are rarely used and AI 联想 has a tile in the
     * 更多 panel, so the toolbar starts with 文本编辑, 剪贴板, 大模型, 翻译, 键盘选择 and 更多.
     */
    fun applyDefaults(ctx: Context) {
        val sp = sp(ctx)
        if (sp.getBoolean("defaults_applied", false)) return
        sp.edit().putBoolean("defaults_applied", true).apply()
        if (!isFresh(ctx)) return
        val kb = AppPrefs.getInstance().keyboard
        kb.showUndoButton.setValue(false)
        kb.showRedoButton.setValue(false)
        kb.showAiPredictButton.setValue(false)
    }

    /** true the first time it is asked for [key] */
    fun once(ctx: Context, key: String): Boolean {
        val sp = sp(ctx)
        if (sp.getBoolean("hint_$key", false)) return false
        sp.edit().putBoolean("hint_$key", true).apply()
        return true
    }
}
