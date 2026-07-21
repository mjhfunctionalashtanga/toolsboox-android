package com.toolsboox.plugin.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.toolsboox.ot.ImageZoom
import com.toolsboox.ot.InkMount
import com.toolsboox.ot.InkPadView
import com.toolsboox.ot.ReadingSize
import java.util.Date

/**
 * The book's marks, in one place — Readest's annotation sidebar, on e-ink.
 *
 * Readest offers Annotations and Bookmarks as two tabs over ONE array, each grouped by the
 * table-of-contents entry the mark sits in, chapters in book order and marks in reading order
 * inside them; a row shows the passage with the reader's note under it, tapping jumps, and each
 * row carries edit-note and delete. That is what this is. The third tab, Notes, is ours: a
 * book-level note has no location to group by, so it cannot share the chapter list — but it is
 * the same object with an empty CFI, not a second store.
 *
 * Black on white, no alpha, no animation: the panel is a page, not a scrim.
 */
object BookNotesPanel {

    /**
     * @param tocOrder chapter labels in book order — the group ordering, since the marks
     *   themselves only know which chapter they were made in.
     * @param onJump go to this mark's location in the open book.
     * @param onUnmark drop the drawn highlight from the page (the store forgets it either way).
     */
    fun show(
        context: Context,
        book: String,
        tocOrder: List<String>,
        startType: String = BookNote.ANNOTATION,
        onJump: (BookNote) -> Unit,
        onUnmark: (BookNote) -> Unit
    ) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(4), px(14), px(16))
        }
        val scroll = ScrollView(context).apply {
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(14), px(12), px(14), px(8))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(tabs)
            addView(rule(context))
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))

        var type = startType
        lateinit var rebuild: () -> Unit

        fun tab(label: String, forType: String): TextView = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, px(2), px(22), px(6))
            setOnClickListener { type = forType; rebuild() }
            tag = forType
            text = label
        }
        val tabAnnotation = tab("🖍  Annotations", BookNote.ANNOTATION)
        val tabBookmark = tab("🔖  Bookmarks", BookNote.BOOKMARK)
        val tabNote = tab("🗒  Notes", BookNote.NOTE)
        tabs.addView(tabAnnotation); tabs.addView(tabBookmark); tabs.addView(tabNote)

        rebuild = {
            val notes = BookNoteStore.list(context, book, type)
            for (t in listOf(tabAnnotation, tabBookmark, tabNote)) {
                val count = BookNoteStore.list(context, book, t.tag as String).size
                val base = (t.text as CharSequence).toString().substringBefore(" (")
                t.text = if (count > 0) "$base ($count)" else base
                // The open tab is the underlined one — the pen toolbar's convention, and the
                // only "selected" state that survives a grayscale panel.
                t.paintFlags = if (t.tag == type) android.graphics.Paint.UNDERLINE_TEXT_FLAG else 0
                t.setTypeface(null, if (t.tag == type) Typeface.BOLD else Typeface.NORMAL)
            }
            list.removeAllViews()
            if (notes.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = when (type) {
                        BookNote.BOOKMARK -> "No bookmarks yet.\n☰ → Marks → Bookmark this page."
                        BookNote.NOTE -> "No notes on this book yet.\n🔧 → Books → Note on this book…"
                        else -> "No annotations yet.\nSelect some text, then tap ✎ to highlight it."
                    }
                    textSize = 15f; setTextColor(Color.BLACK); setPadding(0, px(28), 0, px(28))
                })
            } else {
                // Grouped by chapter in book order, exactly as the Readest sidebar groups by
                // TOC item; a book-level note has no chapter, so Notes falls through as one run.
                val groups = notes.groupBy { it.chapter }
                val ordered = groups.keys.sortedBy { label ->
                    val i = tocOrder.indexOf(label)
                    if (i >= 0) i else Int.MAX_VALUE
                }
                for (chapter in ordered) {
                    if (chapter.isNotBlank()) list.addView(TextView(context).apply {
                        text = chapter
                        textSize = 14f; setTextColor(Color.BLACK)
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, px(16), 0, px(4))
                    })
                    for (note in groups[chapter].orEmpty()) {
                        list.addView(row(context, note, dialog, onJump, onUnmark) { rebuild() })
                    }
                }
            }
            ReadingSize.apply(root, ReadingSize.scale(context))
        }
        rebuild()

        dialog.show()
        dialog.window?.let { w ->
            val metrics = context.resources.displayMetrics
            val lp = w.attributes
            lp.gravity = Gravity.END or Gravity.TOP
            lp.x = 0; lp.y = px(64)
            lp.width = minOf(px(420), (metrics.widthPixels * 0.72f).toInt())
            lp.height = metrics.heightPixels - px(96)
            w.attributes = lp
        }
    }

    /** One mark: the passage, the reader's note under it, and where/when it was made. */
    private fun row(
        context: Context,
        note: BookNote,
        dialog: AlertDialog,
        onJump: (BookNote) -> Unit,
        onUnmark: (BookNote) -> Unit,
        onChanged: () -> Unit
    ): View {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(10), px(10), px(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(px(1), Color.BLACK); cornerRadius = px(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, px(6), 0, 0) }
        }

        val passage = note.text.ifBlank {
            if (note.type == BookNote.BOOKMARK) "${(note.fraction * 100).toInt()}% through the book"
            else "Marked passage"
        }
        if (note.type != BookNote.NOTE || note.text.isNotBlank()) {
            box.addView(TextView(context).apply {
                text = passage
                textSize = 15f; setTextColor(Color.BLACK); maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        if (note.note.isNotBlank()) box.addView(TextView(context).apply {
            text = "🖍  ${note.note}"
            textSize = 14f; setTextColor(Color.BLACK)
            setTypeface(null, Typeface.ITALIC)
            setPadding(0, px(6), 0, 0)
        })
        note.image?.let { b64 ->
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching
                val iv = ImageView(context).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                // Hand-written notes are shown, not composed, so they get the shared mount —
                // and tap-to-enlarge, because a note written small is a note read closely.
                ImageZoom.makeTappable(iv, note.text)
                box.addView(InkMount.wrapInColumn(context, iv, taped = false).apply {
                    (layoutParams as LinearLayout.LayoutParams).setMargins(0, px(8), 0, 0)
                })
            }
        }
        box.addView(TextView(context).apply {
            val stamp = DateFormat.getDateFormat(context).format(Date(note.createdAt))
            text = stamp
            textSize = 12f; setTextColor(Color.BLACK); setPadding(0, px(8), 0, 0)
        })

        box.setOnClickListener {
            if (note.cfi.isBlank()) { editNote(context, note) { onChanged() }; return@setOnClickListener }
            dialog.dismiss()
            onJump(note)
        }
        box.setOnLongClickListener {
            rowMenu(context, note, dialog, onJump, onUnmark, onChanged); true
        }
        return box
    }

    /** Long-press: the row actions Readest puts behind its edit/delete buttons. */
    private fun rowMenu(
        context: Context,
        note: BookNote,
        dialog: AlertDialog,
        onJump: (BookNote) -> Unit,
        onUnmark: (BookNote) -> Unit,
        onChanged: () -> Unit
    ) {
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        if (note.cfi.isNotBlank()) rows += "↪  Go to this spot" to { dialog.dismiss(); onJump(note) }
        rows += (if (note.note.isBlank() && note.image == null) "🖍  Add a note" else "✎  Edit the note") to {
            editNote(context, note) { onChanged() }
        }
        if (note.text.isNotBlank()) rows += "📋  Copy the passage" to {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Ledger", note.text))
        }
        rows += "🗑  Delete" to {
            BookNoteStore.delete(context, currentBook, note.id)
            onUnmark(note)
            onChanged()
        }
        AlertDialog.Builder(context)
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Which book the rows and the composer write to; set by the reader before either opens. */
    private var currentBook: String = ""

    fun bind(book: String) { currentBook = book }

    /**
     * Write a note — typed or hand-written, the same Draw / Type choice the reply composer
     * offers, because a note against a book is the same act as a reply against a thread.
     * Saves onto [existing] when editing, or as a new book-level [BookNote.NOTE] when null.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun editNote(context: Context, existing: BookNote?, onSaved: () -> Unit) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val pad = InkPadView(context)
        var textMode = existing?.image == null

        val drawTab = TextView(context).apply { text = "✍ Draw"; textSize = 15f; setPadding(0, px(2), px(20), px(6)) }
        val typeTab = TextView(context).apply { text = "⌨ Type"; textSize = 15f; setPadding(0, px(2), 0, px(6)) }
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; addView(drawTab); addView(typeTab)
        }

        val inkFrame = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK); setPadding(px(2), px(2), px(2), px(2))
            addView(pad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(300)))
        }
        val inkPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(InkPadView.penBar(context, pad))
            addView(inkFrame)
        }
        val input = EditText(context).apply {
            hint = "A note on this book…"
            setSingleLine(false); minLines = 5; gravity = Gravity.TOP
            setPadding(px(10), px(10), px(10), px(10))
            setText(existing?.note.orEmpty())
        }
        val pane = FrameLayout(context).apply { addView(inkPane); addView(input) }

        fun applyMode() {
            inkPane.visibility = if (textMode) View.GONE else View.VISIBLE
            input.visibility = if (textMode) View.VISIBLE else View.GONE
            drawTab.paintFlags = if (textMode) 0 else android.graphics.Paint.UNDERLINE_TEXT_FLAG
            typeTab.paintFlags = if (textMode) android.graphics.Paint.UNDERLINE_TEXT_FLAG else 0
            drawTab.setTypeface(null, if (textMode) Typeface.NORMAL else Typeface.BOLD)
            typeTab.setTypeface(null, if (textMode) Typeface.BOLD else Typeface.NORMAL)
        }
        drawTab.setOnClickListener { textMode = false; applyMode() }
        typeTab.setOnClickListener { textMode = true; applyMode() }
        applyMode()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(px(14), px(12), px(14), px(4))
            addView(tabRow); addView(pane)
        }

        AlertDialog.Builder(context)
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                val typed = input.text.toString().trim()
                val drawn = if (textMode) null else pad.render()?.let { bmp ->
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                    android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                }
                // Nothing written in either mode is a cancel, not an empty note.
                if (typed.isBlank() && drawn == null && existing == null) return@setPositiveButton
                val now = System.currentTimeMillis()
                val note = existing?.copy(
                    note = if (textMode) typed else existing.note,
                    image = drawn ?: existing.image,
                    updatedAt = now
                ) ?: BookNote(
                    id = BookNote.newId(), type = BookNote.NOTE,
                    note = typed, image = drawn, createdAt = now, updatedAt = now
                )
                BookNoteStore.put(context, currentBook, note)
                onSaved()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rule(context: Context) = View(context).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.density).toInt().coerceAtLeast(1))
    }
}
