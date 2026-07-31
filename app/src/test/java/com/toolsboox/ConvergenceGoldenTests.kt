package com.toolsboox

import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.fi.CalendarDayMerger
import com.toolsboox.plugin.calendar.ot.LedgerDocumentTombstones
import com.toolsboox.plugin.calendar.ot.LedgerDocumentTombstones.Epoch
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Cross-fork convergence golden tests — the Android half of a pair of suites that load the SAME
 * fixtures (`convergence-fixtures/` at the repo root, committed byte-identically to both repos;
 * the iOS twin is `Tests/LedgerCoreTests/ConvergenceGoldenTests.swift`) and must land on the same
 * content-level merge. A future edit to either fork's [CalendarDayMerger] or
 * [LedgerDocumentTombstones] epoch rule that breaks convergence fails here instead of corrupting
 * a sync.
 *
 * Comparison is FIELD BY FIELD on decoded values, never on bytes — Moshi writes constructor
 * order, iOS `LedgerJSON` writes sorted keys, and both are correct.
 */
class ConvergenceGoldenTests {

    private val moshi: Moshi = Moshi.Builder()
        .add(LocaleJsonAdapter())
        .add(DateJsonAdapter())
        .add(UUIDJsonAdapter())
        .build()

    /** Fixtures arrive on the test classpath via the `../convergence-fixtures` resources srcDir
     *  in app/build.gradle; the file-path fallback keeps the suite honest if that wiring drifts. */
    private fun fixture(name: String): String {
        javaClass.classLoader?.getResourceAsStream(name)?.use {
            return it.readBytes().decodeToString()
        }
        for (f in listOf(File("../convergence-fixtures/$name"), File("convergence-fixtures/$name"))) {
            if (f.exists()) return f.readText()
        }
        throw AssertionError("convergence fixture not found: $name")
    }

    private fun day(name: String): CalendarDay =
        requireNotNull(moshi.adapter(CalendarDay::class.java).fromJson(fixture(name))) {
            "fixture $name did not parse"
        }

    // ── CalendarDayMerger ─────────────────────────────────────────────────────────────────────

    /** merge(day-a, day-b) must equal the canonical expected day, property by property. */
    @Test
    fun dayMergeMatchesGolden() {
        val merged = CalendarDayMerger.merge(day("day-a.json"), day("day-b.json"))
        val want = day("day-merged.expected.json")

        assertEquals(want.year, merged.year)
        assertEquals(want.month, merged.month)
        assertEquals(want.day, merged.day)
        assertEquals(want.locale, merged.locale)
        assertEquals(want.events, merged.events)
        assertEquals(want.readingProgress, merged.readingProgress)
        assertEquals(want.hasLanes, merged.hasLanes)
        assertEquals(want.startHour, merged.startHour)
        assertEquals("calendarStrokes diverged", want.calendarStrokes, merged.calendarStrokes)
        assertEquals("calendarValues diverged", want.calendarValues, merged.calendarValues)
        assertEquals(
            "noteStrokes page-key set diverged (deleted-page rule)",
            want.noteStrokes.keys, merged.noteStrokes.keys
        )
        for ((key, strokes) in want.noteStrokes) {
            assertEquals("noteStrokes[$key] diverged", strokes, merged.noteStrokes[key])
        }
        assertEquals("textElements diverged", want.textElements, merged.textElements)
        assertEquals("imageElements diverged", want.imageElements, merged.imageElements)
        assertEquals("readingEvents diverged", want.readingEvents, merged.readingEvents)
        assertEquals("avGrams diverged", want.avGrams, merged.avGrams)
        assertEquals("ledgerItems diverged", want.ledgerItems, merged.ledgerItems)
        assertEquals(
            "deletedStrokeIds diverged (must be lowercased, deduped, sorted)",
            want.deletedStrokeIds, merged.deletedStrokeIds
        )
        assertEquals("deletedElementIds diverged", want.deletedElementIds, merged.deletedElementIds)
        assertEquals("deletedItemIds diverged", want.deletedItemIds, merged.deletedItemIds)
        assertEquals(want.created, merged.created)
        assertEquals(want.updated, merged.updated)
        // Belt and braces: the whole value too, so a newly added CalendarDay field can't drift
        // through the merge unasserted.
        assertEquals("merge(a, b) != golden as a whole value", want, merged)
    }

    /** The merge must not care which device offers its version first. */
    @Test
    fun dayMergeIsSymmetric() {
        val a = day("day-a.json")
        val b = day("day-b.json")
        assertEquals(
            "merge(a, b) != merge(b, a)",
            CalendarDayMerger.merge(a, b), CalendarDayMerger.merge(b, a)
        )
    }

