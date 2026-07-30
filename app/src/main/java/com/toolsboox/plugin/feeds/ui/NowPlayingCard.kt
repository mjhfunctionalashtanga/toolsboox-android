package com.toolsboox.plugin.feeds.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.toolsboox.ot.SemanticCards
import com.toolsboox.ui.plugin.LedgerPlayer

/**
 * The drawer's INLINE Now Playing transport — the podcast/read-aloud controls one layer sooner
 * than the modal, as Michael put it: "controls available a layer sooner (the sidebar), not a
 * popup from the sidebar." Replaces the old "▶️ Now Playing" row that merely OPENED
 * [LedgerPlayer.showModal]; the modal itself survives for the reader/article call sites.
 *
 * One [SemanticCards]-ground card sized for the 24% directory pane: title + source, a thin
 * solid position bar (tap-to-seek on real audio), elapsed/total time, and a two-row transport —
 * ⏪30 ⏯ 30⏩ over speed·⏹. The same buttons drive BOTH backends: on a podcast the skips are
 * ±30s of audio; on TTS read-aloud they are ±one chunk (≈paragraph), which is as fine as the
 * Android engine can seek. Everything is solid black on white — no alpha shades to vanish
 * on e-ink (the audit's invisible-chip lesson, same as [SemanticCards.actionChip]).
 *
 * E-ink refresh discipline: a 1s tick updates the TIME TEXT and bar fraction only, and only
 * when the displayed second actually changed — no smooth animation, no full-card redraws.
 * The tick runs solely while the card is attached to a window (the drawer re-renders with
 * removeAllViews, so detach is our teardown signal). Play/pause/speed flips render immediately
 * through [LedgerPlayer]'s change listener.
 */
object NowPlayingCard {

