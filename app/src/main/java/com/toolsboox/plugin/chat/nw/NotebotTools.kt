package com.toolsboox.plugin.chat.nw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * Notebot's tool layer: ONE registry, two reaches — the Kotlin port of the iOS
 * `AskTools.swift` shape (ledger-ipad-correspondence). Each tool is declared once — name,
 * description, typed params, executor — and that single declaration is emitted into whichever
 * wire schema the active provider speaks (Anthropic `tools`/`input_schema`, OpenAI
 * `functions`/`parameters`).
 *
 * The wire format is PINNED by DESIGN-NOTEBOT-VOICE.md §6, verbatim, so a conversation started
 * on the Boox and a tool schema on the iPhone cannot drift apart: tool schemas are the
 * JSON-schema object `{"type":"object","properties":{...},"required":[...]}` with the v1 names
 * and params exactly as §4 spells them; every mutating tool returns a one-line English sentence
 * starting with a past-tense verb ("Added task: …"); every read tool returns cite-tagged plain
 * text or a plain "nothing found" sentence.
 *
 * The local reach runs in-process against this device's stores; the remote reach is one HTTPS
 * call each to mjh.yoga (see [NotebotRemote]). Nothing in v1 deletes anything — that is the
 * bargain that lets mutating tools execute without confirmation: the mis-fire ceiling is an
 * extra note or task, and every mutation narrates itself.
 */

/** A single parameter, declared once. [type] is a JSON-schema type name ("string", "integer",
 *  "boolean") — the lingua franca both providers accept, which is what makes one declaration
 *  enough. */
data class AskToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
)

/**
 * One tool. [mutates] marks tools that change the Ledger — they execute directly, no
 * confirmation dialog interrupting the conversation, but the bargain is narration: every
 * mutating result says plainly what it made, so the chat reads as a record of what happened.
 * [run] is blocking — the loop calls it from an IO dispatcher.
 */
class AskTool(
    val name: String,
    val description: String,
    val params: List<AskToolParam>,
    val mutates: Boolean = false,
    val run: (JSONObject) -> String,
) {
    /** The shared JSON-schema object both providers wrap differently (§6 pinned shape). */
    fun jsonSchema(): JSONObject {
        val props = JSONObject()
        for (p in params) props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
        val schema = JSONObject().put("type", "object").put("properties", props)
        val required = params.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return schema
    }
}

/** The one registry: holds the tools, emits both provider formats, dispatches execution. */
class ToolRegistry(val tools: List<AskTool>) {

    /** Anthropic Messages API `tools` array: `{name, description, input_schema}`. */
    fun anthropicTools(): JSONArray {
        val arr = JSONArray()
        for (t in tools) arr.put(
            JSONObject().put("name", t.name).put("description", t.description)
                .put("input_schema", t.jsonSchema()))
        return arr
    }

    /** OpenAI chat-completions `tools` array: `{type:"function", function:{name, description, parameters}}`. */
    fun openAITools(): JSONArray {
        val arr = JSONArray()
        for (t in tools) arr.put(
            JSONObject().put("type", "function").put(
                "function", JSONObject().put("name", t.name).put("description", t.description)
                    .put("parameters", t.jsonSchema())))
        return arr
    }

    /** Run a named tool. Unknown names come back as words, not throws — the model can read the
     *  miss and correct itself inside the loop. Executors that blow up become sentences too:
     *  a tool failure is something the transcript SAYS, never something that kills the turn. */
    fun execute(name: String, args: JSONObject): String {
        val tool = tools.firstOrNull { it.name == name }
            ?: return "Unknown tool \"$name\" — nothing was done."
        return runCatching { tool.run(args) }
            .getOrElse { "Tool $name failed: ${it.message ?: it.javaClass.simpleName} — nothing was done." }
    }
}

/**
 * The v1 tool belt for the Android fork — the iOS local seven as they map to this fork's
 * capabilities, plus the eight curated notes-bot remote tools.
 *
 * Deliberately NOT here, per the brief (§4):
 * - `find_contact` — Android has no rolodex parity yet; a tool that always answers "no
 *   contacts here" teaches the model to stop calling it. It lands with the rolodex.
 * - Anything that deletes; the portfolio-ops verbs (mail, refunds, bookings); the async
 *   `notes_repurpose_*`; the overwrite `notes_annotate`; the generic `remote_ability` bridge.
 */
