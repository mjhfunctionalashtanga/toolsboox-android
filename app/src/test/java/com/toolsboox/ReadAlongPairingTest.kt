package com.toolsboox

import com.toolsboox.plugin.reader.ui.ReadAlongPairing
import com.toolsboox.plugin.reader.ui.ReadAlongPairing.AudioChapter
import com.toolsboox.plugin.reader.ui.ReadAlongPairing.TextChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapter matcher, pinned on a small TOC and chapter list — the same fixture the iOS twin
 * (`ReadAlongPairingTests.swift`) runs, so the two forks pair a book identically.
 */
class ReadAlongPairingTest {

    // An EPUB whose TOC has front matter, numbered chapters (one with a subtitle, one spelled out),
    // and an epilogue — six sections, one file each.
    private val toc = listOf(
        TextChapter("cover.xhtml", "Cover", 0),
        TextChapter("dedication.xhtml", "Dedication", 1),
        TextChapter("ch01.xhtml", "Chapter 1: The Storm", 2),
        TextChapter("ch02.xhtml", "Chapter 2", 3),
        TextChapter("ch03.xhtml", "Chapter Three", 4),
        TextChapter("epilogue.xhtml", "Epilogue", 5),
    )

    // The audiobook: credits at both ends, the same three chapters (chapter 1 split into 1, 1.1),
    // titled the way narrators title them.
    private val audio = listOf(
        AudioChapter(0, 0.0, 30.0, "Opening Credits"),
        AudioChapter(1, 30.0, 600.0, "1 - The Storm"),
        AudioChapter(2, 600.0, 900.0, "1.1"),
        AudioChapter(3, 900.0, 1500.0, "Chapter 2"),
        AudioChapter(4, 1500.0, 2100.0, "Chapter III"),
        AudioChapter(5, 2100.0, 2400.0, "Epilogue"),
        AudioChapter(6, 2400.0, 2430.0, "End Credits"),
    )

    @Test
    fun normalizesTitles() {
        assertEquals("the storm", ReadAlongPairing.normalizeTitle("Chapter 12: The Storm"))
        assertEquals("the storm", ReadAlongPairing.normalizeTitle("12 - The Storm"))
        assertEquals("", ReadAlongPairing.normalizeTitle("Chapter 12"))
        assertEquals("", ReadAlongPairing.normalizeTitle("Chapter III"))
        assertEquals("", ReadAlongPairing.normalizeTitle("Chapter Twenty-One"))
        assertEquals("epilogue", ReadAlongPairing.normalizeTitle("Epilogue"))
        // "mix" is a word, not a roman numeral — lowercase and not after a chapter word.
        assertEquals("the mix", ReadAlongPairing.normalizeTitle("The Mix"))
    }

    @Test
    fun readsChapterNumbers() {
        assertEquals(12, ReadAlongPairing.chapterNumber("Chapter 12: The Storm"))
        assertEquals(3, ReadAlongPairing.chapterNumber("Chapter III"))
        assertEquals(3, ReadAlongPairing.chapterNumber("Chapter Three"))
        assertEquals(21, ReadAlongPairing.chapterNumber("Chapter Twenty-One"))
        assertEquals(7, ReadAlongPairing.chapterNumber("7. A Long Walk"))
        assertNull(ReadAlongPairing.chapterNumber("1.1"))
        assertNull(ReadAlongPairing.chapterNumber("Epilogue"))
    }

    @Test
    fun fuzzyIsAboveTheFloorForNearTitles() {
        val a = ReadAlongPairing.normalizeTitle("The Storm Rises")
        val b = ReadAlongPairing.normalizeTitle("Storm Rises")
        assertTrue(ReadAlongPairing.similarity(a, b) >= ReadAlongPairing.FUZZY_MIN)
        assertTrue(ReadAlongPairing.similarity("the storm", "a quiet garden") < ReadAlongPairing.FUZZY_MIN)
    }

    @Test
    fun matchesByTitleThenNumberThenOrdinal() {
        val map = ReadAlongPairing.match(toc, audio)
        val byAudio = map.associateBy { it.audioChapterIndex }
        // Title: "1 - The Storm" ↔ "Chapter 1: The Storm"; "Epilogue" ↔ "Epilogue".
        assertEquals("ch01.xhtml", byAudio[1]?.tocHref); assertEquals(ReadAlongPairing.HOW_TITLE, byAudio[1]?.how)
        assertEquals("epilogue.xhtml", byAudio[5]?.tocHref); assertEquals(ReadAlongPairing.HOW_TITLE, byAudio[5]?.how)
        // Number: "Chapter 2" ↔ "Chapter 2"; "Chapter III" ↔ "Chapter Three".
        assertEquals("ch02.xhtml", byAudio[3]?.tocHref); assertEquals(ReadAlongPairing.HOW_ORDINAL, byAudio[3]?.how)
        assertEquals("ch03.xhtml", byAudio[4]?.tocHref); assertEquals(ReadAlongPairing.HOW_ORDINAL, byAudio[4]?.how)
        // Sequential fill before the first anchor, right-aligned: Opening Credits ↔ Dedication (Cover stays bare).
        assertEquals("dedication.xhtml", byAudio[0]?.tocHref)
        // 1.1 sits between two anchors with no TOC entry left for it → unmapped (absorbed at playback).
        assertNull(byAudio[2])
        // End Credits has nothing after the epilogue to pair with → unmapped.
        assertNull(byAudio[6])
        assertEquals(5, map.size)
        // Pairs never cross: audio order and TOC order agree.
        val sorted = map.sortedBy { it.audioChapterIndex }
        val sections = sorted.map { it.sectionIndex }
        assertEquals(sections.sorted(), sections)
    }

