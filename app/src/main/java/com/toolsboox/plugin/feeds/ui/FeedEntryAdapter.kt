package com.toolsboox.plugin.feeds.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.toolsboox.R
import com.toolsboox.plugin.feeds.da.FeedEntry
import timber.log.Timber
import java.net.URL
import java.util.concurrent.Executors

/**
 * List of feed entries for the in-app Feed Ledger. Row tap opens the article; the star
 * glyph toggles starred (which writes/removes a ReadingEvent in the day JSON).
 */
class FeedEntryAdapter(
    private var items: List<FeedEntry>,
    private val onOpen: (FeedEntry) -> Unit,
    private val onStar: (FeedEntry) -> Unit,
    private val onLongPress: (FeedEntry) -> Unit = {}
) : RecyclerView.Adapter<FeedEntryAdapter.Holder>() {

    /** Accessibility: row text + thumbnail size tier — "small" | "medium" | "large" (wrench). */
    var textTier: String = "medium"
        set(value) {
            if (field != value) { field = value; applyTier(); notifyDataSetChanged() }
        }

    // Per-tier row anatomy, precomputed once per dial change (never per bind — one clean pass
    // over the list, no per-row recalculation). Every tier keeps a description row ("another
    // row for description isn't so bad"): small trims it to 1 line for density, large gets 3.
    //   small  — title 16sp ×2 · blurb 14sp ×1 · 60dp thumb
    //   medium — title 20sp ×2 · blurb 17.5sp ×2 · 76dp thumb
    //   large  — title 23sp ×3 · blurb 20sp ×3 · 116dp thumb
    private var scale = 1.25f
    private var thumbDp = 76
    private var titleLines = 2
    private var blurbLines = 2

    private fun applyTier() {
        scale = when (textTier) { "small" -> 1.0f; "large" -> 1.45f; else -> 1.25f }
        thumbDp = when (textTier) { "small" -> 60; "large" -> 116; else -> 76 }
        titleLines = if (textTier == "large") 3 else 2
        blurbLines = when (textTier) { "small" -> 1; "large" -> 3; else -> 2 }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.entry_title)
        val meta: TextView = view.findViewById(R.id.entry_meta)
        val blurb: TextView = view.findViewById(R.id.entry_blurb)
        val star: TextView = view.findViewById(R.id.entry_star)
        val image: ImageView = view.findViewById(R.id.entry_image)
        /** The picture's frame, and the ▶ that sits on it in The Listen (10-12). */
        val art: android.widget.FrameLayout = view.findViewById(R.id.entry_art)
        val play: ImageView = view.findViewById(R.id.entry_play)
        /** A listen row whose show's cover is not known yet — repainted when it lands, even while
         *  a picture from its notes stands in (the cover outranks it). */
        var waitsCover = false
    }

    // ---- THE LISTEN'S PICTURES + ▶ (Michael 2026-10-12) ----------------------------------------
    //
    // "I wonder why these aren't getting their podcasts cover pic or article featured image etc."
    // Every Listen row shows a picture — the episode's own, else its show's cover ([ShowCovers],
    // learned once per show), else (an article) its featured image — and in The Listen the ▶ sits
    // ON it: tap the picture to play (or pause the one playing), tap the words to open the entry,
    // exactly as before. The host says which rows can play; the adapter draws and routes.

    /** The play verb for a row's picture; null (the default) = no ▶ anywhere. */
    var onPlay: ((FeedEntry) -> Unit)? = null
    /** Whether a row gets the ▶ on its picture — the host's call (The Listen, a playable row). */
    var playable: (FeedEntry) -> Boolean = { false }
    /** The SHOW a row belongs to, for its cover — the feed title, except where the host knows
     *  better (the in-progress shelf, whose feed line carries "· N min left"). */
    var showOf: (FeedEntry) -> String? = { it.feedTitle.ifBlank { null } }

    private var playableIds: Set<Long> = emptySet()
    private var recycler: RecyclerView? = null
    private var playerSig: String? = null
    private val playerListener: () -> Unit = { onMain { repaintBadges() } }
    private val coverListener: () -> Unit = { repaintPendingCovers() }
    private val attachWatch = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = listen(true)
        override fun onViewDetachedFromWindow(v: View) = listen(false)
    }
    private var listening = false

    /** Hooked while the list is on screen and only then — the player and the cover index are
     *  process-wide, and a listener left behind would hold this adapter (and its views) forever. */
    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) {
            com.toolsboox.ui.plugin.LedgerPlayer.addListener(playerListener)
            com.toolsboox.plugin.feeds.nw.ShowCovers.addListener(coverListener)
            playerSig = null
            repaintBadges()
        } else {
            com.toolsboox.ui.plugin.LedgerPlayer.removeListener(playerListener)
            com.toolsboox.plugin.feeds.nw.ShowCovers.removeListener(coverListener)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
        // A changed row rebinds in place, never cross-fades (review 10-12): the diff now marks a
        // row changed when its picture arrives or the lens gives it a ▶, and the default
        // animator's fade between two bindings is a smear on e-ink.
        (recyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        recyclerView.addOnAttachStateChangeListener(attachWatch)
        if (recyclerView.isAttachedToWindow) listen(true)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnAttachStateChangeListener(attachWatch)
        listen(false)
        if (recycler === recyclerView) recycler = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
    }

    /** Each bound row with its entry, for the in-place repaints below (no notifyItemChanged: a
     *  rebind runs the change animation, and a cross-fading row is a smear on e-ink). */
    private inline fun forBound(block: (Holder, FeedEntry) -> Unit) {
        val rv = recycler ?: return
        for (i in 0 until rv.childCount) {
            val h = rv.getChildViewHolder(rv.getChildAt(i)) as? Holder ?: continue
            val e = items.getOrNull(h.adapterPosition) ?: continue
            block(h, e)
        }
    }

    /** The player moved (a new episode, play, pause): repaint only the ▶/❚❚ glyphs, and only
     *  when what the glyphs say actually changed — the player notifies on every seek and chapter. */
    private fun repaintBadges() {
        val p = com.toolsboox.ui.plugin.LedgerPlayer
        val c = p.capture
        val sig = if (c == null) "" else "${c.url}|${c.enclosureUrl}|${p.isPlaying}"
        if (sig == playerSig) return
        playerSig = sig
        forBound { h, e -> if (h.play.visibility == View.VISIBLE) paintBadge(h, e) }
    }

    /** A show's cover was just learned: the rows still waiting for one get it now (a row showing
     *  a notes picture meanwhile trades it for the cover, which outranks it). */
    private fun repaintPendingCovers() {
        forBound { h, e ->
            if (!h.waitsCover) return@forBound
            val art = artOf(h.itemView.context, e)
            h.waitsCover = art.waits
            val best = art.keys.firstOrNull { !FeedThumbCache.isDead(it) } ?: return@forBound
            if (h.image.tag != best) loadInto(h.image, art.keys, keepSlot = h.play.visibility == View.VISIBLE)
        }
    }

    /** Is [e] the episode on the player? By its page (the capture's identity) or its file. */
    private fun isCurrent(e: FeedEntry): Boolean {
        val c = com.toolsboox.ui.plugin.LedgerPlayer.capture ?: return false
        if (c.url.isNotBlank() && c.url == e.url) return true
        val f = c.enclosureUrl
        return f.isNotBlank() && (f == e.enclosureAudio || f == e.mediaUrl)
    }

    private fun paintBadge(h: Holder, e: FeedEntry) {
        val playing = isCurrent(e) && com.toolsboox.ui.plugin.LedgerPlayer.isPlaying
        if (h.play.tag != playing) {
            h.play.tag = playing
            h.play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        h.art.contentDescription = if (playing) "Pause" else "Play"
    }

    /** The picture's tap: the episode on the player toggles; any other plays. */
    private fun tapArt(e: FeedEntry) {
        val p = com.toolsboox.ui.plugin.LedgerPlayer
        if (isCurrent(e) && p.isActive) { p.toggle(); return }
        onPlay?.invoke(e)
    }

    /** A row's pictures, best first, and whether it still waits for its show's cover. */
    private class Art(val keys: List<String>, val waits: Boolean)

    /**
     * The row's pictures, in the order they are tried — each one when the one before fails to
     * load. A LISTEN row (Michael's order, 10-12, the iPhone's too): the episode's own image → an
     * Audiobookshelf cover PATH (its token added only at fetch time, never a key or a file name)
     * → the show's cover, if already known → an image from the notes (it used to come FIRST,
     * so a sponsor banner in the show notes beat the episode's own art). Any other row: its
     * featured image, then its image enclosure. Never network.
     */
    private fun artOf(ctx: android.content.Context, e: FeedEntry): Art {
        val own = e.enclosureImage?.trim()
        if (e.kind != "listen") return Art(listOfNotNull(
            e.inlineImage, own?.takeIf { it.startsWith("http") }, own?.takeIf { it.startsWith("/api/") }).distinct(), false)
        val show = showOf(e)
        val cover = com.toolsboox.plugin.feeds.nw.ShowCovers.lookup(ctx, e.feedUrl, show)
        val ownArt = own != null && (own.startsWith("http") || own.startsWith("/api/"))
        // Waits for the cover only when there is no art of its own (an audiobook's is its own) and
        // something names the show to ask about.
        val waits = cover == null && !ownArt && !e.url.startsWith("abs:") && (e.feedUrl != null || show != null)
        return Art(com.toolsboox.plugin.feeds.nw.ShowCovers.Keys.listenOrder(own, cover, e.inlineImage), waits)
    }

    /**
     * The mail rows you answered, drawn "↩ " before the sender on the meta line (2026-10-10, M1 —
     * "did I answer this?" in no taps). The host fills it as it mints rows; the adapter reads it
     * at each [submit]. A DRAWN mark, never written into the entry: a source starting with ↩
     * already means "correspondence" to the reading log and the year, and a capture handed a
     * list entry would have filed a letter as one (2026-10-10 review).
     */
    var answeredSource: Set<Long> = emptySet()
    private var answered: Set<Long> = emptySet()

    fun submit(list: List<FeedEntry>) {
        val old = items
        val oldAnswered = answered
        // A snapshot per submit, so the diff below can see a row's mark change.
        answered = if (answeredSource.isEmpty()) emptySet() else HashSet(answeredSource)
        // …and which rows wear the ▶: a lens switch (The Feed → The Listen) keeps the same entry
        // rows with the same content, and without this the diff left them unbadged (10-12).
        val oldPlaying = playableIds
        playableIds = if (onPlay == null) emptySet() else list.filter(playable).mapTo(HashSet()) { it.id }
        items = list
        // DiffUtil (audit 09-10): every submit — a search keystroke, a star, a mail re-gather —
        // was a full `notifyDataSetChanged`, i.e. every visible row re-inflated its text and
        // thumbnail and e-ink repainted the whole page. Diff by id/content instead so only the
        // rows that changed rebind. Sync, so `current()` is right the instant this returns.
        // Bounded: Myers is O(N·D); two large, unrelated lists (a lens switch over 5,000 mail
        // rows) fall back to the old full notify rather than pay a quadratic diff on a Boox.
        if (old.isEmpty() || list.isEmpty() || old.size > DIFF_MAX || list.size > DIFF_MAX) {
            notifyDataSetChanged(); return
        }
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].id == list[b].id
            override fun areContentsTheSame(a: Int, b: Int): Boolean {
                val x = old[a]; val y = list[b]   // cheap fields, not the whole content string (audit 09-11)
                return x.starred == y.starred && x.read == y.read && x.title == y.title &&
                    x.feedTitle == y.feedTitle && x.publishedAt == y.publishedAt && x.content.length == y.content.length &&
                    // A picture that arrived since (the in-progress shelf learns art from the
                    // other device's sync) is a changed row (10-12).
                    x.enclosureImage == y.enclosureImage &&
                    (x.id in oldAnswered) == (y.id in answered) &&
                    (x.id in oldPlaying) == (y.id in playableIds)
            }
        }, false)
        diff.dispatchUpdatesTo(this)
    }

    /** The currently shown list (for paging in the article reader). */
    fun current(): List<FeedEntry> = items

    // ---- Selection mode (Later List bulk delete) ---------------------------------------------

    /** While true, rows render a flat ☐/☑ checkbox before the title and a tap TOGGLES the row
     *  instead of opening it — selection is a mode you enter on purpose (from the row's hold
     *  menu) and leave through the action bar, never something a stray tap starts. Solid glyphs
     *  in the title run, no tinted overlays: a translucent "selected" wash is exactly the gray
     *  that dies on e-ink. */
    var selecting = false
        private set

    /** Ids chosen so far. Entry ids, not positions — the Later List's ids are content hashes
     *  (see [com.toolsboox.plugin.feeds.nw.LaterFeed.entryId]), so a background reload can't
     *  shift a choice onto the row below it. */
    val selectedIds = LinkedHashSet<Long>()

    /** Fired with the new count after every toggle, so the host's action bar can say "Delete (3)"
     *  without polling. */
    var onSelectionChanged: (Int) -> Unit = {}

    fun beginSelection(seedId: Long?) {
        selecting = true
        selectedIds.clear()
        seedId?.let { selectedIds.add(it) }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun endSelection() {
        if (!selecting) return
        selecting = false
        selectedIds.clear()
        notifyDataSetChanged()
    }

    private fun toggleSelected(e: FeedEntry) {
        if (!selectedIds.add(e.id)) selectedIds.remove(e.id)
        val i = items.indexOfFirst { it.id == e.id }
        if (i >= 0) notifyItemChanged(i)
        onSelectionChanged(selectedIds.size)
    }

    /** Flip one row's star IN PLACE. Starring used to route through refresh(), which reloads the
     *  feed and closes any open in-pane article — so a star kicked you out of what you were
     *  reading. This repaints just the one row (no scroll reset, no pane teardown). */
    fun setStarred(id: Long, starred: Boolean) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items = items.toMutableList().also { it[i] = it[i].copy(starred = starred) }
        notifyItemChanged(i)
    }

    /** Flip one row's read flag IN PLACE, as [setStarred] flips its star — ● Unread on a letter
     *  (Michael 2026-10-11) un-greys the row without a reload. */
    fun setRead(id: Long, read: Boolean) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items = items.toMutableList().also { it[i] = it[i].copy(read = read) }
        notifyItemChanged(i)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_feed_entry, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        val isRead = e.read || FeedReadState.isRead(e.id)

        // Row tier: Small / Medium / Large scales the text and the featured thumbnail together.
        // Ladder recalibrated on the Tab Mini C (07-24): every tier read too small at reading
        // distance — Small was squinting, Medium barely better, Large nearly right. The whole
        // ladder steps up: Small ≈ the old Medium, Medium ≈ the old Large, Large a notch past it.
        // (Values precomputed in applyTier(); this just applies them, guarded so an unchanged
        // thumbnail size never touches layoutParams — one clean redraw per dial change.)
        holder.title.textSize = 16f * scale
        holder.title.maxLines = titleLines
        holder.meta.textSize = 12f * scale
        holder.blurb.textSize = 14f * scale
        holder.blurb.maxLines = blurbLines
        val side = (thumbDp * holder.image.resources.displayMetrics.density).toInt()
        if (holder.image.layoutParams.width != side) {
            holder.image.layoutParams = holder.image.layoutParams.apply { width = side; height = side }
            // The ▶ scales with the picture it sits on (≈40% of it), so every tier reads alike.
            val badge = (side * 0.4f).toInt()
            holder.play.layoutParams = holder.play.layoutParams.apply { width = badge; height = badge }
            val pad = (badge * 0.22f).toInt()
            holder.play.setPadding(pad, pad, pad, pad)
        }

        // In selection mode the checkbox leads the title — same typeface run, so it stays crisp
        // at every tier without a second view or a layout change.
        holder.title.text =
            if (selecting) (if (selectedIds.contains(e.id)) "☑  " else "☐  ") + e.title
            else e.title
        // Unread stands out (bold, full weight); read is normal + dimmed.
        holder.title.setTypeface(null, if (isRead) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        holder.title.alpha = if (isRead) 0.55f else 1f

        // Meta: unread ● dot · read/watch/listen lens · the feed folder · author · date + time.
        val dot = if (isRead) "" else "●  "
        // A letter is not an article: The Mail's rows carry a mail:// address, and the medium
        // glyph should say so rather than dressing every email as 📖 The Read.
        val lens = if (e.url.startsWith("mail://")) "✉"
        else when (e.kind) { "watch" -> "📺"; "listen" -> "🎧"; else -> "📖" }
        val folder = (if (e.id in answered) "↩ " else "") + (e.categoryLabel?.takeIf { it.isNotBlank() } ?: e.feedTitle)
        holder.meta.text = dot + lens + "  " + listOf(folder, e.author ?: "", formatWhen(e.publishedAt))
            .filter { it.isNotBlank() }.joinToString(" · ")

        // Description row: the entry's excerpt, present at every tier — but an entry with no
        // excerpt shouldn't pay an empty line's height for it.
        holder.blurb.text = e.blurb
        holder.blurb.visibility = if (e.blurb.isBlank()) View.GONE else View.VISIBLE
        holder.star.text = if (e.starred) "★" else "☆"
        // The glyph, not the vibe: reading faces (Fast Mono et al.) draw ★ hollow or substitute
        // it, so a starred row read as unstarred on device. System face keeps the fill solid;
        // the unstarred outline goes quiet gray so the contrast states are unmistakable.
        holder.star.typeface = android.graphics.Typeface.DEFAULT
        holder.star.setTextColor(if (e.starred) 0xFF000000.toInt() else 0xFF9A9A9A.toInt())
        // Inline star: a compact glyph on the meta line (a notch above the meta size at every
        // tier), so the title and thumbnail keep the row's width. Its VISUAL size stays small…
        holder.star.textSize = 14f * scale
        holder.star.contentDescription = if (e.starred) "Unstar" else "Star"
        // Selection mode claims every gesture on the row — tap, hold and the star alike all
        // toggle. Mixed verbs mid-selection (one tap toggles, the next opens an article) is how
        // a bulk delete ends up with a row in it nobody chose.
        holder.itemView.setOnClickListener { if (selecting) toggleSelected(e) else onOpen(e) }   // fragment marks read per the user's setting
        holder.itemView.setOnLongClickListener {
            if (selecting) toggleSelected(e) else onLongPress(e); true
        }   // → the row's hold menu (Reply / Mark above as read / Star / Forward)
        holder.star.setOnClickListener { if (selecting) toggleSelected(e) else onStar(e) }
        // …while its TOUCH target grows to ≥44dp via a TouchDelegate on the row: taps in the
        // halo land on the star (toggle), taps anywhere else on the row still open the entry.
        holder.itemView.post {
            val row = holder.itemView as? ViewGroup ?: return@post
            if (holder.star.width == 0) return@post
            val rect = android.graphics.Rect(0, 0, holder.star.width, holder.star.height)
            row.offsetDescendantRectToMyCoords(holder.star, rect)
            val need = (44 * row.resources.displayMetrics.density).toInt()
            rect.inset(
                -((need - rect.width()) / 2).coerceAtLeast(0),
                -((need - rect.height()) / 2).coerceAtLeast(0)
            )
            row.touchDelegate = android.view.TouchDelegate(rect, holder.star)
        }
        bindArt(holder, e)
    }

    /** Format Miniflux's ISO `published_at` to a short local "MMM d, HH:mm". */
    private fun formatWhen(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            java.time.OffsetDateTime.parse(iso)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(WHEN_FMT)
        }.getOrElse { iso.take(10) }
    }

    private companion object {
        /** Compiled once — `ofPattern` per bind was a pattern parse on every row scrolled in. */
        val WHEN_FMT: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")
        /** Past this many rows on either side, `submit` skips the diff (see there). */
        const val DIFF_MAX = 800
    }

    /** The row's picture and, where the host says so, its ▶. */
    private fun bindArt(holder: Holder, e: FeedEntry) {
        val ctx = holder.itemView.context
        val play = onPlay != null && playable(e)
        if (play) {
            // Selection mode claims the picture too — every gesture on a row toggles it then.
            holder.art.setOnClickListener { if (selecting) toggleSelected(e) else tapArt(e) }
            holder.art.setOnLongClickListener { if (selecting) toggleSelected(e) else onLongPress(e); true }
            holder.play.visibility = View.VISIBLE
            paintBadge(holder, e)
        } else {
            holder.art.setOnClickListener(null)
            holder.art.setOnLongClickListener(null)
            holder.art.contentDescription = null
            holder.play.visibility = View.GONE
        }
        // setOnClickListener(null) leaves the frame clickable — and a clickable frame would swallow
        // the row's own tap (open) over the picture of every non-Listen row.
        holder.art.isClickable = play
        holder.art.isLongClickable = play
        if ((holder.image.background != null) != play)
            holder.image.background = if (play) holder.image.context.getDrawable(R.drawable.listen_art_frame) else null

        val art = artOf(ctx, e)
        // A listen row with no art of its own and no known cover asks for the cover (the feed's
        // head, in the background) and is repainted in place when it lands — see
        // [repaintPendingCovers]; a notes picture, if any, stands in meanwhile.
        holder.waitsCover = art.waits
        if (art.waits) com.toolsboox.plugin.feeds.nw.ShowCovers.ensure(ctx, e.feedUrl, showOf(e))
        loadInto(holder.image, art.keys, keepSlot = play)
    }

    /** Load the first of [keys] that loads, off-thread, tagging the view so recycled rows don't
     *  cross-wire. Memory, then this device's own disk copy, then the network — so a row seen
     *  once keeps its picture offline, and paints once rather than blank-then-picture. A picture
     *  that fails (a 404, not an image) hands over to the next (review 10-12: a dead address used
     *  to leave an empty frame forever); one known dead is skipped without asking. With nothing
     *  left a playable row keeps its framed slot (the ▶'s home); any other gives the width back. */
    private fun loadInto(view: ImageView, keys: List<String>, keepSlot: Boolean) {
        val usable = keys.filterNot { FeedThumbCache.isDead(it) }
        val key = usable.firstOrNull()
        view.tag = key
        val cached = key?.let { FeedThumbCache.get(it) }
        if (cached != null) { view.setImageBitmap(cached); view.visibility = View.VISIBLE; return }
        view.setImageDrawable(null)
        view.visibility = if (keepSlot) View.VISIBLE else View.GONE
        if (key == null) return
        FeedThumbCache.load(view.context, key, diskFirst = true) { bmp ->
            if (view.tag != key) return@load
            if (bmp != null) { view.setImageBitmap(bmp); view.visibility = View.VISIBLE }
            else if (usable.size > 1) loadInto(view, usable.drop(1), keepSlot)
        }
    }
}

