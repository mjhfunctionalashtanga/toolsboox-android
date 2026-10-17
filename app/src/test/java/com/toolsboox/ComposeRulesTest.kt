package com.toolsboox

import com.toolsboox.plugin.mail.ui.ComposeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one compose screen's From rule (Michael 2026-10-11, Q2): the letter's own account → the
 * Mail view's account → the default pinned by holding From → the first account; a reply through
 * a site's FluentCRM goes back through the same one; doors that no longer exist fall through.
 */
class ComposeRulesTest {

    private val doors = setOf("work", "home", "crm:ats")
    private val known: (String) -> Boolean = { it in doors }

    private fun from(
        replyCrm: String? = null, replyAccount: String? = null, viewAccount: String? = null,
        pinned: String? = null, first: String? = "home",
    ) = ComposeRules.from(replyCrm, replyAccount, viewAccount, pinned, first, known)

    @Test
    fun `a reply goes out the door its letter came in, over the view and the pin`() {
        assertEquals("work", from(replyAccount = "work", viewAccount = "home", pinned = "home"))
    }

    @Test
    fun `a reply to a letter sent through a site's FluentCRM goes back through it`() {
        assertEquals("crm:ats", from(replyCrm = "crm:ats", replyAccount = "work", viewAccount = "home"))
    }

    @Test
    fun `a fresh letter from The Mail uses the view's account over the pinned default`() {
        assertEquals("work", from(viewAccount = "work", pinned = "home", first = "home"))
    }

    @Test
    fun `a fresh letter from anywhere else uses the pinned default, then the first account`() {
        assertEquals("crm:ats", from(pinned = "crm:ats"))
        assertEquals("home", from())
    }

    @Test
    fun `a removed account or a blank id falls through`() {
        assertEquals("home", from(replyAccount = "gone", viewAccount = " ", pinned = "also-gone"))
        // The Sent pile names no account: the view hands nothing and the pin decides.
        assertEquals("work", from(viewAccount = null, pinned = "work"))
    }

    @Test
    fun `no accounts at all leaves From empty`() {
        assertNull(ComposeRules.from(null, null, null, null, null) { false })
    }

    @Test
    fun `reply subject says Re once`() {
        assertEquals("Re: Hello", ComposeRules.replySubject("Hello"))
        assertEquals("Re: Hello", ComposeRules.replySubject("Re: Hello"))
        assertEquals("RE: Hello", ComposeRules.replySubject("RE: Hello"))
        assertEquals("", ComposeRules.replySubject(""))
    }
}
