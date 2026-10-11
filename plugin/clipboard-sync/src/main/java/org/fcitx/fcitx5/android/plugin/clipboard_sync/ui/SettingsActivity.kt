package org.fcitx.fcitx5.android.plugin.clipboard_sync.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.clipboard_sync.R
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient

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
                
                preferenceManager.sharedPreferences?.edit()?.putString("download_path", it.toString())?.apply()
                findPreference<Preference>("download_path")?.summary = readablePath(it.toString())
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>("password")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                // mask the value, but still show whether one is set
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    if (p.text.isNullOrEmpty()) getString(R.string.download_path_not_set) else "••••••"
                }
            }

            findPreference<EditTextPreference>("sync_interval")?.setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }

            val downloadPref = findPreference<Preference>("download_path")
            val savedUri = preferenceManager.sharedPreferences?.getString("download_path", null)
            if (savedUri != null) {
                downloadPref?.summary = readablePath(savedUri)
            }
            downloadPref?.setOnPreferenceClickListener {
                openDocumentTree.launch(null)
                true
            }

            findPreference<Preference>("test_connection")?.setOnPreferenceClickListener {
                testConnection()
                true
            }
        }

        private fun testConnection() {
            val address = preferenceManager.sharedPreferences?.getString("server_address", "") ?: ""
            val username = preferenceManager.sharedPreferences?.getString("username", "") ?: ""
            val password = preferenceManager.sharedPreferences?.getString("password", "") ?: ""

            if (address.isBlank()) {
                Toast.makeText(context, R.string.please_set_server_address, Toast.LENGTH_SHORT).show()
                return
            }

            val progressDialog = AlertDialog.Builder(context)
                .setTitle(R.string.testing_connection)
                .setMessage(getString(R.string.connecting_to, address))
                .setCancelable(false)
                .create()
            progressDialog.show()

            CoroutineScope(Dispatchers.IO).launch {
                val result = SyncClient.testConnection(address, username, password)
                
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
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
    }
}
