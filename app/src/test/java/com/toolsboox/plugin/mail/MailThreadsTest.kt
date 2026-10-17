package com.toolsboox.plugin.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The conversation rules (2026-10-10, M1 "see what you sent, and the thread"). */
class MailThreadsTest {
    private val day = 86_400_000L
    private fun inbox(id: String, from: String, subject: String, date: Long, mid: String = "", refs: String = "") =
        InboxMessage(id = "acct:a1:uid:$id", account = "a", fromName = "", fromEmail = from, subject = subject,
            snippet = "", body = "", date = date, messageId = mid, references = refs)
    private fun sent(id: String, to: String, subject: String, date: Long, mid: String = "", refs: String = "", acct: String = "a1") =
        InboxMessage(id = "sent:$acct:$id", account = "a", fromName = "Me", fromEmail = "me@x.org", subject = subject,
            snippet = "", body = "", date = date, toEmail = to, messageId = mid, references = refs)

    @Test fun inReplyToFoldsIntoReferences() =
        assertEquals("<a@x> <b@x>", MailThreads.mergeRefs("<a@x>", "<b@x>"))

    @Test fun inReplyToAlreadyInChainIsNotRepeated() =
        assertEquals("<a@x> <b@x>", MailThreads.mergeRefs("<a@x> <b@x>", "<b@x>"))

    @Test fun replyRefsEndsWithTheParent() =
        assertEquals("<a@x> <b@x>", MailThreads.replyRefs(inbox("1", "r@y", "Hi there", 0, mid = "<b@x>", refs = "<a@x>")))

    @Test fun subjectDressComesOff() {
        assertEquals("lunch plans", MailThreads.subjectKey("Re: AW: Fwd: Lunch plans"))
        assertEquals("lunch plans", MailThreads.subjectKey("Re[2]: Lunch plans"))
        assertEquals("", MailThreads.subjectKey("Re: Hi"))
        assertEquals("", MailThreads.subjectKey("(no subject)"))
    }

    @Test fun sentRowsNameTheirAccountAndCrmHost() {
        val s = sent("u-1", "r@y", "Re: x", 0)
        assertEquals("a1", MailThreads.replyAccountId(s))
        assertEquals("r@y", MailThreads.replyAddress(s))
        val crm = sent("u-2", "r@y", "Re: x", 0, acct = "crm:example.org")
        assertNull(MailThreads.replyAccountId(crm))
        assertEquals("example.org", MailThreads.crmHost(crm))
    }

    @Test fun chainJoinsBothDirections() {
        val a = inbox("1", "r@y", "Plans", 10 * day, mid = "<a@y>")
        val b = sent("s1", "r@y", "Re: Plans", 11 * day, mid = "<b@me>", refs = "<a@y>")
        val c = inbox("2", "r@y", "Re: Plans", 12 * day, mid = "<c@y>", refs = "<a@y> <b@me>")
        val stranger = inbox("3", "q@z", "Other", 12 * day, mid = "<d@z>")
        assertEquals(listOf(a.id, b.id, c.id), MailThreads.of(b, listOf(a, b, c, stranger)).map { it.id })
        assertEquals(b.id, MailThreads.yourReplyAfter(a, MailThreads.of(a, listOf(a, b, c)))?.id)
    }

    @Test fun idlessRowsFallBackToSubjectAndPerson() {
        val a = inbox("1", "r@y", "Weekend retreat", 10 * day, mid = "<a@y>")
        val oldReply = sent("s1", "r@y", "Re: Weekend retreat", 11 * day)          // written before ids were kept
        val otherPerson = sent("s2", "q@z", "Re: Weekend retreat", 11 * day)
        val tooLate = sent("s3", "r@y", "Re: Weekend retreat", 200 * day)
        assertEquals(listOf(a.id, oldReply.id), MailThreads.of(a, listOf(a, oldReply, otherPerson, tooLate)).map { it.id })
    }

    @Test fun twoLettersWithIdsNeverJoinBySubject() {
        val w1 = inbox("1", "news@y", "Weekly update", 1 * day, mid = "<w1@y>")
        val w2 = inbox("2", "news@y", "Weekly update", 8 * day, mid = "<w2@y>")
        assertEquals(listOf(w1.id), MailThreads.of(w1, listOf(w1, w2)).map { it.id })
    }

    // 2026-10-10 review: an id-less row that joined on subject must not pull in more rows — a weekly
    // subject's old cache would otherwise chain every week into one false conversation.
    @Test fun subjectFallbackNeverChainsThroughAnIdlessRow() {
        val w1 = inbox("1", "shala@y", "Weekly class letter", 1 * day)                   // cached before ids
        val w2 = inbox("2", "shala@y", "Weekly class letter", 8 * day, mid = "<w2@y>")
        val w3 = inbox("3", "shala@y", "Weekly class letter", 15 * day, mid = "<w3@y>")
        val w4 = inbox("4", "shala@y", "Weekly class letter", 22 * day, mid = "<w4@y>")
        assertEquals(listOf(w1.id, w3.id), MailThreads.of(w3, listOf(w1, w2, w3, w4)).map { it.id })
    }

    @Test fun anOldSentRowDoesNotDragInAnotherLetter() {
        val sep1 = inbox("1", "r@y", "Practice question", 1 * day, mid = "<p1@y>")
        val sep5 = inbox("5", "r@y", "Practice question", 5 * day, mid = "<p5@y>")
        val sep6 = sent("s6", "r@y", "Re: Practice question", 6 * day)                  // answered sep5, no ids
        assertEquals(listOf(sep1.id, sep6.id), MailThreads.of(sep1, listOf(sep1, sep5, sep6)).map { it.id })
    }

