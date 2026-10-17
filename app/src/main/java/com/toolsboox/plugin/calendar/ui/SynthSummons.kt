package com.toolsboox.plugin.calendar.ui

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.ot.PendingSummon
import com.toolsboox.plugin.calendar.ot.SynthCorrespondence
import com.toolsboox.plugin.calendar.ot.SynthFace
import com.toolsboox.plugin.calendar.ot.SynthLanded
import com.toolsboox.plugin.calendar.ot.SynthLanding
import com.toolsboox.plugin.calendar.ot.SynthPending
import com.toolsboox.plugin.calendar.ot.SynthPersonas
import com.toolsboox.plugin.calendar.ot.SynthVoice
import com.toolsboox.plugin.calendar.ot.SynthWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * THE ❖ SUMMON'S BACK OFFICE — what carries a reply from "❖ Writing back…" to a card he can see,
 * whichever page (or none) is standing when it comes back.
 *
 * Michael, 2026-10-12, B6 -322: "I just used the synth button on the synthesize page. It says
 * reading. It says writing back. It does not put in it's write back pic." The proven chain (see
 * [SynthPending]'s header for the logcat): the reply reached a fragment the page turn had already
 * replaced → the detached landing → a Moshi-less day service → nothing written, nothing said.
 *
 * The landing ladder now, one for every summon (the page summon, the sticky turn, and a summon
 * resumed after the app was killed):
 *  1. THE LIVE PAGE on that day — the summoning fragment if it still stands, otherwise whichever
 *     day page replaced it (every page turn rebuilds the fragment; that is where he is looking).
 *     A page mid-reload is waited for (its reload would otherwise overwrite the card). The card is
 *     drawn through the element-insert repaint and brought into view, sub-page included.
 *  2. THE DAY FILE — no page standing on that day: load → place → save under the day lock with a
 *     service that HAS its Moshi, then a resumed day page is told to re-read.
 *  3. Either way the words say where it went; a failure says so. Never a silent nothing.
 *
 * Every summon leaves one log trail, `synth[<id>] …` (Timber INFO/WARN, kept by the diagnostics
 * ring — Settings › Diagnostics — and flushed to its log.txt at each turn so a killed process
 * still leaves it): start, material length, request sent, reply received/failed/timeout, landed
 * where (page key, y).
 */
@OptIn(DelicateCoroutinesApi::class)
object SynthSummons {

    /** MainActivity is resumed — words can be shown now rather than owed. Set on Main. */
    @Volatile
    var foreground = false

    /** Summons this process is carrying right now; anything else in the pending file is an orphan
     *  of a process that died (killed, rebooted, reinstalled) and is resumed on the next foreground. */
    val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The records behind [inFlight] — what [stillWriting] asks of a new summon's page. */
    private val carried = ConcurrentHashMap<String, PendingSummon>()

    /** This process takes [rec] on. False = someone already carries it (a racing resume pass). */
    fun begin(rec: PendingSummon): Boolean {
        if (!inFlight.add(rec.id)) return false
        carried[rec.id] = rec
        return true
    }

    fun end(rec: PendingSummon) {
        carried.remove(rec.id)
        inFlight.remove(rec.id)
    }

    /** "❖ Still writing back on Synthesize…" when a summon on [pageKey] of [date] is on its way —
     *  the tap that would ask about the same unread ink again is refused; null when the page is free. */
    fun stillWriting(date: LocalDate, pageKey: String): String? =
        SynthPending.carrying(carried.values, date.toString(), pageKey)?.let { SynthWords.stillWriting(placeFor(it)) }

    /** "❖ No AI key set — Settings › Ask my Ledger." */
    fun noKeyWords(): String = SynthWords.noKey(SynthWords.AI_KEY_ROOM_ANDROID)

    /** [missing] = the page's voice when it isn't on this device (the Correspondent answers). */
    fun newRecord(date: LocalDate, pageKey: String, mode: String, pageName: String, seen: List<String>,
                  persona: String = SynthVoice.CORRESPONDENT, missing: String = "") =
        PendingSummon(
            id = UUID.randomUUID().toString(), date = date.toString(), pageKey = pageKey, mode = mode,
            startedAt = System.currentTimeMillis(), pageName = pageName, seen = seen,
            persona = SynthVoice.normalize(persona),
            missing = if (SynthVoice.isCorrespondent(missing)) "" else missing,
        )

    /** "voice=Spiral" — the log trail's word for who answers. */
    fun voiceTag(persona: String): String = "voice=" + SynthVoice.shortName(SynthVoice.normalize(persona))

