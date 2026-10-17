package com.toolsboox.plugin.reader

import com.toolsboox.plugin.reader.ot.BalloonIndex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💬 The balloon index's pure parts, pinned.
 *
 * The TFLite interpreter itself is Android-native (no JVM runtime exists for the AAR), so the
 * detector is exercised through the reference interpreter instead: `fixture-raw.json` holds the
 * graph's raw output tensors for `balloon-fixture.png` (a synthetic 800×1200 page — two panel
 * borders, three drawn speech balloons with text — thumbnailed to 683×1024 exactly as the pass
 * decodes, fitted 480×720 into the 480×736 canvas), recorded 2026-09-10 with LiteRT 1.x and the
 * shipped `yolo_seeneva.tflite`. The same recording on 25 real pages (Absolute Batman 2) gave 213
 * balloons across 380 detections with a LOWEST score of 0.703 — the graph clips at 0.7 itself, so
 * PROB_MIN is a belt on braces and not a knob that could surface more.
 *
 * What is pinned here is everything between the tensors and the page: the un-padding into
 * page-relative 0–1, the class/score gate, the spine rule shared with comic-book.js, the
 * detection order (page on screen first), and the per-page count the Comic menu reads.
 */
class BalloonIndexTest {

    private fun fixture(): JSONObject {
        val stream = javaClass.getResourceAsStream("/balloons/fixture-raw.json")
            ?: error("fixture-raw.json missing from test resources")
        return JSONObject(stream.bufferedReader().readText())
    }

    @Test
    fun fixturePageYieldsThreeBalloons() {
        val f = fixture()
        val img = f.getJSONObject("image")
        val rows = f.getJSONArray("boxes")
        val boxes = Array(rows.length()) { i ->
            val r = rows.getJSONArray(i); FloatArray(5) { j -> r.getDouble(j).toFloat() }
        }
        val cls = f.getJSONArray("classes").let { a -> IntArray(a.length()) { a.getInt(it) } }
        val count = f.getInt("count")
        // The fit the pass computes for the decoded page must match what the recording used.
        assertEquals(img.getInt("nw") to img.getInt("nh"), BalloonIndex.fitted(img.getInt("w"), img.getInt("h")))

        val rects = BalloonIndex.postprocess(boxes, cls, count, img.getInt("nw"), img.getInt("nh"))
        assertEquals("three drawn balloons; the panel (class 1) is ruled off", 3, rects.length())
        val list = (0 until rects.length()).map { i ->
            val r = rects.getJSONArray(i); listOf(r.getDouble(0), r.getDouble(1), r.getDouble(2), r.getDouble(3))
        }
        for (r in list) {
            assertTrue("x in page", r[0] >= 0.0 && r[0] + r[2] <= 1.0001)
            assertTrue("y in page", r[1] >= 0.0 && r[1] + r[3] <= 1.0001)
            assertTrue("a balloon is a real box", r[2] > 0.05 && r[3] > 0.03)
        }
        // The top-left balloon was drawn at x 60–380, y 50–230 of 800×1200 (tail to y 290):
        // 0.075–0.475 × 0.042–0.19. Its rect must land THERE, in the page's own 0–1 — a wrong
        // un-pad (dividing by the canvas instead of the fitted page) would put y at ~0.19 → 0.04.
        // (Two balloons share the top row within a hair of y; the LEFT one is the one meant.)
        val tl = list.filter { it[1] < 0.3 }.minByOrNull { it[0] }!!
        assertEquals(0.09, tl[0], 0.05)
        assertEquals(0.04, tl[1], 0.04)
        assertEquals(0.38, tl[2], 0.08)
        assertEquals(0.16, tl[3], 0.06)
        // The lower balloon (drawn x 80–460, y 640–800 → 0.10–0.575 × 0.53–0.67) is on the
        // SECOND half of the page — proof the y axis was un-padded by nh, not squashed.
        val low = list.maxByOrNull { it[1] }!!
        assertEquals(0.53, low[1], 0.05)
        assertEquals(0.12, low[0], 0.06)
    }

