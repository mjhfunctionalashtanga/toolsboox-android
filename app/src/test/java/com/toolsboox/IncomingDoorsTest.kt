package com.toolsboox

import com.toolsboox.plugin.feeds.ui.IncomingDoors
import com.toolsboox.plugin.feeds.ui.IncomingDoors.Door
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The shared Incoming door order is a promise to Michael (2026-10-11: "the shared order
 * everywhere"): every screen that offers a way into Incoming may show fewer doors, never a
 * different order and never a different name. The drift this replaces happened because each
 * screen typed its own list — his 09-13 order reached the Hub and nowhere else — so these pin the
 * list itself, the one function screens build from, the Doors widget (whose order lives in XML
 * where the compiler can't see it), and that the list-typing screens stay off hand-typed labels.
 */
class IncomingDoorsTest {

    @Test
    fun `the order is Michael's 10-11 list`() {
        assertEquals(
            listOf(
                "➕ Save for later", "🌐 The Net", "📧 The Mail", "🦋 Bluesky",
                "📖 The Read", "📺 The Watch", "🎧 The Listen", "🔖 Later List",
                "⭐ Starred", "💬 Asks & Answers", "# Smart Feeds", "📡 Local Feeds",
            ),
            IncomingDoors.ORDER.map { "${it.glyph} ${it.title}" }
        )
    }

    @Test
    fun `The Mail is The Mail everywhere, tile included`() {
        assertEquals("The Mail", Door.THE_MAIL.title)
        assertEquals("The Mail", Door.THE_MAIL.tile)
        assertEquals("📧  The Mail", Door.THE_MAIL.label)
    }

    @Test
    fun `a screen names its doors, never their order`() {
        // Named scrambled, drawn in the shared order.
        val rows = IncomingDoors.rows(linkedMapOf<Door, () -> Unit>(
            Door.LISTEN to {}, Door.THE_MAIL to {}, Door.WATCH to {}, Door.THE_NET to {}, Door.READ to {},
        ))
        assertEquals(
            listOf("🌐  The Net", "📧  The Mail", "📖  The Read", "📺  The Watch", "🎧  The Listen"),
            rows.map { it.first }
        )
        assertEquals(listOf(Door.SAVE_FOR_LATER, Door.LOCAL), IncomingDoors.inOrder(setOf(Door.LOCAL, Door.SAVE_FOR_LATER)))
    }

    @Test
    fun `a screen's extra row rides directly under its door`() {
        var tapped = ""
        val rows = IncomingDoors.rows(
            mapOf<Door, () -> Unit>(Door.LATER to {}, Door.LISTEN to { tapped = "listen" }),
            after = mapOf(Door.LISTEN to listOf("▶  Continue · Episode" to { tapped = "continue" })),
        )
        assertEquals(listOf("🎧  The Listen", "▶  Continue · Episode", "🔖  Later List"), rows.map { it.first })
        rows[1].second(); assertEquals("continue", tapped)
        rows[0].second(); assertEquals("listen", tapped)
    }

    @Test
    fun `the Doors widget keeps the shared order and names, Watch before Listen`() {
        val byId = mapOf(
            "door_mail" to Door.THE_MAIL, "door_read" to Door.READ,
            "door_watch" to Door.WATCH, "door_listen" to Door.LISTEN,
        )
        val tiles = widgetTiles().filter { it.first in byId }
        val doors = tiles.map { byId.getValue(it.first) }
        assertEquals(listOf(Door.THE_MAIL, Door.READ, Door.WATCH, Door.LISTEN), doors)
        assertEquals(IncomingDoors.inOrder(doors), doors)
        for ((id, texts) in tiles) {
            val door = byId.getValue(id)
            assertEquals("$id glyph", door.glyph, texts.getOrNull(0))
            assertEquals("$id name", door.tile, texts.getOrNull(1))
        }
    }

    @Test
    fun `the screens that list doors take their labels from the list`() {
        // The cause of the drift was hand-typed lists; a quoted door label creeping back into one
        // of these files is the first step of the next drift.
        val files = listOf(
            "plugin/feeds/ui/LedgerDirectory.kt",
            "plugin/feeds/ui/FeedsFragment.kt",
            "plugin/calendar/ui/SiteWebFragment.kt",
        )
        for (rel in files) {
            val src = source("src/main/java/com/toolsboox/$rel").readText()
            for (d in IncomingDoors.ORDER) {
                assertFalse("$rel hand-types \"${d.label}\" — take it from IncomingDoors", src.contains("\"${d.label}\""))
            }
        }
    }

    @Test
    fun `every Mail door goes to The Mail's own room, not to Feeds`() {
        // Michael 2026-10-11: "You can move mail ... out of incoming". The doors that used to put
        // "mail" on the Feeds channel and navigate to Feeds go through openMailRoom now (which
        // keeps kind = "mail" as its own marker, so a stray request in Feeds is handed on).
        val doors = listOf(
            "ui/main/MainActivity.kt",
            "plugin/calendar/ui/CorrespondenceFragment.kt",
            "plugin/calendar/ui/CalendarDayFragment.kt",
        )
        for (rel in doors) {
            val src = source("src/main/java/com/toolsboox/$rel").readText()
            assertFalse("$rel still asks Feeds for mail", src.contains("FeedSelection.kind = \"mail\""))
        }
        val dir = source("src/main/java/com/toolsboox/plugin/feeds/ui/LedgerDirectory.kt").readText()
        val room = dir.substringAfter("fun openMailRoom(").substringBefore("\n}\n")
        assertTrue("openMailRoom must navigate to The Mail's room", room.contains("R.id.action_to_mail"))
    }

    /** The widget's door tiles in layout order: id → its TextViews' texts (glyph, then name). */
    private fun widgetTiles(): List<Pair<String, List<String>>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("src/main/res/layout/doors_widget_layout.xml"))
        val out = mutableListOf<Pair<String, List<String>>>()
        val all = doc.getElementsByTagName("LinearLayout")
        for (i in 0 until all.length) {
            val el = all.item(i) as org.w3c.dom.Element
            val id = el.getAttribute("android:id").removePrefix("@+id/")
            if (!id.startsWith("door_")) continue
            val tvs = el.getElementsByTagName("TextView")
            out += id to (0 until tvs.length).map { (tvs.item(it) as org.w3c.dom.Element).getAttribute("android:text") }
        }
        return out
    }

    /** Gradle runs unit tests from the module directory; the fallback covers a repo-root run. */
    private fun source(rel: String): File =
        listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }
            ?: throw AssertionError("not found: $rel")
}
