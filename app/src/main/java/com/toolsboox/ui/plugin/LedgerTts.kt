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

    private var chunks: List<String> = emptyList()
    private var index = 0            // chunk currently speaking / to resume from
    private var paused = false

    /** Called on the main thread when playback starts (true) or finishes/stops (false). */
    var onStateChange: ((Boolean) -> Unit)? = null

    /** Follow-along: the SENTENCE currently being spoken, fired on the main thread only when it
     *  changes. SENTENCE-level by design (Michael's option, 08-30): word-level would mean an
     *  e-ink repaint per word and a fragile offset map across the 3500-char chunk joins; the
     *  sentence is the honest quiet unit. Derived from [onRangeStart]'s char range within the
     *  speaking chunk. */
    var onSentence: ((String) -> Unit)? = null
    private var lastSentence: String? = null

    private fun sentenceAt(chunk: String, at: Int): String {
        if (chunk.isEmpty()) return ""
        val i = at.coerceIn(0, chunk.length - 1)
        val enders = charArrayOf('.', '!', '?', '。')
        var start = 0
        for (j in i - 1 downTo 0) if (chunk[j] in enders) { start = j + 1; break }
        var end = chunk.length
        for (j in i until chunk.length) if (chunk[j] in enders) { end = (j + 1).coerceAtMost(chunk.length); break }
        return chunk.substring(start, end).trim()
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) { Timber.w("TTS init failed: %d", status); return@TextToSpeech }
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                    val j = id?.toIntOrNull() ?: return
                    val sent = sentenceAt(chunks.getOrNull(j) ?: return, start)
                    if (sent.isNotBlank() && sent != lastSentence) {
                        lastSentence = sent
                        main.post { onSentence?.invoke(sent) }
                    }
                }
                override fun onStart(id: String?) {
                    id?.toIntOrNull()?.let { index = it }
                    main.post { onStateChange?.invoke(true) }
                }
                override fun onDone(id: String?) {
                    if (id?.toIntOrNull() == chunks.lastIndex && !paused) main.post { onStateChange?.invoke(false) }
                }
                @Deprecated("legacy") override fun onError(id: String?) = main.post { onStateChange?.invoke(false) }.let {}
                override fun onError(id: String?, code: Int) = main.post { onStateChange?.invoke(false) }.let {}
            })
            pending?.let { val t = it; pending = null; speak(t) }
        }
    }

    val isSpeaking: Boolean get() = tts?.isSpeaking == true && !paused
    val isPaused: Boolean get() = paused
    val isActive: Boolean get() = paused || isSpeaking

    /** Speak [text] from the top (flushes anything in progress). Buffers if TTS isn't ready yet. */
    fun speak(text: String) {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        if (!ready) { pending = clean; return }
        chunks = chunk(clean); paused = false
        speakFrom(0)
    }

    private fun speakFrom(from: Int) {
        val engine = tts ?: return
        index = from
        for (j in from until chunks.size) {
            engine.speak(chunks[j], if (j == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, j.toString())
        }
    }

    /** TTS has no native pause, so stop and remember which chunk to resume from. */
    fun pause() {
        if (!isSpeaking) return
        paused = true
        tts?.stop()
        onStateChange?.invoke(false)
    }

    fun resume() {
        if (!paused || chunks.isEmpty()) return
        paused = false
        speakFrom(index.coerceIn(0, chunks.lastIndex))
    }

    /** Progress through the current text, in engine chunks (roughly paragraphs). */
    val chunkIndex: Int get() = index
    val chunkCount: Int get() = chunks.size
    /** Characters still to be spoken, the current chunk counted whole (the engine reports no
     *  intra-chunk position) — the transport turns it into a "~N min left" the way a podcast's
     *  clock does, so read-aloud says how long it is, not just which paragraph it is on. */
    val remainingChars: Int get() = chunks.drop(index.coerceAtLeast(0)).sumOf { it.length }
    val totalChars: Int get() = chunks.sumOf { it.length }

    /** Jump to chunk [i] and keep speaking — the transport bar's tap/drag-to-seek for TTS. */
    fun seekTo(i: Int) {
        if (chunks.isEmpty()) return
        paused = false
        speakFrom(i.coerceIn(0, chunks.lastIndex))
    }

    /** Set the speech rate (1.0 = normal). Applies to the next utterance; re-speaks the current
     *  chunk so the change is heard immediately while playing. */
    fun setRate(rate: Float) {
        tts?.setSpeechRate(rate)
        if (!paused && chunks.isNotEmpty()) speakFrom(index.coerceIn(0, chunks.lastIndex))
    }

    /** Jump forward/back by [delta] chunks and keep speaking (used by the player's ⏭/⏮). */
    fun skip(delta: Int) {
        if (chunks.isEmpty()) return
        paused = false
        speakFrom((index + delta).coerceIn(0, chunks.lastIndex))
    }

    fun stop() { paused = false; chunks = emptyList(); tts?.stop(); onStateChange?.invoke(false) }

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

    private companion object { const val LIMIT = 3500 }
}
