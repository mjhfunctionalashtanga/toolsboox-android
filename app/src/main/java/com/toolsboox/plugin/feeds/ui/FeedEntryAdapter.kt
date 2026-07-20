package com.toolsboox.plugin.feeds.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.toolsboox.R
import com.toolsboox.plugin.feeds.da.FeedEntry
import timber.log.Timber
import java.net.URL
import java.util.concurrent.Executors

/**
 * List of feed entries for the in-app Feed Ledger. Row tap opens the article; the star
 * glyph toggles starred (which writes/removes a ReadingEvent in the day JSON).
 */
class FeedEntryAdapter(
    private var items: List<FeedEntry>,
    private val onOpen: (FeedEntry) -> Unit,
    private val onStar: (FeedEntry) -> Unit
) : RecyclerView.Adapter<FeedEntryAdapter.Holder>() {

    /** Accessibility: row text + thumbnail size tier — "small" | "medium" | "large" (wrench). */
    var textTier: String = "medium"
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.entry_title)
        val meta: TextView = view.findViewById(R.id.entry_meta)
        val blurb: TextView = view.findViewById(R.id.entry_blurb)
        val star: TextView = view.findViewById(R.id.entry_star)
        val image: ImageView = view.findViewById(R.id.entry_image)
    }

    fun submit(list: List<FeedEntry>) {
        items = list
        notifyDataSetChanged()
    }

    /** The currently shown list (for paging in the article reader). */
    fun current(): List<FeedEntry> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_feed_entry, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        val isRead = e.read || FeedReadState.isRead(e.id)

        // Row tier: Small / Medium / Large scales the text and the featured thumbnail together.
        val scale = when (textTier) { "small" -> 0.85f; "large" -> 1.3f; else -> 1f }
        holder.title.textSize = 16f * scale
        holder.meta.textSize = 12f * scale
        holder.blurb.textSize = 14f * scale
        holder.blurb.maxLines = if (textTier == "large") 3 else 2
        val side = ((when (textTier) { "small" -> 60; "large" -> 116; else -> 76 }) *
            holder.image.resources.displayMetrics.density).toInt()
        if (holder.image.layoutParams.width != side) {
            holder.image.layoutParams = holder.image.layoutParams.apply { width = side; height = side }
        }

        holder.title.text = e.title
        // Unread stands out (bold, full weight); read is normal + dimmed.
        holder.title.setTypeface(null, if (isRead) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        holder.title.alpha = if (isRead) 0.55f else 1f

        // Meta: unread ● dot · read/watch/listen lens · the feed folder · author · date + time.
        val dot = if (isRead) "" else "●  "
        val lens = when (e.kind) { "watch" -> "📺"; "listen" -> "🎧"; else -> "📖" }
        val folder = e.categoryLabel?.takeIf { it.isNotBlank() } ?: e.feedTitle
        holder.meta.text = dot + lens + "  " + listOf(folder, e.author ?: "", formatWhen(e.publishedAt))
            .filter { it.isNotBlank() }.joinToString(" · ")

        holder.blurb.text = e.blurb
        holder.star.text = if (e.starred) "★" else "☆"
        holder.itemView.setOnClickListener { onOpen(e) }   // fragment marks read per the user's setting
        holder.star.setOnClickListener { onStar(e) }
        bindImage(holder.image, e.imageUrl)
    }

    /** Format Miniflux's ISO `published_at` to a short local "MMM d, HH:mm". */
    private fun formatWhen(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            java.time.OffsetDateTime.parse(iso)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"))
        }.getOrElse { iso.take(10) }
    }

    /** Load the featured thumbnail off-thread, tagging the view so recycled rows don't cross-wire. */
    private fun bindImage(view: ImageView, url: String?) {
        if (url.isNullOrBlank()) { view.visibility = View.GONE; view.setImageDrawable(null); view.tag = null; return }
        view.tag = url
        val cached = ImageCache.get(url)
        if (cached != null) { view.setImageBitmap(cached); view.visibility = View.VISIBLE; return }
        view.visibility = View.GONE
        ImageCache.load(url) { bmp ->
            if (bmp != null && view.tag == url) { view.setImageBitmap(bmp); view.visibility = View.VISIBLE }
        }
    }
}

/** Session read-tracker: ids of entries opened this session, so the list greys them immediately
 *  without waiting for a re-fetch. The server (Miniflux) is marked read on open too, for durability. */
object FeedReadState {
    private val read = java.util.Collections.synchronizedSet(HashSet<Long>())
    fun isRead(id: Long) = read.contains(id)
    fun mark(id: Long) { read.add(id) }
}

/** Tiny async image loader for feed thumbnails — memory-cached, off-thread, no extra deps. */
private object ImageCache {
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun get(url: String): Bitmap? = cache.get(url)

    fun load(url: String, onDone: (Bitmap?) -> Unit) {
        pool.execute {
            val bmp = runCatching {
                URL(url).openStream().use { s ->
                    // Downsample: thumbnails are ~76dp, so a modest inSampleSize keeps memory low.
                    BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            }.onFailure { Timber.d(it, "thumb load failed: %s", url) }.getOrNull()
            if (bmp != null) cache.put(url, bmp)
            main.post { onDone(bmp) }
        }
    }
}
