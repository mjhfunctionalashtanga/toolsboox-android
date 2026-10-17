package com.toolsboox

import com.toolsboox.plugin.mail.ImapClient
import com.toolsboox.plugin.mail.JunkMail
import com.toolsboox.plugin.mail.MailSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🚫 Junk per account (Michael 2026-10-11: "Mail accounts also have individual sents, junks,
 * trash"): the folder is found in the LIST reply the way iOS's `JunkFolder.choose` finds it —
 * special-use \Junk first, else the common names matched on the WHOLE name — and its letters keep
 * ids of their own, spelled as iOS spells them.
 */
class ImapJunkFolderTest {

    @Test
    fun `the special-use Junk attribute wins over a folder merely named Junk`() {
        val gmail = listOf(
            """* LIST (\HasNoChildren) "/" "INBOX"""",
            """* LIST (\HasChildren \Noselect) "/" "[Gmail]"""",
            """* LIST (\HasNoChildren \Junk) "/" "[Gmail]/Spam"""",
            """* LIST (\HasNoChildren \Trash) "/" "[Gmail]/Trash"""",
            """* LIST (\HasNoChildren) "/" "Junk"""",
            "A002 OK Success",
        )
        assertEquals("[Gmail]/Spam", ImapClient.junkMailbox(gmail))
    }

    @Test
    fun `without the attribute the common names count, in iOS's order`() {
        assertEquals("INBOX.Junk", ImapClient.junkMailbox(listOf(
            """* LIST (\HasNoChildren) "." INBOX.Sent""",
            """* LIST (\HasNoChildren) "." INBOX.Junk""",
        )))
        assertEquals("Junk E-mail", ImapClient.junkMailbox(listOf(
            """* LIST (\HasNoChildren) "/" "Junk E-mail"""",
        )))
        assertEquals("Spam", ImapClient.junkMailbox(listOf("""* LIST () NIL Spam""")))
        // Case aside, the name goes back exactly as the server spelled it.
        assertEquals("[Gmail]/spam", ImapClient.junkMailbox(listOf("""* LIST (\HasNoChildren) "/" "[Gmail]/spam"""")))
        // The list's order decides between two present names: Junk before Spam.
        assertEquals("Junk", ImapClient.junkMailbox(listOf(
            """* LIST (\HasNoChildren) "/" "Spam"""",
            """* LIST (\HasNoChildren) "/" "Junk"""",
        )))
    }

    @Test
    fun `a filing folder that merely ends in Spam is never the account's Junk`() {
        // Matched on the whole name, never the last segment (review 2026-10-11).
        assertNull(ImapClient.junkMailbox(listOf(
            """* LIST (\HasNoChildren) "/" "INBOX"""",
            """* LIST (\HasNoChildren) "/" "Clients/Spam"""",
            """* LIST (\HasNoChildren) "/" "Archive/Junk"""",
        )))
        assertEquals("INBOX/Spam", ImapClient.junkMailbox(listOf(
            """* LIST (\HasNoChildren) "/" "Clients/Spam"""",
            """* LIST (\HasNoChildren) "/" "INBOX/Spam"""",
        )))
    }

    @Test
    fun `a folder name sent as a literal is read from the literal`() {
        val lines = listOf(
            """* LIST (\HasNoChildren) "/" {6}""",
            "",
            """* LIST (\HasNoChildren \Junk) "/" {14}""",
            "",
            "A002 OK Success",
        )
        val literals = listOf("Drafts".toByteArray(), "Courrier indé".toByteArray(Charsets.UTF_8))
        assertEquals("Courrier indé", ImapClient.junkMailbox(lines, literals))
        // No literal handed in: that line is skipped, never a crash.
        assertNull(ImapClient.junkMailbox(lines))
    }

    @Test
    fun `no Junk folder, or only an unselectable one, is null`() {
        assertNull(ImapClient.junkMailbox(listOf(
            """* LIST (\HasNoChildren) "/" "INBOX"""",
            """* LIST (\HasNoChildren) "/" "Junkyard Photos"""",
        )))
        assertNull(ImapClient.junkMailbox(listOf("""* LIST (\Noselect \Junk) "/" "Spam"""")))
        assertNull(ImapClient.junkMailbox(emptyList()))
    }

    @Test
    fun `junk letters keep their own ids - spelled as iOS spells them, the account still reads off them`() {
        val id = JunkMail.idFor("acct-1", "77")
        assertEquals("junk:acct-1:uid:77", id)
        assertTrue(JunkMail.isJunk(id))
        assertEquals("acct-1", JunkMail.accountId(id))
        assertEquals("acct-1", MailSync.accountId(id))
        // Never the INBOX letter with the same uid number.
        assertFalse(JunkMail.isJunk("acct:acct-1:uid:77"))
        assertNull(JunkMail.accountId("acct:acct-1:uid:77"))
        assertEquals("acct-1", MailSync.accountId("acct:acct-1:uid:77"))
    }
}
