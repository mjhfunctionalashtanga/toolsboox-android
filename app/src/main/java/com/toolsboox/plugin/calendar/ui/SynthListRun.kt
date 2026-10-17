package com.toolsboox.plugin.calendar.ui

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.LedgerContextMenu
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ot.DayLocks
import com.toolsboox.plugin.calendar.ot.LinkCardRenderer
import com.toolsboox.plugin.calendar.ot.PendingSummon
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.StickyCardRenderer
import com.toolsboox.plugin.calendar.ot.SynthCorrespondence
import com.toolsboox.plugin.calendar.ot.SynthEngines
import com.toolsboox.plugin.calendar.ot.SynthLanded
import com.toolsboox.plugin.calendar.ot.SynthListLanding
import com.toolsboox.plugin.calendar.ot.SynthPageStore
import com.toolsboox.plugin.calendar.ot.SynthPending
import com.toolsboox.plugin.calendar.ot.SynthSources
import com.toolsboox.plugin.calendar.ot.SynthVoice
import com.toolsboox.plugin.calendar.ot.SynthWords
import com.toolsboox.plugin.feeds.ot.ArticleOpen
import com.toolsboox.ui.plugin.ScreenFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ❖ SYNTHESIZE THIS LIST / THIS ARTICLE → A NEW SYNTHESIZE PAGE THAT LINKS BACK, AND ONE PICK.
 *
 * Michael, 2026-10-12: "If thats what it does, the response should come back with links to those
 * articles within ledgable itself so you can follow up on it from within" → "Cool. That goes on
 * both android and ios. And yeah, it can create that page as a new synth page, and add a pick to
 * all picks". (Design: scratchpad synth-list-links-design.md; iOS twin App/SynthListRun.swift.)
 *
 * Replaces [com.toolsboox.plugin.feeds.ui.runEngineIntoNewNote] for the Incoming seats only — a
 * WRITE note with the reply as one text box and no links. Now:
 *
 *   seat → engine → "❖ Reading 23 articles…" → "❖ Writing back…" → a new Synthesize page
 *   (`synthesize-<ms>`, named `<engine> · <scope>`): the reply's stickies with their [n] marks, a
 *   card per cited article, one Sources card listing all that was sent — and one ❖ pick in All
 *   Picks on today. Every card opens the article IN Ledgable ([ArticleOpen]); never the browser.
 *
 * It rides the synth-writeback spine: a [PendingSummon] (mode "list" / "article") is written
 * BEFORE the network call and again the moment the reply arrives, so a reply that came back
 * before a kill still lands on the next launch, under the key chosen at ask time; one asked but
 * not answered is NOT re-asked (a page appearing minutes later would surprise him) — he is told it
 * didn't finish. The page and the pick are written in one pass per day file: through the day page
 * standing on that day when there is one (its next save would otherwise overwrite the cards),
 * else under [DayLocks] with [com.toolsboox.ui.share.ShareActions.service] (never a bare
 * `CalendarDayService()` — the -322 lateinit-Moshi bug). Every failure says so (design §10).
 * One `synth[<id>]` log trail per run, the summon's own.
 */
@OptIn(DelicateCoroutinesApi::class)
object SynthListRun {

    const val MODE_LIST = "list"
    const val MODE_ARTICLE = "article"

    /** What a seat hands over: the rows as the list shows them, or the one article (its
     *  [articleText] the selection first, else the whole piece; [articleLabel] "Selection" or
     *  "This article"). [scope] names the list ("Starred", "Mail · Inbox") or the article's title. */
    data class Request(
        val kind: SynthSources.Kind,
        val scope: String,
        val items: List<SynthSources.Item>,
        val articleLabel: String = "This article",
        val articleText: String = "",
    )

    /** One run in flight per seat — the seat is inert ("❖ Already writing back…") until it ends. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The activity, for words said when the seat's own screen has gone (refreshed every foreground). */
    @Volatile private var activityRef: WeakReference<Activity>? = null

    fun remember(activity: Activity?) {
        if (activity != null) activityRef = WeakReference(activity)
    }

    fun owns(rec: PendingSummon): Boolean = rec.mode == MODE_LIST || rec.mode == MODE_ARTICLE

    private fun seat(kind: SynthSources.Kind) = when (kind) {
        SynthSources.Kind.MAIL -> "mail"
        SynthSources.Kind.ARTICLE -> "article"
        else -> "list"
    }

    // ── The seat ────────────────────────────────────────────────────────────────────────────

    /**
     * The ❖ seat's tap: nothing to read says so; a run in flight says so; else the engine picker,
     * then the run. [stillHere] answers, when the reply lands, whether he is still on the list he
     * synthesized with no article open — then the page opens by itself (0 taps); otherwise
     * "❖ Ready: <page>" with [Go to it].
     */
    fun start(fragment: ScreenFragment, req: Request, stillHere: (() -> Boolean)? = null) {
        val ctx = fragment.context ?: return
        remember(fragment.activity)
        val app = ctx.applicationContext
        val nothing = if (req.kind == SynthSources.Kind.ARTICLE) SynthSources.normalize(req.articleText).isEmpty()
            else req.items.isEmpty()
        if (nothing) {
            GlobalScope.launch(Dispatchers.Main) {
                door(app, null, if (req.kind == SynthSources.Kind.ARTICLE) SynthSources.NO_ARTICLE_TEXT else SynthSources.EMPTY_LIST, fragment)
            }
            return
        }
        if (seat(req.kind) in busy) {
            GlobalScope.launch(Dispatchers.Main) { door(app, null, SynthSources.BUSY, fragment) }
            return
        }
        SynthEngines.pick(ctx, SynthSources.picker(req.kind), present = { fragment.showModal(it) }) { engine ->
            run(app, req, engine, fragment, stillHere)
        }
    }

