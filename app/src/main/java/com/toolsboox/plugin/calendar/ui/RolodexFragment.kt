package com.toolsboox.plugin.calendar.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.toolsboox.R
import com.toolsboox.databinding.FragmentRolodexBinding
import com.toolsboox.plugin.calendar.da.v2.Contact
import com.toolsboox.plugin.calendar.da.v2.CorrespondenceEntry
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.plugin.calendar.ot.CorrespondenceStore
import com.toolsboox.plugin.calendar.ot.DeviceContactImport
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

/**
 * The rolodex — a searchable list of contacts backed by the synced [ContactStore]. Tap a row to
 * edit (an AlertDialog editor); the ＋ button adds one. Wire-compatible with the iOS RolodexView.
 */
@AndroidEntryPoint
class RolodexFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_rolodex
    private lateinit var binding: FragmentRolodexBinding
    private lateinit var adapter: ContactAdapter
    private var allContacts: List<Contact> = emptyList()
    private var correspondenceCounts: Map<String, Int> = emptyMap()

    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) runImport()
            else Toast.makeText(requireContext(), "Contacts permission is needed to import", Toast.LENGTH_SHORT).show()
        }

    private val writeContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) confirmPush()
            else Toast.makeText(requireContext(), "Contacts permission is needed to push", Toast.LENGTH_SHORT).show()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentRolodexBinding.bind(view)

        adapter = ContactAdapter(emptyList(), ::openEditor) { correspondenceCounts[it.id] ?: 0 }
        binding.contactsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.contactsRecycler.adapter = adapter
        binding.contactsRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        binding.addContactButton.setOnClickListener { openEditor(Contact()) }
        binding.importButton.setOnClickListener { startImport() }
        binding.pushButton.setOnClickListener { startPush() }
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(s: Editable?) {}
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val (contacts, counts) = withContext(Dispatchers.IO) {
                ContactStore.list(requireContext()) to CorrespondenceStore.countsByContact(requireContext())
            }
            allContacts = contacts
            correspondenceCounts = counts
            applyFilter()
        }
    }

    private fun startImport() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) runImport()
        else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun runImport() {
        Toast.makeText(requireContext(), "Importing contacts…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val (added, linked) = withContext(Dispatchers.IO) { DeviceContactImport.importFromDevice(requireContext()) }
            load()
            Toast.makeText(requireContext(), "Imported $added · linked $linked", Toast.LENGTH_LONG).show()
        }
    }

    private fun startPush() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) confirmPush()
        else writeContactsPermission.launch(Manifest.permission.WRITE_CONTACTS)
    }

    /** Show what the push will do (non-destructively) before writing to the device address book. */
    private fun confirmPush() {
        lifecycleScope.launch {
            val (toUpdate, toCreate) = withContext(Dispatchers.IO) { DeviceContactImport.pushCounts(requireContext()) }
            AlertDialog.Builder(requireContext())
                .setTitle("Push to device contacts?")
                .setMessage(
                    "Updates $toUpdate and creates $toCreate in your device contacts. " +
                        "Existing names, phone numbers & emails are kept — nothing is deleted or overwritten."
                )
                .setPositiveButton("Push") { _, _ -> runPush() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun runPush() {
        Toast.makeText(requireContext(), "Pushing to device contacts…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val (updated, created) = withContext(Dispatchers.IO) { DeviceContactImport.pushToDevice(requireContext()) }
            load()
            Toast.makeText(requireContext(), "Updated $updated · created $created", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyFilter() {
        val q = binding.searchField.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isEmpty()) allContacts else allContacts.filter {
            it.name.lowercase().contains(q) || it.phone.contains(q) ||
                it.email.lowercase().contains(q) || it.org.lowercase().contains(q)
        }
        adapter.submit(filtered)
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Add / edit a contact via a simple field dialog; Save upserts, Delete tombstones. */
    private fun openEditor(contact: Contact) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String, value: String, type: Int): EditText {
            val e = EditText(ctx).apply {
                this.hint = hint; setText(value); inputType = type; setSingleLine(hint != "Notes")
            }
            root.addView(e); return e
        }
        val nameE = field("Name", contact.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val phoneE = field("Phone", contact.phone, InputType.TYPE_CLASS_PHONE)
        val emailE = field("Email", contact.email, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT)
        val orgE = field("Organization", contact.org, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val bdayE = field("Birthday (e.g. Mar 4)", contact.birthday, InputType.TYPE_CLASS_TEXT)
        val bioE = field("Notes", contact.bio, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)

        // Correspondence history — only for a saved contact (a brand-new one has nothing to log against).
        if (contact.name.isNotBlank()) {
            val n = correspondenceCounts[contact.id] ?: 0
            val header = TextView(ctx).apply {
                text = "📜 Correspondence ($n) ›"
                textSize = 16f
                setPadding(0, pad / 2, 0, pad / 2)
                setOnClickListener { showHistory(contact) }
            }
            root.addView(header, 0)
        }

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (contact.name.isBlank()) "New Contact" else "Edit Contact")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("Save") { _, _ ->
                contact.name = nameE.text.toString().trim()
                contact.phone = phoneE.text.toString().trim()
                contact.email = emailE.text.toString().trim()
                contact.org = orgE.text.toString().trim()
                contact.birthday = bdayE.text.toString().trim()
                contact.bio = bioE.text.toString().trim()
                if (contact.name.isNotBlank()) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { ContactStore.upsert(requireContext(), contact) }
                        load()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
        if (contact.name.isNotBlank()) {
            builder.setNeutralButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        ContactStore.delete(requireContext(), contact.id)
                        // Don't leave the contact's correspondence dangling — tombstone the thread too.
                        CorrespondenceStore.deleteForContact(requireContext(), contact.id)
                    }
                    load()
                }
            }
        }
        builder.show()
    }

    /** A contact's correspondence thread: newest-first list of entries + a "＋ Log" affordance. */
    private fun showHistory(contact: Contact) {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                CorrespondenceStore.forContact(requireContext(), contact.id)
            }
            val ctx = requireContext()
            val dp = resources.displayMetrics.density
            val pad = (16 * dp).toInt()
            val list = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }
            if (entries.isEmpty()) {
                list.addView(TextView(ctx).apply {
                    text = "No correspondence logged yet."
                    setPadding(0, pad / 2, 0, pad / 2)
                })
            } else {
                val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
                for (e in entries) {
                    val kind = CorrespondenceEntry.Kind.of(e.kind)
                    val dir = CorrespondenceEntry.Direction.of(e.direction)
                    val dirTag = if (dir == CorrespondenceEntry.Direction.NONE) "" else " · ${dir.label}"
                    val title = e.subject.ifBlank { kind.label }
                    list.addView(TextView(ctx).apply {
                        text = "${kind.glyph}  $title\n${df.format(Date(e.at))}$dirTag"
                        setPadding(0, pad / 2, 0, pad / 2)
                        setOnClickListener { openEntryEditor(contact, e, isNew = false) }
                    })
                }
            }
            AlertDialog.Builder(ctx)
                .setTitle("📜 ${contact.name.ifBlank { "Contact" }}")
                .setView(ScrollView(ctx).apply { addView(list) })
                .setPositiveButton("＋ Log") { _, _ ->
                    openEntryEditor(contact, CorrespondenceEntry(contactId = contact.id), isNew = true)
                }
                .setNegativeButton("Close", null)
                // The rolodex badge count may have changed while this thread was open.
                .setOnDismissListener { load() }
                .show()
        }
    }

    /** Add / edit one correspondence entry; Save upserts, Delete tombstones. Reopens the thread after. */
    private fun openEntryEditor(contact: Contact, entry: CorrespondenceEntry, isNew: Boolean) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        // Editable date, held in a calendar the picker mutates.
        val cal = Calendar.getInstance().apply { timeInMillis = entry.at }
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val dateBtn = TextView(ctx).apply {
            text = "📅 ${df.format(cal.time)}"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener {
                DatePickerDialog(
                    ctx,
                    { _, y, m, d -> cal.set(y, m, d); text = "📅 ${df.format(cal.time)}" },
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        root.addView(dateBtn)

        val kinds = CorrespondenceEntry.Kind.values()
        val kindSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                kinds.map { "${it.glyph} ${it.label}" })
            setSelection(kinds.indexOf(CorrespondenceEntry.Kind.of(entry.kind)).coerceAtLeast(0))
        }
        root.addView(labeled(ctx, "Kind", kindSpinner, pad))

        val dirs = CorrespondenceEntry.Direction.values()
        val dirSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, dirs.map { it.label })
            setSelection(dirs.indexOf(CorrespondenceEntry.Direction.of(entry.direction)).coerceAtLeast(0))
        }
        root.addView(labeled(ctx, "Direction", dirSpinner, pad))

        val subjectE = EditText(ctx).apply {
            hint = "Subject"; setText(entry.subject); setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(subjectE)
        val bodyE = EditText(ctx).apply {
            hint = "Notes"; setText(entry.body)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(bodyE)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (isNew) "Log correspondence" else "Edit entry")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("Save") { _, _ ->
                entry.at = cal.timeInMillis
                entry.kind = kinds[kindSpinner.selectedItemPosition].wire
                entry.direction = dirs[dirSpinner.selectedItemPosition].wire
                entry.subject = subjectE.text.toString().trim()
                entry.body = bodyE.text.toString().trim()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { CorrespondenceStore.upsert(requireContext(), entry) }
                    showHistory(contact)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> showHistory(contact) }
        if (!isNew) {
            builder.setNeutralButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { CorrespondenceStore.delete(requireContext(), entry.id) }
                    showHistory(contact)
                }
            }
        }
        builder.show()
    }

    /** A small "Label:" caption stacked above [control], matching the plain programmatic editors. */
    private fun labeled(ctx: android.content.Context, label: String, control: View, pad: Int): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad / 2, 0, 0)
            addView(TextView(ctx).apply { text = label; gravity = Gravity.START })
            addView(control)
        }

    override fun showLoading() {}
    override fun hideLoading() {}
}
