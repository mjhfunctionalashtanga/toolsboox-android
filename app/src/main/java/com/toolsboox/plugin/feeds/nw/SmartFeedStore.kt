package com.toolsboox.plugin.feeds.nw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A smart feed = a saved search (keyword rule). Parity with the iOS `SmartFeed`.
 * Resolves live: the Miniflux `search=` endpoint when online, a filter over the
 * last-loaded entries when offline.
 */
data class SmartFeed(
    val id: String,
    val name: String,
    val query: String,
    val unreadOnly: Boolean = false
)

/** On-device store of smart feeds (SharedPreferences, JSON-encoded). */
object SmartFeedStore {
    private const val PREFS = "smart_feeds"
    private const val KEY = "list"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<SmartFeed> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SmartFeed(
                    o.getString("id"), o.getString("name"), o.getString("query"),
                    o.optBoolean("unreadOnly", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<SmartFeed>) {
        val arr = JSONArray()
        for (f in list) {
            arr.put(JSONObject().apply {
                put("id", f.id); put("name", f.name); put("query", f.query); put("unreadOnly", f.unreadOnly)
            })
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, feed: SmartFeed) = save(context, all(context) + feed)

    fun remove(context: Context, id: String) =
        save(context, all(context).filter { it.id != id })
}
