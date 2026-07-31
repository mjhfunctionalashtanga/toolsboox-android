package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.chat.nw.LedgerChatService
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ✧ The one notion of a **path to victory** for a SINGLE quick win.
 *
 * Quick Wins reads the graph to surface the highest-leverage undone tasks (see [QuickWinsEngine]).
 * This is the generative other half: pick ONE win and the Ledger pre-writes the concrete pieces
 * needed to see it through — the email it would send (subject + body, ready to open in Compose), or
 * the small task list it would create (tasks + subtasks, with suggested days and assignees). Nothing
 * is written until the reader confirms; [ask] only drafts, [executeTasks] is the deliberate act.
 *
 * The shape is decided by the model and returned as strict JSON so the surface can preview it exactly
 * — an email card, a task list, or a few plain steps — rather than parse loose prose. The task-write
 * path is the same one every task surface uses ([CalendarDayService.load]/`ledgerItems.add`/`save`),
 * so a created path shows up in Kanban, the day page and the next Quick Wins walk with no new store.
 */
object PathToVictoryEngine {

    /** The three shapes a win's path can take. Anything unrecognised degrades to [STEPS]. */
    enum class Shape { EMAIL, TASKS, STEPS }

    /** An email ready to open in Compose — recipient (best-effort), subject and body. */
    data class EmailDraft(val to: String, val toName: String, val subject: String, val body: String)

    /** One task the path would create, with the day it belongs on, an optional assignee, and its
     *  subtasks (each created as its own indented task so the same task surfaces show them). */
    data class TaskDraft(
        val text: String, val date: LocalDate?, val assignee: String?, val subtasks: List<String>
    )

    /** The whole drafted path: its shape, a one-line "what it will do", and the artifacts. */
    data class Plan(
        val shape: Shape,
        val summary: String,
        val email: EmailDraft?,
        val tasks: List<TaskDraft>,
        val steps: List<String>
    )

    sealed class Result {
        data class Ok(val plan: Plan) : Result()
        data class Err(val message: String) : Result()
    }

    /**
     * Draft the path for one win. Blocking (LLM call) — invoke off the main thread. [winText] is the
     * task line; [reasons]/[companions] are the win's leverage notes (why it matters, what it touches);
     * [assigneeName]/[assigneeEmail] are the resolved rolodex contact when the win is assigned, so an
     * email path arrives already addressed. Returns a parsed [Plan] or a plain error.
     */
    fun ask(
        service: LedgerChatService, provider: String, apiKey: String, model: String,
        winText: String, reasons: List<String>, companions: List<String>,
        assigneeName: String?, assigneeEmail: String?
    ): Result {
        val today = LocalDate.now()
        val system = """
            You are the reader's own Ledger. They have ONE task they want to finish — below. Pre-write
            its "path to victory": the concrete pieces, ready to go, so they only confirm to make it
            happen. First classify the task into ONE shape:
              - "email": the task is to write/send a message → draft the actual email.
              - "tasks": the task is a small project → break it into a few tasks, each with optional
                 subtasks, a suggested day, and (if a person is implied) an assignee.
              - "steps": a short sequence of plain actions, when it's neither of the above.
            Today is $today. Resolve suggested days as absolute YYYY-MM-DD (default to today). Keep the
            email in the reader's warm, plain, concise voice. Keep task/step lists SHORT (2-6 items).
            Return ONLY a single JSON object, no markdown fence, no prose before or after, in exactly
            this shape (omit the fields that don't apply):
            {
              "shape": "email" | "tasks" | "steps",
              "summary": "one short sentence naming what this will do",
              "email": { "to": "address or empty", "toName": "name or empty",
                         "subject": "…", "body": "the full email text" },
              "tasks": [ { "text": "…", "date": "YYYY-MM-DD", "assignee": "name or empty",
                           "subtasks": ["…", "…"] } ],
              "steps": ["…", "…"]
            }
        """.trimIndent()

        val ctx = buildString {
            append("Task to finish: ").append(winText).append('\n')
            if (reasons.isNotEmpty()) append("Why it matters: ").append(reasons.joinToString(", ")).append('\n')
            if (companions.isNotEmpty()) append("Connected to: ").append(companions.joinToString(", ")).append('\n')
            if (!assigneeName.isNullOrBlank()) {
                append("Assigned to: ").append(assigneeName)
                if (!assigneeEmail.isNullOrBlank()) append(" <").append(assigneeEmail).append('>')
                append('\n')
            }
        }

        return when (val r = service.run(provider, apiKey, model, system, ctx)) {
            is LedgerChatService.Result.Ok -> {
                val plan = parse(r.answer, assigneeName, assigneeEmail)
                if (plan == null) Result.Err("The Ledger didn't return a usable path") else Result.Ok(plan)
            }
            is LedgerChatService.Result.Err -> Result.Err(r.message)
        }
    }

