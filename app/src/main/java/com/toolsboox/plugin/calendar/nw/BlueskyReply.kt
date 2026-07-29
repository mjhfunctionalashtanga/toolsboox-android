package com.toolsboox.plugin.calendar.nw

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.text.BreakIterator
import java.util.concurrent.TimeUnit

/**
 * The Boox half of "answer a Bluesky reply by hand".
 *
 * There is deliberately NO AT Protocol client here. The Bluesky app password lives on the VPS and
 * stays there — an app binary that could post to the timeline is an app binary that leaks the
 * ability to post to the timeline the first time it is sideloaded onto a device that isn't his.
 * So this talks only to michaeljoelhall.com (mu-plugin `mjh-bsky-reply.php`), which owns the queue:
 * we GET the replies awaiting an answer and POST the answer back, and a ten-minute cron on the VPS
 * resolves the at:// refs, posts to Bluesky, and acks. The site is the source of truth for what
 * has been answered, which is what keeps the iPad and the Boox from both answering the same reply.
 *
 * Auth is the shared POSSE header `X-MF-Secret`, exactly as `syndicate-queue`/`syndicate-ack` use
 * it. It is a SETTING, never a constant in this file: a secret compiled into the APK is a secret
 * published to anyone who unzips the APK, and this one also authorises the syndication queue.
 */
object BlueskyReply {

    /** Bluesky's own post ceiling, in GRAPHEMES — see [graphemes]. The mu-plugin enforces the same
     *  number server-side; if you change one, change both, or a reply that passed the composer's
     *  counter comes back as a 400 after the ink is already gone. */
    const val MAX_GRAPHEMES = 300

    /** Where the queue lives. Overridable in the settings dialog so a staging site can be pointed
     *  at without a rebuild; the trailing `/mjh/v1` is part of it because that is the whole base
     *  the four routes hang off. */
    const val DEFAULT_BASE = "https://michaeljoelhall.com/wp-json/mjh/v1"

    private const val PREFS = "ledger_bsky_prefs"

    @Volatile private var cachedPrefs: android.content.SharedPreferences? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // A handwriting PNG rides on the reply POST, so the write timeout is the generous one.
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Base URL + the shared POSSE secret. [ready] is what every call gates on. */
    data class Config(val base: String, val secret: String) {
        val ready: Boolean get() = base.isNotBlank() && secret.isNotBlank()
    }