object NotebotRegistry {

    fun build(
        context: Context,
        corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService,
        calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService,
        root: File,
        scope: Set<com.toolsboox.plugin.chat.da.Section>,
    ): ToolRegistry {
        val tools = mutableListOf<AskTool>()
        tools += localTools(context, corpusService, calendarDayService, root, scope)
        // The remote reach only rides when the ingest secret is configured — offering tools that
        // can only ever fail teaches the model to stop calling them (the find_contact lesson).
        if (NotebotRemote.secret(context) != null) tools += remoteTools(context)
        return ToolRegistry(tools)
    }

    // MARK: - Local reach

    private fun localTools(
        context: Context,
        corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService,
        calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService,
        root: File,
        scope: Set<com.toolsboox.plugin.chat.da.Section>,
    ): List<AskTool> = listOf(

        // Retrieval — the same hybrid corpus grounding the old one-shot ask front-loaded, now
        // callable on demand (and repeatedly, with sharper queries).
        AskTool(
            name = "search_ledger",
            description = "Search the reader's own Ledger on this device — book/article highlights, " +
                "feed annotations, planner notes, tasks, events, notes and A/V transcripts. Returns " +
                "cite-tagged excerpts like [2026-07-13 · book · Light on Yoga].",
            params = listOf(AskToolParam("query", "string", "What to look for, in natural language.")),
        ) { args ->
            val q = str(args, "query")
            if (q.isEmpty()) "search_ledger needs a query."
            else {
                val all = corpusService.gather(root, scope)
                val hits = corpusService.retrieveHybrid(all, q)
                if (hits.isEmpty()) "Nothing found in the Ledger for \"$q\"."
                else corpusService.buildContext(hits)
            }
        },

        // Today — tasks off the day JSON (with done state) plus today's events, the same
        // gathering the iOS watch snapshot does.
        AskTool(
            name = "todays_plan",
            description = "Today's plan: the tasks on today's Ledger page (with done state) and " +
                "today's scheduled events.",
            params = emptyList(),
        ) { _ -> todaysPlan(calendarDayService, root) },

        // Site boards — the /due-cards timeline rail, server-bucketed (FluentBoards via the
        // ledgr bridge; reuses the stored Community & Boards credentials).
        AskTool(
            name = "due_cards",
            description = "Due-dated cards across the reader's site kanban boards (FluentBoards " +
                "via the ledgr bridge), bucketed todo/doing/done.",
            params = listOf(AskToolParam("limit", "integer", "Max cards to return (default 20).", required = false)),
        ) { args ->
            val limit = intArg(args, "limit", 20).coerceIn(1, 200)
            val cards = com.toolsboox.plugin.calendar.nw.LedgerBoards.dueCards(context, limit)
            if (cards.isEmpty()) "No due-dated cards on the site boards (or the boards bridge isn't configured)."
            else cards.joinToString("\n") { c ->
                "[${c.bucket}] ${c.title} — ${c.board}" + (c.dueAt?.let { " · due $it" } ?: "")
            }
        },

        // Doing — a task onto the day JSON, the same load-modify-save + CalDAV re-push every
        // other creation path walks, under the day lock so it can't race the open day page.
        AskTool(
            name = "add_task",
            description = "Add a task to the reader's Ledger — lands on the day page, the widgets, " +
                "and re-pushes to Reminders/CalDAV.",
            params = listOf(
                AskToolParam("text", "string", "The task, as the reader would write it."),
                AskToolParam("date", "string",
                    "YYYY-MM-DD; omit for today. Resolve relative dates before calling.", required = false),
                AskToolParam("time", "string", "HH:MM if the reader gave one.", required = false),
            ),
            mutates = true,
        ) { args ->
            val text = str(args, "text")
            if (text.isEmpty()) "add_task needs text — nothing was added."
            else {
                val date = runCatching { LocalDate.parse(str(args, "date")) }.getOrDefault(LocalDate.now())
                val time = str(args, "time").ifBlank { null }
                // Canonical item.date: the due DAY at 12:00 UTC — the convention every other
                // creation path and every reader (itemLocalDate, buildVTodo) uses. The clock time
                // lives only in `time`, which the sync layers apply in local time.
                val item = com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                    id = "ask-${java.util.UUID.randomUUID()}",
                    kind = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
                    text = text,
                    date = java.util.Date(date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()),
                    time = time, source = "ask",
                )
                val saved = runCatching {
                    com.toolsboox.plugin.calendar.ot.DayLocks.withDay(date) {
                        val day = calendarDayService.load(root, date, null, Locale.getDefault())
                        day.ledgerItems.add(item)
                        calendarDayService.save(root, date, day)
                    }
                }.isSuccess
                if (!saved) "Couldn't save the task — nothing was added."
                else {
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(context, item)
                        }
                    }
                    "Added task: \"$text\" on $date" + (time?.let { " @ $it" } ?: "") + "."
                }
            }
        },

        // Doing — a titled note on today (searchable, corpus-visible); when the notes-ingest
        // secret is configured it also posts to mjh.yoga /notes, landing where Notebot's remote
        // half lives — the same double-write the iOS create_note does.
        AskTool(
            name = "create_note",
            description = "Create a titled note on today's Ledger. When the reader's notes-ingest " +
                "secret is configured it also posts to mjh.yoga /notes.",
            params = listOf(
                AskToolParam("title", "string", "A short title for the note."),
                AskToolParam("body", "string", "The note body (markdown welcome)."),
            ),
            mutates = true,
        ) { args ->
            val title = str(args, "title"); val body = str(args, "body")
            if (title.isEmpty() && body.isEmpty()) "create_note needs a title or body."
            else {
                val note = com.toolsboox.plugin.textnotes.TextNotesStore.addNote(
                    context, LocalDate.now(), title, body)
                val posted = NotebotRemote.postNoteIngest(context, note.title, body)
                "Created note \"${note.title}\" on today's Ledger" +
                    (if (posted) " and posted it to mjh.yoga /notes." else ".")
            }
        },

        // Doing — words become a placed object: the quote-card face placed onto the Pickings
        // page with provenance stamped. PLACES, and cannot ask: there is nobody at the keyboard
        // at the moment this runs — it is a tool call inside a model turn, and a destination
        // sheet raised from here would be a dialog with no question behind it. The return
        // sentence names where the gram landed, so the answer you read says where to look.
        AskTool(
            name = "save_gram",
            description = "Render text as a quote-card gram and place it on the reader's making " +
                "surfaces — a movable, connectable object, not just a line of chat.",
            params = listOf(
                AskToolParam("text", "string", "The words the card carries."),
                AskToolParam("title", "string", "Card title / source line (optional).", required = false),
            ),
            mutates = true,
        ) { args ->
            val text = str(args, "text")
            if (text.isEmpty()) "save_gram needs text — nothing was placed."
            else {
                val title = str(args, "title")
                // A tool call inside a model turn cannot raise a chooser, so the shared gram
                // memory routes it — Gram Picks when the remembered place no longer exists today
                // — and the answer sentence names the landing, as it always has.
                val dest = com.toolsboox.plugin.calendar.ot.GramDestinations.inbox(context)
                val placed = runCatching {
                    val face = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                        text.take(600), title.ifBlank { "Ask my Ledger" }, null, 1080, 0)
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, root, face, LocalDate.now(), dest.key,
                        sourceLabel = title.ifBlank { "Ask my Ledger" }, cardText = text,
                        intakeKind = dest.kind)
                }.isSuccess
                if (placed) "Saved gram" + (if (title.isBlank()) "" else " \"$title\"") + " onto ${dest.name}."
                else "Couldn't place the gram — nothing was saved."
            }
        },
    )

    // MARK: - Remote reach: the notes-bot subset, eight tools (brief §4, names pinned)

    /** Each remote tool is one POST to `https://mjh.yoga/wp-json/mjh/v1/notebot/{tool}` with the
     *  `X-MJH-Ingest-Secret` header; the args object IS the JSON body. Timeouts are short,
     *  failures are said out loud in the transcript, and nothing is ever retried silently —
     *  all of that lives in [NotebotRemote.call]. */
    private fun remoteTools(context: Context): List<AskTool> {
        fun remote(name: String, description: String, params: List<AskToolParam>, mutates: Boolean = false) =
            AskTool(name, description, params, mutates) { args -> NotebotRemote.call(context, name, args) }

        val limit = AskToolParam("limit", "integer", "Max results (server default applies).", required = false)
        return listOf(
            remote(
                "notes_search",
                "Keyword search across the reader's mjh.yoga /notes garden — title, content and annotation.",
                listOf(AskToolParam("query", "string", "What to look for."), limit),
            ),
            remote(
                "notes_search_semantic",
                "Semantic (embedding) search across the /notes garden — finds paraphrase and related " +
                    "ideas, not just shared words (0.4+ related, 0.55+ strong).",
                listOf(AskToolParam("query", "string", "The idea to look for."), limit),
            ),
            remote(
                "notes_recent",
                "The most recent notes in the /notes garden.",
                listOf(limit, AskToolParam("days", "integer", "Only notes from the last N days.", required = false)),
            ),
            remote(
                "notes_by_tag",
                "Notes carrying a tag — diary, shala-daily, pickings, seed, essay, techsupport.",
                listOf(AskToolParam("tag", "string", "The tag to list."), limit),
            ),
            remote(
                "notes_get_by_id",
                "One note in full — content plus its annotation.",
                listOf(AskToolParam("id", "integer", "The note's id.")),
            ),
            remote(
                "notes_create",
                "Create a note in the /notes garden — the same create the ingest path uses.",
                listOf(
                    AskToolParam("title", "string", "The note's title."),
                    AskToolParam("content", "string", "The note body (markdown welcome)."),
                    AskToolParam("tag", "string", "Optional tag, e.g. seed or diary.", required = false),
                ),
                mutates = true,
            ),
            remote(
                "notes_annotate_append",
                "Append to a note's annotation — append-only; nothing is overwritten.",
                listOf(
                    AskToolParam("id", "integer", "The note's id."),
                    AskToolParam("text", "string", "What to append."),
                ),
                mutates = true,
            ),
            remote(
                "note_pin",
                "Pin a note (or unpin it) in the /notes garden.",
                listOf(
                    AskToolParam("id", "integer", "The note's id."),
                    AskToolParam("unpin", "boolean", "True to unpin instead.", required = false),
                ),
                mutates = true,
            ),
        )
    }

    // MARK: - Executor helpers

    private fun str(args: JSONObject, key: String): String = args.optString(key, "").trim()

    private fun intArg(args: JSONObject, key: String, def: Int): Int =
        if (args.has(key)) args.optInt(key, def) else def

    /** Today's tasks off the day JSON (done state, time) plus today's events — the same shape
     *  the iOS todays_plan returns, so both forks' answers read alike. */
    private fun todaysPlan(
        calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService,
        root: File,
    ): String {
        val today = LocalDate.now()
        val lines = mutableListOf("TODAY $today")
        val day = runCatching {
            calendarDayService.load(root, today, null, Locale.getDefault())
        }.getOrNull() ?: return "Couldn't read today's Ledger page."
        val tasks = day.ledgerItems.filter {
            it.kind == com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK && it.text.isNotBlank()
        }
        if (tasks.isEmpty()) lines += "No tasks on today's page."
        else {
            lines += "TASKS"
            for (t in tasks) lines += (if (t.done) "☑ " else "☐ ") + t.text.trim() + (t.time?.let { " @ $it" } ?: "")
        }
        val itemEvents = day.ledgerItems.filter {
            it.kind == com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT && it.text.isNotBlank()
        }
        val dayEvents = day.events.filter { it.title.isNotBlank() }
        if (itemEvents.isNotEmpty() || dayEvents.isNotEmpty()) {
            lines += "EVENTS"
            for (e in itemEvents) lines += (e.time?.let { "$it  " } ?: "") + e.text.trim()
            for (e in dayEvents) lines += e.title.trim()
        }
        return lines.joinToString("\n")
    }
}
