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

    /** Picks a settings JSON to import (finger-friendly; any file type so JSON always shows).
     *  A file carrying the `encryptedSecrets` envelope prompts for its passphrase first; a legacy
     *  plaintext backup imports straight through, exactly as before. */
    private val importSettingsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val json = requireContext().contentResolver.openInputStream(uri)!!
                .bufferedReader().use { it.readText() }
            if (com.toolsboox.plugin.calendar.ot.SettingsBackup.hasEncryptedSecrets(json)) {
                promptImportPassphrase { pass -> applySettingsImport(json, pass) }
            } else {
                applySettingsImport(json, null)
            }
        } catch (e: Exception) {
            Timber.w(e, "settings import failed")
            showMessage("Import failed", binding.root)
        }
    }

    /** Apply a settings JSON (passphrase already collected if the file needed one) and report —
     *  a wrong passphrase is a CLEAR message, and the non-secret fields land either way. */
    private fun applySettingsImport(json: String, passphrase: String?) {
        try {
            val result = com.toolsboox.plugin.calendar.ot.SettingsBackup.importJson(requireContext(), json, passphrase)
            if (result.applied.contains("webdav")) {
                sharedPreferences.edit().putBoolean("ultrabridgeEnabled", true).apply()
                val ub = getUltrabridgeEncryptedPrefs()
                binding.ultrabridgeUrlInput.setText(ub.getString("ultrabridge_webdav_url", ""))
                binding.ultrabridgeUserInput.setText(ub.getString("ultrabridge_webdav_user", ""))
                binding.ultrabridgePassInput.setText(ub.getString("ultrabridge_webdav_pass", ""))
                binding.ultrabridgeEnableSwitch.isChecked = true
            }
            val imported = if (result.applied.isEmpty()) "nothing else to import"
                else "imported: ${result.applied.joinToString(", ")}"
            showMessage(
                when {
                    result.wrongPassphrase -> "Wrong passphrase — passwords skipped; $imported"
                    result.hadEncryptedSecrets && !result.secretsUnlocked -> "Passwords skipped; $imported"
                    result.applied.isEmpty() -> "Nothing to import"
                    else -> "Imported: ${result.applied.joinToString(", ")}"
                },
                binding.root
            )
        } catch (e: Exception) {
            Timber.w(e, "settings import failed")
            showMessage("Import failed", binding.root)
        }
    }

    /**
     * Picks a names/structure JSON to import. Same picker, same "any file type so JSON always
     * shows" reasoning as the settings one above.
     *
     * The message is [NamesBackup.Restored.summary], not a flat "Imported": this import MERGES, so
     * the only interesting number is what it actually added, and a device that was already up to
     * date has to be able to say so. Anything it could not use is logged, never guessed at.
     */
    private val importNamesLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val json = requireContext().contentResolver.openInputStream(uri)!!
                .bufferedReader().use { it.readText() }
            val restored = com.toolsboox.plugin.calendar.ot.NamesBackup.importJson(requireContext(), json)
            showMessage(restored.summary(), binding.root)
        } catch (e: Exception) {
            Timber.w(e, "names import failed")
            showMessage("Import failed", binding.root)
        }
    }

    /**
     * The settings JSON staged for the share sheet, deleted in [onResume] — the "finally" of the
     * share flow. startActivity(chooser) pauses this fragment; whatever happens in the share
     * sheet (sent, saved, backed out), control returns through onResume, so the file's lifetime
     * is exactly the share sheet's. It used to sit in cacheDir/exports forever — a creds file
     * parked on disk (cleartext then; even encrypted it has no business outliving the share).
     */
    private var stagedSettingsExport: java.io.File? = null

    /** Stage the settings JSON (secrets enveloped, or omitted when [passphrase] is null) and hand
     *  it to the share sheet. Any stale staged file from an earlier run is deleted FIRST; a
     *  failure after the write deletes the fresh one before rethrowing. */
    private fun shareSettingsExport(passphrase: String?) {
        try {
            // Must live under a FileProvider-declared root (res/xml/file_paths.xml). "exports/"
            // is the declared share-sheet cache dir; "settings/" was never declared, so
            // getUriForFile threw "Failed to find configured root" and export silently failed.
            val dir = java.io.File(requireContext().cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(dir, "ledger-settings.json")
            file.delete()   // a stale copy from an earlier (possibly cleartext-era) export
            try {
                val json = com.toolsboox.plugin.calendar.ot.SettingsBackup.exportJson(requireContext(), passphrase)
                file.writeText(json)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file
                )
                val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Ledger settings")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                stagedSettingsExport = file
                startActivity(android.content.Intent.createChooser(share, getString(R.string.calendar_settings_button_export_settings)))
            } catch (e: Exception) {
                file.delete()
                stagedSettingsExport = null
                throw e
            }
        } catch (e: Exception) {
            Timber.w(e, "settings export failed")
            showMessage("Export failed", binding.root)
        }
    }

    /**
     * Passphrase + confirm for an export. Typed-only dialog, so the platform buttons are fine
     * (the buttons-on-top rule is for handwriting panels). Empty BOTH fields = export with no
     * secrets at all, and the message says so rather than leaving blank to mean something silent.
     * A mismatch re-opens the dialog instead of exporting something other than what was typed;
     * Cancel exports nothing.
     */
    private fun promptExportPassphrase(onReady: (String?) -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun passwordField(hintText: String) = android.widget.EditText(ctx).apply {
            hint = hintText
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pass = passwordField("Passphrase")
        val confirm = passwordField("Confirm passphrase")
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            addView(pass); addView(confirm)
        }
        // Guarded: a passphrase being typed is work — a stray touch outside must not throw it
        // away mid-entry.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Protect your passwords")
            .setMessage("Passwords, tokens and API keys are encrypted with this passphrase — you'll type it again when importing. Leave BOTH fields blank to export with no secrets at all.")
            .setView(col)
            .setPositiveButton("Export") { _, _ ->
                val p1 = pass.text?.toString() ?: ""
                val p2 = confirm.text?.toString() ?: ""
                if (p1 != p2) {
                    Toast.makeText(ctx, "Passphrases didn't match — try again", Toast.LENGTH_LONG).show()
                    promptExportPassphrase(onReady)
                } else {
                    onReady(if (p1.isEmpty()) null else p1)
                }
            }
            .setNegativeButton("Cancel", null)
            .create())
    }

    /**
     * Passphrase for an import whose file carries the `encryptedSecrets` envelope. "Skip secrets"
     * is an explicit choice, not a hidden default: the non-secret fields import either way, and
     * a wrong passphrase reports itself clearly (see [applySettingsImport]).
     */
    private fun promptImportPassphrase(onReady: (String?) -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val pass = android.widget.EditText(ctx).apply {
            hint = "Passphrase"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            addView(pass)
        }
        // Guarded: same as the export prompt — the passphrase mid-entry must not be lost to a
        // stray touch outside.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Backup is passphrase-protected")
            .setMessage("This backup's passwords and keys are encrypted. Enter the passphrase it was exported with, or skip to import everything except the secrets.")
            .setView(col)
            .setPositiveButton("Unlock") { _, _ -> onReady(pass.text?.toString() ?: "") }
            .setNegativeButton("Skip secrets") { _, _ -> onReady(null) }
            .create())
    }

    /** The passphrase collected (or explicitly left blank) BEFORE [backupCreateLauncher] runs —
     *  the create-document picker sits between the prompt and the write, so it rides a field. */
    private var backupPassphrase: String? = null

    /** FULL ledger backup: zip the entire external Documents tree into the file the user picked.
     *  APFS-style cheap it is not, but a personal ledger zips in seconds on device. */
    private val backupCreateLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val passphrase = backupPassphrase
        backupPassphrase = null
        uri ?: return@registerForActivityResult
        val ctx = requireContext()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                val root = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!
                else java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "toolsBoox")
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
                        // Settings ride inside the backup — secrets under the passphrase
                        // envelope (or absent entirely when the passphrase was left blank),
                        // never as cleartext inside a zip parked on a drive.
                        zip.putNextEntry(java.util.zip.ZipEntry("ledger-settings.json"))
                        zip.write(com.toolsboox.plugin.calendar.ot.SettingsBackup.exportJson(ctx, passphrase).toByteArray())
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
            var settingsJson: String? = null
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
                                    // Held for AFTER the unzip: applying may need a passphrase
                                    // prompt, and a dialog cannot block the middle of a stream.
                                    settingsJson = zip.readBytes().toString(Charsets.UTF_8)
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
                settingsJson?.let { json ->
                    runCatching {
                        if (com.toolsboox.plugin.calendar.ot.SettingsBackup.hasEncryptedSecrets(json)) {
                            promptImportPassphrase { pass -> applySettingsImport(json, pass) }
                        } else {
                            com.toolsboox.plugin.calendar.ot.SettingsBackup.importJson(ctx, json, null)
                        }
                    }.onFailure { Timber.w(it, "backup settings import failed") }
                }
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

        // Set the ten section headings ONCE, here rather than in onResume: [styleSectionHeadings]
        // rewrites each label into a two-line spanned form, and re-running it over its own output
        // would find no "·" left to split on and swallow the whole heading into the name line.
        // onViewCreated fires exactly once per inflated view, which is the guarantee this needs.
        styleSectionHeadings()

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

        // The "finally" of the settings-export share: the chooser paused this fragment, and any
        // way out of it — sent, saved, dismissed — resumes it here, so the staged creds file is
        // deleted the moment control returns. (A share target that defers its read past this
        // point loses the stream, which is the acceptable edge of not parking secrets on disk.)
        stagedSettingsExport?.let { f ->
            runCatching { f.delete() }
            stagedSettingsExport = null
        }

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
        updateRotationCycleVisibility(binding.autoRotateSwitch.isChecked)
        binding.autoRotateSwitch.setOnCheckedChangeListener { _, checked ->
            sharedPreferences.edit().putBoolean("autoRotate", checked).apply()
            updateRotationCycleVisibility(checked)
            // Through `apply`, which records the orientation as well as setting it. The switch used
            // to write only the boolean, and the boolean alone cannot restore a LOCKED screen: it
            // says "not the gyro" without saying which way up. Turning the gyro off therefore held
            // until the app closed and then came back on by itself.
            com.toolsboox.ot.ScreenRotation.apply(
                requireActivity(),
                if (checked) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                else com.toolsboox.ot.ScreenRotation.displayedBy(
                    @Suppress("DEPRECATION")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                        requireContext().display?.rotation ?: 0
                    else requireActivity().windowManager.defaultDisplay.rotation
                )
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
        // share to another device; import applies one. Same schema as the iPad. The passphrase
        // dialog comes first: secrets ride encrypted (or not at all) — never cleartext.
        binding.buttonExportSettings.setOnClickListener {
            promptExportPassphrase { pass -> shareSettingsExport(pass) }
        }
        binding.buttonImportSettings.setOnClickListener { importSettingsLauncher.launch("*/*") }

        // Names & structure: the same share-a-JSON idiom as the settings pair directly above,
        // deliberately — see [NamesBackup] for what it carries and, more importantly, what it does
        // not.
        //
        // The inventory rides the share SUBJECT rather than a snackbar, which is the only place it
        // can actually be read: the chooser goes up in the same breath and covers a
        // Snackbar.LENGTH_LONG, whereas the subject is the title of the share sheet's own preview.
        // Worth saying at all because the written-title count is the one figure that can surprise
        // anyone about the size of the file they are about to mail themselves — names are bytes,
        // faces are kilobytes each.
        binding.buttonExportNames.setOnClickListener {
            try {
                val ctx = requireContext()
                val json = com.toolsboox.plugin.calendar.ot.NamesBackup.exportJson(ctx)
                // Same FileProvider-declared "exports/" root the settings export uses; a directory
                // that isn't declared in res/xml/file_paths.xml throws "Failed to find configured
                // root" and the share silently never happens.
                val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
                val file = java.io.File(dir, "ledger-names.json").apply { writeText(json) }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", file
                )
                val inventory = com.toolsboox.plugin.calendar.ot.NamesBackup.inventory(ctx)
                val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .putExtra(
                        android.content.Intent.EXTRA_SUBJECT,
                        "Ledger names & structure — $inventory · ${file.length() / 1024} KB"
                    )
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(android.content.Intent.createChooser(
                    share, getString(R.string.calendar_settings_button_export_names)))
            } catch (e: Exception) {
                Timber.w(e, "names export failed")
                showMessage("Export failed", binding.root)
            }
        }
        binding.buttonImportNames.setOnClickListener { importNamesLauncher.launch("*/*") }

        // "Import backup" was a dead button (no handler); it now restores a full-ledger backup zip —
        // exactly what its label says, and the same action that was otherwise hidden on the long-press
        // of Import settings below. Pairs with "Export backup".
        binding.buttonRestore.setOnClickListener {
            backupRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        // No runtime relabel here any more. The button carried TWO names — the resource said
        // "Text size…" and this line overwrote it with "Legibility (font & size)" — which meant
        // the honest name existed only at runtime and the string anyone would grep for was the
        // stale one. calendar_settings_button_text_size now says it in the resource.
        binding.buttonTextSize.setOnClickListener { showLegibilityDialog() }

        // FULL ledger backup/restore (data, not just settings): long-press Export = zip the whole
        // Documents tree (every day page, contact, board, clipping) to a file you pick; long-press
        // Import = restore a backup zip over it. Settings JSON is included in the zip — which is
        // why the passphrase dialog comes first here too: the embedded copy carries the same
        // secrets the standalone export does.
        binding.buttonExportSettings.setOnLongClickListener {
            promptExportPassphrase { pass ->
                backupPassphrase = pass
                val df = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
                backupCreateLauncher.launch("ledger-backup-${df.format(java.util.Date())}.zip")
            }
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
     * Give the ten section headings the app's own hand: a glyph, the section's NAME in bold mono
     * caps, and its gloss on a second line at four-fifths the size.
     *
     * The glyphs are not decoration and none of them are new — every one is lifted from the row it
     * already labels in the ▦ directory and the day page's Go menu (LedgerDirectory / CalendarDay-
     * Fragment): ☁ is Cloud sync, 🖥 is Site accounts, ⚙ is All settings, 🔄 is Rotate screen, ✒ is
     * Notes, 🔤 is the OCR model picker. So the section that governs your sites wears the same mark
     * as the row that opens them, and Settings stops being the one surface with a private
     * vocabulary. They are carried in the label text rather than as drawableStart because these
     * are emoji, not vector assets, and ScreenFragment.showAccordion already does exactly this
     * (its `glyphLabel`, down to the 1.3-ish RelativeSizeSpan that keeps a glyph from shrinking
     * into the line beside it).
     *
     * The two-line break is the point of the whole method. Each heading string is "NAME  ·  gloss"
     * — a short stamped label plus a human sentence — and set as one run of mono caps it wrapped
     * wherever the column happened to end, which put the break mid-gloss on a Tab and mid-NAME on
     * a Palma. Splitting on the separator the strings already carry makes the break deliberate and
     * identical on every panel, and it lets the two halves be typeset as what they are: the name
     * bold, the gloss plain and smaller. No colour is spent doing it, because there is none to
     * spend — this screen is read on a monochrome panel where a grey subtitle is either invisible
     * or indistinguishable from the heading above it.
     *
     * Strings are read from the views, never rewritten, so the nine renamings from the earlier pass
     * (and the translations that trail them) stay the single source of these words.
     */
    private fun styleSectionHeadings() {
        listOf(
            binding.lookSectionLabel to "🔤",
            binding.rotationSectionLabel to "🔄",
            binding.calendarSectionLabel to "🗓",
            binding.captureSectionLabel to "✒",
            binding.autoSyncSectionLabel to "☁",
            binding.ultrabridgeSectionLabel to "📄",
            binding.gcalSectionLabel to "☑",
            binding.communitySectionLabel to "🖥",
            binding.dataSectionLabel to "🗃",
            binding.deviceSectionLabel to "⚙"
        ).forEach { (label, glyph) -> label.text = sectionHeading(glyph, label.text.toString()) }
    }

    /**
     * Build one heading: "glyph  NAME" bold on line one, the gloss plain and smaller on line two.
     *
     * A heading with no "·" (a translation that dropped it, or a future section that never had one)
     * keeps its whole text as the name and simply gets no second line — the split is opportunistic,
     * never required, because half the locale files still carry English-derived strings for the
     * renamed keys and none of them should be able to make a heading vanish.
     *
     * The name is bolded by a span rather than by the style, and Settings.SectionHeading is
     * deliberately NOT bold: StyleSpan ORs styles onto the base, so there is no span that can take
     * bold back OFF a bold TextView, and TypefaceSpan's Typeface constructor is API 28 against a
     * minSdk of 26. Starting light and adding weight is the only direction that works everywhere.
     */
    private fun sectionHeading(glyph: String, raw: String): CharSequence {
        val separator = raw.indexOf('·')
        val name = (if (separator < 0) raw else raw.substring(0, separator)).trim()
        val gloss = if (separator < 0) "" else raw.substring(separator + 1).trim()

        // Two spaces after the glyph, as everywhere else in the app ("🌿  Roots", "🗺  Map") — one
        // leaves the mark touching the word at these letter-spacings.
        val head = if (glyph.isBlank()) name else "$glyph  $name"
        val full = if (gloss.isEmpty()) head else "$head\n$gloss"
        val styled = android.text.SpannableString(full)

        if (glyph.isNotBlank()) {
            styled.setSpan(android.text.style.RelativeSizeSpan(1.25f), 0, glyph.length, 0)
        }
        styled.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, head.length, 0
        )
        if (gloss.isNotEmpty()) {
            styled.setSpan(
                android.text.style.RelativeSizeSpan(0.8f), head.length + 1, full.length, 0
            )
        }
        return styled
    }

    /**
     * Show or hide the auto-sync interval spinner based on the switch state.
     *
     * Both halves are set on every call, because the section has two drawn states and not one
     * drawn state plus a hole: the framed group when the switch is on, the dashed fold standing in
     * its place when it is off. See the layout's comment above the four destination blocks for why
     * an empty gap was the wrong answer — and note the two are near enough the same height that
     * flipping the switch does not shove the rest of the page down a screen, which on e-ink is the
     * difference between a local update and a full-panel flash.
     */
    private fun updateAutoSyncIntervalVisibility() {
        binding.autoSyncFieldsGroup.visibility = if (autoSyncEnabled) View.VISIBLE else View.GONE
        binding.autoSyncFold.visibility = if (autoSyncEnabled) View.GONE else View.VISIBLE
    }

    /**
     * Show or hide the manual rotation cycle (the hint plus four orientation tick-boxes) according
     * to whether the gyro has the screen.
     *
     * Those boxes write `rotationOrientationMask`, and the mask has exactly one reader:
     * [com.toolsboox.ui.plugin.ScreenFragment.stepScreenOrientation], the toolbar rotate BUTTON's
     * step. Hand the screen to the sensor and the button's cycle is not consulted at all — so the
     * four boxes sat there looking live and answering nothing, which is the same "I changed it and
     * nothing moved" trap the old MODAL SIZE label set. Hiding is deliberately visibility-only:
     * the mask is left exactly as it was, so switching the gyro back off restores the cycle that
     * was already configured rather than resetting it to all four.
     *
     * @param autoRotate true when the gyro is deciding
     */
    private fun updateRotationCycleVisibility(autoRotate: Boolean) {
        binding.rotationManualGroup.visibility = if (autoRotate) View.GONE else View.VISIBLE
    }

    /**
     * Show or hide the Ultrabridge WebDAV fields based on the enable switch state.
     *
     * One view each way now, not three: the three credential boxes live inside the framed group,
     * so the frame IS the thing that appears and disappears. Toggling them individually left the
     * frame to be drawn around nothing whenever a later field was hidden by other means, and it
     * meant the block's two states had to be kept in step in three places.
     */
    private fun updateUltrabridgeFieldsVisibility(enabled: Boolean) {
        binding.ultrabridgeFieldsGroup.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.ultrabridgeFold.visibility = if (enabled) View.GONE else View.VISIBLE
        showLastMirrorResult(enabled)
    }

    /**
     * Report how the last automatic day-mirror pass went, right under the WebDAV switch.
     *
     * The outage this answers ran for three days in total silence: every pass aborted, the worker
     * logged one warn line and returned success, and the sidecar surfaces (text notes, pickings,
     * attachments) kept syncing — so nothing a person could see suggested the day pages had stopped
     * moving between devices at all. The age is shown alongside the outcome deliberately: "ok" from
     * four days ago is as much of a warning as an outright failure, and only one of those two states
     * is visible if you print the outcome on its own.
     */
    private fun showLastMirrorResult(enabled: Boolean) {
        val view = binding.ultrabridgeLastResult
        val prefs = requireContext().getSharedPreferences("MAIN", android.content.Context.MODE_PRIVATE)
        val result = prefs.getString(UltrabridgeSyncWorker.PREF_LAST_MIRROR_RESULT, null)
        val at = prefs.getLong(UltrabridgeSyncWorker.PREF_LAST_MIRROR_AT, 0L)
        if (!enabled || result == null || at <= 0L) {
            view.visibility = View.GONE
            return
        }
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(
            at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        )
        val failed = result.startsWith("failed") || result.startsWith("partial")
        view.text = "Last sync $ago — $result"
        view.setTextColor(if (failed) 0xFF000000.toInt() else 0xFF555555.toInt())
        // E-ink: a failure reads as weight, not colour — an orange warning is a mid-grey smear on
        // this screen, and mid-greys are exactly the tone that vanishes here.
        view.typeface = if (failed) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        view.visibility = View.VISIBLE
    }

    /**
     * Show or hide the Google Calendar fields (target calendar + connect) based on the enable switch.
     */
    private fun updateGcalFieldsVisibility(enabled: Boolean) {
        binding.gcalFieldsGroup.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.gcalFold.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    /**
     * Show or hide the Community / Boards bridge fields (site, user, pass, board) based on the
     * enable switch. Hiding the GROUP via GONE keeps every field's text intact exactly as hiding
     * them one by one did, so Save still persists what was typed before the block was folded.
     */
    private fun updateCommunityFieldsVisibility(enabled: Boolean) {
        binding.communityFieldsGroup.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.communityFold.visibility = if (enabled) View.GONE else View.VISIBLE
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
        // A handle on THIS dialog, so a control that changes which rows exist (the link switch,
        // which adds or removes the nav and pill dials) can close it before drawing the new one.
        // Without the dismiss it would simply stack a second Legibility panel over the first.
        var thisDialog: androidx.appcompat.app.AlertDialog? = null

        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }

        // A dashed hairline, the app's mark for a division WITHIN a group (LedgerContextMenu:
        // "solid = structure, dashed = strips"). Software layer or the dashes are silently dropped
        // when the view is composited into a hardware layer — the same flag, for the same reason,
        // that LedgerContextMenu.DashRule sets.
        fun dashRule() = android.view.View(ctx).apply {
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.LINE
                setStroke(px(2), 0xFF000000.toInt(), px(6).toFloat(), px(6).toFloat())
            }
        }

        // The tag over each preview band. Mono caps at 10sp, worded to MATCH the section label of
        // the dial that moves it further down the panel — "TOP NAV" over the strip, "READING" over
        // the paragraph — because that pairing is the whole reason the preview exists. Five dials
        // is five things to try one at a time when you cannot tell which one owns the thing that
        // is annoying you; naming each sample after its dial turns that into one glance.
        fun bandTag(text: String) = android.widget.TextView(ctx).apply {
            this.text = text
            textSize = 10f; letterSpacing = 0.16f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setTextColor(0xFF000000.toInt())
            setPadding(0, px(9), 0, px(3))
        }

        // LIVE PREVIEW — four bands, one per dial, at the sizes those dials actually produce.
        //
        // It was two lines: a menu row and one sentence. That covered two of the five dials, and it
        // drew the menu row in LedgerTheme.accent — which on the monochrome Boox this is read on
        // resolves to a GREY, so the one sample whose entire job was to demonstrate legibility was
        // demonstrated at deliberately reduced contrast. Everything here is now full black, which
        // is what a menu row is actually drawn in; the accent survives on the card's frame, where
        // it is a signature and degrades to a grey line harmlessly.
        //
        // Bands, not a list: each is tagged, and they are separated by dashed rules inside a single
        // 2dp black frame — the LedgerContextMenu card, which is this app's word for "a panel". The
        // reading band carries two lines because a face cannot be judged from one; the difference
        // between Fast Serif and Atkinson is in how a second line sits under the first.
        val previewNav = android.widget.TextView(ctx).apply {
            text = "‹    2026  ·  JUL  ·  WED 29    ›"; setTextColor(0xFF000000.toInt())
        }
        val previewMenu = android.widget.TextView(ctx).apply { text = "☀  Today      ❤  Daily"; setTextColor(0xFF000000.toInt()) }
        val previewBody = android.widget.TextView(ctx).apply {
            text = "The quick brown fox reads clearly.\nA second line, to judge a face by."
            setTextColor(0xFF000000.toInt()); setLineSpacing(px(2).toFloat(), 1f)
        }
        // The pill sample is a BOX, not a word: PILL SIZE moves the floating pen button and the
        // page pills, whose size is a physical target for a fingertip and cannot be read off a
        // font. Drawn at the real base (ledger_pill_button, 38dp) times the real scale, so what is
        // in the card is the size the thing will be.
        val previewPill = android.view.View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt()); setStroke(px(2), 0xFF000000.toInt())
                cornerRadius = px(8).toFloat()
            }
        }
        val previewPillRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(previewPill, android.widget.LinearLayout.LayoutParams(px(38), px(38)))
        }

        col.addView(android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(14), px(4), px(14), px(14))
            // Square corners and a 2dp stroke: a panel, the same object the drawn pages and the
            // long-press menu are built from. The vibe's accent rings it — the one place in this
            // dialog the accent is spent, matching how showAccordion frames an open folder, and
            // nothing inside depends on it being visible.
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                setStroke(px(2), com.toolsboox.ot.LedgerTheme.accent(ctx))
            }
            // Fresh params per rule: a LayoutParams object belongs to exactly one view, and handing
            // the same instance to three of them makes any later measure pass write through all
            // three at once.
            fun ruleParams() = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, px(12))
            addView(bandTag("TOP NAV")); addView(previewNav)
            addView(dashRule(), ruleParams())
            addView(bandTag("MENUS & DIALOGS")); addView(previewMenu)
            addView(dashRule(), ruleParams())
            addView(bandTag("READING")); addView(previewBody)
            addView(dashRule(), ruleParams())
            addView(bandTag("PILLS & PEN")); addView(previewPillRow)
        })

        // The real tiers from ScreenFragment.tier — the preview must not flatter. It briefly used
        // 0.9/1.3 while the chrome used 0.85/1.25, so what you saw was almost what you got.
        fun interfaceMul() = when (prefs.getString("menu_text_size", "medium")) { "small" -> 0.85f; "large" -> 1.25f; else -> 1.0f }
        fun refreshPreview() {
            // The face that would actually be used: the reader's own pick, or the vibe's default when
            // they're still on "system" — so picking a vibe previews its font as well as its accent.
            val effective = com.toolsboox.ot.LedgerFonts.choiceById(com.toolsboox.ot.LedgerTheme.effectiveFontId(ctx))
            val tf = com.toolsboox.ot.LedgerFonts.typefaceFor(ctx, effective)
            previewNav.typeface = tf; previewMenu.typeface = tf; previewBody.typeface = tf
            // Every scale here is read from the SAME function the surface itself calls, never a
            // number retyped to match — ModalScale.stripScale is what NavigatorRenderer asks, and
            // ModalScale.pillScale is what the pills ask. A preview that keeps its own copy of the
            // arithmetic is a preview that will quietly stop agreeing with the app.
            previewNav.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                15f * com.toolsboox.ot.ModalScale.stripScale(ctx))
            previewMenu.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f * interfaceMul())
            previewBody.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * com.toolsboox.ot.ReadingSize.scale(ctx))
            val pill = (px(38) * com.toolsboox.ot.ModalScale.pillScale(ctx)).toInt()
            previewPill.layoutParams = android.widget.LinearLayout.LayoutParams(pill, pill)
            previewPill.requestLayout()
        }

        // A panel title inside the dialog: mono caps over a solid rule, which is the header
        // LedgerContextMenu draws and the header the intake page draws over each of its quarters.
        // It was 12sp of #8A8A8A — the grey that is simply not there on an e-ink panel, so the nine
        // labels dividing this dialog into sections were the least visible thing in it. Black at
        // the same size, with the rule doing the separating, costs no space and cannot fade.
        fun sectionLabel(title: String) = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(ctx).apply {
                text = title; textSize = 12f; letterSpacing = 0.14f
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                setTextColor(0xFF000000.toInt())
                setPadding(0, px(18), 0, px(5))
            })
            addView(android.view.View(ctx).apply { setBackgroundColor(0xFF000000.toInt()) },
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, px(2)))
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
            // SELECTION IS AN INVERSION: the chosen chip is a solid black box with white text, the
            // rest are white boxes with black text and a 2dp outline.
            //
            // It used to be a 0x11000000 fill (black at 6.7%) behind an accent ring, with the
            // unselected labels at #555. All three of those are the same mistake in three costumes:
            // an alpha fill dithers into mud on e-ink rather than reading as a tint, the accent is
            // a grey on a monochrome Boox, and #555 sits in the dead band where a grey is neither
            // legibly lighter than black nor distinguishable from it. So "which one is on?" was
            // being carried entirely by the bold, in a row of ten short words. Inverting is the one
            // answer this panel can draw at full strength, and it is what LedgerContextMenu already
            // uses for a pressed row — the app's own way of saying "this one".
            fun paint() = views.forEachIndexed { i, v ->
                val on = chips[i].third()
                v.setTypeface(chips[i].second, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                v.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(8).toFloat()
                    setColor(if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                    setStroke(px(2), 0xFF000000.toInt())
                }
                // The vibe swatch is rebuilt on every repaint rather than once at construction,
                // because the chip underneath it now changes colour: a dark accent dot is legible
                // on a white chip and gone on the selected black one. The white ring solves both
                // ends at once — invisible against the unselected chip, and the thing that cuts the
                // dot out of the selected one. On a monochrome panel, where every accent is some
                // grey, that ring is also what keeps the dot a token rather than a smudge.
                swatches?.getOrNull(i)?.let { swatch ->
                    val dot = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(swatch); setSize(px(14), px(14))
                        setStroke(px(2), 0xFFFFFFFF.toInt())
                    }
                    v.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
                    v.compoundDrawablePadding = px(6)
                }
            }
            val outer = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                // Clear of the solid rule that closes every sectionLabel above it.
                setPadding(0, px(9), 0, 0)
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
                        // Taller than it was: a chip is now a box with a real edge, and a box needs
                        // room around its word or the outline reads as a hairline around the text
                        // rather than as a target. It is also the smallest thing on this page that
                        // gets tapped with a stylus.
                        setPadding(px(10), px(11), px(10), px(11))
                        // (The swatch dot is drawn in paint(), not here — it has to be rebuilt each
                        // time selection moves, since the chip beneath it inverts.)
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

        // The panel runs STYLE first, then SIZE — and it used to interleave them: font, two size
        // dials, theme, mode, three more size dials. Nine rows in no order is nine decisions to
        // re-read every time; grouped, it is "what does it look like" followed by "how big is it",
        // and the size block ends with the Linked switch that collapses the last three rows into
        // one. Nothing here changed what it does, only where it sits and what it is called.

        // FONT — each chip labelled in its own face so the choice previews itself.
        col.addView(sectionLabel("FONT"))
        val fonts = com.toolsboox.ot.LedgerFonts.Choice.values().toList()
        col.addView(chipRow(
            fonts.map { c -> Triple(c.label, com.toolsboox.ot.LedgerFonts.typefaceFor(ctx, c)) { com.toolsboox.ot.LedgerFonts.current(ctx) == c } },
            perRow = 2
        ) { i -> com.toolsboox.ot.LedgerFonts.set(ctx, fonts[i].id) })

        // THEME — the vibe: an accent colour plus a default reading face (used only while the font is
        // still "System"). Selecting re-tints the preview immediately; the chrome catches up on reopen.
        col.addView(sectionLabel("THEME  ·  accent colour"))
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

        // READING SIZE — the reading surfaces (books, feed articles, threads, semantic pages).
        // First of the size dials because it is the one that matters most and the one that has
        // nothing to do with chrome: making an article readable should never be a decision about
        // menus, which is exactly what a single shared dial used to make it.
        col.addView(sectionLabel("READING SIZE  ·  books, articles & threads"))
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

        // MODAL SIZE — how large dialogs sit. The scale logic itself lives elsewhere (ModalScale side);
        // here we only persist the choice under "modal_size" (compact / standard / expanded).
        // Named for what it actually moves. "Dialogs" was wrong twice over: this dial never
        // touched an AlertDialog's type (that's `modal_text_size`, the row above), and what it
        // DOES move is the pop-up navigation panels — the page-switcher, the ▦ directory drawer,
        // and the slide-out trays. A label that misnames its own effect makes the setting feel
        // broken, because you change it and the thing you were looking at doesn't move.
        col.addView(sectionLabel("NAV MODALS  ·  page-switcher, directory, trays"))
        val modalSizes = listOf("compact" to "Compact", "standard" to "Standard", "expanded" to "Expanded")
        col.addView(chipRow(
            modalSizes.map { (value, label) -> Triple(label, null as android.graphics.Typeface?) { (prefs.getString("modal_size", "standard")) == value } }
        ) { i -> prefs.edit().putString("modal_size", modalSizes[i].first).apply() })

        // SIZE EVERYTHING TOGETHER — the switch that keeps this section a single choice.
        //
        // Three surfaces genuinely want three dials: dialogs grow into free screen, the nav strip
        // is boxed at 1404 and cannot reflow, and the pills are calibrated against the reading
        // gutter. But three rows is three decisions for everyone, including the many people who
        // just want things bigger. So the default is linked — one dial moves all three — and the
        // other two rows appear only when this is turned off. Off pins each to what it shows right
        // now, so flipping the switch never resizes anything; it only makes the rows editable.
        col.addView(sectionLabel("SIZE EVERYTHING TOGETHER"))
        val linkOptions = listOf(true to "Linked", false to "Separate")
        col.addView(chipRow(
            linkOptions.map { (linked, label) ->
                Triple(label, null as android.graphics.Typeface?) {
                    com.toolsboox.ot.ModalScale.sizesLinked(ctx) == linked
                }
            }
        ) { i ->
            if (linkOptions[i].first) com.toolsboox.ot.ModalScale.linkSizes(ctx)
            else com.toolsboox.ot.ModalScale.unlinkSizes(ctx)
            // The two rows below appear/disappear with this, so the section has to be redrawn —
            // close this panel first, or the new one lands on top of it.
            thisDialog?.dismiss()
            showLegibilityDialog()
        })

        if (!com.toolsboox.ot.ModalScale.sizesLinked(ctx)) {
            // TOP NAV SIZE — the date strip across the top, on its OWN dial.
            //
            // It used to ride the modal dial, which coupled two things with opposite constraints:
            // a floating dialog can grow into free screen, the strip cannot. So sizing dialogs up
            // pushed the nav past its own box and its labels went to "…". Note the strip ALSO
            // fits itself now (NavigatorRenderer measures before it draws), so the worst this row
            // can do is ask for more than fits and silently get the largest size that does.
            col.addView(sectionLabel("TOP NAV SIZE  ·  the date strip"))
            val navKey = com.toolsboox.ot.ModalScale.NAV_SIZE_KEY
            col.addView(chipRow(
                modalSizes.map { (value, label) ->
                    Triple(label, null as android.graphics.Typeface?) {
                        prefs.getString(navKey, prefs.getString("modal_size", "standard")) == value
                    }
                }
            ) { i -> prefs.edit().putString(navKey, modalSizes[i].first).apply() })

            // PILL SIZE — the floating pills and the pen button. Calibrated against the reading
            // gutter rather than the screen: at Compact a vertical pill lands ~39-42dp and the pen
            // button ~43dp, inside the narrowest margin the reading surfaces leave (44dp article,
            // ~46dp book reader on a Tab8). That's why it deserves its own dial — the arithmetic
            // is about the margin, and shouldn't move because a dialog wanted to be easier to read.
            col.addView(sectionLabel("PILL SIZE  ·  floating pills & pen"))
            val pillKey = com.toolsboox.ot.ModalScale.PILL_SIZE_KEY
            col.addView(chipRow(
                modalSizes.map { (value, label) ->
                    Triple(label, null as android.graphics.Typeface?) {
                        prefs.getString(pillKey, prefs.getString("modal_size", "standard")) == value
                    }
                }
            ) { i -> prefs.edit().putString(pillKey, modalSizes[i].first).apply() })
        }

        // FLOATING PILLS — whether they are there at all.
        //
        // The mirror of the switch on the wrenches, and on some surfaces the only way BACK: the
        // feed's wrench lives on the pill it hides. Sitting under PILL SIZE because it answers the
        // same question one step further — how big, and whether. Nothing else changes: each pill
        // keeps its own folded/turned state and its parked position, so showing them again puts
        // every one back exactly as you left it.
        //
        // Takes effect as each surface comes back to the front (ScreenFragment.onResume asks), so
        // a page sitting behind this dialog updates on the way back to it rather than needing the
        // app restarted.
        col.addView(sectionLabel("FLOATING PILLS  ·  the tool and paging capsules"))
        val pillVisibility = listOf(false to "Shown", true to "Hidden")
        col.addView(chipRow(
            pillVisibility.map { (hidden, label) ->
                Triple(label, null as android.graphics.Typeface?) {
                    com.toolsboox.ui.plugin.ScreenFragment.pillsHidden(ctx) == hidden
                }
            }
        ) { i ->
            ctx.getSharedPreferences("ledger_widgets", 0).edit()
                .putBoolean(com.toolsboox.ui.plugin.ScreenFragment.PILLS_HIDDEN, pillVisibility[i].first)
                .apply()
        })

        refreshPreview()

        thisDialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Legibility")
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Done", null)
            .show()
    }

}