    /**
     * FOLDED AWAY, STILL PLAYING.
     *
     * The card sits at the very top of the directory while anything is playing, which is the point
     * of it — and also the problem: it is a permanent block above the views, the feeds and the
     * sources, and the only control that got rid of it was ⏹, which stops the audio. So "I want my
     * drawer back" and "I am done listening" were the same button, and the first one cost you the
     * second. (Michael's punchlist #17: a hideable now-playing bar.)
     *
     * Folded, it is a single speaker button — playback untouched, the transport one tap away.
     * Persisted, because a card you fold away every time the drawer opens is a card you meant to
     * fold away; the drawer rebuilds this view on every render, so without persistence the fold
     * would last until the next tap on anything.
     *
     * [KEY_TITLE] is what makes it a fold rather than a dismissal: a NEW thing starting un-folds
     * the card. You chose to put away the transport for the episode you were listening to, not to
     * never see a transport again. Kept as the title rather than a flag so it survives the rebuild
     * — the comparison has to work when the card is being constructed from scratch, which is the
     * only moment we can see that what is playing has changed.
     */
    private const val PREFS = "ledger_now_playing"
    private const val KEY_FOLDED = "collapsed"
    private const val KEY_TITLE = "collapsed_title"

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** mm:ss, or h:mm:ss past the hour — podcast episodes routinely cross it. */
    private fun clock(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** Thin solid position bar: black hairline frame, solid black fill to [fraction]. */
    private class PositionBar(context: Context) : View(context) {
        var fraction: Float = 0f
            set(value) {
                val v = value.coerceIn(0f, 1f)
                if (v != field) { field = v; invalidate() }
            }
        private val stroke = Paint().apply {
            style = Paint.Style.STROKE; color = 0xFF000000.toInt()
            strokeWidth = resources.displayMetrics.density
        }
        private val fill = Paint().apply { style = Paint.Style.FILL; color = 0xFF000000.toInt() }

        override fun onDraw(canvas: Canvas) {
            val inset = stroke.strokeWidth / 2f
            canvas.drawRect(inset, inset, width - inset, height - inset, stroke)
            if (fraction > 0f) canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), fill)
        }
    }

    /**
     * Build the card. [onStopped] fires (on the main thread) when playback ends or ⏹ is hit,
     * so the host can re-render the directory and let the card disappear.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun build(context: Context, onStopped: () -> Unit): View {
        val player = LedgerPlayer

        val prefs = context.getSharedPreferences(PREFS, 0)
        // A different thing is playing than the one that was folded away — show its transport.
        // Done here, at construction, because that is where the change is visible: the card is
        // rebuilt whenever the drawer renders, and a fresh build is exactly when we can compare
        // what is playing now against what was playing when the fold was chosen.
        val playingTitle = player.title ?: ""
        if (prefs.getBoolean(KEY_FOLDED, false) && prefs.getString(KEY_TITLE, "") != playingTitle) {
            prefs.edit().putBoolean(KEY_FOLDED, false).apply()
        }
        var folded = prefs.getBoolean(KEY_FOLDED, false)

        val titleView = TextView(context).apply {
            text = player.title ?: "Now Playing"
            textSize = 14f; setTextColor(0xFF000000.toInt())
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val subView = TextView(context).apply {
            text = player.subtitle ?: ""
            textSize = 11.5f; setTextColor(0xFF666666.toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            visibility = if (player.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        // The chapter line — "» 3. The middle part" under the track title when the episode has
        // chapters (2.0 tag or description timestamps; see FeedChapters). Tapping it opens the
        // compact chapter list, times + titles, tap to seek. Hidden entirely for plain tracks,
        // and it APPEARS mid-play when the background resolve lands (setChapters → listener).
        val chapterView = TextView(context).apply {
            textSize = 12f; setTextColor(0xFF000000.toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            isClickable = true
            visibility = View.GONE
            setOnClickListener { showChapterList(context) }
        }

        val bar = PositionBar(context)
        // Tap AND DRAG to seek on real audio (x-fraction × duration); TTS has no clock to land on.
        // ACTION_DOWN asks the parent scroll NOT to intercept so a horizontal slide isn't stolen as
        // a vertical scroll — that's why the bar felt un-draggable.
        bar.setOnTouchListener { v, ev ->
            if (!player.isMediaSource || v.width <= 0) return@setOnTouchListener false
            val total = player.durationMs
            if (total <= 0) return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    val f = (ev.x / v.width).coerceIn(0f, 1f)
                    bar.fraction = f                       // live preview while dragging
                    player.seekTo((f * total).toInt())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            true
        }

        val timeView = TextView(context).apply {
            textSize = 11.5f; setTextColor(0xFF000000.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Transport chips: actionChip's stroke-outlined look, but weight-1 and centered so the
        // row spans the narrow pane in equal thirds instead of ragged WRAP_CONTENT clusters.
        fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 13.5f; setTextColor(0xFF000000.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            maxLines = 1
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0x11000000)
                setStroke(dp(context, 1), 0xFF000000.toInt())
                cornerRadius = dp(context, 8).toFloat()
            }
            setOnClickListener { onClick() }
        }
        fun chipRow(vararg chips: TextView) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for (c in chips) addView(c, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2)) })
        }

        val back = chip("⏪30") { player.skipBack(30) }
        val playPause = chip(if (player.isPlaying) "⏸" else "▶") { player.toggle() }
        val fwd = chip("30⏩") { player.skipForward(30) }
        // Chapter skips join the transport row when the episode has chapters; GONE otherwise,
        // so a chapterless track keeps the roomy three-chip row it always had.
        val chapPrev = chip("⏮") { player.chapterPrev() }.apply { visibility = View.GONE }
        val chapNext = chip("⏭") { player.chapterNext() }.apply { visibility = View.GONE }
        val speedBtn = chip("${player.speed}×") { player.cycleSpeed() }
        // Assigned once the card and its folded face both exist — the chips below need to call it,
        // and it needs to know about them.
        var applyFold: (Boolean) -> Unit = {}
        // FOLD, next to STOP, so the difference between them is visible at the moment you are
        // choosing: the chevron takes the card away, the ⏹ takes the audio away.
        val foldBtn = chip("⌄") { applyFold(true) }
        val stopBtn = chip("⏹") { player.stop() }

        val card = SemanticCards.card(context).apply {
            addView(titleView)
            addView(subView)
            addView(chapterView)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 6)
            ).apply { setMargins(0, dp(context, 8), 0, dp(context, 3)) })
            addView(timeView)
            addView(chipRow(chapPrev, back, playPause, fwd, chapNext), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 4) })
            addView(chipRow(speedBtn, foldBtn, stopBtn))
        }

        // The folded face: one button, no information beyond "something is playing" and whether it
        // is running. Right-aligned and WRAP_CONTENT rather than a full-width chip, so what the
        // fold gives back is visibly the whole width of the pane, not a shorter block in the same
        // place. Tapping it brings the transport back.
        val speakerBtn = chip(if (player.isPlaying) "🔊" else "🔈") { applyFold(false) }
        val foldedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(speakerBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(context, 3), dp(context, 4), dp(context, 3), dp(context, 5))
                marginStart = dp(context, 14)
                marginEnd = dp(context, 14)
            })
        }
        speakerBtn.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))

        // ONE view is handed to the drawer, and the fold swaps what is inside it. Returning the
        // card itself and hiding it would have left the drawer holding a view it could not get
        // back — the host only rebuilds this on stop, so there would be nothing left to tap.
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ---- Live state ------------------------------------------------------------------

        // Coalesce e-ink redraws: setText only when the rendered string actually changed.
        var lastTime = ""
        var lastChapter = ""
        // The chapter line rides the same 1s tick as the clock — it only redraws when the
        // playhead actually crosses into a different chapter (or the list arrives/vanishes).
        val tickChapter = {
            val line = if (player.chapters.isEmpty()) "" else {
                val i = player.currentChapterIndex()
                val t = player.currentChapterTitle
                if (i >= 0 && t != null) "» ${i + 1}/${player.chapters.size} · $t"
                else "» ${player.chapters.size} chapters"
            }
            if (line != lastChapter) {
                lastChapter = line
                chapterView.text = line
                chapterView.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
                val chapVis = if (line.isEmpty()) View.GONE else View.VISIBLE
                chapPrev.visibility = chapVis; chapNext.visibility = chapVis
            }
        }
        val tickPosition = {
            val line: String
            if (player.isMediaSource) {
                val total = player.durationMs
                line = if (total > 0) "${clock(player.positionMs)} / ${clock(total)}"
                else clock(player.positionMs)
                bar.fraction = if (total > 0) player.positionMs.toFloat() / total else 0f
            } else {
                // TTS has chunks, not milliseconds — show where the voice is in the text.
                val count = player.ttsChunkCount
                line = if (count > 0) "¶ ${player.ttsChunkIndex + 1} / $count" else "¶ —"
                bar.fraction = if (count > 0) (player.ttsChunkIndex + 1).toFloat() / count else 0f
            }
            if (line != lastTime) { lastTime = line; timeView.text = line }
            tickChapter()
        }

        // Full sync — glyphs, speed, titles — on real state flips (tap, completion, image load).
        val sync = {
            playPause.text = if (player.isPlaying) "⏸" else "▶"
            speedBtn.text = "${player.speed}×"
            // The folded button carries the one thing it can: running, or paused. Kept in sync
            // even while the transport is showing, so unfolding never flashes a stale glyph.
            speakerBtn.text = if (player.isPlaying) "🔊" else "🔈"
            titleView.text = player.title ?: "Now Playing"
            subView.text = player.subtitle ?: ""
            subView.visibility = if (player.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            // The clock is the one thing the folded face doesn't show, so while folded there is
            // nothing on screen for a tick to move — and on e-ink an update nobody can see is
            // still a redraw somebody paid for.
            if (!folded) tickPosition()
        }

        applyFold = { f ->
            folded = f
            prefs.edit()
                .putBoolean(KEY_FOLDED, f)
                // What was playing when you folded it, so the NEXT thing can un-fold it. Written
                // on unfold too, so the pairing can never go stale against a title we never saw.
                .putString(KEY_TITLE, player.title ?: "")
                .apply()
            holder.removeAllViews()
            holder.addView(if (f) foldedRow else card)
            sync()
        }
        applyFold(folded)

        // A downward drag on the TITLE folds it too — the gesture the shape suggests, and the one
        // a hand reaches for before it finds a chevron. On the title rather than on the card as a
        // whole because the card sits inside the drawer's scroller: claiming touches across the
        // whole card to watch for a flick would fight every scroll that happened to start on it.
        run {
            var downY = 0f
            val slop = dp(context, 24)
            titleView.isClickable = true
            titleView.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = ev.y; true }
                    MotionEvent.ACTION_UP -> {
                        if (!folded && ev.y - downY > slop) applyFold(true)
                        true
                    }
                    else -> false
                }
            }
        }

        var stopped = false
        val listener = {
            if (!player.isActive && !stopped) {
                // Playback ended (or ⏹) — hand the drawer back to the host exactly once.
                stopped = true
                onStopped()
            } else sync()
        }

        val ticker = object : Runnable {
            override fun run() {
                if (!holder.isAttachedToWindow) return
                if (!folded) tickPosition()
                holder.postDelayed(this, 1000)
            }
        }
        // The HOLDER is what the drawer keeps, so it is the honest attach/detach signal: the card
        // itself comes and goes as the fold swaps it, and hanging the teardown off that would stop
        // the clock the first time the transport was folded and never start it again.
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                player.addListener(listener)
                holder.postDelayed(ticker, 1000)
            }
            override fun onViewDetachedFromWindow(v: View) {
                player.removeListener(listener)
                holder.removeCallbacks(ticker)
            }
        })

        return holder
    }

    /** The compact chapter list: "12:34  Title" rows, tap to seek. Plain dialog items — solid
     *  black text on white, no custom shading to lose on e-ink. */
    private fun showChapterList(context: Context) {
        val player = LedgerPlayer
        val chapters = player.chapters
        if (chapters.isEmpty()) return
        val current = player.currentChapterIndex()
        val rows = chapters.mapIndexed { i, c ->
            (if (i == current) "▸ " else "   ") + "${c.clock}  ${c.title}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setTitle(player.title ?: "Chapters")
            .setItems(rows) { _, which ->
                chapters.getOrNull(which)?.let { player.seekTo(it.startSec * 1000) }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
