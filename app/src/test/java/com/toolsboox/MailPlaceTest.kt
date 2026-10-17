package com.toolsboox

import android.content.SharedPreferences
import com.toolsboox.plugin.feeds.ui.IncomingPlace
import com.toolsboox.plugin.feeds.ui.MailPlace
import com.toolsboox.plugin.feeds.ui.MailPlace.Door
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mail's own remembered place (Michael 2026-10-11: "You can move mail ... out of incoming" ·
 * "Mail can have its own accordion then" — its rows "accounts, starred, inbox, sent, trash"):
 * every way into The Mail lands on the mailbox and view you were in unless the door names one,
 * ☀ Today resets to every account's Unread, and the one-time split moves the mailbox out of the
 * shared Incoming place without touching an RSS place.
 */
class MailPlaceTest {

    private val inAcct = MailPlace(mode = "both", view = "both", mailbox = "acct-1")

    @Test
    fun `the plain door and a cold start land on the whole place`() {
        assertEquals(inAcct, MailPlace.arrive(inAcct, null))
    }

    @Test
    fun `a door that names nothing new keeps the mailbox, at your view`() {
        val p = MailPlace.arrive(inAcct, Door(mode = "feed"))
        assertEquals("acct-1", p.mailbox); assertEquals("both", p.mode)
    }

    @Test
    fun `a door that names a view or a mailbox gets it`() {
        assertEquals("stars", MailPlace.arrive(inAcct, Door(mode = "stars")).mode)
        assertEquals("acct-2", MailPlace.arrive(inAcct, Door(mailbox = "acct-2")).mailbox)
        assertEquals(MailPlace.TRASH, MailPlace.arrive(inAcct, Door(mailbox = MailPlace.TRASH)).mailbox)
    }

    @Test
    fun `Inbox and the widget's Starred land on every account`() {
        assertNull(MailPlace.arrive(inAcct, Door(mailbox = MailPlace.ALL_ACCOUNTS)).mailbox)
        val starred = MailPlace.arrive(inAcct, Door(mode = "stars", mailbox = MailPlace.ALL_ACCOUNTS))
        assertNull(starred.mailbox); assertEquals("stars", starred.mode)
    }

    @Test
    fun `a letter opens over every account so its row is on screen`() {
        assertNull(MailPlace.arrive(inAcct, Door(mode = "both", letter = true)).mailbox)
        // A ↩ chip names the mailbox the letter was read in — that wins.
        assertEquals(MailPlace.TRASH, MailPlace.arrive(inAcct, Door(mailbox = MailPlace.TRASH, letter = true)).mailbox)
    }

    @Test
    fun `Starred is never Sent's or Trash's`() {
        val inSent = MailPlace(mailbox = MailPlace.SENT)
        assertNull(MailPlace.arrive(inSent, Door(mode = "stars")).mailbox)
        assertNull(MailPlace.arrive(MailPlace(mailbox = MailPlace.TRASH), Door(mode = "stars")).mailbox)
        assertEquals(MailPlace.SENT, MailPlace.arrive(inSent, Door(mode = "feed")).mailbox)
    }

    @Test
    fun `Today is every account's Unread`() {
        val t = MailPlace.today(inAcct)
        assertEquals(MailPlace(), t)
        assertTrue(MailPlace.isHome(t))
        assertFalse(MailPlace.isHome(inAcct))
        assertFalse(MailPlace.isHome(MailPlace(mode = "stars")))
        assertFalse(MailPlace.isHome(MailPlace(mailbox = MailPlace.TRASH)))
    }