    @Test
    fun scoreAndClassGate() {
        val boxes = arrayOf(
            floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f, 0.95f),   // balloon, confident
            floatArrayOf(0.3f, 0.3f, 0.4f, 0.4f, 0.50f),   // balloon, below the export threshold
            floatArrayOf(0.5f, 0.5f, 0.6f, 0.6f, 0.99f),   // panel — ruled off
            floatArrayOf(0.7f, 0.7f, 0.7f, 0.8f, 0.99f),   // zero-height — dropped
        )
        val rects = BalloonIndex.postprocess(boxes, intArrayOf(0, 0, 1, 0), 4, 480, 736)
        assertEquals(1, rects.length())
        // count caps the rows that are read: a stale row past `count` is never a balloon
        assertEquals(0, BalloonIndex.postprocess(boxes, intArrayOf(0, 0, 1, 0), 0, 480, 736).length())
    }

    @Test
    fun spineRuleIsComicBookJs() {
        // comic-book.js's comicPageNames (slice 2, Michael 2026-10-11): extensions matched
        // ASCII-case-insensitively, NATURAL order with case NOT folded (the iPad's rule), ties to
        // plain code units, directories out. scripts/test-comic-page-order.mjs pins this SAME
        // vector in JS.
        val names = listOf("page10.jpg", "page2.jpg", "Page1.JPG", "page01.jpg", "b.jpg", "a.JPG",
            "Dir/c.png", "a.jpg", "ComicInfo.xml", "x.jpeg", "folder/", "FileInfo.txt", "img.Webp",
            "ch 1/p 20.avif", "ch 1/p 3.avif", "notes.jpg.txt", "v2/007.png", "v10/001.png",
            "0.gif", "00.gif")
        val want = listOf("0.gif", "00.gif", "Dir/c.png", "Page1.JPG", "a.JPG", "a.jpg", "b.jpg",
            "ch 1/p 3.avif", "ch 1/p 20.avif", "img.Webp", "page01.jpg", "page2.jpg", "page10.jpg",
            "v2/007.png", "v10/001.png", "x.jpeg")
        val order = BalloonIndex.spineOrder(names)
        assertEquals(want, order.entries.sortedBy { it.value }.map { it.key })
        assertEquals(want, BalloonIndex.spineOrder(names.reversed()).entries.sortedBy { it.value }.map { it.key })
        // The iPad's testSpineRuleIsComicBookJs, verbatim.
        assertEquals(listOf("Dir/c.png", "a.JPG", "a.jpg", "b.jpg", "x.jpeg"),
            BalloonIndex.spineOrder(listOf("b.jpg", "a.JPG", "Dir/c.png", "a.jpg", "ComicInfo.xml",
                "x.jpeg", "folder/", "FileInfo.txt")).entries.sortedBy { it.value }.map { it.key })
    }

    /** THE SHARED PAGE-ORDER VECTOR — byte-identical to the iPad's
     *  testNaturalPageOrderSharedVector and to scripts/test-comic-page-order.mjs: one CBZ must
     *  have the same page numbers on the Boox and the iPad, or synced positions land wrong. */
    @Test
    fun naturalPageOrderSharedVector() {
        val names = listOf("page10.jpg", "page2.jpg", "page1.jpg", "Page3.JPG", "cover.Jpeg", "ComicInfo.xml",
            "folder/", "notes.txt", "01.png", "1.png", "ch10/p1.jpg", "ch2/p10.jpg", "ch2/p9.jpg",
            "é.jpg", "z.webp", "img007.png", "img7.png")
        assertEquals(listOf("01.png", "1.png", "Page3.JPG", "ch2/p9.jpg", "ch2/p10.jpg", "ch10/p1.jpg",
            "cover.Jpeg", "img007.png", "img7.png", "page1.jpg", "page2.jpg", "page10.jpg", "z.webp", "é.jpg"),
            BalloonIndex.spineOrder(names).entries.sortedBy { it.value }.map { it.key })
        // Zero-padded names (Mihon's downloads) keep the order the old plain sort gave them.
        val padded = (1..12).map { "%03d.jpg".format(it) }
        assertEquals(padded.sorted(), padded.sortedWith { a, b -> com.toolsboox.plugin.reader.ot.CbzDirectory.naturalCompare(a, b) })
    }

    @Test
    fun naturalCompareVectors() {
        // The same checks as scripts/test-comic-page-order.mjs, so the twins cannot drift.
        fun sign(n: Int) = Integer.signum(n)
        val c = com.toolsboox.plugin.reader.ot.CbzDirectory::naturalCompare
        assertEquals(-1, sign(c("page2.jpg", "page10.jpg")))
        assertEquals(-1, sign(c("p01", "p1")))
        assertEquals(-1, sign(c("Z.jpg", "a.jpg")))            // case is NOT folded
        assertEquals(1, sign(c("credits.jpg", "Page01.jpg")))  // …so lower case sorts after upper
        assertEquals(-1, sign(c("p1", "p1a")))
        assertEquals(-1, sign(c("x123456789012345678901234567890", "x123456789012345678901234567891")))
        assertEquals(0, c("a1b", "a1b"))
        assertEquals(-1, sign(c("x9", "x10")))
        assertEquals(-1, sign(c("x", "x0")))
        val page = com.toolsboox.plugin.reader.ot.CbzDirectory::isPageName
        assertTrue(page("001.JPG")); assertTrue(page("x/001.Jpeg"))
        assertTrue(!page("folder.jpg/")); assertTrue(!page("ComicInfo.xml"))
    }

    @Test
    fun detectionStartsAtThePageOnScreen() {
        // A reader who resumed at page 23 of 48 sees page 23 detected first, forward to the end,
        // then the front of the book — and pages an earlier cut-short pass indexed are skipped.
        val o = BalloonIndex.detectionOrder(48, 23, setOf(23, 24, 3))
        assertEquals(25, o.first())
        assertEquals(47, o[22])
        assertEquals(0, o[23])
        assertEquals(45, o.size)
        assertTrue(3 !in o && 23 !in o && 24 !in o)
        // Out-of-range start clamps; an empty book has no order.
        assertEquals(listOf(4, 0, 1, 2, 3), BalloonIndex.detectionOrder(5, 9, emptySet()))
        assertEquals(emptyList<Int>(), BalloonIndex.detectionOrder(0, 0, emptySet()))
    }

    @Test
    fun perPageCountForTheMenu() {
        val json = JSONObject().put("v", 2).put("done", false).put("count", 3)
            .put("pages", JSONObject()
                .put("4", org.json.JSONArray("[[0.1,0.1,0.2,0.2],[0.5,0.5,0.1,0.1]]"))
                .put("5", org.json.JSONArray("[]"))).toString()
        assertEquals(2, BalloonIndex.pageBalloons(json, 4))         // "· 2 on this page"
        assertEquals(0, BalloonIndex.pageBalloons(json, 5))         // "· none found on this page"
        assertNull(BalloonIndex.pageBalloons(json, 6))              // not detected yet → indexing…
        assertNull(BalloonIndex.pageBalloons(null, 4))              // no index at all
    }

    @Test
    fun detectorStampIsTheShippedModel() {
        // `det` gates reuse of a record (and, later, of a synced one): it must name THIS model.
        // The stamp is the first 8 hex of the asset's MD5 — re-export the model and this fails
        // until DET (and with it VERSION's meaning) is reconsidered.
        val f = listOf("src/main/assets/yolo_seeneva.tflite", "app/src/main/assets/yolo_seeneva.tflite")
            .map { java.io.File(it) }.first { it.exists() }
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("seeneva-yolo@" + md5.take(8), BalloonIndex.DET)
    }

    @Test
    fun v3RecordRetryAndCompleteness() {
        // A failed page is never in `pages`; it is retried until MAX_ATTEMPTS, and so is a page
        // whose words failed (its rects kept). `failed` stays a COUNT for every old reader.
        val rec = JSONObject().put("v", 3).put("det", BalloonIndex.DET).put("failed", 2)
            .put("pages", JSONObject().put("0", org.json.JSONArray()).put("1", org.json.JSONArray("[[0.1,0.1,0.2,0.2]]")))
            .put("failedPages", JSONObject().put("2", 1).put("3", BalloonIndex.MAX_ATTEMPTS))
            .put("textFailed", JSONObject().put("1", 2))
        assertEquals(setOf(1, 2), BalloonIndex.retryable(rec))
        assertTrue(BalloonIndex.complete(rec, 4))
        assertTrue(!BalloonIndex.complete(rec, 5))
        assertTrue(BalloonIndex.usable(rec))
        // Every page failed: still usable, so the attempt counts survive the next open.
        val allFailed = JSONObject().put("v", 3).put("det", BalloonIndex.DET)
            .put("pages", JSONObject()).put("failedPages", JSONObject().put("0", 1))
        assertTrue(BalloonIndex.usable(allFailed))
        // An older record, or another model's, is re-indexed.
        assertTrue(!BalloonIndex.usable(JSONObject(rec.toString()).put("v", 2)))
        assertTrue(!BalloonIndex.usable(JSONObject(rec.toString()).put("det", "seeneva-yolo@00000000")))
        // The menu and the fallback sentence can tell a failure from "none found".
        val json = rec.toString()
        assertEquals(0, BalloonIndex.pageBalloons(json, 0))
        assertNull(BalloonIndex.pageBalloons(json, 2))
        assertTrue(BalloonIndex.pageFailed(json, 2) && BalloonIndex.pageRetryable(json, 2))
        assertTrue(BalloonIndex.pageFailed(json, 3) && !BalloonIndex.pageRetryable(json, 3))
        assertTrue(!BalloonIndex.pageFailed(json, 0))
    }

    @Test
    fun panelsAreClassOne() {
        val boxes = arrayOf(
            floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f, 0.95f),   // balloon
            floatArrayOf(0.5f, 0.5f, 0.6f, 0.6f, 0.99f),   // panel
        )
        val panels = BalloonIndex.postprocess(boxes, intArrayOf(0, 1), 2, 480, 736, BalloonIndex.PANEL_CLASS)
        assertEquals(1, panels.length())
        assertEquals(0.5, panels.getJSONArray(0).getDouble(1), 0.01)
    }
}
