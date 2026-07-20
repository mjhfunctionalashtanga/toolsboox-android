package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.plugin.calendar.nw.ChatMessage
import com.toolsboox.plugin.calendar.nw.ChatThread
import com.toolsboox.plugin.calendar.nw.LedgerChat
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Messages — the Ledger's window into FluentCommunity chat (the live fluent-messaging plugin).
 * A list of your group (space) chats and 1:1 DMs; open one to read the thread and send text, with
 * a light poll keeping it fresh (no sockets — kind to e-ink). Handwriting replies are a follow-on.
 * Reuses the "Community & Boards" bridge creds; empty with a hint if unconfigured.
 */
@AndroidEntryPoint
class MessagesFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_messages

    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var upButton: Button

    private var threads: List<ChatThread> = emptyList()
    private var openThread: ChatThread? = null

    /** The messages column + the id of the newest shown, so polling only appends the new. */
    private var messagesColumn: LinearLayout? = null
    private var messagesScroll: ScrollView? = null
    private var lastMessageId: Long = 0
    private var pollJob: Job? = null

    private val density get() = resources.displayMetrics.density
    private fun px(v: Int): Int = (v * density).toInt()
    private fun toast(msg: String) { if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        content = view.findViewById(R.id.messages_content)
        titleView = view.findViewById(R.id.messages_title)
        upButton = view.findViewById(R.id.messages_up)
        view.findViewById<Button>(R.id.messages_close).setOnClickListener {
            NavHostFragment.findNavController(this).popBackStack()
        }
        view.findViewById<Button>(R.id.messages_refresh).setOnClickListener {
            val t = openThread
            if (t == null) loadThreads() else openChat(t)
        }
        upButton.setOnClickListener { showThreadList() }
        loadThreads()
    }

    override fun onPause() { super.onPause(); stopPolling() }
    override fun onResume() {
        super.onResume()
        openThread?.let { if (pollJob == null) startPolling(it) }
    }

    /* ---------------------------------------------------------------
     * Thread list
     * ------------------------------------------------------------- */

    private fun loadThreads() {
        stopPolling()
        openThread = null
        titleView.text = "Messages"
        upButton.visibility = View.GONE
        renderMessage("Loading chats…")
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { LedgerChat.threads(requireContext()) }
            threads = list
            if (openThread == null) showThreadList()
        }
    }

    private fun showThreadList() {
        stopPolling()
        openThread = null
        titleView.text = "Messages"
        upButton.visibility = View.GONE
        if (threads.isEmpty()) {
            renderMessage("No chats yet.\n\nSet the community site + app password under Settings → Community & Boards (Fluent), then refresh with ↻. Group chats appear here once a space has group chat on.")
            return
        }
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(6), px(10), px(24))
        }
        val groups = threads.filter { it.isGroup }
        val dms = threads.filter { !it.isGroup }
        if (groups.isNotEmpty()) {
            col.addView(sectionLabel("GROUP CHATS"))
            groups.forEach { col.addView(threadRow(it)) }
        }
        if (dms.isNotEmpty()) {
            col.addView(sectionLabel("DIRECT"))
            dms.forEach { col.addView(threadRow(it)) }
        }
        scroll.addView(col)
        setContent(scroll)
    }

    private fun sectionLabel(t: String) = TextView(requireContext()).apply {
        text = t; setTextColor(Color.parseColor("#888888")); textSize = 11f
        typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
        setPadding(px(6), px(14), 0, px(4))
    }

    private fun threadRow(thread: ChatThread): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(px(2), Color.BLACK); cornerRadius = px(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) }
            isClickable = true
            setOnClickListener { openChat(thread) }
            addView(TextView(ctx).apply {
                text = (if (thread.isGroup) "👥  " else "") + thread.title
                setTextColor(Color.BLACK); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            })
            if (thread.messageCount > 0) addView(TextView(ctx).apply {
                text = "${thread.messageCount} message" + (if (thread.messageCount == 1) "" else "s")
                setTextColor(Color.parseColor("#666666")); textSize = 13f
                setPadding(0, px(3), 0, 0)
            })
        }
    }

    /* ---------------------------------------------------------------
     * A thread
     * ------------------------------------------------------------- */

    private fun openChat(thread: ChatThread) {
        stopPolling()
        openThread = thread
        titleView.text = thread.title
        upButton.visibility = View.VISIBLE
        lastMessageId = 0
        renderMessage("Loading…")
        lifecycleScope.launch {
            val msgs = withContext(Dispatchers.IO) { LedgerChat.messages(requireContext(), thread.id) }
            if (openThread?.id != thread.id) return@launch
            renderChat(thread, msgs)
            startPolling(thread)
        }
    }

    private fun renderChat(thread: ChatThread, msgs: List<ChatMessage>) {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(8), px(10), px(8))
        }
        scroll.addView(col)
        messagesColumn = col
        messagesScroll = scroll

        if (msgs.isEmpty()) col.addView(TextView(ctx).apply {
            text = "No messages yet — say hello."
            setTextColor(Color.parseColor("#888888")); textSize = 14f; setPadding(px(6), px(12), 0, 0)
        })
        msgs.forEach { col.addView(bubble(it)) }
        lastMessageId = msgs.maxOfOrNull { it.id } ?: 0

        // Composer.
        val input = EditText(ctx).apply {
            hint = "Message…"; textSize = 15f; maxLines = 4
            setPadding(px(12), px(10), px(12), px(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(px(2), Color.parseColor("#888888")); cornerRadius = px(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val inkBtn = Button(ctx).apply {
            text = "✍"; isAllCaps = false; textSize = 18f
            minWidth = 0; setPadding(px(12), 0, px(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(6) }
            setOnClickListener { showInkComposer(thread, input) }
        }
        val sendBtn = Button(ctx).apply {
            text = "Send"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(6) }
            setOnClickListener { sendMessage(thread, input) }
        }
        val composer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(8), px(10), px(10))
            addView(input); addView(inkBtn); addView(sendBtn)
        }

        root.addView(scroll)
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1))
        })
        root.addView(composer)
        setContent(root)
        scrollToBottom()
    }

    private fun bubble(m: ChatMessage): View {
        val ctx = requireContext()
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(6) }
            gravity = if (m.mine) Gravity.END else Gravity.START
        }
        if (!m.mine) wrap.addView(TextView(ctx).apply {
            text = m.author; setTextColor(Color.parseColor("#888888")); textSize = 11f
            setPadding(px(6), 0, px(6), px(1))
        })
        val bubbleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(9), px(12), px(9))
            background = GradientDrawable().apply {
                setColor(if (m.mine) Color.parseColor("#ECECEC") else Color.WHITE)
                setStroke(px(2), Color.BLACK); cornerRadius = px(10).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = px(40).takeIf { m.mine } ?: 0
            lp.marginEnd = px(40).takeIf { !m.mine } ?: 0
            layoutParams = lp
        }
        if (m.text.isNotBlank()) bubbleBox.addView(TextView(ctx).apply {
            text = m.text; setTextColor(Color.BLACK); textSize = 15f
        })
        if (m.imageUrl != null) {
            val img = ImageView(ctx).apply {
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(px(200), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = if (m.text.isNotBlank()) px(6) else 0 }
            }
            bubbleBox.addView(img)
            val url = m.imageUrl
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { LedgerChat.loadImage(requireContext(), url) }
                if (bmp != null && isAdded) img.setImageBitmap(bmp)
            }
        }
        wrap.addView(bubbleBox)
        return wrap
    }

    private fun sendMessage(thread: ChatThread, input: EditText) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { LedgerChat.send(requireContext(), thread.id, text) }
            if (!ok) { toast("Couldn't send"); input.setText(text); return@launch }
            pollOnce(thread)   // pull our own message (+ any others) straight back
        }
    }

    /** Handwrite a message — the pad's PNG (plus any typed text) posts as one chat message.
     *  Action buttons live at the TOP (a writing hand rests where bottom buttons would be),
     *  and the pad wears a bold frame so the pen area reads clearly on e-ink. */
    private fun showInkComposer(thread: ChatThread, input: EditText) {
        val ctx = requireContext()
        val ink = InkPadView(ctx)
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 0, 0, px(6))
        }
        val inkFrame = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(px(2), px(2), px(2), px(2))
            addView(ink, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, px(320)
            ))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(12), px(6), px(12), px(10))
            addView(actionRow)
            addView(inkFrame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Handwrite · ${thread.title.take(32)}")
            .setView(box)
            .create()
        fun actionBtn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 16f; setTextColor(Color.parseColor("#2F6F96"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(px(16), px(4), px(16), px(4)); setOnClickListener { onTap() }
        }
        actionRow.addView(actionBtn("Clear") { ink.clear() })
        actionRow.addView(actionBtn("Cancel") { dialog.dismiss() })
        actionRow.addView(actionBtn("Send") {
            val bmp = ink.render() ?: run { toast("Nothing written"); return@actionBtn }
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos); bmp.recycle()
            val typed = input.text.toString().trim().ifBlank { null }
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    LedgerChat.sendInk(requireContext(), thread.id, typed, baos.toByteArray())
                }
                if (!ok) { toast("Couldn't send"); return@launch }
                input.text.clear()
                dialog.dismiss()
                pollOnce(thread)
            }
        })
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** Minimal stylus pad — plain touch, no Onyx pipeline (fine for a short handwritten message). */
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
                MotionEvent.ACTION_DOWN -> current = Path().also { it.moveTo(event.x, event.y); paths.add(it) }
                MotionEvent.ACTION_MOVE -> current?.lineTo(event.x, event.y)
                MotionEvent.ACTION_UP -> current = null
            }
            invalidate(); return true
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (p in paths) canvas.drawPath(p, paint)
        }
        fun clear() { paths.clear(); current = null; invalidate() }
        fun render(): Bitmap? {
            if (paths.isEmpty() || width == 0 || height == 0) return null
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(Color.WHITE)
            for (p in paths) c.drawPath(p, paint)
            return bmp
        }
    }

    /* ---------------------------------------------------------------
     * Polling
     * ------------------------------------------------------------- */

    private fun startPolling(thread: ChatThread) {
        stopPolling()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000)
                if (openThread?.id != thread.id) break
                pollOnce(thread)
            }
        }
    }

    private suspend fun pollOnce(thread: ChatThread) {
        val fresh = withContext(Dispatchers.IO) {
            LedgerChat.newMessages(requireContext(), thread.id, lastMessageId)
        }
        if (fresh.isEmpty() || openThread?.id != thread.id) return
        val col = messagesColumn ?: return
        // Drop the "no messages yet" placeholder on first real arrival.
        if (lastMessageId == 0L && col.childCount == 1 && col.getChildAt(0) is TextView) col.removeAllViews()
        fresh.filter { it.id > lastMessageId }.forEach { col.addView(bubble(it)) }
        lastMessageId = maxOf(lastMessageId, fresh.maxOf { it.id })
        scrollToBottom()
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    private fun scrollToBottom() {
        messagesScroll?.post { messagesScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    /* ---------------------------------------------------------------
     * Content helpers
     * ------------------------------------------------------------- */

    private fun setContent(v: View) {
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderMessage(msg: String) {
        setContent(TextView(requireContext()).apply {
            text = msg; setTextColor(Color.parseColor("#666666")); textSize = 15f
            gravity = Gravity.CENTER; setPadding(px(32), px(48), px(32), px(32))
        })
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
