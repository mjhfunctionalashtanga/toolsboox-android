package com.toolsboox.plugin.calendar.ui

import android.graphics.Paint
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.toolsboox.R
import com.toolsboox.da.Stroke
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer

/**
 * Renders structured [LedgerItem]s (tasks + events). Each row shows ONE face — text or ink —
 * per the item's own [LedgerItem.display]; the ⇄ button flips just that item. Tasks get a
 * checkbox (done, with strikethrough); events show their time. [onChanged] persists edits.
 */
class LedgerItemAdapter(
    private var items: List<LedgerItem>,
    private val strokeById: Map<String, Stroke>,
    private val onChanged: (LedgerItem) -> Unit
) : RecyclerView.Adapter<LedgerItemAdapter.Holder>() {

    fun submit(list: List<LedgerItem>) { items = list; notifyDataSetChanged() }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val lead: TextView = view.findViewById(R.id.li_lead)
        val text: TextView = view.findViewById(R.id.li_text)
        val ink: ImageView = view.findViewById(R.id.li_ink)
        val toggle: ImageButton = view.findViewById(R.id.li_toggle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_ledger_item, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        val isTask = e.kind == LedgerItem.Kind.TASK

        // Lead: checkbox for tasks (tap = done), time for events.
        if (isTask) {
            holder.lead.text = if (e.done) "☑" else "☐"
            holder.lead.setOnClickListener { e.done = !e.done; onChanged(e); notifyItemChanged(position) }
        } else {
            holder.lead.text = e.time ?: "•"
            holder.lead.setOnClickListener(null)
        }

        val showText = e.display == LedgerItem.Display.TEXT
        holder.text.visibility = if (showText) View.VISIBLE else View.GONE
        holder.ink.visibility = if (showText) View.GONE else View.VISIBLE

        if (showText) {
            holder.text.text = e.text
            holder.text.paintFlags =
                if (isTask && e.done) holder.text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else holder.text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        } else {
            val strokes = e.strokeIds.mapNotNull { strokeById[it] }
            if (strokes.isNotEmpty()) {
                val rect = RectF(e.left, e.top, e.right, e.bottom)
                holder.ink.setImageBitmap(runCatching { CalendarPdfRenderer.renderInk(strokes, rect) }.getOrNull())
            } else holder.ink.setImageDrawable(null)
        }

        // Toggle shows the OTHER face's glyph (tap to switch to it).
        holder.toggle.setImageResource(if (showText) R.drawable.ic_pencil else R.drawable.ic_reader_view)
        holder.toggle.setOnClickListener {
            e.display = if (e.display == LedgerItem.Display.TEXT) LedgerItem.Display.INK else LedgerItem.Display.TEXT
            onChanged(e); notifyItemChanged(position)
        }
    }
}
