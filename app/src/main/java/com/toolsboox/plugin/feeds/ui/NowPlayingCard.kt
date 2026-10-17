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

    /** The position SLIDER: hairline track, solid fill to [fraction], and a round thumb riding
     *  the playhead. It was a 6dp hairline before — draggable all along, but nothing about it
     *  said so, which on e-ink reads as decoration ("Now Playing missing play slider", 08-17).
     *  The thumb is the affordance. */
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
        private val thumbRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt()
        }
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFF000000.toInt()
            strokeWidth = 1.5f * resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val trackH = 6f * d
            val top = (height - trackH) / 2f
            val inset = stroke.strokeWidth / 2f
            canvas.drawRect(inset, top + inset, width - inset, top + trackH - inset, stroke)
            if (fraction > 0f) canvas.drawRect(0f, top, width * fraction, top + trackH, fill)
            // The thumb: a white-filled, black-rimmed knob the finger can aim for. Clamped so it
            // never clips at either end.
            val r = (height / 2f) - d
            val cx = (width * fraction).coerceIn(r, width - r)
            canvas.drawCircle(cx, height / 2f, r, thumbRim)
            canvas.drawCircle(cx, height / 2f, r, thumb)
        }
    }

    /**
     * Build the card. [onStopped] fires (on the main thread) when playback ends or ⏹ is hit,
     * so the host can re-render the directory and let the card disappear.
     *
     * ONE PLAYER, TWO SEATS (Michael, 08-30: "the one in the RSS feeds is the better version,
     * but it should be in the tuck panel"): this card is now the only transport UI —
     * [LedgerPlayer.showModal] hosts it too, so the hub's ▶️ Now Playing row and the drawer
     * show the same instrument. [navHost]+[onNavigate] exist for the modal seat: a dialog's
     * view tree has no nav controller, so the title-door resolves it from the fragment and
     * closes the dialog before travelling. [showArt] brings the episode artwork along where
     * there's room (the modal), and stays off in the 24% drawer pane.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun build(
        context: Context,
        navHost: androidx.fragment.app.Fragment? = null,
        onNavigate: (() -> Unit)? = null,
        showArt: Boolean = false,
        /** The SUMMONED seat (the hub's Now Playing modal) opens FULL regardless of the fold the
         *  reading surface chose — you asked for the transport, so you get it — and its own fold
         *  stays local to the dialog (Michael, 09-13: "clicking Now Playing the first time… the
         *  collapse works on the main area but not there"). */
        startUnfolded: Boolean = false,
        onStopped: () -> Unit,
    ): View {
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
        var folded = if (startUnfolded) false else prefs.getBoolean(KEY_FOLDED, false)

        val titleView = TextView(context).apply {
            text = player.title ?: "Now Playing"
            textSize = 14f; setTextColor(0xFF000000.toInt())
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            // The title is the way BACK to the piece ("Now Playing needs to go back to the
            // article", 08-17 page): the same serverless in-pane handoff the Ledger Log uses —
            // id 0 marks it never-sent-to-Miniflux, blank content makes the reader load the live
            // page. Rides wherever the card is seated (feeds drawer, reader, article) because
            // the nav controller is found from the view itself. Silent for tracks with no
            // capturable source (TTS, audiobooks) — there is no article to go back to.
            isClickable = true
            // Hold the title: the same doors the folded chip offers (09-19).
            setOnLongClickListener {
                if (player.capture == null) false
                else { com.toolsboox.ui.plugin.LedgerPlayerCapture.doorsMenu(context); true }
            }
            setOnClickListener { v ->
                // A BOOK being read aloud: back to the reader, which restores the current book
                // and re-takes the follow highlight on load (09-13).
                if (player.capture == null && player.readingBook != null) {
                    runCatching {
                        val nav = navHost?.let { androidx.navigation.fragment.NavHostFragment.findNavController(it) }
                            ?: androidx.navigation.Navigation.findNavController(v)
                        onNavigate?.invoke()
                        nav.navigate(com.toolsboox.R.id.action_to_reader)
                    }
                    return@setOnClickListener
                }
                val cap = player.capture ?: return@setOnClickListener
                com.toolsboox.plugin.feeds.ui.FeedSelection.pendingInPaneEntry =
                    com.toolsboox.plugin.feeds.da.FeedEntry(
                        id = 0L, title = cap.title, feedTitle = cap.feedTitle, url = cap.url,
                        author = null, content = "", publishedAt = "", starred = false)
                runCatching {
                    // A dialog seat has no controller in its view tree — the modal hands us its
                    // fragment; the drawer seat keeps resolving from the view as it always did.
                    val nav = navHost?.let { androidx.navigation.fragment.NavHostFragment.findNavController(it) }
                        ?: androidx.navigation.Navigation.findNavController(v)
                    onNavigate?.invoke()
                    nav.navigate(com.toolsboox.R.id.action_to_feeds)
                }
            }
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
        // Tap AND DRAG to seek: on real audio x-fraction × duration, live as you drag; on TTS
        // x-fraction × paragraphs, landed on pen-up (every TTS seek restarts the engine, so a
        // drag previews and the lift commits — a bar that was dead on read-aloud read as
        // decoration, 09-06: "the TTS screens need a looksie"). ACTION_DOWN asks the parent
        // scroll NOT to intercept so a horizontal slide isn't stolen as a vertical scroll.
        bar.setOnTouchListener { v, ev ->
            if (v.width <= 0) return@setOnTouchListener false
            val total = player.durationMs
            val chunks = player.ttsChunkCount
            if (player.isMediaSource && total <= 0) return@setOnTouchListener false
            if (!player.isMediaSource && chunks <= 0) return@setOnTouchListener false
            val f = (ev.x / v.width).coerceIn(0f, 1f)
            when (ev.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    bar.fraction = f                       // live preview while dragging
                    if (player.isMediaSource) player.seekTo((f * total).toInt())
                }
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!player.isMediaSource) player.ttsSeekTo((f * chunks).toInt().coerceIn(0, chunks - 1))
                }
                MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
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

        // The skips SAY what they do on each backend: ±30 s of audio, ±one paragraph of speech.
        // "⏪30" on read-aloud promised seconds and delivered a paragraph — a lie the hand
        // noticed. The faces re-sync with the backend (see `sync`).
        val back = chip(if (player.isTtsBacked) "⏪¶" else "⏪30") { player.skipBack(30) }
        val playPause = chip(if (player.isPlaying) "⏸" else "▶") { player.toggle() }
        val fwd = chip(if (player.isTtsBacked) "¶⏩" else "30⏩") { player.skipForward(30) }
        // Chapter skips join the transport row when the episode has chapters; GONE otherwise,
        // so a chapterless track keeps the roomy three-chip row it always had.
        val chapPrev = chip("⏮") { player.chapterPrev() }.apply { visibility = View.GONE }
        val chapNext = chip("⏭") { player.chapterNext() }.apply { visibility = View.GONE }
        val speedBtn = chip("${player.speed}×") { player.cycleSpeed() }.apply {
            // HOLD = this show's own settings (the Podcast Republic lift, hold-to-act canon):
            // pin the current speed to the show, and name how many seconds of intro to skip.
            setOnLongClickListener {
                val feed = player.capture?.feedTitle?.takeIf { it.isNotBlank() }
                    ?: return@setOnLongClickListener true
                val cur = com.toolsboox.ui.plugin.LedgerPlayer.showTuning(context, feed)
                val pinned = cur?.speed != null
                val rows = arrayOf(
                    if (pinned) "✕  Stop always ×${cur?.speed} for this show"
                    else "★  Always ×${player.speed} for this show",
                    "⏩  Skip the first seconds of this show…"
                )
                androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
                    .setTitle(feed)
                    .setItems(rows) { _, which ->
                        when (which) {
                            0 -> com.toolsboox.ui.plugin.LedgerPlayer.setShowTuning(
                                context, feed,
                                if (pinned) null else player.speed, cur?.introSec ?: 0)
                            1 -> {
                                val input = android.widget.EditText(context).apply {
                                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                                    hint = "seconds (0 clears)"
                                    setText(if ((cur?.introSec ?: 0) > 0) "${cur?.introSec}" else "")
                                }
                                androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
                                    .setTitle("Skip intro · $feed")
                                    .setView(android.widget.FrameLayout(context).apply {
                                        setPadding(48, 8, 48, 0); addView(input)
                                    })
                                    .setPositiveButton("Keep") { _, _ ->
                                        com.toolsboox.ui.plugin.LedgerPlayer.setShowTuning(
                                            context, feed, cur?.speed,
                                            input.text.toString().toIntOrNull() ?: 0)
                                    }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
        // 🌙 SLEEP TIMER (the AntennaPod lift): one chip, cycling Off → 15m → 30m → 45m → end
        // of episode. The face carries the countdown so the state never hides in a submenu.
        val sleepBtn = chip(player.sleepLabel()?.let { "🌙$it" } ?: "🌙") { player.cycleSleep() }
        // Assigned once the card and its folded face both exist — the chips below need to call it,
        // and it needs to know about them.
        var applyFold: (Boolean) -> Unit = {}
        // FOLD, next to STOP, so the difference between them is visible at the moment you are
        // choosing: the chevron takes the card away, the ⏹ takes the audio away.
        val foldBtn = chip("⌄") { applyFold(true) }
        val stopBtn = chip("⏹") { player.stop() }
        // 📡 OUTPUT ROUTING (Michael, 08-30: "share the music via AirPlay or whatever the
        // screencast equiv is"). The honest Android answer on this fleet: the player is a bare
        // MediaPlayer (no MediaSession/Cast — Boox ships no Cast framework), and audio follows
        // the SYSTEM route — so the chip opens the system media-output panel where the ROM has
        // one (AOSP 11+ hidden settings panel), and falls back to Bluetooth settings, which
        // always exists and is where a speaker actually gets picked. Playback keeps running;
        // the route switch is the OS's, applied live.
        val routeBtn = chip("📡") { openOutputPicker(context) }
        // 📖 THE PAIRED BOOK (Michael, 09-11: "hook into the ebook"): when the playing audiobook is
        // paired with a book on this shelf, one chip opens the reader on it — and the reader,
        // finding its narration already running, follows along instead of restarting it.
        val bookBtn: TextView? = player.capture?.url?.takeIf { it.startsWith("abs:") }?.let { id ->
            val itemId = id.removePrefix("abs:").substringBefore(':')
            val pairing = runCatching { com.toolsboox.plugin.reader.ui.ReadAlong.forAbsItem(context, itemId) }.getOrNull()
                ?: return@let null
            val entries = runCatching { com.toolsboox.plugin.reader.ui.BookshelfSource.list(context) }.getOrDefault(emptyList())
            val entry = entries.firstOrNull { e ->
                val key = com.toolsboox.plugin.calendar.da.v2.ReadingState.keyFor(e.folder, e.name, e.sizeBytes)
                key == pairing.bookKey || e.name.substringBeforeLast('.') == pairing.bookKey
            } ?: entries.firstOrNull { pairing.bookTitle.isNotBlank() && it.name.contains(pairing.bookTitle, ignoreCase = true) }
            entry?.let { e ->
                chip("📖") {
                    context.getSharedPreferences("ledger_reader_prefs", 0).edit()
                        .putString("open_shelf_entry_on_arrival", e.name).apply()
                    val act = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                        .firstOrNull { it is androidx.fragment.app.FragmentActivity } as? androidx.fragment.app.FragmentActivity
                    val nav = (act?.supportFragmentManager?.findFragmentById(com.toolsboox.R.id.fragmentContent)
                        as? androidx.navigation.fragment.NavHostFragment)?.navController
                    runCatching { nav?.navigate(com.toolsboox.R.id.action_to_reader) }
                        .onFailure { android.widget.Toast.makeText(context, "Open the Bookshelf to read along.", android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
        }
        // 🔦 FOLLOW-ALONG (Michael, 08-30): TTS only — highlights the sentence being spoken on
        // the reading surface (the reader wires LedgerPlayer.onFollowSentence while its page is
        // the one being read). A PREFERENCE, toggled here on the transport; sentence-level by
        // design — see LedgerTts.onSentence for why words would flash e-ink raw.
        val followBtn = if (player.isTtsBacked) chip(if (followOn(context)) "🔦" else "🏮") {
            val now = !followOn(context)
            context.getSharedPreferences("MAIN", 0).edit().putBoolean("tts_follow", now).apply()
        } else null

        val card = SemanticCards.card(context).apply {
            // Episode artwork, modal seat only — the drawer's 24% pane has no room to spend on
            // a picture, and the card's whole discipline there is information per vertical inch.
            if (showArt) player.image?.let { bmp ->
                addView(android.widget.ImageView(context).apply {
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setImageBitmap(bmp)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels * 0.28f).toInt()
                ).apply { bottomMargin = dp(context, 6) })
            }
            addView(titleView)
            addView(subView)
            addView(chapterView)
            addView(bar, LinearLayout.LayoutParams(
                // 18dp: room for the thumb to read as a knob — the 6dp track is drawn centered
                // inside it (see PositionBar.onDraw), so the touch target grew with the look.
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 18)
            ).apply { setMargins(0, dp(context, 6), 0, dp(context, 2)) })
            addView(timeView)
            addView(chipRow(chapPrev, back, playPause, fwd, chapNext), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 4) })
            addView(chipRow(*listOfNotNull(speedBtn, sleepBtn, routeBtn, bookBtn, followBtn, foldBtn, stopBtn).toTypedArray()))
            // Up next, named — the queue the lens armed, visible so the auto-advance is never
            // a surprise. A quiet line, not a control: the flow is the default, ⏹ is the out.
            player.upNextTitle?.let { nxt ->
                addView(TextView(context).apply {
                    text = "↳ Up next · $nxt"
                    textSize = 12f
                    setTextColor(0xFF555555.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(context, 4), dp(context, 2), dp(context, 4), 0)
                })
            }
            // ★ / 📝 for the playing EPISODE, when the track carries a capturable identity
            // (podcasts do; TTS read-aloud doesn't): star it onto All Stars, or open the
            // timestamp-seeded note composer — capture without leaving the drawer, because
            // listening from a lesson never opens the pane where the reader's own star lives.
            // A row of their own, not squeezed into the transport rows: the pane is 24% wide
            // and these are the two chips he taps while the episode keeps playing.
            // …and since 09-19 the row's other verbs too ("provide the annotation pick options on
            // the player"): 🃏 pick · ❝ highlight · ⏳ later, between the star and the note.
            if (player.capture != null) {
                addView(chipRow(
                    chip("★") { com.toolsboox.ui.plugin.LedgerPlayerCapture.starNow(context) },
                    chip("🃏") { com.toolsboox.ui.plugin.LedgerPlayerCapture.pickNow(context) },
                    chip("❝") { com.toolsboox.ui.plugin.LedgerPlayerCapture.highlightNow(context) },
                    chip("⏳") { com.toolsboox.ui.plugin.LedgerPlayerCapture.laterNow(context) },
                    chip("📝") { com.toolsboox.ui.plugin.LedgerPlayerCapture.annotateNow(context) }
                ))
            }
        }

        // The folded face: one button, no information beyond "something is playing" and whether it
        // is running. Right-aligned and WRAP_CONTENT rather than a full-width chip, so what the
        // fold gives back is visibly the whole width of the pane, not a shorter block in the same
        // place. Tapping it brings the transport back.
        val speakerBtn = chip(if (player.isPlaying) "🔊" else "🔈") { applyFold(false) }
        // Folded, the doors are one hold away — the transport need not be unfolded to keep a moment.
        speakerBtn.setOnLongClickListener {
            if (player.capture == null) false
            else { com.toolsboox.ui.plugin.LedgerPlayerCapture.doorsMenu(context); true }
        }
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
                // TTS has chunks, not milliseconds — show where the voice is in the text AND
                // how long is left, the way the audio clock does: a paragraph count alone says
                // nothing about whether this is a five-minute read or an hour.
                val count = player.ttsChunkCount
                val left = player.ttsMinutesLeft
                line = if (count > 0) {
                    "¶ ${player.ttsChunkIndex + 1} / $count" +
                        (if (left > 0) "  ·  ~$left min left" else "")
                } else "¶ —"
                bar.fraction = if (count > 0) player.ttsChunkIndex.toFloat() / count else 0f
            }
            if (line != lastTime) { lastTime = line; timeView.text = line }
            tickChapter()
        }

        // Full sync — glyphs, speed, titles — on real state flips (tap, completion, image load).
        val sync = {
            playPause.text = if (player.isPlaying) "⏸" else "▶"
            back.text = if (player.isTtsBacked) "⏪¶" else "⏪30"
            fwd.text = if (player.isTtsBacked) "¶⏩" else "30⏩"
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
            // The dialog seat's fold is its own — it must not fold (or unfold) the page's chip.
            if (!startUnfolded) prefs.edit()
                .putBoolean(KEY_FOLDED, f)
                // What was playing when you folded it, so the NEXT thing can un-fold it. Written
                // on unfold too, so the pairing can never go stale against a title we never saw.
                .putString(KEY_TITLE, player.title ?: "")
                .apply()
            holder.removeAllViews()
            holder.addView(if (f) foldedRow else card)
            holder.requestLayout()
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
                        // A drag down folds; a TAP is the title's click — the door back to the
                        // piece. Consuming DOWN/UP here had been swallowing that click since the
                        // fold gesture arrived (09-13: "when I click on the title… take me to
                        // the rss feed and show the read along highlight").
                        if (!folded && ev.y - downY > slop) applyFold(true)
                        else if (kotlin.math.abs(ev.y - downY) < slop) titleView.performClick()
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

    /**
     * Seat the transport INLINE on a hosting surface — the 08-12 rule: where playback LIVES
     * (the book reader mid-read-aloud, the open article speaking or playing its podcast), the
     * transport is part of the surface, persistent, with the seek bar and the clock; the modal
     * popup is for surfaces that only reach FOR the player (the hub's "▶️ Now Playing" row).
     * TTS rides the very same card — [build] already renders ¶ chunk progress when the TTS
     * backend is up — so read-aloud and podcast wear one set of controls.
     *
     * Contract: give it an empty FrameLayout parked over the surface (top-anchored, so it
     * never sits on the page-turn corners or the nav pill at the bottom). It empties and
     * fills the container by playback state, and re-seats itself through [build]'s onStopped,
     * so the card leaves the surface the moment ⏹ lands or the episode ends. Call it again
     * whenever the surface starts something or resumes — idempotent, cheap when idle.
     * Navigation never touches playback: LedgerPlayer is process-wide, and this container
     * dying with its fragment tears down LISTENERS only (the attach-state hook in [build]).
     */
    fun seat(container: android.view.ViewGroup, foldDefault: Boolean = false) {
        // READING-SURFACE GRAMMAR (Michael, field, 08-30: while reading there was "no transport
        // affordance at all", elsewhere a card could squat over text): playing = at least the
        // CHIP, never zero, never an unbidden card over prose. foldDefault forces the folded
        // one-chip face on the FIRST seat of a surface visit (container tag = the visit marker);
        // the reader's tap-to-expand then survives re-seats within the visit.
        if (foldDefault && LedgerPlayer.isActive && container.getTag(TAG_SUMMON_SEATED) == null) {
            container.setTag(TAG_SUMMON_SEATED, true)
            container.context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_FOLDED, true).apply()
        }
        container.removeAllViews()
        if (!LedgerPlayer.isActive) { container.visibility = View.GONE; return }
        container.visibility = if (container.getTag(TAG_AWAY) == true) View.GONE else View.VISIBLE
        val card = build(container.context) {
            // Stopped/finished: fall back through the same door — empties the container.
            seat(container)
        }
        container.addView(
            card,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = dp(container.context, 6)
                setMargins(m, m, m, m)
            }
        )
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
    private val TAG_SUMMON_SEATED = "ledger_summon_seated".hashCode()
    private const val TAG_OVERLAY = "ledger-now-playing-summon"
    private val TAG_AWAY = "ledger_transport_away".hashCode()

    /**
     * FULL SCREEN IN A READER LEAVES NOTHING ON THE PAGE (Michael 2026-10-12: "IT cant block the
     * comics"; review 2026-10-12, iOS's Now Playing does the same): the transport seated in
     * [container] stands aside while the reader's full screen has its controls away, and comes back
     * with the peek. Playback is untouched; a re-seat while away stays away.
     */
    fun away(container: android.view.ViewGroup, away: Boolean) {
        container.setTag(TAG_AWAY, if (away) true else null)
        val want = if (away || !LedgerPlayer.isActive || container.childCount == 0) View.GONE else View.VISIBLE
        if (container.visibility != want) container.visibility = want
    }

    /** The reading surfaces' summon chip is held away ([summonAway]) — a chip summoned meanwhile
     *  starts away. */
    private var summonHeldAway = false

    /** [away] for the activity's summon chip (the book reader's and Feeds' transport). */
    fun summonAway(activity: android.app.Activity, away: Boolean) {
        summonHeldAway = away
        val content = activity.findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return
        content.findViewWithTag<android.widget.FrameLayout>(TAG_OVERLAY)?.let { away(it, away) }
    }

    /** The floating summon slot on a READING surface: a small edge-docked container in the
     *  activity's content frame holding the card's folded chip (tap = expand in place, ⌄ = back
     *  to the chip). One grammar everywhere: playing = at least this, and only the reader's own
     *  hand expands it over text. Idempotent; call from onResume and on player-state changes. */
    fun summon(activity: android.app.Activity) {
        val content = activity.findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return
        var overlay = content.findViewWithTag<android.widget.FrameLayout>(TAG_OVERLAY)
        if (!LedgerPlayer.isActive) { overlay?.let { content.removeView(it) }; return }
        if (overlay == null) {
            overlay = android.widget.FrameLayout(activity).apply {
                tag = TAG_OVERLAY
                val dp = activity.resources.displayMetrics.density
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.END
                ).apply { bottomMargin = (56 * dp).toInt(); marginEnd = (10 * dp).toInt() }
            }
            content.addView(overlay)
        }
        overlay.setTag(TAG_AWAY, if (summonHeldAway) true else null)
        seat(overlay, foldDefault = true)
    }

    /** Leaving the surface takes its summon chip with it — the hub/drawer seats own elsewhere. */
    fun dismissSummon(activity: android.app.Activity) {
        val content = activity.findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return
        content.findViewWithTag<android.widget.FrameLayout>(TAG_OVERLAY)?.let {
            it.setTag(TAG_SUMMON_SEATED, null)
            content.removeView(it)
        }
    }

    private fun followOn(context: Context): Boolean =
        context.getSharedPreferences("MAIN", 0).getBoolean("tts_follow", true)

    /** The output ladder: the system media-output panel where the ROM serves one; Bluetooth
     *  settings as the always-there fallback. Both leave playback running — routing is the OS's. */
    private fun openOutputPicker(context: Context) {
        val panel = android.content.Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(panel) }.onFailure {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

