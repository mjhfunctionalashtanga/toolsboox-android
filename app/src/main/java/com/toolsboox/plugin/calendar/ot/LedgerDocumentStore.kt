package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * One named document on a ruled surface — its note-page [key], display [name], and the [date] it was
 * started on. The same three fields, spelled the same way, that [WritePage] and [SynthPage] carry
 * and that `pages.json` is on the wire.
 */
data class LedgerNamedPage(val key: String, var name: String, val date: LocalDate)

/**
 * THE NAME INDEX, PARAMETERISED — the store Grid and Jot never had.
 *
 * Michael: "I think grid and jot should be savable." Until now the app had three classes of note
 * surface and Grid and Jot were stuck in the middle one: sub-pageable like a document ("grid",
 * "grid#1", "sketch#2"), but with no store, no name, no directory presence and nothing enumerating
 * their pages — so a grid you filled with something worth keeping was findable only by remembering
 * which day you drew it on. This promotes them into the first class: nameable documents you can find
 * again.
 *
 * ── WHY THIS IS ONE CLASS AND NOT TWO MORE COPIES ─────────────────────────────────────────────
 *
 * [WritePageStore] and [SynthPageStore] are hand-written twins, deliberately "modelled line for line
 * … because two stores that agree on their idiom can't drift". That argument holds for two. At four
 * it inverts: four copies of the same headstone round-trip and the same union merge are four places
 * a fix has to land, and the copy nobody remembered is the one that quietly stops deleting. iOS
 * reached the same conclusion first and shipped it — `LedgerDocumentStore` over there is ONE struct
 * parameterised by `dir` / `defaultKey` / `fallbackName`, with `.write` and `.synthesize` as
 * instances — so a parameterised store here is also the shape that lands 1:1 with the twin rather
 * than a local invention.
 *
 * The BODY is [WritePageStore] line for line all the same: same file, same three fields, same
 * pull-merge-push, same tombstones, and — the rule that matters most here — the same
 * [defaultKey]-is-scoped-by-DATE identity. Grid and Jot have exactly the split Write has: "today's
 * grid page", which every day shares the key "grid" for (that is what keeps the pages already on
 * disk reachable), versus "a named grid document", which is unique by its minted key. A global index
 * keyed on key alone could hold a name for exactly one day in all of history.
 *
 * WHAT WAS REJECTED: refactoring [WritePageStore] and [SynthPageStore] onto this class as well. It
 * is the tidier end state and it is not this change — those two carry Michael's live data through a
 * merge whose asymmetries (Write scopes the daily key by date, Synthesize cannot without a
 * migration) are written down in their own files, and rewriting them to gain nothing but symmetry
 * would put his existing titles on the line for a refactor nobody asked for.
 *
 * ── THE DIRECTORY AND KEY NAMES ARE THE WIRE ──────────────────────────────────────────────────
 *
 * `<dir>/pages.json` and the minted key scheme are the contract with iOS, which reads the same paths
 * off the same WebDAV account. Both are DERIVED FROM THE PAGE KEY FAMILY rather than from what the
 * surface is called in the UI, exactly as Write's are:
 *
 *  • Grid  → `grid-index/pages.json`,   daily key "grid",   minted "grid-<millis>";
 *  • Jot   → `sketch-index/pages.json`, daily key "sketch", minted "sketch-<millis>".
 *
 * Jot is the one worth saying out loud: Michael calls the surface Jot and the hub row says "Jot
 * Notes", but its page keys have been "sketch" / "sketch#n" since long before it had a name, and
 * NOTHING here renames them. A store called Jot whose keys say sketch is a small oddity; rewriting
 * every stored key to match a label would be a migration of his ink, which is not a small oddity.
 *
 * NOTHING here touches page content. A document's strokes/images/text ride the day JSON under its
 * key exactly as before, so every grid and jot page already written under the plain keys keeps its
 * content and simply gains the ability to have a title. That is also why the daily document is NOT
 * recorded on creation: it exists by virtue of the key, and the index only ever holds the exceptions
 * — the days you titled, and the standalone named documents.
 */
