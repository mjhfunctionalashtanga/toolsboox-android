package com.toolsboox.plugin.chat.nw

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Semantic embeddings for hybrid corpus retrieval. Uses OpenAI text-embedding-3-small (the only
 * embeddings endpoint we hold a key for — Anthropic has none), batched, with a persistent vector
 * cache keyed by a hash of the text so unchanged snippets are never re-embedded. Absent an OpenAI
 * key, callers fall back to keyword + recency retrieval.
 */
object EmbeddingIndex {
    private const val MODEL = "text-embedding-3-small"
    private const val URL = "https://api.openai.com/v1/embeddings"
    private const val BATCH = 96
    private const val MAX_CHARS = 8000

    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    /** OpenAI key from the shared chat prefs (used for embeddings regardless of the chat provider). */
    fun apiKey(context: Context): String? = runCatching {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            context, "ledger_chat_encrypted_prefs",
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.getString("ledger_chat_api_key_openai", "")?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun hash(text: String): String = text.hashCode().toUInt().toString(16)

    private fun cacheFile(context: Context) =
        File(context.filesDir, "ledger-embeddings").apply { mkdirs() }.let { File(it, "vectors.json") }

    fun loadCache(context: Context): MutableMap<String, FloatArray> {
        val f = cacheFile(context)
        if (!f.exists()) return HashMap()
        return runCatching {
            val obj = JSONObject(f.readText())
            val map = HashMap<String, FloatArray>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next(); val arr = obj.getJSONArray(k)
                map[k] = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
            map
        }.getOrDefault(HashMap())
    }

    fun saveCache(context: Context, map: Map<String, FloatArray>) {
        runCatching {
            val obj = JSONObject()
            for ((k, v) in map) { val a = JSONArray(); for (x in v) a.put(x.toDouble()); obj.put(k, a) }
            cacheFile(context).writeText(obj.toString())
        }.onFailure { Timber.w(it, "embedding cache save failed") }
    }

    /** Embed [texts] via the API; returns vectors aligned to the input, or null on any failure. */
    fun embed(apiKey: String, texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(texts.size)
        for (chunk in texts.chunked(BATCH)) {
            val input = JSONArray()
            for (t in chunk) input.put(t.take(MAX_CHARS).ifBlank { " " })
            val payload = JSONObject().put("model", MODEL).put("input", input).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(URL)
                .header("Authorization", "Bearer $apiKey").post(payload).build()
            val vecs = runCatching {
                client.newCall(req).execute().use { resp ->
                    val s = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) { Timber.w("embeddings error %d: %s", resp.code, s.take(200)); return null }
                    val data = JSONObject(s).getJSONArray("data")
                    (0 until data.length()).map { i ->
                        val e = data.getJSONObject(i).getJSONArray("embedding")
                        FloatArray(e.length()) { e.getDouble(it).toFloat() }
                    }
                }
            }.getOrNull() ?: return null
            out += vecs
        }
        return out
    }
}
