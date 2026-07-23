package com.toolsboox.plugin.calendar.nw

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text for A/V grams via OpenAI Whisper (uses the OpenAI key from the chat prefs — the
 * same one embeddings use). Returns null without a key or on failure, so callers degrade gracefully.
 */
object Transcribe {
    private val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()
    private const val URL = "https://api.openai.com/v1/audio/transcriptions"

    fun hasKey(context: Context): Boolean = com.toolsboox.plugin.chat.nw.EmbeddingIndex.apiKey(context) != null

    /** Transcribe an audio (or audio-track) file. Blocking — call off the main thread. */
    fun audio(context: Context, file: File): String? {
        val key = com.toolsboox.plugin.chat.nw.EmbeddingIndex.apiKey(context) ?: return null
        if (!file.exists() || file.length() == 0L) return null
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("file", file.name, file.asRequestBody(guessMime(file.name).toMediaTypeOrNull()))
            .build()
        val req = Request.Builder().url(URL).header("Authorization", "Bearer $key").post(body).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Timber.w("whisper error %d: %s", resp.code, s.take(200)); return null }
                JSONObject(s).optString("text").trim().ifBlank { null }
            }
        }.getOrNull()
    }

    private fun guessMime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }
}
