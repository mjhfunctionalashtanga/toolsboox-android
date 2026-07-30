package com.toolsboox.plugin.calendar.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.ot.InkPadView
import com.toolsboox.ot.ModalScale
import com.toolsboox.plugin.calendar.nw.BlueskyReply
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Bluesky — the replies to his syndicated posts that are waiting on an answer, and the pad he
 * answers them on. The Boox counterpart to the iPad's Bluesky inbox, sharing the same server
 * queue (`mjh/v1/bsky-inbox` + `/bsky-reply`), so answering on one device takes the item out of
 * the other's list rather than leaving both showing it and inviting the same reply twice.
 *
 * The one rule this screen exists to enforce, and the reason the composer is shaped the way it is:
 * **handwriting is recognised into an editable field he must see, and can correct, before anything
 * is sent.** OCR of real handwriting is good, not perfect, and the destination here is a public
 * timeline under his own name — a transcription error is not a note to himself with a typo in it,
 * it is a sentence he did not write, published as though he had. So "Recognise" fills the text
 * box; only the text box is ever sent; and re-running recognition APPENDS to what is in the box
 * rather than replacing it, because the second run's job is to add the next paragraph, not to
 * throw away the corrections just made to the first.
 *
 * Structurally this is [MessagesFragment]'s threaded list with [CorrespondenceFragment]'s reply
 * pad — the ink pad, the pen bar, the flat high-contrast rows are all the shared ones.
 */
