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
    REPLY("Reply", "↩"),
    /** OCR'd handwriting — page sections and off-planner annotations from the corpus.
     *  Only search produces these rows; the browse walk doesn't carry them. */
    INK("Handwriting", "✍")
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
    val starred: Boolean = false,
    /** The note page this item lives on (OCR section / planner text), so opening it lands on the
     *  right SURFACE of its day, not just the day. [ReadingLogFragment.PAGEKEY_TEXT_NOTES] routes
     *  to the Text Notes screen instead. */
    val pageKey: String? = null,
    /** Group header drawn above this row ("Matches", "Related — semantic") — search results only. */
    val header: String? = null,
    /** The corpus citation for a corpus-surfaced row — the key "Remove from corpus" tombstones. */
    val citation: String? = null,
    /** The Log line's own id — an article highlight's opens the piece ON its passage (Michael
     *  2026-10-12, the highlights plan: "Log → the passage", one tap). */
    val eventId: String? = null,
    /** The typed note, UNDER the passage (add-to-pick pin §6 — it used to hide behind the excerpt).
     *  Empty when the body IS the note. */
    val note: String = "",
    /** What rides on the line, in the order it was added — the row's strip (pin §6). */
    val media: List<LogMedia> = emptyList(),
) {
    /** The line's videos — each a ▶ tile that plays the VIDEO (pin §6). */
    val videos: List<LogMedia> get() = media.filter { it.kind == com.toolsboox.da.Attachment.Kind.VIDEO }
}

/** One thing on a Log line: a photo or the handwriting (tap → full screen), a video (▶ plays it),
 *  a voice note (▶ Play · m:ss). [path] is local and may not be here yet. */
data class LogMedia(val kind: com.toolsboox.da.Attachment.Kind, val path: String, val seconds: Int = 0, val ink: Boolean = false)

/**
 * Renders the Notes & Annotations log — one card per [LogItem]. A tap invokes [onOpen]
 * (host opens the article URL, plays the gram, or notes where a book highlight lives).
 */