/** Session read-tracker: ids of entries opened this session, so the list greys them immediately
 *  without waiting for a re-fetch. The server (Miniflux) is marked read on open too, for durability. */
object FeedReadState {
    private val read = java.util.Collections.synchronizedSet(HashSet<Long>())
    fun isRead(id: Long) = read.contains(id)
    fun mark(id: Long) { read.add(id) }
    /** Undo a local mark — the "Mark all as read" snackbar's Undo has to reach this side too,
     *  or the rows stay greyed after the server has already been put back to unread. */
    fun unmark(id: Long) { read.remove(id) }
}

/** Tiny async image loader for feed thumbnails — memory-cached, off-thread, no extra deps.
 *  Visible beyond the adapter so "grams for stars" can reuse the row's already-loaded
 *  thumbnail for the intake card face instead of re-downloading it ([LruCache] is thread-safe).
 *
 *  Every thumbnail the screen loads also SPILLS to disk (feed-cache/thumb-<hash>.jpg, named by
 *  [com.toolsboox.plugin.feeds.nw.FeedCache.thumbFile]) — that file is the ONLY image source the
 *  home-screen Feed List widget has, because a widget process must never fetch. The spill is why
 *  the widget's pictures track exactly what the person has already seen: the screen shows a row,
 *  the row's thumbnail lands on disk, the widget finds it there. */
