package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.chat.nw.PersonaStore
import java.io.File

/**
 * The page-voice glue: [SynthVoices]' rows on this device (`correspondence/voices.json`, beside
 * pending.json), a memory cache dropped on every [set] and every merge that changes them, and the
 * sidecar round trip. The rules are all in [SynthVoices] / [SynthVoice] / [SynthPersonas] (pure).
 */
object SynthPageVoices {

    private val lock = Any()

    @Volatile
    private var cache: List<VoiceRow>? = null

    private fun file(context: Context): File =
        File(File(context.filesDir, SynthVoices.DIR).apply { mkdirs() }, SynthVoices.FILE)

    fun rows(context: Context): List<VoiceRow> {
        cache?.let { return it }
        return synchronized(lock) {
            cache ?: run {
                val f = file(context)
                val rows = if (f.exists()) SynthVoices.parse(runCatching { f.readText() }.getOrDefault("")) else emptyList()
                rows.also { cache = it }
            }
        }
    }

    /** Under [lock]: write-then-rename (a kill mid-write must not cost every page its voice). */
    private fun writeLocked(context: Context, rows: List<VoiceRow>) {
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(SynthVoices.encode(rows))
            if (!tmp.renameTo(f)) { f.writeText(SynthVoices.encode(rows)); tmp.delete() }
        }
        cache = rows
    }

    /**
     * Who answers on [key] (any sub-page) of [date]'s day file, resolved on THIS device:
     * [persona] = the live voice ("" = the Correspondent); [missing] = the page's voice when it
     * isn't on this device (the Correspondent answers, and the header and words say so — the row is
     * left alone, ruling 5); [voices] = the page's history.
     */
    data class Voice(val persona: String, val missing: String, val voices: List<String>)

    fun voiceFor(context: Context, date: String, key: String): Voice {
        val row = SynthVoices.rowFor(rows(context), date, key) ?: return Voice(SynthVoice.CORRESPONDENT, "", emptyList())
        val personas = PersonaStore.rows(context)
        val r = SynthPersonas.voice(personas, row.persona)
        // The history as the voices it holds NOW (review of b29cabbc): "Coach" and "Coach — v2"
        // are one voice once Coach was renamed — the header must not read "answering next" for it.
        return Voice(r.persona, r.missing, row.voices.map { SynthPersonas.canonical(personas, it) }.distinct())
    }

    /** The page's top line ([SynthVoice.header]). */
    fun header(context: Context, date: String, key: String): String =
        voiceFor(context, date, key).let { SynthVoice.header(it.persona, it.voices, it.missing) }

    /** He picked [persona] for the page — written at once (before any network call, so a failed
     *  summon retries in the chosen voice), then round-tripped. */
    fun set(context: Context, date: String, key: String, persona: String, hasReply: Boolean) {
        synchronized(lock) {
            writeLocked(context, SynthVoices.set(rows(context), date, key, SynthVoice.normalize(persona),
                System.currentTimeMillis(), hasReply))
        }
        sync(context)
    }

    /** pull → merge → save if changed → push, on the shared sidecar thread. [onChanged] runs there
     *  when the merge changed this device's rows (a page standing on one re-renders its header). */
    fun sync(context: Context, onChanged: (() -> Unit)? = null) {
        val appCtx = context.applicationContext ?: context
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            val local = rows(appCtx)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(appCtx, SynthVoices.REMOTE)
            var changed = false
            val remote = if (remoteText.isNullOrBlank()) null else SynthVoices.parse(remoteText)
            val merged = if (remote == null) local else synchronized(lock) {
                val now = rows(appCtx)
                val m = SynthVoices.merge(now, remote)
                if (m != now) { writeLocked(appCtx, m); changed = true }
                m
            }
            // No write when the remote already holds the merge (review of b29cabbc: every page turn
            // rebuilds the day fragment, and an unconditional PUT per turn is a write for nothing).
            val remoteHolds = remote != null && merged.toSet() == remote.toSet()
            if (!remoteHolds && (merged.isNotEmpty() || remote != null))
                com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(appCtx, SynthVoices.REMOTE, SynthVoices.encode(merged))
            if (changed) onChanged?.invoke()
        }
    }
}

/**
 * Where the voice header was drawn on the standing page — the hit-rect idiom of [PickingsCover] /
 * the dashboard's DashHits: recorded at draw, read by a tap, never a second guess at the layout.
 */
object SynthVoiceHeader {
    /** Per page ("date|key"), so a thumbnail drawn after the standing page can't clobber its rect. */
    private val hits = java.util.concurrent.ConcurrentHashMap<String, android.graphics.RectF>()

    fun record(date: String, key: String, rect: android.graphics.RectF) {
        if (hits.size > 16) hits.clear()
        hits["$date|$key"] = rect
    }

    fun forget(date: String, key: String) { hits.remove("$date|$key") }

    /** A finger is fat: the recorded rect is padded by this much on every side… */
    const val PAD = 16f

    /** …except below [floor] (the frame's top rule): the pad must never reach the writing area,
     *  where a finger tap on the first line would otherwise ask "Who answers next?". */
    fun record(date: String, key: String, rect: android.graphics.RectF, floor: Float) {
        record(date, key, android.graphics.RectF(rect).apply {
            inset(-PAD, -PAD)
            bottom = minOf(bottom, floor)
        })
    }

    /** The header's sizes, largest first: the margin's 25 px small type, stepping down to 19 before
     *  anything is cut. */
    val SIZES = floatArrayOf(25f, 23f, 21f, 19f)

    private const val GLYPH = "❖ "
    private const val ANSWERING = " · answering next"
    private const val NOT_HERE = " isn't on this device"

    data class Fit(val text: String, val size: Float)

    /**
     * [line] ([SynthVoice.header]) fitted to [avail] page px — [measure] is the width of a text at
     * a size. The whole line at the largest size that fits; then, when the voice is [missing], the
     * line without its "The Correspondent · " lead ("❖ Spiral isn't on this device" — who is
     * missing is the half that matters); then the persona's NAME is cut, never the end of the line
     * ("❖ Spi… isn't on this device", "❖ Spi… · answering next"). Pure (review of b29cabbc).
     */
    fun fit(line: String, missing: String, avail: Float, measure: (String, Float) -> Float): Fit {
        for (size in SIZES) if (measure(line, size) <= avail) return Fit(line, size)
        val (name, tail) = when {
            !SynthVoice.isCorrespondent(missing) -> SynthVoice.shortName(missing) to NOT_HERE
            line.endsWith(ANSWERING) -> line.removePrefix(GLYPH).removeSuffix(ANSWERING) to ANSWERING
            else -> line.removePrefix(GLYPH) to ""
        }
        val short = GLYPH + name + tail
        if (short != line) for (size in SIZES) if (measure(short, size) <= avail) return Fit(short, size)
        val min = SIZES.last()
        for (n in name.length - 1 downTo 1) {
            val cut = GLYPH + name.take(n).trimEnd() + "…" + tail
            if (measure(cut, min) <= avail) return Fit(cut, min)
        }
        // Not even a letter of the name fits beside its tail: the end goes, as a last resort.
        for (n in short.length - 1 downTo 1) {
            val cut = short.take(n).trimEnd() + "…"
            if (measure(cut, min) <= avail) return Fit(cut, min)
        }
        return Fit(GLYPH.trim(), min)
    }

    /** True when ([x], [y]) lands on the header drawn for [key] of [date] (its padded rect). */
    fun hitAt(date: String, key: String, x: Float, y: Float): Boolean {
        val rect = hits["$date|$key"] ?: return false
        return rect.contains(x, y)
    }
}