    private fun run(
        ctx: Context, req: Request, engine: SynthEngines.Engine, origin: ScreenFragment?, stillHere: (() -> Boolean)?,
    ) {
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(ctx)
        if (creds == null) {
            GlobalScope.launch(Dispatchers.Main) { door(ctx, null, SynthSources.NO_KEY, origin) }
            return
        }
        val seat = seat(req.kind)
        if (!busy.add(seat)) {
            GlobalScope.launch(Dispatchers.Main) { door(ctx, null, SynthSources.BUSY, origin) }
            return
        }
        val article = req.kind == SynthSources.Kind.ARTICLE
        val material = if (article) {
            val it = SynthSources.prep(req.items.firstOrNull() ?: SynthSources.Item(title = req.scope))
            SynthSources.Material(
                SynthSources.articleMaterial(req.articleLabel, it.title, it.feed, req.articleText), 1, 1, listOf(it))
        } else SynthSources.material(req.scope, req.items)
        if (material.sent == 0) {
            busy.remove(seat)
            GlobalScope.launch(Dispatchers.Main) { door(ctx, null, SynthSources.EMPTY_LIST, origin) }
            return
        }
        val date = LocalDate.now()
        val key = SynthSources.pageKey(System.currentTimeMillis())
        val name = SynthSources.pageName(engine.name, req.scope)
        val rec = PendingSummon(
            id = UUID.randomUUID().toString(), date = date.toString(), pageKey = key,
            mode = if (article) MODE_ARTICLE else MODE_LIST, startedAt = System.currentTimeMillis(),
            pageName = name,
            // A page born from a list has no voices row: the Correspondent writes its first turn
            // (synth-personas pin §I); "❖ Synth with ▸" on the page picks who answers the follow-ups.
            persona = SynthVoice.CORRESPONDENT,
            recordText = SynthSources.seedTurn(req.kind, req.scope, material.items),
            request = requestJson(req.kind, req.scope, engine.name, material.shown, material.items),
        )
        // Carried like any summon (SynthSummons.begin): the resume pass leaves it alone, and a ❖ on
        // its page while it lands says "Still writing back".
        SynthSummons.begin(rec)
        SynthSummons.log(rec, "start ${rec.mode} kind=${req.kind.id} scope=\"${req.scope}\" ${SynthSummons.voiceTag(rec.persona)} sent=${material.sent} " +
            "shown=${material.shown} engine=\"${engine.name}\" key=$key materialChars=${material.text.length}")
        GlobalScope.launch(Dispatchers.Main) {
            try {
                door(ctx, null, SynthSources.reading(req.kind, material.sent, material.shown), origin, progress = true)
                withContext(Dispatchers.IO) { SynthPending.put(ctx, rec) }
                // "From your Ledger" follows the material, per the picker's dial (its [tag · date]
                // brackets are not numeric, so they can't pass for a source).
                val extra = withContext(Dispatchers.IO) {
                    runCatching {
                        SynthEngines.ledgerContext(ctx,
                            com.toolsboox.plugin.chat.fi.LedgerCorpusService(com.toolsboox.ui.share.ShareActions.service(ctx), ctx),
                            com.toolsboox.ot.LedgerPaths.documentsRoot(ctx), material.text)
                    }.getOrDefault("")
                }
                val asking = rec.copy(stage = SynthPending.ASKING, content = material.text + extra, attempts = 1)
                withContext(Dispatchers.IO) { SynthPending.put(ctx, asking) }
                // The voice's words win where both define one ("❖ Writing back as Spiral…"); the
                // Correspondent's are SynthSources.WRITING_BACK, byte for byte.
                door(ctx, null, SynthWords.writingBack(asking.persona), origin, progress = true)
                val out = ask(ctx, creds, asking, engine.prompt + "\n\n" + SynthSources.INSTRUCTION)
                if (out is Outcome.Replied) {
                    val replied = asking.copy(stage = SynthPending.REPLIED, answer = out.answer)
                    withContext(Dispatchers.IO) { SynthPending.put(ctx, replied) }
                    land(ctx, replied, origin, stillHere)
                } else {
                    withContext(Dispatchers.IO) { SynthPending.remove(ctx, rec.id) }
                    val words = when (out) {
                        is Outcome.Failed -> SynthSources.couldntReach(out.reason)
                        Outcome.TimedOut -> SynthSources.TIMEOUT
                        else -> SynthSources.EMPTY_REPLY
                    }
                    door(ctx, rec, words, origin, SynthSources.TRY_AGAIN) { f -> run(ctx, req, engine, f, stillHere) }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                SynthSummons.warn(rec, "run threw", t)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { SynthPending.remove(ctx, rec.id) }
                }
                door(ctx, rec, SynthSources.couldntReach(plain(t, "the request didn't go through")), origin,
                    SynthSources.TRY_AGAIN) { f -> run(ctx, req, engine, f, stillHere) }
            } finally {
                dismissProgress()
                busy.remove(seat)
                SynthSummons.end(rec)
                SynthSummons.flush(ctx)
            }
        }
    }