    @Test
    fun `capture keeps places, not moments`() {
        assertNull(MailPlace.capture(MailPlace(mode = "stars", mailbox = null)))
        assertEquals(inAcct, MailPlace.capture(inAcct))
        assertNull(MailPlace.capture(MailPlace(mode = "later")))
        // 🗑 Trash is a visit — every plain door reopening it would be the "stuck on Starred" trap
        // again (2026-10-11 review; iOS PlaceMemory.remembers) — and so, since each account got its
        // own (Michael 2026-10-11: "individual sents, junks, trash"), is every pile.
        assertNull(MailPlace.capture(MailPlace(mailbox = MailPlace.TRASH)))
        assertNull(MailPlace.capture(MailPlace(mode = "both", view = "both", mailbox = MailPlace.TRASH)))
        assertNull(MailPlace.capture(MailPlace(mailbox = MailPlace.SENT)))
        for (box in listOf(MailPlace.Box.SENT, MailPlace.Box.JUNK, MailPlace.Box.TRASH))
            assertNull(MailPlace.capture(MailPlace(mode = "both", view = "both", mailbox = MailPlace.of(box, "acct-1"))))
        // An account's Inbox is still a place.
        assertEquals("acct-1", MailPlace.capture(MailPlace(mailbox = MailPlace.of(MailPlace.Box.INBOX, "acct-1")))?.mailbox)
    }

    @Test
    fun `a Trash stored by the first cut loads as the Inbox`() {
        val p = MailPlace.load(Prefs(mutableMapOf("mode" to "feed", "view" to "both", "mailbox" to MailPlace.TRASH)))
        assertNull(p.mailbox); assertEquals("both", p.view)
        // Every-account Sent was a place until its row moved under each account (2026-10-11).
        assertNull(MailPlace.load(Prefs(mutableMapOf("mailbox" to MailPlace.SENT))).mailbox)
        assertNull(MailPlace.load(Prefs(mutableMapOf("mailbox" to "__junk:acct-1"))).mailbox)
        assertEquals("acct-1", MailPlace.load(Prefs(mutableMapOf("mailbox" to "acct-1"))).mailbox)
    }

    @Test
    fun `the room stands in an account while it exists`() {
        val two = listOf("acct-1", "acct-2")
        assertEquals("acct-1", MailPlace.standing("acct-1", two))
        // One account: its own row names it now (2026-10-11 — every account has one, for its
        // Sent / Junk / Trash), so the list narrowed to it stands, marked.
        assertEquals("acct-1", MailPlace.standing("acct-1", listOf("acct-1")))
        // An account since removed falls back too; the piles stand whatever the accounts.
        assertNull(MailPlace.standing("gone", two))
        assertEquals(MailPlace.SENT, MailPlace.standing(MailPlace.SENT, emptyList()))
        assertEquals(MailPlace.TRASH, MailPlace.standing(MailPlace.TRASH, listOf("acct-1")))
        assertNull(MailPlace.standing(null, two))
    }

    @Test
    fun `an account's own Sent, Junk and Trash stand while the account does - even the only one`() {
        val one = listOf("acct-1")
        for (box in listOf(MailPlace.Box.SENT, MailPlace.Box.JUNK, MailPlace.Box.TRASH)) {
            val token = MailPlace.of(box, "acct-1")
            assertEquals(token, MailPlace.standing(token, one))
            assertNull(MailPlace.standing(token, listOf("acct-2")))   // its account was removed
        }
        // There is no every-account Junk.
        assertNull(MailPlace.of(MailPlace.Box.JUNK, null))
        assertNull(MailPlace.standing("__junk:", one))
    }

