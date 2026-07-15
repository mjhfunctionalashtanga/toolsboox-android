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
        holder.title.text = e.title
        holder.meta.text = listOf(e.feedTitle, e.author ?: "").filter { it.isNotBlank() }.joinToString(" · ")
        holder.blurb.text = e.blurb
        holder.star.text = if (e.starred) "★" else "☆"
        holder.itemView.setOnClickListener { onOpen(e) }
        holder.star.setOnClickListener { onStar(e) }
        bindImage(holder.image, e.imageUrl)
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