    private sealed class Outcome {
        class Replied(val answer: String) : Outcome()
        class Failed(val reason: String) : Outcome()
        object TimedOut : Outcome()
        object Empty : Outcome()
    }

    /** One ask, wall-clock bounded at [SynthPending.REPLY_TIMEOUT_MS] on the AWAIT (SynthSummons.ask's
     *  rule: a blocking call inside withContext can't be timed out). */
    private suspend fun ask(
        ctx: Context, creds: Triple<String, String, String>, rec: PendingSummon, prompt: String,
    ): Outcome {
        val t0 = SystemClock.elapsedRealtime()
        SynthSummons.log(rec, "request sent mode=${rec.mode} ${SynthSummons.voiceTag(rec.persona)} contentChars=${rec.content.length}")
        SynthSummons.flush(ctx)
        val call = GlobalScope.async(Dispatchers.IO) {
            com.toolsboox.plugin.chat.nw.LedgerChatService().run(creds.first, creds.second, creds.third, prompt, rec.content)
        }
        val res = try {
            withTimeoutOrNull(SynthPending.REPLY_TIMEOUT_MS) { call.await() }
        } catch (t: Throwable) {
            if (t is CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
            SynthSummons.warn(rec, "reply failed: ${t.javaClass.simpleName}: ${t.message}", t)
            return Outcome.Failed(plain(t, "the request didn't go through"))
        }
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        return when (res) {
            null -> {
                call.cancel()
                SynthSummons.warn(rec, "reply timeout after %.1fs".format(secs))
                Outcome.TimedOut
            }
            is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok -> {
                val a = res.answer.trim()
                if (a.isBlank()) { SynthSummons.warn(rec, "reply empty after %.1fs".format(secs)); Outcome.Empty }
                else { SynthSummons.log(rec, "reply received chars=${a.length} in %.1fs".format(secs)); Outcome.Replied(a) }
            }
            is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err -> {
                SynthSummons.warn(rec, "reply failed after %.1fs: %s".format(secs, res.message))
                Outcome.Failed(res.message)
            }
        }
    }

    // ── The request, kept in the pending row ────────────────────────────────────────────────

    private class Sent(val kind: SynthSources.Kind, val scope: String, val engine: String, val shown: Int,
                       val items: List<SynthSources.Item>)

    private fun requestJson(kind: SynthSources.Kind, scope: String, engine: String, shown: Int,
                            items: List<SynthSources.Item>): String {
        val arr = JSONArray()
        for (it in items) arr.put(JSONObject().put("title", it.title).put("feed", it.feed).put("blurb", it.blurb)
            .put("link", it.link).put("entryId", it.entryId))
        return JSONObject().put("kind", kind.id).put("scope", scope).put("engine", engine).put("shown", shown)
            .put("items", arr).toString()
    }

    private fun parseRequest(s: String): Sent? = runCatching {
        val o = JSONObject(s)
        val a = o.getJSONArray("items")
        val items = (0 until a.length()).map { i ->
            val j = a.getJSONObject(i)
            SynthSources.Item(j.optString("title"), j.optString("feed"), j.optString("blurb"), j.optString("link"),
                j.optLong("entryId"))
        }
        Sent(SynthSources.Kind.of(o.optString("kind")), o.optString("scope"), o.optString("engine"),
            o.optInt("shown", items.size), items)
    }.getOrNull()?.takeIf { it.items.isNotEmpty() }

    // ── The landing ─────────────────────────────────────────────────────────────────────────

    /**
     * The reply → the page + the pick → the index, the transcript seed → the pending row goes →
     * the page opens or says it's ready. A landing that can't write keeps the REPLIED row (the
     * next foreground lands it; [Try again] lands it now) and says why.
     */
    suspend fun land(ctx: Context, rec: PendingSummon, origin: ScreenFragment?, stillHere: (() -> Boolean)?) {
        val date = runCatching { LocalDate.parse(rec.date) }.getOrNull()
        val sent = parseRequest(rec.request)
        if (date == null || sent == null) {
            withContext(Dispatchers.IO) { SynthPending.remove(ctx, rec.id) }
            SynthSummons.warn(rec, "landing failed: the request didn't survive (date=${rec.date})")
            door(ctx, rec, SynthSources.couldntSave("what was sent didn't survive"), origin)
            return
        }
        val cleaned = SynthSources.clean(rec.answer, sent.items.size)
        val cited = SynthSources.find(cleaned, sent.items.size)
        // THE PICK'S DAY is the day the reply first goes to land — fixed in the row BEFORE anything is
        // written, so a landing resumed on a later day (the pick written, the process gone before the
        // row was cleared) finds that pick where it stands instead of placing a second one on the new
        // day (review 2026-10-12).
        val pickDay = runCatching { LocalDate.parse(rec.pickDate) }.getOrNull() ?: LocalDate.now()
        if (rec.pickDate.isBlank()) withContext(Dispatchers.IO) {
            runCatching { SynthPending.put(ctx, rec.copy(pickDate = pickDay.toString())) }
                .onFailure { SynthSummons.warn(rec, "couldn't keep the pick's day", it) }
        }
        try {
            val plan = withContext(Dispatchers.Default) { plan(rec, sent, cleaned, cited, date, pickDay) }
            val landed = write(ctx, plan)
            withContext(Dispatchers.IO) {
                SynthPageStore.add(ctx, rec.pageName, date, rec.pageKey)
                // The seed turn + the reply, so ❖ Synth on the page goes on as the Correspondent
                // knowing what was read. Device-local (another device reads the page itself).
                // Recorded only now the cards are on disk, and once (the summon's rule: a landing
                // again after a kill finds the turn already there and doesn't double it).
                SynthCorrespondence.record(ctx, date, rec.pageKey, emptyList(), rec.recordText, cleaned, resumed = true,
                    persona = rec.persona)
                SynthPending.remove(ctx, rec.id)
            }
            SynthSummons.log(rec, "landed page=${rec.pageKey} first=${landed?.firstKey} y=%.0f cards=${plan.cards.size} cited=${cited.size} pick=${plan.pick?.date}"
                .format(landed?.y ?: 0f))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            SynthSummons.warn(rec, "landing failed — the reply is kept", t)
            // A page that won't read (day-file guard) is never written over: the reply stays owed —
            // the REPLIED row lands on a later foreground, or now with Try again — and the words
            // say which page, the summon's own "the reply is kept" line.
            val words = (t as? com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException)
                ?.let { SynthWords.replyKept(rec.pageName, it.whichPage) }
                ?: SynthSources.couldntSave(plain(t, "the page couldn't be written"))
            door(ctx, rec.copy(stage = SynthPending.REPLIED), words,
                origin, SynthSources.TRY_AGAIN, keepRow = true) { f ->
                GlobalScope.launch(Dispatchers.Main) { relandNow(ctx, rec, f) }
            }
            SynthSummons.flush(ctx)
            return
        }
        // Still on the list he synthesized, nothing open, the app in front: the page, 0 taps.
        val o = origin?.takeIf { it.isAdded && it.isResumed && it.view != null }
        val here = o != null && SynthSummons.foreground && runCatching { stillHere?.invoke() == true }.getOrDefault(false)
        if (here && o != null) {
            SynthSummons.log(rec, "opening the page — he is still on the list")
            dismissProgress()
            runCatching { CalendarNavigator.toDayNote(o, date, rec.pageKey) }
                .onFailure { readyDoor(ctx, rec, origin) }
        } else readyDoor(ctx, rec, origin)
        SynthSummons.flush(ctx)
    }

    private suspend fun relandNow(ctx: Context, rec: PendingSummon, f: ScreenFragment) {
        val now = withContext(Dispatchers.IO) { SynthPending.get(ctx, rec.id) } ?: rec
        if (!SynthSummons.begin(now)) return
        try { land(ctx, now, f, null) } finally { SynthSummons.end(now) }
    }

    /** "❖ Ready: <page>" [Go to it] — or [words] in its place (the next foreground's one message,
     *  every owed line in it, the button opening [rec]'s page). */
    private suspend fun readyDoor(ctx: Context, rec: PendingSummon, origin: ScreenFragment?,
                                  words: String = SynthSources.ready(rec.pageName)) {
        val date = runCatching { LocalDate.parse(rec.date) }.getOrDefault(LocalDate.now())
        door(ctx, rec, words, origin, SynthSources.GO_TO_IT) { f ->
            CalendarNavigator.toDayNote(f, date, rec.pageKey)
        }
    }

    /** The cards and the pick, faces rendered (off the main thread). */
    private fun plan(rec: PendingSummon, sent: Sent, cleaned: String, cited: List<Int>, date: LocalDate,
                     pickDay: LocalDate): SynthListLanding.Plan {
        val name = rec.pageName
        val home = SynthSources.pageLink(rec.date, rec.pageKey)
        val chunks = SynthPending.replyChunks(cleaned)
        val cards = mutableListOf<SynthListLanding.Card>()
        // 1. The reply's stickies — the voice's label (the Correspondent's on a page born from a list:
        //    it has no voices row; synth-personas pin §I), so threading, isReply and alreadyLanded hold.
        val replyLabel = SynthVoice.label(rec.persona)
        val replySource = if (SynthVoice.isCorrespondent(rec.persona)) name else name + " · " + SynthVoice.shortName(rec.persona)
        for (chunk in chunks) cards.add(sticky("❖ $chunk", replySource, replyLabel, home, chunk))
        // 2. A card per cited article, ascending, at most twelve.
        for (n in cited.take(SynthSources.MAX_CARDS).sorted())
            sent.items.getOrNull(n - 1)?.let { cards.add(sourceCard(n, it, sent.kind)) }
        // 3. One Sources card: every article sent, each line its link and id.
        val sc = SynthSources.sourcesCard(sent.scope, sent.items, sent.shown, cited.isEmpty())
        cards.add(sticky(sc.face, name, SynthSources.SOURCES_PREFIX + sent.scope, home, sc.cardText))
        // The ❖ pick for All Picks — the drive-by breadcrumb's face (CalendarDayFragment's sticky).
        val pickText = SynthSources.pickText(cleaned)
        val bmp = StickyCardRenderer.render(pickText, source = name, W = 760)
        val (data, w, h) = PickingsPlacement.encodeFace(bmp, treatment = false)
        bmp.recycle()
        val pick = SynthListLanding.Pick(pickDay, data, w, h, home, "❖ $name", pickText)
        return SynthListLanding.Plan(date, rec.pageKey, cards, chunks, pick, replyLabel)
    }

    private fun sticky(face: String, source: String, label: String, link: String, cardText: String): SynthListLanding.Card {
        val bmp = StickyCardRenderer.render(face, source = source, W = SynthSummons.CARD_RENDER_W)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
        val aspect = bmp.height.toFloat() / bmp.width
        bmp.recycle()
        return SynthListLanding.Card(android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP),
            aspect, com.toolsboox.plugin.calendar.ot.SynthLanding.CARD_W, label, link, cardText)
    }

