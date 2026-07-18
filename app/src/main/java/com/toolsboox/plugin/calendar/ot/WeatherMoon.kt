package com.toolsboox.plugin.calendar.ot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The little "☀ 72° · 🌒 Waxing Crescent" line on the day page's Notes & Other events header.
 *
 * Moon phase is a pure local calculation (no network). Weather is fetched by IP geolocation
 * (no GPS / no permissions — fine on a Boox) from Open-Meteo, and cached for an hour.
 */
object WeatherMoon {
    private const val PREFS = "weather_moon"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_FETCHED = "fetched_at"
    private const val TTL_MS = 60 * 60 * 1000L

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

    /** The full line for [date] — weather (if cached) then the moon phase with its name. */
    fun summary(context: Context, date: LocalDate): String {
        val (glyph, name) = moon(date)
        val weather = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SUMMARY, null)
        return if (weather.isNullOrBlank()) "$glyph $name" else "$weather · $glyph $name"
    }

    /** Compact badge for the narrow Notes & Other events bar — weather (if cached) + moon glyph. */
    fun headerBadge(context: Context, date: LocalDate): String {
        val glyph = moon(date).first
        val weather = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SUMMARY, null)
        return if (weather.isNullOrBlank()) glyph else "$weather  $glyph"
    }

    /** Refresh the cached weather by IP location. Returns true if the cache changed. Safe to call often. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_FETCHED, 0L) < TTL_MS && prefs.contains(KEY_SUMMARY)) {
            return@withContext false
        }
        try {
            // ipwho.is: HTTPS, keyless, no rate-limit blocking (ipapi.co rate-limits datacenter IPs).
            val loc = JSONObject(URL("https://ipwho.is/").readText())
            val lat = loc.getDouble("latitude")
            val lon = loc.getDouble("longitude")
            val wx = JSONObject(
                URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code&temperature_unit=fahrenheit").readText()
            ).getJSONObject("current")
            val summary = "${glyphFor(wx.getInt("weather_code"))} ${wx.getDouble("temperature_2m").toInt()}°"
            val before = prefs.getString(KEY_SUMMARY, null)
            prefs.edit().putString(KEY_SUMMARY, summary).putLong(KEY_FETCHED, System.currentTimeMillis()).apply()
            summary != before
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