    /**
     * The record's voice, as it stands on this device at ask time: a rename is followed; a voice
     * that is gone (deleted since the summon, emptied, or never here) is saved as the Correspondent
     * BEFORE the call — the ask and the card then honestly say who wrote it — with the gone voice on
     * the record ([PendingSummon.missing]), so the landing words say why even after a re-ask or a
     * restart. When the voice changes, the transcript riding the request is relabelled for the
     * voice now asked: the turns that were "You replied" are the gone voice's again.
     */
    private fun voicedForAsk(ctx: Context, rec: PendingSummon): PendingSummon {
        if (SynthVoice.isCorrespondent(rec.persona)) return if (rec.persona.isEmpty()) rec else rec.copy(persona = "")
        val rows = runCatching { com.toolsboox.plugin.chat.nw.PersonaStore.rows(ctx) }.getOrNull()
            ?: return rec
        val now = SynthPersonas.voice(rows, rec.persona)
        return when {
            now.missing.isNotEmpty() -> {
                warn(rec, "voice ${SynthVoice.shortName(rec.persona)} isn't on this device — the Correspondent answers")
                relabelled(ctx, rec, SynthVoice.CORRESPONDENT).copy(persona = SynthVoice.CORRESPONDENT, missing = rec.persona)
            }
            now.persona != rec.persona -> relabelled(ctx, rec, now.persona).copy(persona = now.persona)
            else -> rec
        }
    }

    /** [rec]'s request with its transcript tail rebuilt as [voice] reads it — or [rec] unchanged
     *  when it carries no tail, or the tail can't be rebuilt. */
    private fun relabelled(ctx: Context, rec: PendingSummon, voice: String): PendingSummon {
        if (rec.tail.isBlank() || rec.tailTurns <= 0 || !rec.content.contains(rec.tail)) return rec
        val date = runCatching { LocalDate.parse(rec.date) }.getOrNull() ?: return rec
        val fresh = runCatching { SynthCorrespondence.transcriptTail(ctx, date, rec.pageKey, rec.tailTurns, voice) }
            .getOrNull()
        if (fresh.isNullOrBlank() || fresh == rec.tail) return rec
        log(rec, "transcript relabelled for ${voiceTag(voice)}")
        return rec.copy(content = rec.content.replaceFirst(rec.tail, fresh), tail = fresh)
    }

    /** "❖ Written back on …" / "❖ Spiral wrote back on …" / "❖ Spiral isn't on this device — …". */
    private fun landedWords(rec: PendingSummon, place: String, continuedOn: Int?): String =
        if (!SynthVoice.isCorrespondent(rec.missing)) SynthWords.voiceMissing(rec.missing, place)
        else SynthWords.writtenBack(place, continuedOn, rec.persona)

    // ── The log trail ───────────────────────────────────────────────────────────────────────

    private fun tag(rec: PendingSummon) = "synth[${rec.id.take(8)}]"

    fun log(rec: PendingSummon, msg: String) = Timber.i("%s %s", tag(rec), msg)

    fun warn(rec: PendingSummon, msg: String, t: Throwable? = null) =
        if (t != null) Timber.w(t, "%s %s", tag(rec), msg) else Timber.w("%s %s", tag(rec), msg)

    /** The ring to disk — a reboot mid-summon (the B6, 13:30:50) must not take the trail with it. */
    fun flush(ctx: Context) {
        runCatching { com.toolsboox.ot.Diagnostics.flush(ctx) }
    }

    // ── The pieces every summon shares ──────────────────────────────────────────────────────

    /** The AI creds — CalendarDayFragment.aiCreds' read, context-only so a summon resumed with no
     *  page standing can ask. */
    fun creds(ctx: Context): Triple<String, String, String>? {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx, "ledger_chat_encrypted_prefs",
            androidx.security.crypto.MasterKey.Builder(ctx)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val provider = prefs.getString("ledger_chat_provider", "anthropic") ?: "anthropic"
        val key = prefs.getString("ledger_chat_api_key_$provider", "")?.trim().orEmpty()
        val default = if (provider == "openai") "gpt-4o" else "claude-sonnet-5"
        val chatModel = prefs.getString("ledger_chat_model_$provider", default) ?: default
        // OCR can run on a different (e.g. faster/cheaper) model than chat. An override set in the
        // OCR-model picker wins, but only for the matching provider family; blank = use the chat model.
        val ocrOverride = com.toolsboox.ui.plugin.OcrModel.override(ctx, provider)
        val model = ocrOverride ?: chatModel
        return if (key.isBlank()) null else Triple(provider, key, model)
    }

    /**
     * REFERENCE PAGES for a prompt (Michael, 09-13) — the sites' pages closest in meaning to
     * [material], as a block the engine can cite by exact URL. Empty without a catalogue or an
     * embedding key. (CalendarDayFragment.siteReferenceBlock's body, context-only for resume.)
     *
     * minScore 0.30 is NOT iOS's 0.50 on purpose: the two apps score with different embeddings —
     * OpenAI text-embedding-3-small here, Apple's on-device NLEmbedding averages on iOS — and a
     * cosine means something different on each scale. 0.50 here would drop most real matches.
     */
    fun siteReferenceBlock(ctx: Context, material: String): String {
        val sites = com.toolsboox.plugin.calendar.nw.SiteStore.all(ctx).filter { it.url.trim().startsWith("http") }
        if (sites.isEmpty() || material.isBlank()) return ""
        val hits = runCatching {
            com.toolsboox.plugin.calendar.nw.SiteLinker.topic(ctx, material.take(1500), sites, n = 40, minScore = 0.30)
        }.getOrDefault(emptyList())
        if (hits.isEmpty()) return ""
        return "\n\nREFERENCE PAGES ON MY SITES — when the answer touches one of these, cite it by its exact URL " +
            "on its own line (each cited URL becomes a link gram on the page); never invent a URL:\n" +
            hits.joinToString("\n") { "- ${it.title} — ${it.url}" }
    }

