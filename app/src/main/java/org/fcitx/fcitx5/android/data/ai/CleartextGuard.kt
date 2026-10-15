/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * All-in-one build: the app allows plain HTTP (network_security_config.xml) so the built-in
 * clipboard sync can reach a SyncClipboard server on the LAN. Requests that carry an API key
 * (AI services, Azure speech) still go over plain HTTP only to this phone itself.
 */
package org.fcitx.fcitx5.android.data.ai

import java.net.URL

object CleartextGuard {

    private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")

    const val MESSAGE = "为保护密钥，明文 http 只能连本机（127.0.0.1 / localhost），请改用 https 地址"

    /** false for an http:// URL to another host; anything unparsable is left to fail later */
    fun allowed(url: String): Boolean {
        val u = runCatching { URL(url) }.getOrNull() ?: return true
        return !u.protocol.equals("http", ignoreCase = true) || u.host.lowercase() in LOCAL_HOSTS
    }
}
