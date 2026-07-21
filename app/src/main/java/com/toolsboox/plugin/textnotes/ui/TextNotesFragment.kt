package com.toolsboox.plugin.textnotes.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import com.toolsboox.R
import com.toolsboox.databinding.FragmentTextNotesBinding
import com.toolsboox.plugin.textnotes.TextNote
import com.toolsboox.plugin.textnotes.TextNotesStore
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import javax.inject.Inject

/**
 * Text Notes — multiple titled notes per day, each instantly saved. Pick/switch notes via "≡ Notes",
 * add with "＋". Title + body autosave (debounced) to disk; WebDAV backup on leave.
 */
@AndroidEntryPoint
class TextNotesFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_text_notes

    private lateinit var binding: FragmentTextNotesBinding
    private val main = Handler(Looper.getMainLooper())
    private var date: LocalDate = LocalDate.now()
    private val saveRunnable = Runnable { persist() }

    private var notes: MutableList<TextNote> = mutableListOf()
    private var current: Int = 0
    private var suppressWatch = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentTextNotesBinding.bind(view)
        loadDay()

        binding.textNotesPrev.setOnClickListener { goToDate(date.minusDays(1)) }
        binding.textNotesNext.setOnClickListener { goToDate(date.plusDays(1)) }

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

        binding.textNotesNew.setOnClickListener { newNote() }
        binding.textNotesList.setOnClickListener { showNotesList() }
        binding.textNotesMenu.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
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
    }

    private fun showNote(index: Int) {
        current = index.coerceIn(0, notes.lastIndex)
        val n = notes[current]
        suppressWatch = true
        binding.textNotesTitle.setText(n.title)
        binding.textNotesEdit.setText(n.body)
        binding.textNotesEdit.setSelection(n.body.length)
        suppressWatch = false
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
            .setNeutralButton("Delete current") { _, _ -> deleteCurrent() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun deleteCurrent() {
        if (notes.size <= 1) { notes[0] = TextNote(TextNotesStore.newId(), "", "") }
        else notes.removeAt(current)
        TextNotesStore.save(requireContext(), date, notes)
        showNote(current.coerceAtMost(notes.lastIndex))
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
