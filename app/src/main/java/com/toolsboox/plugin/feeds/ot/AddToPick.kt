package com.toolsboox.plugin.feeds.ot

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.toolsboox.da.Attachment
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.LedgerPaths
import com.toolsboox.ot.ModalScale
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.AddToPickWords
import com.toolsboox.plugin.calendar.ot.ArticlePickIndex
import com.toolsboox.plugin.calendar.ot.AvPlayback
import com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes
import com.toolsboox.plugin.calendar.ot.DayLocks
import com.toolsboox.plugin.calendar.ot.GramStudio
import com.toolsboox.plugin.calendar.ot.MarkCard
import com.toolsboox.plugin.calendar.ot.MarkWords
import com.toolsboox.plugin.calendar.ot.PickFace
import com.toolsboox.plugin.calendar.ot.PickFaceLayout
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.ReadingEventEdit
import com.toolsboox.plugin.reader.ui.BookNote
import com.toolsboox.plugin.reader.ui.MarkFace
import com.toolsboox.ui.plugin.ScreenFragment
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * WHAT IS ADDED TO A HIGHLIGHT'S PICK, AND ITS ONE FACE (the add-to-pick pin, 2026-10-16; Michael
 * 10-12: "I should be able to add image, take picture, video, voice, text, handwritten note" →
 * "That sounds great. Let the photo/video/note show on the pick.").
 *
 * ONE RECORD — REUSE of the -328 note-joins-pick path (ArticleHighlights, moved here so every door
 * reaches it, not only an open article): everything said about a passage lives on its ONE Log line
 * (the typed note, and the attachments — ink-…png, photo-…jpg, voice-…m4a, video-…mp4), and its ONE
 * pick is RE-FACED from that line in the shape the studio gave it (the mark's `face`), with its
 * `faceSlots` and its one media slot rewritten. Never a second card; an add never stars.
 *
 * Every method here works off the main thread (it loads and saves days) and says what happened
 * through [say] — never a silent nothing.
 *
 * A DAY THAT WON'T READ IS NEVER WRITTEN OVER (the day-file guard, Android -332): every day this
 * engine changes goes through a guarded door — [ReadingEventEdit.update] / [ReadingEventEdit.addIfAbsent]
 * / [ReadingEventEdit.loadForWriteOrThrow] / [CalendarDayService.loadToEdit] / [PickingsPlacement] —
 * and a refusal is said in the shared words (convergence-fixtures/day-guard-words.json, [MarkWords]):
 * "Couldn't log it — …" (a new line), "Couldn't change the line — …" (an add or a removal on it),
 * "Couldn't change the pick — …" (its face re-drawn). A take whose line was refused keeps its file in
 * the attachment store — nothing here ever deletes one — and the ADD is kept too: it waits ([waitFor])
 * and lands once the page reads ([drain]), and the words say so ([AddToPickWords.refusedKept];
 * merge-addpick review #2 — a refused photo, recording or note was held by nothing).
 */
class PickAdds(
    private val app: Context,
    private val service: CalendarDayService,
    private val root: () -> File,
    /** One line for the person; posts itself to the main thread. */
    private val say: (String) -> Unit,
) {
    private val dir: File get() = LedgerPaths.attachmentsDir(app)

    // ── The line ──────────────────────────────────────────────────────────────────────────────

    /**
     * A Log line about the piece, on [day]. Stamped NOW when [day] is today, else at noon of [day]
     * (review 2026-10-12 #13: a line made late on an old pick's day — its first note — read as made
     * at this moment, and the Log's row time comes from it).
     */
    fun lineFor(a: ArticleHighlights.Article, id: String, passage: String, day: LocalDate, note: String? = null) = ReadingEvent(
        id = id, kind = ReadingEvent.Kind.ARTICLE,
        date = if (day == LocalDate.now()) Date()
        else Date.from(day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()),
        title = a.title, source = a.feedTitle.ifBlank { null }, url = a.httpUrl.ifBlank { null },
        excerpt = passage.ifBlank { null }, note = note, updatedAt = Date())

    /** The mark's Log line and the day it is on — the one home of what's said about the passage. */
    fun lineOf(m: BookNote): Pair<LocalDate, ReadingEvent>? {
        val day = m.lineDay ?: return null
        if (m.logId.isBlank()) return null
        return ReadingEventEdit.find(service, root(), day, m.logId)
    }

    /**
     * The mark as it is stored NOW (review 2026-10-16 #11: a sheet held the copy it was opened with,
     * so every add to a highlight whose line had been made again saw the old line id as removed and
     * made one more line). A tombstone included, so a removal elsewhere is still seen.
     */
    fun fresh(a: ArticleHighlights.Article, m: BookNote): BookNote =
        runCatching { ArticleNoteStore.stored(app, a.key, m.id) }.getOrNull() ?: m

    /** What rides on the pick now — for the ladder's open rows, its chips and its Remove list. */
    fun current(m: BookNote): AddToPick.Current =
        lineOf(m)?.second.let { AddToPick.Current(it?.note.orEmpty(), it?.attachments.orEmpty()) }

    /**
     * The mark's line, made on its day if it has none (an old pick from the share card never had
     * one) — "It goes in the day it was picked's log". Returns the mark with its logId, or null when
     * the line can't be had here (the caller keeps the add waiting, [waitFor]):
     *  - the mark HAS a line that isn't on this device yet (made on the other device, its day not
     *    synced): a second copy made here would win the next merge over the real one and its note
     *    (newest updatedAt wins) — wait for the day instead;
     *  - its past day isn't here ([ReadingEventEdit.loadForWriteOrThrow], review 2026-10-12 #2).
     * THROWS [Refused] when a day it must find or write the line on is there but won't read (the
     * day-file guard) — nothing is written over it:
     *  - the line's OWN day — or a neighbour a clock that crossed midnight may have put it on
     *    ([ReadingEventEdit.candidateDays], iOS's `lineRefusal`; merge-addpick review #11) — while
     *    the mark has a line: the line may well be in it, so that is "Couldn't change the line —
     *    <page> won't read" ([MarkWords.CHANGE_THE_LINE]), never "not here yet" (and never a second
     *    line made beside it);
     *  - the day a new line goes on: "Couldn't log it — <page> won't read" ([MarkWords.LOG_IT]).
     * A line that was REMOVED from the Log (its marker on the day) is made again under a new id —
     * and that id is filed on the mark (in this key's file even when the mark was found under the
     * cleaned-URL fallback), so the next add finds the line instead of making another.
     */
    fun ensureLine(a: ArticleHighlights.Article, m: BookNote): BookNote? {
        if (lineOf(m) != null) return m
        val day = m.lineDay ?: LocalDate.now()
        if (m.logId.isNotBlank()) unreadableNear(day)?.let { throw Refused(it, MarkWords.CHANGE_THE_LINE) }
        val removed = m.logId.isNotBlank() && lineRemoved(day, m.logId)
        if (m.logId.isNotBlank() && !removed) return null
        val lineId = when {
            removed -> m.id + "-" + UUID.randomUUID().toString().replace("-", "").take(4)
            else -> m.id
        }
        if (!guarded(MarkWords.LOG_IT) { ReadingEventEdit.addIfAbsent(service, root(), day, lineFor(a, lineId, m.text, day)) }) return null
        return ArticleNoteStore.update(app, a.key, m.id) { it.copy(logId = lineId, logDay = day.toString()) }
            ?: m.copy(logId = lineId, logDay = day.toString(), updatedAt = System.currentTimeMillis()).also {
                if (ArticleNoteStore.stored(app, a.key, m.id) == null) ArticleNoteStore.put(app, a.key, it)
            }
    }

    /** True when line [id] was taken off the Log on [day] (or a neighbour) — its marker is there. */
    private fun lineRemoved(day: LocalDate, id: String): Boolean =
        ReadingEventEdit.candidateDays(day).any { d ->
            service.loadOrNull(root(), d, Locale.getDefault())?.deletedElementIds?.any { it.equals(id, ignoreCase = true) } == true
        }

    /** True when the highlight was taken off (on this device or, synced in, the other one). */
    private fun gone(a: ArticleHighlights.Article, m: BookNote): Boolean =
        ArticleNoteStore.stored(app, a.key, m.id)?.deletedAt != null

    /**
     * A day-file guard refusal on its way up, with the verb that names what couldn't be done
     * ([MarkWords]) — said as "Couldn't <doing> — <page> won't read" ([words]).
     */
    class Refused(val e: CalendarDayService.UnreadableDayException, val doing: String) : Exception(e) {
        val words: String get() = CalendarDayService.refusal(e, doing).orEmpty()
    }

    /** [block], its [CalendarDayService.UnreadableDayException] named as [doing] ([Refused]). */
    private inline fun <T> guarded(doing: String, block: () -> T): T =
        try { block() } catch (e: CalendarDayService.UnreadableDayException) { throw Refused(e, doing) }

    /** [day]'s refusal when its file is there but won't read — asked of the reader's door, so nothing
     *  is written; null when it reads or isn't there. */
    private fun unreadable(day: LocalDate): CalendarDayService.UnreadableDayException? =
        (service.read(root(), day) as? CalendarDayService.DayRead.Unreadable)
            ?.let { CalendarDayService.UnreadableDayException(it.file, it.cause) }

    /** The first of [day]'s candidate days ([ReadingEventEdit.candidateDays] — its own first) that
     *  won't read: a line not found on the others may be in it. Null when they all read or aren't here. */
    private fun unreadableNear(day: LocalDate): CalendarDayService.UnreadableDayException? =
        ReadingEventEdit.candidateDays(day).firstNotNullOfOrNull { unreadable(it) }

    /** Where an add landed: [mark] set → re-face its pick; [notHere] → its line isn't on this device
     *  yet (the add waits); [refused] → a day it must write won't read (the words that say so —
     *  nothing was written); none → it landed and there is nothing to re-face (the highlight went). */
    private class Landed(val mark: BookNote? = null, val notHere: Boolean = false, val refused: String? = null)

    /**
     * Change the highlight's line with [change]. Removed elsewhere mid-add (pin §7): the add still
     * lands — on its line, or a new one on its day — and says so; nothing is re-faced (the pick went
     * with the highlight). A day that won't read refuses it ([Landed.refused]) — nothing is written.
     */
    private fun onLine(a: ArticleHighlights.Article, mark: BookNote,
                       alsoOnDay: ((com.toolsboox.plugin.calendar.da.v2.CalendarDay) -> Unit)? = null,
                       change: (ReadingEvent) -> ReadingEvent): Landed = try {
        onLineOrRefuse(a, mark, alsoOnDay, change)
    } catch (r: Refused) {
        Timber.w(r.e, "add to pick: %s refused — its page won't read, nothing written", mark.id)
        Landed(refused = r.words)
    }

    private fun onLineOrRefuse(a: ArticleHighlights.Article, mark: BookNote,
                               alsoOnDay: ((com.toolsboox.plugin.calendar.da.v2.CalendarDay) -> Unit)?,
                               change: (ReadingEvent) -> ReadingEvent): Landed {
        val held = fresh(a, mark)
        if (held.deletedAt != null || gone(a, held)) {
            val line = lineOf(held)
            val (day, id) = if (line != null) line.first to line.second.id else {
                val d = held.lineDay ?: LocalDate.now()
                // Its own line's day (or a neighbour) that won't read may hold it: refused, never a second line.
                if (held.logId.isNotBlank()) unreadableNear(d)?.let { throw Refused(it, MarkWords.CHANGE_THE_LINE) }
                val id = held.id + "-" + UUID.randomUUID().toString().replace("-", "").take(4)
                if (!guarded(MarkWords.LOG_IT) { ReadingEventEdit.addIfAbsent(service, root(), d, lineFor(a, id, held.text, d)) })
                    return Landed(notHere = true)
                d to id
            }
            if (guarded(MarkWords.CHANGE_THE_LINE) { ReadingEventEdit.update(service, root(), day, id, alsoOnDay, change) } == null)
                return Landed(notHere = true)
            say(AddToPickWords.REMOVED_ELSEWHERE)
            return Landed()
        }
        val m = ensureLine(a, held) ?: return Landed(notHere = true)
        val day = m.lineDay ?: return Landed(notHere = true)
        if (guarded(MarkWords.CHANGE_THE_LINE) { ReadingEventEdit.update(service, root(), day, m.logId, alsoOnDay, change) } == null)
            return Landed(notHere = true)
        return Landed(m)
    }

    // ── The adds ──────────────────────────────────────────────────────────────────────────────

    /** What an add came to: [Landed] (with what to say, or nothing), [Waiting] for its line to
     *  arrive ([waitFor]), or [Refused] by a day that won't read (the words — nothing written; the
     *  add itself waits for the page, [waitFor]). */
    private sealed class Outcome {
        class Landed(val msg: String?) : Outcome()
        object Waiting : Outcome()
        class Refused(val words: String) : Outcome()

        /** The line to say: what landed, or the refusal. */
        val said: String? get() = when (this) { is Landed -> msg; is Refused -> words; Waiting -> null }
    }

    /** ⌨ The typed note — the same line's words, replaced (an emptied note clears them). */
    fun addText(a: ArticleHighlights.Article, mark: BookNote, words: String): String? =
        text(a, mark, words, replay = false).said

    private fun text(a: ArticleHighlights.Article, mark: BookNote, words: String, replay: Boolean): Outcome {
        val r = onLine(a, mark) { it.copy(note = words.ifBlank { null }) }
        // A day it must write won't read: said, nothing written — and the words KEPT, landing once
        // the page reads ([drain]; merge-addpick review #2: the typed note was dropped).
        r.refused?.let { if (!replay) waitFor(a, mark, words = words); return Outcome.Refused(AddToPickWords.refusedKept(it)) }
        if (r.notHere) { if (!replay) { waitFor(a, mark, words = words); say(AddToPickWords.LINE_NOT_HERE) }; return Outcome.Waiting }
        // Landed: an older note still waiting would otherwise be replayed over this one.
        if (!replay) unwaitWords(a.key, mark.id)
        val m = r.mark ?: return Outcome.Landed(null)
        return Outcome.Landed(refaced(a, m, if (words.isBlank()) "Note cleared" else "Note saved"))
    }

    /** ✍ The handwriting — kept as ink-<uuid>.png in the attachment store, then onto the line. */
    fun addInk(a: ArticleHighlights.Article, mark: BookNote, ink: Bitmap): String? {
        val att = saveInk(app, ink) ?: run { say(AddToPickWords.OUT_OF_SPACE); return null }
        return attach(a, mark, att, "Handwriting saved", replay = false).said
    }

    /** 🎤 📷 🖼 🎥 — the file is in the store already; onto the line, and a clip into its card
     *  day's A/V list (that day as it stands — review 2026-10-12 #2). */
    fun addMedia(a: ArticleHighlights.Article, mark: BookNote, att: Attachment): String? =
        attach(a, mark, att, when {
            com.toolsboox.plugin.calendar.ot.PickFaceAdds.isInk(att) -> "Handwriting saved"
            att.kind == Attachment.Kind.PHOTO -> "Photo saved"
            att.kind == Attachment.Kind.VIDEO -> "Video saved"
            else -> "Voice note saved"
        }, replay = false).said

    private fun attach(a: ArticleHighlights.Article, mark: BookNote, att: Attachment, what: String, replay: Boolean): Outcome {
        val r = onLine(a, mark) { line ->
            val held = line.attachments.orEmpty()
            if (held.any { it.id == att.id }) line else line.copy(attachments = (held + att).toMutableList())
        }
        // A day it must write won't read (day-file guard): said, nothing written — the take's file
        // stays in the attachment store (never deleted on a refusal), and the take itself WAITS, as a
        // not-here one does, joining the line once the page reads (merge-addpick review #2: nothing
        // held it, so it was on no line, no A/V list and no card).
        r.refused?.let { if (!replay) waitFor(a, mark, att = att); return Outcome.Refused(AddToPickWords.refusedKept(it)) }
        // Its line isn't here yet (review 2026-10-16 #16): the take is never dropped — it waits,
        // and joins the line when the day arrives ([drainWaiting]).
        if (r.notHere) { if (!replay) { waitFor(a, mark, att = att); say(AddToPickWords.LINE_NOT_HERE) }; return Outcome.Waiting }
        val m = r.mark ?: return Outcome.Landed(null)
        if (att.kind != Attachment.Kind.PHOTO) {
            val avDay = (if (m.pickId.isNotBlank()) m.cardDay else null) ?: m.lineDay ?: LocalDate.now()
            // The card's day as it stands (today through the writer's door). One that won't read is
            // left alone: the clip is on the line, and the re-face below says that page won't read.
            DayLocks.withDay(avDay) {
                val cal = try { ReadingEventEdit.loadForWriteOrThrow(service, root(), avDay) }
                    catch (e: CalendarDayService.UnreadableDayException) {
                        Timber.w(e, "add to pick %s: the clip's card day won't read — not on its A/V list", m.id); null
                    } ?: return@withDay
                if (cal.avGrams.none { it.id == att.id }) { cal.avGrams.add(att); service.save(root(), avDay, cal) }
            }
        }
        return Outcome.Landed(refaced(a, m, what))
    }

    /**
     * What was saved, then where its pick is — re-drawn first ([reface]). The line holds what was
     * said either way; a pick whose page won't read says so after it ("Note saved · Couldn't change
     * the pick — the Oct 3 page won't read", [MarkWords.CHANGE_THE_PICK]) and waits to be re-drawn
     * once that page reads ([drain]).
     */
    private fun refaced(a: ArticleHighlights.Article, m: BookNote, what: String): String {
        val where = try { reface(a, m) } catch (e: CalendarDayService.UnreadableDayException) {
            return listOf(what, CalendarDayService.refusal(e, MarkWords.CHANGE_THE_PICK).orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · ")
        }
        return said(what, where)
    }

    /**
     * 🗑 Remove from this pick… — the one thing leaves the line (`att:<id>` tombstoned there, the form
     * both apps' mergers read — review 2026-10-16 #1 — so the other device's copy never hands it
     * back) and its card day's A/V list; the pick is re-faced. The file stays in the store. A line
     * whose day won't read keeps it ("Couldn't change the line — …"); a pick whose day won't read is
     * re-drawn once it does ("Couldn't change the pick — …"), and a clip its A/V list couldn't let
     * go of then is taken off it once it reads ([waitDrop]; merge-addpick review #8).
     */
    fun removeAttachment(a: ArticleHighlights.Article, mark: BookNote, att: Attachment): String? {
        val r = onLine(a, mark, alsoOnDay = { ReadingEventEdit.tombstoneAttachment(it, att.id) }) {
            it.copy(attachments = it.attachments?.filter { x -> x.id != att.id }?.toMutableList())
        }
        r.refused?.let { return it }
        if (r.notHere) { say(AddToPickWords.LINE_NOT_HERE_REMOVE); return null }
        val m = r.mark ?: return null
        var clipRefused: CalendarDayService.UnreadableDayException? = null
        if (att.kind != Attachment.Kind.PHOTO) {
            val avDay = (if (m.pickId.isNotBlank()) m.cardDay else null) ?: m.lineDay
            if (avDay != null) clipRefused = dropClips(avDay, listOf(att.id))
            if (avDay != null && clipRefused != null) waitDrop(a.key, avDay, att.id)
        }
        val pickRefused = try { reface(a, m); null } catch (e: CalendarDayService.UnreadableDayException) { e }
        return CalendarDayService.refusal(pickRefused ?: clipRefused, MarkWords.CHANGE_THE_PICK)
    }

    /**
     * Take clips [ids] off the A/V list of [day] (± a day), tombstoned there so the other device's
     * copy never hands them back as standalone A/V notes. Each day as it stands
     * ([CalendarDayService.loadToEdit] — nothing minted); one that won't read is left exactly as it
     * is (the day-file guard). Returns [day]'s own refusal when the clips were found on no readable
     * day and [day] won't read (they are most likely there), else null.
     */
    fun dropClips(day: LocalDate, ids: Collection<String>): CalendarDayService.UnreadableDayException? {
        if (ids.isEmpty()) return null
        val dead = ids.map { it.lowercase() }.toSet()
        var found = false
        var refused: CalendarDayService.UnreadableDayException? = null
        for (d in ReadingEventEdit.candidateDays(day)) DayLocks.withDay(d) {
            val cal = try { service.loadToEdit(root(), d) } catch (e: CalendarDayService.UnreadableDayException) {
                Timber.w(e, "add to pick: a clip's day won't read — its A/V list left as it is")
                if (d == day) refused = e
                null
            } ?: return@withDay
            val gone = cal.avGrams.filter { it.id.lowercase() in dead }
            if (gone.isEmpty()) return@withDay
            cal.avGrams.removeAll { it.id.lowercase() in dead }
            gone.forEach { ReadingEventEdit.tombstone(cal, it.id) }
            service.save(root(), d, cal)
            found = true
        }
        return if (found) null else refused
    }

    /**
     * ❝ from the studio, TIED (pin §2 D1): the highlight's OWN pick re-faced in the chosen shape, and
     * the shape remembered ([BookNote.face]) so every later add re-faces it the same. On a Series the
     * pick is card 1/n; [more] (cards 2…n, as baked) are placed after it — copies, so they carry no
     * words and never highlight back — and their ids are kept on the mark ([BookNote.pickCopies]), so
     * a later design takes the earlier copies off instead of piling more up (review 2026-10-16 #15).
     *
     * The day-file guard: a pick whose page won't read is not re-drawn, so the design isn't taken
     * either — the mark keeps the shape it had and its copies, and the words say which page
     * ("Couldn't change the pick — …"). An earlier copy on a page that won't read stays on the mark,
     * so the next design still takes it off.
     */
    fun designed(a: ArticleHighlights.Article, mark: BookNote, face: MarkFace, more: List<Bitmap> = emptyList()): String? {
        val before = fresh(a, mark)
        val wasWaiting = isStale(app, a.key, mark.id)
        val m = ArticleNoteStore.update(app, a.key, mark.id) { it.copy(face = face) }
            ?: if (gone(a, before)) { say(AddToPickWords.REMOVED_ELSEWHERE); return null }
            // Found under the cleaned-URL fallback, not in this key's file: filed here with its face.
            else before.copy(face = face, updatedAt = System.currentTimeMillis()).also { ArticleNoteStore.put(app, a.key, it) }
        val where = try { reface(a, m) } catch (e: CalendarDayService.UnreadableDayException) {
            ArticleNoteStore.update(app, a.key, mark.id) { it.copy(face = before.face) }
            staleFaces(a.key, mark.id, wasWaiting)
            return CalendarDayService.refusal(e, MarkWords.CHANGE_THE_PICK)
        }
        val day = fresh(a, m).cardDay ?: m.cardDay ?: LocalDate.now()
        // The earlier design's copies come off (on the card's day, where they were placed) — each
        // day as it stands; one that won't read keeps its copies, and they stay on the mark.
        val stale = before.pickCopies
        val kept = mutableListOf<String>()
        if (stale.isNotEmpty()) {
            val atDay = before.cardDay ?: day
            val off = mutableSetOf<String>()
            var refused = false
            for (d in ReadingEventEdit.candidateDays(atDay)) DayLocks.withDay(d) {
                val cal = try { service.loadToEdit(root(), d) } catch (e: CalendarDayService.UnreadableDayException) {
                    Timber.w(e, "add to pick %s: an earlier design's copies sit on a page that won't read — kept", mark.id)
                    refused = true; null
                } ?: return@withDay
                val here = stale.filter { s -> cal.imageElements.any { it.elementId.toString().equals(s, ignoreCase = true) } }
                if (here.isEmpty()) return@withDay
                stale.forEach { MarkCard.dropFrom(cal, it) }
                service.save(root(), d, cal)
                off += here.map { it.lowercase() }
            }
            if (refused) kept += stale.filter { it.lowercase() !in off }
        }
        val placed = mutableListOf<String>()
        var placeRefused: CalendarDayService.UnreadableDayException? = null
        for (b in more) {
            val id = UUID.randomUUID()
            runCatching {
                PickingsPlacement.place(service, root(), b, day, CalendarDayPageNotes.GRAM_PICKS,
                    sourceLink = a.httpUrl, sourceLabel = FeedNoteGram.label(a.title, a.feedTitle),
                    cardText = "", sourceFeed = a.feedTitle, pastDayMustExist = true, elementId = id)
            }.onSuccess { placed += id.toString() }.onFailure {
                Timber.w(it, "add to pick %s: a Series card wasn't placed", mark.id)
                if (placeRefused == null) placeRefused = it as? CalendarDayService.UnreadableDayException
            }
        }
        if (stale.isNotEmpty() || placed.isNotEmpty())
            ArticleNoteStore.update(app, a.key, mark.id) { it.copy(pickCopies = placed + kept) }
        val done = where?.let { "❝ Its pick in $it" } ?: "❝ Its pick"
        return listOfNotNull(done, CalendarDayService.refusal(placeRefused, MarkWords.CHANGE_THE_PICK)).joinToString(" · ")
    }

    /** iOS's toast: what was saved, then where its pick is ("Note saved · its pick in All Picks"). */
    private fun said(what: String, where: String?) = where?.let { "$what · its pick in $it" } ?: what

    // ── The one face ──────────────────────────────────────────────────────────────────────────

    /**
     * The face the mark's pick wears now: its line's words and attachments in its designed shape. A
     * designed fit applies only past 420 chars (code points — review 2026-10-16 B#19), as the studio
     * offers it and as iOS applies it. The selection's own picture rides when nothing of yours does
     * and the face's `picture` is on ([AddToPick.selectionPicture] — review B#13: a re-face here
     * dropped the picture iOS drew, and the next one on the iPhone put it back).
     */
    fun face(a: ArticleHighlights.Article, m: BookNote, line: ReadingEvent): PickFace.Rendered? {
        val whole = m.type == BookNote.NOTE
        // A note about the whole piece has no passage: its words are the card's body. A passage
        // wears its link lines, as its first face did (the line keeps them).
        val quote = if (whole) line.note.orEmpty() else line.excerpt?.takeIf { it.isNotBlank() } ?: m.text
        val noteWords = if (whole) "" else line.note.orEmpty()
        val atts = line.attachments.orEmpty()
        if (quote.isBlank() && noteWords.isBlank() && atts.isEmpty()) return null
        val f = m.face ?: MarkFace()
        val shape = PickFaceLayout.Shape.of(f.format)
        val day = m.cardDay ?: LocalDate.now()
        val src = FeedNoteGram.footer(a.title, a.feedTitle, day)
        var passage = quote
        var source = src
        if (!whole && GramStudio.isLong(quote)) when (f.fit) {
            MarkFace.FIT_PULLQUOTE -> passage = GramStudio.pullQuote(quote)
            // Adds ride card 1/n only.
            MarkFace.FIT_SERIES -> GramStudio.seriesChunks(quote).let { chunks ->
                passage = chunks.first()
                if (chunks.size > 1) source = "$src · 1/${chunks.size}"
            }
        }
        val yours = atts.any { (it.kind == Attachment.Kind.PHOTO && !PickFace.isInk(it)) || it.kind == Attachment.Kind.VIDEO }
        val picture = if (f.picture && !yours) AddToPick.selectionPicture(m) else null
        val adds = PickFace.fromLine(dir, noteWords, atts, picture)
        return PickFace.render(shape, passage, source, adds)
    }

    /** The pick's one media slot, for older builds: a video if there is any, else a voice note —
     *  the NEWEST of that kind by date ([PickFace.newestFirst], iOS's rule); none clears it. */
    private fun mediaSlot(a: ArticleHighlights.Article, atts: List<Attachment>): PickingsPlacement.MediaRef {
        val clip = com.toolsboox.plugin.calendar.ot.PickFaceAdds.mediaClip(atts) ?: return PickingsPlacement.MediaRef(kind = "")
        val secs = clip.duration ?: (if (clip.kind == Attachment.Kind.VIDEO) com.toolsboox.plugin.calendar.ot.AvPoster.durationMs(File(dir, clip.filename)) / 1000.0 else 0.0)
        return PickingsPlacement.MediaRef(
            kind = if (clip.kind == Attachment.Kind.VIDEO) "video" else "audio",
            attachmentId = clip.id, durationMs = (secs * 1000).toInt(),
            title = FeedNoteGram.label(a.title, a.feedTitle))
    }

    /**
     * Re-draw the highlight's ONE card from its line, in its designed shape — the same card, in place
     * ([PickingsPlacement.reface]); a card that was taken off the board stays off (edits never add
     * cards). A mark with no card yet — an adopted line from before cards — gets its one, on its own
     * day's All Picks. Returns where the card is now (for the toast), or null when there is none.
     *
     *  - A card carried onto another page under a NEW id ("Bring in a picking") is followed by its
     *    passage and the mark points at it from then on (review 2026-10-12 #7).
     *  - A card not on this device yet (its day hasn't synced), or a face that would be drawn without
     *    a photo / video / handwriting the other device made and this one hasn't received yet (review
     *    2026-10-16 #3 — that face would win the element merge on BOTH devices): remembered, and
     *    re-drawn when the day or the file arrives ([drainWaiting], after every sync; [retryStaleFaces]
     *    on open).
     *  - The first card for a cardless mark is CHECKED AND SET (review #11).
     *  - A card whose page is there but won't read (day-file guard) is NOT gone: nothing is written
     *    over that page, the face is remembered as waiting ([drain] re-draws it once the page reads),
     *    and [CalendarDayService.UnreadableDayException] goes up for the caller to say
     *    ("Couldn't change the pick — …", [MarkWords.CHANGE_THE_PICK]).
     *  - Every re-draw files the line's recordings into the card day's A/V list in the same locked
     *    save (iOS `MarkPick.reface(avFrom:)`; merge-addpick review #8): a clip added while that day
     *    wouldn't read joins it when the waiting face is re-drawn, so the card's ▶ finds its file.
     *    One taken off that day's list on purpose (its tombstone there) is not put back.
     */
    fun reface(a: ArticleHighlights.Article, m: BookNote): String? {
        val (day, line) = lineOf(m) ?: return null
        val atts = line.attachments.orEmpty()
        if (com.toolsboox.plugin.calendar.ot.PickFaceAdds.missing(dir, atts).isNotEmpty()) {
            Timber.i("article highlight %s: a file its face draws isn't here yet — re-faced when it lands", m.id)
            staleFaces(a.key, m.id, true)
            return if (m.pickId.isNotBlank()) m.cardDay?.let { whereCard(a, it, m.pickId) } else null
        }
        val rendered = face(a, m, line) ?: return null
        val media = mediaSlot(a, atts)
        val slots = PickingsPlacement.storedSlots(rendered.layout, treatment = true)
        val fileClips = clipsOnto(atts)
        if (m.pickId.isNotBlank()) {
            // On the CARD's day (± a day for a clock that crossed midnight), not the line's.
            val near = try {
                PickingsPlacement.refaceNear(service, root(), ReadingEventEdit.candidateDays(m.cardDay ?: day), m.pickId,
                    rendered.bitmap, media, faceSlots = slots, alsoOnDay = fileClips)
            } catch (e: CalendarDayService.UnreadableDayException) {
                staleFaces(a.key, m.id, true); throw e
            }
            near?.let { d -> staleFaces(a.key, m.id, false); return whereCard(a, d, m.pickId) }
            followCard(a, m)?.let { (d, id) ->
                val refaced = try { PickingsPlacement.reface(service, root(), d, id, rendered.bitmap, media, faceSlots = slots, alsoOnDay = fileClips) }
                    catch (e: CalendarDayService.UnreadableDayException) { staleFaces(a.key, m.id, true); throw e }
                if (refaced) {
                    ArticleNoteStore.update(app, a.key, m.id) { it.copy(pickId = id, pickDay = d.toString()) }
                    staleFaces(a.key, m.id, false)
                    return whereCard(a, d, id)
                }
            }
            if (cardRemoved(m)) {
                Timber.i("article highlight %s: its card was taken off — not re-added", m.id)
                staleFaces(a.key, m.id, false)
            } else staleFaces(a.key, m.id, true)
            return null
        }
        val whole = m.type == BookNote.NOTE
        val pickId = UUID.randomUUID()
        val placed = runCatching {
            PickingsPlacement.place(service, root(), rendered.bitmap, day, CalendarDayPageNotes.GRAM_PICKS,
                sourceLink = a.httpUrl, sourceLabel = FeedNoteGram.label(a.title, a.feedTitle),
                // A whole-piece note's card carries its words but is no passage: no cardText, so it
                // never "highlights back" onto the page as if it were one.
                cardText = if (whole) "" else m.text, sourceFeed = a.feedTitle, elementId = pickId,
                media = media.takeIf { it.kind.isNotBlank() }, pastDayMustExist = true, faceSlots = slots,
                alsoOnDay = fileClips)
        }.onFailure { Timber.w(it, "article highlight %s: its first card wasn't placed", m.id) }
        // Its day there but unreadable: said by the caller, never "no card" (day-file guard) — and
        // remembered, like a re-face's refusal: [drain] places its first card once the page reads.
        (placed.exceptionOrNull() as? CalendarDayService.UnreadableDayException)?.let { staleFaces(a.key, m.id, true); throw it }
        if (placed.isFailure) return null
        staleFaces(a.key, m.id, false)
        val mine = pickId.toString()
        ArticleNoteStore.update(app, a.key, m.id) { held ->
            if (held.pickId.isBlank()) held.copy(pickId = mine, pickDay = day.toString()) else null
        }
        val held = ArticleNoteStore.stored(app, a.key, m.id)
        when {
            // Not in this key's file (found under the cleaned-URL fallback): filed here now.
            held == null -> ArticleNoteStore.put(app, a.key, m.copy(pickId = mine, pickDay = day.toString(),
                updatedAt = System.currentTimeMillis()))
            held.pickId != mine -> {
                MarkCard.remove(service, root(), day, mine)
                return held.cardDay?.let { whereCard(a, it, held.pickId) }
            }
        }
        return "All Picks"
    }

    /** The line's recordings ([atts] — audio and video) onto a card day's A/V list, each once; one
     *  tombstoned there (taken off that list on purpose) stays off. Null when there are none. */
    private fun clipsOnto(atts: List<Attachment>): ((com.toolsboox.plugin.calendar.da.v2.CalendarDay) -> Unit)? {
        val clips = atts.filter { it.kind != Attachment.Kind.PHOTO }
        if (clips.isEmpty()) return null
        return { day ->
            for (c in clips) {
                if (day.avGrams.any { it.id.equals(c.id, ignoreCase = true) }) continue
                if (day.deletedElementIds.any { it.equals(c.id, ignoreCase = true) }) continue
                day.avGrams.add(c)
            }
        }
    }

    /** Where card [id] on [day] is, named for the toast — from the index; All Picks when it can't say. */
    private fun whereCard(a: ArticleHighlights.Article, day: LocalDate, id: String): String {
        val page = ArticlePickIndex.hits(app, a.key).firstOrNull { it.elementId.equals(id, ignoreCase = true) }?.page
            ?: return "All Picks"
        return if (page.substringBefore('#') == CalendarDayPageNotes.GRAM_PICKS) "All Picks" else MarkCard.boardName(app, day, page)
    }

    /** The mark's card under ANOTHER id — the same passage's pick of this piece that no other mark
     *  (live or removed) claims, newest first (review 2026-10-12 #7). */
    private fun followCard(a: ArticleHighlights.Article, m: BookNote): Pair<LocalDate, String>? {
        val n = ArticleMarkSet.norm(m.text)
        if (n.isBlank()) return null
        val claimed = ArticleNoteStore.listWithTombstones(app, a.key, a.clean)
            .filter { it.id != m.id }.map { it.pickId.lowercase() }.filter { it.isNotBlank() }.toSet()
        return ArticlePickIndex.hits(app, a.key)
            .filter { it.elementId.isNotBlank() && !it.elementId.equals(m.pickId, ignoreCase = true) &&
                it.elementId.lowercase() !in claimed && ArticleMarkSet.norm(it.text) == n }
            .maxByOrNull { it.date }?.let { it.date to it.elementId }
    }

    /** True when the mark's card was taken off on purpose — its marker is on its day here. */
    private fun cardRemoved(m: BookNote): Boolean {
        val day = m.cardDay ?: return false
        return ReadingEventEdit.candidateDays(day).any { d ->
            service.loadOrNull(root(), d, Locale.getDefault())?.deletedElementIds
                ?.any { it.equals(m.pickId, ignoreCase = true) } == true
        }
    }

    /** The marks whose card face waits for its day or its files ("<key>|<mark id>", this device only). */
    fun staleFaces(key: String, markId: String, stale: Boolean) = Companion.staleFaces(app, key, markId, stale)

    /** Re-draw the cards that were waiting — this piece's — and replay its waiting adds. One that
     *  still can't be found keeps waiting; one taken off on purpose is dropped. */
    fun retryStaleFaces(a: ArticleHighlights.Article) = drain { it == a.key }

    /**
     * THE WAITING, DRAINED: every add whose line hadn't arrived — or whose page wouldn't read
     * ([waitFor]) — is replayed onto it once it can be, every face that waited for its day or its
     * files is re-drawn once they are here, and every clip a removal couldn't take off its A/V list
     * ([waitDrop]) comes off once that page reads. [keyOk] picks the articles to try (all of them
     * after a sync). Off the main thread.
     */
    fun drain(keyOk: (String) -> Boolean) {
        val p = app.getSharedPreferences(PREFS, 0)
        for (raw in p.getStringSet(KEY_WAITING_ADDS, emptySet()).orEmpty().toList()) {
            val w = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: run { unwait(raw); null } ?: continue
            val key = w.optString("k"); val id = w.optString("m")
            if (!keyOk(key)) continue
            val m = ArticleNoteStore.stored(app, key, id) ?: run { unwait(raw); null } ?: continue
            val a = articleOf(m)
            val att = w.optJSONObject("a")?.let { attachmentFromJson(it) }
            val words = if (w.has("w")) w.optString("w") else null
            // One that fails is tried again on the next pass; the others still go.
            val out = runCatching {
                when {
                    att != null -> attach(a, m, att, "", replay = true)
                    words != null -> text(a, m, words, replay = true)
                    else -> Outcome.Landed(null)
                }
            }.onFailure { Timber.w(it, "add to pick %s: a waiting add failed — it keeps waiting", id) }.getOrNull() ?: continue
            // Landed (or the highlight went and it landed on its line): no longer waiting. Its day
            // there but still unreadable (day-file guard): it keeps waiting, quietly — it was told
            // it would land, and lands once the page reads.
            if (out is Outcome.Refused) continue
            if (out is Outcome.Landed || lineOf(fresh(a, m)) != null || fresh(a, m).deletedAt != null) unwait(raw)
        }
        for (entry in p.getStringSet(KEY_STALE, emptySet()).orEmpty().toList()) {
            val key = entry.substringBefore('|'); val id = entry.substringAfter('|')
            if (!keyOk(key)) continue
            val m = ArticleNoteStore.get(app, key, id)
            if (m == null) { staleFaces(key, id, false); continue }
            // A page that still won't read keeps it waiting (reface marks it) — the next one is tried.
            runCatching { reface(articleOf(m), m) }.onFailure { Timber.w(it, "add to pick %s: its pick's face still waits", id) }
        }
        for (entry in p.getStringSet(KEY_WAITING_DROPS, emptySet()).orEmpty().toList()) {
            val parts = entry.split('|')
            val day = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val clip = parts.getOrNull(2).orEmpty()
            if (parts.size != 3 || day == null || clip.isBlank()) { unwaitDrop(entry); continue }
            if (!keyOk(parts[0])) continue
            // Off its list now, or that list reads and doesn't hold it: done. Still unreadable: waits.
            val still = runCatching { dropClips(day, listOf(clip)) }
                .onFailure { Timber.w(it, "add to pick: a clip's removal from its A/V list still waits") }
            if (still.isSuccess && still.getOrNull() == null) unwaitDrop(entry)
        }
    }

    /** Keep a clip a removal couldn't take off [day]'s A/V list (that page won't read) — [drain]
     *  takes it off once it reads, so the pick it left doesn't keep it there after the heal. */
    private fun waitDrop(key: String, day: LocalDate, clipId: String) = synchronized(LOCK) {
        val p = app.getSharedPreferences(PREFS, 0)
        val held = p.getStringSet(KEY_WAITING_DROPS, emptySet()).orEmpty()
        val entry = "$key|$day|$clipId"
        if (entry !in held) p.edit().putStringSet(KEY_WAITING_DROPS, held + entry).apply()
    }

    private fun unwaitDrop(entry: String) = synchronized(LOCK) {
        val p = app.getSharedPreferences(PREFS, 0)
        val held = p.getStringSet(KEY_WAITING_DROPS, emptySet()).orEmpty()
        if (entry in held) p.edit().putStringSet(KEY_WAITING_DROPS, held - entry).apply()
    }

    /** A typed note that landed: the one still waiting for this mark (older words) is let go, so the
     *  drain never replays it over the newer note. */
    private fun unwaitWords(key: String, markId: String) = synchronized(LOCK) {
        val p = app.getSharedPreferences(PREFS, 0)
        val held = p.getStringSet(KEY_WAITING_ADDS, emptySet()).orEmpty()
        val kept = held.filterNot { r ->
            runCatching { org.json.JSONObject(r) }.getOrNull()?.let { it.optString("k") == key && it.optString("m") == markId && it.has("w") } == true
        }.toSet()
        if (kept.size != held.size) p.edit().putStringSet(KEY_WAITING_ADDS, kept).apply()
    }

    /** Keep an add whose line isn't on this device yet, or whose page won't read — replayed by [drain]. */
    private fun waitFor(a: ArticleHighlights.Article, m: BookNote, att: Attachment? = null, words: String? = null) {
        val o = org.json.JSONObject().put("k", a.key).put("m", m.id).put("t", System.currentTimeMillis())
        att?.let { o.put("a", attachmentJson(it)) }
        words?.let { o.put("w", it) }
        synchronized(LOCK) {
            val p = app.getSharedPreferences(PREFS, 0)
            val held = p.getStringSet(KEY_WAITING_ADDS, emptySet()).orEmpty()
            // A typed note replaces the one waiting before it (the line keeps one note).
            val kept = if (words == null) held else held.filterNot { r ->
                runCatching { org.json.JSONObject(r) }.getOrNull()?.let { it.optString("k") == a.key && it.optString("m") == m.id && it.has("w") } == true
            }.toSet()
            p.edit().putStringSet(KEY_WAITING_ADDS, kept + o.toString()).apply()
        }
    }

    private fun unwait(raw: String) = synchronized(LOCK) {
        val p = app.getSharedPreferences(PREFS, 0)
        val held = p.getStringSet(KEY_WAITING_ADDS, emptySet()).orEmpty()
        if (raw in held) p.edit().putStringSet(KEY_WAITING_ADDS, held - raw).apply()
    }

    companion object {
        private const val PREFS = "ledger_article_marks"
        private const val KEY_STALE = "stale_faces"
        private const val KEY_WAITING_ADDS = "waiting_adds"
        /** Clips a removal couldn't take off an A/V list yet ("<key>|<day>|<clip id>"). */
        private const val KEY_WAITING_DROPS = "waiting_drops"
        private val LOCK = Any()

        /** True when the face of mark [markId] waits for its day or its files. */
        fun isStale(app: Context, key: String, markId: String): Boolean =
            "$key|$markId" in app.getSharedPreferences(PREFS, 0).getStringSet(KEY_STALE, emptySet()).orEmpty()

        fun staleFaces(app: Context, key: String, markId: String, stale: Boolean) = synchronized(LOCK) {
            val p = app.getSharedPreferences(PREFS, 0)
            val held = p.getStringSet(KEY_STALE, emptySet()).orEmpty()
            val entry = "$key|$markId"
            if (stale == (entry in held)) return@synchronized
            p.edit().putStringSet(KEY_STALE, if (stale) held + entry else held - entry).apply()
        }

        /** True when anything waits — a face or an add — so a sync pass only drains when it must. */
        fun anyWaiting(app: Context): Boolean {
            val p = app.getSharedPreferences(PREFS, 0)
            return p.getStringSet(KEY_STALE, emptySet()).orEmpty().isNotEmpty() ||
                p.getStringSet(KEY_WAITING_ADDS, emptySet()).orEmpty().isNotEmpty() ||
                p.getStringSet(KEY_WAITING_DROPS, emptySet()).orEmpty().isNotEmpty()
        }

        /**
         * After a sync pass has pulled the days and the attachments (CalendarWebDavSyncService): the
         * faces and adds that were waiting for them, drained — not only when their article is opened
         * again (review 2026-10-16 #3). Quiet unless something lands.
         */
        fun drainWaiting(context: Context) {
            val app = context.applicationContext
            if (!anyWaiting(app)) return
            runCatching {
                PickAdds(app, com.toolsboox.ui.share.ShareActions.service(app), { LedgerPaths.documentsRoot(app) }) { msg ->
                    AddToPick.say(app, msg)
                }.drain { true }
            }.onFailure { Timber.w(it, "add to pick: draining the waiting faces failed") }
        }

        private fun attachmentJson(a: Attachment): org.json.JSONObject = org.json.JSONObject()
            .put("id", a.id).put("kind", when (a.kind) {
                Attachment.Kind.PHOTO -> "photo"; Attachment.Kind.AUDIO -> "audio"; Attachment.Kind.VIDEO -> "video"
            }).put("filename", a.filename)
            .apply { a.duration?.let { put("duration", it) }; a.date?.let { put("date", it.time) } }

        private fun attachmentFromJson(o: org.json.JSONObject): Attachment? {
            val id = o.optString("id").ifBlank { return null }
            val kind = when (o.optString("kind")) {
                "photo" -> Attachment.Kind.PHOTO; "audio" -> Attachment.Kind.AUDIO; "video" -> Attachment.Kind.VIDEO
                else -> return null
            }
            return Attachment(id, kind, o.optString("filename").ifBlank { return null },
                if (o.has("duration")) o.optDouble("duration") else null,
                if (o.has("date")) Date(o.optLong("date")) else null)
        }

        /** The article a mark is about, as the marks name it — for a door with no article open (a
         *  held pick, a Log row). [link] the pick's own source when known. */
        fun articleOf(m: BookNote, link: String = ""): ArticleHighlights.Article =
            ArticleHighlights.Article(entryId = m.entryId, url = link.ifBlank { m.url }, title = m.title,
                feedTitle = m.source, view = m.view.ifBlank { BookNote.VIEW_LIVE })

        /** The live highlight a pick on a page belongs to (pickId == its id), or null. */
        fun markOfPick(app: Context, e: ImageElement): BookNote? {
            val link = e.sourceLink
            if (!link.startsWith("http", ignoreCase = true)) return null
            val id = e.elementId.toString()
            // Its own key only — no walk of every bucket (this runs on a hold, on the main thread).
            return runCatching {
                ArticleNoteStore.list(app, ArticleKey.of(link)).firstOrNull { it.pickId.equals(id, ignoreCase = true) }
            }.getOrNull()
        }

        /** The live highlight a Log line belongs to that has a pick, or null. */
        fun markOfLine(app: Context, lineId: String, url: String?): BookNote? {
            val u = url?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
            return runCatching {
                ArticleNoteStore.list(app, ArticleKey.of(u))
                    .firstOrNull { (it.logId == lineId || it.id == lineId) && it.pickId.isNotBlank() }
            }.getOrNull()
        }

        /**
         * Keep ink as ink-<uuid>.png in the attachment store, the moment it is made — TRIMMED to what
         * was written + 16 (pin §4; review 2026-10-16 B#11: the pad saved the whole page and only this
         * app trimmed at render, so iOS drew Android's handwriting as the whole pad). The attachment's
         * id IS the filename's uuid (iOS's rule — review #7), so either app finds the file by its id.
         * Null: it couldn't be saved (out of space).
         */
        fun saveInk(app: Context, ink: Bitmap): Attachment? {
            val id = UUID.randomUUID().toString()
            val dest = File(LedgerPaths.attachmentsDir(app), "${PickFace.INK_PREFIX}$id.png")
            val trimmed = runCatching { PickFace.trimInk(ink) }.getOrDefault(ink)
            val ok = runCatching { dest.outputStream().use { trimmed.compress(Bitmap.CompressFormat.PNG, 100, it) } }.isSuccess
            if (!ok) { runCatching { dest.delete() }; return null }
            return Attachment(id, Attachment.Kind.PHOTO, dest.name, null, Date())
        }
    }
}

/**
 * "＋ ADD TO THIS PICK" — the ladder, its chips, the failures' words and the players (pin §1, §5, §7).
 * One ladder for every door: the Pick sheet's first row (D1), the Highlight after-step (D2), the hold
 * menu on a pick (D3) and the Log (D4). The canon chooser's order with the extras taken out — ✍ 🎤 📷
 * ⌨ 🖼 🎥 — never ★ (an add never stars), never asking for anything (a bare highlight is complete).
 */
object AddToPick {

    /** What the ladder adds onto: a highlight's pick ([Tied]) or the sheet's waiting adds ([Pending]). */
    interface Sink {
        /** The passage the adds are about (the pads show it). */
        val passage: String
        /** What rides now — off the main thread. */
        fun current(): Current
        fun addText(words: String)
        fun addInk(ink: Bitmap)
        fun addMedia(att: Attachment)
        fun remove(att: Attachment)
    }

    data class Current(val note: String, val attachments: List<Attachment>) {
        val isEmpty: Boolean get() = note.isBlank() && attachments.isEmpty()
    }

    /** The capture a ladder row asks the host for. */
    enum class Capture { VOICE, CAMERA, GALLERY, VIDEO }

    /**
     * TIED: a highlight's own pick — each add goes straight onto its line and re-faces its card. The
     * mark is RE-READ before every add and after it (review 2026-10-16 #11): a sheet that held the
     * copy it opened with made a new Log line on each add once the first had made the line again,
     * and its chips, open rows and Remove list stayed empty on an adopted highlight.
     */
    class Tied(
        val engine: PickAdds, val article: ArticleHighlights.Article, mark: BookNote,
        /** Hears the toast (null: already said) after each change, off the main thread. */
        private val after: (String?) -> Unit,
    ) : Sink {
        /** The mark as last read. */
        @Volatile var mark: BookNote = mark
            private set
        /** The open sheet's redraw (main thread) — the Pick sheet sets it while it is up. */
        @Volatile var onChange: (() -> Unit)? = null
        override val passage: String get() = mark.text
        /** The mark as stored now (its face, its line) — and kept. */
        fun markNow(): BookNote = engine.fresh(article, mark).also { mark = it }
        override fun current(): Current = engine.current(markNow())
        override fun addText(words: String) = done(engine.addText(article, markNow(), words))
        override fun addInk(ink: Bitmap) = done(engine.addInk(article, markNow(), ink))
        override fun addMedia(att: Attachment) = done(engine.addMedia(article, markNow(), att))
        override fun remove(att: Attachment) = done(engine.removeAttachment(article, markNow(), att))
        private fun done(msg: String?) { markNow(); after(msg); onChange?.let { f -> main { f() } } }
        /** What the face draws now — the line's adds, decoded off the main thread. */
        fun adds(dir: File): PickFace.Adds =
            current().let { PickFace.fromLine(dir, it.note, it.attachments) }
    }

    /**
     * UNTIED (the lasso's 🃏, the comic panel's 🃏): each file goes into the attachment store the
     * moment it is made (never lost), then waits here; on place the host bakes it into the face, its
     * faceSlots and its media slot ([stamp]) and files the clip into the day's A/V list ([clips]).
     */
    class Pending(private val app: Context, override val passage: String) : Sink {
        @Volatile private var note: String = ""
        private val held = java.util.Collections.synchronizedList(mutableListOf<Attachment>())
        /** The sheet's redraw (main thread). */
        var onChange: (() -> Unit)? = null

        /** A card that wears these adds was placed (or is being: ❝ / Here was pressed) — they have a
         *  home; otherwise [keepUnplaced] files them on the day when the sheet closes. */
        @Volatile var placed: Boolean = false

        override fun current(): Current = Current(note, held.toList())
        override fun addText(words: String) { note = words; changed() }
        override fun addInk(ink: Bitmap) {
            val att = PickAdds.saveInk(app, ink) ?: return say(app, AddToPickWords.OUT_OF_SPACE)
            held += att; changed()
        }
        override fun addMedia(att: Attachment) { held += att; changed() }
        override fun remove(att: Attachment) { held.removeAll { it.id == att.id }; changed() }

        private fun changed() { val f = onChange ?: return; main { f() } }

        /** What the face draws — decoded off the main thread. */
        fun adds(picture: Bitmap? = null): PickFace.Adds =
            PickFace.fromLine(LedgerPaths.attachmentsDir(app), note, held.toList(), picture)

        /** The clips to file into the placing day's A/V list. */
        fun clips(): List<Attachment> = held.filter { it.kind != Attachment.Kind.PHOTO }

        /** Everything waiting here, files and all — what [keepUnplaced] keeps. */
        fun attachments(): List<Attachment> = held.toList()

        /** The media slot a placed card wears (a video if any, else a voice note; the NEWEST by
         *  date, [PickFace.newestFirst]), or null. */
        fun media(title: String = ""): PickingsPlacement.MediaRef? {
            val clip = com.toolsboox.plugin.calendar.ot.PickFaceAdds.mediaClip(held.toList()) ?: return null
            return PickingsPlacement.MediaRef(kind = if (clip.kind == Attachment.Kind.VIDEO) "video" else "audio",
                attachmentId = clip.id, durationMs = ((clip.duration ?: 0.0) * 1000).toInt(), title = title)
        }

        /** The layout of the card the sheet last drew with these adds on it (card 1/n) — set by the
         *  sheet, read on place. */
        @Volatile var layout: PickFaceLayout.Layout? = null

        /** Dress a placed element with what waits here: its slots ([treatment]: the face was taped
         *  down by CardTreatment on the way in) and its media slot. */
        fun stamp(e: ImageElement, treatment: Boolean) {
            placed = true
            e.faceSlots = layout?.let { PickingsPlacement.storedSlots(it, treatment) }?.takeIf { it.isNotEmpty() }
            media(e.sourceLabel)?.let { m ->
                e.mediaKind = m.kind; e.attachmentId = m.attachmentId; e.durationMs = m.durationMs
                if (e.mediaTitle.isBlank()) e.mediaTitle = m.title
            }
        }

        val isEmpty: Boolean get() = note.isBlank() && held.isEmpty()
    }

    // ── The chips under the sheet's first row ─────────────────────────────────────────────────

    /** The pick's things as chips (✍ 📷 🎥 0:41 🎤 0:23), in the order they were added. */
    fun chips(c: Current): List<Pair<String, Attachment>> = c.attachments.map { a ->
        when {
            PickFace.isInk(a) -> AddToPickWords.CHIP_INK
            a.kind == Attachment.Kind.PHOTO -> AddToPickWords.CHIP_PHOTO
            a.kind == Attachment.Kind.VIDEO -> AddToPickWords.chipVideo(PickFaceLayout.clock(a.duration?.toInt() ?: 0))
            else -> AddToPickWords.chipVoice(PickFaceLayout.clock(a.duration?.toInt() ?: 0))
        } to a
    }

    // ── The ladder ────────────────────────────────────────────────────────────────────────────

    /**
     * The Add ladder. Above row 1, when the pick already has things: `🗒 “…” — open` and
     * `✍ The handwriting — open`. Below row 6: `🗑 Remove from this pick…`.
     */
    fun showLadder(fragment: ScreenFragment, sink: Sink) {
        val ctx = fragment.requireContext()
        background {
            val cur = runCatching { sink.current() }.getOrDefault(Current("", emptyList()))
            main {
                if (!fragment.isAdded) return@main
                val rows = mutableListOf<Pair<String, () -> Unit>>()
                if (cur.note.isNotBlank()) rows += AddToPickWords.openNote(preview(cur.note, 80)) to { typeNote(fragment, sink, cur.note) }
                val ink = PickFace.newestFirst(cur.attachments).firstOrNull { PickFace.isInk(it) }
                if (ink != null) rows += AddToPickWords.OPEN_INK to { openInk(fragment, sink, ink) }
                rows += AddToPickWords.HANDWRITE to {
                    FeedNoteGram.inkNoteDialog(fragment, sink.passage) { bmp -> if (bmp != null) background { sink.addInk(bmp) } }
                }
                rows += AddToPickWords.VOICE to { fragment.captureForPick(Capture.VOICE) { att -> background { sink.addMedia(att) } } }
                rows += AddToPickWords.TAKE_PHOTO to { fragment.captureForPick(Capture.CAMERA) { att -> background { sink.addMedia(att) } } }
                rows += AddToPickWords.TYPE to { typeNote(fragment, sink, cur.note) }
                rows += AddToPickWords.CHOOSE_PICTURE to { fragment.captureForPick(Capture.GALLERY) { att -> background { sink.addMedia(att) } } }
                rows += AddToPickWords.VIDEO to { fragment.captureForPick(Capture.VIDEO) { att -> background { sink.addMedia(att) } } }
                if (cur.attachments.isNotEmpty()) rows += AddToPickWords.REMOVE to { showRemove(fragment, sink, cur) }
                fragment.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
                    .setTitle(AddToPickWords.ADD)
                    .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create())
            }
        }
    }

    private fun typeNote(fragment: ScreenFragment, sink: Sink, current: String) {
        FeedNoteGram.typeNoteDialog(fragment, sink.passage, current) { words ->
            if (words != current) background { sink.addText(words) }
        }
    }

    /** The handwriting, shown — and another page of it, if you like. */
    private fun openInk(fragment: ScreenFragment, sink: Sink, ink: Attachment) {
        val ctx = fragment.requireContext()
        background {
            val bmp = PickFace.decode(File(LedgerPaths.attachmentsDir(ctx), ink.filename), 1400)
            main {
                if (!fragment.isAdded) return@main
                if (bmp == null) { say(ctx, AddToPickWords.NOT_HERE_YET); return@main }
                val img = android.widget.ImageView(ctx).apply { setImageBitmap(bmp); adjustViewBounds = true }
                fragment.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
                    .setTitle("✍  Handwritten note").setView(img)
                    .setPositiveButton("Write another") { _, _ ->
                        FeedNoteGram.inkNoteDialog(fragment, sink.passage) { more -> if (more != null) background { sink.addInk(more) } }
                    }
                    .setNegativeButton(android.R.string.ok, null).create())
            }
        }
    }

    /** 🗑 — what's attached, one row each; a removed one leaves the line and the card is re-faced. */
    private fun showRemove(fragment: ScreenFragment, sink: Sink, cur: Current) {
        val ctx = fragment.requireContext()
        val items = chips(cur).asReversed()
        fragment.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(AddToPickWords.REMOVE)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> background { sink.remove(items[which].second) } }
            .setNegativeButton(android.R.string.cancel, null).create())
    }

    // ── Open / play what is on a pick (pin §5, §6) ────────────────────────────────────────────

    /** A photo, the handwriting, a video or a voice note — full screen / the player. */
    fun open(fragment: ScreenFragment, att: Attachment, title: String = "") {
        val ctx = fragment.requireContext()
        val f = File(LedgerPaths.attachmentsDir(ctx), att.filename)
        if (!f.exists()) { say(ctx, AddToPickWords.NOT_HERE_YET); return }
        when (att.kind) {
            Attachment.Kind.PHOTO -> openImage(ctx, f)
            Attachment.Kind.VIDEO -> AvPlayback.play(ctx, "video", f, "", title, 0)
            Attachment.Kind.AUDIO -> playVoice(ctx, f, title)
        }
    }

    /** Full screen in the system viewer — the Log's own way (ReadingLogFragment.openImage). */
    fun openImage(ctx: Context, f: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { say(ctx, AddToPickWords.NOT_HERE_YET) }
    }

    /** The shared player, with its transport — the Log's voice memos' own (toggleAudio). */
    fun playVoice(ctx: Context, f: File, title: String) {
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        player.startAudio(ctx, title.ifBlank { "🎤 Voice note" }, null, null, f.absolutePath)
        player.showModal(ctx)
    }

    /**
     * The file a slot names, found in the store by its id — `ink-<id>.png`, `photo-<id>.jpg`,
     * `video-<id>.mp4` (or .mov / .m4v, an iOS export that fell back), `voice-<id>.m4a` (or .aac),
     * as either app names what it makes (iOS `AddToPick` tries the same names). Null when none is
     * here.
     */
    fun byName(dir: File, slot: com.toolsboox.da.FaceSlot): Attachment? {
        val names = when (slot.k) {
            PickFaceLayout.Kind.INK.key -> listOf("${PickFace.INK_PREFIX}%s.png")
            PickFaceLayout.Kind.PHOTO.key -> listOf("photo-%s.jpg", "photo-%s.jpeg", "photo-%s.png")
            PickFaceLayout.Kind.VIDEO.key -> listOf("video-%s.mp4", "video-%s.mov", "video-%s.m4v")
            PickFaceLayout.Kind.VOICE.key -> listOf("voice-%s.m4a", "voice-%s.aac", "voice-%s.mp4")
            else -> return null
        }
        val ids = listOf(slot.a, slot.a.lowercase(), slot.a.uppercase()).distinct().filter { it.isNotBlank() }
        val kind = when (slot.k) {
            PickFaceLayout.Kind.VIDEO.key -> Attachment.Kind.VIDEO
            PickFaceLayout.Kind.VOICE.key -> Attachment.Kind.AUDIO
            else -> Attachment.Kind.PHOTO
        }
        for (id in ids) for (n in names) {
            val f = File(dir, n.format(id))
            if (f.exists()) return Attachment(slot.a, kind, f.name, null, null)
        }
        return null
    }

    /**
     * THE SELECTION'S OWN PICTURE for a re-face (pin §4 "Picture on the pick"; review 2026-10-16
     * B#13): the picture the passage swept over, as the mark's anchor records it (`img`, which iOS
     * writes) — fetched once each, briefly, and kept for the session. Null when the mark has none or
     * it can't be had now (the face is drawn without it, never waiting on the network). Off the main
     * thread.
     */
    fun selectionPicture(m: BookNote): Bitmap? {
        val src = runCatching { org.json.JSONObject(m.cfi).optString("img") }.getOrNull().orEmpty()
        if (!src.startsWith("http", ignoreCase = true)) return null
        synchronized(pictures) { pictures[src]?.let { return it } }
        val bmp = runCatching {
            val conn = java.net.URL(src).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 4_000; conn.readTimeout = 6_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) LedgerReader/1.0")
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        synchronized(pictures) { pictures[src] = bmp }
        return bmp
    }

    private val pictures = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }

    /** The attachment [id] rides on — the line of [mark], found by id. */
    fun attachmentOf(engine: PickAdds, mark: BookNote, id: String): Attachment? =
        engine.lineOf(mark)?.second?.attachments?.firstOrNull { it.id.equals(id, ignoreCase = true) }

    // ── A pick on the page: its tap (pin §5) and its hold menu (pin §1, D3) ───────────────────

    /** The face slot under the canvas point ([cx], [cy]) on [e] — its rotation undone — or null
     *  (old cards have no slots: the tap does what it always did). */
    fun slotAt(e: ImageElement, cx: Float, cy: Float): com.toolsboox.da.FaceSlot? {
        val slots = e.faceSlots?.takeIf { it.isNotEmpty() } ?: return null
        var px = cx; var py = cy
        if (e.rotation != 0f) {
            val ox = e.x + e.width / 2f; val oy = e.y + e.height / 2f
            val r = Math.toRadians(-e.rotation.toDouble())
            val dx = cx - ox; val dy = cy - oy
            px = (ox + dx * Math.cos(r) - dy * Math.sin(r)).toFloat()
            py = (oy + dx * Math.sin(r) + dy * Math.cos(r)).toFloat()
        }
        return slots.firstOrNull { it.hit(px - e.x, py - e.y, e.width, e.height) }
    }

    /** Play or open what [slot] holds — the clip from the open day's A/V list when it is there,
     *  else the highlight's line, else the store BY NAME (review 2026-10-16 #7: a studio card has no
     *  highlight and a kept pick loses its own, so their photos and handwriting always said "still on
     *  its way"; the id is the filename's uuid on both apps, so the file is found from the slot
     *  alone — iOS `AddToPick.attachment`); not on this device yet → say so. */
    fun openSlot(fragment: ScreenFragment, service: CalendarDayService, root: File, e: ImageElement,
                 slot: com.toolsboox.da.FaceSlot, avGrams: List<Attachment>) {
        avGrams.firstOrNull { it.id.equals(slot.a, ignoreCase = true) }?.let { open(fragment, it, e.mediaTitle); return }
        val app = fragment.requireContext().applicationContext
        background {
            val att = PickAdds.markOfPick(app, e)?.let { m ->
                PickAdds(app, service, { root }) { }.lineOf(m)?.second?.attachments
                    ?.firstOrNull { it.id.equals(slot.a, ignoreCase = true) }
            } ?: byName(LedgerPaths.attachmentsDir(app), slot)
            main {
                if (!fragment.isAdded) return@main
                if (att != null) open(fragment, att, e.mediaTitle) else say(app, AddToPickWords.NOT_HERE_YET)
            }
        }
    }

    /**
     * The hold menu's rows for a pick: `▶ Play video note` and `🎤 Play voice note`, one per slot,
     * then — on a pick that has its highlight — `＋ Add to this pick`, the ladder TIED to it (the same
     * card re-faced, never a second one). [changed] runs on the main thread after each add (the page
     * holding the day reloads it).
     */
    fun holdRows(fragment: ScreenFragment, service: CalendarDayService, root: File, e: ImageElement,
                 avGrams: List<Attachment>, changed: () -> Unit): List<Pair<String, () -> Unit>> {
        val app = fragment.requireContext().applicationContext
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        for (slot in e.faceSlots.orEmpty()) when (slot.k) {
            PickFaceLayout.Kind.VIDEO.key -> rows += AddToPickWords.PLAY_VIDEO to { openSlot(fragment, service, root, e, slot, avGrams) }
            PickFaceLayout.Kind.VOICE.key -> rows += AddToPickWords.PLAY_VOICE to { openSlot(fragment, service, root, e, slot, avGrams) }
        }
        val mark = PickAdds.markOfPick(app, e)
        if (mark != null) rows += AddToPickWords.ADD to {
            val engine = PickAdds(app, service, { root }) { msg -> say(app, msg) }
            showLadder(fragment, Tied(engine, PickAdds.articleOf(mark, e.sourceLink), mark) { msg ->
                main { msg?.let { say(app, it) }; changed() }
            })
        }
        return rows
    }

    // ── Failures — never a silent nothing (pin §7) ────────────────────────────────────────────

    /** `Microphone is off…` [Open Settings] [Cancel]. */
    fun micOff(fragment: ScreenFragment) {
        val ctx = fragment.context ?: return
        fragment.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setMessage(AddToPickWords.MIC_OFF)
            .setPositiveButton(AddToPickWords.OPEN_SETTINGS) { _, _ -> openSettings(ctx) }
            .setNegativeButton(AddToPickWords.CANCEL, null).create())
    }

    /** `Camera is off…` [Open Settings] [🖼 Choose a picture]. */
    fun cameraOff(fragment: ScreenFragment, video: Boolean, choose: () -> Unit) {
        val ctx = fragment.context ?: return
        fragment.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setMessage(if (video) AddToPickWords.CAMERA_OFF_VIDEO else AddToPickWords.CAMERA_OFF_PHOTO)
            .setPositiveButton(AddToPickWords.OPEN_SETTINGS) { _, _ -> openSettings(ctx) }
            .setNegativeButton(AddToPickWords.CHOOSE_PICTURE) { _, _ -> choose() }.create())
    }

    fun openSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────────────────────

    fun preview(text: String, max: Int): String =
        text.replace('\n', ' ').trim().let { if (it.length > max) it.take(max - 1) + "…" else it }

    fun say(ctx: Context, text: String) = main {
        runCatching { android.widget.Toast.makeText(ctx.applicationContext, text, android.widget.Toast.LENGTH_LONG).show() }
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun main(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) runCatching(block).onFailure { Timber.w(it, "add to pick") }
        else mainHandler.post { runCatching(block).onFailure { Timber.w(it, "add to pick") } }
    }

    fun background(block: () -> Unit) {
        Thread { runCatching(block).onFailure { Timber.w(it, "add to pick (background)") } }
            .apply { isDaemon = true }.start()
    }
}
