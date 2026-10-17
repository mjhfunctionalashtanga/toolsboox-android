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

        // Avatars: base64 → bitmap off the main thread, one decode per contact (audit 09-10).
        val avatarKey = if (c.avatarData.isNotBlank()) c.id + ":" + c.avatarData.length else null
        holder.avatar.tag = avatarKey
        val cached = avatarKey?.let { AvatarCache.get(it) }
        if (cached != null) holder.avatar.setImageBitmap(cached)
        else {
            holder.avatar.setImageResource(R.drawable.ic_calendar_today)
            if (avatarKey != null) AvatarCache.load(avatarKey, c.avatarData) { bmp ->
                if (bmp != null && holder.avatar.tag == avatarKey) holder.avatar.setImageBitmap(bmp)
            }
        }

        holder.itemView.setOnClickListener { onClick(c) }
    }

    fun submit(list: List<Contact>) {
        items = list
        notifyDataSetChanged()
    }
}

private object AvatarCache {
    private val lru = android.util.LruCache<String, android.graphics.Bitmap>(64)
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun get(key: String): android.graphics.Bitmap? = lru.get(key)

    fun load(key: String, base64: String, onReady: (android.graphics.Bitmap?) -> Unit) {
        io.execute {
            val bmp = runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (bmp != null) lru.put(key, bmp)
            main.post { onReady(bmp) }
        }
    }
}
