package com.toolsboox.plugin.calendar.ui

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.plugin.calendar.nw.RecordPrefs
import com.toolsboox.plugin.calendar.nw.RosterBridge
import com.toolsboox.ui.plugin.ScreenFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pre-session nudge: when an OPTED-IN class (its FluentBooking event bell is on — [RecordPrefs])
 * is about to start and hasn't been dismissed today, it surfaces once with the class announcement and
 * three ways forward — Record (opens the A/V capture surface), Open roster, or Not now (dismisses it
 * for the rest of the day). A personal appointment, or a class you never opted into, never nags.
 *
 * Cheap to call from any screen's onResume: it self-throttles to at most one roster fetch every few
 * minutes and shows at most one dialog at a time. Mirrors the appointment-nudge half of iOS
 * `App/RosterView.swift`. Fires primarily from the day page; the Roster surface calls it too.
 */
object AppointmentNudge {

    /** How close to the start we begin nudging (mirrors the iOS ~45-minute window). */
    private const val WINDOW_MS = 45 * 60 * 1000L

    /** A little grace after the start so a just-begun class still prompts rather than vanishing. */
    private const val GRACE_MS = 10 * 60 * 1000L

    /** Don't re-hit the network on every resume — one roster fetch per this interval is plenty. */
    private const val THROTTLE_MS = 5 * 60 * 1000L

    @Volatile private var lastCheckAt = 0L
    @Volatile private var showing = false

    /**
     * Check the day's roster and, if an opted-in class is imminent and undismissed, show the nudge.
     * [onRecord] is the caller's A/V capture entry (the day page records into the day; the roster
     * does the same).
     */
    fun maybeShow(fragment: ScreenFragment, onRecord: () -> Unit) {
        val now = System.currentTimeMillis()
        if (showing || now - lastCheckAt < THROTTLE_MS) return
        lastCheckAt = now
        val ctx = fragment.requireContext().applicationContext

        fragment.lifecycleScope.launch {
            // The nudge watches the ROSTER's site (theyoga.club by default) — sessions live
            // there whatever the app's active site is.
            val cfg = com.toolsboox.plugin.calendar.nw.SiteAffinity.boardsConfigFor(
                ctx, com.toolsboox.plugin.calendar.nw.SiteRouting.ROSTER
            )
            val next = withContext(Dispatchers.IO) { RosterBridge.nextToday(ctx, cfg) } ?: return@launch
            if (!fragment.isAdded || !fragment.isResumed || showing) return@launch
            if (next.eventId <= 0) return@launch
            if (!RecordPrefs.enabled(ctx, next.eventId)) return@launch
            if (RecordPrefs.dismissedToday(ctx, next.eventId)) return@launch
            val start = next.startEpochMillis ?: return@launch
            val delta = start - System.currentTimeMillis()
            if (delta > WINDOW_MS || delta < -GRACE_MS) return@launch
            show(fragment, next, onRecord)
        }
    }

    private fun show(fragment: ScreenFragment, a: RosterBridge.Attendee, onRecord: () -> Unit) {
        val ctx = fragment.requireContext()
        showing = true
        val title = a.eventTitle.ifBlank { "Upcoming session" } + "  ·  " + a.clock
        val message = a.announcement.ifBlank {
            "${a.name} is booked in. Record the session and pull up its notes?"
        }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Record") { d, _ -> d.dismiss(); onRecord() }
            .setNeutralButton("Open roster") { d, _ ->
                d.dismiss()
                runCatching { NavHostFragment.findNavController(fragment).navigate(R.id.action_to_roster) }
            }
            .setNegativeButton("Not now") { d, _ ->
                RecordPrefs.dismissToday(ctx, a.eventId)
                d.dismiss()
            }
            .setOnDismissListener { showing = false }
            .show()
    }
}
