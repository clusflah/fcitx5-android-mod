package org.fcitx.fcitx5.android.plugin.clipboard_sync.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.clipboard_sync.R
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.ServerStore
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncServer
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Own Toolbar + inset padding: with targetSdk 35+ the window is edge-to-edge, and the decor
        // action bar let the preference list slide under the status bar and the action bar.
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val fg = a.getColorStateList(0) ?: ColorStateList.valueOf(0xff000000.toInt())
        a.recycle()
        val toolbar = Toolbar(this).apply {
            title = getString(R.string.settings_title)
            setTitleTextColor(fg)
            navigationIcon = AppCompatResources.getDrawable(
                context, androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )?.mutate()?.also { DrawableCompat.setTintList(it, fg) }
        }
        val content = FrameLayout(this).apply { id = R.id.settings }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // must be set after setSupportActionBar, which replaces the navigation click listener
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        /** "content://…/tree/primary%3ADownload%2Fclipboard-sync" → "内部存储/Download/clipboard-sync" */
        private fun readablePath(uri: String): String = runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':', "")
            (if (volume == "primary") "内部存储" else volume) + if (path.isEmpty()) "" else "/$path"
        }.getOrDefault(uri)

        private val openDocumentTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(it, flags)

                prefs.edit().putString("download_path", it.toString()).apply()
                findPreference<Preference>("download_path")?.summary = readablePath(it.toString())
            }
        }

        private val prefs get() = preferenceManager.sharedPreferences!!
        private var servers = mutableListOf<SyncServer>()

        /** MainService switches the active server; reflect it while the page is open */
        private val activeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ServerStore.KEY_ACTIVE) renderServers()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            servers = ServerStore.load(prefs).toMutableList()
            renderServers()

            findPreference<EditTextPreference>("sync_interval")?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
            }

            val downloadPref = findPreference<Preference>("download_path")
            val savedUri = prefs.getString("download_path", null)
            if (savedUri != null) {
                downloadPref?.summary = readablePath(savedUri)
            }
            downloadPref?.setOnPreferenceClickListener {
                openDocumentTree.launch(null)
                true
            }

            findPreference<Preference>("test_connection")?.setOnPreferenceClickListener {
                testAll()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            prefs.registerOnSharedPreferenceChangeListener(activeListener)
            renderServers()
        }

        override fun onPause() {
            prefs.unregisterOnSharedPreferenceChangeListener(activeListener)
            super.onPause()
        }

        private fun saveServers() {
            ServerStore.save(prefs, servers)
            renderServers()
        }

        private fun renderServers() {
            val category = findPreference<PreferenceCategory>("servers") ?: return
            category.removeAll()
            val activeId = ServerStore.activeId(prefs)
            servers.forEachIndexed { i, s ->
                category.addPreference(Preference(requireContext()).apply {
                    isPersistent = false
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                    title = "${i + 1}. ${s.label}" + if (s.id == activeId) "　✅ 正在使用" else ""
                    summary = s.url + if (s.username.isNotBlank()) "\n用户：${s.username}" else ""
                    setOnPreferenceClickListener { editServer(s); true }
                })
            }
            category.addPreference(Preference(requireContext()).apply {
                isPersistent = false
                isIconSpaceReserved = false
                title = "＋ 添加服务器"
                summary = if (servers.isEmpty()) getString(R.string.server_address_summary)
                else "可以添加同一服务的其他地址（如局域网和公网），连不上时自动切换"
                setOnPreferenceClickListener { editServer(null); true }
            })
        }

        private fun editServer(server: SyncServer?) {
            val ctx = requireContext()
            val pad = (20 * resources.displayMetrics.density).toInt()
            fun input(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(ctx).apply {
                this.hint = hint
                setText(value)
                inputType = type
            }
            val name = input("名称（如：家里局域网）", server?.name.orEmpty())
            val url = input(
                getString(R.string.server_address_summary), server?.url.orEmpty(),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )
            val user = input(getString(R.string.username), server?.username.orEmpty())
            val pass = input(
                getString(R.string.password), server?.password.orEmpty(),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                listOf(name, url, user, pass).forEach { addView(it) }
            }
            val dialog = AlertDialog.Builder(ctx)
                .setTitle(if (server == null) "添加服务器" else "编辑服务器")
                .setView(ScrollView(ctx).apply { addView(box) })
                .setPositiveButton("保存", null)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(if (server == null) "测试" else "更多", null)
                .create()

            fun current() = SyncServer(
                id = server?.id ?: UUID.randomUUID().toString(),
                name = name.text.toString().trim(),
                url = url.text.toString().trim(),
                username = user.text.toString().trim(),
                password = pass.text.toString()
            )

            dialog.setOnShowListener {
                // replace the default listeners so the dialog stays open on validation errors / tests
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val s = current()
                    if (s.url.isBlank()) {
                        Toast.makeText(ctx, R.string.please_set_server_address, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val i = servers.indexOfFirst { it.id == s.id }
                    if (i >= 0) servers[i] = s else servers.add(s)
                    saveServers()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (server == null) testOne(current())
                    else serverMenu(server, dialog) { testOne(current()) }
                }
            }
            dialog.show()
        }

        private fun serverMenu(server: SyncServer, editDialog: AlertDialog, test: () -> Unit) {
            AlertDialog.Builder(requireContext())
                .setItems(arrayOf("测试连接", "上移（优先使用）", "下移", "删除")) { _, which ->
                    val i = servers.indexOfFirst { it.id == server.id }
                    when (which) {
                        0 -> {
                            test()
                            return@setItems
                        }
                        1 -> if (i > 0) servers.add(i - 1, servers.removeAt(i))
                        2 -> if (i in 0 until servers.lastIndex) servers.add(i + 1, servers.removeAt(i))
                        3 -> {
                            AlertDialog.Builder(requireContext())
                                .setMessage("删除服务器“${server.label}”？")
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    servers.removeAll { it.id == server.id }
                                    saveServers()
                                    editDialog.dismiss()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                            return@setItems
                        }
                    }
                    saveServers()
                    editDialog.dismiss()
                }
                .show()
        }

        private fun testOne(server: SyncServer) {
            if (server.url.isBlank()) {
                Toast.makeText(context, R.string.please_set_server_address, Toast.LENGTH_SHORT).show()
                return
            }
            val progressDialog = AlertDialog.Builder(context)
                .setTitle(R.string.testing_connection)
                .setMessage(getString(R.string.connecting_to, server.url))
                .setCancelable(false)
                .create()
            progressDialog.show()

            CoroutineScope(Dispatchers.IO).launch {
                val result = SyncClient.testConnection(server.url, server.username, server.password)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (!isAdded) return@withContext
                    if (result.isSuccess) {
                        Toast.makeText(context, R.string.connection_success, Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.connection_failed)
                            .setMessage(result.exceptionOrNull()?.message ?: getString(R.string.unknown_error))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }

        /** Test every server in priority order and list the results. */
        private fun testAll() {
            val list = servers.filter { it.url.isNotBlank() }
            if (list.isEmpty()) {
                Toast.makeText(context, R.string.please_set_server_address, Toast.LENGTH_SHORT).show()
                return
            }
            val progressDialog = AlertDialog.Builder(context)
                .setTitle(R.string.testing_connection)
                .setMessage("共 ${list.size} 个服务器…")
                .setCancelable(false)
                .create()
            progressDialog.show()

            val unknown = getString(R.string.unknown_error)
            CoroutineScope(Dispatchers.IO).launch {
                val lines = list.mapIndexed { i, s ->
                    val r = SyncClient.testConnection(s.url, s.username, s.password)
                    "${i + 1}. ${s.label}：" +
                            if (r.isSuccess) "✅ 连接成功" else "❌ " + (r.exceptionOrNull()?.message ?: unknown)
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (!isAdded) return@withContext
                    AlertDialog.Builder(context)
                        .setTitle(R.string.test_connection)
                        .setMessage(lines.joinToString("\n\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }
}