@AndroidEntryPoint
class BlueskyFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_bluesky

    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView

    private var items: List<BlueskyReply.InboxItem> = emptyList()

    // This screen says what it is doing in the content area itself ("Loading replies…"), which on
    // e-ink beats a spinner that would only smear; the base hooks stay empty as they do on the
    // other list surfaces.
    override fun showLoading() {}
    override fun hideLoading() {}

    private val density get() = resources.displayMetrics.density
    private fun px(v: Int): Int = (v * density).toInt()
    private fun toast(msg: String) { if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        content = view.findViewById(R.id.bluesky_content)
        titleView = view.findViewById(R.id.bluesky_title)
        view.findViewById<Button>(R.id.bluesky_close).setOnClickListener {
            NavHostFragment.findNavController(this).popBackStack()
        }
        view.findViewById<Button>(R.id.bluesky_refresh).setOnClickListener { load() }
        view.findViewById<Button>(R.id.bluesky_settings).setOnClickListener { showSettings() }
        load()
    }

    /* ---------------------------------------------------------------
     * The inbox
     * ------------------------------------------------------------- */

    private fun load() {
        val ctx = context ?: return
        if (!BlueskyReply.config(ctx).ready) {
            renderMessage(
                "The Bluesky bridge isn't configured yet.\n\n" +
                    "Tap ⚙ and paste the shared POSSE secret (the same X-MF-Secret the syndication " +
                    "queue uses). It is kept in the encrypted store on this device — it is never " +
                    "compiled into the app."
            )
            // Dropped rather than held: a target that survived an unconfigured visit would pop a
            // composer for a post he tapped days ago the next time this screen loaded properly.
            BlueskyTarget.pendingUri = null
            return
        }
        renderMessage("Loading replies…")
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { BlueskyReply.inbox(ctx, 25) }
            items = list
            if (isAdded) { renderList(); consumePendingTarget() }
        }
    }

    /**
     * Open the composer for a post the Feed Ledger's timeline sent us, if it is one we can answer.
     *
     * ANSWERABLE IS NARROWER THAN VISIBLE, and pretending otherwise would be the dishonest option.
     * The site's queue carries replies to HIS syndicated posts — a comment threaded under something
     * of his, addressed by its on-site comment id — and there is no route that posts to an arbitrary
     * skeet from a device that (by design) holds no app password. So a timeline post that is in the
     * queue opens the pad; one that is not says so and offers the place it can be answered, which is
     * Bluesky itself. The alternative — a composer whose Send could only ever 404 — is the failure
     * this whole screen's "review before you send" discipline exists to avoid.
     */
    private fun consumePendingTarget() {
        val uri = BlueskyTarget.pendingUri ?: return
        BlueskyTarget.pendingUri = null
        val match = items.firstOrNull { it.bskyUri.isNotBlank() && it.bskyUri == uri }
        if (match != null) { showComposer(match); return }
        showModal(
            androidx.appcompat.app.AlertDialog.Builder(ModalScale.wrap(requireContext()))
                .setTitle("Not in the reply queue")
                .setMessage(
                    "Ledger can answer replies to your own syndicated posts — those come home " +
                        "through the site's queue, which is what carries an answer back out.\n\n" +
                        "This post isn't one of those, so there is nothing here to reply through. " +
                        "Open it on Bluesky to answer it there."
                )
                .setPositiveButton("Open on Bluesky") { _, _ ->
                    // The AT URI is not a web address; bsky.app's own profile/post form is, and it
                    // is built from the two path components at the end of the URI.
                    val parts = uri.removePrefix("at://").split("/")
                    if (parts.size >= 3) openUrl("https://bsky.app/profile/${parts[0]}/post/${parts.last()}")
                    else toast("No web address for that post")
                }
                .setNegativeButton("Not now", null)
                .create()
        )
    }

    private fun renderMessage(text: String) {
        val ctx = context ?: return
        content.removeAllViews()
        content.addView(TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF444444.toInt())
            setPadding(px(22), px(24), px(22), px(24))
        })
    }

    private fun renderList() {
        val ctx = requireContext()
        titleView.text = if (items.isEmpty()) "Bluesky" else "Bluesky · ${items.size} waiting"
        if (items.isEmpty()) {
            renderMessage("Nothing waiting.\n\nReplies to your syndicated posts land here once the mirror has pulled them home. ↻ to check again.")
            return
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(10), px(14), px(24))
        }
        for (item in items) col.addView(row(item))
        content.removeAllViews()
        content.addView(ScrollView(ctx).apply { addView(col) })
        com.toolsboox.ot.ReadingSize.apply(col)
    }

    /** One waiting reply. Whole row is the tap target — a separate "Reply" button would be a
     *  second small thing to hit with a stylus for no gain, since reading it and answering it are
     *  the same act here. */
    private fun row(item: BlueskyReply.InboxItem): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(10), px(12), px(12))
            // Flat and framed rather than a card with a shadow: elevation renders as a grey
            // smear on e-ink, so every container in this app draws its own hairline instead.
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(10) }
        }
        box.addView(TextView(ctx).apply {
            text = buildString {
                append(item.author.ifBlank { item.handle })
                if (item.handle.isNotBlank() && item.handle != item.author) append("  @${item.handle}")
            }
            textSize = 14f; setTextColor(0xFF000000.toInt()); setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(ctx).apply {
            text = item.text
            textSize = 16f; setTextColor(0xFF000000.toInt()); setPadding(0, px(4), 0, px(6))
        })
        box.addView(TextView(ctx).apply {
            text = "on “${item.postTitle}”   ·   ${item.createdAt}"
            textSize = 12f; setTextColor(0xFF777777.toInt())
        })
        box.addView(TextView(ctx).apply {
            text = "✍  Answer this…"
            textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(8), 0, 0)
        })
        box.addView(View(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
                .apply { topMargin = px(10) }
        })
        box.setOnClickListener { showComposer(item) }
        box.setOnLongClickListener {
            // The long press is "go look at it where it lives" — the thread on Bluesky if the
            // syndicated post has a URL, otherwise the entry on his own site.
            val url = item.postBskyUrl.ifBlank { item.canonicalUrl }
            if (url.isNotBlank()) openUrl(url) else toast("No link for this one")
            true
        }
        return box
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure { toast("Couldn't open that link") }
    }

    /* ---------------------------------------------------------------
     * The composer — write by hand, recognise, REVIEW, send
     * ------------------------------------------------------------- */

    @SuppressLint("ClickableViewAccessibility")
    private fun showComposer(item: BlueskyReply.InboxItem) {
        val ctx = requireContext()
        val ink = InkPadView(ctx)
        var attachInk = false
        // Every pad-full recognised SO FAR, stacked. Recognition clears the pad (so the next
        // pad-full isn't read twice into the field), which would otherwise mean that ticking
        // "attach the handwriting" after two recognitions attached nothing at all — the ink he
        // wrote would have been thrown away by the very act of reading it.
        var written: Bitmap? = null

        // What they said, kept above the pad so the answer is written against it rather than
        // from memory. Scrollable because a Bluesky reply can be 300 graphemes of its own.
        val theirs = TextView(ctx).apply {
            text = "@${item.handle.ifBlank { item.author }}:  ${item.text}"
            textSize = 13f; setTextColor(0xFF333333.toInt())
            setPadding(px(8), px(4), px(8), px(6))
            maxHeight = px(110)
            movementMethod = android.text.method.ScrollingMovementMethod()
        }

        val penBar = InkPadView.penBar(ctx, ink)
        val inkFrame = FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt()); setPadding(px(2), px(2), px(2), px(2))
            addView(ink, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(280)))
        }

        // THE FIELD. It is not optional, not collapsed, and not below the fold: it is the thing
        // that gets sent, so it is the thing on screen. Multi-line and freely editable — fixing a
        // misread word must be a tap and a keystroke, not a re-write of the whole reply.
        val review = EditText(ctx).apply {
            hint = "Your reply — recognise the handwriting above, then read it and fix it here"
            setSingleLine(false); minLines = 3; gravity = Gravity.TOP
            setPadding(px(10), px(10), px(10), px(10))
            textSize = 15f
        }

        val counter = TextView(ctx).apply {
            textSize = 13f; setPadding(px(2), px(4), px(2), px(2))
        }
        // Nullable rather than lateinit: the counter is wired to the field's text watcher, and a
        // lateinit local that the watcher could reach before the button exists is a crash waiting
        // for the one input method that fires a change during layout.
        var sendBtn: TextView? = null

        /** Live grapheme counter + the Send gate. Bluesky counts graphemes, so the composer does
         *  too — see [BlueskyReply.graphemes] for why `length` would lie. Over the ceiling the
         *  count goes bold-black (the only "alarm" e-ink renders honestly) and Send goes dead
         *  rather than staying tappable and failing at the server after the ink is gone. */
        fun refreshCount() {
            val n = BlueskyReply.graphemes(review.text.toString().trim())
            val over = n > BlueskyReply.MAX_GRAPHEMES
            counter.text = "$n / ${BlueskyReply.MAX_GRAPHEMES}" + if (over) "   ✕ too long for Bluesky" else ""
            counter.setTextColor(if (over) 0xFF000000.toInt() else 0xFF777777.toInt())
            counter.setTypeface(null, if (over) Typeface.BOLD else Typeface.NORMAL)
            val sendable = !over && n > 0
            sendBtn?.isEnabled = sendable
            sendBtn?.setTextColor(if (sendable) 0xFF2F6F96.toInt() else 0xFFAAAAAA.toInt())
        }
        review.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshCount()
        })

        val recogniseBtn = TextView(ctx).apply {
            text = "✍  Recognise handwriting"
            textSize = 15f; setTextColor(0xFF2F6F96.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(px(2), px(8), px(16), px(6))
        }
        val attachToggle = TextView(ctx).apply {
            textSize = 13f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(2), px(6), px(2), px(2))
            fun label() = (if (attachInk) "☑" else "☐") + "  Attach the handwriting as the reply's image"
            text = label()
            setOnClickListener { attachInk = !attachInk; text = label() }
        }

        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 0, 0, px(4))
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(8), px(12), 0)
            addView(actionRow)
            addView(theirs)
            addView(penBar)
            addView(inkFrame)
            addView(recogniseBtn)
            addView(review, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(counter)
            addView(attachToggle)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle("Reply · @${item.handle.ifBlank { item.author }}".take(40))
            .setView(ScrollView(ctx).apply { addView(box) })
            .create()

        fun actionBtn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(px(16), px(4), px(16), px(4))
            setOnClickListener { if (isEnabled) onTap() }
        }
        // "Clear ink" wipes the pad AND the accumulated pages: it means "that isn't what I want to
        // send", and leaving the earlier pad-fulls behind to be attached would contradict it.
        actionRow.addView(actionBtn("Clear ink") {
            ink.clear(); written?.recycle(); written = null
        })
        actionRow.addView(actionBtn("Cancel") { dialog.dismiss() })
        sendBtn = actionBtn("Send") {
            // A COPY of the accumulated pages, because stacking consumes its top argument and the
            // send can fail — on a dropped connection he must still be able to tap Send again with
            // the handwriting intact rather than find it recycled out from under him.
            val attachment = if (!attachInk) null
            else stackVertically(written?.copy(Bitmap.Config.ARGB_8888, false), ink.render())
            send(item, review.text.toString(), attachment, dialog)
        }
        actionRow.addView(sendBtn)
        refreshCount()

        recogniseBtn.setOnClickListener {
            recognise(ink, review) { page -> written = stackVertically(written, page) }
        }
        showModal(dialog)
    }

    /** Two pad-fulls, one above the other, on white — the same stacking the Correspondence reply
     *  pad does when it combines an attachment with your ink. Either side may be null. */
    private fun stackVertically(top: Bitmap?, bottom: Bitmap?): Bitmap? {
        if (top == null) return bottom
        if (bottom == null) return top
        val w = maxOf(top.width, bottom.width)
        val gap = px(8)
        val out = Bitmap.createBitmap(w, top.height + gap + bottom.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(0xFFFFFFFF.toInt())
        c.drawBitmap(top, (w - top.width) / 2f, 0f, null)
        c.drawBitmap(bottom, (w - bottom.width) / 2f, (top.height + gap).toFloat(), null)
        top.recycle()
        return out
    }

    /**
     * Run the ink through the vision OCR and APPEND the result to whatever is already in the
     * review field.
     *
     * Appending, not replacing, is the whole contract: the natural way to write 300 graphemes on
     * a 280dp pad is a pad-full at a time — recognise, clear the pad, write the next bit,
     * recognise again — and a second run that clobbered the field would silently delete both the
     * first half of the reply and every correction made to it. So the pad is a source of new text
     * and the field is the only record.
     *
     * [onPageRead] hands the composer the bitmap that was just read, before the pad is wiped, so
     * the "attach the handwriting" option still has all of the handwriting to attach.
     */
    private fun recognise(ink: InkPadView, review: EditText, onPageRead: (Bitmap) -> Unit) {
        val ctx = requireContext()
        if (ink.isBlank()) { toast("Nothing written on the pad yet"); return }
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(ctx)
        if (creds == null) {
            toast("Add your AI key in Ask my Ledger settings"); return
        }
        // OCR may run on a cheaper/faster model than chat — the same override the day page's
        // lasso recognition honours, so one setting governs every recognition in the app.
        val model = com.toolsboox.ui.plugin.OcrModel.override(ctx, creds.first) ?: creds.third
        val bmp = ink.render() ?: run { toast("Nothing written on the pad yet"); return }
        toast("Reading your handwriting…")
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, model)
            }
            if (!isAdded) { bmp.recycle(); return@launch }
            if (text.isNullOrBlank()) {
                // A failed read leaves the pad alone: whatever is on it is the only copy of what
                // he wrote, and clearing it would make "try again" mean "write it again".
                bmp.recycle()
                toast("Couldn't read that — try writing it larger")
                return@launch
            }
            val existing = review.text.toString()
            val joined = if (existing.isBlank()) text.trim() else existing.trimEnd() + " " + text.trim()
            review.setText(joined)
            review.setSelection(joined.length)
            // The composer keeps the page (it may be attached to the reply); this coroutine must
            // not recycle it afterwards, which is why ownership passes out here rather than being
            // borrowed. Clearing the pad after a successful read is what makes "write a bit,
            // recognise, write the next bit" work without reading the same sentence in twice.
            onPageRead(bmp)
            ink.clear()
        }
    }

    private fun send(
        item: BlueskyReply.InboxItem, text: String, inkBitmap: Bitmap?,
        dialog: androidx.appcompat.app.AlertDialog
    ) {
        val ctx = requireContext()
        val reviewed = text.trim()
        if (reviewed.isBlank()) { toast("Nothing to send"); return }
        val png = inkBitmap?.let {
            val baos = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.PNG, 100, baos)
            it.recycle()
            baos.toByteArray()
        }
        toast("Sending…")
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { BlueskyReply.reply(ctx, item.commentId, reviewed, png) }
            if (!isAdded) return@launch
            toast(res.message)
            if (res.ok) {
                dialog.dismiss()
                // Drop it from the list immediately rather than waiting for a refetch: the site
                // has already marked the parent answered, so a re-fetch would agree — but on a
                // slow connection the row would sit there looking unanswered in the meantime.
                items = items.filterNot { it.commentId == item.commentId }
                renderList()
            }
        }
    }

    /* ---------------------------------------------------------------
     * Settings — base URL + the shared POSSE secret
     * ------------------------------------------------------------- */

    /**
     * Self-contained so the screen carries its own configuration rather than needing a row added
     * to the (already long) calendar-settings scroll — the same shape [com.toolsboox.ui.plugin.OcrModel]
     * uses for its picker.
     */
    private fun showSettings() {
        val ctx = requireContext()
        val cfg = BlueskyReply.config(ctx)
        val baseIn = EditText(ctx).apply {
            hint = BlueskyReply.DEFAULT_BASE; setSingleLine(); setText(cfg.base)
        }
        val secretIn = EditText(ctx).apply {
            hint = "X-MF-Secret"; setSingleLine(); setText(cfg.secret)
        }
        val note = TextView(ctx).apply {
            text = "The shared POSSE secret. Stored encrypted on this device only — it is never " +
                "compiled into the app, because an APK is a zip anyone can open."
            textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, px(8), 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), 0)
            addView(baseIn); addView(secretIn); addView(note)
        }
        showModal(
            androidx.appcompat.app.AlertDialog.Builder(ModalScale.wrap(ctx))
                .setTitle("Bluesky bridge")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    BlueskyReply.saveConfig(
                        ctx,
                        BlueskyReply.Config(
                            baseIn.text.toString().trim().ifBlank { BlueskyReply.DEFAULT_BASE },
                            secretIn.text.toString().trim()
                        )
                    )
                    load()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        )
    }
}

/**
 * A post the Feed Ledger's Bluesky timeline wants answered, handed over without nav args.
 *
 * The same one-shot idiom as [com.toolsboox.plugin.feeds.ui.FeedSelection], and for the same
 * reason: the two screens live in different plugins and a typed argument between them would mean a
 * nav-graph argument, a bundle key and a parcelable for one string. Consumed exactly once, by
 * [BlueskyFragment] as soon as it knows what is in the reply queue.
 */
object BlueskyTarget {
    /** The AT URI of the post to open the composer for, or null. */
    var pendingUri: String? = null
}