    /** Why an ask came back empty-handed — or the reply. */
    sealed class Asked {
        data class Reply(val text: String) : Asked()
        data class Failed(val failure: SynthPending.Failure) : Asked()
    }

    /**
     * The REFERENCE PAGES for [rec], within [SynthPending.REFERENCES_BUDGET_MS] (both apps — iOS
     * gave its lookup 25 s of the 120; Android's had no bound, and on the B6 it is the step that ran
     * out of memory). The lookup runs on its own (not a child of the ask, which would then wait for
     * it); past the budget the ask goes without it.
     */
    private suspend fun references(ctx: Context, rec: PendingSummon): String {
        val t0 = SystemClock.elapsedRealtime()
        val lookup = GlobalScope.async(Dispatchers.IO) {
            runCatching { siteReferenceBlock(ctx, rec.content) }.getOrDefault("")
        }
        val block = withTimeoutOrNull(SynthPending.REFERENCES_BUDGET_MS) { lookup.await() }
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        if (block == null) {
            lookup.cancel()
            warn(rec, "references skipped after %.1fs (budget %ds) — asking without them"
                .format(secs, SynthPending.REFERENCES_BUDGET_MS / 1000))
            return ""
        }
        log(rec, "references chars=${block.length} in %.1fs".format(secs))
        return block
    }

    /**
     * ONE ASK, wall-clock bounded at [SynthPending.REPLY_TIMEOUT_MS]. The old
     * `withTimeoutOrNull { withContext(IO) { blocking } }` could not actually return at the bound —
     * withContext waits for its blocking block — so the request runs in its own async and the
     * timeout races the AWAIT. Logs request sent / reply received / failed (with why) / timeout —
     * one line each, never a failure logged as a timeout too.
     */
    suspend fun asked(ctx: Context, creds: Triple<String, String, String>, rec: PendingSummon): Asked {
        val t0 = SystemClock.elapsedRealtime()
        log(rec, "request sent page=${rec.pageKey} mode=${rec.mode} ${voiceTag(rec.persona)} contentChars=${rec.content.length} attempt=${rec.attempts}")
        flush(ctx)
        val call = GlobalScope.async(Dispatchers.IO) {
            val references = if (rec.mode == MODE_STICKY || rec.mode == MODE_DRIVEBY) "" else references(ctx, rec)
            // The Correspondent's prompt verbatim, or the persona's own + ON THE PAGE (ruling 2).
            val prompt = if (SynthVoice.isCorrespondent(rec.persona)) ""
                else com.toolsboox.plugin.chat.nw.PersonaStore.promptFor(ctx, rec.persona).orEmpty()
            com.toolsboox.plugin.chat.nw.LedgerChatService().run(
                creds.first, creds.second, creds.third,
                SynthVoice.systemFor(rec.persona, prompt) + references, rec.content)
        }
        var threw: Throwable? = null
        val res = try {
            withTimeoutOrNull(SynthPending.REPLY_TIMEOUT_MS) { call.await() }
        } catch (t: Throwable) {
            if (t is CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
            threw = t
            null
        }
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val failure = threw
        val out: Asked = when {
            failure != null -> {
                warn(rec, "reply failed after %.1fs: %s: %s".format(secs, failure.javaClass.simpleName, failure.message), failure)
                Asked.Failed(when {
                    failure is java.io.InterruptedIOException -> SynthPending.Failure.TIMEOUT
                    failure is java.io.IOException -> SynthPending.Failure.LOST_CONNECTION
                    else -> SynthPending.Failure.REFUSED
                })
            }
            res == null -> {
                call.cancel()
                warn(rec, "reply timeout after %.1fs (bound %ds)".format(secs, SynthPending.REPLY_TIMEOUT_MS / 1000))
                Asked.Failed(SynthPending.Failure.TIMEOUT)
            }
            res is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok -> {
                val text = res.answer.trim()
                if (text.isBlank()) {
                    warn(rec, "reply empty after %.1fs".format(secs))
                    Asked.Failed(SynthPending.Failure.REFUSED)
                } else {
                    log(rec, "reply received chars=${text.length} in %.1fs".format(secs))
                    Asked.Reply(text)
                }
            }
            res is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err -> {
                val why = when {
                    res.lostConnection -> SynthPending.Failure.LOST_CONNECTION
                    res.timedOut -> SynthPending.Failure.TIMEOUT
                    else -> SynthPending.Failure.REFUSED
                }
                warn(rec, "reply failed after %.1fs (%s): %s".format(secs, why.name.lowercase(), res.message))
                Asked.Failed(why)
            }
            else -> Asked.Failed(SynthPending.Failure.REFUSED)
        }
        flush(ctx)
        return out
    }

    /** [asked] as the reply text, null for none — the drive-by's one-shot ask. */
    suspend fun ask(ctx: Context, creds: Triple<String, String, String>, rec: PendingSummon): String? =
        (asked(ctx, creds, rec) as? Asked.Reply)?.text

    /** The reply as sticky faces — bitmaps rendered off the main thread, PNG/base64 for the wire.
     *  The face's source line is the voice's short name ("The Correspondent", "Spiral"). */
    fun faces(answer: String, persona: String = SynthVoice.CORRESPONDENT): List<SynthFace> = SynthPending.replyChunks(answer).map { chunk ->
        val bmp = com.toolsboox.plugin.calendar.ot.StickyCardRenderer.render(
            "❖ $chunk", source = SynthVoice.shortName(SynthVoice.normalize(persona)), W = CARD_RENDER_W)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
        val aspect = bmp.height.toFloat() / bmp.width
        bmp.recycle()
        SynthFace(chunk, android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP), aspect)
    }

