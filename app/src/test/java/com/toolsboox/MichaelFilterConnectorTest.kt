package com.toolsboox

import com.toolsboox.plugin.michaelfilter.da.IntakeSubmission
import com.toolsboox.plugin.michaelfilter.nw.IntakeDrain
import com.toolsboox.plugin.michaelfilter.nw.MichaelFilterConnector
import com.toolsboox.plugin.michaelfilter.nw.MichaelFilterIntakeClient
import com.toolsboox.plugin.michaelfilter.nw.MichaelFilterIntakeClient.SubmitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MichaelFilter as a Settings connector (2026-10-17).
 *
 * The key used to be compiled into every build; the compiled copy had been dead since July, so the
 * B6's sends all came back 401 and the queue threw each one away without a word. These pin the
 * four behaviours that replaced that: a BLANK key sends nothing and keeps the queue; a SET key is
 * the one that goes out; a 401 is the key's fault, not the item's — the pass stops, nothing is
 * dropped, nothing retries; and what was kept is RE-SENT once a key is saved.
 *
 * Fake keys only — no real key appears in this file.
 */
class MichaelFilterConnectorTest {

    private fun item(n: Int) = IntakeSubmission(linkUrl = "https://example.com/$n", linkKind = "read")

    /** A queue the test can watch: what was sent (with which key) and what was taken off. */
    private class FakeQueue(items: List<IntakeSubmission>) {
        val pending = items.toMutableList()
        val sent = mutableListOf<Pair<String, String>>()   // url to key

        fun drain(key: String, answer: (IntakeSubmission, String) -> SubmitResult): IntakeDrain.Outcome =
            IntakeDrain.run(
                key = key,
                pending = pending.toList(),
                load = { it },
                submit = { s, k -> sent += s.linkUrl to k; answer(s, k) },
                remove = { pending.remove(it) }
            )
    }

