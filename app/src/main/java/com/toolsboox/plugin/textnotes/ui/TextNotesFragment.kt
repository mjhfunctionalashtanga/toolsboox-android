package com.toolsboox.plugin.textnotes.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.toolsboox.R
import com.toolsboox.databinding.FragmentTextNotesBinding
import com.toolsboox.plugin.textnotes.TextNote
import com.toolsboox.plugin.textnotes.TextNotesStore
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * Text Notes — multiple titled notes per day, each instantly saved. Pick/switch notes via "≡ Notes",
 * add with "＋". Title + body autosave (debounced) to disk; WebDAV backup on leave. The Almanac
 * strip up top travels the days the way Mail and the Feed do — notes ARE date-anchored, so the
 * strip is the natural way to reach last Tuesday's page.
 */
@AndroidEntryPoint
class TextNotesFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_text_notes

    private lateinit var binding: FragmentTextNotesBinding
    private val main = Handler(Looper.getMainLooper())
    private var date: LocalDate = LocalDate.now()
    private val saveRunnable = Runnable { persist() }
    private val highlightRunnable = Runnable {
        binding.textNotesEdit.text?.let { com.toolsboox.ot.MarkdownHighlight.apply(it) }
    }

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    // The Almanac strip (same CalendarNavBarHost Mail hosts). Notes live one day per page, so a
    // period tap doesn't FILTER here — it retunes the arrows' stride (tap Week, and ‹ › walk a
    // week at a time), while the tapped day itself is where the editor lands.
    private var navBar: com.toolsboox.plugin.calendar.ui.CalendarNavBarHost? = null
    private var navGranularity = "day"

    private var notes: MutableList<TextNote> = mutableListOf()
    private var current: Int = 0
    private var suppressWatch = false

    companion object {
        const val ARG_DATE = "textNotesDate"
        const val ARG_NOTE_ID = "textNotesNoteId"

        /**
         * Open Text Notes ON a particular note rather than at today's first one.
         *
         * The whole-ledger directory lists text notes across all time, and a list that can name a
         * note but only ever lands you on today isn't a directory — it's a list of things you then
         * have to go and find. Every other making surface is reachable by (date, page) through
         * [com.toolsboox.plugin.calendar.CalendarNavigator]; Text Notes lives in its own fragment,
         * so it needs its own door, and this is it. Both arguments are optional and each degrades
         * on its own: an unparseable date lands on today, an id that no longer exists (deleted on
         * another device) lands on that day's first note rather than on nothing.
         */
        fun open(fragment: ScreenFragment, date: LocalDate? = null, noteId: String? = null) {
            androidx.navigation.fragment.NavHostFragment.findNavController(fragment).navigate(
                R.id.action_to_text_notes,
                androidx.core.os.bundleOf(
                    ARG_DATE to date?.toString(),
                    ARG_NOTE_ID to noteId
                )
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentTextNotesBinding.bind(view)
        // Honour the day/note handed in by the directory before the first load, so the screen opens
        // on the note that was chosen instead of opening on today and then jumping.
        arguments?.getString(ARG_DATE)
            ?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() }
            ?.let { date = it }
        loadDay()
        arguments?.getString(ARG_NOTE_ID)?.let { id ->
            val i = notes.indexOfFirst { it.id == id }
            if (i >= 0) showNote(i)
        }
        // Consume the arguments: they describe how this screen was OPENED, and leaving them in
        // place would make a later recreate (rotation, process death) re-land you on that note
        // after you had navigated away from it inside the screen.
        arguments?.remove(ARG_DATE)
        arguments?.remove(ARG_NOTE_ID)

        binding.textNotesPrev.setOnClickListener { goToDate(date.minusDays(1)) }
        binding.textNotesNext.setOnClickListener { goToDate(date.plusDays(1)) }

        navBar = com.toolsboox.plugin.calendar.ui.CalendarNavBarHost(
            requireContext(), binding.textNotesNavigator, this,
            onStepDay = { d ->
                // The arrows stride by the chosen granularity (a day by default; a week/month/…
                // after a period tap) — quick travel without leaving the notes.
                val dir = if (d.isBefore(date)) -1 else 1
                goToDate(stepByGranularity(date, dir))
            },
            onSelectPeriod = { g, d ->
                navGranularity = g
                if (d != date) goToDate(d) else renderNav()
            }
        )
        renderNav()

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatch) return
                main.removeCallbacks(saveRunnable); main.postDelayed(saveRunnable, 400)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        binding.textNotesTitle.addTextChangedListener(watcher)
        binding.textNotesEdit.addTextChangedListener(watcher)

        // Live markdown, the way Markor does it: style the note in the editor as it's typed —
        // headings grow, **bold** goes bold — while the marks stay visible and editable. A
        // separate, faster beat than the save debounce, and it never touches the text so the
        // cursor doesn't move. The 👁 toggle is still there for a clean, marks-hidden read.
        binding.textNotesEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatch || s == null) return
                main.removeCallbacks(highlightRunnable)
                main.postDelayed(highlightRunnable, 120)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.textNotesNew.setOnClickListener { newNote() }
        binding.textNotesList.setOnClickListener { showNotesList() }
        binding.textNotesPreview.setOnClickListener { togglePreview() }
        binding.textNotesMenu.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }
        // The header's chrome retires into the rail: ☰ Hub is the same door, and New / List /
        // Preview ride as icons. The ‹ date › stepper stays in the header — it names the day
        // the way the almanac strip does elsewhere.
        binding.textNotesMenu.visibility = View.GONE
        binding.textNotesNew.visibility = View.GONE
        binding.textNotesList.visibility = View.GONE
        binding.textNotesPreview.visibility = View.GONE
        setupActionRail(
            binding.textNotesRail, "text_notes",
            actions = { listOf(
                com.toolsboox.ot.TuckPanel.Item(0, "New note", glyph = "＋") {
                    binding.textNotesNew.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(0, "Notes list", glyph = "≡") {
                    binding.textNotesList.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_reader_view, "Preview") {
                    binding.textNotesPreview.performClick()
                }
            ) }
        )
    }

    private var previewing = false

    /**
     * Flip between writing and reading the note.
     *
     * Writing is the raw markdown in the editor; reading is the rendered version — headings,
     * bold, lists, quotes — the way Markor shows a note once you stop typing. The eye toggles it,
     * and turning preview on saves first so what you read is what you just wrote.
     */
    private fun togglePreview() {
        previewing = !previewing
        if (previewing) {
            persist()
            binding.textNotesRender.text = com.toolsboox.ot.MarkdownRender.render(
                binding.textNotesEdit.text.toString())
            binding.textNotesEdit.visibility = View.GONE
            binding.textNotesPreviewScroll.visibility = View.VISIBLE
            binding.textNotesPreview.text = "✎"
        } else {
            binding.textNotesPreviewScroll.visibility = View.GONE
            binding.textNotesEdit.visibility = View.VISIBLE
            binding.textNotesPreview.text = "👁"
        }
    }

    /** Load (or reload) the notes for [date] — the date label + first note. */
    private fun loadDay() {
        binding.textNotesDate.text = date.toString()
        notes = TextNotesStore.load(requireContext(), date)
        if (notes.isEmpty()) notes.add(TextNote(TextNotesStore.newId(), "", ""))
        showNote(0)
        // Pull the remote copy in the background and merge by note id, so another device's notes for
        // this day converge onto disk (picked up on next open) without clobbering what's here.
        TextNotesStore.sync(requireContext(), date, null)
    }

    /** Persist the day we're leaving, then jump to another day's notes (same up/down date nav as the rest). */
    private fun goToDate(target: LocalDate) {
        main.removeCallbacks(saveRunnable)
        persist()
        TextNotesStore.sync(requireContext(), date, null)
        date = target
        loadDay()
        renderNav()
    }

    private fun stepByGranularity(d: LocalDate, dir: Int): LocalDate = when (navGranularity) {
        "week" -> d.plusWeeks(dir.toLong())
        "month" -> d.plusMonths(dir.toLong())
        "quarter" -> d.plusMonths(3L * dir)
        "year" -> d.plusYears(dir.toLong())
        else -> d.plusDays(dir.toLong())
    }

    /** Redraw the Almanac strip for the current day (dots for filled pages), Mail's exact pattern:
     *  view-scoped so back-navigation cancels the draw, and a missing pattern falls back to an
     *  empty one — an unrendered strip never sets its day, and a day-less strip ignores touch. */
    private fun renderNav() {
        val bar = navBar ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val root = documentsRoot()
            val loc = java.util.Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, date, null, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v2.CalendarDay(
                        date.year, date.monthValue, date.dayOfMonth, startHour = null)
                val p = runCatching { calendarPatternService.load(root, date, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v1.CalendarPattern(date.year, loc).fill()
                cd to p
            }
            if (isAdded) bar.render(day, pat)
        }
    }

    private fun showNote(index: Int) {
        current = index.coerceIn(0, notes.lastIndex)
        val n = notes[current]
        suppressWatch = true
        binding.textNotesTitle.setText(n.title)
        binding.textNotesEdit.setText(n.body)
        binding.textNotesEdit.setSelection(n.body.length)
        suppressWatch = false
        binding.textNotesEdit.text?.let { com.toolsboox.ot.MarkdownHighlight.apply(it) }
        binding.textNotesList.text = "≡ Notes (${notes.size})"
    }

    /** Pull the editor's current text back into the model + persist locally. Bumps the note's
     *  updatedAt only on a real change, so cross-device merge favours the genuinely newer edit. */
    private fun persist() {
        if (current !in notes.indices) return
        val n = notes[current]
        val t = binding.textNotesTitle.text.toString()
        val b = binding.textNotesEdit.text.toString()
        if (n.title != t || n.body != b) {
            n.title = t; n.body = b; n.updatedAt = System.currentTimeMillis()
        }
        TextNotesStore.save(requireContext(), date, notes)
    }

    private fun newNote() {
        persist()
        notes.add(TextNote(TextNotesStore.newId(), "", ""))
        showNote(notes.lastIndex)
        TextNotesStore.save(requireContext(), date, notes)
        binding.textNotesTitle.requestFocus()
    }

    private fun showNotesList() {
        persist()
        val labels = notes.mapIndexed { i, n ->
            val t = n.title.ifBlank { n.body.take(30).ifBlank { "Untitled" } }
            if (i == current) "• $t" else "  $t"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Notes · $date")
            .setItems(labels) { _, which -> showNote(which) }
            // Text Notes is one of the five surfaces the shared Send / Export sheet covers, and
            // this list is the note's own menu — the only place on this screen that is about the
            // note rather than about the text cursor.
            .setPositiveButton("→  Send / Export…") { _, _ -> showSendExport() }
            .setNeutralButton("Delete current") { _, _ -> deleteCurrent() }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * The shared Send / Export sheet for the note on screen.
     *
     * Unlike the day surfaces this one has no canvas to photograph, so the picture it hands over is
     * the note SET as a page — title and body laid out on white at the app's usual 1404×1872. That
     * is what makes the picture-shaped destinations (PNG, the community space, the PDF's first
     * page, the email's inline image) mean something here rather than being greyed out: a typed
     * note still has a face, it just has to be typeset instead of photographed.
     */
    private fun showSendExport() {
        persist()
        val note = notes.getOrNull(current) ?: return
        com.toolsboox.plugin.calendar.ot.LedgerSendExport.show(
            this,
            com.toolsboox.plugin.calendar.ot.LedgerSendExport.Payload(
                title = note.title.ifBlank { "$date · text note" },
                text = { note.body },
                bitmap = { runCatching { typesetPage(note.title, note.body) }.getOrNull() },
                // A text note's identity is its note id, not a note-page key — TextNotesStore keys
                // by id and nothing on a day surface can address one. That id is what the stamp's
                // published-URL memory is filed under, which is right: renaming the note must not
                // orphan the address the published version already lives at.
                surface = com.toolsboox.plugin.calendar.ot.LedgerDocuments
                    .label(com.toolsboox.plugin.calendar.ot.LedgerDocuments.TEXT_NOTES),
                date = date,
                pageKey = note.id
            )
        )
    }

    /** Lay [title] and [body] out on a page-sized white bitmap. Plain sans and generous leading —
     *  this is read on e-ink and printed to PDF, not shown behind glass. */
    private fun typesetPage(title: String, body: String): android.graphics.Bitmap {
        val w = 1404
        val h = 1872
        val margin = 96f
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        var y = margin + 40f
        if (title.isNotBlank()) {
            val tp = android.text.TextPaint().apply {
                isAntiAlias = true; color = android.graphics.Color.BLACK
                textSize = 52f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            canvas.drawText(title.take(60), margin, y, tp)
            y += 70f
        }
        val bp = android.text.TextPaint().apply {
            isAntiAlias = true; color = android.graphics.Color.BLACK
            textSize = 36f; typeface = android.graphics.Typeface.SANS_SERIF
        }
        val layout = android.text.StaticLayout.Builder
            .obtain(body, 0, body.length, bp, (w - margin * 2).toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(10f, 1.1f)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(margin, y)
        layout.draw(canvas)
        canvas.restore()
        return bmp
    }

    private fun deleteCurrent() {
        if (notes.size <= 1) { notes[0] = TextNote(TextNotesStore.newId(), "", "") }
        else notes.removeAt(current)
        TextNotesStore.save(requireContext(), date, notes)
        showNote(current.coerceAtMost(notes.lastIndex))
    }

    /**
     * A note for [date] was written to the store by something other than this screen — today, the
     * 📷 Capture → OCR path in [com.toolsboox.ui.main.MainActivity], which can fire from any screen
     * including this one.
     *
     * RE-READ, never blind-save — the day page's rule, adapted to a store that has no per-item
     * write. [persist] serialises the WHOLE list, so simply saving here would drop the note that
     * was just added, and simply reloading would drop whatever is half-typed in the editor. So do
     * both, in the only order that keeps both: fold the editor's live text back into the note object
     * in memory (no disk), re-read the list from disk to pick up the newcomer, and union the two by
     * id with the live note winning its own slot.
     *
     * Deliberately NOT [showNote] — the current note's text has not changed, and re-setting it would
     * yank the cursor to the end of the body mid-sentence. Only the list and its count move.
     */
    override fun onExternalNoteAdded(date: LocalDate) {
        if (!isAdded || !isResumed || date != this.date) return
        val live = notes.getOrNull(current)
        if (live != null) {
            val t = binding.textNotesTitle.text.toString()
            val b = binding.textNotesEdit.text.toString()
            if (live.title != t || live.body != b) {
                live.title = t; live.body = b; live.updatedAt = System.currentTimeMillis()
            }
        }
        val byId = LinkedHashMap<String, TextNote>()
        for (n in TextNotesStore.load(requireContext(), date)) byId[n.id] = n
        for (n in notes) byId.putIfAbsent(n.id, n)
        live?.let { byId[it.id] = it }
        notes = byId.values.toMutableList()
        current = notes.indexOfFirst { it.id == live?.id }.coerceAtLeast(0)
        binding.textNotesList.text = "≡ Notes (${notes.size})"
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(saveRunnable)
        persist()
        TextNotesStore.sync(requireContext(), date, null)
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
