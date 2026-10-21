/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.os.RemoteCallbackList
import org.fcitx.fcitx5.android.common.ipc.IKeyboardVisibilityListener
import timber.log.Timber

/**
 * Tells plugins (e.g. clipboard-sync) when the keyboard window is shown or hidden,
 * so they can do their work only while the user is typing.
 */
object KeyboardVisibility {

    private val listeners = RemoteCallbackList<IKeyboardVisibilityListener>()

    @Volatile
    var shown = false
        private set

    fun register(listener: IKeyboardVisibilityListener) {
        listeners.register(listener)
        if (shown) runCatching { listener.onKeyboardShown() }
    }

    fun unregister(listener: IKeyboardVisibilityListener) {
        listeners.unregister(listener)
    }

    fun onShown() = update(true)

    fun onHidden() = update(false)

    @Synchronized
    private fun update(value: Boolean) {
        if (shown == value) return
        shown = value
        val n = listeners.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    listeners.getBroadcastItem(i).run {
                        if (value) onKeyboardShown() else onKeyboardHidden()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to notify keyboard visibility listener")
                }
            }
        } finally {
            listeners.finishBroadcast()
        }
    }
}
