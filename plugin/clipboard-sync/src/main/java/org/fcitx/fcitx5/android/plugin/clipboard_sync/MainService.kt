package org.fcitx.fcitx5.android.plugin.clipboard_sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.common.FcitxPluginService
import org.fcitx.fcitx5.android.common.ipc.FcitxRemoteConnection
import org.fcitx.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fcitx.fcitx5.android.common.ipc.bindFcitxRemoteService
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.ServerStore
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncServer
import java.io.IOException

class MainService : FcitxPluginService() {

    companion object {
        private const val TAG = "FcitxClipboardSync"
        private const val KEY_LAST_REMOTE_HASH = "last_remote_hash"
        /** hash of the last text taken from the server (not the text itself), for servers without a hash */
        private const val KEY_LAST_REMOTE_TEXT = "last_remote_text_hash"
        private const val DEFAULT_INTERVAL = 3 // seconds
        /** Retry higher-priority servers this often, e.g. to switch back to the LAN address at home. */
        private const val PREFERRED_RECHECK_MS = 5 * 60 * 1000L
    }

    private lateinit var connection: FcitxRemoteConnection
    private lateinit var prefs: SharedPreferences
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    /** ETag per server id: each address may be a different HTTP endpoint */
    private val etags = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var lastPreferredCheck = 0L
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off, stopping sync")
                    stopPeriodicSync()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen on, restarting sync")
                    startPeriodicSync()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    Log.d(TAG, "Power save mode changed, restarting sync")
                    startPeriodicSync()
                }
            }
        }
    }

    // Cache to avoid circular updates (Pull -> Local -> Push -> Loop)
    private var lastLocalContent: String? = null
    private var lastRemoteContent: String? = null

    // kept across restarts: otherwise the first pull after the service (re)starts puts whatever the
    // server holds, often an old entry, back on the clipboard, and the keyboard pops it up again
    private var lastRemoteHash: String = ""
        set(value) {
            field = value
            prefs.edit().putString(KEY_LAST_REMOTE_HASH, value).apply()
        }

    private val transformer = object : IClipboardEntryTransformer.Stub() {
        override fun getPriority(): Int = 100

        override fun transform(clipboardText: String): String {
            // This is called when user copies text locally
            if (clipboardText == lastRemoteContent) {
                // If this change matches what we just pulled, ignore it (don't push back)
                return clipboardText
            }

            if (clipboardText != lastLocalContent) {
                lastLocalContent = clipboardText
                // the server will hold this text: don't take it back as "new" after a restart
                prefs.edit().putString(KEY_LAST_REMOTE_TEXT, clipboardText.hashCode().toString()).apply()
                Log.d(TAG, "[Push] Detected local change, triggering upload")
                uploadToCloud(clipboardText)
            }
            return clipboardText
        }

        override fun getDescription(): String = "SyncClipboard"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MainService onCreate")
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        lastRemoteHash = prefs.getString(KEY_LAST_REMOTE_HASH, "") ?: ""
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun start() {
        Log.d(TAG, "MainService start")
        connection = bindFcitxRemoteService(BuildConfig.MAIN_APPLICATION_ID,
            onDisconnect = {
                Log.d(TAG, "Disconnected from Fcitx")
                stopPeriodicSync()
            },
            onConnected = { service ->
                Log.d(TAG, "Connected to Fcitx")
                try {
                    service.registerClipboardEntryTransformer(transformer)
                    startPeriodicSync()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register transformer", e)
                }
            }
        )
        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun stop() {
        Log.d(TAG, "MainService stop")
        runCatching { unregisterReceiver(screenReceiver) }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        stopPeriodicSync()
        runCatching {
            connection.remoteService?.unregisterClipboardEntryTransformer(transformer)
        }
        unbindService(connection)
        scope.cancel()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ServerStore.KEY_SERVERS) {
            // servers edited: start over from the top of the list
            etags.clear()
            lastPreferredCheck = 0L
        }
        if (key == "quick_sync" || key == "sync_interval" || key == ServerStore.KEY_SERVERS) {
            Log.d(TAG, "Preference changed: $key, restarting sync")
            startPeriodicSync()
        }
    }

    /**
     * Run [block] against the server in use. If it fails with an IOException, try the other servers in
     * priority order and keep using the first one that works. Every [PREFERRED_RECHECK_MS] the list is
     * walked from the top again, so a higher-priority server is picked up once it is reachable again.
     * Returns null when no server is configured; throws the last error when all of them fail.
     */
    private fun <T> withFailover(block: (SyncServer) -> T): T? {
        val servers = ServerStore.load(prefs).filter { it.url.isNotBlank() }
        if (servers.isEmpty()) return null
        val active = servers.find { it.id == ServerStore.activeId(prefs) }
        val now = SystemClock.elapsedRealtime()
        val recheckPreferred = now - lastPreferredCheck >= PREFERRED_RECHECK_MS
        if (recheckPreferred) lastPreferredCheck = now
        val order = if (active == null || recheckPreferred) servers else listOf(active) + (servers - active)
        var lastError: IOException? = null
        for (server in order) {
            try {
                val result = block(server)
                if (server.id != active?.id) Log.i(TAG, "[Failover] Now using ${server.label}")
                ServerStore.setActive(prefs, server.id)
                return result
            } catch (e: IOException) {
                Log.w(TAG, "[Failover] ${server.label} failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError!!
    }

    private fun uploadToCloud(text: String) {
        if (!prefs.getBoolean("quick_sync", true)) {
            Log.d(TAG, "[Push] Sync disabled, skipping upload")
            return
        }
        scope.launch {
            try {
                withFailover { s ->
                    SyncClient.putClipboard(this@MainService, s.url, s.username, s.password, text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Push] Failed to upload clipboard on every server", e)
            }
        }
    }

    private fun startPeriodicSync() {
        stopPeriodicSync()
        
        val quickSync = prefs.getBoolean("quick_sync", true)
        if (!quickSync) {
            Log.d(TAG, "[Pull] Quick sync disabled, stopping background polling")
            return
        }
        
        Log.d(TAG, "[Pull] Starting periodic sync")
        syncJob = scope.launch {
            while (isActive) {
                try {
                    val interval = prefs.getString("sync_interval", "3")?.toLongOrNull() ?: 3L
                    var safeInterval = interval.coerceIn(1, 60)
                    
                    if (powerManager.isPowerSaveMode) {
                        safeInterval = 60L
                    }
                    
                    checkRemoteClipboard()
                    
                    delay(safeInterval * 1000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[Pull] Loop error", e)
                    delay(5000) // Retry delay on error
                }
            }
        }
    }

    private fun stopPeriodicSync() {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun checkRemoteClipboard() {
        val downloadPath = prefs.getString("download_path", null)
        val downloadUri = if (downloadPath != null) Uri.parse(downloadPath) else null

        try {
            // null: no server configured, not modified, or already seen
            val data = withFailover { s ->
                // 1. Fetch metadata (JSON) only
                val (partialData, newETag) = SyncClient.fetchClipboardJson(s.url, s.username, s.password, etags[s.id])
                if (newETag != null) etags[s.id] = newETag
                // 304 Not Modified
                if (partialData == null) return@withFailover null

                // 2. Check if hash matches last known remote hash to avoid repeated downloads
                if (partialData.hash.isNotEmpty() && partialData.hash == lastRemoteHash) return@withFailover null

                // 3. Download full details (files) if changed, from the same server
                SyncClient.downloadDetails(this, s.url, s.username, s.password, partialData, downloadUri)
            } ?: return

            // 4. Update hash cache
            if (data.hash.isNotEmpty()) {
                lastRemoteHash = data.hash
            }

            val remoteText = data.text
            Log.d(TAG, "[Pull] Processed data: type=${data.type}, text=$remoteText")
            
            val textHash = remoteText.hashCode().toString()
            val seenBefore = lastRemoteContent == null && prefs.getString(KEY_LAST_REMOTE_TEXT, null) == textHash
            if (remoteText.isNotEmpty() && remoteText != lastLocalContent && remoteText != lastRemoteContent && !seenBefore) {
                Log.d(TAG, "[Pull] Remote content changed, updating local")
                lastRemoteContent = remoteText
                prefs.edit().putString(KEY_LAST_REMOTE_TEXT, textHash).apply()
                lastLocalContent = remoteText // Update local cache to prevent echo
                
                withContext(Dispatchers.Main) {
                    if (data.type == "Text") {
                        updateSystemClipboard(remoteText)
                    } else {
                        updateSystemClipboardWithUri(Uri.parse(remoteText))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Error checking remote clipboard", e)
        }
    }

    private fun updateSystemClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SyncClipboard", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "[Pull] System clipboard updated (Text)")
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to update system clipboard", e)
        }
    }

    private fun updateSystemClipboardWithUri(uri: Uri) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(contentResolver, "SyncClipboard", uri)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "[Pull] System clipboard updated with URI: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to update system clipboard with URI", e)
        }
    }
}
