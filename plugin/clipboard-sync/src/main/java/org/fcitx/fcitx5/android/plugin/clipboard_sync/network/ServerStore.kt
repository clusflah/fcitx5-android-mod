package org.fcitx.fcitx5.android.plugin.clipboard_sync.network

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** One address of the SyncClipboard / WebDAV server; several of them form a failover list. */
@Serializable
data class SyncServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = ""
) {
    val label: String get() = name.ifBlank { url }
}

/**
 * Servers in priority order, stored as JSON under [KEY_SERVERS] in the default SharedPreferences.
 * The server currently in use is [KEY_ACTIVE] (written by MainService, shown by the settings page).
 */
object ServerStore {
    const val KEY_SERVERS = "servers"
    const val KEY_ACTIVE = "active_server"

    // settings of the single-server version, migrated into the list on first load
    private const val OLD_ADDRESS = "server_address"
    private const val OLD_USERNAME = "username"
    private const val OLD_PASSWORD = "password"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(prefs: SharedPreferences): List<SyncServer> {
        prefs.getString(KEY_SERVERS, null)?.let { raw ->
            return runCatching { json.decodeFromString<List<SyncServer>>(raw) }.getOrDefault(emptyList())
        }
        // migrate the old single server
        val address = prefs.getString(OLD_ADDRESS, "").orEmpty()
        val list = if (address.isBlank()) emptyList() else listOf(
            SyncServer(
                name = "服务器 1",
                url = address,
                username = prefs.getString(OLD_USERNAME, "").orEmpty(),
                password = prefs.getString(OLD_PASSWORD, "").orEmpty()
            )
        )
        save(prefs, list)
        prefs.edit().remove(OLD_ADDRESS).remove(OLD_USERNAME).remove(OLD_PASSWORD).apply()
        return list
    }

    fun save(prefs: SharedPreferences, servers: List<SyncServer>) {
        prefs.edit().putString(KEY_SERVERS, json.encodeToString(servers)).apply()
    }

    fun activeId(prefs: SharedPreferences): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActive(prefs: SharedPreferences, id: String?) {
        if (activeId(prefs) != id) prefs.edit().putString(KEY_ACTIVE, id).apply()
    }
}