    /** "Synthesize", "Synthesize · page 2", "Topic", "Synthesize · Oct 11" — relative to the day
     *  the page he is looking at shows (or today, with no page standing). */
    private fun placeFor(rec: PendingSummon, key: String = rec.pageKey): String {
        val date = runCatching { LocalDate.parse(rec.date) }.getOrDefault(LocalDate.now())
        val viewing = CalendarDayFragment.liveDate() ?: LocalDate.now()
        return SynthWords.place(rec.pageName.ifBlank { SynthWords.pageName(rec.pageKey.substringBefore('#'), null) },
            key, date, viewing)
    }

    // ── Saying it ───────────────────────────────────────────────────────────────────────────

    /** A progress line ("❖ Writing back…") — shown where a day page stands, never owed, never
     *  persisted (it would overwrite the summon's own record). */
    fun progress(words: String, origin: CalendarDayFragment?) {
        val page = origin?.takeIf { it.canSpeak() } ?: CalendarDayFragment.live()?.takeIf { it.canSpeak() }
        page?.sayOnPage(words)
    }

    /**
     * Never a silent nothing. The words go to the summoning page if it still stands, else to
     * whichever day page is up, else a toast while the app is in front — and when it is not (the
     * summon finished in the background), they are OWED: kept as a notice record and said the
     * next time he opens the app — unless [owe] is false (the record must stay as it is, and
     * whatever finishes it later speaks for it).
     */
    suspend fun say(ctx: Context, rec: PendingSummon, words: String, origin: CalendarDayFragment? = null,
                    owe: Boolean = true) {
        val page = origin?.takeIf { it.canSpeak() } ?: CalendarDayFragment.live()?.takeIf { it.canSpeak() }
        when {
            page != null -> page.sayOnPage(words)
            foreground -> android.widget.Toast.makeText(ctx.applicationContext, words, android.widget.Toast.LENGTH_LONG).show()
            !owe -> { log(rec, "not said (app in background; the record speaks later): $words"); return }
            else -> {
                withContext(Dispatchers.IO) {
                    SynthPending.put(ctx, rec.copy(stage = SynthPending.NOTICE, notice = words, content = "", answer = "", tail = "", tailTurns = 0))
                }
                log(rec, "owed (app in background): $words")
                return
            }
        }
        log(rec, "said: $words")
    }

    // ── Landing ─────────────────────────────────────────────────────────────────────────────

