package com.toolsboox.ui.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import timber.log.Timber
import java.util.Locale

/**
 * Thin wrapper over Android [TextToSpeech] for reader read-aloud (books + feed articles).
 * Long text is split into engine-safe chunks on sentence boundaries and queued; [onStateChange]
 * reports speaking/idle so the caller can flip a Read-aloud ↔ Stop control.
 */
class LedgerTts(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /** Called on the main thread when playback starts (true) or finishes/stops (false). */
    var onStateChange: ((Boolean) -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) { Timber.w("TTS init failed: %d", status); return@TextToSpeech }
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = main.post { onStateChange?.invoke(true) }.let {}
                override fun onDone(id: String?) { if (id == LAST) main.post { onStateChange?.invoke(false) } }
                @Deprecated("legacy") override fun onError(id: String?) = main.post { onStateChange?.invoke(false) }.let {}
                override fun onError(id: String?, code: Int) = main.post { onStateChange?.invoke(false) }.let {}
            })
            pending?.let { val t = it; pending = null; speak(t) }
        }
    }

    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    /** Speak [text] from the top (flushes anything in progress). Buffers if TTS isn't ready yet. */
    fun speak(text: String) {
        val engine = tts ?: return
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        if (!ready) { pending = clean; return }
        val chunks = chunk(clean)
        chunks.forEachIndexed { i, c ->
            val id = if (i == chunks.lastIndex) LAST else "c$i"
            engine.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() { tts?.stop(); onStateChange?.invoke(false) }

    fun shutdown() { runCatching { tts?.stop(); tts?.shutdown() }; tts = null }

    /** Break text into <~3500-char pieces on sentence boundaries (engine hard-caps at 4000). */
    private fun chunk(text: String): List<String> {
        if (text.length <= LIMIT) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?。])\\s+"))) {
            if (sb.length + sentence.length > LIMIT && sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
            if (sentence.length > LIMIT) {
                // A single monster sentence — hard-split on width.
                var s = sentence
                while (s.length > LIMIT) { out.add(s.substring(0, LIMIT)); s = s.substring(LIMIT) }
                sb.append(s).append(' ')
            } else sb.append(sentence).append(' ')
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }

    private companion object { const val LIMIT = 3500; const val LAST = "last" }
}
