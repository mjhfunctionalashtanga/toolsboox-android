package com.toolsboox.plugin.feeds.nw

import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches + flattens a podcast episode's transcript (RSS podcast:transcript) into readable prose —
 * the Kotlin twin of the iOS `TranscriptLoader`. Handles WebVTT / SRT (drops the header, timestamp
 * cues, and SRT indices), HTML (strips tags), and plain text. Blocking — call off the main thread.
 */
object TranscriptLoader {
    fun fetch(urlString: String): String? = runCatching {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            if (conn.responseCode !in 200..399) return@runCatching null
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            if (raw.isBlank()) return@runCatching null
            val ext = urlString.substringBefore('?').substringAfterLast('.', "").lowercase()
            when {
                ext == "vtt" || ext == "srt" || raw.startsWith("WEBVTT") || raw.contains("-->") -> parseCues(raw)
                raw.contains('<') && raw.contains('>') -> stripHtml(raw)
                else -> raw.trim()
            }
        } finally { conn.disconnect() }
    }.getOrNull()

    private fun parseCues(s: String): String =
        s.lineSequence().map { it.trim() }
            .filter {
                it.isNotEmpty() && !it.startsWith("WEBVTT") && !it.startsWith("NOTE") &&
                    !it.contains("-->") && it.toIntOrNull() == null
            }
            .joinToString(" ").replace(Regex("\\s+"), " ").trim()

    private fun stripHtml(h: String): String =
        h.replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&[a-z]+;"), " ")
            .replace(Regex("\\s+"), " ").trim()
}