    /** A cited article's card: the link-gram face (LinkCardRenderer — title, excerpt, "↗ Feed · host",
     *  the address), label `[n] Title`, its own link, its feed for "Go to feed". */
    private fun sourceCard(n: Int, item: SynthSources.Item, kind: SynthSources.Kind): SynthListLanding.Card {
        val it = SynthSources.prep(item)
        val mail = it.link.startsWith("mail://")
        val text = SynthSources.sourceCard(n, it)
        val bmp = LinkCardRenderer.render(
            url = if (mail) "" else it.link, title = it.title,
            kind = when { mail -> "mail"; kind == SynthSources.Kind.EPISODES -> "listen"; else -> "read" },
            sourceName = it.feed, excerpt = it.blurb)
        val (data, w, h) = PickingsPlacement.encodeFace(bmp, treatment = true)
        bmp.recycle()
        return SynthListLanding.Card(data, h.toFloat() / w.coerceAtLeast(1), com.toolsboox.plugin.calendar.ot.SynthLanding.CARD_W,
            text.label, it.link, text.cardText, sourceFeed = if (mail) "" else it.feed, edgeBaked = true)
    }

    /** Each day the plan touches, one pass: the day page standing on it takes the cards in memory
     *  (its next save would otherwise write them away), else the day file under its lock. Every
     *  day is read FIRST: one that won't read refuses the whole landing before anything is written
     *  ([SynthListLanding.refuseUnreadable]) — never the page's cards with the pick's day refused. */
    private suspend fun write(ctx: Context, plan: SynthListLanding.Plan): SynthLanded? {
        withContext(Dispatchers.IO) {
            SynthListLanding.refuseUnreadable(com.toolsboox.ui.share.ShareActions.service(ctx),
                com.toolsboox.ot.LedgerPaths.documentsRoot(ctx), plan)
        }
        var landed: SynthLanded? = null
        for (d in SynthListLanding.days(plan)) {
            val m = writeDay(ctx, d, plan)
            if (d == plan.date) landed = m
        }
        return landed
    }

