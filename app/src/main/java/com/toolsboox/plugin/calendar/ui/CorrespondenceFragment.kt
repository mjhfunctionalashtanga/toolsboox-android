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
import com.toolsboox.plugin.calendar.nw.LedgerCorrespondence
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentCorrespondenceBinding.bind(view)
        binding.correspondenceClose.setOnClickListener { NavHostFragment.findNavController(this).popBackStack() }
        binding.correspondenceRefresh.setOnClickListener { load() }
        load()
    }

    private fun load() {
        val ctx = context ?: return
        lifecycleScope.launch {
            val replies = withContext(Dispatchers.IO) { LedgerCorrespondence.fetch(ctx) }
            render(replies)
        }
    }

    private fun render(replies: List<LedgerReply>) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val container = binding.correspondenceContainer
        container.removeAllViews()

        if (!com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(ctx).ready) {
            container.addView(TextView(ctx).apply {
                text = "Connect the community site (Boards → Web bridge…) and replies to your posts and cards gather here."
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
            container.addView(TextView(ctx).apply {
                text = (if (head.source == "boards") "📋  " else "👥  ") + head.thread.ifBlank { "Untitled thread" }
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
                    text = r.excerpt; textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                container.addView(card)
            }
            if (head.source == "community" && head.threadUrl.isNotBlank()) {
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
                    setOnClickListener { showInkReplyDialog(head.threadId, head.thread) }
                })
            }
        }
    }

    /** A white card you write on with the stylus; Done posts the ink as your comment. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showInkReplyDialog(feedId: Long, thread: String) {
        val ctx = requireContext()
        val ink = InkPadView(ctx)
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(ink, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (420 * dp).toInt()))
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Reply in ink · ${thread.ifBlank { "thread" }}")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val bmp = ink.render() ?: run {
                    android.widget.Toast.makeText(ctx, "Nothing written", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                bmp.recycle()
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) {
                        LedgerCorrespondence.postInkReply(ctx, feedId, baos.toByteArray())
                    }
                    android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
                    if (status == "Reply posted") load()
                }
            }
            .setNeutralButton("Clear") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .show()
            .also { dialog ->
                // Keep the dialog open on Clear — re-bind the button after show().
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { ink.clear() }
            }
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
