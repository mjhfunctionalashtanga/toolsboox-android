package com.toolsboox.plugin.calendar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ContactNote
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.toolsboox.R
import com.toolsboox.databinding.FragmentRolodexBinding
import com.toolsboox.plugin.calendar.da.v2.Contact
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.plugin.calendar.ot.DeviceContactImport
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The rolodex — a searchable list of contacts backed by the synced [ContactStore]. Tap a row to
 * edit (an AlertDialog editor); the ＋ button adds one. Wire-compatible with the iOS RolodexView.
 */
@AndroidEntryPoint
class RolodexFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    override val view = R.layout.fragment_rolodex
    private lateinit var binding: FragmentRolodexBinding
    private lateinit var adapter: ContactAdapter
    private var allContacts: List<Contact> = emptyList()

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

        adapter = ContactAdapter(emptyList(), ::showContactDetail)
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
            val contacts = withContext(Dispatchers.IO) { ContactStore.list(requireContext()) }
            allContacts = contacts
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

    /** The contact "page": header, the tasks/events assigned to this person (gathered across day
     *  files), and a running notes/history log you can append to. Edit opens the field form. */
    private fun showContactDetail(contact: Contact) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun label(text: String, size: Float, bold: Boolean = false, color: Int = 0xFF000000.toInt()): TextView =
            TextView(ctx).apply {
                this.text = text; textSize = size; setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(20), px(12), px(20), 0)
        }

        root.addView(label(contact.name.ifBlank { "Unnamed" }, 20f, bold = true))
        if (contact.org.isNotBlank()) root.addView(label(contact.org, 14f, color = 0xFF666666.toInt()))
        val line = listOfNotNull(contact.phone.ifBlank { null }, contact.email.ifBlank { null }).joinToString("  ·  ")
        if (line.isNotBlank()) root.addView(label(line, 14f, color = 0xFF333333.toInt()).apply { setPadding(0, px(4), 0, 0) })
        if (contact.birthday.isNotBlank()) root.addView(label("🎂 ${contact.birthday}", 14f).apply { setPadding(0, px(4), 0, 0) })

        // Tasks & Events (gathered off-main).
        root.addView(label("Tasks & Events", 13f, bold = true, color = 0xFF888888.toInt()).apply { setPadding(0, px(16), 0, px(4)) })
        val tasksBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tasksBox.addView(label("Loading…", 13f, color = 0xFF999999.toInt()))
        root.addView(tasksBox)

        // Pickings, Notes & Cards (grams / text boxes linked to this contact).
        root.addView(label("Pickings, Notes & Cards", 13f, bold = true, color = 0xFF888888.toInt()).apply { setPadding(0, px(16), 0, px(4)) })
        val elementsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        elementsBox.addView(label("Loading…", 13f, color = 0xFF999999.toInt()))
        root.addView(elementsBox)

        // Notes & History.
        root.addView(label("Notes & History", 13f, bold = true, color = 0xFF888888.toInt()).apply { setPadding(0, px(16), 0, px(4)) })
        val noteInput = EditText(ctx).apply {
            hint = "Add a note…"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = Button(ctx).apply { text = "Add"; isAllCaps = false }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(noteInput); addView(addBtn)
        })
        val notesBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(notesBox)

        fun renderNotes() {
            notesBox.removeAllViews()
            val sorted = contact.history.sortedByDescending { it.at }
            if (sorted.isEmpty()) { notesBox.addView(label("No notes yet.", 13f, color = 0xFF999999.toInt())); return }
            for (n in sorted) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(6), 0, px(6)) }
                row.addView(label(n.text, 14f))
                row.addView(label(formatMillis(n.at), 11f, color = 0xFF999999.toInt()))
                row.setOnLongClickListener {
                    contact.history = contact.history.filterNot { it.id == n.id }
                    lifecycleScope.launch { withContext(Dispatchers.IO) { ContactStore.upsert(requireContext(), contact) }; renderNotes() }
                    Toast.makeText(ctx, "Note deleted", Toast.LENGTH_SHORT).show(); true
                }
                notesBox.addView(row)
            }
        }
        renderNotes()

        addBtn.setOnClickListener {
            val t = noteInput.text.toString().trim()
            if (t.isNotBlank()) {
                contact.history = listOf(ContactNote(text = t)) + contact.history
                noteInput.setText("")
                lifecycleScope.launch { withContext(Dispatchers.IO) { ContactStore.upsert(requireContext(), contact) }; renderNotes() }
            }
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("Edit") { _, _ -> openEditor(contact) }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val (items, elements) = withContext(Dispatchers.IO) {
                gatherContactItems(contact.id) to gatherContactElements(contact.id)
            }
            tasksBox.removeAllViews()
            val itemsSorted = sortUpcomingFirst(items)
            if (itemsSorted.isEmpty()) tasksBox.addView(label("None assigned yet.", 13f, color = 0xFF999999.toInt()))
            else for (item in itemsSorted) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), 0, px(4)) }
                val textLabel = label(item.text, 14f).apply { if (item.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG }
                val markView = label(markFor(item.done, item.kind), 16f).apply { setPadding(0, 0, px(10), 0) }
                if (item.kind == LedgerItem.Kind.TASK) markView.setOnClickListener {
                    toggleDone(item) { done ->
                        markView.text = markFor(done, item.kind)
                        textLabel.paintFlags = if (done) textLabel.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                        else textLabel.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    }
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    isClickable = true
                    setOnClickListener { dialog.dismiss(); openDay(item.date) }
                }
                col.addView(textLabel)
                col.addView(label(formatDate(item.date) + (item.time?.let { t -> "  ·  $t" } ?: "") + "   ›", 11f, color = 0xFF999999.toInt()))
                row.addView(markView); row.addView(col)
                tasksBox.addView(row)
            }
            elementsBox.removeAllViews()
            if (elements.isEmpty()) elementsBox.addView(label("None linked yet.", 13f, color = 0xFF999999.toInt()))
            else for (el in elements) {
                val mark = when (el.kind) { "Picking" -> "❝"; "Card" -> "🖼"; else -> "📝" }
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(4), 0, px(4)); isClickable = true }
                row.addView(label("$mark  ${el.title.ifBlank { el.kind }}", 14f))
                row.addView(label("${el.kind} · ${el.date}   ›", 11f, color = 0xFF999999.toInt()))
                row.setOnClickListener {
                    dialog.dismiss()
                    CalendarNavigator.toDayPage(this@RolodexFragment, el.date, CalendarDay.DEFAULT_STYLE)
                }
                elementsBox.addView(row)
            }
        }
    }

    private data class LinkedElement(val kind: String, val title: String, val date: java.time.LocalDate, val millis: Long)

    /** Text boxes (pickings/notes) + gram-cards across day files linked to [contactId], newest first. */
    private fun gatherContactElements(contactId: String): List<LinkedElement> {
        val calendarRoot = File(documentsRoot(), "calendar")
        if (!calendarRoot.exists()) return emptyList()
        val out = mutableListOf<LinkedElement>()
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file ->
                val ld = dayDateFromName(file.name) ?: return@forEach
                val day = runCatching { calendarDayService.load(file) }.getOrNull() ?: return@forEach
                for (t in day.textElements) if (t.contactId == contactId && t.text.isNotBlank()) {
                    out.add(LinkedElement(if (t.pageKey == "pickings") "Picking" else "Note", t.text.trim(), ld, t.timestamp))
                }
                for (img in day.imageElements) if (img.contactId == contactId) {
                    out.add(LinkedElement("Card", img.sourceLabel.ifBlank { "Card" }, ld, img.timestamp))
                }
            }
        return out.sortedByDescending { it.millis }
    }

    /** "day-YYYY-MM-DD-v2.json" → LocalDate. */
    private fun dayDateFromName(name: String): java.time.LocalDate? = runCatching {
        val m = Regex("day-(\\d{4})-(\\d{2})-(\\d{2})").find(name) ?: return null
        java.time.LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()

    /** Jump the planner to the day a task/event lives on (dates are stored at noon UTC). */
    private fun openDay(date: Date) {
        val ld = date.toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
        CalendarNavigator.toDayPage(this, ld, CalendarDay.DEFAULT_STYLE)
    }

    private fun markFor(done: Boolean, kind: LedgerItem.Kind): String =
        when { kind == LedgerItem.Kind.EVENT -> "📅"; done -> "✓"; else -> "○" }

    /** Undone, today-or-later tasks/events float up (soonest first); the rest fall below newest-first. */
    private fun sortUpcomingFirst(items: List<LedgerItem>): List<LedgerItem> {
        val today = java.time.LocalDate.now()
        fun upcoming(i: LedgerItem): Boolean {
            val d = i.date.toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
            return !i.done && !d.isBefore(today)
        }
        return items.sortedWith(Comparator { a, b ->
            val ua = upcoming(a); val ub = upcoming(b)
            if (ua != ub) if (ua) -1 else 1
            else if (ua) a.date.compareTo(b.date) else b.date.compareTo(a.date)
        })
    }

    /** Check a task off (or back on) from the contact page — writes to its day + pushes to CalDAV. */
    private fun toggleDone(item: LedgerItem, onDone: (Boolean) -> Unit) {
        val ld = item.date.toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
        lifecycleScope.launch {
            val newState = withContext(Dispatchers.IO) {
                val root = documentsRoot()
                val day = calendarDayService.load(root, ld, null, Locale.getDefault())
                val j = day.ledgerItems.indexOfFirst { it.id == item.id }
                if (j < 0) return@withContext item.done
                day.ledgerItems[j].done = !day.ledgerItems[j].done
                calendarDayService.save(root, ld, day)
                runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), day.ledgerItems[j]) }
                day.ledgerItems[j].done
            }
            onDone(newState)
        }
    }

    /** Every ledger item (task/event) across day files assigned to [contactId], newest first. */
    private fun gatherContactItems(contactId: String): List<LedgerItem> {
        val calendarRoot = File(documentsRoot(), "calendar")
        if (!calendarRoot.exists()) return emptyList()
        val out = mutableListOf<LedgerItem>()
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file ->
                val day = runCatching { calendarDayService.load(file) }.getOrNull() ?: return@forEach
                out.addAll(day.ledgerItems.filter { it.contactId == contactId })
            }
        return out.sortedByDescending { it.date.time }
    }

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    private fun formatMillis(ms: Long): String =
        SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(ms))

    private fun formatDate(d: Date): String =
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(d)

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
                    withContext(Dispatchers.IO) { ContactStore.delete(requireContext(), contact.id) }
                    load()
                }
            }
        }
        builder.show()
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
