package com.toolsboox

import com.toolsboox.plugin.mail.MailOpenResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The All Stars "Email" tap, resolved — the 08-12 "it does not load the email, it gets to the
 * email feed however" fix, pinned.
 *
 * A gram starred on the iPad names its letter as `acct:<iPad row id>:uid:<n>`; this device holds
 * the same letter as `acct:<its own row id>:uid:<n>`. [MailOpenResolver] must open on an exact id
 * match, fall back to the letter's own IMAP uid tail when the account prefix is foreign, and
 * refuse to guess when the tail is ambiguous or absent.
 */
class MailOpenResolverTest {

    private val held = listOf(
        "acct:android-a:uid:4711",
        "acct:android-a:uid:4712",
        "acct:android-b:uid:900",
        "sent:android-a:3f9e2b"
    )

    @Test
    fun `exact id opens exactly`() {
        assertEquals("acct:android-a:uid:4712", MailOpenResolver.resolve("acct:android-a:uid:4712", held))
    }

    @Test
    fun `a foreign account prefix still finds the letter by its uid tail`() {
        // The iPad's spelling of a letter this device holds under android-a.
        assertEquals("acct:android-a:uid:4711", MailOpenResolver.resolve("acct:ipad-x:uid:4711", held))
    }

    @Test
    fun `an ambiguous uid tail refuses to guess`() {
        val two = held + "acct:android-b:uid:4711"
        assertNull(MailOpenResolver.resolve("acct:ipad-x:uid:4711", two))
    }

    @Test
    fun `a letter this device never fetched falls through`() {
        assertNull(MailOpenResolver.resolve("acct:ipad-x:uid:9999", held))
    }

    @Test
    fun `ids with no uid tail never match by accident`() {
        // sent: rows and blank/odd ids carry no uid; nothing to fall back on.
        assertNull(MailOpenResolver.resolve("sent:ipad-x:deadbeef", held))
        assertNull(MailOpenResolver.resolve("", held))
        // A non-numeric tail is not a uid.
        assertNull(MailOpenResolver.resolve("acct:ipad-x:uid:47a1", held))
    }

    @Test
    fun `exact match wins even when the tail is ambiguous`() {
        val two = held + "acct:android-b:uid:4711"
        assertEquals("acct:android-b:uid:4711", MailOpenResolver.resolve("acct:android-b:uid:4711", two))
    }

    @Test
    fun `a Junk letter and an Inbox letter never stand in for each other`() {
        // Junk uids number the Junk folder, Inbox uids the INBOX: the same digits are unrelated
        // letters (review 2026-10-11), so the tail only matches within its own kind.
        val withJunk = held + "junk:android-a:uid:777"
        assertNull(MailOpenResolver.resolve("junk:ipad-x:uid:4711", held))
        assertNull(MailOpenResolver.resolve("acct:ipad-x:uid:777", withJunk))
        // Junk to Junk still finds it across the two devices' account spellings (iOS's ids).
        assertEquals("junk:android-a:uid:777", MailOpenResolver.resolve("junk:ipad-x:uid:777", withJunk))
        assertEquals("junk:android-a:uid:777", MailOpenResolver.resolve("junk:android-a:uid:777", withJunk))
    }
}