    /**
     * The landing ladder (see the header). The pending record goes only once the card is ON DISK,
     * and the transcript turn is recorded only then — the old record-before-land marked the ink
     * "read" for replies that never arrived, so the next summon said "nothing new". Once the card
     * stands, nothing after it may say "couldn't be written".
     */
    suspend fun land(ctx: Context, rec: PendingSummon, answer: String, origin: CalendarDayFragment?,
                     resumed: Boolean = false) {
        val date = runCatching { LocalDate.parse(rec.date) }.getOrNull() ?: run {
            withContext(Dispatchers.IO) { SynthPending.remove(ctx, rec.id) }
            warn(rec, "landing failed: unreadable date ${rec.date}")
            return
        }
        try {
            val faces = withContext(Dispatchers.Default) { faces(answer, rec.persona) }

            // 1. The live page on that day — the summoner, or the page that replaced it.
            val host = awaitHost(origin, date)
            val live = host?.landSynthLive(rec, faces, resumed)
            if (host != null && live != null) {
                if (live.already) log(rec, "already standing — not landed twice page=${live.firstKey} y=%.0f cards=${live.cards}".format(live.y))
                else log(rec, "landed live page=${live.firstKey} y=%.0f cards=${live.cards} last=${live.lastKey} viewing=${host.viewingPage()}".format(live.y))
                // On disk before anything else — before a reveal can turn the page (the next
                // sub-page's fragment reads the day from the file) and before the record goes.
                // A page whose file stopped reading under it REFUSES here (saveNow marks it
                // read-only and rethrows UnreadableDayException): the catch below keeps the reply
                // and says so — never "wrote back" over a save that didn't happen (2026-10-16
                // review, item 3). Any other failure is a plain "not on disk yet".
                val saved = runCatching { host.saveSynthLanding() }
                    .onFailure { if (it is com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException || it is CancellationException) throw it }
                    .getOrDefault(false)
                var citedRefused: com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException? = null
                if (saved) {
                    // A page made from a list: a card for each [n] newly cited, under the reply —
                    // once the reply is on disk, while the host still stands (before a reveal can
                    // turn the page and leave the write to a day file no page has re-read yet), and
                    // BEFORE the record goes, so a kill in between leaves a reply to land again and
                    // that landing places what is still missing (iOS lands its follow-up cards
                    // before synthFinish too). Before the cited grams' detached write, so the two
                    // never race one day file. Refused (the page won't read): the record STAYS a
                    // reply to land — the next pass finds the reply standing and adds only the
                    // missing cards (iOS's citedCardsKept, item 4).
                    citedRefused = followUpCards(ctx, rec, live, answer)
                    if (citedRefused == null) finishLanded(ctx, rec, answer, resumed || live.already)
                } else warn(rec, "the day didn't save — the record stays; the next foreground finds the card standing and finishes it")
                val kept = citedRefused?.let { SynthWords.citedCardsKept(it.whichPage) }
                val words = kept ?: landedWords(rec, placeFor(rec, live.firstKey), SynthWords.continuedOn(live))
                if (host.canSpeak()) {
                    // A kept line said a moment ago (every return lands every kept reply again) is
                    // not said — nor the card panned to — again.
                    if (kept == null || keptMayBeSaid(rec, kept, host)) runCatching { host.revealSynthLanding(rec, live, words) }
                        .onFailure { warn(rec, "reveal failed", it); host.sayOnPage(words) }
                } else {
                    // The page turned meanwhile: the page now standing re-reads the day (it loaded
                    // before the card was on disk), the words go where he is, and the card is
                    // brought into view when its page opens. Unsaved — or its cited cards still
                    // owed — the record must stay a reply to land, so the words aren't owed over
                    // it (that landing says them).
                    armReveal(rec, live)
                    if (saved) CalendarDayFragment.liveOn(date)?.takeIf { it !== host }
                        ?.let { runCatching { it.onExternalGramPlaced(live.firstKey) } }
                    if (kept != null) sayKept(ctx, rec, kept, origin) else say(ctx, rec, words, owe = saved)
                }
                if (!live.already) placeCitedGrams(ctx, rec, date, live.firstKey, answer)
                flush(ctx)
                return
            }

            // 2. The day file.
            val landed = withContext(Dispatchers.IO) {
                SynthLanding.landInDayFile(
                    com.toolsboox.ui.share.ShareActions.service(ctx),
                    com.toolsboox.ot.LedgerPaths.documentsRoot(ctx), date, rec.pageKey, faces, resumed,
                    SynthVoice.label(rec.persona))
            }
            if (landed != null) {
                if (landed.already) log(rec, "already standing in the day file — not landed twice page=${landed.firstKey} y=%.0f cards=${landed.cards}".format(landed.y))
                else log(rec, "landed in the day file page=${landed.firstKey} y=%.0f cards=${landed.cards} last=${landed.lastKey}".format(landed.y))
                // The follow-up cards before the record goes, as on the live path — and, refused
                // because the page won't read, the record stays for them (item 4).
                val citedRefused = followUpCards(ctx, rec, landed, answer)
                if (citedRefused == null) finishLanded(ctx, rec, answer, resumed || landed.already)
                // No page took it, so none brought it into view: the page it went to pans to it
                // when it next opens (a landscape page's card can sit under the fold).
                armReveal(rec, landed)
                // A day page standing on this date (mid-reload a moment ago) re-reads — never saves.
                CalendarDayFragment.liveOn(date)?.let { runCatching { it.onExternalGramPlaced(rec.pageKey) } }
                if (citedRefused != null) sayKept(ctx, rec, SynthWords.citedCardsKept(citedRefused.whichPage), origin)
                else say(ctx, rec, landedWords(rec, placeFor(rec, landed.firstKey), SynthWords.continuedOn(landed)), origin)
                if (!landed.already) placeCitedGrams(ctx, rec, date, landed.firstKey, answer)
            } else {
                withContext(Dispatchers.IO) { SynthPending.remove(ctx, rec.id) }
                warn(rec, "landing failed: day ${rec.date} isn't on this device — nothing written")
                say(ctx, rec, SynthWords.couldNotWrite(placeFor(rec)), origin)
            }
        } catch (e: com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException) {
            // THE PAGE WON'T READ (day-file guard): nothing is written over it, and the reply is
            // NEVER dropped for it. The record stays exactly as it is on disk — a REPLIED reply to
            // land (askAndLand stored it before this landing; a resumed one already was) — so the
            // next foreground lands it once the page reads. Said where he is; in the background,
            // not owed as a notice (that would replace the reply with its words) — the record
            // speaks again when it is next landed.
            warn(rec, "landing refused: ${e.file.name} won't read — the reply is kept for the next foreground", e)
            sayKept(ctx, rec, SynthWords.replyKept(placeFor(rec), e.whichPage), origin)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            warn(rec, "landing threw", t)
            withContext(Dispatchers.IO) { runCatching { SynthPending.remove(ctx, rec.id) } }
            say(ctx, rec, SynthWords.couldNotWrite(placeFor(rec)), origin)
        }
        flush(ctx)
    }