    @Test
    fun unmappedChaptersAreAbsorbedByTheMappedChapterBefore() {
        val map = ReadAlongPairing.match(toc, audio)
        val spans = ReadAlongPairing.spans(map, audio, 2430.0)
        val storm = spans.first { it.mapping.tocHref == "ch01.xhtml" }
        // Chapter 1's span runs to Chapter 2's start: 1.1 (600–900) is inside it, never skipped.
        assertEquals(30.0, storm.start, 0.001)
        assertEquals(900.0, storm.end, 0.001)
        assertEquals("ch01.xhtml", ReadAlongPairing.spanAt(spans, 750.0)?.mapping?.tocHref)
        // End Credits (2400–2430) belong to the epilogue's span, which runs to the end.
        assertEquals("epilogue.xhtml", ReadAlongPairing.spanAt(spans, 2410.0)?.mapping?.tocHref)
        assertEquals(2430.0, spans.last().end, 0.001)
    }

    @Test
    fun sectionLookupsAndApproximateStart() {
        val map = ReadAlongPairing.match(toc, audio)
        val spans = ReadAlongPairing.spans(map, audio, 2430.0)
        // The Storm is section 2: 40% of the way through the section is 40% into 30–900.
        assertEquals(30.0 + 870.0 * 0.4, ReadAlongPairing.startSeconds(spans, 2, 0.4)!!, 0.001)
        // Section 0 (Cover) has no mapped entry and nothing before it → the first span.
        assertEquals(0, ReadAlongPairing.spanFor(spans, 0)?.mapping?.audioChapterIndex)
        // A section past the last mapped one leans on the nearest mapped entry before it.
        assertEquals("epilogue.xhtml", ReadAlongPairing.spanFor(spans, 9)?.mapping?.tocHref)
    }

    @Test
    fun repairMovesOnePairAndStampsIt() {
        val map = ReadAlongPairing.match(toc, audio)
        val p = ReadAlongPairing.Pairing("item-1", "abs.example.com", "Book", "Book (audio)", audio.size, map, 1_000L)
        assertEquals("5 of 7 chapters matched", p.summary)
        // Point 1.1 at Chapter 2's text: Chapter 2's old pair (audio 3) is dropped, 1.1 is manual.
        val fixed = ReadAlongPairing.repair(p, 2, toc[3], now = 2_000L)
        val byAudio = fixed.chapterMap.associateBy { it.audioChapterIndex }
        assertEquals("ch02.xhtml", byAudio[2]?.tocHref)
        assertEquals(ReadAlongPairing.HOW_MANUAL, byAudio[2]?.how)
        assertNull(byAudio[3])
        assertEquals(2_000L, fixed.updated)
        // "No text": the pair is simply removed.
        val bare = ReadAlongPairing.repair(fixed, 2, null, now = 3_000L)
        assertNull(bare.chapterMap.firstOrNull { it.audioChapterIndex == 2 })
    }

    @Test
    fun jsonRoundTripsAndNewestWins() {
        val map = ReadAlongPairing.match(toc, audio)
        val p = ReadAlongPairing.Pairing("item-1", "abs.example.com", "Book", "Book (audio)", audio.size, map, 1_000L)
        val back = ReadAlongPairing.fromJson(ReadAlongPairing.toJson(p))
        assertEquals(p, back)
        assertEquals(ReadAlongPairing.MODE_CHAPTERS, back?.mode)
        val later = p.copy(updated = 5_000L)
        assertEquals(later, ReadAlongPairing.newest(p, later))
        assertEquals(later, ReadAlongPairing.newest(later, p))
    }

    @Test
    fun fileKeyIsPathSafeAndStable() {
        val key = "Fiction/Le Guin - Earthsea.epub#1234567"
        val fk = ReadAlongPairing.fileKey(key)
        assertTrue(fk.matches(Regex("[A-Za-z0-9._-]+")))
        assertEquals(fk, ReadAlongPairing.fileKey(key))
        assertEquals("readalong/$fk.json", ReadAlongPairing.remotePath(key))
        // The Swift twin computes this same number (Java's String.hashCode over UTF-16).
        assertEquals("Book".hashCode(), ReadAlongPairing.javaHash("Book"))
    }
}