    /** Pull the JSON object out of a reply that may carry a fence or stray prose, and shape it. */
    private fun parse(answer: String, assigneeName: String?, assigneeEmail: String?): Plan? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { JSONObject(answer.substring(start, end + 1)) }.getOrNull() ?: return null

        val shape = when (obj.optString("shape").trim().lowercase()) {
            "email" -> Shape.EMAIL
            "tasks" -> Shape.TASKS
            else -> Shape.STEPS
        }
        val summary = obj.optString("summary").trim()

        val email = obj.optJSONObject("email")?.let { e ->
            val body = e.optString("body").trim()
            val subject = e.optString("subject").trim()
            if (body.isBlank() && subject.isBlank()) null
            else EmailDraft(
                to = e.optString("to").trim().ifBlank { assigneeEmail.orEmpty() },
                toName = e.optString("toName").trim().ifBlank { assigneeName.orEmpty() },
                subject = subject, body = body
            )
        }

        val tasks = ArrayList<TaskDraft>()
        obj.optJSONArray("tasks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val text = t.optString("text").trim()
                if (text.isEmpty()) continue
                val date = runCatching { LocalDate.parse(t.optString("date").trim()) }.getOrNull()
                val assignee = t.optString("assignee").trim().ifBlank { null }
                val subs = ArrayList<String>()
                t.optJSONArray("subtasks")?.let { sa ->
                    for (j in 0 until sa.length()) sa.optString(j).trim().takeIf { it.isNotEmpty() }?.let(subs::add)
                }
                tasks.add(TaskDraft(text, date, assignee, subs))
            }
        }

        val steps = ArrayList<String>()
        obj.optJSONArray("steps")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(steps::add)
        }

        // A drafted plan has to carry something to confirm; an empty shell is an error, not a path.
        val hasBody = when (shape) {
            Shape.EMAIL -> email != null
            Shape.TASKS -> tasks.isNotEmpty()
            Shape.STEPS -> steps.isNotEmpty() || tasks.isNotEmpty()
        }
        if (!hasBody && email == null && tasks.isEmpty() && steps.isEmpty()) return null
        return Plan(shape, summary, email, tasks, steps)
    }

    /**
     * Write a task-shaped (or step-shaped) plan into the ledger — the deliberate act after preview.
     * Each [TaskDraft] becomes a TASK on its suggested day (default today), its subtasks become their
     * own indented TASK items on the same day, and a resolved assignee is carried as the task's
     * [LedgerItem.contactId]. Plain steps are created as single-line tasks for today. Blocking (IO) —
     * call off the main thread. Returns how many task items were written.
     */
    fun executeTasks(context: Context, calendarDayService: CalendarDayService, root: File, plan: Plan): Int {
        val locale = Locale.getDefault()
        val today = LocalDate.now()
        val contacts = runCatching { ContactStore.list(context) }.getOrNull().orEmpty()

        fun resolveAssignee(name: String?): String? {
            val n = name?.trim().orEmpty()
            if (n.isEmpty()) return null
            return contacts.firstOrNull { it.name.equals(n, ignoreCase = true) }?.id
                ?: contacts.firstOrNull { it.name.isNotBlank() && it.name.contains(n, ignoreCase = true) }?.id
        }

        fun newTask(text: String, day: LocalDate, contactId: String?) = LedgerItem(
            id = "pv-${UUID.randomUUID()}",
            kind = LedgerItem.Kind.TASK,
            text = text,
            // Canonical item.date: the due DAY at 12:00 UTC — the convention every creation path and
            // reader uses (see NotebotRegistry's add_task tool); device-local clock time drifted items.
            date = Date(day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()),
            stage = "todo", source = "path", contactId = contactId
        )

        // Group everything by target day so each day file is loaded/saved once.
        data class Pending(val text: String, val contactId: String?)
        val byDay = HashMap<LocalDate, MutableList<Pending>>()
        fun add(day: LocalDate, text: String, contactId: String?) =
            byDay.getOrPut(day) { ArrayList() }.add(Pending(text, contactId))

        if (plan.tasks.isNotEmpty()) {
            for (t in plan.tasks) {
                val day = t.date ?: today
                val contactId = resolveAssignee(t.assignee)
                add(day, t.text, contactId)
                for (s in t.subtasks) add(day, "↳ $s", contactId)
            }
        } else {
            for (s in plan.steps) add(today, s, null)
        }

        var count = 0
        val created = ArrayList<LedgerItem>()
        for ((day, pendings) in byDay) {
            runCatching {
                val cd = calendarDayService.load(root, day, null, locale)
                for (p in pendings) {
                    val item = newTask(p.text, day, p.contactId)
                    cd.ledgerItems.add(item)
                    created.add(item)
                    count++
                }
                calendarDayService.save(root, day, cd)
            }
        }
        // Best-effort push, the same follow-through the Kanban/Ask creation paths make.
        for (item in created) runCatching {
            kotlinx.coroutines.runBlocking {
                com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(context, item)
            }
        }
        return count
    }
}