    /** Re-merging the result with either input must change nothing — this is what lets
     *  two-device pairs go quiet instead of re-uploading forever. */
    @Test
    fun dayMergeIsIdempotent() {
        val a = day("day-a.json")
        val b = day("day-b.json")
        val merged = CalendarDayMerger.merge(a, b)
        assertEquals("merge(merge(a,b), b) drifted", merged, CalendarDayMerger.merge(merged, b))
        assertEquals("merge(merge(a,b), a) drifted", merged, CalendarDayMerger.merge(merged, a))
        assertEquals("merge(m, m) drifted", merged, CalendarDayMerger.merge(merged, merged))
    }

    // ── LedgerDocumentTombstones epoch rules ──────────────────────────────────────────────────

    private fun epochMap(name: String): Map<String, Epoch> {
        val obj = JSONObject(fixture(name))
        val out = LinkedHashMap<String, Epoch>()
        for (id in obj.keys()) {
            val e = obj.getJSONObject(id)
            out[id] = Epoch(e.getLong("d"), e.getLong("r"))
        }
        return out
    }

    private fun legacyIds(name: String): List<String> {
        val arr = JSONArray(fixture(name))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** The `epochs` object inside `epochs-merged.expected.json`. */
    private fun expectedEpochs(want: JSONObject): Map<String, Epoch> {
        val out = LinkedHashMap<String, Epoch>()
        val eo = want.getJSONObject("epochs")
        for (id in eo.keys()) {
            val e = eo.getJSONObject(id)
            out[id] = Epoch(e.getLong("d"), e.getLong("r"))
        }
        return out
    }

    /** Device A's round trip: fold its own legacy `deleted.json` (as `epochs` does on read),
     *  then merge the remote store by per-id MAX (as `roundTrip` does). Must land on the golden
     *  store and the golden dead set. */
    @Test
    fun epochMergeMatchesGolden() {
        val local = LedgerDocumentTombstones.foldLegacy(epochMap("epochs-a.json"), legacyIds("deleted-a.json"))
        val merged = LedgerDocumentTombstones.mergeEpochs(local, epochMap("epochs-b.json"))

        val want = JSONObject(fixture("epochs-merged.expected.json"))
        val wantEpochs = expectedEpochs(want)
        val deadArr = want.getJSONArray("dead")
        val wantDead = (0 until deadArr.length()).map { deadArr.getString(it) }.toSet()

        assertEquals("epoch store diverged from golden", wantEpochs, merged)
        assertEquals(
            "dead set diverged (dead iff d > r; tie goes to the revival)",
            wantDead, LedgerDocumentTombstones.deadIds(merged)
        )
    }

    /** Per-id MAX is order-free and re-merging changes nothing; legacy folding onto a known id
     *  is a no-op. */
    @Test
    fun epochMergeIsSymmetricAndIdempotent() {
        val a = epochMap("epochs-a.json")
        val b = epochMap("epochs-b.json")
        assertEquals(
            "epoch merge is not symmetric",
            LedgerDocumentTombstones.mergeEpochs(a, b), LedgerDocumentTombstones.mergeEpochs(b, a)
        )
        val legacy = legacyIds("deleted-a.json")
        val merged = LedgerDocumentTombstones.mergeEpochs(LedgerDocumentTombstones.foldLegacy(a, legacy), b)
        assertEquals("epoch re-merge drifted", merged, LedgerDocumentTombstones.mergeEpochs(merged, b))
        assertEquals(
            "legacy fold moved a known id's clocks",
            merged, LedgerDocumentTombstones.foldLegacy(merged, legacy)
        )
    }

    /** The two devices approach from opposite sides (B merges A's store and only THEN sees A's
     *  legacy array, so B's `doc-6` deletion clock lags at 0 vs A's 1 for one round trip). Their
     *  DEAD sets must already agree, and one more exchange must land both on the golden store. */
    @Test
    fun epochMergeConvergesFromBothDevices() {
        val a = epochMap("epochs-a.json")
        val b = epochMap("epochs-b.json")
        val legacy = legacyIds("deleted-a.json")
        val wantEpochs = expectedEpochs(JSONObject(fixture("epochs-merged.expected.json")))

        val mergedOnA = LedgerDocumentTombstones.mergeEpochs(LedgerDocumentTombstones.foldLegacy(a, legacy), b)
        val mergedOnB = LedgerDocumentTombstones.foldLegacy(LedgerDocumentTombstones.mergeEpochs(b, a), legacy)
        assertEquals(
            "the two devices disagree on which documents are dead",
            LedgerDocumentTombstones.deadIds(mergedOnA), LedgerDocumentTombstones.deadIds(mergedOnB)
        )
        assertEquals(
            "devices did not converge to the golden store",
            wantEpochs, LedgerDocumentTombstones.mergeEpochs(mergedOnA, mergedOnB)
        )
        assertEquals(
            "devices did not converge to the golden store (other order)",
            wantEpochs, LedgerDocumentTombstones.mergeEpochs(mergedOnB, mergedOnA)
        )
    }
}