    private fun prefs(context: Context): android.content.SharedPreferences = cachedPrefs
        ?: EncryptedSharedPreferences.create(
            context, PREFS,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { cachedPrefs = it }

    fun config(context: Context): Config = try {
        val p = prefs(context)
        Config(
            (p.getString("base", "") ?: "").ifBlank { DEFAULT_BASE },
            (p.getString("secret", "") ?: "").trim()
        )
    } catch (e: Exception) {
        // A device whose keystore entry was lost (a restore, a wipe of the app's keys) must not
        // crash the surface — it reads as "not configured", and the settings dialog rewrites it.
        Timber.w(e, "bluesky config read failed")
        Config(DEFAULT_BASE, "")
    }

    fun saveConfig(context: Context, c: Config) {
        try {
            prefs(context).edit()
                .putString("base", c.base.trim().trimEnd('/').ifBlank { DEFAULT_BASE })
                .putString("secret", c.secret.trim())
                .apply()
        } catch (e: Exception) {
            Timber.w(e, "bluesky config save failed")
        }
    }

    /**
     * One Bluesky reply waiting for an answer, as the inbox route returns it.
     *
     * [commentId] is the on-site comment id of THEIR reply and the only handle the POST needs —
     * the at:// URI it is threaded under is resolved server-side, so the device never has to know
     * anything about AT Protocol addressing.
     */
    data class InboxItem(
        val commentId: Long,
        val author: String,
        val handle: String,
        val profileUrl: String,
        val text: String,
        val bskyUri: String,
        val createdAt: String,
        val postId: Long,
        val postTitle: String,
        val canonicalUrl: String,
        val postBskyUrl: String,
    )

    /**
     * What the site did with an answer. [duplicate] is not a failure: the route is idempotent on
     * the parent comment, so a second Send (a double tap, a retry after a flaky cellular POST)
     * returns the FIRST reply rather than posting a second one to the thread — and the composer
     * treats that as success, because from Michael's side the answer is indeed sent.
     */
    data class ReplyResult(val ok: Boolean, val duplicate: Boolean, val message: String)

    /**
     * Count [text] the way Bluesky counts it: in grapheme clusters, not chars and not bytes. A
     * flag emoji is one grapheme and two-to-four Java chars; "é" written as e + combining acute is
     * one grapheme and two chars. Counting `String.length` would let the composer say 280 while
     * the PDS rejected the record at 300+, which is the failure mode this whole ceiling exists to
     * avoid. `BreakIterator.getCharacterInstance()` is ICU-backed on Android, so this agrees with
     * Swift's `String.count` on the iPad and with `grapheme_strlen()` in the mu-plugin.
     */
    fun graphemes(text: String): Int {
        if (text.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        var n = 0
        while (it.next() != BreakIterator.DONE) n++
        return n
    }

    /** The replies still awaiting an answer, newest first. Blocking — call from Dispatchers.IO. */
    fun inbox(context: Context, limit: Int = 25): List<InboxItem> {
        val c = config(context)
        if (!c.ready) return emptyList()
        return try {
            val req = Request.Builder()
                .url("${c.base}/bsky-inbox?limit=${limit.coerceIn(1, 50)}")
                .header("X-MF-Secret", c.secret)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("bsky inbox %d: %s", resp.code, resp.body?.string()?.take(200).orEmpty())
                    return emptyList()
                }
                val arr = JSONObject(resp.body?.string() ?: return emptyList())
                    .optJSONArray("items") ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
                    val id = o.optLong("comment_id", 0)
                    if (id <= 0) return@mapNotNull null
                    InboxItem(
                        id,
                        o.optString("author", "?"),
                        o.optString("handle", ""),
                        o.optString("profile_url", ""),
                        o.optString("text", ""),
                        o.optString("bsky_uri", ""),
                        o.optString("created_at", ""),
                        o.optLong("post_id", 0),
                        o.optString("post_title", ""),
                        o.optString("canonical_url", ""),
                        o.optString("post_bsky_url", ""),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "bsky inbox fetch failed")
            emptyList()
        }
    }

    /**
     * Send the answer. [text] is the REVIEWED text — whatever is in the composer's editable field
     * at the moment Send is tapped, never raw OCR (see BlueskyFragment for why that rule is not
     * negotiable). [png] optionally carries the handwriting itself, which the site stores as an
     * uploads URL and the VPS attaches as the reply's image embed.
     *
     * Multipart rather than JSON whenever there is a PNG, matching how ledgr/v1 takes ink replies;
     * the route accepts either. Blocking — call from Dispatchers.IO.
     */
    fun reply(context: Context, commentId: Long, text: String, png: ByteArray? = null): ReplyResult {
        val c = config(context)
        if (!c.ready) return ReplyResult(false, false, "Bluesky bridge not configured")
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ReplyResult(false, false, "Nothing to send")
        // The client-side ceiling is checked here as well as in the composer, because this object
        // is the last thing between the ink and a 400 that arrives after the composer has closed.
        val n = graphemes(trimmed)
        if (n > MAX_GRAPHEMES) return ReplyResult(false, false, "Too long — $n of $MAX_GRAPHEMES")

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("comment_id", commentId.toString())
            .addFormDataPart("text", trimmed)
            .apply {
                png?.let { addFormDataPart("png", "bsky-reply.png", it.toRequestBody("image/png".toMediaType())) }
            }
            .build()

        return try {
            val req = Request.Builder()
                .url("${c.base}/bsky-reply")
                .post(body)
                .header("X-MF-Secret", c.secret)
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val o = runCatching { JSONObject(raw) }.getOrNull()
                if (resp.isSuccessful && o?.optBoolean("ok", false) == true) {
                    val dup = o.optBoolean("duplicate", false)
                    ReplyResult(true, dup, if (dup) "Already answered" else "Reply queued for Bluesky")
                } else {
                    // WP_Error bodies say exactly what went wrong ("reply is 312 graphemes; …"),
                    // and swallowing that made every refusal look like a bad secret.
                    val msg = o?.optString("message").orEmpty()
                    ReplyResult(false, false, msg.ifBlank { "Reply failed (${resp.code})" })
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "bsky reply failed")
            ReplyResult(false, false, "Network error")
        }
    }
}
