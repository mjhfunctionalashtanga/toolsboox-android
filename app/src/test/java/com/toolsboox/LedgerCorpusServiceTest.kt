package com.toolsboox

import android.content.ContextWrapper
import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.chat.da.Section
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Proves the "Ask my Ledger" corpus engine reads real (iOS-shaped) day JSON off disk,
 * flattens each section, honors scope, and retrieves by keyword.
 */
class LedgerCorpusServiceTest {

    /**
     * The corpus service reads its non-calendar sections (text notes, feed cache, page sections)
     * out of `context.filesDir`. A JVM unit test has no Android Context, so we hand it a wrapper
     * that answers the one method the gather path actually calls and stubs nothing else.
     */
    private class FilesDirContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private fun service(filesDir: File = Files.createTempDirectory("corpus-files").toFile()): LedgerCorpusService {
        val moshi = Moshi.Builder()
            .add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build()
        val cds = CalendarDayService().apply { this.moshi = moshi }
        return LedgerCorpusService(cds, FilesDirContext(filesDir))
    }

    private fun writeDay(root: File) {
        val dir = File(root, "calendar/2026/07").apply { mkdirs() }
        File(dir, "day-2026-07-13-v2.json").writeText(
            """
            {
              "year":2026,"month":7,"day":13,"locale":"en-US",
              "events":[],"readingProgress":[],"hasLanes":true,"startHour":null,
              "calendarStrokes":{},"calendarValues":{},"noteStrokes":{},
              "textElements":[
                {"elementId":"11111111-1111-1111-1111-111111111111","timestamp":1720900000000,
                 "x":0,"y":0,"width":300,"height":60,"text":"Remember to breathe through Marichyasana",
                 "fontFamily":"atkinson_hyperlegible","fontSize":24,"color":-16777216,"pageKey":"default"}
              ],
              "imageElements":[],
              "readingEvents":[
                {"id":"bookhl-1","kind":"book","date":1720900000000,"title":"Light on Yoga",
                 "source":"B.K.S. Iyengar","excerpt":"Sthira sukham asanam","note":"steady and easeful breathing"},
                {"id":"art-1","kind":"article","date":1720901111111,"title":"On Practice",
                 "source":"Ashtanga Tech","url":"https://ashtanga.tech/on-practice","note":"consistency over intensity"}
              ],
              "avGrams":[
                {"id":"av-1","kind":"audio","filename":"voice-1.m4a","duration":12.5,"date":1720902222222}
              ],
              "deletedStrokeIds":[],"deletedElementIds":[],
              "created":1720800000000,"updated":1720999999999
            }
            """.trimIndent()
        )
    }

    @Test
    fun gathers_all_sections_then_scopes_and_retrieves() {
        val root = Files.createTempDirectory("ledger-corpus").toFile()
        try {
            writeDay(root)
            val svc = service()

            // All sections: 1 book + 1 article + 1 planner text + 1 av gram = 4.
            val all = svc.gather(root, Section.ALL)
            assertEquals(4, all.size)
            assertEquals(1, all.count { it.section == Section.BOOKS })
            assertEquals(1, all.count { it.section == Section.ARTICLES })
            assertEquals(1, all.count { it.section == Section.PLANNER })
            assertEquals(1, all.count { it.section == Section.MEDIA })

            // Book snippet fuses excerpt + note and cites the day.
            val book = all.first { it.section == Section.BOOKS }
            assertTrue(book.text.contains("Sthira sukham asanam"))
            assertTrue(book.text.contains("steady and easeful"))
            assertTrue(book.citation.matches(Regex("^\\d{4}-\\d{2}-\\d{2} · book · .*")))

            // Scope = just books.
            val booksOnly = svc.gather(root, setOf(Section.BOOKS))
            assertEquals(1, booksOnly.size)
            assertEquals(Section.BOOKS, booksOnly[0].section)

            // Retrieval by keyword surfaces the right snippet first.
            val hits = svc.retrieve(all, "what did I note about breathing?", k = 2)
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.any { it.text.contains("breathe") || it.text.contains("breathing") })

            // Context block is cite-prefixed.
            val ctx = svc.buildContext(hits)
            assertTrue(ctx.contains("["))
            assertTrue(ctx.contains(Regex("\\d{4}-\\d{2}-\\d{2}")))
        } finally {
            root.deleteRecursively()
        }
    }
}
