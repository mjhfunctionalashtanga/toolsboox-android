package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last few things the spiral surfaced, so the widget has something to show.
 *
 * The widget cannot compute a pick. Choosing one means walking every day file — a hundred
 * megabytes of JSON on this device — and a widget update has neither the time nor the process to
 * do that in. So the APP writes what it chose, and the widget reads it. A tiny cache, refilled
 * whenever you open a day.
 *
 * A RING rather than a single value because the widget's job here is different from the page's.
 * The page shows what is due to come back today. The home screen is glanced at twenty times a
 * day, and the same sentence twenty times is wallpaper — it stops being read. Rotating through
 * the last few keeps it worth looking at, without the widget having to decide anything.
 *
 * Deliberately not authoritative: this is a cache of things that have already been chosen
 * properly elsewhere. If it is empty or stale the widget shows the band empty, which is correct —
 * better a quiet space than a wrong claim about what you have been thinking about.
 */
object SpiralRing {

    private const val PREFS = "ledger_spiral_ring"
    private const val KEY = "items"

    /** How many to keep. Enough that a glance rarely repeats, few enough to stay a cache. */
    const val CAPACITY = 8

    data class Entry(val text: String, val citation: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Remember [text] as the newest thing surfaced.
     *
     * Newest first, and any earlier copy of the same words is removed rather than duplicated —
     * the spiral will legitimately choose the same item on consecutive days, and a ring holding
     * one sentence eight times rotates through nothing.
     */
    fun push(context: Context, text: String, citation: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val existing = load(context).filterNot { it.text == body }
        val out = (listOf(Entry(body, citation.trim())) + existing).take(CAPACITY)
        val arr = JSONArray()
        for (e in out) arr.put(JSONObject().put("t", e.text).put("c", e.citation))
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Entry(it.optString("t"), it.optString("c")) }
        }.filter { it.text.isNotBlank() }
    }

    /** Which one to show right now. Pure — reading this changes nothing. */
    fun next(context: Context, now: Long = System.currentTimeMillis()): Entry? {
        val items = load(context)
        if (items.isEmpty()) return null
        // Chosen by the CLOCK, not by advancing a stored cursor on every read.
        //
        // A widget's renderer runs once per widget instance per refresh, so two Schedule widgets
        // on the home screen advanced the ring twice per glance and you never saw half of them —
        // and any preview or re-render moved it too. Deriving the position from the hour means
        // every widget agrees with every other, a redraw shows the same thing, and it still turns
        // over as the day goes on. Rendering should not mutate anything.
        val slot = ((now / TURN_MS) % items.size).toInt()
        return items[slot]
    }

    /** How long each entry stays up. Long enough to be read, short enough to change by teatime. */
    private const val TURN_MS = 3 * 60 * 60 * 1000L

    /** What would be shown next, without advancing — for previews and tests. */
    fun peek(context: Context, now: Long = System.currentTimeMillis()): Entry? = next(context, now)
}