class ReadingEventAdapter(
    private var items: List<LogItem>,
    private val onOpen: (LogItem) -> Unit,
    private val onLong: (LogItem) -> Unit = {},
    /** A tap on the strip: the photo / handwriting full screen, the video played, the voice note. */
    private val onMedia: (LogMedia) -> Unit = {},
) : RecyclerView.Adapter<ReadingEventAdapter.Holder>() {

    /** The live query — rows bold its tokens so the eye lands on WHY each result is here. */
    var highlight: String = ""

    fun submit(list: List<LogItem>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val header: TextView = view.findViewById(R.id.event_header)
        val mark: TextView = view.findViewById(R.id.event_mark)
        val title: TextView = view.findViewById(R.id.event_title)
        val meta: TextView = view.findViewById(R.id.event_meta)
        val excerpt: TextView = view.findViewById(R.id.event_excerpt)
        val thumb: android.widget.ImageView = view.findViewById(R.id.event_thumb)
        val note: TextView = view.findViewById(R.id.event_note)
        val strip: android.widget.LinearLayout = view.findViewById(R.id.event_strip)
        val stripScroll: View = view.findViewById(R.id.event_strip_scroll)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_reading_event, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        holder.header.visibility = if (e.header == null) View.GONE else View.VISIBLE
        holder.header.text = e.header ?: ""
        holder.mark.text = e.origin.mark
        val title = if (e.starred) "★ ${e.title}" else e.title
        holder.title.text = if (highlight.isBlank()) title
            else com.toolsboox.plugin.calendar.ot.LedgerSearch.emphasize(highlight, title)
        // A voice memo advertises itself with a ▶ chip in the meta line.
        holder.meta.text = if (e.audioPath != null)
            listOf("▶ Voice memo", e.meta).filter { it.isNotBlank() }.joinToString("  ·  ")
        else e.meta
        holder.excerpt.visibility = if (e.body.isBlank()) View.GONE else View.VISIBLE
        holder.excerpt.text = if (highlight.isBlank()) e.body
            else com.toolsboox.plugin.calendar.ot.LedgerSearch.emphasize(highlight, e.body)

        holder.note.visibility = if (e.note.isBlank()) View.GONE else View.VISIBLE
        holder.note.text = if (highlight.isBlank()) e.note
            else com.toolsboox.plugin.calendar.ot.LedgerSearch.emphasize(highlight, e.note)
        bindStrip(holder, e)

        // A line with a strip shows its pictures there; the lone thumb is for everything else.
        val path = e.imagePath.takeIf { e.media.isEmpty() }
        holder.thumb.tag = path
        val cached = path?.let { ThumbCache.get(it) }
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
            holder.thumb.visibility = View.VISIBLE
        } else if (path != null) {
            // Decode off the main thread (audit 09-10): a fling through a log of photo picks
            // used to decode one 12-MP file per bound row on the UI thread.
            holder.thumb.setImageDrawable(null)
            holder.thumb.visibility = View.GONE
            ThumbCache.load(path) { bmp ->
                if (holder.thumb.tag == path && bmp != null) {
                    holder.thumb.setImageBitmap(bmp)
                    holder.thumb.visibility = View.VISIBLE
                }
            }
        } else {
            holder.thumb.setImageDrawable(null)
            holder.thumb.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onOpen(e) }
        holder.itemView.setOnLongClickListener { onLong(e); true }
    }

    /** The strip: photos and handwriting as thumbs, a video as its first frame with ▶, a voice note
     *  as ▶ Play · m:ss — each tap opens or plays THAT one. */
    private fun bindStrip(holder: Holder, e: LogItem) {
        holder.strip.removeAllViews()
        holder.stripScroll.visibility = if (e.media.isEmpty()) View.GONE else View.VISIBLE
        if (e.media.isEmpty()) return
        val ctx = holder.itemView.context
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        for (m in e.media) {
            val v: View = when (m.kind) {
                com.toolsboox.da.Attachment.Kind.AUDIO -> TextView(ctx).apply {
                    text = "▶ Play · " + com.toolsboox.plugin.calendar.ot.PickFaceLayout.clock(m.seconds)
                    textSize = 14f; setTextColor(0xFF000000.toInt())
                    setBackgroundResource(android.R.drawable.btn_default)
                    setPadding(px(10), px(6), px(10), px(6))
                }
                else -> android.widget.FrameLayout(ctx).apply {
                    val img = android.widget.ImageView(ctx).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(if (m.kind == com.toolsboox.da.Attachment.Kind.VIDEO) 0xFF1F1F1F.toInt() else 0xFFEEEEEE.toInt())
                    }
                    addView(img, android.widget.FrameLayout.LayoutParams(px(if (m.ink) 140 else 96), px(96)))
                    if (m.kind == com.toolsboox.da.Attachment.Kind.VIDEO) addView(TextView(ctx).apply {
                        text = "▶"; textSize = 26f; setTextColor(0xFFFFFFFF.toInt())
                        gravity = android.view.Gravity.CENTER
                    }, android.widget.FrameLayout.LayoutParams(px(96), px(96)))
                    val key = m.path + (if (m.kind == com.toolsboox.da.Attachment.Kind.VIDEO) "#frame" else "")
                    img.tag = key
                    val cached = ThumbCache.get(key)
                    if (cached != null) img.setImageBitmap(cached)
                    else ThumbCache.load(m.path, video = m.kind == com.toolsboox.da.Attachment.Kind.VIDEO) { bmp ->
                        if (img.tag == key && bmp != null) img.setImageBitmap(bmp)
                    }
                }
            }
            v.setOnClickListener { onMedia(m) }
            holder.strip.addView(v, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = px(8) })
        }
    }
}

/** Row thumbnails: decoded on IO, sampled down, kept in a small LRU keyed by path. */
private object ThumbCache {
    private val lru = android.util.LruCache<String, android.graphics.Bitmap>(32)
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun get(path: String): android.graphics.Bitmap? = lru.get(path)

    fun load(path: String, video: Boolean = false, onReady: (android.graphics.Bitmap?) -> Unit) {
        io.execute {
            val key = if (video) "$path#frame" else path
            val bmp = runCatching {
                if (!java.io.File(path).exists()) null
                // A video's tile is its first frame (AvPoster's frame grab — add-to-pick pin §6).
                else if (video) com.toolsboox.plugin.calendar.ot.AvPoster.firstFrame(java.io.File(path))?.let { f ->
                    val r = 240f / maxOf(f.width, f.height).coerceAtLeast(1)
                    if (r < 1f) android.graphics.Bitmap.createScaledBitmap(f, (f.width * r).toInt().coerceAtLeast(1),
                        (f.height * r).toInt().coerceAtLeast(1), true) else f
                }
                else android.graphics.BitmapFactory.decodeFile(path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
            }.getOrNull()
            if (bmp != null) lru.put(key, bmp)
            main.post { onReady(bmp) }
        }
    }
}
