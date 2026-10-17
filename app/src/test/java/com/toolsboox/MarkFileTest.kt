package com.toolsboox

import com.toolsboox.plugin.reader.ui.BookNote
import com.toolsboox.plugin.reader.ui.MarkFile
import com.toolsboox.plugin.reader.ui.MarkJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * THE SYNC RACE IS CLOSED (design critique #1, Michael 2026-10-12's one-tap article Highlight):
 * a mark saved while the sync's pull is out survives the sync's write — for books and articles,
 * one engine ([MarkFile]).
 */
class MarkFileTest {

    private fun tmp(): File = Files.createTempDirectory("marks").toFile().resolve("m.json")
    private val file = MarkFile({ error("no context in a JVM test") }, "reader/test.json")

    private fun note(id: String, at: Long, deleted: Long? = null, text: String = id) =
        BookNote(id = id, type = BookNote.ANNOTATION, text = text, createdAt = at, updatedAt = at, deletedAt = deleted)

    private fun remoteOf(vararg notes: Pair<String, BookNote>): String = JSONObject().apply {
        for ((key, n) in notes) {
            val arr = optJSONArray(key) ?: JSONArray().also { put(key, it) }
            arr.put(MarkJson.toJson(n))
        }
    }.toString()

    @Test
    fun `a mark saved while the pull was out survives the sync's write`() {
        val f = tmp()
        file.mutateAt(f, "k") { it + note("old", 1) }
        // The pull went out here, carrying the remote's own mark…
        val remote = remoteOf("k" to note("old", 1), "k" to note("theirs", 2))
        // …and, while it was out, Highlight saved a mark.
        file.mutateAt(f, "k") { it + note("mine", 3) }
        val (changed, push) = file.mergeAfterPull(f, remote)
        assertTrue(changed)
        assertEquals(setOf("old", "theirs", "mine"), file.rawListAt(f, "k").map { it.id }.toSet())
        // And the push carries it, so no device loses it either.
        assertNotNull(push)
        assertTrue(push!!.contains("\"mine\""))
    }

    @Test
    fun `puts racing syncs - every put lands`() {
        val f = tmp()
        val staleRemote = remoteOf("k" to note("theirs", 1))
        val go = CountDownLatch(1)
        val writer = thread {
            go.await()
            repeat(200) { i -> file.mutateAt(f, "k") { list -> list + note("p$i", 10L + i) } }
        }
        val syncer = thread {
            go.await()
            repeat(200) { file.mergeAfterPull(f, staleRemote) }
        }
        go.countDown(); writer.join(); syncer.join()
        val ids = file.rawListAt(f, "k").map { it.id }.toSet()
        for (i in 0 until 200) assertTrue("p$i lost", "p$i" in ids)
        assertTrue("theirs" in ids)
    }

    @Test
    fun `nothing new on either side - nothing written, nothing pushed`() {
        val f = tmp()
        file.mutateAt(f, "k") { it + note("a", 5) }
        val remote = remoteOf("k" to note("a", 5))
        val (changed, push) = file.mergeAfterPull(f, remote)
        assertFalse(changed)
        assertNull(push)
    }

    @Test
    fun `the newer version of a mark wins, a tombstone included - and keeps winning`() {
        val f = tmp()
        file.mutateAt(f, "k") { it + note("a", 5) }
        file.mergeAfterPull(f, remoteOf("k" to note("a", 9, deleted = 9)))
        assertEquals(9L, file.rawListAt(f, "k").single().deletedAt)
        // An older live copy arriving later cannot resurrect it.
        file.mergeAfterPull(f, remoteOf("k" to note("a", 6)))
        assertEquals(9L, file.rawListAt(f, "k").single().deletedAt)
    }

    @Test
    fun `a book mark's JSON is what it was - the article fields ride only when set`() {
        val book = MarkJson.toJson(note("b", 1))
        for (k in listOf("url", "title", "source", "entryId", "view", "day", "pickId", "logId", "logDay", "pickDay"))
            assertFalse(k, book.has(k))
        val art = note("a", 1).copy(url = "https://example.com/a", title = "T", source = "F", entryId = 42,
            view = BookNote.VIEW_READER, day = "2026-10-12", pickId = "p-1", logId = "mfhl-42-abcd1234")
        val back = MarkJson.fromJson(MarkJson.toJson(art))!!
        assertEquals(art, back)
        assertEquals(note("b", 1), MarkJson.fromJson(book))
    }

    @Test
    fun `an iOS mark keeps its logDay, pickDay and any field this build doesn't know through an Android rewrite`() {
        val f = tmp()
        // What iOS writes for an article highlight (BookMarksStore.swift), plus a field from some
        // later build that this one has never heard of.
        val ios = JSONObject()
            .put("id", "mfhl-7-0a1b2c3d").put("type", "annotation").put("cfi", "{\"exact\":\"P.\"}")
            .put("chapter", "").put("fraction", 0.4).put("text", "P.").put("note", "").put("style", "highlight")
            .put("createdAt", 100L).put("updatedAt", 100L)
            .put("url", "https://e.com/a").put("entryId", 7).put("view", "reader")
            .put("logId", "mfhl-7-0a1b2c3d").put("logDay", "2026-10-08")
            .put("pickId", "ABC").put("pickDay", "2026-10-01").put("day", "2026-10-08")
            .put("futureField", JSONObject().put("x", 1))
        val remote = JSONObject().put("a-0", JSONArray().put(ios)).toString()
        file.mergeAfterPull(f, remote)
        // Android writes the bucket (a Highlight of another passage lands beside it)…
        file.mutateAt(f, "a-0") { it + note("mine", 200) }
        val back = file.rawListAt(f, "a-0").first { it.id == "mfhl-7-0a1b2c3d" }
        assertEquals("2026-10-08", back.logDay)
        assertEquals("2026-10-01", back.pickDay)
        assertEquals(java.time.LocalDate.of(2026, 10, 8), back.lineDay)
        assertEquals(java.time.LocalDate.of(2026, 10, 1), back.cardDay)
        // …and the stored JSON still carries every field iOS wrote.
        val stored = JSONObject(f.readText()).getJSONArray("a-0").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("id") == "mfhl-7-0a1b2c3d" }
        }
        assertEquals("2026-10-08", stored.getString("logDay"))
        assertEquals("2026-10-01", stored.getString("pickDay"))
        assertEquals(1, stored.getJSONObject("futureField").getInt("x"))
        // A re-merge of the same bytes is quiet (nothing changed, nothing to push).
        val (changed, push) = file.mergeAfterPull(f, JSONObject(f.readText()).toString())
        assertFalse(changed); assertNull(push)
    }

    @Test
    fun `an Android article mark writes the days iOS reads`() {
        val art = note("a", 1).copy(url = "https://e.com/a", day = "2026-10-12", logId = "a", pickId = "p",
            logDay = "2026-10-12", pickDay = "2026-10-12")
        val j = MarkJson.toJson(art)
        assertEquals("2026-10-12", j.getString("logDay"))
        assertEquals("2026-10-12", j.getString("pickDay"))
        assertEquals("2026-10-12", j.getString("day"))
        assertFalse(j.has("extra"))
        assertEquals(art, MarkJson.fromJson(j))
    }
}
