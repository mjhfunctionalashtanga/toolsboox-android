package com.toolsboox.plugin.feeds.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.toolsboox.R
import com.toolsboox.plugin.feeds.da.FeedEntry

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
    }
}
