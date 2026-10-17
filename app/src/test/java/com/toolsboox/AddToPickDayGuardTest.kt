package com.toolsboox

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.toolsboox.da.Attachment
import com.toolsboox.ot.LedgerMoshi
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.AddToPickWords
import com.toolsboox.plugin.calendar.ot.MarkWords
import com.toolsboox.plugin.feeds.ot.ArticleHighlights
import com.toolsboox.plugin.feeds.ot.ArticleNoteStore
import com.toolsboox.plugin.feeds.ot.PickAdds
import com.toolsboox.plugin.reader.ui.BookNote
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * "＋ ADD TO THIS PICK" MEETS THE DAY-FILE GUARD (merge of add-to-pick into -332): every day the adds
 * write goes through a guarded door, a refusal is said in the shared words
 * (convergence-fixtures/day-guard-words.json — [MarkWords]), nothing is written over a day that won't
 * read, and a take whose line was refused keeps its file in the attachment store — and the add itself
 * waits for the page and lands once it reads (merge-addpick review #2). The face itself is
 * drawn with android.graphics ([com.toolsboox.plugin.calendar.ot.PickFace] can't load on the JVM),
 * so these stop short of a re-face: the line doors, the A/V list's, and the waiting adds.
 */
class AddToPickDayGuardTest {

    private val day: LocalDate = LocalDate.of(2026, 7, 30)
    private val url = "https://ashtanga.tech/on-practice"
    private val article = ArticleHighlights.Article(entryId = 42, url = url, title = "On Practice",
        feedTitle = "Ashtanga Tech", view = BookNote.VIEW_LIVE)

    /** A context with a files dir, an external files dir (the attachment store) and in-memory prefs. */
    private class TestContext(private val dir: File) : ContextWrapper(null) {
        val prefs = HashMap<String, MemPrefs>()
        override fun getFilesDir(): File = File(dir, "files").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?): File = File(dir, "ext/${type.orEmpty()}").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { MemPrefs() }
        override fun getApplicationContext(): Context = this
    }

    private class MemPrefs : SharedPreferences {
        val values = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v } }
        }
    }

    private class Bench {
        val dir: File = Files.createTempDirectory("addpick-guard").toFile()
        val app = TestContext(dir)
        val root = File(dir, "docs").apply { mkdirs() }
        val service = CalendarDayService(LedgerMoshi.standard())
        val said = mutableListOf<String>()
        val engine = PickAdds(app, service, { root }) { said += it }
        val store: File get() = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!.let { File(it, "attachments").apply { mkdirs() } }
        fun dayFile(d: LocalDate) = File(root, "calendar/%04d/%02d/day-%s-v2.json".format(d.year, d.monthValue, d))
        /** A day file that won't read — torn mid-write. */
        fun tear(d: LocalDate): ByteArray {
            val f = dayFile(d).apply { parentFile?.mkdirs() }
            f.writeText("""{"year":${d.year},"month":${d.monthValue},"day":${d.dayOfMonth},"readingEvents":[{"id":"mfhl""")
            return f.readBytes()
        }
        fun save(d: LocalDate, build: CalendarDay.() -> Unit) =
            service.save(root, d, CalendarDay(d.year, d.monthValue, d.dayOfMonth, Locale.US, startHour = 7).apply(build))
        fun waiting(): Set<String> =
            app.getSharedPreferences("ledger_article_marks", 0).getStringSet("waiting_adds", emptySet()).orEmpty()
        fun drops(): Set<String> =
            app.getSharedPreferences("ledger_article_marks", 0).getStringSet("waiting_drops", emptySet()).orEmpty()
        fun lineOn(d: LocalDate, id: String): ReadingEvent? =
            service.loadOrNull(root, d, Locale.US)?.readingEvents?.firstOrNull { it.id == id }
    }

    private fun mark(logId: String = "mfhl-42-aaaa", logDay: LocalDate? = day) = BookNote(
        id = "mfhl-42-aaaa", type = BookNote.ANNOTATION, text = "The passage.", url = url, title = "On Practice",
        source = "Ashtanga Tech", entryId = 42, view = BookNote.VIEW_LIVE, day = day.toString(),
        logId = logId, logDay = logDay?.toString().orEmpty())

    private fun line(id: String, atts: List<Attachment> = emptyList()) = ReadingEvent(id = id, kind = ReadingEvent.Kind.ARTICLE,
        date = Date(1_000L), title = "On Practice", url = url, excerpt = "The passage.",
        attachments = atts.toMutableList().ifEmpty { null })

    private fun refused(doing: String, d: LocalDate) = "Couldn't $doing — ${CalendarDayService.pageName(d)} won't read"
    private fun kept(doing: String, d: LocalDate) = AddToPickWords.refusedKept(refused(doing, d))

    @Test
    fun `an add onto a line whose day won't read is refused in the shared words, nothing written, the add kept`() {
        val b = Bench()
        val before = b.tear(day)
        val photo = File(b.store, "photo-11111111-2222-3333-4444-555555555555.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val att = Attachment("11111111-2222-3333-4444-555555555555", Attachment.Kind.PHOTO, photo.name, null, Date())

        assertEquals("the typed note", kept(MarkWords.CHANGE_THE_LINE, day), b.engine.addText(article, mark(), "said later"))
        assertEquals("the take", kept(MarkWords.CHANGE_THE_LINE, day), b.engine.addMedia(article, mark(), att))
        assertTrue("…its file untouched", photo.exists())
        assertEquals("a removal", refused(MarkWords.CHANGE_THE_LINE, day), b.engine.removeAttachment(article, mark(), att))
        assertEquals("the words end in the table's line, then that it is kept",
            "Couldn't change the line — ${CalendarDayService.pageName(day)} won't read. What you added is kept; it lands once the page reads.",
            kept(MarkWords.CHANGE_THE_LINE, day))

        assertArrayEquals("the day is never written over", before, b.dayFile(day).readBytes())
        assertFalse("no neighbour minted", b.dayFile(day.minusDays(1)).exists() || b.dayFile(day.plusDays(1)).exists())
        assertTrue("the take's file stays in the store", photo.exists())
        // Kept (merge-addpick review #2): the note and the take wait for the page; a removal doesn't.
        assertEquals("the note and the take wait", 2, b.waiting().size)
        assertTrue("one of them is the take", b.waiting().any { JSONObject(it).optJSONObject("a")?.optString("id") == att.id })
        assertTrue("the other is the words", b.waiting().any { JSONObject(it).optString("w") == "said later" })
        assertTrue("nothing else said", b.said.isEmpty())
    }

    @Test
    fun `a first line onto a day that won't read is refused as log it, and kept - a past day and today`() {
        for (d in listOf(day, LocalDate.now())) {
            val b = Bench()
            val before = b.tear(d)
            val m = mark(logId = "", logDay = null).copy(day = d.toString())
            assertEquals(kept(MarkWords.LOG_IT, d), b.engine.addText(article, m, "a first note"))
            assertArrayEquals(before, b.dayFile(d).readBytes())
            assertEquals(1, b.waiting().size)
        }
    }

    @Test
    fun `a refused take joins its line once the page reads`() {
        val b = Bench()
        val m = mark()
        ArticleNoteStore.put(b.app, article.key, m)
        b.tear(day)
        // Its file not on this device (the other device made it): the re-face waits for the file,
        // so this stops short of drawing a face, as on the JVM it must.
        val att = Attachment("66666666-2222-3333-4444-555555555555", Attachment.Kind.PHOTO,
            "photo-66666666-2222-3333-4444-555555555555.jpg", null, Date())
        assertEquals(kept(MarkWords.CHANGE_THE_LINE, day), b.engine.addMedia(article, m, att))
        assertEquals(1, b.waiting().size)

        b.engine.drain { true }
        assertEquals("still unreadable: it still waits", 1, b.waiting().size)

        b.dayFile(day).delete()
        b.save(day) { readingEvents.add(line(m.logId)) }   // healed — the line is there
        b.engine.drain { true }
        assertEquals("on its line", listOf(att.id), b.lineOn(day, m.logId)?.attachments?.map { it.id })
        assertTrue("no longer waiting", b.waiting().isEmpty())
    }

    @Test
    fun `a line that isn't on its own day but may be on a neighbour that won't read is refused, never 'not here'`() {
        val b = Bench()
        val next = day.plusDays(1)
        val torn = b.tear(next)
        assertEquals("the neighbour named (iOS lineRefusal checks the three days)",
            kept(MarkWords.CHANGE_THE_LINE, next), b.engine.addText(article, mark(), "said later"))
        assertArrayEquals(torn, b.dayFile(next).readBytes())
        assertFalse("its own day is never minted", b.dayFile(day).exists())
        assertEquals("kept", 1, b.waiting().size)
        assertFalse("not said as 'not here yet'", AddToPickWords.LINE_NOT_HERE in b.said)
    }

    @Test
    fun `a typed note that lands lets an older waiting one go, so it is never replayed over it`() {
        val b = Bench()
        val m = mark()
        ArticleNoteStore.put(b.app, article.key, m)
        assertNull("its line isn't here yet: the older note waits", b.engine.addText(article, m, "older"))
        assertEquals(1, b.waiting().size)
        b.save(day) { readingEvents.add(line(m.logId)) }
        // The re-face after it draws with android.graphics, which the JVM can't: the line is
        // written before it, and that is what this pins.
        runCatching { b.engine.addText(article, m, "newer") }
        assertEquals("newer", b.lineOn(day, m.logId)?.note)
        assertTrue("the older one no longer waits", b.waiting().isEmpty())
    }

    @Test
    fun `a clip a removal couldn't take off its A-V list comes off once that page reads`() {
        val b = Bench()
        val clip = Attachment("CLIP-9", Attachment.Kind.AUDIO, "voice-clip-9.m4a", 3.0, Date())
        val torn = b.tear(day)
        val prefs = b.app.getSharedPreferences("ledger_article_marks", 0)
        prefs.edit().putStringSet("waiting_drops", mutableSetOf("${article.key}|$day|CLIP-9")).apply()

        b.engine.drain { true }
        assertEquals("still unreadable: it waits", 1, b.drops().size)
        assertArrayEquals(torn, b.dayFile(day).readBytes())

        b.dayFile(day).delete()
        b.save(day) { avGrams.add(clip) }
        b.engine.drain { true }
        val healed = b.service.loadOrNull(b.root, day, Locale.US)!!
        assertTrue("off the list", healed.avGrams.isEmpty())
        assertTrue("tombstoned", healed.deletedElementIds.any { it.equals("CLIP-9", ignoreCase = true) })
        assertTrue("no longer waiting", b.drops().isEmpty())
    }

    @Test
    fun `a line that isn't here yet still waits, and a past day is never minted for it`() {
        val b = Bench()
        assertNull(b.engine.addText(article, mark(), "said later"))
        assertEquals(1, b.waiting().size)
        assertFalse(b.dayFile(day).exists())
    }

    @Test
    fun `a waiting add whose day arrives unreadable keeps waiting, quietly - a typed note and a take`() {
        val b = Bench()
        val m = mark()
        ArticleNoteStore.put(b.app, article.key, m)
        assertNull("its line isn't here yet: it waits", b.engine.addText(article, m, "said later"))
        // A photo waiting the same way (as [PickAdds.waitFor] keeps it), its file in the store.
        val photo = File(b.store, "photo-11111111-2222-3333-4444-555555555555.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val prefs = b.app.getSharedPreferences("ledger_article_marks", 0)
        val take = JSONObject().put("k", article.key).put("m", m.id).put("t", 1L)
            .put("a", JSONObject().put("id", "11111111-2222-3333-4444-555555555555").put("kind", "photo").put("filename", photo.name))
            .toString()
        prefs.edit().putStringSet("waiting_adds", (b.waiting() + take).toMutableSet()).apply()
        val held = b.waiting()
        assertEquals(2, held.size)
        b.said.clear()

        val torn = b.tear(day)
        b.engine.drain { true }
        assertEquals("both still waiting", held, b.waiting())
        assertArrayEquals("never written over", torn, b.dayFile(day).readBytes())
        assertTrue("its file stays in the store", photo.exists())
        assertTrue("quiet", b.said.isEmpty())
    }

    @Test
    fun `a clip comes off the readable day it is on, and on none its own day that won't read is the refusal`() {
        val b = Bench()
        val clip = Attachment("CLIP-1", Attachment.Kind.AUDIO, "voice-clip-1.m4a", 3.0, Date())
        val torn = b.tear(day)
        b.save(day.plusDays(1)) { avGrams.add(clip) }
        assertNull("found beside it", b.engine.dropClips(day, listOf("clip-1")))
        val next = b.service.loadOrNull(b.root, day.plusDays(1), Locale.US)!!
        assertTrue(next.avGrams.isEmpty())
        assertTrue("tombstoned", next.deletedElementIds.any { it.equals("CLIP-1", ignoreCase = true) })
        assertArrayEquals(torn, b.dayFile(day).readBytes())

        val e = b.engine.dropClips(day, listOf("clip-2"))
        assertEquals(b.dayFile(day).name, e?.file?.name)
        assertEquals(refused(MarkWords.CHANGE_THE_PICK, day), CalendarDayService.refusal(e, MarkWords.CHANGE_THE_PICK))
        assertArrayEquals(torn, b.dayFile(day).readBytes())
    }

    @Test
    fun `the shared words the adds say are the table's`() {
        val table = JSONObject(listOf(File("../convergence-fixtures/day-guard-words.json"), File("convergence-fixtures/day-guard-words.json"))
            .first { it.exists() }.readText()).getJSONArray("marks")
        val expected = (0 until table.length()).map { table.getJSONObject(it) }.associate { it.getString("site") to it.optString("doing") }
        assertEquals(expected["logLine"], MarkWords.LOG_IT)
        assertEquals(expected["changeLine"], MarkWords.CHANGE_THE_LINE)
        assertEquals(expected["changePick"], MarkWords.CHANGE_THE_PICK)
        // An untied sheet's adds kept on today: the same sentence as a highlight on today.
        assertEquals(MarkWords.HIGHLIGHT_REFUSED, MarkWords.TODAY_REFUSED)
    }
}