    /** The card stands: the transcript turn + the watermark, then the pending record goes. A failure
     *  here is logged, never said — the reply IS on the page. [resumed] records the turn once only
     *  (a kill after the turn was recorded but before the record went must not double it). */
    private suspend fun finishLanded(ctx: Context, rec: PendingSummon, answer: String, resumed: Boolean) {
        runCatching {
            withContext(Dispatchers.IO) {
                SynthCorrespondence.record(ctx, LocalDate.parse(rec.date), rec.pageKey, rec.seen, rec.recordText, answer, resumed,
                    persona = rec.persona)
            }
        }.onFailure { warn(rec, "the transcript turn didn't record — the card stands", it) }
        withContext(Dispatchers.IO) { runCatching { SynthPending.remove(ctx, rec.id) } }
    }

    /** ❖ A page made from a list (its Sources card rode the ask, [PendingSummon.sources]): a card for
     *  every number the reply cites that has none on the page yet, placed under the reply's last
     *  card ([SynthLanded.lastKey]). Idempotent — a landing resumed after a kill places only what is
     *  still missing. Any other failure is logged, never said: the reply IS on the page. The
     *  guard's refusal (its page won't read) is RETURNED — the caller keeps the record for the
     *  cards and says so ([SynthWords.citedCardsKept]); null when nothing was refused. */
    private suspend fun followUpCards(ctx: Context, rec: PendingSummon, landed: SynthLanded, answer: String):
        com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException? {
        try {
            SynthListRun.followUpCards(ctx, rec, answer, landed.lastKey)
        } catch (e: com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException) {
            warn(rec, "follow-up cards refused: ${e.file.name} won't read — the reply stands, the record stays for its cards", e)
            return e
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            warn(rec, "follow-up cards threw", t)
        }
        return null
    }

    /** Every return lands every kept reply again; its refusal is said once per window. */
    private val keptRepeats = SynthWords.Repeats()

    /** Whether a kept reply's line [words] is said now: only where it can be heard (a kept line is
     *  never owed — the record must stay a reply to land, and that landing speaks for it), and not
     *  again within [SynthWords.KEPT_REPEAT_MS] of the same line. */
    private fun keptMayBeSaid(rec: PendingSummon, words: String, origin: CalendarDayFragment?): Boolean {
        val heard = origin?.canSpeak() == true || CalendarDayFragment.live()?.canSpeak() == true || foreground
        if (!heard) { log(rec, "not said (app in background; the record speaks later): $words"); return false }
        if (!keptRepeats.shouldSay(words, SystemClock.elapsedRealtime())) {
            log(rec, "not said again (said within ${SynthWords.KEPT_REPEAT_MS / 1000}s): $words")
            return false
        }
        return true
    }

    /** A kept reply's line, where he is — never owed, once per window ([keptMayBeSaid]). */
    private suspend fun sayKept(ctx: Context, rec: PendingSummon, words: String, origin: CalendarDayFragment?) {
        if (keptMayBeSaid(rec, words, origin)) say(ctx, rec, words, origin, owe = false)
    }

    /** Pan [landed]'s first card into view the next time its page opens on that day — one-shot,
     *  keyed by day and page (CalendarDayFragment.renderPage spends it), no words. */
    fun armReveal(rec: PendingSummon, landed: SynthLanded) {
        val date = runCatching { LocalDate.parse(rec.date) }.getOrNull() ?: return
        CalendarNavigator.pendingReveal = CalendarNavigator.Reveal(date, landed.firstKey,
            android.graphics.RectF(landed.x, landed.y, landed.x + landed.w, landed.y + landed.h), null)
        log(rec, "reveal armed for page=${landed.firstKey} y=%.0f".format(landed.y))
    }