    private suspend fun writeDay(ctx: Context, d: LocalDate, plan: SynthListLanding.Plan): SynthLanded? {
        val service = com.toolsboox.ui.share.ShareActions.service(ctx)
        val root = com.toolsboox.ot.LedgerPaths.documentsRoot(ctx)
        // The day page standing on [d] places against ITS copy (unsaved ink and a reply it landed a
        // moment ago included) and saves it — the copy its own next save would write anyway.
        // ON DISK before the caller clears its row (the summon's rule, 2026-10-12 review): the
        // page's own save is fire-and-forget, so the run awaits its own — even when nothing new was
        // minted, since a landing again may be finishing cards an unsaved try left in memory.
        val live = withContext(Dispatchers.Main) {
            val host = awaitHost(d) ?: return@withContext null
            val day = host.synthDay() ?: return@withContext null
            val minted = SynthListLanding.mint(day, d, plan)
            if (minted.elements.isNotEmpty()) host.addSynthElements(minted.elements, save = false)
            if (!host.saveSynthLanding()) throw java.io.IOException("the page didn't save")
            minted
        }
        if (live != null) return live.landed
        val minted = withContext(Dispatchers.IO) {
            DayLocks.withDay(d) {
                // The writer's door (day-file guard): the day, a fresh one when there is none — and
                // a file that is there but won't read THROWS UnreadableDayException, never saved
                // over. [land] keeps the reply's row for it and says which page won't read.
                val day = service.loadForWriteOrThrow(root, d, null, Locale.getDefault())
                val m = SynthListLanding.mint(day, d, plan)
                if (m.elements.isNotEmpty()) {
                    day.imageElements.addAll(m.elements)
                    service.save(root, d, day)
                }
                m
            }
        }
        // A day page that came up on this date meanwhile re-reads — never saves.
        withContext(Dispatchers.Main) {
            CalendarDayFragment.liveOn(d)?.let { runCatching { it.onExternalGramPlaced(plan.key) } }
        }
        return minted.landed
    }

    /** The day page on [date] that can take cards in memory — waiting out a reload in flight. */
    private suspend fun awaitHost(date: LocalDate): CalendarDayFragment? {
        repeat(40) {
            val live = CalendarDayFragment.liveOn(date)
            if (live != null && live.canTakeSynthLanding(date)) return live
            if (live?.isSynthHostLoading(date) != true) return null
            delay(200)
        }
        return null
    }

    // ── The next foreground ─────────────────────────────────────────────────────────────────