object FeedThumbCache {
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** The widget rows are ~40dp; the reader rows top out at ~96dp. 256px covers every consumer
     *  at retina-ish density while keeping each spilled JPEG a few KB. */
    private const val DISK_MAX_SIDE = 256

    fun get(url: String): Bitmap? = cache.get(url)

    /** Row-sized decode target (px, longest side): a row tops out at 116dp. */
    const val ROW_SIDE = 300
    /** The Now Playing modal's picture — 28% of the screen's height. */
    const val STAGE_SIDE = 720

    fun load(context: android.content.Context, url: String, onDone: (Bitmap?) -> Unit) =
        load(context, url, diskFirst = false, resolve = null, onDone = onDone)

    /**
     * Load [url] off-thread: the network, spilled to disk; or with [diskFirst] (the list rows,
     * 10-12) this device's own spilled copy FIRST — rows seen once keep their picture with no
     * network, and a cover shared by forty rows is fetched once. When the network fails, the
     * disk copy answers either way. [resolve] maps the key to what to fetch at fetch time; with
     * none, an Audiobookshelf picture (a remembered `/api/` path, or a full unsigned address on
     * the configured server) gains its token there — the token is never a key or a file name.
     *
     * One fetch per picture at a time (review 10-12): when a show's cover lands, every row of
     * that show asks for it at once, and they all wait on the one request. An address that
     * failed is not asked again on every bind ([isDead]).
     */
    fun load(
        context: android.content.Context, url: String, diskFirst: Boolean,
        resolve: ((String) -> String)? = null, side: Int = ROW_SIDE, onDone: (Bitmap?) -> Unit
    ) {
        val app = context.applicationContext
        val flight = "$side|$url"
        synchronized(inflight) {
            inflight[flight]?.let { it += onDone; return }
            inflight[flight] = mutableListOf(onDone)
        }
        pool.execute {
            var bmp: Bitmap? = if (diskFirst) diskRead(app, url) else null
            if (bmp == null && !isDead(url)) {
                bmp = try {
                    val target = resolve?.invoke(url)
                        ?: if (url.startsWith("/api/") || url.contains("/api/items/"))
                            com.toolsboox.plugin.reader.ui.Audiobookshelf.authorizePicture(app, url) else url
                    fetch(target, side).also { failedUntil.remove(url) }
                } catch (d: DeadPicture) {
                    Timber.d("thumb dead: %s (%s)", url, d.message)
                    failedUntil[url] = Long.MAX_VALUE; null
                } catch (t: Throwable) {
                    Timber.d(t, "thumb load failed: %s", url)
                    failedUntil[url] = System.currentTimeMillis() + RETRY_FAILED_MS; null
                }
                if (bmp != null) {
                    // Guarded: a failed spill costs the widget a picture, never the row its bitmap.
                    runCatching { spillToDisk(app, url, bmp) }
                        .onFailure { Timber.d(it, "thumb spill failed: %s", url) }
                }
            }
            if (bmp == null && !diskFirst) bmp = diskRead(app, url)
            // The bigger of two copies stays (the player's stage-size decode outranks a row's).
            if (bmp != null && (cache.get(url)?.let { maxOf(it.width, it.height) < maxOf(bmp.width, bmp.height) } != false))
                cache.put(url, bmp)
            val done = bmp
            main.post {
                val waiting = synchronized(inflight) { inflight.remove(flight) }.orEmpty()
                waiting.forEach { cb -> runCatching { cb(done) }.onFailure { Timber.w(it, "thumb callback") } }
            }
        }
    }