    /**
     * The pages a reply cited → link grams on THE PAGE IT LANDED ON ([key] of [date]) — not on
     * whichever page the day fragment is showing, which can be another page of that day, or one
     * about to turn to the reply's next sub-page (2026-10-12 review). Detached: written into the day
     * file under its lock (a service with its Moshi), then MERGED into a page standing on that day.
     */
    private fun placeCitedGrams(ctx: Context, rec: PendingSummon, date: LocalDate, key: String, answer: String) {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                if (com.toolsboox.plugin.calendar.nw.SiteLinker.citedSiteUrls(ctx, answer).isEmpty()) return@runCatching
                val (placed, missed) = CalendarDayFragment.placeSiteGramCards(
                    com.toolsboox.ui.share.ShareActions.service(ctx), com.toolsboox.ot.LedgerPaths.documentsRoot(ctx),
                    CalendarDayFragment.citedSitePairs(ctx, answer), date, key)
                log(rec, "cited grams placed=$placed missed=${missed.size} page=$key")
                if (placed > 0) withContext(Dispatchers.Main) { CalendarDayFragment.liveOn(date)?.mergeFileElements() }
            }.onFailure { warn(rec, "cited grams failed", it) }
            flush(ctx)
        }
    }

    /** A day page that can take the card in memory now — waiting out a reload in flight (up to
     *  ~8 s), because a card added to the pre-reload snapshot is overwritten by the reload. */
    private suspend fun awaitHost(origin: CalendarDayFragment?, date: LocalDate): CalendarDayFragment? {
        repeat(40) {
            val ready = origin?.takeIf { it.canTakeSynthLanding(date) }
                ?: CalendarDayFragment.liveOn(date)?.takeIf { it.canTakeSynthLanding(date) }
            if (ready != null) return ready
            val loading = origin?.isSynthHostLoading(date) == true ||
                CalendarDayFragment.liveOn(date)?.isSynthHostLoading(date) == true
            if (!loading) return null
            delay(200)
        }
        return null
    }

    // ── The summon's spine ──────────────────────────────────────────────────────────────────

    /**
     * After the read: persist the request (BEFORE the network call — the record a killed process
     * leaves), ask, persist the reply (a kill between reply and landing must not cost a re-ask),
     * land. A lost connection, or a failure while he was away, is asked again once
     * ([SynthPending.reasks]) — at once when he is on the app, else when he is next back. Every
     * definite failure says so.
     */
    suspend fun askAndLand(ctx: Context, creds: Triple<String, String, String>, rec: PendingSummon,
                           origin: CalendarDayFragment?, resumed: Boolean = false) {
        // In the RECORD's voice (resolved at summon time), never the page's current one — checked
        // against this device's set and saved before the call.
        val voiced = withContext(Dispatchers.IO) { voicedForAsk(ctx, rec) }
        val asking = voiced.copy(stage = SynthPending.ASKING, attempts = rec.attempts + 1)
        withContext(Dispatchers.IO) { SynthPending.put(ctx, asking) }
        when (val asked = asked(ctx, creds, asking)) {
            is Asked.Reply -> {
                // A page with a Sources card: the reply's [n] cleaned against its numbers before it
                // is kept or landed — an invented number never reaches the page (synth-list-links).
                val text = SynthListRun.cleanReply(asking, asked.text)
                val replied = asking.copy(stage = SynthPending.REPLIED, answer = text)
                withContext(Dispatchers.IO) { SynthPending.put(ctx, replied) }
                land(ctx, replied, text, origin, resumed)
            }
            is Asked.Failed -> {
                val away = !foreground
                if (SynthPending.reasks(asked.failure, away, asking.attempts)) {
                    if (away) {
                        // Left exactly as a kill leaves it — the record on disk, stage asking, its
                        // request whole — and released by the caller: the next foreground pass
                        // re-asks it (onForeground → ASK), in this process or the next. Nothing
                        // waits here, so no dead page is pinned for however long he is away. (All
                        // on Main, like the pass itself: no pass can slip between this and the
                        // release and skip it as still in flight.)
                        log(rec, "${asked.failure.name.lowercase()} while the app was away — kept; the next foreground asks again")
                        flush(ctx)
                        return
                    }
                    delay(RETRY_PAUSE_MS)
                    log(rec, "asking again (attempt ${asking.attempts + 1} of ${SynthPending.MAX_ATTEMPTS}) after ${asked.failure.name.lowercase()}")
                    progress(SynthWords.writingBack(asking.persona), origin)
                    askAndLand(ctx, creds, asking, origin, resumed)
                    return
                }
                withContext(Dispatchers.IO) { SynthPending.remove(ctx, rec.id) }
                // On the page that asked: the plain line, as ever. Anywhere else: name the page.
                val words = if (origin?.canSpeak() == true && origin.viewingPage() == rec.pageKey) SynthWords.NO_REPLY
                    else SynthWords.noReply(placeFor(rec))
                say(ctx, rec, words, origin)
                flush(ctx)
            }
        }
    }

    /** The breath before a re-ask in the foreground — the radio coming back after a drop. */
    private const val RETRY_PAUSE_MS = 3_000L

    // ── The next foreground ─────────────────────────────────────────────────────────────────

    /**
     * MainActivity.onResume: say what is owed, and resume every summon a dead process left behind
     * — re-ask (its request was saved before the network call) or just land the reply that had
     * already come back, into the day file or onto the page standing on that day.
     *
     * Everything owed is said as ONE message, a line each (2026-10-12 review: one say per record
     * meant each snackbar replaced the last, and only the final line was ever seen). And each record
     * is re-read once claimed — the B6's wake fires resume, pause, resume, and a second pass must not
     * act on a snapshot an earlier pass has already finished or advanced.
     */
    fun onForeground(activity: Activity) {
        foreground = true
        SynthListRun.remember(activity)
        val ctx = activity.applicationContext
        GlobalScope.launch(Dispatchers.Main) {
            runCatching {
                val now = System.currentTimeMillis()
                val recs = withContext(Dispatchers.IO) { SynthPending.all(ctx) }
                val owed = mutableListOf<String>()
                var first: PendingSummon? = null
                // A ❖ Synthesize this list run's "Ready: <page>" among them — the one message then
                // carries its [Go to it] (synth-list-links §4).
                var ready: PendingSummon? = null
                for (rec in recs) {
                    val action = SynthPending.actionFor(rec, inFlight, now)
                    if (action == SynthPending.Action.WAIT) continue
                    if (!begin(rec)) continue
                    val fresh = withContext(Dispatchers.IO) { SynthPending.get(ctx, rec.id) }
                    if (fresh != rec) {
                        end(rec)
                        if (fresh != null) log(rec, "resume skipped: another pass moved it on (stage ${fresh.stage})")
                        continue
                    }
                    // ❖ Synthesize this list / article keeps its own words: a run the app died in is
                    // told, never re-asked; its owed lines join the one message like any summon's.
                    val list = SynthListRun.owns(rec)
                    val words = if (list) SynthListRun.owedWords(ctx, rec, action) else owedWords(ctx, rec, action)
                    if (words != null) {
                        end(rec)
                        owed += words
                        if (first == null) first = rec
                        if (ready == null && list && SynthListRun.isReady(rec)) ready = rec
                        continue
                    }
                    launch {
                        try {
                            if (list) SynthListRun.resume(ctx, rec, action) else resume(ctx, rec, action)
                        } catch (t: Throwable) {
                            if (t !is CancellationException) warn(rec, "resume threw", t)
                        } finally {
                            end(rec)
                        }
                    }
                }
                val said = SynthWords.joined(owed)
                if (said.isNotBlank()) {
                    val door = ready
                    if (door != null) SynthListRun.sayReady(ctx, door, said)
                    else first?.let { say(ctx, it, said) }
                    flush(ctx)
                }
            }.onFailure { Timber.w(it, "synth resume pass failed") }
        }
    }

    /** The words a finished or failed record still owes — its record cleared — or null when the
     *  record is resumed instead (ask / land). */
    private suspend fun owedWords(ctx: Context, rec: PendingSummon, action: SynthPending.Action): String? {
        val words = when (action) {
            SynthPending.Action.SAY -> { log(rec, "saying what was owed"); rec.notice }
            SynthPending.Action.CUT_OFF -> {
                warn(rec, "resumed: the app closed before the read finished — nothing to ask with")
                SynthWords.cutOff(placeFor(rec))
            }
            SynthPending.Action.GIVE_UP, SynthPending.Action.EXPIRED -> {
                warn(rec, "resumed: ${action.name.lowercase()} after ${rec.attempts} ask(s)")
                SynthWords.noReply(placeFor(rec))
            }
            else -> return null
        }
        withContext(Dispatchers.IO) { SynthPending.remove(ctx, rec.id) }
        return words
    }

    private suspend fun resume(ctx: Context, rec: PendingSummon, action: SynthPending.Action) {
        when (action) {
            SynthPending.Action.LAND -> {
                log(rec, "resumed: landing the reply that came back before the app closed")
                land(ctx, rec, rec.answer, null, resumed = true)
            }
            SynthPending.Action.ASK -> {
                val creds = runCatching { withContext(Dispatchers.IO) { creds(ctx) } }.getOrNull()
                if (creds == null) {
                    withContext(Dispatchers.IO) { SynthPending.remove(ctx, rec.id) }
                    warn(rec, "resumed: no AI key — can't ask again")
                    say(ctx, rec, SynthWords.noReply(placeFor(rec)))
                    return
                }
                log(rec, "resumed after the app closed mid-summon — asking again (attempt ${rec.attempts + 1} of ${SynthPending.MAX_ATTEMPTS})")
                // Said again, as iOS does: he is back, and a reply is on its way — in the record's voice.
                progress(SynthWords.writingBack(rec.persona), null)
                askAndLand(ctx, creds, rec, null, resumed = true)
            }
            else -> Unit
        }
    }

    const val MODE_DELTA = "delta"
    const val MODE_SELECTION = "selection"
    const val MODE_CARD = "card"
    const val MODE_STICKY = "sticky"
    const val MODE_DRIVEBY = "driveby"

    /** Chunk width the reply cards render at; StickyCardRenderer auto-measures height. */
    const val CARD_RENDER_W = 1080
}
