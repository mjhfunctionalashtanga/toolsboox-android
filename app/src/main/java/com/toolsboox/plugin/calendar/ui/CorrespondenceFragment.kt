package com.toolsboox.plugin.calendar.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentCorrespondenceBinding
import com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge
import com.toolsboox.plugin.calendar.nw.LedgerCorrespondence
import com.toolsboox.plugin.calendar.nw.LedgerPost
import com.toolsboox.plugin.calendar.nw.LedgerReply
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import com.toolsboox.ot.InkPadView

/**
 * The Correspondence page — what passed between you and other people. Replies to your community
 * posts and your Ledgr board cards, grouped BY EXCHANGE (thread), newest first. A community
 * thread takes a handwritten reply: "✍ Reply in ink" opens a white card you write on with the
 * stylus; Done posts the ink as your comment via the bridge. Mirrors iOS CorrespondenceView.
 * Records exchanges; shows no unread counts, ever.
 */
@AndroidEntryPoint
class CorrespondenceFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_correspondence
    private lateinit var binding: FragmentCorrespondenceBinding

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    private fun documentsRoot(): java.io.File =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!
        else
            java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    /** Stack two bitmaps vertically (either may be null) — the attached item above your ink. */
    private fun stackVertically(top: Bitmap?, bottom: Bitmap?): Bitmap? {
        if (top == null) return bottom
        if (bottom == null) return top
        val w = maxOf(top.width, bottom.width)
        val gap = (8 * resources.displayMetrics.density).toInt()
        val h = top.height + gap + bottom.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        c.drawBitmap(top, ((w - top.width) / 2f), 0f, null)
        c.drawBitmap(bottom, ((w - bottom.width) / 2f), (top.height + gap).toFloat(), null)
        return out
    }

    /** "replies" = the exchange inbox · "community" = a space's posts you can reply to. */
    private var mode = "replies"
    private var spaceId = 25L                 // MichaelFilter — the first space
    private var spaceTitle = "MichaelFilter"

    // Thread reader state — so a reply posted from inside it can reopen it fresh.
    private var threadReaderOpen = false
    private var threadReaderDialog: androidx.appcompat.app.AlertDialog? = null

    private fun prefs() = requireContext().getSharedPreferences("ledger_correspondence", Context.MODE_PRIVATE)

    /** Decode HTML entities (&hellip; &#039; &amp; …) so excerpts read cleanly. */
    private fun deHtml(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()

    /** Rendered HTML with formatting KEPT (bold/italic/links/paragraphs) — for post bodies.
     *  (img tags render as nothing here; the featured image is shown separately.) */
    private fun richHtml(s: String): CharSequence =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).trim()

    /** Add an inline image view to [container] and load [url] into it off-thread. */
    private fun addImage(container: LinearLayout, url: String, heightDp: Int = 160) {
        val ctx = container.context
        val dp = resources.displayMetrics.density
        val img = android.widget.ImageView(ctx).apply {
            adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
            scaleType = android.widget.ImageView.ScaleType.FIT_START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt(); bottomMargin = (4 * dp).toInt() }
            maxHeight = (heightDp * dp).toInt()
        }
        container.addView(com.toolsboox.ot.InkMount.wrapInColumn(ctx, img))
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { LedgerCorrespondence.loadImage(ctx, url) }
            if (bmp != null && isAdded) img.setImageBitmap(bmp)
        }
    }

    /** Locally-tracked "you replied to this thread" set — a ✓ marker without a server round-trip. */
    private fun markReplied(source: String, id: Long) {
        val set = prefs().getStringSet("repliedThreads", emptySet())!!.toMutableSet()
        set.add("$source-$id"); prefs().edit().putStringSet("repliedThreads", set).apply()
    }
    private fun hasReplied(source: String, id: Long) =
        prefs().getStringSet("repliedThreads", emptySet())!!.contains("$source-$id")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentCorrespondenceBinding.bind(view)
        spaceId = prefs().getLong("space_id", 25L)
        spaceTitle = prefs().getString("space_title", "MichaelFilter") ?: "MichaelFilter"
        binding.correspondenceClose.setOnClickListener { NavHostFragment.findNavController(this).popBackStack() }
        binding.correspondenceRefresh.setOnClickListener { load() }
        load()
    }

    private fun load() {
        val ctx = context ?: return
        lifecycleScope.launch {
            if (mode == "community") {
                val posts = withContext(Dispatchers.IO) { LedgerCorrespondence.fetchSpaceFeed(ctx, spaceId) }
                renderCommunity(posts)
            } else {
                val replies = withContext(Dispatchers.IO) { LedgerCorrespondence.fetch(ctx) }
                render(replies)
            }
        }
    }

    /** The Replies | Community tab row + (in community) the current-space chip. Prepended to both views. */
    private fun addTabs(container: LinearLayout) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(px(4), px(4), px(4), px(10))
        }
        fun tab(label: String, key: String) = TextView(ctx).apply {
            text = label; textSize = 16f; isAllCaps = false
            setPadding(px(12), px(6), px(12), px(6))
            val on = mode == key
            setTextColor(if (on) 0xFF000000.toInt() else 0xFF888888.toInt())
            if (on) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { if (mode != key) { mode = key; load() } }
        }
        row.addView(tab("Replies", "replies"))
        row.addView(tab("Community", "community"))
        container.addView(row)
        if (mode == "community") {
            container.addView(TextView(ctx).apply {
                text = "❝  $spaceTitle   ▾"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                setPadding(px(6), 0, px(6), px(10))
                setOnClickListener { pickSpace() }
            })
        }
    }

    /** Choose which community space to browse (persists). */
    private fun pickSpace() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val spaces = withContext(Dispatchers.IO) { LedgerCommunityBridge.spaces(ctx) }
            if (spaces.isEmpty()) {
                android.widget.Toast.makeText(ctx, "No spaces (check the community bridge in Settings)", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = spaces.map { it.title }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Community space")
                .setItems(labels) { _, i ->
                    spaceId = spaces[i].id; spaceTitle = spaces[i].title
                    prefs().edit().putLong("space_id", spaceId).putString("space_title", spaceTitle).apply()
                    load()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun renderCommunity(posts: List<LedgerPost>) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val container = binding.correspondenceContainer
        container.removeAllViews()
        addTabs(container)

        if (!com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(ctx).ready) {
            container.addView(TextView(ctx).apply {
                text = "Add your community site + application password in Calendar Settings, then a space's posts appear here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(16), px(8), 0)
            })
            return
        }
        if (posts.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "No posts in $spaceTitle yet."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(16), px(8), 0)
            })
            return
        }
        for (post in posts) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(10), px(8), px(10), px(8))
                setBackgroundColor(0xFFF3F3F3.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, px(8)) }
            }
            card.addView(TextView(ctx).apply {
                text = listOfNotNull(post.author.ifBlank { null }, post.createdAt.take(16).ifBlank { null })
                    .joinToString("   ·   ") + (if (post.commentsCount > 0) "   ·   ${post.commentsCount}💬" else "") +
                    (if (hasReplied("community", post.id)) "   ·   ✓ replied" else "")
                textSize = 12f; setTextColor(0xFF666666.toInt())
            })
            if (post.title.isNotBlank()) card.addView(TextView(ctx).apply {
                text = deHtml(post.title); textSize = 15f; setTextColor(0xFF000000.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            // Body with formatting kept (falls back to the plain excerpt).
            card.addView(TextView(ctx).apply {
                text = if (post.html.isNotBlank()) richHtml(post.html) else deHtml(post.excerpt)
                textSize = 14f; setTextColor(0xFF000000.toInt())
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            })
            // The message's featured / embedded image.
            post.imageUrl?.let { addImage(card, it, heightDp = 200) }
            // Horizontal-scrollable so the extra reply actions never push buttons off-screen.
            val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val actionsScroll = android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false; addView(actions)
            }
            // Like — the FluentCommunity reaction, toggled straight from the Ledger.
            var liked = post.liked
            var likeCount = post.reactionsCount
            actions.addView(TextView(ctx).apply {
                fun label() = (if (liked) "♥" else "♡") + (if (likeCount > 0) "  $likeCount" else "")
                text = label()
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), px(18), 0)
                setOnClickListener {
                    val was = liked; val count0 = likeCount
                    liked = !liked; likeCount = (likeCount + if (liked) 1 else -1).coerceAtLeast(0)
                    text = label()   // optimistic flip
                    lifecycleScope.launch {
                        val res = withContext(Dispatchers.IO) { LedgerCorrespondence.reactPost(ctx, post.id) }
                        if (res == null) { liked = was; likeCount = count0; text = label() }   // revert
                        else { liked = res.first; likeCount = res.second; text = label() }
                    }
                }
            })
            val provDefault = "↩ In reply to ${post.author}" +
                post.excerpt.trim().take(90).let { if (it.isNotBlank()) ": “$it”" else "" }
            val quotedPost = post.author.ifBlank { "Post" } + ":  " + post.excerpt
            actions.addView(TextView(ctx).apply {
                text = "↩  Reply"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), px(18), 0)
                setOnClickListener {
                    showReplyDialog(
                        post.id, post.title.ifBlank { spaceTitle },
                        replyingTo = quotedPost, provenanceDefault = provDefault, provUrl = post.url
                    )
                }
            })
            actions.addView(TextView(ctx).apply {
                text = "📄  Post & replies"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), px(18), 0)
                setOnClickListener { showThread("community", post.id, post.title.ifBlank { spaceTitle }) }
            })
            actions.addView(TextView(ctx).apply {
                text = "▸  Related"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), px(18), 0)
                setOnClickListener { showPostRelated(post) }
            })
            if (post.url.isNotBlank() && post.public) actions.addView(TextView(ctx).apply {
                text = "↗  Open"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), 0, 0)
                setOnClickListener { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(post.url))) }
            })
            card.addView(actionsScroll)
            container.addView(card)
        }
    }

    private fun render(replies: List<LedgerReply>) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val container = binding.correspondenceContainer
        container.removeAllViews()
        addTabs(container)

        if (!com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(ctx).ready) {
            container.addView(TextView(ctx).apply {
                text = "Add your community site + application password in Calendar Settings, and replies to your posts and cards gather here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(24), px(8), 0)
            })
            return
        }
        if (replies.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "No correspondence yet. When someone answers a post or a card of yours, the exchange appears here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(24), px(8), 0)
            })
            return
        }

        // Group by exchange, newest activity first (items arrive newest-first from the bridge).
        val threads = replies.groupBy { "${it.source}-${it.threadId}" }.values
            .sortedByDescending { it.first().createdAt }

        for (thread in threads) {
            val head = thread.first()
            val replied = hasReplied(head.source, head.threadId)
            container.addView(TextView(ctx).apply {
                text = (if (head.source == "boards") "📋  " else "👥  ") +
                    deHtml(head.thread).ifBlank { "Untitled thread" } + (if (replied) "   ✓ replied" else "")
                textSize = 16f; setTextColor(0xFF000000.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(px(4), px(14), px(4), px(6))
                // Tap the thread title → open the original post (and its replies) in-app.
                setOnClickListener { showThread(head.source, head.threadId, head.thread) }
            })
            for (r in thread) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(10), px(8), px(10), px(8))
                    setBackgroundColor(0xFFF3F3F3.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, px(6)) }
                }
                card.addView(TextView(ctx).apply {
                    text = "${r.author}   ·   ${r.createdAt.take(16)}"
                    textSize = 12f; setTextColor(0xFF666666.toInt())
                })
                card.addView(TextView(ctx).apply {
                    // The whole message, not the trimmed excerpt — reading it here is the point.
                    text = deHtml(r.content.ifBlank { r.excerpt }); textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                r.imageUrl?.let { addImage(card, it, heightDp = 180) }
                container.addView(card)
            }
            container.addView(TextView(ctx).apply {
                text = "📄  Open post & replies"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                setPadding(px(10), px(2), px(10), px(4))
                setOnClickListener { showThread(head.source, head.threadId, head.thread) }
            })
            if (head.source == "community" && head.threadUrl.isNotBlank() && head.public) {
                container.addView(TextView(ctx).apply {
                    text = "↗  Open the thread"
                    textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                    setPadding(px(10), px(2), px(10), px(4))
                    setOnClickListener {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(head.threadUrl)))
                    }
                })
            }
            if (head.source == "community") {
                val quoted = head.author + ":  " + head.content.ifBlank { head.excerpt }
                container.addView(TextView(ctx).apply {
                    text = "↩  Reply"; textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                    setPadding(px(10), px(2), px(10), px(10))
                    setOnClickListener { showReplyDialog(head.threadId, head.thread, replyingTo = quoted) }
                })
            }
        }
    }

    /** Read the whole exchange in-app: every comment, yours marked "· you", uploads shown (📎 + image). */
    private fun showThread(source: String, threadId: Long, title: String) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(8), px(18), px(8)) }
        val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
        col.addView(TextView(ctx).apply { text = "Loading…"; setTextColor(0xFF888888.toInt()); setPadding(0, px(12), 0, 0) })
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(deHtml(title).ifBlank { "Thread" })
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()
        threadReaderDialog = dialog
        threadReaderOpen = true
        dialog.setOnDismissListener { if (threadReaderDialog === dialog) { threadReaderOpen = false; threadReaderDialog = null } }
        dialog.show()
        // The reader earns the whole screen width — long messages were cramped in the
        // stock dialog's narrow column.
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lifecycleScope.launch {
            val bundle = withContext(Dispatchers.IO) { LedgerCorrespondence.threadBundle(ctx, source, threadId) }
            if (!isAdded) return@launch
            val items = bundle.comments
            col.removeAllViews()

            // The ORIGINAL post/card first — this is the "bring up the post in-app" the row opens.
            bundle.post?.let { p ->
                col.addView(TextView(ctx).apply {
                    text = p.author + "   ·   " + p.createdAt.take(16)
                    textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, px(2), 0, px(1))
                })
                if (p.title.isNotBlank()) col.addView(TextView(ctx).apply {
                    text = deHtml(p.title); textSize = 17f; setTextColor(0xFF000000.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, px(2), 0, px(2))
                })
                if (p.content.isNotBlank()) col.addView(TextView(ctx).apply {
                    text = deHtml(p.content); textSize = 15f; setTextColor(0xFF000000.toInt())
                    setTextIsSelectable(true); setPadding(0, px(2), 0, px(4))
                })
                p.imageUrl?.let { url ->
                    val img = android.widget.ImageView(ctx).apply {
                        adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(px(240), LinearLayout.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = px(4) }
                    }
                    col.addView(img)
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.IO) { LedgerCorrespondence.loadImage(ctx, url) }
                        if (bmp != null && isAdded) img.setImageBitmap(bmp)
                    }
                }
                // Divider before the replies.
                col.addView(View(ctx).apply {
                    setBackgroundColor(0xFFDDDDDD.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
                        .apply { topMargin = px(10); bottomMargin = px(2) }
                })
                col.addView(TextView(ctx).apply {
                    text = if (items.isEmpty()) "No replies yet." else "${items.size} " + (if (items.size == 1) "reply" else "replies")
                    textSize = 11f; setTextColor(0xFF888888.toInt()); setPadding(0, px(4), 0, px(2))
                })
            }
            if (bundle.post == null && items.isEmpty()) {
                col.addView(TextView(ctx).apply { text = "No replies yet."; setTextColor(0xFF888888.toInt()) })
            }

            // Render one comment (indented when it's a nested reply), with its own actions.
            /**
             * A voice/video reply, mounted the way the web side mounts it: a bordered box with a
             * play mark on it. Pressing it streams from the same URL the browser would use, so
             * nothing is downloaded by merely scrolling a thread.
             */
            fun soundbox(c: com.toolsboox.plugin.calendar.nw.ThreadComment, leftPad: Int): View {
                val isVideo = c.mediaKind == "video"
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(10), px(8), px(14), px(8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFFFFFFF.toInt()); setStroke(px(2), 0xFF111111.toInt()); cornerRadius = px(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = px(6); bottomMargin = px(2); leftMargin = leftPad }
                }
                box.addView(TextView(ctx).apply {
                    text = "▶"; textSize = 16f; setTextColor(0xFF000000.toInt())
                    setPadding(0, 0, px(10), 0)
                })
                box.addView(TextView(ctx).apply {
                    text = if (isVideo) "Video gram" else "Voice gram"
                    textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                box.setOnClickListener {
                    if (c.mediaUrl.isBlank()) {
                        android.widget.Toast.makeText(ctx, "No media on this reply", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        com.toolsboox.plugin.calendar.ot.AvPlayback.play(
                            ctx, c.mediaKind, null, c.mediaUrl,
                            (if (isVideo) "🎥 " else "🎤 ") + c.author, 0
                        )
                    }
                }
                return box
            }

            fun renderComment(c: com.toolsboox.plugin.calendar.nw.ThreadComment, indent: Boolean) {
                val leftPad = if (indent) px(22) else 0
                col.addView(TextView(ctx).apply {
                    val clip = when (c.mediaKind) { "audio" -> "   🎤"; "video" -> "   🎥"; else -> if (c.hasImage) "   📎" else "" }
                    text = (if (indent) "↳ " else "") + c.author + (if (c.mine) "  · you" else "") +
                        "   ·   " + c.createdAt.take(16) + clip
                    textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(leftPad, px(12), 0, px(1))
                })
                val body = c.content.ifBlank { c.excerpt }
                if (body.isNotBlank()) col.addView(TextView(ctx).apply {
                    text = deHtml(body); textSize = 14f; setTextColor(0xFF000000.toInt())
                    setTextIsSelectable(true); setPadding(leftPad, 0, 0, 0)
                })
                // A voice or video reply reads as a small box you press, not a media player
                // sitting open in the middle of a conversation. Nothing loads until it's pressed.
                if (c.mediaKind == "audio" || c.mediaKind == "video") {
                    col.addView(soundbox(c, leftPad))
                } else c.imageUrl?.let { url ->
                    val img = android.widget.ImageView(ctx).apply {
                        adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(px(180), LinearLayout.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = px(4); leftMargin = leftPad }
                    }
                    col.addView(img)
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.IO) { LedgerCorrespondence.loadImage(ctx, url) }
                        if (bmp != null && isAdded) img.setImageBitmap(bmp)
                    }
                }
                // Per-comment actions row: Reply (community, nests under this comment) + Delete (own).
                val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(leftPad, px(2), 0, px(2)) }
                if (source == "community") actions.addView(TextView(ctx).apply {
                    text = "↩ Reply"; textSize = 13f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(2), px(20), px(2))
                    setOnClickListener {
                        showTextReplyDialog(threadId, title, replyingTo = c.author + ":  " + c.content.ifBlank { c.excerpt }, parentId = c.id)
                    }
                })
                if (c.mine) actions.addView(TextView(ctx).apply {
                    text = "✕ Delete"; textSize = 13f; setTextColor(0xFFB00020.toInt()); setPadding(0, px(2), 0, px(2))
                    setOnClickListener {
                        androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setMessage("Delete this reply?")
                            .setPositiveButton("Delete") { _, _ ->
                                lifecycleScope.launch {
                                    val ok = withContext(Dispatchers.IO) { LedgerCorrespondence.deleteThreadComment(ctx, source, c.id) }
                                    android.widget.Toast.makeText(ctx, if (ok) "Deleted" else "Couldn't delete", android.widget.Toast.LENGTH_SHORT).show()
                                    if (ok) { afterReplyPosted(source, threadId, title) }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
                if (actions.childCount > 0) col.addView(actions)
            }

            // Top-level comments in order; each followed by its nested replies (one level).
            val topLevel = items.filter { it.parentId == 0L }
            val childrenOf = items.filter { it.parentId != 0L }.groupBy { it.parentId }
            for (c in topLevel) {
                renderComment(c, indent = false)
                childrenOf[c.id]?.forEach { renderComment(it, indent = true) }
            }
            // Orphaned nested replies (parent not in this page) still show, indented.
            items.filter { it.parentId != 0L && topLevel.none { t -> t.id == it.parentId } }
                .forEach { renderComment(it, indent = true) }

            // Bottom reply bar — reply to the POST from inside the reader (community only).
            if (source == "community") {
                col.addView(View(ctx).apply {
                    setBackgroundColor(0xFFDDDDDD.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
                        .apply { topMargin = px(12); bottomMargin = px(6) }
                })
                col.addView(TextView(ctx).apply {
                    text = "↩  Reply"; textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(4), 0, px(4))
                    setOnClickListener { showReplyDialog(threadId, title) }
                })
            }
        }
    }


    /** ▸ Related for a gram/post: its provenance (what it answered) + your neighbouring posts. */
    private fun showPostRelated(post: LedgerPost) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun open(url: String) = startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(8), px(18), px(8)) }
        col.addView(TextView(ctx).apply { text = "Loading…"; setTextColor(0xFF888888.toInt()); setPadding(0, px(12), 0, 0) })
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("▸ Related · ${deHtml(post.title).ifBlank { spaceTitle }.take(32)}")
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Close", null)
            .show()
        fun sectionLabel(t: String) = TextView(ctx).apply {
            text = t; textSize = 11f; setTextColor(0xFF888888.toInt()); setPadding(0, px(12), 0, px(3))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { LedgerCorrespondence.communityRelated(ctx, post.id) }
            if (!isAdded) return@launch
            col.removeAllViews()
            if (r == null) { col.addView(TextView(ctx).apply { text = "Couldn't load." }); return@launch }
            r.provenance?.let { p ->
                col.addView(sectionLabel("FROM"))
                col.addView(TextView(ctx).apply {
                    // A real, easy-to-hit button (not a tiny ↩) when there's a source to jump to.
                    text = (if (p.url != null) "↩  Go to source" else "") + (if (p.url != null) "\n" else "") + deHtml(p.label)
                    textSize = 15f
                    setTextColor(if (p.url != null) 0xFF2F6F96.toInt() else 0xFF333333.toInt())
                    setPadding(px(12), px(10), px(12), px(10))
                    if (p.url != null) {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setStroke(px(1), 0xFF2F6F96.toInt()); cornerRadius = px(8).toFloat()
                        }
                        setOnClickListener { open(p.url) }
                    }
                })
            }
            col.addView(sectionLabel("YOUR POSTS IN THIS SPACE"))
            if (r.related.isEmpty()) col.addView(TextView(ctx).apply {
                text = "None yet."; setTextColor(0xFF888888.toInt()); textSize = 14f
            })
            r.related.forEach { rc ->
                col.addView(TextView(ctx).apply {
                    text = "•  ${deHtml(rc.title)}"
                    textSize = 14f; setTextColor(if (rc.url != null) 0xFF2F6F96.toInt() else 0xFF000000.toInt())
                    setPadding(0, px(3), 0, px(1))
                    if (rc.url != null) setOnClickListener { open(rc.url) }
                })
            }
        }
    }

    // Thin wrappers so every call site keeps working — both open the ONE unified reply surface.
    @SuppressLint("ClickableViewAccessibility")
    private fun showInkReplyDialog(
        feedId: Long, thread: String, provenanceDefault: String? = null, provUrl: String? = null,
        replyingTo: String? = null, parentId: Long = 0
    ) = showReplyDialog(feedId, thread, replyingTo, parentId, provenanceDefault, provUrl, startText = false)

    private fun showTextReplyDialog(
        feedId: Long, thread: String, replyingTo: String? = null, parentId: Long = 0
    ) = showReplyDialog(feedId, thread, replyingTo, parentId, null, null, startText = true)

    /**
     * The ONE reply surface (the "merging surface"): a ⅓-page canvas with a Draw / Type
     * toggle. Draw = stylus ink (post as annotation, or Share as gram with provenance);
     * Type = markdown text (bridge renders a safe subset), with a basics cheat-sheet.
     * Nests under [parentId] when replying to a specific comment.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showReplyDialog(
        feedId: Long, thread: String, replyingTo: String? = null, parentId: Long = 0,
        provenanceDefault: String? = null, provUrl: String? = null, startText: Boolean = false
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val ink = InkPadView(ctx)
        var textMode = startText
        var shareAsGram: (() -> Unit)? = null

        // "Reply with Gram / Picking / Log": an existing object attached to this reply, KEEPING its
        // provenance, combined with the small handwriting box as one reply.
        var attachedBitmap: Bitmap? = null   // picking/gram image, stacked above the ink
        // An attached voice/video gram: the bitmap above is its poster, this is the clip itself.
        var attachedAvFile: java.io.File? = null
        var attachedAvKind: String = ""
        var attachedAvTitle: String = ""
        var attachedCaption = ""             // markdown provenance/quote that rides with the reply
        // Rhizome loop: also save this reply into your Ledger (a Pickings gram whose provenance
        // points BACK at this thread), so a comment becomes a Ledger object you can rework.
        var saveToLedger = false

        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END; setPadding(0, 0, 0, px(4))
        }
        // Mode toggle — Draw vs Type; the active one is bold+underlined.
        val drawTab = TextView(ctx).apply { text = "✍ Draw"; textSize = 15f; setPadding(0, px(2), px(20), px(6)) }
        val typeTab = TextView(ctx).apply { text = "⌨ Type"; textSize = 15f; setPadding(0, px(2), 0, px(6)) }
        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; addView(drawTab); addView(typeTab) }

        // Draw surface: the shared pen toolbar (undo · colours · width) over a bold-framed
        // ⅓-page pad — the creation-page niceties that don't need the Onyx engine.
        val penBar = InkPadView.penBar(ctx, ink)

        val inkFrame = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt()); setPadding(px(2), px(2), px(2), px(2))
            addView(ink, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, (340 * dp).toInt()
            ))
        }
        val inkPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; addView(penBar); addView(inkFrame)
        }
        // Type surface: markdown editor + collapsible basics.
        val input = android.widget.EditText(ctx).apply {
            hint = "Write a reply… (markdown)"; setSingleLine(false); minLines = 6; gravity = android.view.Gravity.TOP
            setPadding(px(10), px(10), px(10), px(10))
        }
        val cheatToggle = TextView(ctx).apply {
            text = "ⓘ  Markdown basics"; textSize = 13f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), 0, px(2))
        }
        val cheat = TextView(ctx).apply {
            text = "**bold**   *italic*   `code`\n[text](https://link)\n- bullet    1. number\n# Heading    > quote"
            textSize = 12f; setTextColor(0xFF666666.toInt()); typeface = android.graphics.Typeface.MONOSPACE
            setPadding(px(8), px(4), px(8), px(6)); visibility = View.GONE
        }
        cheatToggle.setOnClickListener {
            cheat.visibility = if (cheat.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        val textPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; addView(input); addView(cheatToggle); addView(cheat)
        }

        val contentFrame = android.widget.FrameLayout(ctx).apply { addView(inkPane); addView(textPane) }

        // Attachment preview (shown once you attach a Gram/Picking/Log) + the "Reply with…" row.
        val attachPreview = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(px(6), px(6), px(6), px(6)); setBackgroundColor(0xFFEFF4F7.toInt())
        }
        fun clearAttachment() {
            // Detach the preview BEFORE recycling — an attached ImageView drawing a
            // recycled bitmap is a hard crash.
            attachPreview.removeAllViews(); attachPreview.visibility = View.GONE
            attachedBitmap?.recycle(); attachedBitmap = null; attachedCaption = ""
            attachedAvFile = null; attachedAvKind = ""; attachedAvTitle = ""
        }
        fun setAttachment(label: String, bmp: Bitmap?, caption: String) {
            attachPreview.removeAllViews()
            attachedBitmap?.recycle()
            attachedBitmap = bmp; attachedCaption = caption
            attachPreview.addView(TextView(ctx).apply {
                text = "📎  $label     ✕ remove"; textSize = 12f; setTextColor(0xFF2F6F96.toInt())
                setOnClickListener { clearAttachment() }
            })
            if (bmp != null) attachPreview.addView(com.toolsboox.ot.InkMount.wrapInColumn(ctx,
                android.widget.ImageView(ctx).apply {
                    setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                    scaleType = android.widget.ImageView.ScaleType.FIT_START
                    maxHeight = (140 * dp).toInt()
                }, taped = false))
            if (caption.isNotBlank()) attachPreview.addView(TextView(ctx).apply {
                text = richHtml(caption.replace("\n", "<br>")); textSize = 12f; setTextColor(0xFF444444.toInt())
                setPadding(0, px(4), 0, 0)
            })
            attachPreview.visibility = View.VISIBLE
        }
        val withRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(px(2), px(8), px(2), px(2))
        }
        fun withBtn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 14f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(2), px(16), px(2))
            setOnClickListener { onTap() }
        }
        withRow.addView(withBtn("🎴 with Gram") { pickGramForReply { l, b, c -> setAttachment(l, b, c) } })
        withRow.addView(withBtn("❝ with Picking") { pickPickingForReply { l, b, c -> setAttachment(l, b, c) } })
        withRow.addView(withBtn("🕘 with Log") { pickLogForReply { l, c -> setAttachment(l, null, c) } })
        withRow.addView(withBtn("🎤 with Voice") {
            pickAvGramForReply { label, poster, file, kind, title ->
                setAttachment(label, poster, "")
                attachedAvFile = file; attachedAvKind = kind; attachedAvTitle = title
            }
        })

        // Save-to-Ledger toggle: the reply also lands as a Pickings gram citing this thread.
        val saveToggle = TextView(ctx).apply {
            textSize = 13f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(2), px(4), px(2), px(2))
            fun label() = (if (saveToLedger) "☑" else "☐") + "  Save this reply to my Ledger"
            text = label()
            setOnClickListener { saveToLedger = !saveToLedger; text = label() }
        }

        val gramRow = TextView(ctx).apply {
            text = "↗  Share as gram instead…"
            textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(2), px(10), 0, px(4))
            setOnClickListener { shareAsGram?.invoke() }
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(12), px(8), px(12), 0)
            addView(actionRow)
            addView(tabRow)
            if (!replyingTo.isNullOrBlank()) addView(TextView(ctx).apply {
                text = deHtml(replyingTo); textSize = 13f; setTextColor(0xFF333333.toInt())
                setPadding(px(8), px(4), px(8), px(6)); maxHeight = (120 * dp).toInt()
                movementMethod = android.text.method.ScrollingMovementMethod()
            })
            addView(attachPreview)
            addView(contentFrame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(withRow)
            addView(saveToggle)
            if (provenanceDefault != null) addView(gramRow)
        }

        fun applyMode() {
            inkPane.visibility = if (textMode) View.GONE else View.VISIBLE
            textPane.visibility = if (textMode) View.VISIBLE else View.GONE
            gramRow.visibility = if (!textMode && provenanceDefault != null) View.VISIBLE else View.GONE
            drawTab.setTypeface(null, if (textMode) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
            drawTab.paintFlags = if (textMode) 0 else android.graphics.Paint.UNDERLINE_TEXT_FLAG
            typeTab.setTypeface(null, if (textMode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            typeTab.paintFlags = if (textMode) android.graphics.Paint.UNDERLINE_TEXT_FLAG else 0
        }
        drawTab.setOnClickListener { textMode = false; applyMode() }
        typeTab.setOnClickListener { textMode = true; applyMode() }
        applyMode()

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle((if (parentId > 0) "Reply · " else "Reply · ") + deHtml(thread).ifBlank { "thread" }.take(28))
            .setView(box)
            .create()

        fun actionBtn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(px(16), px(4), px(16), px(4)); setOnClickListener { onTap() }
        }
        actionRow.addView(actionBtn("Clear") { if (textMode) input.setText("") else ink.clear() })
        actionRow.addView(actionBtn("Cancel") { dialog.dismiss() })
        actionRow.addView(actionBtn("Send") {
            if (textMode) {
                // Type mode: the typed body, plus any attached item's provenance caption.
                // An attached IMAGE (gram/picking) rides too — switching Draw→Type must not
                // silently drop what you picked.
                val typed = input.text.toString().trim()
                val text = listOf(typed, attachedCaption).filter { it.isNotBlank() }.joinToString("\n\n")
                if (text.isBlank() && attachedBitmap == null) {
                    android.widget.Toast.makeText(ctx, "Nothing to send", android.widget.Toast.LENGTH_SHORT).show(); return@actionBtn
                }
                val png = attachedBitmap?.let {
                    val baos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.PNG, 100, baos); baos.toByteArray()
                }
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) {
                        if (attachedAvFile != null) LedgerCorrespondence.postInkReply(
                            ctx, feedId, null, parentId, text,
                            attachedAvFile, attachedAvKind, attachedAvTitle, png)
                        else if (png != null) LedgerCorrespondence.postInkReply(ctx, feedId, png, parentId, text)
                        else LedgerCorrespondence.postTextReply(ctx, feedId, text, parentId)
                    }
                    android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
                    if (status == "Reply posted") { markReplied("community", feedId); dialog.dismiss(); afterReplyPosted("community", feedId, thread) }
                }
            } else {
                // Draw mode: [attached image] stacked above [your ink], one combined PNG, with the
                // attachment's provenance as the caption. If nothing is drawn AND only a Log (text)
                // is attached, post it as a text reply instead.
                val inkBmp = ink.render()
                val combined = stackVertically(attachedBitmap, inkBmp)
                if (combined == null && attachedCaption.isBlank()) {
                    android.widget.Toast.makeText(ctx, "Nothing written", android.widget.Toast.LENGTH_SHORT).show()
                    return@actionBtn
                }
                val png = combined?.let {
                    val baos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.PNG, 100, baos); baos.toByteArray()
                }
                val cap = attachedCaption
                // With a clip attached, the ink stands on its own as the picture and the poster
                // stays the clip's own face — stacking them would make a poster of your notes.
                val avFile = attachedAvFile
                val avKind = attachedAvKind
                val avTitle = attachedAvTitle
                val inkOnlyPng = if (avFile == null) null else inkBmp?.let {
                    val baos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.PNG, 100, baos); baos.toByteArray()
                }
                val posterPng = if (avFile == null) null else attachedBitmap?.let {
                    val baos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.PNG, 100, baos); baos.toByteArray()
                }
                val alsoSave = saveToLedger && combined != null
                val saveBmp = if (alsoSave) combined!!.copy(combined.config ?: Bitmap.Config.ARGB_8888, false) else null
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) {
                        if (avFile != null) LedgerCorrespondence.postInkReply(
                            ctx, feedId, inkOnlyPng, parentId, cap, avFile, avKind, avTitle, posterPng)
                        else if (png != null) LedgerCorrespondence.postInkReply(ctx, feedId, png, parentId, cap)
                        else LedgerCorrespondence.postTextReply(ctx, feedId, cap, parentId)
                    }
                    // Rhizome: the reply also becomes a Pickings gram whose provenance points back here.
                    if (status == "Reply posted" && saveBmp != null) withContext(Dispatchers.IO) {
                        runCatching {
                            com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                                calendarDayService, documentsRoot(), saveBmp, java.time.LocalDate.now(),
                                com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY,
                                sourceLink = provUrl ?: "", sourceLabel = "↩ Reply · ${deHtml(thread).take(40)}"
                            )
                        }
                        saveBmp.recycle()
                    }
                    // NEVER recycle the attachment here — stackVertically ALIASES it when there's
                    // no ink, and the still-open dialog's preview (failed post) or the dismiss
                    // animation would then draw a recycled bitmap → crash. clearAttachment owns it.
                    if (inkBmp !== combined) inkBmp?.recycle()
                    if (combined !== attachedBitmap && combined !== inkBmp) combined?.recycle()
                    android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
                    if (status == "Reply posted") { markReplied("community", feedId); dialog.dismiss(); afterReplyPosted("community", feedId, thread) }
                }
            }
        })
        shareAsGram = {
            val bmp = ink.render()
            if (bmp == null) android.widget.Toast.makeText(ctx, "Nothing written", android.widget.Toast.LENGTH_SHORT).show()
            else { dialog.dismiss(); showGramProvenanceEditor(bmp, provenanceDefault ?: "", provUrl) }
        }
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** After any reply/delete: refresh the inbox, and if the thread reader is open, reopen it
     *  so the change (new reply, nesting, removal) shows immediately. */
    private fun afterReplyPosted(source: String, id: Long, thread: String) {
        load()
        if (threadReaderOpen) { threadReaderDialog?.dismiss(); showThread(source, id, thread) }
    }

    /**
     * Confirm-and-edit step before a reply goes out as a gram: the provenance line is editable
     * (redact or reword it) — outward-facing, so nothing shares until you say so.
     */
    private fun showGramProvenanceEditor(bmp: Bitmap, provenanceDefault: String, provUrl: String?) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val preview = com.toolsboox.ot.InkMount.wrapInColumn(ctx, android.widget.ImageView(ctx).apply {
            setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(150))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        })
        val input = android.widget.EditText(ctx).apply {
            setText(provenanceDefault); setSelection(text.length)
            textSize = 14f; setPadding(px(12), px(10), px(12), px(10)); minLines = 2
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(16), px(10), px(16), 0)
            addView(preview)
            addView(TextView(ctx).apply {
                text = "PROVENANCE (edit before sharing)"
                textSize = 11f; setTextColor(0xFF888888.toInt()); setPadding(0, px(12), 0, px(3))
            })
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Share as gram")
            .setView(box)
            .setPositiveButton("Share ↗") { _, _ ->
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos); bmp.recycle()
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                val provenance = input.text.toString().trim()
                val uuid = "gram-" + java.util.UUID.randomUUID().toString().lowercase()
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) {
                        com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.postGram(
                            ctx, b64, "", uuid, spaceId, provenance.ifBlank { null }, provUrl
                        )
                    }
                    android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
                    if (status.startsWith("Posted")) load()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> bmp.recycle() }
            .show()
    }

    /** Minimal stylus pad: white background, black ink, no Onyx pipeline needed for a short reply. */

    private data class LogPick(val kind: String, val title: String, val excerpt: String, val source: String?, val url: String?)

    /** Walk the most recent [days] day-files and gather log items (feeds deduped, pickings, items). */
    private fun gatherLog(days: Int): List<LogPick> {
        val out = mutableListOf<LogPick>()
        val cal = java.io.File(documentsRoot(), "calendar")
        if (!cal.exists()) return out
        cal.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .sortedByDescending { it.name }
            .take(days)
            .forEach { f ->
                // Slim decode — the log never needs the stroke arrays, and "All" walks ~1000 files.
                val day = runCatching { calendarDayService.loadLogSlice(f) }.getOrNull() ?: return@forEach
                val seenUrl = HashSet<String>()
                for (e in day.readingEvents) {
                    val u = e.url
                    if (!u.isNullOrBlank() && !seenUrl.add(u)) continue
                    val t = (e.excerpt?.takeIf { it.isNotBlank() } ?: e.title).trim()
                    if (t.isNotBlank()) out.add(LogPick("📰", e.title, t, e.source, e.url))
                }
                for (t in day.textElements.filter { it.pageKey == "pickings" && it.text.isNotBlank() })
                    out.add(LogPick("❝", "Picking", t.text.trim(), null, null))
                for (li in day.ledgerItems.filter { it.text.isNotBlank() })
                    out.add(LogPick("🗒", li.text.trim().take(40), li.text.trim(), null, null))
            }
        return out
    }

    /** Pick a Log item → its quote + source ride the reply as a markdown provenance caption.
     *  Scroll-safe (tappable rows, not an AlertDialog list), searchable, with a widening window
     *  so it loads a small recent slice fast and you can reach back. */
    private fun pickLogForReply(onPicked: (String, String) -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val ranges = intArrayOf(14, 60, 180, 1000)   // ~2wk · 2mo · 6mo · all
        val rangeNames = arrayOf("2 wk", "2 mo", "6 mo", "All")
        var rangeIdx = 0
        var all: List<LogPick> = emptyList()

        val search = android.widget.EditText(ctx).apply {
            hint = "Search log…"; setSingleLine(true); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rangeBtn = TextView(ctx).apply {
            text = "▸ ${rangeNames[rangeIdx]}"; textSize = 14f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(10), px(6), px(6), px(6))
        }
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(search); addView(rangeBtn)
        }
        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (440 * dp).toInt())
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(12), px(8), px(12), 0)
            addView(topRow); addView(scroll)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Reply with Log")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create()

        fun captionFor(p: LogPick) = buildString {
            append("> ").append(p.excerpt.take(400))
            val src = p.source?.takeIf { it.isNotBlank() }; val url = p.url?.takeIf { it.isNotBlank() }
            if (url != null) append("\n\n↩ from [").append(src ?: "source").append("](").append(url).append(")")
            else if (src != null) append("\n\n↩ from ").append(src)
        }
        fun render() {
            val q = search.text.toString().trim().lowercase()
            val shown = (if (q.isBlank()) all else all.filter { it.excerpt.lowercase().contains(q) || (it.source ?: "").lowercase().contains(q) }).take(400)
            listCol.removeAllViews()
            if (shown.isEmpty()) listCol.addView(TextView(ctx).apply {
                text = "No matching log items."; setTextColor(0xFF888888.toInt()); setPadding(px(4), px(12), px(4), 0)
            })
            for (p in shown) listCol.addView(TextView(ctx).apply {
                text = "${p.kind}  ${p.excerpt.take(90)}" + (p.source?.let { "\n      · $it" } ?: "")
                textSize = 14f; setTextColor(0xFF000000.toInt()); setPadding(px(6), px(10), px(6), px(10))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onPicked("Log · ${p.title.take(30)}", captionFor(p)); dialog.dismiss() }
            })
        }
        fun reload() {
            listCol.removeAllViews()
            listCol.addView(TextView(ctx).apply { text = "Loading…"; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) })
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) { gatherLog(ranges[rangeIdx]) }
                if (!isAdded) return@launch
                all = loaded; render()
            }
        }
        rangeBtn.setOnClickListener { rangeIdx = (rangeIdx + 1) % ranges.size; rangeBtn.text = "▸ ${rangeNames[rangeIdx]}"; reload() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        reload()
        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private data class PickPage(val date: java.time.LocalDate, val key: String, val name: String)

    private fun dayFileDate(name: String): java.time.LocalDate? =
        runCatching { java.time.LocalDate.parse(name.removePrefix("day-").removeSuffix("-v2.json")) }.getOrNull()

    /** Fullscreen lightbox: inspect the image BIG before committing — [label] on top,
     *  Attach / Close on the action row. The inspect step both visual pickers share. */
    private fun showAttachLightbox(bmp: Bitmap, label: String, onAttach: () -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val img = android.widget.ImageView(ctx).apply {
            setImageBitmap(bmp); adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(com.toolsboox.ot.InkMount.wrapInColumn(ctx, img))
            setPadding(px(8), px(4), px(8), px(4))
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(label.take(40))
            .setView(scroll)
            .setPositiveButton("Attach") { _, _ -> onAttach() }
            .setNegativeButton("Close", null)
            .show()
            .window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
    }

    /** Pick a Pickings page — VISUALLY: thumbnail rows (rendered lazily, batched) and a
     *  tap-to-zoom lightbox before committing ("it's all titles — who can remember"). */
    private fun pickPickingForReply(onPicked: (String, Bitmap?, String) -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (440 * dp).toInt())
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Reply with Picking").setView(scroll).setNegativeButton("Cancel", null).create()

        // Render the chosen page fresh at full width and hand it to the lightbox → attach.
        fun inspect(p: PickPage) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val day = runCatching { calendarDayService.load(documentsRoot(), p.date, null, java.util.Locale.getDefault()) }.getOrNull() ?: return@withContext null
                    com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderPageToBitmap(
                        day.noteStrokes[p.key] ?: emptyList(),
                        day.imageElements.filter { it.page == p.key },
                        day.textElements.filter { it.pageKey == p.key },
                        targetWidth = 1000
                    )
                }
                if (!isAdded) return@launch
                if (bmp == null) { android.widget.Toast.makeText(ctx, "That picking is empty", android.widget.Toast.LENGTH_SHORT).show(); return@launch }
                showAttachLightbox(bmp, "❝ ${p.name} · ${p.date}") {
                    dialog.dismiss()
                    onPicked("Picking · ${p.name}", bmp, "❝ from your Picking “${p.name}” · ${p.date}")
                }
            }
        }

        listCol.addView(TextView(ctx).apply { text = "Loading pickings…"; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) })
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) {
                val out = mutableListOf<PickPage>()
                val cal = java.io.File(documentsRoot(), "calendar")
                if (cal.exists()) cal.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
                    .sortedByDescending { it.name }.take(120)
                    .forEach { f ->
                        val d = dayFileDate(f.name) ?: return@forEach
                        val day = runCatching { calendarDayService.load(f) }.getOrNull() ?: return@forEach
                        val names = runCatching { com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, d).associate { it.key to it.name } }.getOrNull() ?: emptyMap()
                        val keys = (day.noteStrokes.keys + day.imageElements.map { it.page } + day.textElements.map { it.pageKey })
                            .filter { com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(it) }.toSet()
                        for (k in keys) {
                            val hasContent = (day.noteStrokes[k]?.isNotEmpty() == true) ||
                                day.imageElements.any { it.page == k } || day.textElements.any { it.pageKey == k && it.text.isNotBlank() }
                            if (hasContent) out.add(PickPage(d, k, names[k] ?: "Pickings"))
                        }
                    }
                out
            }
            if (!isAdded) return@launch
            listCol.removeAllViews()
            if (pages.isEmpty()) { listCol.addView(TextView(ctx).apply { text = "No pickings with content yet."; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) }); return@launch }

            // Thumbnail rows in lazy batches: placeholder first, small renders fill in as they
            // finish (all 120 up-front would stall the dialog for many seconds on e-ink).
            val batch = 20
            var shown = 0
            lateinit var appendBatch: () -> Unit
            appendBatch = {
                val slice = pages.drop(shown).take(batch)
                shown += slice.size
                val moreBtn: TextView? = if (shown < pages.size) TextView(ctx).apply {
                    text = "＋ ${pages.size - shown} more"
                    textSize = 13f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(8), px(12), px(8), px(12))
                } else null
                for (p in slice) {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(px(6), px(8), px(6), px(8)); setBackgroundResource(android.R.drawable.list_selector_background)
                    }
                    val thumbView = android.widget.ImageView(ctx).apply {
                        adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                        layoutParams = FrameLayout.LayoutParams(px(110), px(80))
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }
                    row.addView(com.toolsboox.ot.InkMount.wrap(ctx, thumbView, taped = false).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = px(10) }
                    })
                    row.addView(TextView(ctx).apply {
                        text = "❝ ${p.name}\n· ${p.date}"; textSize = 14f; setTextColor(0xFF000000.toInt())
                    })
                    row.setOnClickListener { inspect(p) }
                    if (moreBtn != null) listCol.addView(row, listCol.childCount)
                    else listCol.addView(row)
                    // Lazy thumb: small render off-main, fills in when ready.
                    lifecycleScope.launch {
                        val thumb = withContext(Dispatchers.IO) {
                            runCatching {
                                val day = calendarDayService.load(documentsRoot(), p.date, null, java.util.Locale.getDefault())
                                com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderPageToBitmap(
                                    day.noteStrokes[p.key] ?: emptyList(),
                                    day.imageElements.filter { it.page == p.key },
                                    day.textElements.filter { it.pageKey == p.key },
                                    targetWidth = 320
                                )
                            }.getOrNull()
                        }
                        if (isAdded && thumb != null) thumbView.setImageBitmap(thumb)
                    }
                }
                moreBtn?.let { btn ->
                    btn.setOnClickListener { listCol.removeView(btn); appendBatch() }
                    listCol.addView(btn)
                }
            }
            appendBatch()
        }
        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private data class GramPick(val date: java.time.LocalDate, val data: String, val label: String, val link: String)

    /** Pick an existing Gram (a dropped image element, carrying its own provenance) → attach it
     *  with that provenance. Scroll-safe thumbnail list over the recent window. */
    private fun pickGramForReply(onPicked: (String, Bitmap?, String) -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (440 * dp).toInt())
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Reply with Gram").setView(scroll).setNegativeButton("Cancel", null).create()

        listCol.addView(TextView(ctx).apply { text = "Loading grams…"; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) })
        lifecycleScope.launch {
            val grams = withContext(Dispatchers.IO) {
                val out = mutableListOf<GramPick>()
                val cal = java.io.File(documentsRoot(), "calendar")
                if (cal.exists()) cal.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
                    .sortedByDescending { it.name }.take(120)
                    .forEach { f ->
                        val d = dayFileDate(f.name) ?: return@forEach
                        val day = runCatching { calendarDayService.load(f) }.getOrNull() ?: return@forEach
                        for (e in day.imageElements) {
                            if (e.data.isBlank()) continue
                            val label = e.sourceLabel.ifBlank { "Gram" }
                            out.add(GramPick(d, e.data, label, e.sourceLink))
                            if (out.size >= 200) return@withContext out
                        }
                    }
                out
            }
            if (!isAdded) return@launch
            listCol.removeAllViews()
            if (grams.isEmpty()) { listCol.addView(TextView(ctx).apply { text = "No grams yet."; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) }); return@launch }
            for (g in grams) {
                // Downsampled decode — 200 full-res bitmaps in one list is an OOM on e-ink RAM.
                val thumb = runCatching {
                    val bytes = android.util.Base64.decode(g.data, android.util.Base64.DEFAULT)
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val target = px(72)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull() ?: continue
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(6), px(8), px(6), px(8)); setBackgroundResource(android.R.drawable.list_selector_background)
                }
                row.addView(com.toolsboox.ot.InkMount.wrap(ctx,
                    android.widget.ImageView(ctx).apply {
                        setImageBitmap(thumb); adjustViewBounds = true
                        layoutParams = FrameLayout.LayoutParams(px(72), px(72))
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }, taped = false).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = px(10) }
                })
                row.addView(TextView(ctx).apply {
                    text = "${g.label}\n· ${g.date}"; textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                row.setOnClickListener {
                    dialog.dismiss()
                    val bmp = runCatching {
                        val bytes = android.util.Base64.decode(g.data, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                    val cap = if (g.link.startsWith("http")) "🎴 gram · [${g.label}](${g.link})" else "🎴 gram · ${g.label}"
                    onPicked("Gram · ${g.label.take(24)}", bmp, cap)
                }
                listCol.addView(row)
            }
        }
        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** One recording available to attach to a reply. */
    private data class AvPick(
        val date: java.time.LocalDate,
        val file: java.io.File,
        val kind: String,
        val title: String,
        val durationMs: Int
    )

    /**
     * Attach a voice or video gram you've already made to a reply.
     *
     * Reads the recordings off recent days rather than offering to record a new one here: the
     * capture surfaces already exist, and a reply is for sending something, not making it. The
     * poster comes back with it so the composer can show what's attached, and so the posted reply
     * carries the clip's own still face.
     */
    private fun pickAvGramForReply(
        onPicked: (String, Bitmap?, java.io.File, String, String) -> Unit
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (440 * dp).toInt())
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Reply with Voice or Video").setView(scroll).setNegativeButton("Cancel", null).create()

        listCol.addView(TextView(ctx).apply {
            text = "Looking for recordings…"; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0)
        })

        lifecycleScope.launch {
            val picks = withContext(Dispatchers.IO) {
                val out = mutableListOf<AvPick>()
                val dir = attachmentsDir()
                val cal = java.io.File(documentsRoot(), "calendar")
                if (cal.exists()) cal.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
                    .sortedByDescending { it.name }.take(120)
                    .forEach { f ->
                        val d = dayFileDate(f.name) ?: return@forEach
                        val day = runCatching { calendarDayService.load(f) }.getOrNull() ?: return@forEach
                        for (a in day.avGrams) {
                            val kind = when (a.kind) {
                                com.toolsboox.da.Attachment.Kind.AUDIO -> "audio"
                                com.toolsboox.da.Attachment.Kind.VIDEO -> "video"
                                else -> continue
                            }
                            val file = java.io.File(dir, a.filename)
                            if (!file.exists()) continue
                            // Prefer the title the gram was given on its picking card.
                            val titled = day.imageElements.firstOrNull { it.attachmentId == a.id }
                            out.add(AvPick(
                                d, file, kind,
                                titled?.mediaTitle?.ifBlank { null } ?: a.filename,
                                titled?.durationMs?.takeIf { it > 0 }
                                    ?: a.duration?.let { (it * 1000).toInt() } ?: 0
                            ))
                            if (out.size >= 80) return@withContext out
                        }
                    }
                out
            }

            if (!isAdded) return@launch
            listCol.removeAllViews()
            if (picks.isEmpty()) {
                listCol.addView(TextView(ctx).apply {
                    text = "No recordings yet.\nMake one from the day page, then it'll show up here."
                    setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0)
                })
                return@launch
            }

            for (p in picks) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(6), px(10), px(6), px(10))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
                row.addView(TextView(ctx).apply {
                    text = if (p.kind == "video") "🎥" else "🎤"; textSize = 22f
                    setPadding(0, 0, px(12), 0)
                })
                val clock = com.toolsboox.plugin.calendar.ot.AvPoster.clock(p.durationMs)
                row.addView(TextView(ctx).apply {
                    text = p.title.take(40) + "\n· " + p.date + (if (clock.isNotBlank()) "   ·   $clock" else "")
                    textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                row.setOnClickListener {
                    dialog.dismiss()
                    lifecycleScope.launch {
                        val poster = withContext(Dispatchers.IO) {
                            val kind = if (p.kind == "video") com.toolsboox.da.Attachment.Kind.VIDEO
                                else com.toolsboox.da.Attachment.Kind.AUDIO
                            com.toolsboox.plugin.calendar.ot.AvPoster.poster(p.file, kind, p.durationMs, p.title)
                        }
                        if (!isAdded) { poster?.recycle(); return@launch }
                        val label = (if (p.kind == "video") "Video · " else "Voice · ") + p.title.take(24)
                        onPicked(label, poster, p.file, p.kind, p.title)
                    }
                }
                listCol.addView(row)
            }
        }

        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
