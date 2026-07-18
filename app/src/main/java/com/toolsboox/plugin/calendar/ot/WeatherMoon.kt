package com.toolsboox.plugin.calendar.ot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The weather + moon strip on the day page's Notes & Other events area:
 *   line 1 — "⛅ 72°  ↑78° ↓61°  💧20%  🌒 Waxing Crescent"  (current, day high/low, precip, moon)
 *   line 2 — a compact horizontal hour-by-hour temperature sparkline for the day.
 *
 * Moon phase is a pure local calculation. Weather is fetched by IP geolocation (no GPS/permission)
 * from Open-Meteo and cached for an hour; it degrades to moon-only when offline.
 */
object WeatherMoon {
    private const val PREFS = "weather_moon"
    private const val TTL_MS = 60 * 60 * 1000L
    private const val K_SUMMARY = "summary"       // "⛅ 72°" (current glyph + temp)
    private const val K_HIGH = "high"
    private const val K_LOW = "low"
    private const val K_PRECIP = "precip"          // day max precipitation probability, %
    private const val K_HOURLY = "hourly"          // comma-joined hourly temps for the day
    private const val K_FETCHED = "fetched_at"
    private const val K_DAY = "day"                // ISO date the cache is for

    private val MOON_GLYPHS = listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")
    private val MOON_NAMES = listOf(
        "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
        "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent"
    )

    /** Moon phase glyph + name for a date (synodic-month calc from a known new moon). */
    fun moon(date: LocalDate): Pair<String, String> {
        val knownNewMoon = LocalDate.of(2000, 1, 6)
        val synodic = 29.53058867
        var p = (ChronoUnit.DAYS.between(knownNewMoon, date) % synodic) / synodic
        if (p < 0) p += 1.0
        val i = ((p * 8) + 0.5).toInt() % 8
        return MOON_GLYPHS[i] to MOON_NAMES[i]
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Header line for [date] — weather (only for today, if cached) then the moon phase. */
    fun summary(context: Context, date: LocalDate): String {
        val (glyph, name) = moon(date)
        val p = prefs(context)
        // Weather is for "today"; on other days show just the moon.
        val current = p.getString(K_SUMMARY, null)
        val forDay = p.getString(K_DAY, null)
        if (current.isNullOrBlank() || forDay != date.toString()) return "$glyph $name"
        val high = p.getInt(K_HIGH, Int.MIN_VALUE)
        val low = p.getInt(K_LOW, Int.MIN_VALUE)
        val precip = p.getInt(K_PRECIP, -1)
        val parts = StringBuilder(current)
        if (high != Int.MIN_VALUE && low != Int.MIN_VALUE) parts.append("  ↑${high}° ↓${low}°")
        if (precip > 0) parts.append("  💧${precip}%")
        parts.append("  $glyph $name")
        return parts.toString()
    }

    /** The day's hourly temperatures for the sparkline (empty if not cached for [date]). */
    fun hourly(context: Context, date: LocalDate): List<Float> {
        val p = prefs(context)
        if (p.getString(K_DAY, null) != date.toString()) return emptyList()
        return p.getString(K_HOURLY, null)?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: emptyList()
    }

    /** Refresh by IP location. Returns true if the cache changed. Safe to call often. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val today = LocalDate.now().toString()
        val fresh = System.currentTimeMillis() - p.getLong(K_FETCHED, 0L) < TTL_MS
        if (fresh && p.getString(K_DAY, null) == today && p.contains(K_SUMMARY)) return@withContext false
        try {
            val loc = JSONObject(URL("https://ipwho.is/").readText())
            val lat = loc.getDouble("latitude")
            val lon = loc.getDouble("longitude")
            val wx = JSONObject(URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&hourly=temperature_2m&temperature_unit=fahrenheit&timezone=auto&forecast_days=1"
            ).readText())

            val cur = wx.getJSONObject("current")
            val summary = "${glyphFor(cur.getInt("weather_code"))} ${cur.getDouble("temperature_2m").toInt()}°"
            val daily = wx.getJSONObject("daily")
            val high = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()
            val low = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()
            val precip = daily.getJSONArray("precipitation_probability_max").optInt(0, 0)
            val hArr = wx.getJSONObject("hourly").getJSONArray("temperature_2m")
            val hourly = (0 until hArr.length()).joinToString(",") { hArr.getDouble(it).toInt().toString() }

            val before = p.getString(K_SUMMARY, null)
            p.edit()
                .putString(K_SUMMARY, summary).putInt(K_HIGH, high).putInt(K_LOW, low)
                .putInt(K_PRECIP, precip).putString(K_HOURLY, hourly)
                .putString(K_DAY, today).putLong(K_FETCHED, System.currentTimeMillis())
                .apply()
            summary != before || p.getString(K_DAY, null) != today
        } catch (_: Exception) {
            false   // offline / rate-limited: keep the last cache, moon phase still shows
        }
    }

    /** WMO weather code → a single glyph. */
    private fun glyphFor(code: Int): String = when (code) {
        0 -> "☀"
        1, 2, 3 -> "⛅"
        45, 48 -> "🌫"
        in 51..67 -> "🌧"
        in 71..77 -> "❄"
        in 80..82 -> "🌦"
        in 95..99 -> "⛈"
        else -> "☁"
    }
}
