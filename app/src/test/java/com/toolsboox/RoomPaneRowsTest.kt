package com.toolsboox

import com.toolsboox.ot.PanelSeats
import com.toolsboox.plugin.feeds.ui.IncomingDoors
import com.toolsboox.plugin.feeds.ui.MailPlace
import com.toolsboox.plugin.feeds.ui.RoomPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE ROW STYLE FOR THE ROOM PANES (Michael 2026-10-12: "The aesthetic/design weight/language needs
 * a bit of tweaking for readability/usability"): counts first in a compact column, monochrome faces
 * from the panel's own family, the words without their colour emoji.
 */
class RoomPaneRowsTest {

    @Test
    fun `the count column - blank at 0, four characters at most`() {
        assertEquals("", RoomPane.countText(0))
        assertEquals("", RoomPane.countText(-3))
        assertEquals("1", RoomPane.countText(1))
        assertEquals("812", RoomPane.countText(812))
        assertEquals("9999", RoomPane.countText(9999))
        assertEquals("10k", RoomPane.countText(10_000))
        assertEquals("12k", RoomPane.countText(12_345))
        for (n in listOf(0, 7, 99, 812, 9999, 10_000, 99_999, 999_999)) assertTrue("$n", RoomPane.countText(n).length <= 4)
    }

    @Test
    fun `every door and every mailbox has a face, all from the panel family`() {
        val names = R.drawable::class.java.fields.associate { it.getInt(null) to it.name }
        fun res(name: String) = listOf(java.io.File("src/main/res/drawable/$name.xml"),
            java.io.File("app/src/main/res/drawable/$name.xml")).firstOrNull { it.exists() }
        val faces = IncomingDoors.Door.entries.map { it.name to RoomPane.doorFace(it) } +
            MailPlace.Box.entries.map { it.name to RoomPane.boxFace(it) } +
            listOf("read", "watch", "listen", "educate").map { "lane $it" to RoomPane.laneFace(it) } +
            listOf("CONTACTS" to RoomPane.CONTACTS, "SETTINGS" to RoomPane.SETTINGS, "ADD" to RoomPane.ADD,
                "ADD_LINK" to RoomPane.ADD_LINK,
                "LOG" to RoomPane.LOG, "SORT" to RoomPane.SORT, "PODCASTS" to RoomPane.PODCASTS,
                "IMPORT" to RoomPane.IMPORT, "EXPORT" to RoomPane.EXPORT, "TO_SERVER" to RoomPane.TO_SERVER,
                "FEEDS" to RoomPane.FEEDS, "ACCOUNT" to RoomPane.ACCOUNT)
        for ((what, f) in faces) {
            if (f.iconRes == 0) {
                // A text face only from the panel's mono whitelist (@).
                assertTrue("$what wears '${f.glyph}'", f.glyph != null && f.glyph in PanelSeats.MONO_GLYPHS)
                continue
            }
            val name = names[f.iconRes] ?: error("$what: unknown drawable ${f.iconRes}")
            assertTrue("$what wears $name", name.startsWith("ic_pf_") || name == "ic_toolbar_trash")
            if (!name.startsWith("ic_pf_")) continue
            val xml = res(name)?.readText() ?: continue   // run from another directory: names only
            assertTrue("$name is not on the 256 grid", xml.contains("android:viewportWidth=\"256\""))
            assertTrue("$name has no 16-unit stroke", xml.contains("android:strokeWidth=\"16\""))
            assertTrue("$name strokes without round caps", xml.contains("android:strokeLineCap=\"round\""))
        }
        // One face per door: no two doors look alike in one pane.
        val doorFaces = IncomingDoors.Door.entries.map { RoomPane.doorFace(it) }
        assertEquals(doorFaces.size, doorFaces.toSet().size)
        // The mailboxes, likewise.
        val boxFaces = MailPlace.Box.entries.map { RoomPane.boxFace(it) }
        assertEquals(boxFaces.size, boxFaces.toSet().size)
        // ONE FACE, ONE MEANING across the two panes (review 2026-10-12): The Mail is two trays, an
        // Inbox one, Later's Email lane an envelope; Save for later's plus is not Add feed by URL's
        // link; an account is @, never a text glyph.
        val mailDoor = RoomPane.doorFace(IncomingDoors.Door.THE_MAIL)
        val inbox = RoomPane.boxFace(MailPlace.Box.INBOX)
        val emailLane = RoomPane.laneFace("email")
        assertEquals(3, setOf(mailDoor, inbox, emailLane).size)
        assertTrue(RoomPane.ADD_LINK != RoomPane.doorFace(IncomingDoors.Door.SAVE_FOR_LATER))
        assertTrue(RoomPane.ACCOUNT.iconRes != 0)
    }

    @Test
    fun `the count column holds one digit at the least, four at the most`() {
        // Nothing counted: still one digit wide, so the column never folds away as folders open.
        assertEquals(1, RoomPane.digits(emptyList()))
        assertEquals(1, RoomPane.digits(listOf("", "")))
        assertEquals(1, RoomPane.digits(listOf("", "7")))
        assertEquals(3, RoomPane.digits(listOf("12", "812", "")))
        assertEquals(4, RoomPane.digits(listOf(RoomPane.countText(9999))))
        assertEquals(3, RoomPane.digits(listOf(RoomPane.countText(12_345))))   // "12k"
        assertEquals(4, RoomPane.digits(listOf("12345")))
    }

    @Test
    fun `the panes' words carry no colour emoji - the face is the glyph`() {
        val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}]")
        for (d in IncomingDoors.Door.entries) assertTrue(d.title, !emoji.containsMatchIn(d.title))
        for (b in MailPlace.Box.entries) for (n in listOf(1, 2)) {
            assertTrue(b.words(n), !emoji.containsMatchIn(b.words(n)))
            // The Hub keeps its glyph: label = glyph + the same words.
            assertEquals("${b.glyph}  ${b.words(n)}", b.label(n))
        }
        assertEquals("Sent · all accounts", MailPlace.Box.SENT.words(2))
        // One account: the ALL ACCOUNTS heading says whose they are (iOS everyAccountTitle).
        assertEquals("Sent", MailPlace.Box.SENT.words(1))
        assertEquals("Inbox · all accounts", MailPlace.Box.INBOX.words(3))
        // An account's own pile never says "all accounts", however many there are.
        assertEquals("Junk", MailPlace.Box.JUNK.words(3))
        // An account's row: the @ is its face, the words are its name (the fold key keeps the label).
        val labels = MailPlace.AccountLabel.all(listOf(Triple("a", "Michael", "m@x.org"), Triple("b", "Michael", "m@y.org")))
        assertEquals("Michael · m@x.org", MailPlace.AccountLabel.plain(labels.getValue("a")))
        assertEquals("@  Michael · m@y.org", labels.getValue("b"))
    }
}