    // ── blank ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `blank key sends nothing and keeps every item`() {
        val q = FakeQueue(listOf(item(1), item(2)))
        assertEquals(IntakeDrain.Outcome.NOT_CONNECTED, q.drain("") { _, _ -> SubmitResult.Saved })
        assertEquals(IntakeDrain.Outcome.NOT_CONNECTED, q.drain("   ") { _, _ -> SubmitResult.Saved })
        assertTrue("nothing may be sent without a key", q.sent.isEmpty())
        assertEquals(2, q.pending.size)
    }

    @Test
    fun `the client itself refuses to send without a key`() {
        assertEquals(SubmitResult.NotConnected, MichaelFilterIntakeClient.submit(item(1), ""))
        assertEquals(SubmitResult.NotConnected, MichaelFilterIntakeClient.submit(item(1), "  \n"))
    }

    @Test
    fun `no build carries an intake key`() {
        val names = BuildConfig::class.java.declaredFields.map { it.name }
        assertFalse("BuildConfig.INTAKE_KEY is back — keys belong in Settings", "INTAKE_KEY" in names)
    }

    // ── set ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a set key is the one sent and delivered items leave the queue`() {
        val q = FakeQueue(listOf(item(1), item(2)))
        val outcome = q.drain("fake-key-one") { s, _ ->
            if (s.linkUrl.endsWith("2")) SubmitResult.Duplicate else SubmitResult.Saved
        }
        assertEquals(IntakeDrain.Outcome.DONE, outcome)
        assertTrue(q.pending.isEmpty())
        assertEquals(listOf("fake-key-one", "fake-key-one"), q.sent.map { it.second })
    }

    @Test
    fun `a pasted key is trimmed and its fingerprint is four hex characters`() {
        assertEquals("fake-key-one", MichaelFilterConnector.normalize("  fake-key-one\n"))
        assertEquals("", MichaelFilterConnector.normalize(null))
        val fp = MichaelFilterConnector.fingerprint("fake-key-one")
        assertTrue(fp, Regex("^[0-9a-f]{4}$").matches(fp))
        assertEquals("same key, same fingerprint (whitespace ignored)", fp, MichaelFilterConnector.fingerprint(" fake-key-one "))
        assertNotEquals(fp, MichaelFilterConnector.fingerprint("fake-key-two"))
        assertFalse("the fingerprint must not carry the key", fp.contains("fake"))
        assertEquals("none", MichaelFilterConnector.fingerprint(""))
    }

    // ── 401 ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `401 and WordPress's 403 are the key, item refusals drop, 5xx is the network`() {
        assertEquals(SubmitResult.KeyRejected, MichaelFilterIntakeClient.classify(401, """{"code":"rest_forbidden"}"""))
        assertEquals(SubmitResult.KeyRejected, MichaelFilterIntakeClient.classify(401, ""))
        assertEquals(
            SubmitResult.KeyRejected,
            MichaelFilterIntakeClient.classify(403, """{"code":"rest_forbidden","message":"Sorry","data":{"status":403}}""")
        )
        assertEquals(SubmitResult.Rejected(400), MichaelFilterIntakeClient.classify(400, ""))
        assertEquals(SubmitResult.NetworkFailure, MichaelFilterIntakeClient.classify(503, ""))
        assertEquals(SubmitResult.Saved, MichaelFilterIntakeClient.classify(200, """{"ok":true,"note_id":7}"""))
        assertEquals(SubmitResult.Duplicate, MichaelFilterIntakeClient.classify(200, """{"ok":true,"dup":true}"""))
        assertEquals(SubmitResult.Rejected(200), MichaelFilterIntakeClient.classify(200, """{"ok":false,"error":"no url"}"""))
    }

    @Test
    fun `only the item's own refusals drop it`() {
        for (code in listOf(400, 409, 410, 413, 415, 422)) {
            assertEquals("HTTP $code is this item's fault", SubmitResult.Rejected(code), MichaelFilterIntakeClient.classify(code, ""))
        }
        // The route gone or the plugin off, a timeout, too early, a rate limit: every item would get
        // it, so dropping on it would empty the whole queue the way the dead key's 401 used to.
        for (code in listOf(404, 405, 408, 425, 429)) {
            assertEquals("HTTP $code must be kept", SubmitResult.NetworkFailure, MichaelFilterIntakeClient.classify(code, """{"code":"rest_no_route"}"""))
        }
    }

    @Test
    fun `a firewall page is the network, not the key`() {
        // Hostinger's bot challenge answers 403 with an HTML page (the July 4G sends met it). Its
        // inline CSS has braces org.json would half-parse; it must still not read as WordPress.
        val challenge = "<!DOCTYPE html><html><head><style>body{margin:0}</style></head>" +
            "<body><script>var c={a:1};</script>Checking your browser…</body></html>"
        assertEquals(SubmitResult.NetworkFailure, MichaelFilterIntakeClient.classify(403, challenge))
        assertEquals(SubmitResult.NetworkFailure, MichaelFilterIntakeClient.classify(403, ""))
    }

    @Test
    fun `a 2xx we can't read is kept, a 2xx behind a PHP notice still counts`() {
        assertEquals(SubmitResult.NetworkFailure, MichaelFilterIntakeClient.classify(200, "not json"))
        assertEquals(
            "a maintenance page is not a refusal of the item",
            SubmitResult.NetworkFailure,
            MichaelFilterIntakeClient.classify(200, "<html><style>h1{color:red}</style><h1>Down for maintenance</h1></html>")
        )
        assertEquals(
            "JSON that isn't the intake's answer is not a refusal either",
            SubmitResult.NetworkFailure,
            MichaelFilterIntakeClient.classify(200, """{"message":"cached"}""")
        )
        assertEquals(
            SubmitResult.Saved,
            MichaelFilterIntakeClient.classify(200, "<br />\n<b>Deprecated</b>: something on line <b>12</b><br />\n{\"ok\":true,\"note_id\":9}")
        )
    }

    @Test
    fun `a 404 on every item keeps the whole queue`() {
        val q = FakeQueue(listOf(item(1), item(2), item(3)))
        val outcome = q.drain("fake-key-one") { _, _ -> MichaelFilterIntakeClient.classify(404, """{"code":"rest_no_route"}""") }
        assertEquals(IntakeDrain.Outcome.RETRY_LATER, outcome)
        assertEquals("a missing route is not three bad links", 3, q.pending.size)
    }

    @Test
    fun `a turned-down key stops the pass at once and keeps the whole queue`() {
        val q = FakeQueue(listOf(item(1), item(2), item(3)))
        val outcome = q.drain("fake-dead-key") { _, _ -> SubmitResult.KeyRejected }
        assertEquals(IntakeDrain.Outcome.KEY_TURNED_DOWN, outcome)
        assertEquals("one refusal is enough — no retrying the rest against the same key", 1, q.sent.size)
        assertEquals("nothing is thrown away on a 401", 3, q.pending.size)
    }

    @Test
    fun `a refused item is dropped and network trouble is kept for a retry`() {
        val q = FakeQueue(listOf(item(1), item(2)))
        val outcome = q.drain("fake-key-one") { s, _ ->
            if (s.linkUrl.endsWith("1")) SubmitResult.Rejected(400) else SubmitResult.NetworkFailure
        }
        assertEquals(IntakeDrain.Outcome.RETRY_LATER, outcome)
        assertEquals(listOf("https://example.com/2"), q.pending.map { it.linkUrl })
    }

    // ── re-send ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `what a 401 kept is re-sent once a good key is saved`() {
        val server = { _: IntakeSubmission, key: String ->
            if (key == "fake-good-key") SubmitResult.Saved else SubmitResult.KeyRejected
        }
        val q = FakeQueue(listOf(item(1), item(2), item(3)))

        assertEquals(IntakeDrain.Outcome.KEY_TURNED_DOWN, q.drain("fake-dead-key", server))
        assertEquals(3, q.pending.size)

        q.sent.clear()
        assertEquals(IntakeDrain.Outcome.DONE, q.drain("fake-good-key", server))
        assertTrue("every kept item went out with the new key", q.pending.isEmpty())
        assertEquals(
            listOf("https://example.com/1", "https://example.com/2", "https://example.com/3"),
            q.sent.map { it.first }
        )
        assertTrue(q.sent.all { it.second == "fake-good-key" })
    }

    // ── which key a refusal belongs to ─────────────────────────────────────────────────────

    @Test
    fun `a refusal earned by an old key never brands a new one`() {
        val old = MichaelFilterConnector.fingerprint("fake-dead-key")
        val new = MichaelFilterConnector.fingerprint("fake-good-key")
        // A pass that started with the old key gets its 401 after the new key was saved:
        assertFalse("the stale pass's refusal is not recorded", MichaelFilterConnector.refusalApplies(old, new))
        assertFalse("and an old refusal doesn't count for the new key", MichaelFilterConnector.refusalCounts(new, old))
        assertFalse("nor holds the new key's pass", MichaelFilterConnector.holdsRefusedKey(new, old, 0L, 1_000L))
        // The key actually refused is still turned down.
        assertTrue(MichaelFilterConnector.refusalApplies(new, new))
        assertTrue(MichaelFilterConnector.refusalCounts(new, new))
        assertFalse(MichaelFilterConnector.refusalCounts(new, null))
    }

    @Test
    fun `a refused key is held for a day, then tried once more`() {
        val fp = MichaelFilterConnector.fingerprint("fake-dead-key")
        val day = MichaelFilterConnector.RECHECK_AFTER_MS
        val refusedAt = 10_000_000L
        assertTrue(MichaelFilterConnector.holdsRefusedKey(fp, fp, refusedAt, refusedAt + 1))
        assertTrue(MichaelFilterConnector.holdsRefusedKey(fp, fp, refusedAt, refusedAt + day - 1))
        assertFalse("a day on, the next pass re-checks it", MichaelFilterConnector.holdsRefusedKey(fp, fp, refusedAt, refusedAt + day))
        assertFalse("a clock that went backwards re-checks rather than holds forever",
            MichaelFilterConnector.holdsRefusedKey(fp, fp, refusedAt, refusedAt - 5))
        assertFalse(MichaelFilterConnector.holdsRefusedKey(fp, null, 0L, 1L))
    }

    @Test
    fun `an empty queue is done without asking for a key`() {
        val q = FakeQueue(emptyList())
        assertEquals(IntakeDrain.Outcome.DONE, q.drain("") { _, _ -> SubmitResult.Saved })
        assertTrue(q.sent.isEmpty())
    }
}
