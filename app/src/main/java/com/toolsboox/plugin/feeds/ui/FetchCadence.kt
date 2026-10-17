package com.toolsboox.plugin.feeds.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.toolsboox.ot.EinkControls
import com.toolsboox.plugin.feeds.nw.FeedFetchWorker

/**
 * The background fetch cadence as one block of rows — a label and a big radio row per choice —
 * built the same way wherever it appears. It lives in two dialogs: the feed settings, because
 * articles arrive on it, and the mail accounts, because mail does too (one worker fetches both,
 * and a person setting up mail should not have to know the clock is kept in the feeds' wrench).
 *
 * Picking a row persists and re-arms the worker at once ([FeedFetchWorker.setMinutes]); there is
 * nothing to save afterwards, so the same block behaves identically under a dialog that has a
 * Save button and one that does not.
 */
object FetchCadence {

    /** The one wording, both places. */
    const val LABEL = "Fetch new mail & articles in the background"

    fun view(ctx: Context): View {
        val dp = ctx.resources.displayMetrics.density
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = LABEL; textSize = 14f; setTextColor(0xFF444444.toInt())
            setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt())
        })
        val current = FeedFetchWorker.CADENCE_MINUTES.indexOf(FeedFetchWorker.minutes(ctx))
            .takeIf { it >= 0 } ?: FeedFetchWorker.CADENCE_MINUTES.indexOf(FeedFetchWorker.DEFAULT_MINUTES)
        col.addView(EinkControls.radioRows(ctx, FeedFetchWorker.CADENCE_LABELS, current) { i ->
            FeedFetchWorker.setMinutes(ctx.applicationContext, FeedFetchWorker.CADENCE_MINUTES[i])
        })
        return col
    }
}