    @Test
    fun `mailbox tokens read back to their box and account`() {
        for (box in MailPlace.Box.PER_ACCOUNT) {
            val w = MailPlace.where(MailPlace.of(box, "acct-1"))
            assertEquals(box, w.box); assertEquals("acct-1", w.account)
        }
        assertEquals(MailPlace.Where(MailPlace.Box.SENT, null), MailPlace.where(MailPlace.SENT))
        assertEquals(MailPlace.Where(MailPlace.Box.TRASH, null), MailPlace.where(MailPlace.TRASH))
        assertEquals(MailPlace.Where(MailPlace.Box.INBOX, null), MailPlace.where(null))
        assertEquals(MailPlace.Where(MailPlace.Box.INBOX, null), MailPlace.where(MailPlace.ALL_ACCOUNTS))
        assertEquals(MailPlace.SENT, MailPlace.of(MailPlace.Box.SENT, null))
        assertNull(MailPlace.of(MailPlace.Box.STARRED, "acct-1"))
        assertEquals("acct-1", MailPlace.accountOf("__trash:acct-1"))
        assertNull(MailPlace.accountOf(MailPlace.TRASH))
        // Starred over an account's pile lets go of the pile, as over every account's.
        assertNull(MailPlace.arrive(MailPlace(mailbox = "__sent:acct-1"), Door(mode = "stars")).mailbox)
    }

    @Test
    fun `the mailboxes are his order and his names`() {
        // Michael 2026-10-11: "Inbox then starred then @gmail" — and each account's own
        // "sents, junks, trash" under it.
        assertEquals(listOf("📥  Inbox", "⭐  Starred"), MailPlace.Box.TOP.map { it.label(1) })
        assertEquals(listOf("📥  Inbox", "➤  Sent", "🚫  Junk", "🗑  Trash"), MailPlace.Box.PER_ACCOUNT.map { it.label() })
        assertEquals("📥  Inbox · all accounts", MailPlace.Box.INBOX.label(2))
        // Every account's Sent and Trash (the Mail drawer's foot, for letters no account owns).
        assertEquals(listOf("➤  Sent · all accounts", "🗑  Trash · all accounts"),
            (MailPlace.Box.EVERY_ACCOUNT - MailPlace.Box.INBOX).map { it.label(2) })
        assertEquals("➤  Sent", MailPlace.Box.SENT.label(1))
        assertEquals("🚫  Junk", MailPlace.Box.JUNK.label(3))
        assertEquals(MailPlace.SENT, MailPlace.of(MailPlace.Box.SENT, null))
        assertEquals(MailPlace.TRASH, MailPlace.of(MailPlace.Box.TRASH, null))
        assertTrue(MailPlace.isPile(MailPlace.SENT) && MailPlace.isPile(MailPlace.TRASH))
        assertTrue(MailPlace.isPile("__sent:acct-1") && MailPlace.isPile("__junk:acct-1") && MailPlace.isPile("__trash:acct-1"))
        assertFalse(MailPlace.isPile(null) || MailPlace.isPile("acct-1"))
    }

    @Test
    fun `the split moves a Mail place across and turns Incoming to your view of everything`() {
        val s = MailPlace.split(mapOf(
            "mode" to "both", "kind" to "mail", "view" to "read", "mailbox" to "acct-1",
            "category" to "Essays", "feed" to "Aeon", "picked_lens" to "read",
        ))
        assertEquals(MailPlace(mode = "both", view = "read", mailbox = "acct-1"), s.mail)
        assertEquals(mapOf("kind" to null, "mode" to "read", "category" to null, "feed" to null, "picked_lens" to null), s.incomingPuts)
        assertEquals(setOf("mailbox"), s.incomingRemoves)
    }

    @Test
    fun `the split never touches an RSS place - only the dead mailbox goes`() {
        val s = MailPlace.split(mapOf(
            "mode" to "later", "kind" to null, "view" to "both", "later_lane" to "watch",
            "category" to "Essays", "feed" to "Aeon", "picked_lens" to "read", "mailbox" to MailPlace.SENT,
        ))
        assertTrue(s.incomingPuts.isEmpty())
        assertEquals(setOf("mailbox"), s.incomingRemoves)
        // The Mail keeps its mailbox and your view; its mode is that view (Mail wasn't where you were).
        assertEquals(MailPlace(mode = "both", view = "both", mailbox = MailPlace.SENT), s.mail)
    }

