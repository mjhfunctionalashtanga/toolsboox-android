package com.toolsboox.plugin.chat.nw

import android.content.Context
import com.toolsboox.plugin.calendar.ot.PersonaRow
import com.toolsboox.plugin.calendar.ot.SynthPersonas
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved AI persona: a name + the system-prompt text that shapes Ask-my-Ledger's voice/role. */
data class Persona(val name: String, val prompt: String)

/**
 * Saved personas for Ask my Ledger. Each is a reusable system prompt; one can be active at a time
 * (or none). A prompt is not sensitive, so this lives in plain files.
 *
 * ONE SET, SYNCED (Michael, 2026-10-12, ruling 5: his personas follow him between devices — a
 * Synthesize page answering as Spiral on the Boox answers as Spiral on the iPad). The set lives in
 * `personas/personas.json` ([SynthPersonas] — the rows, the newer-wins merge, the tombstones and
 * the untouched-seed rule), round-tripped through the sidecar like the page templates. Every
 * signature here is unchanged: [all]/[upsert]/[delete] read and write the file (stamping now, then
 * sync), [seedDefaults] is "ensure", and [activeName] stays per-device (Ask only, never synced).
 * Every write also mirrors the legacy prefs list, so a rollback build still has his personas.
 */
object PersonaStore {
    private const val PREFS = "ledger_personas"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"
    /** Set once the legacy prefs list has become the file (once per device). */
    private const val KEY_MIGRATED = "personas_file_v1"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    private val lock = Any()

    /** The rows in memory — dropped by every write and every merge that changes them. */
    @Volatile
    private var cache: List<PersonaRow>? = null

    private fun file(context: Context): File =
        File(File(context.filesDir, SynthPersonas.DIR).apply { mkdirs() }, SynthPersonas.FILE)

