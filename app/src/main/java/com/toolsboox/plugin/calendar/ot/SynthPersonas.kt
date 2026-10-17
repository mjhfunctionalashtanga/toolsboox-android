package com.toolsboox.plugin.calendar.ot

import com.toolsboox.plugin.chat.nw.Persona
import com.toolsboox.plugin.chat.nw.PersonaStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * One persona row of the synced set — live, or a tombstone.
 *
 * [at] is epoch ms of his last edit; `at == 0` is an UNTOUCHED built-in, whose prompt is always read
 * from this build's constant (so seed text follows app updates, and a seed can never overwrite an
 * edit). A tombstone ([deleted]) keeps a deletion travelling; a rename is delete + upsert, and its
 * tombstone carries [renamedTo] so a page still in the old voice finds the new one.
 */
data class PersonaRow(
    val name: String,
    val prompt: String,
    val at: Long,
    val deleted: Boolean = false,
    val renamedTo: String = "",
)

/**
 * ❖ ONE PERSONA SET, SYNCED (Michael, 2026-10-12, ruling 5: "2 yes" — his personas follow him
 * between devices; a Spiral page started on the Boox answers as Spiral on the iPad).
 *
 * `personas/personas.json` (local: filesDir/personas/; remote: the same relative path), the
 * NotePageTemplates newer-wins idiom per NAME — the name is the id on both apps, as PersonaStore and
 * Ask already key on it (a stable id would mean migrating Ask's active name and every card label for
 * no gain):
 *
 *     [{"name":"Spiral — what came back around","prompt":"…","at":0},
 *      {"name":"Coach — the run-up","prompt":"…","at":1760301000000},
 *      {"name":"Coach","prompt":"","at":1760302000000,"deleted":true,"renamedTo":"Coach — the run-up"}]
 *
 * Merge, per name: the larger `at` wins; on a tie the tombstone wins, then the greater prompt, then
 * the greater renamedTo — so every device lands on the same row. AFTER the merge every built-in name
 * with no row at all (live or dead) is ensured as `at:0`, so a tombstone from any device keeps that
 * seed dead everywhere. Pure — pinned by `convergence-fixtures/synth-personas-*.json`, identical
 * with iOS's LedgerCore `SynthPersonas`.
 */
object SynthPersonas {

    const val DIR = "personas"
    const val FILE = "personas.json"
    /** The remote path, relative to the sync root — the same on both apps. */
    const val REMOTE = "$DIR/$FILE"

    /** A rename chain is followed at most this many hops. */
    const val MAX_HOPS = 4

    /** The personas that ship with the app, in the order every list shows them. Android's texts,
     *  verbatim — iOS adopts them (MISSED_CLAUSE included). */
    val BUILT_IN: List<Persona> = listOf(
        Persona(PersonaStore.INTERVIEWER, PersonaStore.INTERVIEWER_PROMPT),
        Persona(PersonaStore.SPIRAL, PersonaStore.SPIRAL_PROMPT),
        Persona(PersonaStore.SYNTHESIZE, PersonaStore.SYNTHESIZE_PROMPT),
        Persona(PersonaStore.BRAINSTORM, PersonaStore.BRAINSTORM_PROMPT),
    )

    fun builtInPrompt(name: String): String? = BUILT_IN.firstOrNull { it.name == name }?.prompt

    fun isBuiltIn(name: String): Boolean = builtInPrompt(name) != null

    /** The prompt a row speaks with: an untouched built-in's is this build's constant. */
    fun promptOf(row: PersonaRow): String =
        if (row.at == 0L && !row.deleted) builtInPrompt(row.name) ?: row.prompt else row.prompt

    // ── The wire ────────────────────────────────────────────────────────────────────────────

    fun toJson(r: PersonaRow): JSONObject {
        val o = JSONObject().put("name", r.name).put("prompt", r.prompt).put("at", r.at)
        if (r.deleted) o.put("deleted", true)
        if (r.renamedTo.isNotBlank()) o.put("renamedTo", r.renamedTo)
        return o
    }

