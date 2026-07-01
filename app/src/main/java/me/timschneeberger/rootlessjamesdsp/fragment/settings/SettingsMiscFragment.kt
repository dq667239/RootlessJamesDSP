package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Patterns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.OnboardingActivity
import me.timschneeberger.rootlessjamesdsp.analysis.MNoiseFileVerifier
import me.timschneeberger.rootlessjamesdsp.analysis.ReferenceFileHasher
import me.timschneeberger.rootlessjamesdsp.analysis.TonalityReferenceBuilder
import me.timschneeberger.rootlessjamesdsp.analysis.TonalityReferenceStore
import me.timschneeberger.rootlessjamesdsp.analysis.WavPcmDecoder
import me.timschneeberger.rootlessjamesdsp.api.AutoEqClient
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.preference.IconPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSwitchPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AssetManagerExtensions.installPrivateAssets
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showYesNoAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale

class SettingsMiscFragment : SettingsBaseFragment() {

    private val autoStartNotify by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_autostart_prompt_at_boot)) }
    private val repairAssets by lazy { findPreference<Preference>(getString(R.string.key_troubleshooting_repair_assets)) }
    private val crashReports by lazy { findPreference<Preference>(getString(R.string.key_share_crash_reports)) }
    private val aeqApiUrl by lazy { findPreference<EditTextPreference>(getString(R.string.key_network_autoeq_api_url)) }
    private val debugDatabase by lazy { findPreference<Preference>(getString(R.string.key_debug_database)) }
    private val permSkipPrompt by lazy { findPreference<IconPreference>(getString(R.string.key_misc_permission_skip_prompt)) }
    private val permAutoStart by lazy { findPreference<IconPreference>(getString(R.string.key_misc_permission_auto_start)) }
    private val permRestartSetup by lazy { findPreference<Preference>(getString(R.string.key_misc_permission_restart_setup)) }
    private val tonalityImportReference by lazy { findPreference<Preference>(getString(R.string.key_tonality_import_reference)) }
    private val tonalityReferenceStatus by lazy { findPreference<Preference>(getString(R.string.key_tonality_reference_status)) }

    private val preferences: Preferences.App by inject()

    private val referenceImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        importTonalityReference(uri)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_misc_preferences, rootKey)

        crashReports?.setOnPreferenceChangeListener { _, newValue ->
            CrashlyticsImpl.setCollectionEnabled(newValue as Boolean)
            true
        }

        repairAssets?.setOnPreferenceClickListener {
            requireContext().assets.installPrivateAssets(requireContext(), force = true)
            requireContext().showAlert(R.string.success, R.string.troubleshooting_repair_assets_success)
            true
        }

        aeqApiUrl?.setOnPreferenceChangeListener { _, newValue ->
            if (!Patterns.WEB_URL.matcher(newValue.toString().lowercase(Locale.ROOT)).matches()) {
                requireContext().toast(R.string.network_invalid_url)
                return@setOnPreferenceChangeListener false
            }

            // Verify URL by performing a connection test
            try {
                val client = AutoEqClient(requireContext(), 5, newValue.toString())
                requireContext().toast(R.string.network_autoeq_conntest_running)

                client.queryProfiles(
                    "conntest",
                    onResponse = { _, _ ->
                        context?.toast(R.string.network_autoeq_conntest_done, false)
                    },
                    onFailure = { error ->
                        context?.showYesNoAlert(
                            getString(R.string.network_autoeq_conntest_fail),
                            getString(R.string.network_autoeq_conntest_fail_summary, error)
                        ) {
                            if (it) {
                                // Restore default URL if requested
                                preferences.reset<String>(R.string.key_network_autoeq_api_url)
                                aeqApiUrl?.text = preferences.getDefault(R.string.key_network_autoeq_api_url)
                            }
                        }
                    }
                )
            }
            catch(ex: IllegalArgumentException) {
                // Handle invalid base url argument in retrofit
                requireContext().toast(R.string.network_invalid_url)
                return@setOnPreferenceChangeListener false
            }

            true
        }

        permRestartSetup?.setOnPreferenceClickListener {
            startActivity(Intent(context, OnboardingActivity::class.java).apply {
                putExtra(OnboardingActivity.EXTRA_ROOTLESS_REDO_ADB_SETUP, true)
            })
            true
        }

        tonalityImportReference?.setOnPreferenceClickListener {
            referenceImportLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*", "application/octet-stream"))
            true
        }

        updatePermissionStates()
        updateTonalityReferenceStatus()

        crashReports?.parent?.isVisible = !BuildConfig.FOSS_ONLY
        debugDatabase?.parent?.isVisible = BuildConfig.DEBUG
        autoStartNotify?.isVisible = isRootless()
        permRestartSetup?.parent?.isVisible = isRootless()
    }

    override fun onResume() {
        updatePermissionStates()
        updateTonalityReferenceStatus()
        super.onResume()
    }

    private fun importTonalityReference(uri: Uri) {
        tonalityImportReference?.isEnabled = false
        tonalityReferenceStatus?.summary = getString(R.string.tonality_import_running)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val label = withContext(Dispatchers.IO) {
                    val context = requireContext().applicationContext
                    val resolver = context.contentResolver
                    val displayName = displayNameFor(uri) ?: "Imported reference WAV"
                    val hashes = ReferenceFileHasher.hash(resolver, uri)
                    val verified = MNoiseFileVerifier.isVerifiedMNoise(hashes.sha256, hashes.md5)
                    val decoded = resolver.openInputStream(uri)?.use {
                        WavPcmDecoder.decode(it, displayName)
                    } ?: throw IllegalArgumentException("Unable to open reference file")
                    val builder = TonalityReferenceBuilder()
                    val store = TonalityReferenceStore(context)

                    for (sampleRate in REFERENCE_SAMPLE_RATES) {
                        store.save(builder.build(decoded, sampleRate, hashes, verified))
                    }

                    store.loadBestFor(48_000).displayLabel.also {
                        preferences.set(R.string.key_tonality_reference_label, it)
                    }
                }

                updateTonalityReferenceStatus(label)
                val message = if (label == "verified M-Noise WAV") {
                    R.string.tonality_import_verified
                }
                else {
                    R.string.tonality_import_unverified
                }
                requireContext().toast(message)
            }
            catch (e: Exception) {
                Timber.e(e, "Failed to import tonality reference")
                updateTonalityReferenceStatus()
                requireContext().toast(getString(R.string.tonality_import_error, e.message ?: e.javaClass.simpleName))
            }
            finally {
                tonalityImportReference?.isEnabled = true
            }
        }
    }

    private fun updateTonalityReferenceStatus(label: String? = null) {
        val resolvedLabel = label ?: TonalityReferenceStore(requireContext()).referenceStatusLabel()
        tonalityReferenceStatus?.summary = getString(R.string.tonality_reference_format, resolvedLabel)
        tonalityImportReference?.summary = getString(R.string.tonality_reference_format, resolvedLabel)
    }

    private fun displayNameFor(uri: Uri): String? {
        val resolver = requireContext().contentResolver
        val cursor: Cursor? = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return it.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun updatePermissionStates() {
        if(!isRootless())
            return

        val allowSkipPrompt = context?.hasProjectMediaAppOp() == true
        val allowAutoStart = allowSkipPrompt && Settings.canDrawOverlays(context)

        autoStartNotify?.title = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot
            else R.string.autostart_prompt_at_boot
        )
        autoStartNotify?.summaryOn = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot_on
            else R.string.autostart_prompt_at_boot_on
        )
        autoStartNotify?.summaryOff = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot_off
            else R.string.autostart_prompt_at_boot_off
        )

        fun getIcon(allowed: Boolean) = context?.let {
            ContextCompat.getDrawable(it,
                if(allowed) R.drawable.ic_twotone_check_circle_24dp
                else R.drawable.ic_twotone_warning_24dp
            )
        }

        fun getSummary(allowed: Boolean) = context?.getString(
            if(allowed) R.string.permission_allowed
            else R.string.permission_not_allowed
        )

        permSkipPrompt?.summary = getSummary(allowSkipPrompt)
        permAutoStart?.summary = getSummary(allowAutoStart)
        permSkipPrompt?.icon = getIcon(allowSkipPrompt)
        permAutoStart?.icon = getIcon(allowAutoStart)
        permRestartSetup?.isVisible = !allowSkipPrompt || !allowAutoStart
    }

    companion object {
        private val REFERENCE_SAMPLE_RATES = intArrayOf(44_100, 48_000)

        fun newInstance(): SettingsMiscFragment {
            return SettingsMiscFragment()
        }
    }
}
