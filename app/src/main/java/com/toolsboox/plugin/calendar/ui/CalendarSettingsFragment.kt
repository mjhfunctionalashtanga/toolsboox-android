package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import timber.log.Timber
import android.text.format.DateFormat
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.google.firebase.analytics.FirebaseAnalytics
import com.toolsboox.R
import com.toolsboox.plugin.calendar.nw.LedgerEventSync
import com.toolsboox.da.LocaleItem
import com.toolsboox.databinding.FragmentCalendarSettingsBinding
import com.toolsboox.ot.LocaleItemAdapter
import com.toolsboox.ot.NoFilterAdapter
import com.toolsboox.plugin.calendar.nw.CalendarSyncWorker
import com.toolsboox.plugin.calendar.nw.UltrabridgeSyncWorker
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Calendar settings fragment.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
@AndroidEntryPoint
class CalendarSettingsFragment @Inject constructor() : ScreenFragment() {

    /** Picks a settings JSON to import (finger-friendly; any file type so JSON always shows). */
    private val importSettingsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val json = requireContext().contentResolver.openInputStream(uri)!!
                .bufferedReader().use { it.readText() }
            val applied = com.toolsboox.plugin.calendar.ot.SettingsBackup.importJson(requireContext(), json)
            if (applied.contains("webdav")) {
                sharedPreferences.edit().putBoolean("ultrabridgeEnabled", true).apply()
                val ub = getUltrabridgeEncryptedPrefs()
                binding.ultrabridgeUrlInput.setText(ub.getString("ultrabridge_webdav_url", ""))
                binding.ultrabridgeUserInput.setText(ub.getString("ultrabridge_webdav_user", ""))
                binding.ultrabridgePassInput.setText(ub.getString("ultrabridge_webdav_pass", ""))
                binding.ultrabridgeEnableSwitch.isChecked = true
            }
            showMessage(
                if (applied.isEmpty()) "Nothing to import" else "Imported: ${applied.joinToString(", ")}",
                binding.root
            )
        } catch (e: Exception) {
            Timber.w(e, "settings import failed")
            showMessage("Import failed", binding.root)
        }
    }

    /** FULL ledger backup: zip the entire external Documents tree into the file the user picked.
     *  APFS-style cheap it is not, but a personal ledger zips in seconds on device. */
    private val backupCreateLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri ?: return@registerForActivityResult
        val ctx = requireContext()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                val root = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!
                else java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "toolsBoox")
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
                        // Settings ride inside the backup.
                        zip.putNextEntry(java.util.zip.ZipEntry("ledger-settings.json"))
                        zip.write(com.toolsboox.plugin.calendar.ot.SettingsBackup.exportJson(ctx).toByteArray())
                        zip.closeEntry()
                        root.walkTopDown().filter { it.isFile }.forEach { f ->
                            zip.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(root).path))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }.isSuccess
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                showMessage(if (ok) "Ledger backed up" else "Backup failed", binding.root)
            }
        }
    }

    /** Restore a backup zip over the Documents tree (backup's copies win); settings re-import. */
    private val backupRestoreLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val ctx = requireContext()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var files = 0
            val ok = runCatching {
                val root = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!
                else java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "toolsBoox")
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                if (entry.name == "ledger-settings.json") {
                                    val json = zip.readBytes().toString(Charsets.UTF_8)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        runCatching { com.toolsboox.plugin.calendar.ot.SettingsBackup.importJson(ctx, json) }
                                    }
                                } else if (!entry.name.contains("..")) {   // zip-slip guard
                                    val dest = java.io.File(root, entry.name)
                                    dest.parentFile?.mkdirs()
                                    dest.outputStream().use { zip.copyTo(it) }
                                    files++
                                }
                            }
                            zip.closeEntry(); entry = zip.nextEntry
                        }
                    }
                }
            }.isSuccess
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                showMessage(if (ok) "Restored $files files — restart the app" else "Restore failed", binding.root)
            }
        }
    }

    /** Result of the Google Calendar consent flow — a toast either way; the granted scope now lets
     *  [LedgerEventSync] fetch a token and push events. */
    private val googleCalendarConnectLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ok = result.resultCode == android.app.Activity.RESULT_OK
        Toast.makeText(
            requireContext(),
            if (ok) R.string.calendar_settings_gcal_connected_toast
            else R.string.calendar_settings_gcal_connect_failed_toast,
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * The shared preferences.
     */
    @Inject
    lateinit var sharedPreferences: SharedPreferences

    /**
     * The Firebase analytics.
     */
    @Inject
    lateinit var firebaseAnalytics: FirebaseAnalytics

    /**
     * The presenter of the fragment.
     */
    @Inject
    lateinit var presenter: CalendarSettingsPresenter

    /**
     * The inflated layout.
     */
    override val view = R.layout.fragment_calendar_settings

    /**
     * The view binding.
     */
    private lateinit var binding: FragmentCalendarSettingsBinding

    /**
     * The selected locale language tag.
     */
    private lateinit var selectedLocaleLanguageTag: String

    /**
     * The selected start view.
     */
    private var selectedStartView: Int = 0

    /**
     * The selected start hour.
     */
    private var selectedStartHour: Int = -1

    /**
     * The selected note template.
     */
    private var selectedNoteTemplate: Int = 0

    /**
     * Whether auto-sync is enabled.
     */
    private var autoSyncEnabled: Boolean = false

    /**
     * The selected auto-sync interval index (maps to SYNC_INTERVAL_MINUTES).
     */
    private var selectedAutoSyncInterval: Int = 1

    companion object {
        @Volatile private var cachedUbPrefs: SharedPreferences? = null
        private const val WORK_NAME = "calendar-cloud-sync"
        private const val ULTRABRIDGE_ENCRYPTED_PREFS_NAME = "ultrabridge_encrypted_prefs"

        /**
         * Interval options in minutes, indexed to match the spinner.
         */
        private val SYNC_INTERVAL_MINUTES = longArrayOf(15, 60, 360, 1440)
    }

    /**
     * Get or create EncryptedSharedPreferences for Ultrabridge WebDAV credentials.
     * MEMOIZED process-wide: EncryptedSharedPreferences.create + MasterKey take 200ms–2s on
     * e-ink flash, and this used to run on the main thread every settings open — the lag.
     */
    private fun getUltrabridgeEncryptedPrefs(): SharedPreferences {
        cachedUbPrefs?.let { return it }
        val masterKey = MasterKey.Builder(requireContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            requireContext(),
            ULTRABRIDGE_ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { cachedUbPrefs = it }
    }

    /**
     * OnViewCreated hook.
     *
     * @param view the parent view
     * @param savedInstanceState the saved instance state
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = FragmentCalendarSettingsBinding.bind(view)

        toolbar.toolbarPager.visibility = View.GONE

        // Multi-site foundation. Fold any existing single-site bridge creds into one site (so nothing
        // breaks on first run after the update), then seed the owner's three sites once. Both are
        // idempotent — safe to call every time settings opens.
        com.toolsboox.plugin.calendar.nw.SiteStore.seedIfNeeded(requireContext())
        com.toolsboox.plugin.calendar.nw.SiteStore.seedKnownSites(requireContext())
    }

    /**
     * OnResume hook.
     */
    override fun onResume() {
        super.onResume()

        toolbar.root.title = getString(R.string.calendar_main_title, getString(R.string.calendar_settings_title))

        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        val savedLocaleLanguageTag = sharedPreferences.getString("calendarLocale", Locale.getDefault().toLanguageTag())
        selectedLocaleLanguageTag = savedLocaleLanguageTag ?: Locale.getDefault().toLanguageTag()

        selectedStartView = sharedPreferences.getInt("calendarStartView", 0)
        selectedStartHour = sharedPreferences.getInt("calendarStartHour", 5)
        selectedNoteTemplate = sharedPreferences.getInt("calendarNoteTemplate", 0)

        // Start view settings
        val listOfStartViews = mutableListOf<String>()
        listOfStartViews.add(getString(R.string.calendar_settings_start_view_day))
        listOfStartViews.add(getString(R.string.calendar_settings_start_view_week))
        listOfStartViews.add(getString(R.string.calendar_settings_start_view_month))
        listOfStartViews.add(getString(R.string.calendar_settings_start_view_quarter))
        listOfStartViews.add(getString(R.string.calendar_settings_start_view_year))

        val startViewAdapter = NoFilterAdapter(this.requireContext(), R.layout.list_item_locale, listOfStartViews)
        binding.startViewSpinner.setAdapter(startViewAdapter)
        startViewAdapter.notifyDataSetChanged()

        binding.startViewSpinner.inputType = 0
        binding.startViewSpinner.setOnItemClickListener { _, _, position, _ ->
            requireActivity().currentFocus?.let {
                imm.hideSoftInputFromWindow(it.windowToken, 0)
            }
            selectedStartView = position
        }

        // Locale settings
        val listOfLocales = mutableListOf<LocaleItem>()
        for (locale in Locale.getAvailableLocales()) {
            listOfLocales.add(LocaleItem(locale.toLanguageTag(), locale.displayName))
        }

        val localeIndex = listOfLocales.indexOfFirst { it.languageTag == selectedLocaleLanguageTag }

        val localeAdapter = LocaleItemAdapter(this.requireContext(), R.layout.list_item_locale, listOfLocales)
        binding.localesSpinner.setAdapter(localeAdapter)
        localeAdapter.notifyDataSetChanged()

        binding.localesSpinner.setOnItemClickListener { _, _, position, _ ->
            requireActivity().currentFocus?.let {
                imm.hideSoftInputFromWindow(it.windowToken, 0)
            }
            updateLocaleSettings(localeAdapter, position)
        }

        // Start hour settings
        val listOfStartHours = mutableListOf<String>()
        listOfStartHours.add(getString(R.string.calendar_settings_select_start_hour_empty))
        val hourPattern = if (DateFormat.is24HourFormat(context)) "HH" else "ha"
        listOfStartHours.add(LocalTime.of(0, 0, 0).format(DateTimeFormatter.ofPattern(hourPattern)))
        listOfStartHours.add(LocalTime.of(1, 0, 0).format(DateTimeFormatter.ofPattern(hourPattern)))
        listOfStartHours.add(LocalTime.of(2, 0, 0).format(DateTimeFormatter.ofPattern(hourPattern)))
        listOfStartHours.add(LocalTime.of(3, 0, 0).format(DateTimeFormatter.ofPattern(hourPattern)))
        listOfStartHours.add(LocalTime.of(4, 0, 0).format(DateTimeFormatter.ofPattern(hourPattern)))
        listOfStartHours.add(LocalTime.of(5, 0, 0).format(DateTimeFormatter.ofPattern(hourPattern)))
        listOfStartHours.add(LocalTime.of(6, 0, 0).format(DateTimeFormatter.ofPattern(hourPattern)))
        listOfStartHours.add(LocalTime.of(7, 0, 0).format(DateTimeFormatter.ofPattern(hourPattern)))

        val startHourAdapter = NoFilterAdapter(this.requireContext(), R.layout.list_item_locale, listOfStartHours)
        binding.startHourSpinner.setAdapter(startHourAdapter)
        startHourAdapter.notifyDataSetChanged()

        binding.startHourSpinner.inputType = 0
        binding.startHourSpinner.setOnItemClickListener { _, _, position, _ ->
            requireActivity().currentFocus?.let {
                imm.hideSoftInputFromWindow(it.windowToken, 0)
            }
            selectedStartHour = position - 1
        }

        // Note template
        val listOfNoteTemplates = mutableListOf<String>()
        listOfNoteTemplates.add(getString(R.string.calendar_settings_select_note_template_lines))
        listOfNoteTemplates.add(getString(R.string.calendar_settings_select_note_template_grid))

        val noteTemplateAdapter = NoFilterAdapter(this.requireContext(), R.layout.list_item_locale, listOfNoteTemplates)
        binding.noteTemplateSpinner.setAdapter(noteTemplateAdapter)
        noteTemplateAdapter.notifyDataSetChanged()

        binding.noteTemplateSpinner.inputType = 0
        binding.noteTemplateSpinner.setOnItemClickListener { _, _, position, _ ->
            requireActivity().currentFocus?.let {
                imm.hideSoftInputFromWindow(it.windowToken, 0)
            }
            selectedNoteTemplate = position
        }

        // Rotation orientation preferences — bitmask of allowed orientations
        // bit 0 = portrait, 1 = landscape CW, 2 = reverse portrait, 3 = landscape CCW
        val rotationMask = sharedPreferences.getInt("rotationOrientationMask", 0b1111)
        binding.rotationPortraitCheck.isChecked = (rotationMask and 0b0001) != 0
        binding.rotationLandscapeCwCheck.isChecked = (rotationMask and 0b0010) != 0
        binding.rotationReversePortraitCheck.isChecked = (rotationMask and 0b0100) != 0
        binding.rotationLandscapeCcwCheck.isChecked = (rotationMask and 0b1000) != 0

        // Auto-rotate. The rotate button's hold gesture was meant to do this and proved neither
        // discoverable nor reliable on the Palma, so it lives here as a plain switch. Applied
        // immediately AND remembered, so it survives leaving the screen.
        binding.autoRotateSwitch.isChecked = sharedPreferences.getBoolean("autoRotate", false)
        binding.autoRotateSwitch.setOnCheckedChangeListener { _, checked ->
            sharedPreferences.edit().putBoolean("autoRotate", checked).apply()
            requireActivity().requestedOrientation = if (checked)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else com.toolsboox.ot.ScreenRotation.displayedBy(
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    requireContext().display?.rotation ?: 0
                else requireActivity().windowManager.defaultDisplay.rotation
            )
        }

        // Auto-capture section zones on page-leave (paid vision OCR). Global, default on.
        binding.autoCaptureSwitch.isChecked = sharedPreferences.getBoolean("autoCaptureSections", true)
        binding.autoCaptureSwitch.setOnCheckedChangeListener { _, checked ->
            sharedPreferences.edit().putBoolean("autoCaptureSections", checked).apply()
        }

        // Auto-sync settings
        autoSyncEnabled = sharedPreferences.getBoolean("autoSyncEnabled", false)
        selectedAutoSyncInterval = sharedPreferences.getInt("autoSyncIntervalIndex", 1)

        binding.autoSyncSwitch.isChecked = autoSyncEnabled

        val listOfIntervals = mutableListOf<String>()
        listOfIntervals.add(getString(R.string.calendar_settings_auto_sync_interval_15_min))
        listOfIntervals.add(getString(R.string.calendar_settings_auto_sync_interval_1_hr))
        listOfIntervals.add(getString(R.string.calendar_settings_auto_sync_interval_6_hr))
        listOfIntervals.add(getString(R.string.calendar_settings_auto_sync_interval_daily))

        val intervalAdapter = NoFilterAdapter(this.requireContext(), R.layout.list_item_locale, listOfIntervals)
        binding.autoSyncIntervalSpinner.setAdapter(intervalAdapter)
        intervalAdapter.notifyDataSetChanged()

        binding.autoSyncIntervalSpinner.inputType = 0
        binding.autoSyncIntervalSpinner.setOnItemClickListener { _, _, position, _ ->
            requireActivity().currentFocus?.let {
                imm.hideSoftInputFromWindow(it.windowToken, 0)
            }
            selectedAutoSyncInterval = position
        }

        binding.autoSyncIntervalSpinner.setText(listOfIntervals[selectedAutoSyncInterval])

        // Toggle interval spinner visibility based on switch state
        updateAutoSyncIntervalVisibility()
        binding.autoSyncSwitch.setOnCheckedChangeListener { _, isChecked ->
            autoSyncEnabled = isChecked
            updateAutoSyncIntervalVisibility()
        }

        // Ultrabridge Backup settings
        val ultrabridgePrefs = getUltrabridgeEncryptedPrefs()
        val ultrabridgeEnabled = sharedPreferences.getBoolean("ultrabridgeEnabled", false)
        binding.ultrabridgeEnableSwitch.isChecked = ultrabridgeEnabled
        binding.ultrabridgeUrlInput.setText(ultrabridgePrefs.getString("ultrabridge_webdav_url", ""))
        binding.ultrabridgeUserInput.setText(ultrabridgePrefs.getString("ultrabridge_webdav_user", ""))
        binding.ultrabridgePassInput.setText(ultrabridgePrefs.getString("ultrabridge_webdav_pass", ""))

        // Toggle field visibility based on switch state
        updateUltrabridgeFieldsVisibility(ultrabridgeEnabled)
        binding.ultrabridgeEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateUltrabridgeFieldsVisibility(isChecked)
        }

        // Google Calendar (events) settings — events you add in Ledger land on this calendar.
        val gcalEnabled = sharedPreferences.getBoolean(LedgerEventSync.ENABLED_KEY, false)
        binding.gcalEnableSwitch.isChecked = gcalEnabled
        binding.gcalIdInput.setText(sharedPreferences.getString(LedgerEventSync.CALENDAR_ID_KEY, "primary"))
        updateGcalFieldsVisibility(gcalEnabled)

        // On-device extract (redundant with the server OCR) — off by default.
        binding.autoExtractSwitch.isChecked = sharedPreferences.getBoolean(
            com.toolsboox.plugin.calendar.ot.LedgerExtractor.AUTO_EXTRACT_ENABLED_KEY, false)

        // Community bridge (FluentCommunity) creds — one home for connection settings, instead
        // of being buried under Boards → Web bridge.
        val bridgeCfg = com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(requireContext())
        val boardsCfg = com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(requireContext())
        // Prefer whichever bridge already holds creds (they share site/user/pass on one site).
        binding.communitySiteInput.setText(bridgeCfg.site.ifBlank { boardsCfg.site })
        binding.communityUserInput.setText(bridgeCfg.user.ifBlank { boardsCfg.user })
        binding.communityPassInput.setText(bridgeCfg.pass.ifBlank { boardsCfg.pass })
        binding.communityBoardInput.setText(if (boardsCfg.boardId > 0) boardsCfg.boardId.toString() else "")
        // Collapse the four connection fields behind an enable switch (like Ultrabridge / GCal),
        // so a page full of credential boxes isn't the first thing you see. Start expanded only
        // when creds already exist. Hiding a field never clears it — Save reads the inputs directly.
        val communityHasCreds = bridgeCfg.site.isNotBlank() || boardsCfg.site.isNotBlank() ||
            bridgeCfg.user.isNotBlank() || boardsCfg.user.isNotBlank() || boardsCfg.boardId > 0
        binding.communityEnableSwitch.isChecked = communityHasCreds
        updateCommunityFieldsVisibility(communityHasCreds)
        binding.communityEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateCommunityFieldsVisibility(isChecked)
        }

        // Manage sites: the multi-site surface. Activating a site write-throughs its creds into the
        // same bridge keys these inline fields edit, so on return we re-read the active site's creds
        // into the boxes to keep the two views in step.
        binding.buttonManageSites.setOnClickListener {
            SitesSettingsDialog.show(requireContext()) {
                val bCfg = com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(requireContext())
                val brCfg = com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(requireContext())
                binding.communitySiteInput.setText(bCfg.site.ifBlank { brCfg.site })
                binding.communityUserInput.setText(bCfg.user.ifBlank { brCfg.user })
                binding.communityPassInput.setText(bCfg.pass.ifBlank { brCfg.pass })
                binding.communityBoardInput.setText(if (brCfg.boardId > 0) brCfg.boardId.toString() else "")
                val has = brCfg.site.isNotBlank() || bCfg.site.isNotBlank()
                binding.communityEnableSwitch.isChecked = has
                updateCommunityFieldsVisibility(has)
            }
        }
        // Persist toggles the INSTANT they flip — not only on the Save button. Users expect a
        // toggle to stick; tapping Connect or backing out used to lose an un-Saved flip.
        binding.gcalEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(LedgerEventSync.ENABLED_KEY, isChecked).apply()
            updateGcalFieldsVisibility(isChecked)
        }
        binding.autoExtractSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(
                com.toolsboox.plugin.calendar.ot.LedgerExtractor.AUTO_EXTRACT_ENABLED_KEY, isChecked).apply()
        }
        binding.gcalConnectButton.setOnClickListener {
            // Incremental consent for the Calendar-events scope (alongside Drive), so the signed-in
            // account can push events. Reuses whatever Google account the app already uses.
            val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestScopes(
                    Scope(DriveScopes.DRIVE_APPDATA),
                    Scope(DriveScopes.DRIVE_FILE),
                    Scope(LedgerEventSync.CALENDAR_SCOPE)
                )
                .requestEmail()
                .build()
            googleCalendarConnectLauncher.launch(GoogleSignIn.getClient(requireContext(), opts).signInIntent)
        }

        // Create shortcut of calendar
        binding.buttonShortcut.setOnClickListener {
            presenter.createShortcut(this@CalendarSettingsFragment, binding)
        }

        // Sync the patterns of the calendar
        binding.buttonPatternSync.setOnClickListener {
            val languageTag = sharedPreferences.getString("calendarLocale", null)
            val locale = languageTag?.let { Locale.forLanguageTag(it) }
            presenter.patternSync(this@CalendarSettingsFragment, binding, locale ?: Locale.getDefault())
        }

        // Export the calendar
        binding.buttonBackup.setOnClickListener {
            presenter.export(this@CalendarSettingsFragment, binding)
        }

        // Settings transfer: export the connection settings (WebDAV / RSS / AI keys) as a JSON to
        // share to another device; import applies one. Same schema as the iPad.
        binding.buttonExportSettings.setOnClickListener {
            try {
                val json = com.toolsboox.plugin.calendar.ot.SettingsBackup.exportJson(requireContext())
                val dir = java.io.File(requireContext().cacheDir, "settings").apply { mkdirs() }
                val file = java.io.File(dir, "ledger-settings.json").apply { writeText(json) }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file
                )
                val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Ledger settings")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(android.content.Intent.createChooser(share, getString(R.string.calendar_settings_button_export_settings)))
            } catch (e: Exception) {
                Timber.w(e, "settings export failed")
                showMessage("Export failed", binding.root)
            }
        }
        binding.buttonImportSettings.setOnClickListener { importSettingsLauncher.launch("*/*") }

        // "Import backup" was a dead button (no handler); it now restores a full-ledger backup zip —
        // exactly what its label says, and the same action that was otherwise hidden on the long-press
        // of Import settings below. Pairs with "Export backup".
        binding.buttonRestore.setOnClickListener {
            backupRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        binding.buttonTextSize.setOnClickListener { showLegibilityDialog() }
        binding.buttonTextSize.text = "Legibility (font & size)"

        // FULL ledger backup/restore (data, not just settings): long-press Export = zip the whole
        // Documents tree (every day page, contact, board, clipping) to a file you pick; long-press
        // Import = restore a backup zip over it. Settings JSON is included in the zip.
        binding.buttonExportSettings.setOnLongClickListener {
            val df = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
            backupCreateLauncher.launch("ledger-backup-${df.format(java.util.Date())}.zip")
            true
        }
        binding.buttonImportSettings.setOnLongClickListener {
            backupRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            true
        }

        // Save and back
        binding.buttonSave.setOnClickListener {
            sharedPreferences.edit().putString("calendarLocale", selectedLocaleLanguageTag).apply()
            sharedPreferences.edit().putInt("calendarStartView", selectedStartView).apply()
            sharedPreferences.edit().putInt("calendarStartHour", selectedStartHour).apply()
            sharedPreferences.edit().putInt("calendarNoteTemplate", selectedNoteTemplate).apply()

            // Persist rotation orientation mask
            var rotMask = 0
            if (binding.rotationPortraitCheck.isChecked) rotMask = rotMask or 0b0001
            if (binding.rotationLandscapeCwCheck.isChecked) rotMask = rotMask or 0b0010
            if (binding.rotationReversePortraitCheck.isChecked) rotMask = rotMask or 0b0100
            if (binding.rotationLandscapeCcwCheck.isChecked) rotMask = rotMask or 0b1000
            if (rotMask == 0) rotMask = 0b0001  // never empty — fall back to portrait
            sharedPreferences.edit().putInt("rotationOrientationMask", rotMask).apply()

            // Persist auto-sync settings
            sharedPreferences.edit().putBoolean("autoSyncEnabled", autoSyncEnabled).apply()
            sharedPreferences.edit().putInt("autoSyncIntervalIndex", selectedAutoSyncInterval).apply()

            // Persist the independent toggles EARLY — before the Ultrabridge validation below,
            // which can `return@setOnClickListener` and used to silently drop these (the
            // "toggle says it works but doesn't turn" bug). Google Calendar on/off + target,
            // and the on-device extract switch, have nothing to do with Ultrabridge.
            val gcalIdEarly = binding.gcalIdInput.text?.toString()?.trim().let { if (it.isNullOrEmpty()) "primary" else it }
            sharedPreferences.edit()
                .putBoolean(LedgerEventSync.ENABLED_KEY, binding.gcalEnableSwitch.isChecked)
                .putString(LedgerEventSync.CALENDAR_ID_KEY, gcalIdEarly)
                .putBoolean(com.toolsboox.plugin.calendar.ot.LedgerExtractor.AUTO_EXTRACT_ENABLED_KEY, binding.autoExtractSwitch.isChecked)
                .apply()

            // Enqueue or cancel periodic sync work
            val workManager = WorkManager.getInstance(requireContext())
            if (autoSyncEnabled) {
                val intervalMinutes = SYNC_INTERVAL_MINUTES[selectedAutoSyncInterval]
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    // Skip only when the battery is genuinely low. No charging requirement —
                    // this device often runs for days unplugged, and Doze already throttles
                    // periodic work into occasional maintenance windows once the screen is off.
                    .setRequiresBatteryNotLow(true)
                    .build()
                val syncRequest = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
                    intervalMinutes, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    // Back off generously on failure so a flaky network can't cause a retry storm.
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    syncRequest
                )
            } else {
                workManager.cancelUniqueWork(WORK_NAME)
            }

            // Persist Ultrabridge settings
            // Community (FluentCommunity) + Boards bridge creds — persisted FIRST, before the
            // Ultrabridge validation below can early-return, so an incomplete Ultrabridge never
            // silently drops community/boards creds typed in the same session.
            run {
                val bSite = binding.communitySiteInput.text?.toString()?.trim().orEmpty()
                val bUser = binding.communityUserInput.text?.toString()?.trim().orEmpty()
                val bPass = binding.communityPassInput.text?.toString().orEmpty().replace(" ", "")
                com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.saveConfig(
                    requireContext(),
                    com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.Config(bSite, bUser, bPass)
                )
                val bBoard = binding.communityBoardInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
                com.toolsboox.plugin.calendar.nw.LedgerWebBridge.saveConfig(
                    requireContext(),
                    com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config(bSite, bUser, bPass, bBoard)
                )
            }

            val ubEnabled = binding.ultrabridgeEnableSwitch.isChecked
            val ubUrl = binding.ultrabridgeUrlInput.text?.toString()?.trim() ?: ""
            val ubUser = binding.ultrabridgeUserInput.text?.toString()?.trim() ?: ""
            val ubPass = binding.ultrabridgePassInput.text?.toString() ?: ""

            if (ubEnabled && (ubUrl.isEmpty() || ubUser.isEmpty() || ubPass.isEmpty())) {
                Toast.makeText(
                    requireContext(),
                    R.string.calendar_settings_ultrabridge_missing_fields,
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            sharedPreferences.edit().putBoolean("ultrabridgeEnabled", ubEnabled).apply()

            // Store credentials in EncryptedSharedPreferences
            val ubPrefs = getUltrabridgeEncryptedPrefs()
            ubPrefs.edit()
                .putString("ultrabridge_webdav_url", ubUrl)
                .putString("ultrabridge_webdav_user", ubUser)
                .putString("ultrabridge_webdav_pass", ubPass)
                .apply()

            // Enqueue or cancel Ultrabridge periodic work
            if (ubEnabled) {
                val ubConstraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    // Skip only when the battery is genuinely low. No charging requirement —
                    // this device runs for days unplugged. Doze throttles this periodic run, the
                    // 60-min period keeps the wake count down, and the on-exit syncNow() (network-
                    // only, no battery gate) still pushes a full re-mirror every time you close.
                    .setRequiresBatteryNotLow(true)
                    .build()
                val ubRequest = PeriodicWorkRequestBuilder<UltrabridgeSyncWorker>(
                    60, TimeUnit.MINUTES
                )
                    .setConstraints(ubConstraints)
                    // Back off generously on failure so a flaky network can't cause a retry storm.
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    UltrabridgeSyncWorker.WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    ubRequest
                )
                Toast.makeText(
                    requireContext(),
                    R.string.calendar_settings_ultrabridge_enabled_toast,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                workManager.cancelUniqueWork(UltrabridgeSyncWorker.WORK_NAME)
            }

            // (Google Calendar, on-device extract, and community/boards creds were persisted early,
            // above — before the Ultrabridge validation — so nothing is dropped on an early return.)

            this@CalendarSettingsFragment.requireActivity().onBackPressed()
        }

        binding.buttonBack.setOnClickListener {
            this@CalendarSettingsFragment.requireActivity().onBackPressed()
        }

        // Set start of spinners
        if (localeIndex > -1) {
            binding.localesSpinner.setText(listOfLocales[localeIndex].toString())
            updateLocaleSettings(localeAdapter, localeIndex)
        }

        binding.startViewSpinner.setText(listOfStartViews[selectedStartView])
        binding.startHourSpinner.setText(listOfStartHours[selectedStartHour + 1])
        binding.noteTemplateSpinner.setText(listOfNoteTemplates[selectedNoteTemplate])
    }

    /**
     * Show or hide the auto-sync interval spinner based on the switch state.
     */
    private fun updateAutoSyncIntervalVisibility() {
        val visibility = if (autoSyncEnabled) View.VISIBLE else View.GONE
        binding.autoSyncIntervalText.visibility = visibility
        binding.autoSyncIntervalSpinnerLayout.visibility = visibility
    }

    /**
     * Show or hide the Ultrabridge WebDAV fields based on the enable switch state.
     */
    private fun updateUltrabridgeFieldsVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        binding.ultrabridgeUrlLayout.visibility = visibility
        binding.ultrabridgeUserLayout.visibility = visibility
        binding.ultrabridgePassLayout.visibility = visibility
    }

    /**
     * Show or hide the Google Calendar fields (target calendar + connect) based on the enable switch.
     */
    private fun updateGcalFieldsVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        binding.gcalIdLayout.visibility = visibility
        binding.gcalConnectButton.visibility = visibility
    }

    /**
     * Show or hide the Community / Boards bridge fields (site, user, pass, board) based on the
     * enable switch. Hiding via GONE keeps the text intact, so Save still persists what was typed.
     */
    private fun updateCommunityFieldsVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        binding.communitySiteLayout.visibility = visibility
        binding.communityUserLayout.visibility = visibility
        binding.communityPassLayout.visibility = visibility
        binding.communityBoardLayout.visibility = visibility
    }


    /**
     * Update the locale settings fields.
     *
     * @param adapter the adapter
     * @param position the position of the locale in the list
     */
    private fun updateLocaleSettings(adapter: LocaleItemAdapter, position: Int) {
        val localeItem = adapter.getItem(position)
        val locale = Locale.forLanguageTag(localeItem.languageTag)
        val localDate = LocalDate.now()

        val weekFields = WeekFields.of(locale)
        val startWeekDate = localDate
            .with(weekFields.dayOfWeek(), 1)

        val dayName = when (startWeekDate.dayOfWeek.value) {
            DayOfWeek.MONDAY.value -> DayOfWeek.MONDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())
            DayOfWeek.TUESDAY.value -> DayOfWeek.TUESDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())
            DayOfWeek.WEDNESDAY.value -> DayOfWeek.WEDNESDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())
            DayOfWeek.THURSDAY.value -> DayOfWeek.THURSDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())
            DayOfWeek.FRIDAY.value -> DayOfWeek.FRIDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())
            DayOfWeek.SATURDAY.value -> DayOfWeek.SATURDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())
            DayOfWeek.SUNDAY.value -> DayOfWeek.SUNDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> "?"
        }

        binding.calendarFirstDayOfTheWeekValue.text = dayName

        val firstWeek = LocalDate.of(localDate.year, 1, 1)
            .get(WeekFields.of(locale).weekOfWeekBasedYear())
        val lastWeek = LocalDate.of(localDate.year + 1, 1, 1).minusDays(1L)
            .get(WeekFields.of(locale).weekOfWeekBasedYear())

        binding.calendarWeekNumberOfFirstDayValue.text = requireContext().getString(R.string.week_abbreviation, firstWeek)
        binding.calendarWeekNumberOfLastDayValue.text = requireContext().getString(R.string.week_abbreviation, lastWeek)

        selectedLocaleLanguageTag = localeItem.languageTag
    }

    /**
     * OnPause hook.
     */
    override fun onPause() {
        super.onPause()

        toolbar.toolbarPager.visibility = View.GONE
    }

    /**
     * Show the progress bar.
     */
    override fun showLoading() {
        binding.mainProgress.visibility = View.VISIBLE
    }

    /**
     * Hide the progress bar.
     */
    override fun hideLoading() {
        binding.mainProgress.visibility = View.INVISIBLE
    }

    /**
     * Menu and modal text size, each on its own dial.
     *
     * They used to share one, and they are not the same thing to read: a menu is a list of
     * destinations you scan and hit, a dialog is a sentence you read and answer. Sizing the menu
     * for a fingertip meant dialogs that shouted.
     *
     * Here rather than only in the Feed wrench, which is where it lived and is not where anybody
     * would look for it.
     */
    /**
     * Legibility — the one home for the app's readability, built for two people at once: someone who
     * wants a clean, small, open layout, and someone with a vision impairment who needs it large. Two
     * independent size dials (Reading vs Interface) so big reading text never forces giant menus, plus
     * a font pick (System / Atkinson Hyperlegible / the e-ink Fast faces). A live preview shows the
     * effect as you change it. The interface tier drives BOTH chrome keys (menus + dialogs) at once,
     * and the menu/dialog builders re-read these each time they open, so nothing clips — chrome reflows.
     */
    private fun showLegibilityDialog() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val prefs = ctx.getSharedPreferences("ledger_a11y", 0)

        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }

        // Live preview: a sample menu row + a line of body text, redrawn as font/size change.
        val previewMenu = android.widget.TextView(ctx).apply { text = "☀  Today      ❤  Daily"; setTextColor(0xFF000000.toInt()) }
        val previewBody = android.widget.TextView(ctx).apply {
            text = "The quick brown fox reads clearly."
            setTextColor(0xFF000000.toInt()); setPadding(0, px(6), 0, 0)
        }
        col.addView(android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F4F4.toInt()); cornerRadius = px(10).toFloat()
            }
            addView(previewMenu); addView(previewBody)
        })

        // The real tiers from ScreenFragment.tier — the preview must not flatter. It briefly used
        // 0.9/1.3 while the chrome used 0.85/1.25, so what you saw was almost what you got.
        fun interfaceMul() = when (prefs.getString("menu_text_size", "medium")) { "small" -> 0.85f; "large" -> 1.25f; else -> 1.0f }
        fun refreshPreview() {
            // The face that would actually be used: the reader's own pick, or the vibe's default when
            // they're still on "system" — so picking a vibe previews its font as well as its accent.
            val effective = com.toolsboox.ot.LedgerFonts.choiceById(com.toolsboox.ot.LedgerTheme.effectiveFontId(ctx))
            val tf = com.toolsboox.ot.LedgerFonts.typefaceFor(ctx, effective)
            previewMenu.typeface = tf; previewBody.typeface = tf
            // The menu sample carries the vibe's accent, so a theme pick shows its colour at once.
            previewMenu.setTextColor(com.toolsboox.ot.LedgerTheme.accent(ctx))
            previewMenu.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f * interfaceMul())
            previewBody.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * com.toolsboox.ot.ReadingSize.scale(ctx))
        }

        fun sectionLabel(title: String) = android.widget.TextView(ctx).apply {
            text = title; textSize = 12f; setTextColor(0xFF8A8A8A.toInt()); letterSpacing = 0.08f
            setPadding(0, px(16), 0, px(6))
        }

        // Selectable chips, chunked into rows of [perRow] — a single weighted row sharded ten vibe
        // names into 2-character columns. [swatches] draws an accent dot before a chip's label
        // (the vibe picker), so a theme shows its colour before it's picked.
        fun chipRow(
            chips: List<Triple<String, android.graphics.Typeface?, () -> Boolean>>,
            perRow: Int = chips.size,
            swatches: List<Int?>? = null,
            onPick: (Int) -> Unit
        ): android.view.View {
            val views = ArrayList<android.widget.TextView>()
            fun paint() = views.forEachIndexed { i, v ->
                val on = chips[i].third()
                v.setTypeface(chips[i].second, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                v.setTextColor(if (on) 0xFF000000.toInt() else 0xFF555555.toInt())
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(8).toFloat(); setColor(if (on) 0x11000000 else 0x00000000)
                    // The selected chip's ring wears the vibe's accent — read fresh on every
                    // repaint, so tapping a new vibe re-rings its own row in the new colour.
                    setStroke(px(1), if (on) com.toolsboox.ot.LedgerTheme.accent(ctx) else 0xFFCCCCCC.toInt())
                }
            }
            val outer = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            chips.indices.chunked(perRow).forEach { rowIdx ->
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                for (i in rowIdx) {
                    val (label, _, _) = chips[i]
                    row.addView(android.widget.TextView(ctx).apply {
                        text = label; textSize = 15f; maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = android.view.Gravity.CENTER
                        setPadding(px(10), px(8), px(10), px(8))
                        swatches?.getOrNull(i)?.let { c ->
                            val dot = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(c); setSize(px(10), px(10))
                            }
                            setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
                            compoundDrawablePadding = px(6)
                        }
                        setOnClickListener { onPick(i); paint(); refreshPreview() }
                        views.add(this)
                    }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { rightMargin = px(6); bottomMargin = px(6) })
                }
                // Pad the last row so its chips keep the same width as full rows.
                repeat(perRow - rowIdx.size) {
                    row.addView(android.view.View(ctx),
                        android.widget.LinearLayout.LayoutParams(0, 1, 1f).apply { rightMargin = px(6) })
                }
                outer.addView(row)
            }
            paint()
            return outer
        }

        // FONT — each chip labelled in its own face so the choice previews itself.
        col.addView(sectionLabel("FONT"))
        val fonts = com.toolsboox.ot.LedgerFonts.Choice.values().toList()
        col.addView(chipRow(
            fonts.map { c -> Triple(c.label, com.toolsboox.ot.LedgerFonts.typefaceFor(ctx, c)) { com.toolsboox.ot.LedgerFonts.current(ctx) == c } },
            perRow = 2
        ) { i -> com.toolsboox.ot.LedgerFonts.set(ctx, fonts[i].id) })

        // READING SIZE — the reading surfaces (books, feed articles, threads, semantic pages).
        col.addView(sectionLabel("READING SIZE"))
        val steps = com.toolsboox.ot.ReadingSize.STEPS.toList()
        val stepLabels = listOf("Default", "Large", "Larger", "X-Large", "Max")
        col.addView(chipRow(
            steps.mapIndexed { i, s -> Triple(stepLabels.getOrElse(i) { "${(s * 100).toInt()}%" }, null as android.graphics.Typeface?) { kotlin.math.abs(com.toolsboox.ot.ReadingSize.scale(ctx) - s) < 0.001f } }
        ) { i -> com.toolsboox.ot.ReadingSize.setScale(ctx, steps[i]) })

        // INTERFACE SIZE — menus AND dialogs together (one Chrome dial, drives both a11y keys).
        col.addView(sectionLabel("INTERFACE SIZE  ·  menus & dialogs"))
        val tiers = listOf("small" to "Small", "medium" to "Medium", "large" to "Large")
        col.addView(chipRow(
            tiers.map { (value, label) -> Triple(label, null as android.graphics.Typeface?) { (prefs.getString("menu_text_size", "medium")) == value } }
        ) { i ->
            val v = tiers[i].first
            prefs.edit().putString("menu_text_size", v).putString("modal_text_size", v).apply()
        })

        // THEME — the vibe: an accent colour plus a default reading face (used only while the font is
        // still "System"). Selecting re-tints the preview immediately; the chrome catches up on reopen.
        col.addView(sectionLabel("THEME"))
        val vibes = com.toolsboox.ot.LedgerTheme.ALL
        col.addView(chipRow(
            vibes.map { v -> Triple(v.name, null as android.graphics.Typeface?) { com.toolsboox.ot.LedgerTheme.current(ctx).id == v.id } },
            perRow = 3,
            // Dialogs are light cards even in Dark scheme, so the light accent is the legible one.
            swatches = vibes.map { it.accentLight }
        ) { i -> com.toolsboox.ot.LedgerTheme.setTheme(ctx, vibes[i].id) })

        // MODE — light / dark / follow-system. This flips the whole app's colour resources, so it takes
        // a recreate to redraw the chrome; the dialog is torn down with it, which is fine (choice is saved).
        col.addView(sectionLabel("MODE  ·  light & dark"))
        val schemes = listOf(0 to "System", 1 to "Light", 2 to "Dark")
        col.addView(chipRow(
            schemes.map { (n, label) -> Triple(label, null as android.graphics.Typeface?) { com.toolsboox.ot.LedgerTheme.scheme(ctx) == n } }
        ) { i ->
            com.toolsboox.ot.LedgerTheme.setScheme(ctx, schemes[i].first)
            com.toolsboox.ot.LedgerTheme.applyNightMode(ctx)
            requireActivity().recreate()
        })

        // MODAL SIZE — how large dialogs sit. The scale logic itself lives elsewhere (ModalScale side);
        // here we only persist the choice under "modal_size" (compact / standard / expanded).
        col.addView(sectionLabel("MODAL SIZE  ·  dialogs"))
        val modalSizes = listOf("compact" to "Compact", "standard" to "Standard", "expanded" to "Expanded")
        col.addView(chipRow(
            modalSizes.map { (value, label) -> Triple(label, null as android.graphics.Typeface?) { (prefs.getString("modal_size", "standard")) == value } }
        ) { i -> prefs.edit().putString("modal_size", modalSizes[i].first).apply() })

        refreshPreview()

        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Legibility")
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Done", null)
            .show()
    }

}
