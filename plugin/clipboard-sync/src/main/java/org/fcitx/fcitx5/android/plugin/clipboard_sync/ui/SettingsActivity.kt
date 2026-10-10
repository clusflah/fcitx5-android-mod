package org.fcitx.fcitx5.android.plugin.clipboard_sync.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
        setContentView(R.layout.activity_settings)
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val openDocumentTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(it, flags)
                
                preferenceManager.sharedPreferences?.edit()?.putString("download_path", it.toString())?.apply()
                findPreference<Preference>("download_path")?.summary = it.toString()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>("password")?.setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            findPreference<EditTextPreference>("sync_interval")?.setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }

            val downloadPref = findPreference<Preference>("download_path")
            val savedUri = preferenceManager.sharedPreferences?.getString("download_path", null)
            if (savedUri != null) {
                downloadPref?.summary = savedUri
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
