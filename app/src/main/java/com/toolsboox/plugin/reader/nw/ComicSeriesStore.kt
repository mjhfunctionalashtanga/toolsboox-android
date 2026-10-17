package com.toolsboox.plugin.reader.nw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * THE SERIES' OWN RECORD (Michael, 09-13: "when I download it to my app I get it and the tracker
 * info, and the downloaded comic can go to the drive or dav"): what the source knew about a
 * series when its chapters were fetched — title, cover, description, author/artist, genre,
 * status, the source and the series' page there, the chapter list — kept as a sidecar
 * (`comics/series/<series>.json`, cover inline) that rides the same sync the reading positions
 * ride (Drive or Ultrabridge), so every Ledgable that receives the chapters through the hub also
 * receives the series' face. Written on the first chapter download, refreshed when the chapter
 * list is fetched again.
 */
object ComicSeriesStore {
    data class Series(
        val title: String, val folder: String, val source: String, val lang: String, val url: String,
        val description: String, val author: String, val artist: String, val genre: String, val status: String,
        val chapters: List<Pair<String, Float>>, val coverB64: String, val updatedAt: Long,
    )

    private const val DIR = "comics/series"
    fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9 ._\\-]"), "_").trim().take(80)
    private fun path(folder: String) = "$DIR/${safe(folder.substringAfterLast('/'))}.json"

    private fun cacheDir(ctx: Context) = java.io.File(ctx.filesDir, "comic-series").apply { mkdirs() }

    fun get(ctx: Context, folder: String): Series? {
        val f = java.io.File(cacheDir(ctx), safe(folder.substringAfterLast('/')) + ".json")
        val text = (if (f.exists()) f.readText() else null)
            ?: runCatching { com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(ctx, path(folder)) }.getOrNull()
            ?: return null
        return runCatching { parse(text) }.getOrNull()?.also { if (!f.exists()) f.writeText(text) }
    }

    fun record(ctx: Context, s: Series) {
        val o = JSONObject().put("title", s.title).put("folder", s.folder).put("source", s.source).put("lang", s.lang).put("url", s.url)
            .put("description", s.description).put("author", s.author).put("artist", s.artist).put("genre", s.genre).put("status", s.status)
            .put("chapters", JSONArray().also { arr -> s.chapters.forEach { (n, num) -> arr.put(JSONObject().put("name", n).put("number", num.toDouble())) } })
            .put("coverB64", s.coverB64).put("updatedAt", s.updatedAt)
        val text = o.toString()
        runCatching { java.io.File(cacheDir(ctx), safe(s.folder.substringAfterLast('/')) + ".json").writeText(text) }
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            runCatching { com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(ctx, path(s.folder), text) }
                .onFailure { Timber.w(it, "ComicSeriesStore: push failed") }
        }
    }

    private fun parse(text: String): Series {
        val o = JSONObject(text)
        val ch = o.optJSONArray("chapters") ?: JSONArray()
        return Series(o.optString("title"), o.optString("folder"), o.optString("source"), o.optString("lang"), o.optString("url"),
            o.optString("description"), o.optString("author"), o.optString("artist"), o.optString("genre"), o.optString("status"),
            (0 until ch.length()).map { i -> ch.getJSONObject(i).let { it.optString("name") to it.optDouble("number", 0.0).toFloat() } },
            o.optString("coverB64"), o.optLong("updatedAt"))
    }

    fun cover(s: Series): android.graphics.Bitmap? = runCatching {
        if (s.coverB64.isBlank()) return null
        val bytes = android.util.Base64.decode(s.coverB64, android.util.Base64.NO_WRAP)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** The record, made if missing from the person's server (Suwayomi) — the source's face of
     *  a series the shelf only has as files (a Mihon download, a hub arrival). */
    fun ensure(ctx: Context, folder: String): Series? {
        get(ctx, folder)?.let { return it }
        val srv = TrackerBridge.server(ctx) ?: return null
        val (id, _) = runCatching { TrackerBridge.findSeries(srv, folder) }.getOrNull() ?: return null
        return recordFromServer(ctx, srv, id, folder)
    }

    /** The server's knowledge of manga [mangaId] → the record for [folder]. */
    fun recordFromServer(ctx: Context, srv: TrackerBridge.Server, mangaId: Int, folder: String): Series? = runCatching {
        val m = TrackerBridge.gql(srv, "query(\$id: Int!) { manga(id: \$id) { title description author artist genre status thumbnailUrl realUrl source { name lang } chapters { nodes { name chapterNumber } } } }",
            JSONObject().put("id", mangaId)).optJSONObject("manga") ?: return@runCatching null
        val genre = m.optJSONArray("genre")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
        val thumb = m.optString("thumbnailUrl").takeIf { it.isNotBlank() }?.let { rel ->
            runCatching {
                val url = if (rel.startsWith("http")) rel else srv.base + rel
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                if (srv.user.isNotBlank() || srv.pass.isNotBlank())
                    conn.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("${srv.user}:${srv.pass}".toByteArray(), android.util.Base64.NO_WRAP))
                val bytes = conn.inputStream.use { it.readBytes() }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val scale = 600f / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
                val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
                val bos = java.io.ByteArrayOutputStream(); small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos)
                android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull()
        }.orEmpty()
        val ch = m.optJSONObject("chapters")?.optJSONArray("nodes") ?: JSONArray()
        val chapters = (0 until ch.length()).map { i -> ch.getJSONObject(i).let { it.optString("name") to it.optDouble("chapterNumber", 0.0).toFloat() } }.sortedBy { it.second }
        val status = when (m.optString("status")) { "ONGOING" -> "Ongoing"; "COMPLETED" -> "Completed"; "LICENSED" -> "Licensed"; "PUBLISHING_FINISHED" -> "Publishing finished"; "CANCELLED" -> "Cancelled"; "ON_HIATUS" -> "On hiatus"; else -> "" }
        val series = Series(
            title = m.optString("title"), folder = folder, source = m.optJSONObject("source")?.optString("name").orEmpty(),
            lang = m.optJSONObject("source")?.optString("lang").orEmpty(), url = m.optString("realUrl"),
            description = m.optString("description"), author = m.optString("author"), artist = m.optString("artist"),
            genre = genre, status = status, chapters = chapters, coverB64 = thumb, updatedAt = System.currentTimeMillis())
        record(ctx, series); series
    }.onFailure { Timber.w(it, "ComicSeriesStore: recordFromServer failed") }.getOrNull()

    fun statusName(code: Int) = when (code) { 1 -> "Ongoing"; 2 -> "Completed"; 3 -> "Licensed"; 4 -> "Publishing finished"; 5 -> "Cancelled"; 6 -> "On hiatus"; else -> "" }
}