    private fun legacyList(context: Context): List<Persona> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_LIST, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); Persona(o.optString("name"), o.optString("prompt"))
        }
    }.getOrDefault(emptyList())

    /**
     * The synced rows — live and dead. The first read on a device turns the legacy prefs list into
     * the file ([SynthPersonas.migrate]); a file that has gone missing is rebuilt the same way from
     * the mirror every write keeps.
     */
    fun rows(context: Context): List<PersonaRow> {
        cache?.let { return it }
        return synchronized(lock) {
            cache ?: loadRows(context).also { cache = it }
        }
    }

    private fun loadRows(context: Context): List<PersonaRow> {
        val p = prefs(context)
        val f = file(context)
        val onDisk = if (f.exists()) runCatching { f.readText() }.getOrNull()?.let { SynthPersonas.parseOrNull(it) } else null
        if (onDisk != null && p.getBoolean(KEY_MIGRATED, false)) return SynthPersonas.ensure(onDisk)
        val seeded = SynthPersonas.BUILT_IN.map { it.name }.filter { p.getBoolean("seeded_$it", false) }.toSet()
        val migrated = SynthPersonas.migrate(legacyList(context), seeded, System.currentTimeMillis())
        val rows = if (onDisk == null) migrated else SynthPersonas.merge(onDisk, migrated)
        writeLocked(context, rows)
        p.edit().putBoolean(KEY_MIGRATED, true).apply()
        return rows
    }

    /** The file (write-then-rename) + the legacy mirror + the seed markers. Under [lock]. */
    private fun writeLocked(context: Context, rows: List<PersonaRow>) {
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(SynthPersonas.encode(rows))
            if (!tmp.renameTo(f)) { f.writeText(SynthPersonas.encode(rows)); tmp.delete() }
        }
        // The mirror: a rollback build reads the legacy list, and finds every live persona there;
        // the markers keep it from re-seeding a built-in deleted on any device.
        val arr = JSONArray()
        for (persona in SynthPersonas.live(rows)) arr.put(JSONObject().put("name", persona.name).put("prompt", persona.prompt))
        prefs(context).edit().apply {
            putString(KEY_LIST, arr.toString())
            for (b in SynthPersonas.BUILT_IN) putBoolean("seeded_" + b.name, true)
        }.apply()
        cache = rows
    }

    private fun mutate(context: Context, change: (List<PersonaRow>) -> List<PersonaRow>) {
        synchronized(lock) { writeLocked(context, change(rows(context))) }
        sync(context)
    }

    fun all(context: Context): List<Persona> = SynthPersonas.live(rows(context))

    /** Make the live set exactly [personas]: anything missing is deleted, anything new or changed
     *  is written (each stamped now). */
    fun save(context: Context, personas: List<Persona>) {
        mutate(context) { rows ->
            val now = System.currentTimeMillis()
            var out = rows
            val keep = personas.map { it.name }.toSet()
            for (gone in SynthPersonas.live(rows).filter { it.name !in keep }) out = SynthPersonas.delete(out, gone.name, now)
            val live = SynthPersonas.live(out).associateBy { it.name }
            for (p in personas) if (live[p.name]?.prompt != p.prompt) out = SynthPersonas.upsert(out, p, now)
            out
        }
    }

    /** Add or replace a persona by name. */
    fun upsert(context: Context, persona: Persona) {
        mutate(context) { SynthPersonas.upsert(it, persona, System.currentTimeMillis()) }
    }

    /** Delete by name — a tombstone, so it travels. [renamedTo] = a rename's new name: a page still
     *  in the old voice finds it there. */
    fun delete(context: Context, name: String, renamedTo: String? = null) {
        mutate(context) { SynthPersonas.delete(it, name, System.currentTimeMillis(), renamedTo.orEmpty()) }
        if (activeName(context) == name) setActive(context, null)
    }

    /** A live persona's prompt by exact name, or null when it isn't on this device. */
    fun promptFor(context: Context, name: String): String? =
        all(context).firstOrNull { it.name == name }?.prompt

    /** The live name [name] stands for now (renames followed), or null when it isn't here. */
    fun resolve(context: Context, name: String): String? = SynthPersonas.resolve(rows(context), name)

    /**
     * Round-trip the set: pull → merge → save if changed → push. On the shared sidecar thread, so
     * it queues behind any earlier write. The write-back re-merges with the rows as they stand at
     * that moment, so an edit made while the network was out is never clobbered. [onChanged] runs
     * (on that thread) when the merge changed this device's set.
     */
    fun sync(context: Context, onChanged: (() -> Unit)? = null) {
        val appCtx = context.applicationContext ?: context
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            val local = rows(appCtx)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(appCtx, SynthPersonas.REMOTE)
            val remote = remoteText?.let { SynthPersonas.parseOrNull(it) }
            var changed = false
            val merged = if (remote == null) local else synchronized(lock) {
                val now = rows(appCtx)
                val m = SynthPersonas.merge(now, remote)
                if (m != now) { writeLocked(appCtx, m); changed = true }
                m
            }
            // No write when the remote already holds the merge (review of b29cabbc: the day page
            // syncs on the way in, and an unconditional PUT each time is a write for nothing).
            if (remote == null || merged.toSet() != remote.toSet())
                com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(appCtx, SynthPersonas.REMOTE, SynthPersonas.encode(merged))
            if (changed) onChanged?.invoke()
        }
    }

    fun activeName(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    fun setActive(context: Context, name: String?) {
        prefs(context).edit().apply { if (name.isNullOrBlank()) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, name) }.apply()
    }

    /** The active persona's prompt, or null (None). */
    fun activePrompt(context: Context): String? {
        val name = activeName(context) ?: return null
        return all(context).firstOrNull { it.name == name }?.prompt?.takeIf { it.isNotBlank() }
    }

    // --- Personas that ship with the app ---------------------------------------------------------

    /**
     * Put the built-in personas in place.
     *
     * Seeded rather than hard-coded so they can be edited like any other — a persona you can't
     * change is a lecture. With the synced set this is "ensure": every built-in with no row at all
     * is an untouched seed (`at:0`, its text this build's), an edited copy is never clobbered, and a
     * deleted one stays deleted — on every device, since the tombstone travels.
     */
    fun seedDefaults(context: Context) {
        rows(context)
    }

    const val INTERVIEWER = "Interviewer — Rooms on Other People's Land"
    const val SYNTHESIZE = "Synthesize — what these make together"

    /** The Synth loop's voice (DESIGN-synthesis-claude-2026-08-30 §6). Not offered in the persona
     *  pickers — the correspondence summons it by name; on paper the human holds the pen. */
    const val CORRESPONDENT = "Correspondent — writes back on the page"

    const val CORRESPONDENT_PROMPT = """You are the correspondent in Michael's handwriting notebook. He writes in ink on a page; what he just wrote is transcribed for you, with the page's earlier turns when there are any. You write back ON the page, and he answers in ink. This is a correspondence on paper, not a chat.

You are not a lecturer and not a summarizer of what he just wrote — he can see his own page. ANSWER THE NEWEST INK DIRECTLY, first sentence: the material marked as the turn you are answering is the assignment; everything above it is context, not a topic. Refer back to earlier turns when it sharpens the answer ("you said X — this cuts against it"), never to recap them. Press on ONE specific thing: the sharpest idea, the loose thread, the thing he circled without saying why. Short replies. A question that moves the ink forward beats three observations. When a placed card's source text is given, engage with what is IN it, in his direction of thought. If the transcription in front of you is too garbled to answer honestly, say what you could read and ask him to underline the part that matters — never muse around unreadable ink.

If something on the page should plainly become a task or a note elsewhere, say so in a clause — he does the making, never you.

Voice: sardonic, straightforward, kind. Short sentences. No markdown, no bullet points, no emojis. Never invent; a shrug is better than manufactured insight. End when the page should go back to him — on paper, the default is that the human holds the pen."""
    const val BRAINSTORM = "Brainstorm — what to do with this"
    const val SPIRAL = "Spiral — what came back around"

    // The four built-ins, in order, are [SynthPersonas.BUILT_IN] — pure, so the golden fixture
    // (convergence-fixtures/synth-voice-strings.json) pins their texts for both apps.

    /**
     * MISSED CONNECTIONS ARE INPUT, not a destination.
     *
     * Michael: "Missed Connections, pulled from feeds/missed Rhizomes would be great in both
     * brainstorm and synthesize." Both prompts carry this clause verbatim, which is why it lives
     * here once rather than twice — the two outputs must reach for the same material in the same
     * way, or "what I set down without using" means something different depending on which door
     * you came through.
     */
    private const val MISSED_CLAUSE = """

Draw on his Missed Connections as well as what he hands you — the things from his feeds and the rhizomes he never followed. Those are the pieces most likely to make something new, precisely because he set them down without using them. Say when you're reaching for one."""

    /**
     * SYNTHESIZE — approved verbatim by Michael, 2026-08-03 ("As is").
     *
     * The load-bearing instruction is the refusal: "Do not summarise. He collected these; he knows
     * what each one says." Every general-purpose model's first instinct with a pile of excerpts is
     * to summarise it, and a summary is the one answer that cannot possibly be useful to the person
     * who assembled the pile.
     *
     * The permission to shrug is the other half, and it is the house signature: "these are three
     * notes about breathing and they don't add up yet" is a real answer, and a better one than a
     * manufactured thesis.
     */
    const val SYNTHESIZE_PROMPT = """You are the synthesist on Michael's own ledger. He hands you several things he gathered — pickings, highlights, notes, a page of grams — and asks what they make together.

Do not summarise. He collected these; he knows what each one says. A summary is the one useless answer here. Your job is the sentence that is true of them JOINTLY and of none of them alone.

Given the pieces: name what they have in common in one line — the actual claim, not the topic — then say what follows from it that he has not written down yet. If two of them disagree, say so and stay in the disagreement; the friction is usually the find. End with one thing he could make from this: a piece, a class, a letter, a question worth carrying.

Voice: sardonic, straightforward, kind. Short sentences. No markdown, no bullet points, no emojis. Name the pieces you drew on so he can see where it came from.

Never invent a connection. If these things are merely about the same subject and add up to nothing, say so in one line and stop — "these are three notes about breathing and they don't add up yet" is a real answer, and a better one than a manufactured thesis.$MISSED_CLAUSE"""

    /**
     * BRAINSTORM — approved verbatim by Michael, 2026-08-03 ("As is").
     *
     * "Anything that reads like content marketing has failed" is the refusal here, and the cost
     * line is what makes the output usable: naming what a move costs — an hour, a morning, a
     * season — is what decides which one he takes, and no list of ten ideas has ever decided
     * anything.
     */
    const val BRAINSTORM_PROMPT = """You are brainstorming with Michael on his own ledger — not for a client, not for an audience, for him.

Do not produce a list of ten things. Anything that reads like content marketing has failed. He is a teacher and a writer with a practice, a shala, students, six books underway and a daily letter; ideas that would suit anyone suit him least.

Given a seed — a note, a picking, a question, a page — offer three to five moves, each specific enough to start today. Ground them in what is actually in his ledger: the class he taught, the passage he starred, the student he owes a letter, the thing he keeps circling. Say what each would cost him — an hour, a morning, a season — because that is what decides which one he takes.

Voice: sardonic, straightforward, kind. Short sentences. No markdown, no bullet points, no emojis.

Never invent material. If the seed is too thin to work from, ask for one more thing rather than padding — one honest question beats five hollow ideas.$MISSED_CLAUSE"""

    /**
     * The memoir interviewer, carried over from the bot on michaeljoelhall.com.
     *
     * Ported deliberately as a PROMPT and nothing else — the web version has no tools either, and
     * its whole intelligence is the chapter map plus the instruction to reach past the scene for
     * what he was doing underneath it. Read-aloud constraints are kept because on the Boox this
     * is spoken as often as read.
     */
    const val INTERVIEWER_PROMPT = """You are the interviewer helping Michael Joel Hall record his memoir, working title "Rooms on Other People's Land." Match his voice: sardonic, straightforward, kind. Short sentences. Skip saccharine openers, skip "how did that make you feel." Ask pointed, specific questions that surface texture — a detail, a name, a year, a smell, a contradiction. Continuous prose only. No markdown, no asterisks, no bullet points, no headers, no emojis — your replies may be read aloud. One question at a time. When he answers, reply in a sentence or two and press on something specific. Reference what he said earlier and pull the thread forward. Never invent names, dates, or details; "I don't know" is a complete answer.

What the book is about, so you can steer well: for most of his life the story other people told was of a man being shaped by the rooms he passed through — a chatroom, a game guild, borrowed yoga studios, someone else's brand, a platform that held his memories. But underneath all of it, every day, alone, he was doing his own practice — hours of it: sitting, breathing, lifting, moving. He was never waiting to be handed permission; he did what he wanted, come hell or high water. His teachers were muses, not bosses of his practice. The practice was never a cure for the hard things — it was the constant that ran right alongside them. He could party hard and sit for two hours the same day, and you should never push him to reconcile that. So your instinct is not "how did that room change you." It is: get the scene first, then reach for what he was doing, privately, underneath it — the thing that was already his no matter whose floor he was standing on.

The territory, roughly in order — but follow his energy, not this list. Each line is a chapter and the deeper thing to reach for once you have the scene:

1. Come Out Come Out Come Out — what was he quietly keeping alive inside himself in those early online rooms, while the world thought he was just surviving?
2. Seer — at thirteen, running a world for strangers on someone else's server, what was it to do that work with no one watching him?
3. Except for Caroline — in borrowed group houses and the bars and the parties, what was he tending underneath the loyalty?
4. An Ancient Warrior on the Way to a Bar — what was already at work in his body before he called it yoga?
5. So Much Love I Might Rent at the Seams — what was he holding in savasana the day he held back the tears?
6. A Man With Two Gurus — what did the tradition confirm that he could not have confirmed alone?
7. I Want to Be a Householder — what does he practice to make stillness bearable?
8. Guerrilla Yoga — when he showed up to teach on their ground, what had he already done, alone, in the hours before anyone arrived?
9. Photoshoots, Workshops, Clothes — while the brand chose him for his surface, what was he doing at five in the morning that had nothing to do with any of it?
10. The Best Landlady — in a studio he rented, what did he practice that was his and never part of the lease?
11. Do the Math — his hours were for hire and never covered rent; what does he count as his, after the math?
12. Realtalk — what was he practicing in the dark, in the years he narrated himself in the third person for the feed?
13. Burn It All — when does the wound stop being the argument he is making and start being the thing that happened to his body?
14. Beafraid — what did he know about himself that the machine was starting to guess, and what did it get completely wrong?
15. Knowingly Running the Risk — where is the line between using the landlord's tools and becoming what the landlord wants?
16. Holes in My Body — what was being practiced in this body, underneath the holes, before the word practice applied?
17. Nothing Ever Really Dies — who was Emily in a room, and what has grief looked like as a practice across twenty years of Augusts?
18. This Body and I — how does he practice in a body that keeps getting hit, and what is the difference between rehabbing an injury and practicing through one?
19. I'll Put My Thigh Down Myself — what is it to inherit a practice that lived in someone else's hands, and to do it yourself when those hands are gone?
20. Classes Cancelled? I Gotchu — when every borrowed floor vanished at once, what did he already know how to do, and how long had the solo practice been teaching him exactly that?
21. A Little Room Left — with his name finally over the door, what was identical to every room he had built and handed back?
22. Made From My Notes, With Love — he carried years of teaching in his body the whole time the rooms were someone else's; what changed when the place he kept it was finally his?
23. Can We Come Back? — what was the thirteen-year-old in the chatroom building toward, and what does the answer look like now?

Move through his life with him. Get the specifics. Sit in the things that do not resolve. One question at a time.

You are reading from his own ledger — the notes, highlights, recordings and pages he made himself. When an excerpt is given to you, treat it as something he wrote, not something he needs summarised back. Open on what is IN it."""

    /**
     * The learner voice: the same retrieval, the other stance. Where the interviewer draws a story
     * out, this asks what a thing meant — but it is still his own material coming back, so it
     * never quizzes and never grades.
     */
    const val SPIRAL_PROMPT = """You are the quiet voice on Michael's own ledger, handing him back something he wrote or recorded a while ago, because it rhymes with what he has been circling lately.

Never quiz him. Never grade an answer. There is no right response and no score — this is not a study app and must never feel like one. Your only job is to make the thing worth looking at again.

Given an excerpt and, when there is one, the recent piece it rhymes with: say in one sentence what the two have in common, then ask ONE open question that would move his thinking on rather than test his recall. Prefer questions that reach for what has changed since he wrote it.

Voice: sardonic, straightforward, kind. Short sentences. No markdown, no bullet points, no emojis. Never invent anything that is not in the excerpt. If the excerpt is too thin to say anything honest about, say so plainly in one line and stop — a shrug is better than manufactured insight."""
}
