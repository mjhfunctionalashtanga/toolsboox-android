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

    /** "replies" = the exchange inbox · "community" = a space's posts you can reply to. */
    private var mode = "replies"
    private var spaceId = 25L                 // MichaelFilter — the first space
    private var spaceTitle = "MichaelFilter"

    private fun prefs() = requireContext().getSharedPreferences("ledger_correspondence", Context.MODE_PRIVATE)

    /** Decode HTML entities (&hellip; &#039; &amp; …) so excerpts read cleanly. */
    private fun deHtml(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()

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
                text = post.title; textSize = 15f; setTextColor(0xFF000000.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(ctx).apply {
                text = deHtml(post.excerpt); textSize = 14f; setTextColor(0xFF000000.toInt())
            })
            val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
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
            actions.addView(TextView(ctx).apply {
                text = "✍  Reply in ink"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), px(18), 0)
                val provDefault = "↩ In reply to ${post.author}" +
                    post.excerpt.trim().take(90).let { if (it.isNotBlank()) ": “$it”" else "" }
                setOnClickListener {
                    showInkReplyDialog(
                        post.id, post.title.ifBlank { spaceTitle }, provDefault, post.url,
                        replyingTo = post.author.ifBlank { "Post" } + ":  " + post.excerpt
                    )
                }
            })
            if (post.commentsCount > 0) actions.addView(TextView(ctx).apply {
                text = "💬  See replies"
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
            card.addView(actions)
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
                    head.thread.ifBlank { "Untitled thread" } + (if (replied) "   ✓ replied" else "")
                textSize = 16f; setTextColor(0xFF000000.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(px(4), px(14), px(4), px(6))
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
                container.addView(card)
            }
            container.addView(TextView(ctx).apply {
                text = "💬  See replies"
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
                container.addView(TextView(ctx).apply {
                    text = "✍  Reply in ink"
                    textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                    setPadding(px(10), px(2), px(10), px(10))
                    setOnClickListener {
                        showInkReplyDialog(
                            head.threadId, head.thread,
                            replyingTo = head.author + ":  " + head.content.ifBlank { head.excerpt }
                        )
                    }
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
            .setTitle(title.ifBlank { "Thread" })
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()
        dialog.show()
        // The reader earns the whole screen width — long messages were cramped in the
        // stock dialog's narrow column.
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { LedgerCorrespondence.threadComments(ctx, source, threadId) }
            if (!isAdded) return@launch
            col.removeAllViews()
            if (items.isEmpty()) {
                col.addView(TextView(ctx).apply { text = "No replies yet."; setTextColor(0xFF888888.toInt()) })
                return@launch
            }
            for (c in items) {
                col.addView(TextView(ctx).apply {
                    text = c.author + (if (c.mine) "  · you" else "") + "   ·   " + c.createdAt.take(16) +
                        (if (c.hasImage) "   📎" else "")
                    textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, px(12), 0, px(1))
                })
                val body = c.content.ifBlank { c.excerpt }
                if (body.isNotBlank()) col.addView(TextView(ctx).apply {
                    text = deHtml(body); textSize = 14f; setTextColor(0xFF000000.toInt())
                    setTextIsSelectable(true)
                })
                c.imageUrl?.let { url ->
                    val img = android.widget.ImageView(ctx).apply {
                        adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(px(180), LinearLayout.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = px(4) }
                    }
                    col.addView(img)
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.IO) { LedgerCorrespondence.loadImage(ctx, url) }
                        if (bmp != null && isAdded) img.setImageBitmap(bmp)
                    }
                }
                // Your own comments/uploads are deletable (server enforces ownership).
                if (c.mine) col.addView(TextView(ctx).apply {
                    text = "✕  Delete"
                    textSize = 13f; setTextColor(0xFFB00020.toInt()); setPadding(0, px(3), 0, px(2))
                    setOnClickListener {
                        androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setMessage("Delete this reply?")
                            .setPositiveButton("Delete") { _, _ ->
                                lifecycleScope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        LedgerCorrespondence.deleteThreadComment(ctx, source, c.id)
                                    }
                                    android.widget.Toast.makeText(ctx, if (ok) "Deleted" else "Couldn't delete",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                    if (ok) { dialog.dismiss(); showThread(source, threadId, title); load() }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
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
            .setTitle("▸ Related · ${post.title.ifBlank { spaceTitle }.take(32)}")
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
                    text = p.label + (if (p.url != null) "   ↗" else "")
                    textSize = 14f; setTextColor(if (p.url != null) 0xFF2F6F96.toInt() else 0xFF333333.toInt())
                    setPadding(0, px(2), 0, px(2))
                    if (p.url != null) setOnClickListener { open(p.url) }
                })
            }
            col.addView(sectionLabel("YOUR POSTS IN THIS SPACE"))
            if (r.related.isEmpty()) col.addView(TextView(ctx).apply {
                text = "None yet."; setTextColor(0xFF888888.toInt()); textSize = 14f
            })
            r.related.forEach { rc ->
                col.addView(TextView(ctx).apply {
                    text = "•  ${rc.title}"
                    textSize = 14f; setTextColor(if (rc.url != null) 0xFF2F6F96.toInt() else 0xFF000000.toInt())
                    setPadding(0, px(3), 0, px(1))
                    if (rc.url != null) setOnClickListener { open(rc.url) }
                })
            }
        }
    }

    /** A white card you write on with the stylus; Done posts the ink as your comment. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showInkReplyDialog(
        feedId: Long, thread: String, provenanceDefault: String? = null, provUrl: String? = null,
        replyingTo: String? = null
    ) {
        val ctx = requireContext()
        val ink = InkPadView(ctx)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        var shareAsGram: (() -> Unit)? = null
        // Actions at the TOP, not the bottom — so your writing hand never rests on them mid-stroke.
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END; setPadding(0, 0, 0, px(6))
        }
        // A bold frame around the pen area: on e-ink the white pad melted into the white
        // dialog, so it wasn't clear where writing would land.
        val inkFrame = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
            setPadding(px(2), px(2), px(2), px(2))
            addView(ink, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, (420 * dp).toInt()
            ))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(12), px(8), px(12), 0)
            addView(actionRow)
            // What you're answering, right above where you answer it. Capped height so the
            // pad keeps its room; long messages scroll inside the quote.
            if (!replyingTo.isNullOrBlank()) {
                addView(TextView(ctx).apply {
                    text = deHtml(replyingTo); textSize = 13f; setTextColor(0xFF333333.toInt())
                    setPadding(px(8), px(4), px(8), px(6))
                    maxHeight = (140 * dp).toInt()
                    isVerticalScrollBarEnabled = true
                    movementMethod = android.text.method.ScrollingMovementMethod()
                })
            }
            addView(inkFrame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        if (provenanceDefault != null) box.addView(TextView(ctx).apply {
            text = "↗  Share as gram instead…"
            textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(2), px(10), 0, px(4))
            setOnClickListener { shareAsGram?.invoke() }
        })

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Reply in ink · ${thread.ifBlank { "thread" }}")
            .setView(box)
            .create()

        fun actionBtn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(px(16), px(4), px(16), px(4)); setOnClickListener { onTap() }
        }
        actionRow.addView(actionBtn("Clear") { ink.clear() })
        actionRow.addView(actionBtn("Cancel") { dialog.dismiss() })
        actionRow.addView(actionBtn("Send") {
            val bmp = ink.render() ?: run {
                android.widget.Toast.makeText(ctx, "Nothing written", android.widget.Toast.LENGTH_SHORT).show()
                return@actionBtn
            }
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos); bmp.recycle()
            lifecycleScope.launch {
                val status = withContext(Dispatchers.IO) {
                    LedgerCorrespondence.postInkReply(ctx, feedId, baos.toByteArray())
                }
                android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
                if (status == "Reply posted") { markReplied("community", feedId); dialog.dismiss(); load() }
            }
        })
        shareAsGram = {
            val bmp = ink.render()
            if (bmp == null) {
                android.widget.Toast.makeText(ctx, "Nothing written", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                showGramProvenanceEditor(bmp, provenanceDefault ?: "", provUrl)
            }
        }
        dialog.show()
        // Full width: the writing surface is the point of this dialog.
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Confirm-and-edit step before a reply goes out as a gram: the provenance line is editable
     * (redact or reword it) — outward-facing, so nothing shares until you say so.
     */
    private fun showGramProvenanceEditor(bmp: Bitmap, provenanceDefault: String, provUrl: String?) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val preview = android.widget.ImageView(ctx).apply {
            setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(150))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
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
    private class InkPadView(context: Context) : View(context) {
        private val paths = mutableListOf<Path>()
        private var current: Path? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        init { setBackgroundColor(Color.WHITE) }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    current = Path().also { it.moveTo(event.x, event.y); paths.add(it) }
                }
                MotionEvent.ACTION_MOVE -> current?.lineTo(event.x, event.y)
                MotionEvent.ACTION_UP -> current = null
            }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (p in paths) canvas.drawPath(p, paint)
        }

        fun clear() { paths.clear(); current = null; invalidate() }

        /** The written card as a bitmap, or null when blank. */
        fun render(): Bitmap? {
            if (paths.isEmpty() || width == 0 || height == 0) return null
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            for (p in paths) c.drawPath(p, paint)
            return bmp
        }
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