    /**
     * [SynthSummons.onForeground]'s words for this run's rows — the row cleared — or null when the
     * row is landed instead ([resume]): the owed notice ("❖ Ready: <page>" among them), or — for a
     * run the app died in the middle of, whatever stage it reached — that it didn't finish. Never
     * re-asked: a page appearing minutes later would surprise him. The pass joins these with every
     * other summon's into ONE message (the 2026-10-12 review), [sayReady]'s when one is the ready.
     */
    suspend fun owedWords(ctx: Context, rec: PendingSummon, action: SynthPending.Action): String? {
        val words = when (action) {
            SynthPending.Action.WAIT, SynthPending.Action.LAND -> return null
            SynthPending.Action.SAY -> { SynthSummons.log(rec, "saying what was owed"); rec.notice }
            else -> {
                SynthSummons.warn(rec, "resumed: the app closed mid-run (${action.name.lowercase()}) — not asked again")
                SynthSources.killed(rec.pageName)
            }
        }
        withContext(Dispatchers.IO) { SynthPending.remove(ctx, rec.id) }
        return words
    }

    /** [rec] owes "❖ Ready: <page>" — the message that carries [Go to it]. */
    fun isReady(rec: PendingSummon): Boolean =
        owns(rec) && rec.stage == SynthPending.NOTICE && rec.notice == SynthSources.ready(rec.pageName)

    /** The next foreground's one message, [words] (every owed line), with [Go to it] for [rec]'s page. */
    suspend fun sayReady(ctx: Context, rec: PendingSummon, words: String) =
        readyDoor(ctx, rec.copy(stage = ""), null, words)

    /** [SynthSummons.onForeground]'s branch for this run's rows: a reply that came back before the
     *  app closed is landed; anything else is [owedWords]' (said alone when called directly). */
    suspend fun resume(ctx: Context, rec: PendingSummon, action: SynthPending.Action) {
        if (action == SynthPending.Action.LAND) {
            SynthSummons.log(rec, "resumed: landing the reply that came back before the app closed")
            land(ctx, rec, null, null)
            return
        }
        val words = owedWords(ctx, rec, action) ?: return
        if (isReady(rec)) sayReady(ctx, rec, words) else door(ctx, null, words, null)
    }

    // ── Saying it ───────────────────────────────────────────────────────────────────────────

    /** The activity last seen in front (refreshed every foreground), or null once it has gone. */
    fun activity(): Activity? = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** The screen to speak on: the seat's own while it stands, else the one in front. */
    private fun screen(origin: ScreenFragment?): ScreenFragment? = screenInFront(origin, null)

    /**
     * [origin] while it stands, else the screen in front of [act] (else of the activity last seen)
     * — for a door, a dialog or a menu that outlived the page that raised it (the day page rebuilds
     * often: three times in 75 s in the -322 logcat). Null when no screen stands.
     */
    fun screenInFront(origin: ScreenFragment?, act: Activity?): ScreenFragment? {
        origin?.takeIf { it.isAdded && it.isResumed && it.view != null }?.let { return it }
        val a = (act ?: activityRef?.get()) as? FragmentActivity ?: return null
        if (a.isFinishing || a.isDestroyed) return null
        val navHost = a.supportFragmentManager.primaryNavigationFragment ?: return null
        return navHost.childFragmentManager.fragments.lastOrNull { it.isResumed && it.view != null } as? ScreenFragment
    }

    /**
     * The run's progress line ("❖ Reading 23 articles…", "❖ Writing back…") stays up until the next
     * word replaces it — the engine can take two minutes, and a LENGTH_LONG bar gone after three
     * seconds looked, on e-ink, as if nothing had happened (review 2026-10-12; iOS holds its line
     * the same way). Main thread only.
     */
    private var progressBar: WeakReference<com.google.android.material.snackbar.Snackbar>? = null

    private fun dismissProgress() {
        progressBar?.get()?.dismiss()
        progressBar = null
    }

