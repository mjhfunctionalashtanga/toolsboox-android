package com.toolsboox.ot

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.toolsboox.plugin.calendar.ot.LedgerTags

/**
 * Tag anything — including the things with nowhere to write a `#`.
 *
 * Tagging has always meant WRITING the tag: on a page, in a box. That works for pages and leaves
 * out everything else — a recording, an email, a book, an archive post. There is no text to put a
 * hash into, so none of them could be named.
 *
 * A PICKER, not a text field, and that's the load-bearing decision: tags are the naming system
 * here, so a vocabulary that splinters into `#ashtanga`, `#Ashtanga` and `#ashtnga` stops doing
 * the job. You choose from what you already say; typing is the last resort rather than the only
 * route.
 *
 * One dialog for tagging AND untagging: what's already on the object shows ✓, and tapping it again
 * severs the edge. A separate "remove a tag" surface would be a second thing to find for one
 * question. The iPad's `TagPickerSheet`, in the idiom this codebase uses for modals.
 */
object TagPicker {

    /**
     * Show the picker for [uri].
     *
     * [onChanged] fires after any add or remove so a caller can redraw a tag strip. The dialog
     * rebuilds itself in place on each toggle — on e-ink a full redraw per tap is cheaper than any
     * incremental scheme, and it keeps the ✓ marks honest with no diffing.
     */
    fun show(
        context: Context,
        uri: String,
        label: String,
        showModal: (AlertDialog) -> Unit,
        onChanged: () -> Unit = {}
    ) {
        if (uri.isBlank()) return
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(8))
        }
        val dialog = AlertDialog.Builder(ModalScale.wrap(context))
            .setTitle(label.ifBlank { "Tag" })
            .setView(ScrollView(context).apply { addView(col) })
            .setNegativeButton(android.R.string.ok, null)
            .create()

        fun rebuild() {
            col.removeAllViews()
            val chosen = LedgerTags.tagsOn(context, uri).toMutableSet()
            val all = LedgerTags.list(context).map { it.tag }

            // What you'd plausibly reach for: what's already on it, then its co-occurrences, then
            // the most-used overall. relatedTags has existed for a while and fed one row in the
            // rhizome; this is the use it was built for.
            val suggested = LinkedHashSet<String>(chosen)
            for (t in chosen.toList()) {
                LedgerTags.relatedTags(context, t).take(4).forEach { suggested.add(it.first) }
            }
            all.take(8).forEach { suggested.add(it) }

            fun addRow(tag: String) {
                col.addView(TextView(context).apply {
                    text = if (chosen.contains(tag)) "✓  #$tag" else "    #$tag"
                    textSize = 16f
                    setTextColor(0xFF000000.toInt())
                    setPadding(px(4), px(10), px(4), px(10))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener {
                        if (chosen.contains(tag)) LedgerTags.untag(context, uri, tag)
                        else LedgerTags.recordObject(context, uri, label, "#$tag")
                        onChanged()
                        rebuild()
                    }
                })
            }

            fun header(t: String) = col.addView(TextView(context).apply {
                text = t
                textSize = 12f; setTextColor(0xFF888888.toInt())
                setPadding(px(4), px(12), px(4), px(2))
            })

            if (suggested.isNotEmpty()) {
                header("SUGGESTED")
                suggested.forEach { addRow(it) }
            }
            val rest = all.filterNot { suggested.contains(it) }
            if (rest.isNotEmpty()) {
                header("ALL TAGS")
                rest.forEach { addRow(it) }
            }
            if (all.isEmpty() && chosen.isEmpty()) {
                col.addView(TextView(context).apply {
                    text = "No tags yet — make one below."
                    textSize = 14f; setTextColor(0xFF888888.toInt())
                    setPadding(px(4), px(10), px(4), px(10))
                })
            }

            // Making a new one, last: the fallback, not the front door.
            header("MAKE A TAG")
            val input = EditText(context).apply {
                hint = "new tag"; isSingleLine = true; textSize = 15f
            }
            col.addView(input)
            col.addView(TextView(context).apply {
                text = "＋  Add"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                gravity = Gravity.END
                setPadding(px(4), px(8), px(4), px(4))
                setOnClickListener {
                    // Through the REAL extractor, so the picker can't mint a tag the harvest
                    // itself would reject — no private dialect.
                    val t = LedgerTags.extract("#" + input.text.toString().trim().trimStart('#')).firstOrNull()
                    if (t != null) {
                        LedgerTags.recordObject(context, uri, label, "#$t")
                        onChanged()
                        rebuild()
                    }
                }
            })
            LedgerFonts.applyTree(col)
        }

        rebuild()
        showModal(dialog)
    }
}