    // A FluentCRM letter has no Message-ID of its own (the site mints it), so the answer cites an id
    // this device never held — it still joins on subject + person, and the original follows by id.
    @Test fun crmLetterJoinsTheAnswerItGot() {
        val a = inbox("1", "r@y", "Plans", 10 * day, mid = "<a@y>")
        val crm = sent("u1", "r@y", "Re: Plans", 11 * day, refs = "<a@y>", acct = "crm:example.org")
        val answer = inbox("2", "r@y", "Re: Plans", 12 * day, mid = "<c@y>", refs = "<site-minted@example.org>")
        assertEquals(listOf(a.id, crm.id, answer.id), MailThreads.of(answer, listOf(a, crm, answer)).map { it.id })
    }

    // Some providers replace the Message-ID on submission: the answer names only an id this device
    // never saw. Beside a letter you sent (same person, same subject, in the window) it still joins.
    @Test fun replyToAReplacedMessageIdStillJoins() {
        val mine = sent("u1", "r@y", "Retreat dates", 10 * day, mid = "<mine@me>")
        val answer = inbox("2", "r@y", "RE: Retreat dates", 11 * day, mid = "<c@y>", refs = "<swapped@mail.example.net>")
        val unrelated = inbox("3", "r@y", "Retreat dates", 12 * day, mid = "<d@y>")         // no chain: not a reply
        assertEquals(listOf(mine.id, answer.id), MailThreads.of(answer, listOf(mine, answer, unrelated)).map { it.id })
        assertEquals(listOf(mine.id, answer.id), MailThreads.of(mine, listOf(mine, answer, unrelated)).map { it.id })
    }

    // In a group thread your reply to Q's letter answers Q (and Q's earlier letters), never P's.
    @Test fun answeredMeansTheParentAndThatPersonsChain() {
        val toQ = sent("u1", "q@z", "Re: Plans", 3 * day, mid = "<r@me>", refs = "<q0@z> <p1@p> <q1@z>")
        val keys = MailThreads.answeredKeys(listOf(toQ))
        assert("<q1@z>" in keys)
        assert("<q0@z>|q@z" in keys)
        assert("<p1@p>" !in keys && "<p1@p>|p@p" !in keys)
    }

    @Test fun replyRefsKeepTheRootAndTheNewest() {
        val refs = (1..30).joinToString(" ") { "<id$it@x>" }
        val chain = MailThreads.ids(MailThreads.replyRefs(inbox("1", "r@y", "Long one", 0, mid = "<own@x>", refs = refs))!!)
        assertEquals(20, chain.size)
        assertEquals("<id1@x>", chain.first())
        assertEquals("<own@x>", chain.last())
    }

    @Test fun ownIdIsTheCleanToken() =
        assertEquals("<a@b>", MailThreads.inReplyTo(inbox("1", "r@y", "Hi there", 0, mid = " <a@b> (added by relay)")))
}

/** The letter's own Message-ID is the caller's (2026-10-10, gap #4) — the sent row keeps the same one. */
class SmtpOwnMessageIdTest {
    private val smtp = SmtpClient("localhost", 465, true)
    private fun out(mid: String?) = SmtpClient.Outgoing(
        fromEmail = "me@x.org", fromName = "Me", toEmail = "r@y.org", subject = "Re: Plans", body = "yes",
        inReplyTo = "<a@y.org>", references = "<z@y.org> <a@y.org>", messageId = mid)

    @Test fun callersIdGoesOnTheWire() {
        val msg = smtp.buildMessage(out("<abc@x.org>"))
        assert(msg.contains("\r\nMessage-ID: <abc@x.org>\r\n")) { msg }
        assert(msg.contains("\r\nIn-Reply-To: <a@y.org>\r\n")) { msg }
        assert(msg.contains("\r\nReferences: <z@y.org> <a@y.org>\r\n")) { msg }
    }

    // A long References (one id per reply) is folded: no line near the 998-character limit, and
    // unfolding gives back every id (2026-10-10 review; iOS folds at 76 columns too).
    @Test fun longReferencesAreFolded() {
        val ids = (1..30).map { "<CAKx${it}abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH-$it@mail.gmail.com>" }
        val msg = smtp.buildMessage(out("<abc@x.org>").copy(references = ids.joinToString(" ")))
        val head = msg.substringBefore("\r\n\r\n")
        head.split("\r\n").forEach { assert(it.length <= 998) { "line of ${it.length}" } }
        val lines = head.split("\r\n")
        val start = lines.indexOfFirst { it.startsWith("References:") }
        val folded = listOf(lines[start]) + lines.drop(start + 1).takeWhile { it.startsWith(" ") }
        assert(folded.size > 1) { head }
        folded.forEach { assert(it.length <= 78 || MailThreads.ids(it).size == 1) { "unfolded: $it" } }
        assertEquals(ids, MailThreads.ids(folded.joinToString(" ")))
    }

    @Test fun mintedOnTheSendersDomain() {
        val mid = SmtpClient.newMessageId("me@x.org")
        assert(mid.startsWith("<") && mid.endsWith("@x.org>")) { mid }
        assert(smtp.buildMessage(out(null)).contains("@x.org>\r\n")) { "no id minted" }
    }
}