    /** Requests in flight, by side and address → everyone waiting on that one fetch. */
    private val inflight = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** Addresses that failed, and until when they are not asked again: a 404/403 or something that
     *  is not a picture — for the life of the process; a network failure — for [RETRY_FAILED_MS]
     *  (offline tonight is not "this picture is gone"). A row then moves straight to its next
     *  picture instead of fetching the dead one on every bind and scroll. */
    private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RETRY_FAILED_MS = 10L * 60 * 1000

    /** Has [url] failed recently enough that a row should not ask for it again yet? */
    fun isDead(url: String): Boolean {
        val until = failedUntil[url] ?: return false
        return until > System.currentTimeMillis()
    }

    /** A fetch that will not come right by asking again (a 4xx, not a picture, too big). */
    private class DeadPicture(why: String) : Exception(why)

    /** Fetch with timeouts and a size cap, decoded near [side] and then scaled DOWN to it — a
     *  3000px podcast cover decoded at the old fixed ½ was a 9MB bitmap, bigger than the whole
     *  memory cache; the power-of-two sample alone still left rows anywhere up to 2× [side]
     *  (≈1.4MB each, four to ten rows in the 6MB cache), and a 1400px cover asked for at the
     *  player's [STAGE_SIDE] decoded whole at 7.8MB and emptied the cache on its way in. */
    private fun fetch(url: String, side: Int, hops: Int = 0): Bitmap? {
        val conn = URL(url).openConnection().apply { connectTimeout = 10_000; readTimeout = 15_000 }
        (conn as? java.net.HttpURLConnection)?.responseCode?.let { code ->
            // URLConnection never follows http↔https on its own — and a podcast's http:// art
            // that moved to https:// must not be judged "not a picture" for it.
            if (code in 300..399) {
                val to = conn.getHeaderField("Location")
                (conn as java.net.HttpURLConnection).disconnect()
                if (to.isNullOrBlank() || hops >= 3) throw DeadPicture("HTTP $code")
                return fetch(URL(URL(url), to).toString(), side, hops + 1)
            }
            if (code in 400..499 && code != 408 && code != 429) throw DeadPicture("HTTP $code")
            if (code >= 500) throw java.io.IOException("HTTP $code")
        }
        val bytes = conn.getInputStream().use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_FETCH_BYTES) throw DeadPicture("over ${MAX_FETCH_BYTES / (1024 * 1024)}MB")
            }
            out.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) throw DeadPicture("not a picture")
        var sample = 1
        while (longest / (sample * 2) >= side) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw DeadPicture("undecodable")
        val l = maxOf(decoded.width, decoded.height)
        if (l <= side) return decoded
        val k = side.toFloat() / l
        val scaled = Bitmap.createScaledBitmap(decoded,
            (decoded.width * k).toInt().coerceAtLeast(1), (decoded.height * k).toInt().coerceAtLeast(1), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private const val MAX_FETCH_BYTES = 12 * 1024 * 1024

    /** The spilled copy, for this process's own rows. A read keeps a still-shown picture from
     *  aging out under the cache janitor (it prunes by modified time): a show's cover is read
     *  every day and written once. */
    private fun diskRead(context: android.content.Context, url: String): Bitmap? = runCatching {
        val f = com.toolsboox.plugin.feeds.nw.FeedCache.thumbFile(context, url)
        if (!f.exists()) return@runCatching null
        val now = System.currentTimeMillis()
        if (now - f.lastModified() > TOUCH_AFTER_MS) f.setLastModified(now)
        BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    private const val TOUCH_AFTER_MS = 3L * 24 * 3600 * 1000

    /** A small JPEG beside the cached lists, written once per URL (immutable enough — a feed
     *  image that changes under its URL gets the old face until the janitor retires it). Scaled
     *  to [DISK_MAX_SIDE] before compressing so the on-disk copy stays a few KB whatever the
     *  source served. Temp-then-rename, the house rule — a half-written JPEG under a name that
     *  vouches for it would just decode as garbage on the home screen. */
    private fun spillToDisk(context: android.content.Context, url: String, bmp: Bitmap) {
        val target = com.toolsboox.plugin.feeds.nw.FeedCache.thumbFile(context, url)
        if (target.exists()) return
        val scale = DISK_MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1), true
        ) else bmp
        val temp = java.io.File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (small !== bmp) small.recycle()
        if (!temp.renameTo(target)) temp.delete()
    }

    /** Disk-only read for the widget process: the spilled JPEG or nothing. NEVER fetches —
     *  that is the whole contract of the feed widgets ("read what's cached, never go and get
     *  it"), and the spilled files are already sized for a widget row, so a plain decode is
     *  bounded by construction. */
    fun diskGet(context: android.content.Context, url: String): Bitmap? = runCatching {
        val f = com.toolsboox.plugin.feeds.nw.FeedCache.thumbFile(context, url)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }.getOrNull()
}
