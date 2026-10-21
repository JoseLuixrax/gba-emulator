package com.joseluixrax.gbaemu.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joseluixrax.gbaemu.BuildConfig
import com.joseluixrax.gbaemu.Prefs
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.databinding.ActivitySettingsBinding
import com.joseluixrax.gbaemu.emulator.GameFiles
import com.joseluixrax.gbaemu.ui.game.PadLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = getString(R.string.settings)
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val pickBios = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) installBios(uri)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>(Prefs.KEY_KEY_MAPPING)?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), KeyMappingActivity::class.java))
                true
            }
            findPreference<Preference>(Prefs.KEY_RESET_LAYOUT)?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.pref_reset_layout_confirm)
                    .setPositiveButton(R.string.reset_short) { _, _ ->
                        PadLayout.reset(preferenceManager.sharedPreferences!!)
                        toast(R.string.layout_reset_done)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<Preference>(Prefs.KEY_BIOS)?.setOnPreferenceClickListener {
                showBiosOptions()
                true
            }
            findPreference<Preference>(Prefs.KEY_ABOUT)?.summary =
                getString(R.string.pref_about_summary, BuildConfig.VERSION_NAME)
            findPreference<Preference>(Prefs.KEY_LICENSES)?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.pref_licenses)
                    .setMessage(R.string.licenses_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.view_source) { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MGBA_SOURCE_URL)))
                    }
                    .show()
                true
            }
            updateBiosSummary()
        }

        private fun updateBiosSummary() {
            val installed = GameFiles.biosFile(requireContext()).isFile
            findPreference<Preference>(Prefs.KEY_BIOS)?.summary =
                getString(if (installed) R.string.pref_bios_installed else R.string.pref_bios_missing)
        }

        private fun showBiosOptions() {
            val file = GameFiles.biosFile(requireContext())
            if (!file.isFile) {
                pickBios.launch(arrayOf("*/*"))
                return
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_bios)
                .setItems(arrayOf(getString(R.string.bios_replace), getString(R.string.bios_remove))) { _, which ->
                    if (which == 0) {
                        pickBios.launch(arrayOf("*/*"))
                    } else {
                        file.delete()
                        updateBiosSummary()
                    }
                }
                .show()
        }

        private fun installBios(uri: Uri) {
            val context = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@withContext false
                        // The GBA BIOS is exactly 16 KiB.
                        if (data.size != GBA_BIOS_SIZE) return@withContext false
                        val target = GameFiles.biosFile(context)
                        target.parentFile?.mkdirs()
                        target.writeBytes(data)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                toast(if (ok) R.string.bios_installed else R.string.bios_invalid)
                updateBiosSummary()
            }
        }

        private fun toast(message: Int) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val GBA_BIOS_SIZE = 16 * 1024
        const val MGBA_SOURCE_URL = "https://github.com/mgba-emu/mgba"
    }
}