    @Test
    fun `the split is safe to run twice`() {
        val once = MailPlace.split(mapOf("mode" to "feed", "kind" to "listen", "view" to "feed"))
        assertTrue(once.incomingPuts.isEmpty()); assertTrue(once.incomingRemoves.isEmpty())
        assertEquals(MailPlace(), once.mail)
    }

    @Test
    fun `migrateOnce runs once, before either room saves`() {
        val incoming = Prefs(mutableMapOf("mode" to "read", "kind" to "mail", "view" to "read", "mailbox" to "acct-9"))
        val mail = Prefs()
        MailPlace.migrateOnce(incoming, mail)
        assertEquals(MailPlace(mode = "read", view = "read", mailbox = "acct-9"), MailPlace.load(mail))
        assertFalse(incoming.contains("mailbox")); assertFalse(incoming.contains("kind"))
        // Incoming now loads as your view of everything.
        val feeds = IncomingPlace.load(incoming)
        assertNull(feeds.kind); assertEquals("read", feeds.mode)
        // Feeds saves a new view afterwards; a second run must not copy it into The Mail.
        incoming.edit().putString("view", "both").apply()
        MailPlace.migrateOnce(incoming, mail)
        assertEquals("read", MailPlace.load(mail).view)
    }

    @Test
    fun `a stored mail lens never stands in Feeds, whatever wrote it`() {
        val p = IncomingPlace.load(Prefs(mutableMapOf("mode" to "stars", "kind" to "mail", "view" to "both", "feed" to "x")))
        assertNull(p.kind); assertEquals("both", p.mode); assertNull(p.feed)
        // An RSS place loads untouched.
        val rss = IncomingPlace.load(Prefs(mutableMapOf("mode" to "later", "kind" to "listen", "later_lane" to "watch")))
        assertEquals("later", rss.mode); assertEquals("listen", rss.kind); assertEquals("watch", rss.laterLane)
    }

    @Test
    fun `account rows are told apart even when two share a display name`() {
        val one = MailPlace.AccountLabel.all(listOf(Triple("a", "gmail", "me@example.com")))
        assertEquals(mapOf("a" to "@  gmail"), one)
        // Both From names "Sam Example": the address tells them apart — the Hub's drop-downs
        // and their remembered state are keyed by this label (review 2026-10-11).
        val two = MailPlace.AccountLabel.all(listOf(
            Triple("a", "Sam Example", "me@example.com"),
            Triple("b", "Sam Example", "me@example.org"),
            Triple("c", "Studio", "hi@example.net"),
        ))
        assertEquals("@  Sam Example · me@example.com", two["a"])
        assertEquals("@  Sam Example · me@example.org", two["b"])
        assertEquals("@  Studio", two["c"])
        // The same address twice (no name): a count, never one label for two rows.
        val same = MailPlace.AccountLabel.all(listOf(Triple("a", "x@y.z", "x@y.z"), Triple("b", "x@y.z", "x@y.z")))
        assertEquals(2, same.values.toSet().size)
    }

    /** In-memory prefs with Android's own rule: putting a null string removes the key. */
    private class Prefs(private val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String?, d: MutableSet<String>?) = values[k] as? MutableSet<String> ?: d
        override fun getInt(k: String?, d: Int) = values[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = values[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = values[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = values[k] as? Boolean ?: d
        override fun contains(k: String?) = values.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            override fun putString(k: String?, v: String?) = apply { if (v == null) removed.add(k!!) else staged[k!!] = v }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { staged[k!!] = v }
            override fun putInt(k: String?, v: Int) = apply { staged[k!!] = v }
            override fun putLong(k: String?, v: Long) = apply { staged[k!!] = v }
            override fun putFloat(k: String?, v: Float) = apply { staged[k!!] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { staged[k!!] = v }
            override fun remove(k: String?) = apply { removed.add(k!!) }
            override fun clear() = apply { removed.addAll(values.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                for (k in removed) values.remove(k)
                values.putAll(staged)
            }
        }
    }
}