    /** Tolerant read (opt*): a row with no name is dropped; a missing `at` is 0. Null = not a list. */
    fun parseOrNull(text: String): List<PersonaRow>? = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            if (name.isBlank()) null
            else PersonaRow(name, o.optString("prompt"), o.optLong("at"), o.optBoolean("deleted"), o.optString("renamedTo"))
        }
    }.getOrNull()

    fun parse(text: String): List<PersonaRow> = if (text.isBlank()) emptyList() else parseOrNull(text) ?: emptyList()

    fun encode(rows: List<PersonaRow>): String = JSONArray().apply { rows.forEach { put(toJson(it)) } }.toString()

    // ── The rules ───────────────────────────────────────────────────────────────────────────

    /** Does [a] beat [b] for the same name? */
    fun wins(a: PersonaRow, b: PersonaRow): Boolean = when {
        a.at != b.at -> a.at > b.at
        a.deleted != b.deleted -> a.deleted
        a.prompt != b.prompt -> a.prompt > b.prompt
        else -> a.renamedTo >= b.renamedTo
    }

    /** Built-ins first in [BUILT_IN] order, then his own by name, case-insensitive. */
    fun ordered(rows: Collection<PersonaRow>): List<PersonaRow> {
        val builtIns = BUILT_IN.mapNotNull { b -> rows.firstOrNull { it.name == b.name } }
        val own = rows.filter { !isBuiltIn(it.name) }
            .sortedWith(compareBy<PersonaRow>({ it.name.lowercase() }, { it.name }))
        return builtIns + own
    }

    /** Every built-in name with no row at all — live or dead — as an untouched seed. */
    fun ensure(rows: List<PersonaRow>): List<PersonaRow> {
        val missing = BUILT_IN.filter { b -> rows.none { it.name == b.name } }
        return if (missing.isEmpty()) ordered(rows) else ordered(rows + missing.map { PersonaRow(it.name, it.prompt, 0L) })
    }

    /** One row per name, the winner of [wins]; then [ensure]. Commutative. */
    fun merge(a: List<PersonaRow>, b: List<PersonaRow>): List<PersonaRow> {
        val byName = LinkedHashMap<String, PersonaRow>()
        for (r in a + b) {
            val mine = byName[r.name]
            if (mine == null || wins(r, mine)) byName[r.name] = r
        }
        return ensure(byName.values.toList())
    }

    /** The live personas, in list order, each with the prompt it speaks with. */
    fun live(rows: List<PersonaRow>): List<Persona> =
        ordered(rows).filter { !it.deleted }.map { Persona(it.name, promptOf(it)) }

    /** The live name [name] stands for now — itself, or where its renames lead (≤ [MAX_HOPS]);
     *  null when it is gone from this set (deleted, or never here). */
    fun resolve(rows: List<PersonaRow>, name: String): String? {
        var n = name
        for (hop in 0..MAX_HOPS) {
            val r = rows.firstOrNull { it.name == n } ?: return null
            if (!r.deleted) return n
            if (hop == MAX_HOPS || r.renamedTo.isBlank()) return null
            n = r.renamedTo
        }
        return null
    }

    /**
     * A page's voice on THIS device: [wanted] (a page row's persona) → the live voice that answers
     * (renames followed) and, when it is gone from this set, the name that is missing — the
     * Correspondent answers instead, and the header and words say so (ruling 5).
     */
    data class Resolved(val persona: String, val missing: String)

    fun voice(rows: List<PersonaRow>, wanted: String): Resolved {
        if (SynthVoice.isCorrespondent(wanted)) return Resolved(SynthVoice.CORRESPONDENT, "")
        val live = resolve(rows, wanted) ?: return Resolved(SynthVoice.CORRESPONDENT, wanted)
        // A live persona with nothing to say is not a voice (iOS SynthVoice.resolve, review of
        // b29cabbc): answering with the Correspondent's prompt under its name would make the header
        // and the cards lie — so it reads as missing, and the words say so.
        val row = rows.firstOrNull { it.name == live }
        if (row == null || promptOf(row).isBlank()) return Resolved(SynthVoice.CORRESPONDENT, wanted)
        return if (SynthVoice.isCorrespondent(live)) Resolved(SynthVoice.CORRESPONDENT, "") else Resolved(live, "")
    }

    /**
     * The one name a stored voice stands for NOW, for comparing voices (review of b29cabbc): a turn
     * or a page-history entry written as "Coach" is the same voice as "Coach — v2" once Coach was
     * renamed — so the renamed voice owns its own turns and the header counts it once. Renames are
     * followed; a voice gone from this set keeps its own name (its turns still say who wrote them);
     * the Correspondent, in any spelling, is "".
     */
    fun canonical(rows: List<PersonaRow>, name: String): String {
        if (SynthVoice.isCorrespondent(name)) return SynthVoice.CORRESPONDENT
        val live = resolve(rows, name) ?: return name
        return if (SynthVoice.isCorrespondent(live)) SynthVoice.CORRESPONDENT else live
    }

    /**
     * An edit (or a new persona), stamped [now] — never older than the row it replaces.
     *
     * Saving an UNTOUCHED built-in with its own text changes nothing (review of b29cabbc): opening
     * Spiral in Ask and tapping Save must not stamp it, or the seed stops following app updates
     * and the frozen copy syncs to the other device as if he had edited it.
     */
    fun upsert(rows: List<PersonaRow>, persona: Persona, now: Long): List<PersonaRow> {
        val prior = rows.firstOrNull { it.name == persona.name }
        val seed = builtInPrompt(persona.name)
        if (seed != null && seed == persona.prompt && (prior == null || (prior.at == 0L && !prior.deleted))) return ensure(rows)
        val at = maxOf(now, (prior?.at ?: 0L) + 1)
        return ensure(rows.filterNot { it.name == persona.name } + PersonaRow(persona.name, persona.prompt, at))
    }

    /** A deletion — a tombstone, so it travels. [renamedTo] = the name it lives on under. */
    fun delete(rows: List<PersonaRow>, name: String, now: Long, renamedTo: String = ""): List<PersonaRow> {
        val prior = rows.firstOrNull { it.name == name }
        val at = maxOf(now, (prior?.at ?: 0L) + 1)
        return ensure(rows.filterNot { it.name == name } + PersonaRow(name, "", at, deleted = true, renamedTo = renamedTo))
    }

    /**
     * Once per device, from the legacy prefs list (`ledger_personas` `[{name,prompt}]`) and its
     * per-name `seeded_<name>` markers: a row whose prompt IS its built-in's text stays an untouched
     * seed (`at:0`); anything else is his edit (`at:now`). A built-in whose marker is set but whose
     * row is gone was deleted by him — a tombstone at now. The rest are ensured.
     */
    fun migrate(legacy: List<Persona>, seeded: Set<String>, now: Long): List<PersonaRow> {
        val out = LinkedHashMap<String, PersonaRow>()
        for (p in legacy) {
            if (p.name.isBlank() || out.containsKey(p.name)) continue
            val seed = builtInPrompt(p.name)
            out[p.name] = PersonaRow(p.name, p.prompt, if (seed != null && seed == p.prompt) 0L else now)
        }
        for (b in BUILT_IN) {
            if (!out.containsKey(b.name) && b.name in seeded) out[b.name] = PersonaRow(b.name, "", now, deleted = true)
        }
        return ensure(out.values.toList())
    }
}
