package com.toolsboox.plugin.calendar.ui

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.toolsboox.R
import com.toolsboox.plugin.calendar.da.v2.Contact

/**
 * Rolodex list adapter. Mirrors [LedgerItemAdapter]'s constructor-callback pattern: a row tap flows
 * back to the fragment via [onClick]. Avatar is the contact's inline base64 photo, or a placeholder.
 */
class ContactAdapter(
    private var items: List<Contact>,
    private val onClick: (Contact) -> Unit
) : RecyclerView.Adapter<ContactAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.contact_avatar)
        val name: TextView = view.findViewById(R.id.contact_name)
        val sub: TextView = view.findViewById(R.id.contact_sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val c = items[position]
        holder.name.text = c.name.ifBlank { "Unnamed" }
        holder.sub.text = listOf(c.org, c.email, c.phone).firstOrNull { it.isNotBlank() } ?: ""

        val bmp = if (c.avatarData.isNotBlank()) {
            runCatching {
                val bytes = Base64.decode(c.avatarData, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        } else null
        if (bmp != null) holder.avatar.setImageBitmap(bmp)
        else holder.avatar.setImageResource(R.drawable.ic_calendar_today)

        holder.itemView.setOnClickListener { onClick(c) }
    }

    fun submit(list: List<Contact>) {
        items = list
        notifyDataSetChanged()
    }
}