    /** A reason he can read: the throwable's own words when it has some, plain words for the
     *  network's usual failures, else [fallback] — never a class name (review 2026-10-12). */
    private fun plain(t: Throwable, fallback: String): String = when (t) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "no connection"
        is java.net.SocketTimeoutException -> "the connection timed out"
        else -> t.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    /**
     * Never a silent nothing: [words] on the screen in front, with [action] as its one button
     * when given — and when the app is away, OWED ([rec] kept as a notice row, said on the next
     * foreground). [progress] lines are only ever shown, never owed. [keepRow]: the row stays as it
     * is (a reply waiting to land is its own notice).
     */
    private suspend fun door(
        ctx: Context, rec: PendingSummon?, words: String, origin: ScreenFragment?,
        actionLabel: String? = null, progress: Boolean = false, keepRow: Boolean = false,
        action: ((ScreenFragment) -> Unit)? = null,
    ) {
        // Any word said replaces the progress line (a new progress line included).
        dismissProgress()
        val s = screen(origin)
        val v = s?.view
        if (s != null && v != null) {
            val bar = com.google.android.material.snackbar.Snackbar.make(v, words, when {
                progress -> com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                actionLabel != null -> DOOR_MS
                else -> com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            })
            if (actionLabel != null && action != null) bar.setAction(actionLabel) {
                val f = screen(s) ?: s
                runCatching { action(f) }.onFailure { Timber.w(it, "synth-list door action failed") }
            }
            s.snackAnchor?.let { bar.setAnchorView(it) }
            bar.show()
            if (progress) progressBar = WeakReference(bar)
            rec?.let { SynthSummons.log(it, "said: $words") }
            return
        }
        if (progress) return
        if (SynthSummons.foreground) {
            android.widget.Toast.makeText(ctx.applicationContext, words, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (rec != null && !keepRow) {
            withContext(Dispatchers.IO) {
                SynthPending.put(ctx, rec.copy(stage = SynthPending.NOTICE, notice = words, content = "", answer = "", request = ""))
            }
            SynthSummons.log(rec, "owed (app in background): $words")
        }
    }

    /** A door with a button stays long enough for a pen to reach it on e-ink. */
    private const val DOOR_MS = 8_000

    // ── The page's links: taps, the hold menu, the "Go to source" arm ───────────────────────

    /** Every card on [pageKey]'s run (the page and its "#n" continuations). */
    private fun onRun(day: CalendarDay, pageKey: String): List<ImageElement> {
        val base = pageKey.substringBefore('#')
        return day.imageElements.filter { it.page == base || it.page.startsWith("$base#") }
    }

    /**
     * The Sources card's resolver lines for [pageKey], or "" — only on a Synthesize page. Looked up
     * across the page's RUN, the page and its "#n" sub-pages ([onRun]): the Sources card stands on
     * page one's right-hand column, or — on a page that already held something — last in the one
     * column, maybe a sub-page or two on; a reply on page one finds it either way. Both apps flow a
     * full Synthesize page onto "#n" sub-pages (iOS's "<name> · page N" continuation documents are
     * gone, merge 2026-10-16), so a run's numbers are always its own and never another
     * conversation's (a card carried in from another page reads this page's numbers; a source
     * card's id rides only when its own link agrees, [entryIdFor]).
     */
    fun sourcesOn(day: CalendarDay, pageKey: String?): String {
        if (pageKey == null) return ""
        if (!SynthPageStore.isSynth(pageKey.substringBefore('#'))) return ""
        return onRun(day, pageKey).firstOrNull { SynthSources.role(it.sourceLabel) == SynthSources.Role.Sources }
            ?.cardText.orEmpty()
    }

    /**
     * What [el] offers as ↗ rows on its Synthesize page (empty anywhere else) — the tap's and the
     * hold menu's one answer. A source card's OWN LINK WINS: a "[3] Title" card carried onto another
     * synthesis offers and opens ITS article, never that run's [3], and one on a run whose Sources
     * card is gone still opens; the id rides only when the run's line vouches for this very link
     * (iOS SynthListRun.rows, case for case).
     */
    internal fun rowsFor(day: CalendarDay, pageKey: String?, el: ImageElement): List<SynthSources.Row> {
        if (pageKey == null || !SynthPageStore.isSynth(pageKey.substringBefore('#'))) return emptyList()
        val role = SynthSources.role(el.sourceLabel, el.sourceLink, el.page)
        if (role == SynthSources.Role.Other) return emptyList()
        val sources = SynthSources.parseSources(sourcesOn(day, pageKey))
        val rows = SynthSources.rows(role, el.cardText, sources)
        if (role is SynthSources.Role.Card && el.sourceLink.isNotBlank()) {
            val run = rows.firstOrNull()
            if (run != null && run.source.link == el.sourceLink) return listOf(run)
            val own = ownSource(el, role.n)
            return listOf(SynthSources.Row(SynthSources.rowLabel(own), own))
        }
        return rows
    }

    /** A source card's own article: its link, its line's words ("Title — Feed"), no id. */
    private fun ownSource(el: ImageElement, n: Int): SynthSources.Source {
        val first = el.cardText.substringBefore('\n')
        val text = (if (first.startsWith("[")) first.substringAfter(' ', "") else "")
            .ifEmpty { el.sourceLabel.substringAfter(' ', "") }
        return SynthSources.Source(n, text, el.sourceLink, 0)
    }

    private fun open(fragment: ScreenFragment, s: SynthSources.Source) {
        val title = s.text.substringBeforeLast(" — ", s.text)
        val feed = if (" — " in s.text) s.text.substringAfterLast(" — ") else ""
        ArticleOpen.open(fragment, s.link, s.entryId, title, feed)
    }

    private fun showRows(fragment: ScreenFragment, title: String, rows: List<SynthSources.Row>) {
        // The hold menu's "All N sources…" can outlive the page that raised it — the screen in front.
        val f = screenInFront(fragment, fragment.activity) ?: return
        val ctx = f.context ?: return
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(title)
            .setItems(rows.map { it.label }.toTypedArray()) { _, i -> rows.getOrNull(i)?.let { open(f, it.source) } }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        f.showModal(dialog)
    }

    /**
     * A TAP on a Synthesize page (design §4): a source card opens its article (1 tap); a reply
     * card that cites offers its ↗ rows, the Sources card all of them (2 taps). Anything else —
     * and a reply citing nothing — is not consumed, so the page behaves as it always did.
     */
    fun onTap(fragment: ScreenFragment, day: CalendarDay, pageKey: String, el: ImageElement): Boolean {
        val rows = rowsFor(day, pageKey, el)
        if (rows.isEmpty()) return false
        when (SynthSources.role(el.sourceLabel, el.sourceLink, el.page)) {
            // Its own article ([rowsFor]: the card's own link wins; the id rides only when the
            // Sources card agrees, and ArticleOpen checks it anyway).
            is SynthSources.Role.Card -> open(fragment, rows.first().source)
            SynthSources.Role.Sources -> showRows(fragment, el.sourceLabel, rows)
            else -> showRows(fragment, SynthPageStore.nameOf(fragment.requireContext(), pageKey.substringBefore('#')), rows)
        }
        return true
    }

    /** The same rows, heading the hold menu's "knowing" group. The Sources card's can be forty, so
     *  past six it offers the first five and "All N sources…" (a menu that overruns the screen
     *  hides its own rows — the -66 lesson). */
    fun holdRows(fragment: ScreenFragment, day: CalendarDay, pageKey: String?, el: ImageElement): List<LedgerContextMenu.Item> {
        val rows = rowsFor(day, pageKey, el)
        if (rows.isEmpty()) return emptyList()
        val items = rows.map { r -> LedgerContextMenu.Item(r.label) { open(fragment, r.source) } }
        if (items.size <= 6) return items
        return items.take(5) + LedgerContextMenu.Item("↗ All ${rows.size} sources…") { showRows(fragment, el.sourceLabel, rows) }
    }

    /** "↩ Go to source" on an http card: its server id when the page's Sources card knows it. */
    fun entryIdFor(day: CalendarDay?, pageKey: String?, el: ImageElement): Long {
        if (day == null) return 0
        val role = SynthSources.role(el.sourceLabel) as? SynthSources.Role.Card ?: return 0
        return SynthSources.parseSources(sourcesOn(day, pageKey)).firstOrNull { it.n == role.n && it.link == el.sourceLink }
            ?.entryId ?: 0
    }

    /** The title a source card's article goes by — its label without the "[n] ". */
    fun titleFor(el: ImageElement): String =
        if (SynthSources.role(el.sourceLabel) is SynthSources.Role.Card) el.sourceLabel.substringAfter("] ")
        else el.sourceLabel.ifBlank { "Source" }

    // ── Follow-ups: ❖ Synth on a page that carries a Sources card ───────────────────────────

    /** A summon's reply on such a page, cleaned against the page's numbers (an invented [n] goes). */
    fun cleanReply(rec: PendingSummon, answer: String): String {
        if (rec.sources.isBlank() || owns(rec)) return answer
        val top = SynthSources.parseSources(rec.sources).maxOfOrNull { it.n } ?: return answer
        return SynthSources.clean(answer, top)
    }

    /** After such a reply landed: a card for each number it cites that has none on the page's run
     *  yet, placed under what [onPage] holds — the page the reply's last card went to. THROWS
     *  [com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException] when the page's
     *  day won't read (nothing is written over it): the caller keeps the reply's record so the cards
     *  land on a later pass and says so (2026-10-16 review, item 4 — swallowed here, the record was
     *  cleared and the cards never came). Any other failure is logged; the reply is on the page. */
    suspend fun followUpCards(ctx: Context, rec: PendingSummon, answer: String, onPage: String = rec.pageKey) {
        if (rec.sources.isBlank() || owns(rec)) return
        val date = runCatching { LocalDate.parse(rec.date) }.getOrNull() ?: return
        val sources = SynthSources.parseSources(rec.sources)
        val top = sources.maxOfOrNull { it.n } ?: return
        val byN = sources.associateBy { it.n }
        val cited = SynthSources.find(answer, top).filter { it in byN }
        if (cited.isEmpty()) return
        runCatching {
            val service = com.toolsboox.ui.share.ShareActions.service(ctx)
            val root = com.toolsboox.ot.LedgerPaths.documentsRoot(ctx)
            // The page standing on that day knows best (a card it took a moment ago may not be saved yet).
            val live = withContext(Dispatchers.Main) {
                CalendarDayFragment.liveOn(date)?.takeIf { it.canTakeSynthLanding(date) }?.synthDay()
                    ?.let { d -> onRun(d, rec.pageKey) }
            }
            // The editor's door: a day that is there but won't read THROWS (never read as "no
            // cards here", which cleared the record with the cards still missing).
            val els = live ?: withContext(Dispatchers.IO) { service.loadToEdit(root, date) }
                ?.let { onRun(it, rec.pageKey) } ?: return
            val have = els.mapNotNull { (SynthSources.role(it.sourceLabel) as? SynthSources.Role.Card)?.n }.toSet()
            val room = (SynthSources.MAX_CARDS - have.size).coerceAtLeast(0)
            val fresh = cited.filter { it !in have }.take(room).sorted()
            if (fresh.isEmpty()) return
            val cards = withContext(Dispatchers.Default) {
                fresh.mapNotNull { n ->
                    val s = byN[n] ?: return@mapNotNull null
                    val title = s.text.substringBeforeLast(" — ", s.text)
                    val feed = if (" — " in s.text) s.text.substringAfterLast(" — ") else ""
                    sourceCard(n, SynthSources.Item(title, feed, "", s.link, s.entryId), SynthSources.Kind.FEEDS)
                }
            }
            write(ctx, SynthListLanding.Plan(date, onPage, cards, emptyList(), null))
            SynthSummons.log(rec, "follow-up: cards for ${fresh.joinToString(",")}")
        }.onFailure {
            if (it is CancellationException || it is com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException) throw it
            SynthSummons.warn(rec, "follow-up cards failed", it)
        }
    }
}