open class LedgerDocumentStore(
    /** The sidecar directory, local and remote — [WritePageStore.DIR]'s counterpart. */
    val dir: String,
    /** The implicit daily document's key, and the prefix every named one is minted under. */
    val defaultKey: String,
    /** What an unnamed new document falls back to ("Grid 3"). */
    private val fallbackName: String,
) {
    private companion object {
        const val FILE = "pages.json"
    }

    /** The identity two devices merge an entry on — key AND date, because every day shares
     *  [defaultKey]. [LedgerDocumentTombstones] is handed this same function so a headstone can
     *  never address something different from what the merge addresses. */
    fun idOf(key: String, date: LocalDate) = "$key|$date"

    private fun idOf(p: LedgerNamedPage) = idOf(p.key, p.date)

    /** True for this surface's daily page or any of its named documents (sub-page tails included). */
    fun isMine(key: String?): Boolean {
        val base = key?.substringBefore('#') ?: return false
        return base == defaultKey || base.startsWith("$defaultKey-")
    }

    private fun file(context: Context) =
        File(context.filesDir, dir).apply { mkdirs() }.let { File(it, FILE) }

    /**
     * Every recorded document: the standalone named ones AND the titled daily pages. Newest first.
     * A day whose page was never titled appears nowhere in here — [LedgerDocuments] supplies the
     * implicit daily document for the date being viewed, so an untitled day is a titled document
     * regardless (titled by its date), not an absence.
     */
    fun list(context: Context): MutableList<LedgerNamedPage> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it)
                val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return@mapNotNull null
                LedgerNamedPage(o.optString("key"), o.optString("name"), d)
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    /** Only the standalone named documents — the daily-page titles filtered out. */
    fun documents(context: Context): List<LedgerNamedPage> = list(context).filter { it.key != defaultKey }

    fun save(context: Context, pages: List<LedgerNamedPage>) {
        runCatching {
            val arr = JSONArray()
            for (p in pages) arr.put(JSONObject()
                .put("key", p.key).put("name", p.name).put("date", p.date.toString()))
            file(context).writeText(arr.toString())
        }.onFailure { Timber.w(it, "$dir index save failed") }
    }

    /** Start a new named document, homed on [date] (today by default). */
    fun add(context: Context, name: String, date: LocalDate = LocalDate.now()): LedgerNamedPage {
        val pages = list(context)
        val page = LedgerNamedPage("$defaultKey-${System.currentTimeMillis()}",
            name.ifBlank { "$fallbackName ${pages.count { it.key != defaultKey } + 1}" }, date)
        pages.add(0, page); save(context, pages)
        sync(context)
        return page
    }

    /**
     * Name a document. [date] only matters for the daily page, which is date-scoped (see the class
     * note): titling the 21st's grid page must not title the 22nd's. Naming a daily page for the
     * first time INSERTS its entry — the index holds only the days you bothered to title.
     */
    fun rename(context: Context, key: String, name: String, date: LocalDate) {
        val pages = list(context)
        val existing = pages.firstOrNull { it.key == key && (key != defaultKey || it.date == date) }
        if (existing != null) existing.name = name
        else pages.add(0, LedgerNamedPage(key, name, date))
        save(context, pages)
        // Writing under an id that was deleted un-deletes it — see [WritePageStore.rename], which
        // this reproduces for the same reason: the daily page is the case that matters, and without
        // the headstone being lifted the merge would keep subtracting the entry just made.
        LedgerDocumentTombstones.forget(context, dir, idOf(key, date))
        sync(context)
    }

    /**
     * Drop the entry AND record that it is gone.
     *
     * The record is the whole point — see [LedgerDocumentTombstones]. Removing the entry alone does
     * not work: [sync] folds the server's copy back in whenever the local side lacks it, so the
     * deletion is undone within seconds by a background thread and nothing says so.
     */
    fun delete(context: Context, key: String, date: LocalDate) {
        save(context, list(context).filterNot { it.key == key && (key != defaultKey || it.date == date) })
        LedgerDocumentTombstones.add(context, dir, idOf(key, date))
        sync(context)
    }

    /**
     * The explicit name of a document, or null when it has never been named — null is the signal
     * [LedgerDocuments] and the page header need to fall back to the date, so this must NOT invent
     * a placeholder.
     */
    fun nameOf(context: Context, key: String?, date: LocalDate): String? {
        val base = key?.substringBefore('#') ?: return null
        if (!isMine(base)) return null
        return list(context)
            .firstOrNull { it.key == base && (base != defaultKey || it.date == date) }
            ?.name?.takeIf { it.isNotBlank() }
    }

    private fun remotePath() = "$dir/$FILE"

    /**
     * Round-trip the registry so a document named on one device appears on the others. Merges on
     * key AND date rather than key alone: the daily pages all share [defaultKey], so a key-only
     * merge would let one day's title swallow every other day's.
     */
    fun sync(context: Context) {
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            // Headstones first: what every device knows to be deleted — less what any device has
            // since REVIVED (the epochs round trip; see LedgerDocumentTombstones) — is what the
            // entry merge below is allowed to keep. On its own paths, so an iOS reader of
            // pages.json never sees it.
            val dead = LedgerDocumentTombstones.roundTrip(context, dir)

            val local = list(context)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(context, remotePath())
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byId = LinkedHashMap<String, LedgerNamedPage>()
                for (p in local) byId[idOf(p)] = p
                runCatching {
                    val arr = JSONArray(remoteText)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i); val k = o.optString("key")
                        val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull()
                        if (k.isNotBlank() && d != null && !byId.containsKey("$k|$d"))
                            byId["$k|$d"] = LedgerNamedPage(k, o.optString("name"), d)
                    }
                }
                byId.values.toMutableList()
            }
            // Subtract the headstones from BOTH sides. The remote side is the resurrection this
            // exists to stop; the local side is belt and braces for a device that deleted while
            // offline and whose own file was rewritten by an older build in the meantime.
            merged.removeAll { idOf(it) in dead }
            if (merged.map { idOf(it) } != local.map { idOf(it) }) save(context, merged)
            val arr = JSONArray()
            for (p in merged) arr.put(JSONObject()
                .put("key", p.key).put("name", p.name).put("date", p.date.toString()))
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, remotePath(), arr.toString())
        }
    }
}

/**
 * Grid's name index. Daily key "grid", named documents "grid-<millis>", index at
 * `grid-index/pages.json` — every one of them derived from the page key the surface has always used,
 * so nothing already on disk moves.
 */
object GridPageStore : LedgerDocumentStore("grid-index", "grid", "Grid")

/**
 * Jot's name index.
 *
 * The store is named for the surface Michael names ("Jot Notes" in the hub, ⌱ on the pill); its keys
 * and its directory are named for the page key that surface has carried since before it was called
 * Jot — "sketch". See the class note: the two disagree on purpose, because agreeing would mean
 * rewriting stored page keys, and the day files are read by iOS.
 */
object JotPageStore : LedgerDocumentStore("sketch-index", "sketch", "Jot")
