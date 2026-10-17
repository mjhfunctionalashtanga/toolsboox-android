package com.toolsboox.ui.share

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.toolsboox.R
import com.toolsboox.plugin.calendar.ot.GramDestinations
import com.toolsboox.plugin.michaelfilter.ot.ShareTextParser
import com.toolsboox.ui.main.MainActivity
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * SEND FROM ANY APP — THE CARD (Michael, 2026-10-10: "highlight text, or pick an image, in EinkBro
 * or any other app. A small Ledgable card appears over that app. One tap saves it, and you're back
 * in EinkBro.").
 *
 * Every share used to start the WHOLE app inside the sender's task: the day page inflated (an ink
 * surface, a full e-ink refresh), then the chooser, then Back to get home — and a highlight could
 * not reach Ledgable at all. This activity is translucent, floating, out of recents and out of the
 * back stack; it is the PROCESS_TEXT door (EinkBro lists it by this activity's label, "Ledgable")
 * and it owns all three SEND filters (EinkBro's last-target shortcut picks the OTHER activity when
 * two accept text). Rows that save run here and the card closes; rows that take you somewhere hand
 * a destination to MainActivity; ⋯ More re-sends to today's full chooser, so no row is lost.
 *
 * E-INK: no dim (gray dims dither and ghost on Carta), no shadow, no elevation, no ripple, no
 * animation — black on white. It never touches the pen path: no SurfaceView, no BigmeFastInk, no
 * InkMount, no Onyx raw drawing, so there is no B6 daemon state to wedge.
 */
class ShareCatchActivity : AppCompatActivity() {

    private enum class Kind { TEXT, LINK, IMAGE, PDF }

    /** What arrived. [passageLink] is a link INSIDE a highlighted passage (⏳ Later); [pageLink] and
     *  [title] are the page it came from, when the sender says (EinkBro doesn't, yet). */
    private data class Incoming(
        val kind: Kind,
        val passage: String = "",
        val url: String? = null,
        val passageLink: String? = null,
        val pageLink: String? = null,
        val title: String? = null,
        val blurb: String = "",
        val sharedText: String? = null,
        val subject: String? = null,
        val stream: Uri? = null,
        val mime: String? = null,
        val processText: Boolean = false,
    )

    /** One card row. [big] is the fast default — read first, largest. */
    private data class Row(val label: String, val big: Boolean = false, val action: () -> Unit)

    private lateinit var incoming: Incoming
    private var via: String? = null
    private var imageCopy: Deferred<File?>? = null
    private var busy = false
    /** "Place as pick…" has its chooser up (or on its way — e-ink takes a beat to draw it): taps
     *  on the card stop until the card has its focus back, so a second tap can't open a second
     *  chooser and place the card twice (review, 2026-10-10). */
    private var choosing = false
    private var cardShown = false
    private var launchedLedgable = false
    private var closed = false
    private var rowsBox: LinearLayout? = null
    private var headerTitle: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noTransition(opening = true)
        // READ-ONLY, ALWAYS: EinkBro says EXTRA_PROCESS_TEXT_READONLY; an editable field elsewhere
        // may expect replacement text back. Ledgable never replaces text, and an OK result with no
        // text can wipe the selection — so the answer is always CANCELED.
        setResult(RESULT_CANCELED)
        // A RE-CREATED card (a config change its configChanges don't cover) would parse the same
        // share again: a second PDF import racing the first to the same shelf name, a second
        // image copy, a fresh idle card over a busy one. The share was the first card's — this
        // one just goes (review, 2026-10-10).
        if (savedInstanceState != null) { finish(); return }
        val parsed = parse(intent)
        if (parsed == null) {
            // A .txt shared as a file, or a type we can't read — say so, never a silent nothing.
            ShareActions.toast(this, "Nothing Ledgable can save from that")
            finish(); return
        }
        val from = senderPackage()
        // Our own menus hide this door (OwnProcessText), but if one slips through, never put the
        // card over our own ink surface (C9). A highlight takes the card's default — ◈ To All
        // Picks, one tap, nothing drawn — so no chooser pops over the dialog or book it came from;
        // a deliberate share from Ledgable to itself gets the in-app chooser (review, 2026-10-10).
        if (from == packageName && parsed.kind != Kind.PDF) {
            if (parsed.processText) { incoming = parsed; pickText() } else handBack(parsed)
            return
        }
        incoming = parsed
        via = ShareActions.viaName(this, from)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!busy && !choosing) finish() }
        })
        when (parsed.kind) {
            Kind.PDF -> catchPdf(parsed.stream!!)
            Kind.IMAGE -> {
                // Copy while we hold the sender's grant — it dies with this card (P4).
                val src = parsed.stream!!
                val ctx = applicationContext
                imageCopy = ShareActions.appScope.async(Dispatchers.IO) { ShareActions.copyToCache(ctx, src) }
                showCard(imageRows())
            }
            Kind.LINK -> showCard(linkRows())
            Kind.TEXT -> showCard(textRows())
        }
    }

    /**
     * The card holds key focus while it's up, so a Boox page key lands HERE, not on the page
     * behind — unhandled it would pop the system volume panel over EinkBro (a second e-ink redraw).
     * A page key means "never mind, keep reading": it closes the card. Swallowed both ways.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
            android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (event.action == android.view.KeyEvent.ACTION_UP && cardShown && !busy && !choosing) finish()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** The card has its focus back: whatever "Place as pick…" opened is closed (picked → the save
     *  already set busy; cancelled → the rows wake up again). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) choosing = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (cardShown) placeWindow()
    }

    override fun finish() {
        val first = !closed
        closed = true
        super.finish()
        noTransition(opening = false)
        // P1: EinkBro is only paused behind a translucent card and doesn't redraw, so on a Boox the
        // card's border can ghost after it closes. Onyx only, GU (a clean without GC's black
        // flash); the B6 is left to its own refresh — the card never reaches for the daemon.
        if (first && cardShown && !launchedLedgable) cleanPanelAfterClose()
    }

    // --- What arrived ----------------------------------------------------------------------------

    private fun parse(i: Intent?): Incoming? {
        i ?: return null
        @Suppress("DEPRECATION")
        val stream = runCatching { i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
            ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        return when (i.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                // A CharSequence, not always a String (C10): TextViews and Chrome send styled text,
                // which getStringExtra returns as null.
                val sel = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
                if (sel.isEmpty()) null
                else Incoming(
                    Kind.TEXT, passage = sel, passageLink = ShareTextParser.parse(sel, null).url,
                    pageLink = originatingLink(i),
                    title = (i.getCharSequenceExtra(Intent.EXTRA_SUBJECT) ?: i.getCharSequenceExtra(Intent.EXTRA_TITLE))
                        ?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    processText = true)
            }
            Intent.ACTION_SEND -> {
                val type = i.type.orEmpty().lowercase()
                when {
                    type == "application/pdf" -> stream?.let { Incoming(Kind.PDF, stream = it, mime = type) }
                    type.startsWith("image/") -> stream?.let { Incoming(Kind.IMAGE, stream = it, mime = type) }
                    else -> {
                        val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        val subject = i.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
                        val p = ShareTextParser.parse(text, subject)
                        if (p.url != null) Incoming(
                            Kind.LINK, url = p.url, title = p.title, blurb = p.leftoverText?.trim().orEmpty(),
                            sharedText = text, subject = subject)
                        else {
                            // A reader's highlight share: the book's title as the subject, the
                            // quote as the text. The title is the SOURCE (footer, label, note
                            // title), never the quote's first line (review, 2026-10-10); with no
                            // separate body the lone line is the passage itself.
                            val body = p.leftoverText?.trim()?.takeIf { it.isNotEmpty() }
                            val passage = (body ?: p.title ?: text?.trim()).orEmpty()
                            if (passage.isBlank()) null
                            else Incoming(Kind.TEXT, passage = passage, title = if (body != null) p.title else null,
                                sharedText = text, subject = subject)
                        }
                    }
                }
            }
            else -> null
        }
    }

    /** The page a highlight came from, when the sender adds it (the EinkBro 2-line change would put
     *  the page address in EXTRA_ORIGINATING_URI and its title in EXTRA_SUBJECT). */
    private fun originatingLink(i: Intent): String? {
        @Suppress("DEPRECATION")
        val u = runCatching { i.getParcelableExtra<Uri>(Intent.EXTRA_ORIGINATING_URI)?.toString() }.getOrNull()
            ?: runCatching { i.getStringExtra(Intent.EXTRA_ORIGINATING_URI) }.getOrNull()
        return u?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    /** Who sent it — the referrer the system stamps (EinkBro's plain startActivity), else the
     *  caller of a for-result launch (a TextView's selection menu). */
    private fun senderPackage(): String? {
        if (intent?.getBooleanExtra(ShareActions.EXTRA_FORWARDED, false) == true) {
            // Forwarded by MainActivity (C8): the launcher of record is Ledgable itself; only the
            // referrer it carried names the sender — and none means unknown, never "ourselves".
            @Suppress("DEPRECATION")
            val ref = runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER) }.getOrNull()
            return ref?.takeIf { it.scheme == "android-app" }?.host
        }
        return referrer?.takeIf { it.scheme == "android-app" }?.host ?: callingPackage
    }

    // --- The card --------------------------------------------------------------------------------

    private fun showCard(rows: List<Row>) {
        val ink = Color.BLACK
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(2), ink) }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            elevation = 0f
        }
        // Everything on TOP — the header with ✕, then the rows, the default first.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), 0, 0)
        }
        headerTitle = TextView(this).apply {
            text = headerText()
            textSize = 14f; setTextColor(ink); setTypeface(typeface, Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "✕"; textSize = 20f; setTextColor(ink); gravity = Gravity.CENTER
            contentDescription = "Close"; background = null
            setOnClickListener { if (!busy && !choosing) finish() }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        card.addView(header)
        previewText()?.let { p ->
            card.addView(TextView(this).apply {
                text = p; textSize = 14f; setTextColor(ink)
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(16), 0, dp(16), dp(8))
            })
        }
        card.addView(rule())
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rowsBox = box
        card.addView(ScrollView(this).apply {
            // A STANDING scrollbar (no fade — a static bar is fine on e-ink): the link card runs
            // to ten rows, and in landscape on a Palma ⋯ More sits below the fold with no other
            // sign it exists (review, 2026-10-10).
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            isVerticalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(box)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fillRows(rows)
        setContentView(card)
        cardShown = true
        placeWindow()
        setFinishOnTouchOutside(true)
    }

    /** Top of the screen, centred, no dim, no animation — away from the resting hand. */
    private fun placeWindow() {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        val w = minOf(resources.displayMetrics.widthPixels - dp(32), dp(520))
        window.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply { y = dp(40); dimAmount = 0f; windowAnimations = 0 }
    }

    private fun headerText(): String = listOfNotNull("Ledgable", via?.let { "via $it" }).joinToString(" · ")

    private fun previewText(): String? = when (incoming.kind) {
        Kind.TEXT -> "“" + incoming.passage.take(400) + "”"
        Kind.LINK -> listOfNotNull(incoming.title, incoming.url).joinToString("\n")
        Kind.IMAGE -> "🖼  A picture"
        Kind.PDF -> null
    }

    private fun rule(): View = View(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun fillRows(rows: List<Row>) {
        val box = rowsBox ?: return
        box.removeAllViews()
        rows.forEachIndexed { i, row ->
            if (i > 0) box.addView(rule())
            box.addView(TextView(this).apply {
                text = row.label
                setTextColor(Color.BLACK)
                textSize = if (row.big) 21f else 17f
                if (row.big) setTypeface(typeface, Typeface.BOLD)
                minHeight = dp(if (row.big) 60 else 52)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                background = null   // no ripple: an animation e-ink can only smear
                setOnClickListener { if (!busy && !choosing) row.action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** A row that has to hold the card a moment (a render, an image copy): taps stop, the header
     *  says so, and an outside tap no longer closes it mid-write. */
    private fun setBusy() {
        busy = true
        setFinishOnTouchOutside(false)
        headerTitle?.text = headerText() + " · …"
    }

    // --- Rows, by what arrived -------------------------------------------------------------------

    // THE ORDER IS THE WANT (Michael, 09-13: Later · the pick · a fresh note page · a task card,
    // today's page LAST) — the same order as ⋯ More's full chooser and iOS 198, so the same share
    // reads the same way everywhere. A trailing "…" marks the rows that open Ledgable.

    /** Highlighted text, or a plain-text share with no link. */
    private fun textRows(): List<Row> {
        val rows = mutableListOf<Row>()
        incoming.passageLink?.let { link ->
            rows += Row("⏳  Later · ${Uri.parse(link).host ?: link}") { saveLater(link, null, incoming.passage) }
        }
        rows += Row("◈  To All Picks", big = true) { pickText() }
        rows += Row("✎  On a new note page…") { textOntoNewNotePage() }
        rows += Row("☐  Task card") { textTask() }
        rows += Row("📝  Text note") { textNote() }
        rows += Row("🔎  Ask about this…") { ask() }
        // The old chooser's "On this page (text)", kept (Michael 2026-10-10: "not losing features,
        // not having too many clicks") — and the only way a highlight WITH a link drops as text.
        rows += Row("On this page…") { textOnThisPage() }
        rows += Row("⋯  More") { more() }
        return rows
    }

    /** A link — EinkBro's page share; a picture first when the link IS a picture. */
    private fun linkRows(): List<Row> {
        val url = incoming.url ?: return emptyList()
        val picture = ShareActions.isImageLink(url)
        val rows = mutableListOf<Row>()
        if (picture) {
            rows += Row("🖼  Picture to All Picks", big = true) { picture(asTask = false) }
            rows += Row("🖼  Picture as a task card") { picture(asTask = true) }
        }
        rows += Row("⏳  Later", big = !picture) { saveLater(url, incoming.title, incoming.blurb) }
        rows += Row("◈  To All Picks") { linkToPicks(null) }
        rows += Row("✎  On a new note page…") { linkOntoNewNotePage() }
        rows += Row("☐  Task card") { linkTask() }
        rows += Row("Place as pick…") { choosePlace("Place this card on") { d -> linkToPicks(d) } }
        rows += Row("File to a lane…") { showLanes() }
        rows += Row("As text…") { linkAsText() }
        rows += Row("On this page…") { linkOnThisPage() }
        rows += Row("⋯  More") { more() }
        return rows
    }

    /** An image from Gallery or any app. */
    private fun imageRows(): List<Row> = listOf(
        Row("◈  To All Picks", big = true) { imageToPicks(null) },
        Row("✎  On a new note page…") { imageOntoNewNotePage() },
        Row("☐  Task card") { imageTask() },
        Row("Place as pick…") { choosePlace("Place this image on") { d -> imageToPicks(d) } },
        Row("On this page…") { imageOnThisPage() },
        Row("⋯  More") { more() },
    )

    /** "Place as pick…" — the whole destination vocabulary, with the card's taps held while it's
     *  up ([choosing]; released when the card gets its focus back). */
    private fun choosePlace(title: String, onPick: (GramDestinations.Destination) -> Unit) {
        if (busy || choosing) return
        choosing = true
        runCatching { GramDestinations.choose(this, title = title, fragment = null, onPick = onPick) }
            .onFailure { choosing = false; Timber.w(it, "share card: chooser failed") }
    }

    /** "File to a lane…" swaps the rows in place — ‹ Back on top, no second dialog. */
    private fun showLanes() {
        val url = incoming.url ?: return
        val title = incoming.title
        fun lane(kind: String, res: Int, glyph: String): Row {
            val label = getString(res)
            val filed = getString(R.string.ledger_share_filed, label)
            return Row("$glyph  $label") {
                stay { ctx ->
                    withContext(Dispatchers.IO) { ShareActions.fileToLane(ctx, kind, url, title) }
                    ShareActions.toast(ctx, filed)
                }
            }
        }
        fillRows(listOf(
            Row("‹  Back") { fillRows(linkRows()) },
            lane("read", R.string.ledger_share_read, "📖"),
            lane("watch", R.string.ledger_share_watch, "📺"),
            lane("listen", R.string.ledger_share_listen, "🎧"),
            lane("educate", R.string.ledger_share_educate, "🎓"),
            Row(getString(R.string.ledger_share_drop_on_page).trim() + "…") { linkAsText() },
        ))
    }

    // --- Row actions: text -----------------------------------------------------------------------

    private fun textLabel() = ShareActions.sourceLabel(incoming.title, via, "Shared note")
    private fun textFooter() = ShareActions.footer(incoming.title, via)
    private fun firstLine(max: Int): String =
        incoming.passage.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(max) ?: "Shared note"

    private fun pickText() {
        val inc = incoming; val label = textLabel(); val foot = textFooter(); val feed = via.orEmpty()
        stay { ctx ->
            val face = ShareActions.textCard(inc.passage, foot) ?: error("no card")
            val d = ShareActions.allPicks(ctx)
            val ok = ShareActions.placeGram(ctx, face, d, inc.pageLink.orEmpty(), label,
                cardText = inc.passage, sourceFeed = feed)
            if (ok) ShareActions.dayWritten(d.key)
            ShareActions.toast(ctx, if (ok) "◈ Added to ${d.name}" else "Couldn't save that")
        }
    }

    private fun textNote() {
        val inc = incoming; val v = via
        stay { ctx ->
            withContext(Dispatchers.IO) { ShareActions.addTextNote(ctx, inc.passage, inc.title, v) }
            ShareActions.toast(ctx, "📝 Text note · today")
        }
    }

    private fun textTask() {
        val inc = incoming; val foot = textFooter(); val label = firstLine(80)
        stay { ctx ->
            val face = ShareActions.textCard(inc.passage, foot) ?: error("no card")
            val ok = ShareActions.pinBitmapAsTask(ctx, face, label)
            if (ok) ShareActions.dayWritten(TODAY_PAGE)
            ShareActions.toast(ctx, if (ok) "☐ Saved as a task card" else "Couldn't save that")
        }
    }

    private fun ask() {
        val inc = incoming; val v = via.orEmpty()
        open { ctx ->
            val grounding = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.ot.AskBridge.grounding(
                    ctx, inc.passage, inc.title.orEmpty(), inc.pageLink.orEmpty(), v)
            }
            handoff(ShareActions.OPEN_ASK)
                .putExtra(ShareActions.EXTRA_ASK_PASSAGE, inc.passage)
                .putExtra(ShareActions.EXTRA_ASK_CONTEXT, grounding)
        }
    }

    private fun textOntoNewNotePage() {
        val inc = incoming; val label = textLabel(); val foot = textFooter(); val feed = via.orEmpty()
        val name = inc.title ?: firstLine(48)
        open { ctx ->
            val face = ShareActions.textCard(inc.passage, foot) ?: return@open null
            val note = ShareActions.placeOnNewNotePage(ctx, face, inc.pageLink.orEmpty(), label,
                cardText = inc.passage, sourceFeed = feed, noteName = name) ?: return@open null
            noteHandoff(note)
        }
    }

    /** "On this page…" — the passage as a movable text box on today's page (MainActivity wraps
     *  it), the old chooser's "On this page (text)". */
    private fun textOnThisPage() {
        val passage = incoming.passage
        open { _ ->
            handoff(ShareActions.OPEN_TEXT_ON_DAY).putExtra(ShareActions.EXTRA_TEXT, passage)
        }
    }

    // --- Row actions: links ----------------------------------------------------------------------

    private fun saveLater(url: String, title: String?, excerpt: String?) = stay { ctx ->
        val lane = ShareActions.saveToLater(ctx, url, title, excerpt)
        ShareActions.toast(ctx, "⏳ Saved for later · $lane")
    }

    /** ◈ To All Picks, or the place "Place as pick…" chose. */
    private fun linkToPicks(dest: GramDestinations.Destination?) {
        val url = incoming.url ?: return
        val t = incoming.title ?: url; val blurb = incoming.blurb
        stay { ctx ->
            val card = ShareActions.renderLinkCard(url, t, blurb) ?: error("no card")
            val d = dest ?: ShareActions.allPicks(ctx)
            val ok = ShareActions.placeGram(ctx, card, d, url, t)
            if (ok) ShareActions.dayWritten(d.key)
            ShareActions.toast(ctx, if (ok) "◈ Added to ${d.name}" else "Couldn't make a card")
        }
    }

    private fun linkTask() {
        val url = incoming.url ?: return
        val t = incoming.title ?: url; val blurb = incoming.blurb
        stay { ctx ->
            val card = ShareActions.renderLinkCard(url, t, blurb) ?: error("no card")
            val ok = ShareActions.pinBitmapAsTask(ctx, card, t)
            if (ok) ShareActions.dayWritten(TODAY_PAGE)
            ShareActions.toast(ctx, if (ok) "☐ Saved as a task card" else "Couldn't save that")
        }
    }

    private fun linkOntoNewNotePage() {
        val url = incoming.url ?: return
        val t = incoming.title ?: url; val blurb = incoming.blurb
        open { ctx ->
            val card = ShareActions.renderLinkCard(url, t, blurb) ?: return@open null
            val note = ShareActions.placeOnNewNotePage(ctx, card, url, t) ?: return@open null
            noteHandoff(note)
        }
    }

    /** "On this page…" — the link card on today's page, then today's page. */
    private fun linkOnThisPage() {
        val url = incoming.url ?: return
        val t = incoming.title ?: url; val blurb = incoming.blurb
        open { ctx ->
            val card = ShareActions.renderLinkCard(url, t, blurb) ?: return@open null
            if (!ShareActions.placeGram(ctx, card, null, url, t)) return@open null
            ShareActions.afterCardWrite(ctx)
            handoff(ShareActions.OPEN_DAY)
        }
    }

    /** "As text…" — title and link as a movable box on today's page (MainActivity wraps it). */
    private fun linkAsText() {
        val url = incoming.url ?: return
        val box = listOfNotNull(incoming.title, url).joinToString("\n")
        open { _ ->
            handoff(ShareActions.OPEN_TEXT_ON_DAY)
                .putExtra(ShareActions.EXTRA_TEXT, box)
                .putExtra(ShareActions.EXTRA_URL, url)
        }
    }

    /** 🖼 — the link IS a picture: download it, the photo card's face, the address as its source. */
    private fun picture(asTask: Boolean) {
        val url = incoming.url ?: return
        val foot = ShareActions.footer(incoming.title, via)
        val label = ShareActions.sourceLabel(incoming.title, via, "Picture"); val feed = via.orEmpty()
        val taskText = listOfNotNull(incoming.title ?: "Picture", via?.let { "via $it" }).joinToString(" · ")
        stay { ctx ->
            val face = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.feeds.ot.FeedNoteGram.fetchImage(url)
                    ?.let { com.toolsboox.plugin.feeds.ot.FeedNoteGram.photoCard(it, foot) }
            }
            if (face == null) { ShareActions.toast(ctx, "Couldn't fetch that image"); return@stay }
            val d = if (asTask) null else ShareActions.allPicks(ctx)
            val ok = if (d == null) ShareActions.pinBitmapAsTask(ctx, face, taskText)
            else ShareActions.placeGram(ctx, face, d, url, label, sourceFeed = feed)
            if (ok) ShareActions.dayWritten(d?.key ?: TODAY_PAGE)
            ShareActions.toast(ctx, when {
                !ok -> "Couldn't save that"
                d == null -> "☐ Saved as a task card"
                else -> "◈ Added to ${d.name}"
            })
        }
    }

    // --- Row actions: images ---------------------------------------------------------------------

    private fun imageLabel() = ShareActions.sourceLabel(null, via, "Shared image")

    private fun imageToPicks(dest: GramDestinations.Destination?) {
        val label = imageLabel(); val feed = via.orEmpty()
        stayWithImage { ctx, bmp ->
            val d = dest ?: ShareActions.allPicks(ctx)
            val ok = ShareActions.placeGram(ctx, bmp, d, "", label, sourceFeed = feed)
            if (ok) ShareActions.dayWritten(d.key)
            ShareActions.toast(ctx, if (ok) "◈ Added to ${d.name}" else "Couldn't save that")
        }
    }

    private fun imageTask() = stayWithImage { ctx, bmp ->
        val ok = ShareActions.pinBitmapAsTask(ctx, bmp, "Shared image")
        if (ok) ShareActions.dayWritten(TODAY_PAGE)
        ShareActions.toast(ctx, if (ok) "☐ Saved as a task card" else "Couldn't save that")
    }

    private fun imageOntoNewNotePage() {
        val label = imageLabel(); val feed = via.orEmpty()
        open { ctx ->
            val bmp = cachedImage(ctx) ?: return@open null
            val note = ShareActions.placeOnNewNotePage(ctx, bmp, "", label, sourceFeed = feed,
                noteName = "Shared image") ?: return@open null
            noteHandoff(note)
        }
    }

    /** "On this page…" — the movable image element on today's page, from our cached copy. */
    private fun imageOnThisPage() = open { ctx ->
        val file = imageCopy?.await() ?: return@open null
        handoff(ShareActions.OPEN_IMAGE_ON_DAY)
            .putExtra(ShareActions.EXTRA_IMAGE, ShareActions.cacheUri(ctx, file).toString())
    }

    private suspend fun cachedImage(ctx: Context): Bitmap? {
        val file = imageCopy?.await() ?: return null
        return withContext(Dispatchers.IO) { ShareActions.decodeImage(ctx, Uri.fromFile(file)) }
    }

    // --- ⋯ More, and the self-share --------------------------------------------------------------

    /** ⋯ More — today's full chooser, unchanged, so every row it ever had is still one tap away. */
    private fun more() {
        setBusy()
        val inc = incoming; val v = via
        ShareActions.appScope.launch {
            val copy = imageCopy?.await()
            val send = sendIntent(inc, copy?.let { ShareActions.cacheUri(applicationContext, it) })
                .putExtra(ShareActions.EXTRA_FROM_CARD, true)
            v?.let { send.putExtra(ShareActions.EXTRA_VIA, it) }
            launchLedgable(send)
            finish()
        }
    }

    /** A share from Ledgable itself: no card over our own ink — the in-app chooser takes it. */
    private fun handBack(inc: Incoming) {
        launchLedgable(sendIntent(inc, null).putExtra(ShareActions.EXTRA_FROM_CARD, true))
        finish()
    }

    /** The share as the SEND MainActivity's arms read. A highlight becomes plain shared text. */
    private fun sendIntent(inc: Incoming, image: Uri?): Intent = when (inc.kind) {
        Kind.IMAGE, Kind.PDF -> Intent(Intent.ACTION_SEND)
            .setType(inc.mime ?: "image/*")
            .putExtra(Intent.EXTRA_STREAM, image ?: inc.stream)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Kind.LINK -> Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, inc.sharedText ?: inc.url)
            .apply { inc.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }
        Kind.TEXT -> Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, if (inc.processText) inc.passage else inc.sharedText ?: inc.passage)
            .apply { (inc.subject ?: inc.title)?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }
    }

    // --- PDF -------------------------------------------------------------------------------------

    /**
     * "PDF: goes straight to the shelf with a toast. No card and no app launch." Nothing is drawn,
     * and the window lets touches through to the app below while the copy runs — but the card stays
     * alive until it's done, because the read grant on the PDF is the card's.
     */
    private fun catchPdf(uri: Uri) {
        busy = true
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val host = this
        ShareActions.appScope.launch {
            val name = ShareActions.importPdf(host, uri)
            ShareActions.toast(applicationContext, if (name != null) "On the shelf · $name" else "Couldn't import that PDF")
            finish()
        }
    }

    // --- Doors -----------------------------------------------------------------------------------

    /** A row that saves and STAYS: the card closes on the tap and the save runs in the app scope
     *  (C7), then the sync push a cold process would otherwise never make (P3). */
    private fun stay(work: suspend (Context) -> Unit) {
        if (busy) return
        // Taps stop NOW: e-ink takes a beat to take the card down, and a second tap in that beat
        // (the usual "did it hear me?") would place the pick twice.
        busy = true
        val ctx = applicationContext
        finish()
        ShareActions.appScope.launch {
            runCatching { work(ctx) }.onFailure {
                Timber.w(it, "share card: save failed")
                ShareActions.toast(ctx, "Couldn't save that")
            }
            ShareActions.afterCardWrite(ctx)
        }
    }

    /** [stay] for an image: the card holds just until its cached copy exists (the grant is the
     *  card's), then closes; the decode and the save run after. */
    private fun stayWithImage(work: suspend (Context, Bitmap) -> Unit) {
        if (busy) return
        setBusy()
        val ctx = applicationContext
        ShareActions.appScope.launch {
            val file = imageCopy?.await()
            finish()
            val bmp = file?.let { withContext(Dispatchers.IO) { ShareActions.decodeImage(ctx, Uri.fromFile(it)) } }
            if (bmp == null) { ShareActions.toast(ctx, "Couldn't read that image"); return@launch }
            runCatching { work(ctx, bmp) }.onFailure {
                Timber.w(it, "share card: image save failed")
                ShareActions.toast(ctx, "Couldn't save that")
            }
            ShareActions.afterCardWrite(ctx)
        }
    }

    /**
     * A row that TAKES YOU SOMEWHERE: the card stays up while the store half runs (a render and a
     * place — a breath), then starts MainActivity WHILE IT IS STILL IN FRONT — Android 14 lets a
     * foreground activity launch, not a finished one — and only then closes.
     */
    private fun open(work: suspend (Context) -> Intent?) {
        if (busy) return
        setBusy()
        val ctx = applicationContext
        ShareActions.appScope.launch {
            val handoff = runCatching { work(ctx) }
                .onFailure { Timber.w(it, "share card: open failed") }.getOrNull()
            if (handoff != null) launchLedgable(handoff)
            else ShareActions.toast(ctx, "Couldn't do that")
            finish()
        }
    }

    /** The destination alone; [launchLedgable] puts it on the launcher-shaped intent. */
    private fun handoff(what: String): Intent = Intent().putExtra(ShareActions.EXTRA_CARD_OPEN, what)

    private fun noteHandoff(note: com.toolsboox.plugin.calendar.ot.LedgerDocuments.NewNote): Intent {
        ShareActions.afterCardWrite(applicationContext)
        return handoff(ShareActions.OPEN_NOTE)
            .putExtra(ShareActions.EXTRA_DATE, note.date.toString())
            .putExtra(ShareActions.EXTRA_KEY, note.key)
    }

    /**
     * Into Ledgable's OWN task (NEW_TASK — not the sender's), onto the copy already running there
     * (CLEAR_TOP | SINGLE_TOP → onNewIntent), with no transition.
     *
     * The intent is LAUNCHER-SHAPED — MAIN + LAUNCHER, the [payload] in extras (review,
     * 2026-10-10): after a reboot or Clear-all there is no Ledgable task, and this intent becomes
     * its root. A SEND or CARD_OPEN root makes the next launcher tap stack a SECOND MainActivity
     * (two day surfaces), and a restore after process death replay the old share. [payload] is a
     * [handoff] or ⋯ More's SEND, which rides whole in EXTRA_CARD_SEND; the per-process token
     * proves to the exported MainActivity that it came from this card.
     */
    private fun launchLedgable(payload: Intent) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(applicationContext, MainActivity::class.java)
            .putExtra(ShareActions.EXTRA_CARD_TOKEN, ShareActions.cardToken)
        if (payload.action == Intent.ACTION_SEND) {
            i.putExtra(ShareActions.EXTRA_CARD_SEND, payload)
            // A nested intent's grant flags are never honoured: the stream rides the OUTER
            // intent's clip (not part of the launcher match) so MainActivity can still read it.
            @Suppress("DEPRECATION")
            runCatching { payload.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let {
                i.clipData = android.content.ClipData.newRawUri("", it)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else payload.extras?.let { i.putExtras(it) }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val starter: Context = if (!isDestroyed) this else applicationContext
        val opts = ActivityOptions.makeCustomAnimation(starter, 0, 0).toBundle()
        runCatching { starter.startActivity(i, opts); launchedLedgable = true }
            .onFailure {
                Timber.w(it, "card → Ledgable launch failed")
                ShareActions.toast(applicationContext, "Couldn't open Ledgable")
            }
    }

    private companion object {
        /** Today's day page itself — the key a task card's telling carries (MainActivity's own
         *  pinBitmapAsTask tells "default" too; ShareActions.placeGram's null destination). */
        const val TODAY_PAGE = "default"
    }

    private fun noTransition(opening: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(
                if (opening) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun cleanPanelAfterClose() {
        if (!Build.MANUFACTURER.contains("onyx", ignoreCase = true)) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                com.onyx.android.sdk.api.device.epd.EpdController.repaintEveryThing(
                    com.onyx.android.sdk.api.device.epd.UpdateMode.GU
                )
            } catch (t: Throwable) { /* no panel to clean */ }
        }, 350)
    }
}
