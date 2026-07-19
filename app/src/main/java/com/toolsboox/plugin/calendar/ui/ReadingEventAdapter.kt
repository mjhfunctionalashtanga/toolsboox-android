package com.toolsboox.plugin.calendar.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.toolsboox.R

/** The origin of a log item — the filter dimension in the Notes & Annotations log. */
enum class LogOrigin(val label: String, val mark: String) {
    BOOK("Book", "✎"),
    READ("Read", "★"),
    WATCH("Watch", "▶"),
    LISTEN("Listen", "♪"),
    PICKING("Picking", "❝"),
    AV("AV", "◉"),
    TASK("Task", "☑"),
    NOTE("Note", "✒"),
    CARD("Card", "▦"),
    /** Feed articles pulled in by the Log's opt-in feed toggle (never shown by default). */
    FEED("Feed", "📰"),
    /** Correspondence — "the Ledger writing back": community replies, and any reading
     *  event flagged as correspondence (source starts with "↩"). Never a feed. */
    REPLY("Reply", "↩")
}

/** One row in the Notes & Annotations log, flattened from a reading event or an AV gram. */
data class LogItem(
    val origin: LogOrigin,
    val title: String,
    val meta: String,
    val body: String,
    val url: String?,
    val millis: Long,
    /** Local image path (panel card or photo attachment); shown as a thumbnail when present. */
    val imagePath: String? = null,
    /** Local voice-memo path; shows a ▶ chip and plays on tap. */
    val audioPath: String? = null,
    /** The day page this item lives on — the rhizome edge back to its home. */
    val day: java.time.LocalDate? = null,
    /** Starred — filterable via the Log's ★ toggle and shown as a ★ title prefix. */
    val starred: Boolean = false
)

/**
 * Renders the Notes & Annotations log — one card per [LogItem]. A tap invokes [onOpen]
 * (host opens the article URL, plays the gram, or notes where a book highlight lives).
 */
class ReadingEventAdapter(
    private var items: List<LogItem>,
    private val onOpen: (LogItem) -> Unit,
    private val onLong: (LogItem) -> Unit = {}
) : RecyclerView.Adapter<ReadingEventAdapter.Holder>() {

    fun submit(list: List<LogItem>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val mark: TextView = view.findViewById(R.id.event_mark)
        val title: TextView = view.findViewById(R.id.event_title)
        val meta: TextView = view.findViewById(R.id.event_meta)
        val excerpt: TextView = view.findViewById(R.id.event_excerpt)
        val thumb: android.widget.ImageView = view.findViewById(R.id.event_thumb)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_reading_event, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        holder.mark.text = e.origin.mark
        holder.title.text = if (e.starred) "★ ${e.title}" else e.title
        // A voice memo advertises itself with a ▶ chip in the meta line.
        holder.meta.text = if (e.audioPath != null)
            listOf("▶ Voice memo", e.meta).filter { it.isNotBlank() }.joinToString("  ·  ")
        else e.meta
        holder.excerpt.visibility = if (e.body.isBlank()) View.GONE else View.VISIBLE
        holder.excerpt.text = e.body

        val path = e.imagePath
        if (path != null && java.io.File(path).exists()) {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            holder.thumb.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path, opts))
            holder.thumb.visibility = View.VISIBLE
        } else {
            holder.thumb.setImageDrawable(null)
            holder.thumb.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onOpen(e) }
        holder.itemView.setOnLongClickListener { onLong(e); true }
    }
}
