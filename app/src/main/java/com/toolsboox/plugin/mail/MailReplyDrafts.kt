package com.toolsboox.plugin.mail

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * The reply INTENTS — which letters have a reply being drafted, and where the drafting lives.
 *
 * "Draft the reply" mints the letter's card onto a board so the answer can be gathered before it is
 * written (Pickings), sketched (Synthesize), then written (Write). The card itself stays an ordinary
 * link-face gram — `sourceLink = mail://<id>`, the address every mail gram already carries — because
 * the day JSON is a wire format two forks read, and NOTHING here adds a field to it: a reply gram on
 * the iPad decodes as a mail gram, opens the letter, loses nothing. What the day file can't say is
 * the INTENT ("this card is an answer being composed, and this Write piece is where it's being
 * written"), so that rides here, in a sidecar of its own, keyed by the same `mail://` address the
 * gram already wears. Deleting the gram costs nothing but a menu entry; deleting this file turns
 * reply grams back into plain mail grams, which is the graceful way for a sidecar to die.
 *
 * Synced through the sidecar round-trip like every other small registry (the WritePageStore idiom),
 * so a reply drafted on the Palma offers its "Write the reply" door on the Note Air too. The merge
 * is by mail id, newest [Intent.updatedAt] wins — the same newer-side-wins rule the calendar merge
 * already follows. Bounded at [CAP] entries, oldest dropped: an intent is scaffolding for a letter
 * being answered this season, not an archive.
 */
object MailReplyDrafts {

    /**
     * One letter being answered. [mailId] is the full inbox id (`acct:<accountId>:uid:<uid>`) — the
     * id [MailSync.sendReply] parses the outgoing account from, so holding it whole is what lets the
     * send door work even after the inbox window has scrolled past the letter. [subject],
     * [senderName] and [senderEmail] are kept for the same reason: enough to address and thread the
     * reply (`Re: <subject>`, same door it came in) without the letter in hand.
     *
     * [writeKey]/[writeDate] arrive later, when "Write the reply" mints the piece — blank until
     * then. The date rides along because [WritePageStore] scopes entries by key AND date.
     */
    data class Intent(
        val mailId: String,
        val subject: String,
        val senderName: String,
        val senderEmail: String,
        val writeKey: String = "",
        val writeDate: String = "",
        val updatedAt: Long = 0L
    ) {
        /** Who the reply goes to, named the way the letter list names them. */
        val sender: String get() = senderName.ifBlank { senderEmail }.ifBlank { "the sender" }

        /** The threaded subject — the same rule [MailSync.sendReply] applies on the way out. */
        val reSubject: String
            get() {
                val s = subject.trim().ifBlank { "(no subject)" }
                return if (s.lowercase().startsWith("re:")) s else "Re: $s"
            }

        fun toJson(): JSONObject = JSONObject()
            .put("mailId", mailId).put("subject", subject)
            .put("senderName", senderName).put("senderEmail", senderEmail)
            .put("writeKey", writeKey).put("writeDate", writeDate)
            .put("updatedAt", updatedAt)

        companion object {
            fun fromJson(o: JSONObject) = Intent(
                o.optString("mailId"), o.optString("subject"),
                o.optString("senderName"), o.optString("senderEmail"),
                o.optString("writeKey"), o.optString("writeDate"),
                o.optLong("updatedAt")
            )
        }
    }

    private const val DIR = "mail-reply-intents"
    private const val FILE = "intents.json"
    private const val CAP = 200

    /** The gram's address for a letter — the exact string its [ImageElement.sourceLink] carries. */
    fun uriOf(mailId: String) = "mail://$mailId"

    // The whole file is a couple of hundred small rows at most, so it is read once and kept —
    // the hold menu asks "is this a reply gram" on every long-press over a mail card, and a menu
    // must not grow a disk read per open. Writes go through [save], which refreshes the cache.
    @Volatile private var cache: List<Intent>? = null

    private fun file(context: Context) =
        File(context.filesDir, DIR).apply { mkdirs() }.let { File(it, FILE) }

    fun list(context: Context): List<Intent> {
        cache?.let { return it }
        val f = file(context)
        val loaded = if (!f.exists()) emptyList() else runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Intent.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        cache = loaded
        return loaded
    }

    private fun save(context: Context, intents: List<Intent>) {
        // Newest first, capped — see the class note. The cache updates BEFORE the disk write so a
        // menu opened mid-save already sees the new intent.
        val kept = intents.sortedByDescending { it.updatedAt }.take(CAP)
        cache = kept
        runCatching {
            val arr = JSONArray(); kept.forEach { arr.put(it.toJson()) }
            file(context).writeText(arr.toString())
        }.onFailure { Timber.w(it, "reply intents save failed") }
    }

    /**
     * Record that [m] is being answered — called when the reply gram is minted. A re-draft of the
     * same letter refreshes the entry but keeps any Write piece already linked to it: the second
     * gram is another handle on the same answer, not a second answer.
     */
    fun note(context: Context, m: InboxMessage) {
        val existing = list(context).firstOrNull { it.mailId == m.id }
        val next = Intent(
            mailId = m.id, subject = m.subject,
            senderName = m.fromName, senderEmail = m.fromEmail,
            writeKey = existing?.writeKey.orEmpty(), writeDate = existing?.writeDate.orEmpty(),
            updatedAt = System.currentTimeMillis()
        )
        save(context, list(context).filterNot { it.mailId == m.id } + next)
        sync(context)
    }

    /** The intent behind a gram, by the `mail://` address its sourceLink carries. Null = an
     *  ordinary mail gram (a star, a filed letter) that nobody is drafting an answer to. */
    fun forMail(context: Context, sourceLink: String): Intent? {
        if (!sourceLink.startsWith("mail://")) return null
        val id = sourceLink.removePrefix("mail://")
        return list(context).firstOrNull { it.mailId == id }
    }

    /**
     * The intent a Write piece is answering, or null when the piece is ordinary writing. [key] may
     * arrive with a sub-page tail ("write-123#2") — the piece is the whole document, so the base
     * key is what's matched. The daily "write" key is date-scoped in its store, so it is matched
     * with its date; named documents are unique by key alone, as elsewhere.
     */
    fun forWrite(context: Context, key: String?, date: LocalDate): Intent? {
        val base = key?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: return null
        return list(context).firstOrNull {
            it.writeKey == base && (base != "write" || it.writeDate == date.toString())
        }
    }

    /** Marry the intent to the Write piece minted for it — from then on the piece's Send / Export
     *  leads with the reply door, and "Write the reply" reopens this piece instead of minting twins. */
    fun linkWrite(context: Context, mailId: String, key: String, date: LocalDate) {
        val intents = list(context).map {
            if (it.mailId == mailId)
                it.copy(writeKey = key, writeDate = date.toString(), updatedAt = System.currentTimeMillis())
            else it
        }
        save(context, intents)
        sync(context)
    }

    /**
     * Round-trip the registry so a reply drafted on one device offers its doors on the others.
     * Merge by mail id, newest updatedAt wins — both sides' entries survive, and the side that
     * linked a Write piece beats the side that only minted the gram.
     */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            val local = list(appContext)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(appContext, "$DIR/$FILE")
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byId = LinkedHashMap<String, Intent>()
                for (i in local) byId[i.mailId] = i
                runCatching {
                    val arr = JSONArray(remoteText)
                    for (j in 0 until arr.length()) {
                        val r = Intent.fromJson(arr.getJSONObject(j))
                        if (r.mailId.isBlank()) continue
                        val mine = byId[r.mailId]
                        if (mine == null || r.updatedAt > mine.updatedAt) byId[r.mailId] = r
                    }
                }
                byId.values.toList()
            }
            save(appContext, merged)
            val arr = JSONArray(); list(appContext).forEach { arr.put(it.toJson()) }
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(appContext, "$DIR/$FILE", arr.toString())
        }
    }
}
