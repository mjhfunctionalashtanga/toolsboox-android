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
    private val onStar: (FeedEntry) -> Unit,
    private val onLongPress: (FeedEntry) -> Unit = {}
) : RecyclerView.Adapter<FeedEntryAdapter.Holder>() {

    /** Accessibility: row text + thumbnail size tier — "small" | "medium" | "large" (wrench). */
    var textTier: String = "medium"
        set(value) {
            if (field != value) { field = value; applyTier(); notifyDataSetChanged() }
        }

    // Per-tier row anatomy, precomputed once per dial change (never per bind — one clean pass
    // over the list, no per-row recalculation). Every tier keeps a description row ("another
    // row for description isn't so bad"): small trims it to 1 line for density, large gets 3.
    //   small  — title 16sp ×2 · blurb 14sp ×1 · 60dp thumb
    //   medium — title 20sp ×2 · blurb 17.5sp ×2 · 76dp thumb
    //   large  — title 23sp ×3 · blurb 20sp ×3 · 116dp thumb
    private var scale = 1.25f
    private var thumbDp = 76
    private var titleLines = 2
    private var blurbLines = 2

    private fun applyTier() {
        scale = when (textTier) { "small" -> 1.0f; "large" -> 1.45f; else -> 1.25f }
        thumbDp = when (textTier) { "small" -> 60; "large" -> 116; else -> 76 }
        titleLines = if (textTier == "large") 3 else 2
        blurbLines = when (textTier) { "small" -> 1; "large" -> 3; else -> 2 }
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
        // Ladder recalibrated on the Tab Mini C (07-24): every tier read too small at reading
        // distance — Small was squinting, Medium barely better, Large nearly right. The whole
        // ladder steps up: Small ≈ the old Medium, Medium ≈ the old Large, Large a notch past it.
        // (Values precomputed in applyTier(); this just applies them, guarded so an unchanged
        // thumbnail size never touches layoutParams — one clean redraw per dial change.)
        holder.title.textSize = 16f * scale
        holder.title.maxLines = titleLines
        holder.meta.textSize = 12f * scale
        holder.blurb.textSize = 14f * scale
        holder.blurb.maxLines = blurbLines
        val side = (thumbDp * holder.image.resources.displayMetrics.density).toInt()
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

        // Description row: the entry's excerpt, present at every tier — but an entry with no
        // excerpt shouldn't pay an empty line's height for it.
        holder.blurb.text = e.blurb
        holder.blurb.visibility = if (e.blurb.isBlank()) View.GONE else View.VISIBLE
        holder.star.text = if (e.starred) "★" else "☆"
        // The glyph, not the vibe: reading faces (Fast Mono et al.) draw ★ hollow or substitute
        // it, so a starred row read as unstarred on device. System face keeps the fill solid;
        // the unstarred outline goes quiet gray so the contrast states are unmistakable.
        holder.star.typeface = android.graphics.Typeface.DEFAULT
        holder.star.setTextColor(if (e.starred) 0xFF000000.toInt() else 0xFF9A9A9A.toInt())
        // Inline star: a compact glyph on the meta line (a notch above the meta size at every
        // tier), so the title and thumbnail keep the row's width. Its VISUAL size stays small…
        holder.star.textSize = 14f * scale
        holder.star.contentDescription = if (e.starred) "Unstar" else "Star"
        holder.itemView.setOnClickListener { onOpen(e) }   // fragment marks read per the user's setting
        holder.itemView.setOnLongClickListener { onLongPress(e); true }   // → mark everything above read
        holder.star.setOnClickListener { onStar(e) }
        // …while its TOUCH target grows to ≥44dp via a TouchDelegate on the row: taps in the
        // halo land on the star (toggle), taps anywhere else on the row still open the entry.
        holder.itemView.post {
            val row = holder.itemView as? ViewGroup ?: return@post
            if (holder.star.width == 0) return@post
            val rect = android.graphics.Rect(0, 0, holder.star.width, holder.star.height)
            row.offsetDescendantRectToMyCoords(holder.star, rect)
            val need = (44 * row.resources.displayMetrics.density).toInt()
            rect.inset(
                -((need - rect.width()) / 2).coerceAtLeast(0),
                -((need - rect.height()) / 2).coerceAtLeast(0)
            )
            row.touchDelegate = android.view.TouchDelegate(rect, holder.star)
        }
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
        val cached = FeedThumbCache.get(url)
        if (cached != null) { view.setImageBitmap(cached); view.visibility = View.VISIBLE; return }
        view.visibility = View.GONE
        FeedThumbCache.load(url) { bmp ->
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

/** Tiny async image loader for feed thumbnails — memory-cached, off-thread, no extra deps.
 *  Visible beyond the adapter so "grams for stars" can reuse the row's already-loaded
 *  thumbnail for the intake card face instead of re-downloading it ([LruCache] is thread-safe). */
object FeedThumbCache {
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
