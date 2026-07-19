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

        // Floating-pill orientation (shared by every Ledger pill via the ledger_widgets pref).
        val widgetPrefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        val narrow = resources.configuration.screenWidthDp < 520
        binding.pillOrientationSwitch.isChecked = widgetPrefs.getBoolean("vertical", narrow)
        binding.pillOrientationSwitch.setOnCheckedChangeListener { _, checked ->
            widgetPrefs.edit().putBoolean("vertical", checked).apply()
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

            // (Google Calendar + on-device extract were persisted early, above.)

            // Community bridge (FluentCommunity) creds — moved here from Boards → Web bridge
            // so all connection settings live in one place.
            val bridgeSite = binding.communitySiteInput.text?.toString()?.trim().orEmpty()
            val bridgeUser = binding.communityUserInput.text?.toString()?.trim().orEmpty()
            // WordPress application passwords display in spaced groups ("abcd efgh …") but the
            // real secret has no spaces — strip them so a cut-and-paste of either form works.
            val bridgePass = binding.communityPassInput.text?.toString().orEmpty().replace(" ", "")
            com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.saveConfig(
                requireContext(),
                com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.Config(bridgeSite, bridgeUser, bridgePass)
            )
            // Same site/user/pass also drive the Boards bridge (gram pinning); board ID is its own.
            val bridgeBoard = binding.communityBoardInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
            com.toolsboox.plugin.calendar.nw.LedgerWebBridge.saveConfig(
                requireContext(),
                com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config(bridgeSite, bridgeUser, bridgePass, bridgeBoard)
            )

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
}
