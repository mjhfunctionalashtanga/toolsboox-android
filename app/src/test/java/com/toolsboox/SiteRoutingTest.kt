package com.toolsboox

import com.toolsboox.plugin.calendar.nw.SiteRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The per-surface site resolution — the pure half of Michael's "no dominant global site" ruling.
 * Every case here is a sentence from the design: a remembered pick beats the natural home, the
 * natural home beats the active site, the active site beats "first configured", and a pick naming
 * a deleted site is ignored rather than half-honoured. The Context-facing wiring ([SiteAffinity],
 * the fragments' chips) all funnels through [SiteRouting.resolveId], so these tests pin the whole
 * routing behaviour without an Android dependency.
 */
class SiteRoutingTest {

    // The owner's actual fleet, as seedKnownSites builds it (ids are UUIDs in life; short here).
    private val ats = SiteRouting.Ref("ats", "https://ashtanga.tech")
    private val tyc = SiteRouting.Ref("tyc", "https://theyoga.club")
    private val mjh = SiteRouting.Ref("mjh", "https://michaeljoelhall.com")
    private val fleet = listOf(ats, tyc, mjh)

    // ── hostOf: tolerant of everything a hand-typed site URL does ────────────────────────────────

    @Test
    fun hostStripsSchemeCaseWwwPathAndPort() {
        assertEquals("theyoga.club", SiteRouting.hostOf("https://www.TheYoga.Club/booking/"))
        assertEquals("ashtanga.tech", SiteRouting.hostOf("http://ashtanga.tech:443"))
        assertEquals("michaeljoelhall.com", SiteRouting.hostOf("michaeljoelhall.com"))
        assertEquals("", SiteRouting.hostOf("   "))
    }

    // ── natural homes: where each surface's thing actually lives ─────────────────────────────────

    @Test
    fun fluentSurfacesAreHomedOnAshtangaTech() {
        for (surface in listOf(SiteRouting.MESSAGES, SiteRouting.CORRESPONDENCE, SiteRouting.SITE_BOARDS))
            assertEquals("ashtanga.tech", SiteRouting.naturalHomeHost(surface))
    }

    @Test
    fun bookingSurfacesAreHomedOnTheYogaClub() {
        for (surface in listOf(SiteRouting.BOOKINGS, SiteRouting.ROSTER))
            assertEquals("theyoga.club", SiteRouting.naturalHomeHost(surface))
    }

    @Test
    fun publishAndPostsHaveNoHomeButLastUsed() {
        assertNull(SiteRouting.naturalHomeHost(SiteRouting.PUBLISH))
        assertNull(SiteRouting.naturalHomeHost(SiteRouting.POSTS))
    }

    // ── resolution order ─────────────────────────────────────────────────────────────────────────

    @Test
    fun rememberedPickBeatsEverything() {
        // Messages remembered on mjh.com (odd, but the user's explicit pick) → mjh, not ATS, not active.
        assertEquals("mjh", SiteRouting.resolveId(SiteRouting.MESSAGES, "mjh", fleet, activeId = "tyc"))
    }

    @Test
    fun naturalHomeBeatsTheActiveSite() {
        // THE RULING'S CORE CASE: the app's dominant site is theyoga.club, yet Messages still
        // lands on ashtanga.tech — chat lives there; no global switch needed to reach it.
        assertEquals("ats", SiteRouting.resolveId(SiteRouting.MESSAGES, null, fleet, activeId = "tyc"))
        // …and its mirror: bookings land on theyoga.club while the app points at ashtanga.tech.
        assertEquals("tyc", SiteRouting.resolveId(SiteRouting.BOOKINGS, null, fleet, activeId = "ats"))
    }

    @Test
    fun homeMatchIsByHostNotByExactUrl() {
        val messy = listOf(SiteRouting.Ref("x", "http://www.ASHTANGA.tech/"), tyc)
        assertEquals("x", SiteRouting.resolveId(SiteRouting.CORRESPONDENCE, null, messy, activeId = "tyc"))
    }

    @Test
    fun activeSiteIsTheDefaultForHomelessSurfaces() {
        // Publish has no natural home: nothing remembered → the global switcher's site, its
        // demoted-but-alive role as "the default for everything that hasn't chosen".
        assertEquals("tyc", SiteRouting.resolveId(SiteRouting.PUBLISH, null, fleet, activeId = "tyc"))
    }

    @Test
    fun activeSiteCatchesSurfacesWhoseHomeIsNotConfigured() {
        // Only mjh.com + theyoga.club configured: Messages' home (ashtanga.tech) doesn't exist
        // here, so the active site catches it rather than the surface dead-ending.
        val noAts = listOf(mjh, tyc)
        assertEquals("tyc", SiteRouting.resolveId(SiteRouting.MESSAGES, null, noAts, activeId = "tyc"))
    }

    @Test
    fun firstSiteIsTheLastResort() {
        // No pick, no home match, no (valid) active id → the first configured site, so a surface
        // never resolves to nothing while any site exists.
        val noAts = listOf(mjh, tyc)
        assertEquals("mjh", SiteRouting.resolveId(SiteRouting.PUBLISH, null, noAts, activeId = null))
        assertEquals("mjh", SiteRouting.resolveId(SiteRouting.PUBLISH, null, noAts, activeId = "gone"))
    }

    // ── stale ids: deletions must not half-honour old picks ─────────────────────────────────────

    @Test
    fun rememberedIdNamingADeletedSiteIsIgnored() {
        // The pick said "deleted-site"; that site is gone → fall through the whole ladder
        // (home first), exactly as if nothing were remembered.
        assertEquals("ats", SiteRouting.resolveId(SiteRouting.MESSAGES, "deleted", fleet, activeId = "tyc"))
    }

    @Test
    fun staleActiveIdFallsThroughToFirst() {
        assertEquals("ats", SiteRouting.resolveId(SiteRouting.PUBLISH, null, fleet, activeId = "deleted"))
    }

    @Test
    fun noSitesResolvesToNull() {
        assertNull(SiteRouting.resolveId(SiteRouting.MESSAGES, "ats", emptyList(), activeId = "ats"))
    }

    // ── surface keys are persisted prefs — pin them so a rename can't orphan choices ────────────

    @Test
    fun surfaceKeysAreStable() {
        assertEquals("messages", SiteRouting.MESSAGES)
        assertEquals("correspondence", SiteRouting.CORRESPONDENCE)
        assertEquals("site_boards", SiteRouting.SITE_BOARDS)
        assertEquals("bookings", SiteRouting.BOOKINGS)
        assertEquals("roster", SiteRouting.ROSTER)
        assertEquals("publish", SiteRouting.PUBLISH)
        assertEquals("posts", SiteRouting.POSTS)
    }
}
