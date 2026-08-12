package com.toolsboox.ui.plugin

import android.os.Bundle

/**
 * 🕘 History — a bounded in-memory ring of visited surfaces, newest first, feeding the hub
 * directory's History fold (Michael's 08-12 page). [LedgerReturn] next door holds exactly ONE
 * return anchor and only for the day-page chip; this is the general memory: the last ~30
 * destinations the NavController actually landed on, each with the label it wore and the args
 * it was opened with, so a tap can go straight back — the same day, the same note page, the
 * same article.
 *
 * Fed from ONE place: the `addOnDestinationChangedListener` MainActivity registers on the nav
 * host. That listener is the only true choke point navigation has — there are 112 scattered
 * `navigate()` call sites and no router to instrument, but every one of them ends in the
 * NavController, and the NavController tells this ring. Nothing else may record here, or the
 * ring stops being a record and becomes an opinion.
 *
 * In-memory ON PURPOSE: history is a session's breadcrumb trail, not an archive — persisting
 * it would resurrect week-old trails on cold start (stale args included, which can name
 * since-deleted things). Process death costs the trail and nothing else.
 *
 * Consecutive same-destination visits collapse (a page reload, a same-day re-navigate) —
 * "where have I been" is a list of places, not a list of renders. Distinct args on the same
 * destination (another day, another article) are different places and both stay.
 *
 * DEFERRED, deliberately: the global forward/back edge swipe that was to ride this ring. The
 * day surface already spends ALL FOUR single/two-finger swipe directions (LTR/RTL step days,
 * UTD week, DTU pickings — CalendarDayPage; the notes pages spend all four on the ritual
 * chain), and the tucked rail claims a screen edge via systemGestureExclusionRects — a global
 * back-swipe would collide with day-stepping head-on. History lands as the fold; the gesture
 * waits for a design that doesn't fight the pages.
 */
object LedgerHistory {

    /** ~30 — enough to walk a whole working session back, small enough to skim in the fold. */
    private const val CAPACITY = 30

    /**
     * One visit. [args] is a defensive copy of the destination's arg bundle, held so the
     * return trip can re-navigate with the same everything; [key] is the collapse identity —
     * destination id plus the args that make it a distinct place.
     */
    data class Visit(val destId: Int, val label: String, val args: Bundle?, val key: String)

    private val ring = ArrayDeque<Visit>()

    /**
     * Record a landing. Called from MainActivity's destination-changed listener only.
     *
     * @param destId the NavDestination id (navigable directly — NavController.navigate takes
     *   destination ids as well as action ids)
     * @param label the human reading of the place, built by the caller from the destination
     *   and its args ("📅 Day · Aug 5", "📰 Feeds")
     * @param args the destination's arguments, copied here so a mutable caller bundle can't
     *   rewrite history
     */
    @Synchronized
    fun record(destId: Int, label: String, args: Bundle?) {
        val key = "$destId|$label"
        // Collapse the consecutive repeat; also drop an immediately-preceding twin of the
        // place we just returned to via History itself, so using the fold doesn't braid
        // A-B-A-B chains out of two surfaces.
        if (ring.lastOrNull()?.key == key) ring.removeLast()
        ring.addLast(Visit(destId, label, if (args != null) Bundle(args) else null, key))
        while (ring.size > CAPACITY) ring.removeFirst()
    }

    /**
     * The trail for the fold: newest first, current surface EXCLUDED (offering "return to
     * where you already are" is a row that can only waste a tap), deduped so a surface you
     * bounced through five times is one row at its most recent position.
     */
    @Synchronized
    fun recent(): List<Visit> {
        val out = ArrayList<Visit>(ring.size)
        val seen = HashSet<String>()
        var first = true
        for (v in ring.asReversed()) {
            if (first) { first = false; seen.add(v.key); continue }   // the current surface
            if (seen.add(v.key)) out.add(v)
        }
        return out
    }
}
