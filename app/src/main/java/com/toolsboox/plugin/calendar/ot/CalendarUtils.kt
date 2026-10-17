package com.toolsboox.plugin.calendar.ot

import android.content.SharedPreferences
import androidx.constraintlayout.widget.ConstraintSet
import com.toolsboox.R
import com.toolsboox.databinding.FragmentCalendarBinding
import javax.inject.Inject

/**
 * Common calendar utils.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarUtils @Inject constructor() {

    /**
     * The shared preferences.
     */
    @Inject
    lateinit var sharedPreferences: SharedPreferences

    /**
     * Update toolbar side.
     *
     * @param binding the binding
     * @param switch switch side
     */
    fun updateToolbar(binding: FragmentCalendarBinding, switch: Boolean = false) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(binding.drawingLayout)

        val calendarSide = sharedPreferences.getString("calendarToolbarSide", "LEFT")
        val newCalendarSide = if ("LEFT" == calendarSide) {
            if (switch) "RIGHT" else "LEFT"
        } else {
            if (switch) "LEFT" else "RIGHT"
        }

        if ("RIGHT" == newCalendarSide) {
            if (switch) {
                sharedPreferences.edit().putString("calendarToolbarSide", "RIGHT").apply()
            }

            constraintSet.clear(R.id.toolbarDrawing, ConstraintSet.START)
            constraintSet.connect(R.id.toolbarDrawing, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constraintSet.connect(R.id.templateImageView, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            constraintSet.connect(R.id.templateImageView, ConstraintSet.END, R.id.toolbarDrawing, ConstraintSet.START)
            constraintSet.connect(R.id.surfaceView, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            constraintSet.connect(R.id.surfaceView, ConstraintSet.END, R.id.toolbarDrawing, ConstraintSet.START)
        } else {
            if (switch) {
                sharedPreferences.edit().putString("calendarToolbarSide", "LEFT").apply()
            }

            constraintSet.connect(R.id.toolbarDrawing, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            constraintSet.clear(R.id.toolbarDrawing, ConstraintSet.END)
            constraintSet.connect(R.id.templateImageView, ConstraintSet.START, R.id.toolbarDrawing, ConstraintSet.END)
            constraintSet.connect(R.id.templateImageView, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constraintSet.connect(R.id.surfaceView, ConstraintSet.START, R.id.toolbarDrawing, ConstraintSet.END)
            constraintSet.connect(R.id.surfaceView, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        }

        // THE COLUMN HANGS FROM ITS SCREEN EDGE ONLY — no chain with the template. Full screen on an
        // ink page sends the column GONE (TuckPanel.applyState), and a GONE view anchored to the
        // parent alone collapses to a point ON that edge, so the template and the SurfaceView, still
        // constrained beside it, run right to the screen's edge: the drawing viewport takes the
        // column's room (Michael 2026-10-11: "So, yeah it can grow as necessary"). In Normal the
        // column is the same fixed width at the same edge, so nothing moves there. (The page itself
        // re-centres and fits the wider surface in full screen — Michael 2026-10-11:
        // "Recentering/zooming after closing the tuck panel makes sense" — SurfaceFragment's refit,
        // not these constraints.)

        // THE DATE STRIP TAKES THE WHOLE TOP; the panel starts beneath it (Michael 2026-10-10: "The
        // date strip should take up the whole top, the full should start beneath it"). The strip
        // runs edge to edge on either dock side, and the column — ⛶ its first seat — hangs from the
        // strip's bottom instead of the screen's top. The template and the SurfaceView keep exactly
        // the constraints they had (top at the strip's bottom, side by side with the column), so
        // the page's geometry doesn't move: no extra surfaceChanged, no extra B6 reconnect.
        //
        // The strip keeps the whole row in full screen too: the full-screen pill that stood at its
        // start left for the foot of the screen, merged with the notes button (Michael 2026-10-11:
        // "The one top left and the one bottom right" — BottomPill).
        constraintSet.connect(R.id.navigatorImageView, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraintSet.connect(R.id.navigatorImageView, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        constraintSet.connect(R.id.toolbarDrawing, ConstraintSet.TOP, R.id.navigatorImageView, ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.toolbarDrawing, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraintSet.constrainHeight(R.id.toolbarDrawing, ConstraintSet.MATCH_CONSTRAINT)

        // The Roots band's floating line used to be described here too — a view this cloned set
        // doesn't mention comes back 0×0 (laid out, visible, and invisible, which looks exactly like
        // a feature that was never wired up), so it had to be pinned to the origin even though its
        // real position came from the surface's transform. The band is retired and the line with it.

        // The numbered-notes pager hugs the TOP-LEFT corner — centered at the top it sat over the
        // writing (and is excluded from ink capture, so you couldn't write there). The right edge is
        // taken by the nav/tool pills, so left is the free corner. The same 0×0 rule applies to it.
        val pagerGap = (2 * binding.root.resources.displayMetrics.density).toInt()
        // TOP-RIGHT now (09-03: "the pills and title at the top of a note still overlap
        // poorly") — the nav/tool pills that owned that corner are retired, the tuck rail's
        // column is cleared by the inset, and the title ink lives top-LEFT where the pill
        // used to sit on it.
        val pagerInset = (64 * binding.root.resources.displayMetrics.density).toInt()
        constraintSet.connect(R.id.notePager, ConstraintSet.TOP, R.id.navigatorImageView, ConstraintSet.BOTTOM, pagerGap)
        constraintSet.connect(R.id.notePager, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, pagerInset)
        constraintSet.clear(R.id.notePager, ConstraintSet.START)
        constraintSet.constrainWidth(R.id.notePager, ConstraintSet.WRAP_CONTENT)
        constraintSet.constrainHeight(R.id.notePager, ConstraintSet.WRAP_CONTENT)
        // Its writing-state › tab stands exactly where the pill's own › stood (review 2026-10-10):
        // the screen's start edge, its old spot, is the column's ⛶ now that the column shares the
        // row under the strip (Michael 2026-10-10), and the page's start edge is the title ink's.
        // Inside the pager's rect it needs no new exclude rect on Onyx the moment it appears.
        constraintSet.connect(R.id.notePagerTab, ConstraintSet.TOP, R.id.navigatorImageView, ConstraintSet.BOTTOM, pagerGap)
        constraintSet.connect(R.id.notePagerTab, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, pagerInset)
        constraintSet.clear(R.id.notePagerTab, ConstraintSet.START)

        // The day face's NOTES BAND rides the page's bottom margin, tracking the template's own
        // edges so it sits over the paper whichever side the toolbar stands on. Same 0×0 rule as
        // the pager: a view this cloned set doesn't mention is laid out at nothing.
        val density = binding.root.resources.displayMetrics.density
        val bandGap = (6 * density).toInt()
        val bandInset = (8 * density).toInt()
        constraintSet.connect(R.id.notesBand, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, bandGap)
        constraintSet.connect(R.id.notesBand, ConstraintSet.START, R.id.templateImageView, ConstraintSet.START, bandInset)
        constraintSet.connect(R.id.notesBand, ConstraintSet.END, R.id.templateImageView, ConstraintSet.END, bandInset)
        constraintSet.constrainWidth(R.id.notesBand, ConstraintSet.MATCH_CONSTRAINT)
        constraintSet.constrainHeight(R.id.notesBand, ConstraintSet.WRAP_CONTENT)

        // THE TODAY DASHBOARD is no longer a View overlay (CORRECTION 3, 2026-08-23): it is DRAWN
        // INTO the schedule template bitmap (CalendarDayPage.drawDashboardRight), part of the page,
        // so it scales and reflows with the paper through sidebar/drawer transitions on its own —
        // no constraints to describe here. The Schedule-left / right-column-50/50 geometry lives in
        // canvas space in CalendarDayPage.

        constraintSet.applyTo(binding.drawingLayout)
    }
}