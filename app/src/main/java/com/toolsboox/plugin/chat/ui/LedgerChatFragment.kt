package com.toolsboox.plugin.chat.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.R
import com.toolsboox.databinding.FragmentLedgerChatBinding
import com.toolsboox.plugin.chat.da.Section
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import com.toolsboox.plugin.chat.nw.LedgerChatService
import com.toolsboox.plugin.chat.nw.NotebotLoop
import com.toolsboox.plugin.chat.nw.NotebotRegistry
import com.toolsboox.plugin.chat.nw.NotebotRemote
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Notebot — one conversation surface, two reaches (DESIGN-NOTEBOT-VOICE.md).
 *
 * The local reach is the Ask-my-Ledger corpus plus the local doing verbs; the remote reach is
 * the mjh.yoga notes-bot's world through eight curated tools. The model decides which reach a
 * question needs — one bot with two arms, not two bots with a switcher (a switcher is a mode,
 * and the standing rule is no modes). Each answer's receipt line names which arms it used.
 *
 * Voice in: tap the mic to dictate into the question field (read it, fix it, send it — the
 * full TitlePad discipline); HOLD the mic to talk-and-send — release ends the recording, the
 * transcript lands in the field visibly, a 2.5 s countdown pill offers "tap to edit", then it
 * sends. Nothing is ever sent that was not first shown.
 *
 * Voice out: system TTS, symmetry by default — a spoken question gets a spoken answer, a typed
 * question gets a read one — with a remembered speaker toggle overriding in both directions.
 * Errors are spoken too when the turn was spoken: hands-free means eyes-free.
 *
 * VOICE STATES, all flat (e-ink canon — no waveform, no pulse, no meter):
 *   idle → recording (counting bar, by the second) → transcribing (progress bar)
 *        → [hold path] countdown pill (by the second) → asking (progress bar)
 *        → answered (transcript turn) → speaking (one pill, tap to stop).
 * States swap in place with the input row; nothing floats, nothing animates per-frame.
 */
@AndroidEntryPoint
class LedgerChatFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var corpusService: LedgerCorpusService

    @Inject
    lateinit var chatService: LedgerChatService

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    override val view = R.layout.fragment_ledger_chat

    private lateinit var binding: FragmentLedgerChatBinding

    private var asking = false
    private var lastQuestion: String = ""
    private var lastAnswer: String = ""

    /** Which tools the last answered turn used (call order, deduped) — the receipt the saved
     *  note repeats, so the note and the screen cannot disagree about what was consulted. */
    private var lastTools: List<String> = emptyList()

    /** One id per visit; every history row this visit writes carries it. Per-device, no sync —
     *  the chat is working material, and one-truth-per-thing is canon (brief §6). */
    private var sessionId: String = ""

    /** The answered turns of this visit, oldest first — the loop sees the last 8 as context. */
    private val sessionTurns = mutableListOf<Pair<String, String>>()

    /**
     * The send-to-Ask bridge's provenance preamble — rides the system context (never the visible
     * question) for every ask in this visit, so follow-up questions stay grounded in where the
     * passage came from. Held in a field rather than read straight off [getArguments] at ask time
     * because it can now be PUT DOWN: the banner's ✕ drops the passage and the grounding together,
     * and an argument the fragment kept re-reading would resurrect it on the next question.
     */
    private var askContext: String? = null

    // ---- voice state ---------------------------------------------------------------------------

    private enum class RecMode { NONE, TAP, HOLD }

    private var recMode = RecMode.NONE
    private var recShownSecond = -1
    private var countdownDeadline = 0L
    private var countdownShownSecond = -1

    /** Was the question that is currently being asked (or answered) spoken in? Drives the
     *  voice-in → voice-out symmetry and the spoken-errors rule. */
    private var lastVoiced = false

    private var tts: com.toolsboox.ui.plugin.LedgerTts? = null

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recTicker: Runnable? = null
    private var countdownTicker: Runnable? = null

    /** What to do once the mic permission comes back granted. */
    private var pendingMicAction: (() -> Unit)? = null

    private val micPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingMicAction; pendingMicAction = null
        if (granted) action?.invoke() else showMessage("Notebot needs the microphone for voice.")
    }

    /** When the RecognizerIntent fallback fires from a HOLD, its transcript auto-sends through
     *  the same visible countdown the Whisper path uses. */
    private var fallbackHold = false

    /** System speech-to-text fallback (for units without an OpenAI key) → into the question box.
     *  Tap path: dictate-then-edit. Hold path: the countdown takes it from here. */
    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val hold = fallbackHold; fallbackHold = false
        if (!text.isNullOrBlank()) {
            val cur = binding.questionEdit.text?.toString().orEmpty()
            binding.questionEdit.setText(if (cur.isBlank() || hold) text else "$cur $text")
            binding.questionEdit.setSelection(binding.questionEdit.text?.length ?: 0)
            if (hold) startCountdown()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerChatBinding.bind(view)

        sessionId = "boox-" + java.util.UUID.randomUUID().toString().take(8)

        val prefs = encryptedPrefs()
        // One-time migration: an earlier build stored a single (Claude) key under the base keys.
        if (prefs.getString(apiKeyKey(LedgerChatService.ANTHROPIC), null) == null) {
            prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }?.let { old ->
                prefs.edit()
                    .putString(apiKeyKey(LedgerChatService.ANTHROPIC), old)
                    .putString(modelKey(LedgerChatService.ANTHROPIC),
                        prefs.getString(KEY_MODEL, LedgerChatService.DEFAULT_MODEL))
                    .apply()
            }
        }
        var provider = prefs.getString(KEY_PROVIDER, LedgerChatService.ANTHROPIC) ?: LedgerChatService.ANTHROPIC
        loadProviderFields(provider)
        (if (provider == LedgerChatService.OPENAI) binding.providerOpenai else binding.providerClaude).isChecked = true

        binding.providerGroup.setOnCheckedChangeListener { _, checkedId ->
            // Stash the current fields under the old provider, then swap to the newly-picked one.
            saveProviderFields(provider)
            provider = if (checkedId == R.id.provider_openai) LedgerChatService.OPENAI else LedgerChatService.ANTHROPIC
            loadProviderFields(provider)
        }

        binding.settingsToggle.setOnClickListener {
            binding.settingsPanel.visibility =
                if (binding.settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        binding.secretEdit.setText(prefs.getString(NotebotRemote.KEY_SECRET, "") ?: "")
        binding.saveKeyButton.setOnClickListener {
            saveProviderFields(provider)
            prefs.edit()
                .putString(KEY_PROVIDER, provider)
                // The one new credential field: the notes ingest secret that unlocks the remote
                // reach. Same encrypted store as the keys (the standing Keychain equivalent).
                .putString(NotebotRemote.KEY_SECRET, binding.secretEdit.text.toString().trim())
                .apply()
            binding.modelEdit.setText(prefs.getString(modelKey(provider), LedgerChatService.defaultModel(provider)))
            binding.settingsPanel.visibility = View.GONE
            showMessage(R.string.ledger_chat_key_saved)
        }
        binding.askButton.setOnClickListener { ask(voiced = false) }
        binding.personaButton.setOnClickListener { showPersonaDialog() }
        binding.saveNoteButton.setOnClickListener { saveAnswerAsNote() }
        binding.saveFeedButton.setOnClickListener { saveAnswerToFeed() }
        binding.historyButton.setOnClickListener { showHistoryDialog() }
        updatePersonaLabel()

        // ---- the mic's two verbs (the app's standing gesture grammar: tap acts, hold is the
        // second verb). Tap = dictate-then-edit; hold = talk-and-send with the visible countdown.
        binding.micButton.setOnClickListener { startVoice(hold = false) }
        binding.micButton.setOnLongClickListener { startVoice(hold = true); true }
        binding.micButton.setOnTouchListener { _, ev ->
            // Release ends a HOLD recording — that IS the gesture; the transcript then lands in
            // the field and the countdown runs. Returning false keeps click/long-click alive.
            if ((ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL)
                && recMode == RecMode.HOLD) finishRecording()
            false
        }
        // Tap the counting bar to stop a tap-mode recording (the bar's own label says so).
        binding.recordingBar.setOnClickListener { if (recMode == RecMode.TAP) finishRecording() }
        // Tapping during the countdown cancels the send and leaves the transcript in the field
        // for correction — the cheap, reversible mis-fire the hold gesture was priced for.
        binding.countdownPill.setOnClickListener { cancelCountdown() }
        binding.questionEdit.setOnClickListener { if (countdownTicker != null) cancelCountdown() }

        // ---- voice out: the remembered speaker toggle + the tap-to-stop pill.
        updateSpeakerLabel()
        binding.speakerToggle.setOnClickListener { cycleSpeakerMode() }
        binding.speakingPill.setOnClickListener { stopSpeaking() }

        // The presets that ship arrive on first look rather than at install, the same way the
        // personas do, so a fresh Ask has something on the chip row to tap.
        com.toolsboox.plugin.chat.nw.AskPresetStore.seedDefaults(requireContext())
        rebuildPresetRow()

        // A sent passage: shown in the banner, riding the grounding, waiting for a prompt. Read
        // from the arguments on every view creation (not just a fresh one) so a rotation or a
        // process death doesn't quietly detach the passage from the visit it belongs to.
        askContext = arguments?.getString("ask_context")?.trim()?.takeIf { it.isNotEmpty() }
        showPassage(arguments?.getString("ask_passage")?.trim())
        binding.passageClear.setOnClickListener {
            // One gesture, both halves. Dropping only the banner would leave the model still
            // reading a passage the screen no longer admits to holding.
            askContext = null
            arguments?.remove("ask_context")
            arguments?.remove("ask_passage")
            showPassage(null)
        }

        // Seeded query (e.g. from a lasso "Find in Ledger") → prefill and auto-ask once. This is
        // the door left open for callers that genuinely know what they want to ask; the send-to-Ask
        // bridge deliberately no longer uses it (see [AskBridge.askFrom]).
        if (savedInstanceState == null) {
            arguments?.getString("initial_query")?.trim()?.takeIf { it.isNotEmpty() }?.let { seed ->
                binding.questionEdit.setText(seed)
                arguments?.remove("initial_query")
                binding.questionEdit.post { ask(voiced = false) }
            }
        }
        // Shared ▦ Ledger directory — consistent "get in/out" nav across every surface.
        binding.ledgerButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }
        // The header ▦ retires into the rail — ☰ Hub is the same door. The speaker toggle
        // stays in the title row: it shows a state, and the rail's icons don't. Ask has no
        // other chrome: Hub · ⇄ · ✕.
        binding.ledgerButton.visibility = View.GONE
        setupActionRail(binding.chatRail, "chat", actions = { emptyList() })
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen mid-recording discards the clip (a chat question is not a memo),
        // kills any countdown, and silences the voice — no state survives that the screen
        // doesn't show.
        if (::binding.isInitialized) {
            if (recMode != RecMode.NONE) {
                com.toolsboox.ot.VoiceRecorder.stop(save = false)
                resetVoiceChrome()
            }
            cancelCountdown()
        }
        stopSpeaking()
    }

    override fun onDestroyView() {
        tts?.shutdown(); tts = null
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun apiKeyKey(provider: String) = "${KEY_API}_$provider"
    private fun modelKey(provider: String) = "${KEY_MODEL}_$provider"

    private fun loadProviderFields(provider: String) {
        val prefs = encryptedPrefs()
        binding.apiKeyEdit.setText(prefs.getString(apiKeyKey(provider), "") ?: "")
        binding.modelEdit.setText(prefs.getString(modelKey(provider), LedgerChatService.defaultModel(provider)))
        binding.apiKeyEdit.hint = getString(
            if (provider == LedgerChatService.OPENAI) R.string.ledger_chat_api_key_hint_openai
            else R.string.ledger_chat_api_key_hint
        )
    }

    private fun saveProviderFields(provider: String) {
        val model = binding.modelEdit.text.toString().ifBlank { LedgerChatService.defaultModel(provider) }
        encryptedPrefs().edit()
            .putString(apiKeyKey(provider), binding.apiKeyEdit.text.toString().trim())
            .putString(modelKey(provider), model)
            .apply()
    }

    private fun selectedScope(): Set<Section> {
        val s = mutableSetOf<Section>()
        if (binding.scopeBooks.isChecked) s += Section.BOOKS
        if (binding.scopeArticles.isChecked) s += Section.ARTICLES
        if (binding.scopeFeed.isChecked) s += Section.FEED
        if (binding.scopePlanner.isChecked) s += Section.PLANNER
        if (binding.scopeMedia.isChecked) s += Section.MEDIA
        // Your own captured text — OCR'd page sections, Text Notes, tasks & events — is always in
        // scope so the whole corpus is searchable regardless of the classic-section checkboxes.
        s += Section.SECTIONS; s += Section.NOTES; s += Section.TASKS
        return s
    }

    // ---- the transcript ------------------------------------------------------------------------

    /**
     * Append one turn to the ledger-page transcript (brief §7): question in bold, full width;
     * answer in regular weight beneath; the receipt caption under that in small type; a hairline
     * rule between turns. Newest at the bottom, scrolled to. Returns the answer and receipt
     * views so the ask can fill them in when the loop comes back — appending invalidates from
     * the new turn down, never the page (full-refresh discipline).
     */
    private fun appendTurn(question: String): Pair<android.widget.TextView, android.widget.TextView> {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val transcript = binding.transcript

        if (transcript.childCount > 0) {
            transcript.addView(View(ctx).apply {
                setBackgroundColor(0xFFBBBBBB.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { topMargin = (10 * density).toInt(); bottomMargin = (10 * density).toInt() }
            })
        }

        val q = android.widget.TextView(ctx).apply {
            text = question
            textSize = 17f
            setTextColor(0xFF000000.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextIsSelectable(true)
        }
        val a = android.widget.TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            setTextIsSelectable(true)
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        val receipt = android.widget.TextView(ctx).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            visibility = View.GONE
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        transcript.addView(q); transcript.addView(a); transcript.addView(receipt)
        scrollTranscriptToBottom()
        return a to receipt
    }

    private fun scrollTranscriptToBottom() {
        binding.transcriptScroll.post { binding.transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * An answer with its citations turned into doors. Every snippet the corpus hands the model
     * is cited as `yyyy-MM-dd · kind · label`; making that tappable is the difference between
     * "the machine says you wrote this" and being able to go and look. Underline only, no
     * colour — the standing e-ink rule.
     */
    private fun setAnswerWithLinks(target: android.widget.TextView, text: String) {
        val span = android.text.SpannableString(text)
        for (link in com.toolsboox.plugin.calendar.ot.CitationLinks.find(text)) {
            span.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) {
                    if (link.notePage != null) {
                        com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(
                            this@LedgerChatFragment, link.date, link.notePage)
                    } else {
                        com.toolsboox.plugin.calendar.CalendarNavigator.toDayPage(
                            this@LedgerChatFragment, link.date)
                    }
                }

                // Underline only: a coloured link on e-ink is a grey smudge.
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.isUnderlineText = true
                    ds.color = ds.linkColor
                }
            }, link.start, link.endExclusive, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        target.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        target.text = span
    }

    // ---- voice in ------------------------------------------------------------------------------

    /**
     * Open the microphone. Primary path: [com.toolsboox.ot.VoiceRecorder] (AAC/m4a — capture is
     * a solved problem here) → OpenAI Whisper via the key [com.toolsboox.plugin.chat.nw.EmbeddingIndex]
     * already holds — it works on every unit that can chat at all, and it hears "Pasasana".
     * Fallback: the system RecognizerIntent where present. Never a dead button: with neither,
     * the mic says plainly what it needs.
     */
    private fun startVoice(hold: Boolean) {
        if (recMode != RecMode.NONE) return
        // Talking over the bot is the interrupt a conversation already has.
        stopSpeaking()
        val ctx = requireContext()
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingMicAction = { startVoice(hold) }
            micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!com.toolsboox.plugin.calendar.nw.Transcribe.hasKey(ctx)) {
            startDictationFallback(hold)
            return
        }
        cancelCountdown()
        val out = File(ctx.cacheDir, "notebot-ask.m4a")
        val ok = com.toolsboox.ot.VoiceRecorder.start(ctx, out) { f, _ -> onRecorded(f) }
        if (!ok) { showMessage("Couldn't open the microphone."); return }
        recMode = if (hold) RecMode.HOLD else RecMode.TAP
        recShownSecond = -1
        binding.inputRow.visibility = View.GONE
        binding.recordingBar.visibility = View.VISIBLE
        tickRecordingBar()
        val tick = object : Runnable {
            override fun run() {
                if (recMode == RecMode.NONE) return
                tickRecordingBar()
                uiHandler.postDelayed(this, 500)
            }
        }
        recTicker = tick
        uiHandler.postDelayed(tick, 500)
    }

    /** The flat recording bar: `● Listening 0:07 — release to send` (hold) or `— tap Stop`
     *  (tap). Redrawn only when the displayed second changes — e-ink counts, it never pulses. */
    private fun tickRecordingBar() {
        val s = com.toolsboox.ot.VoiceRecorder.elapsedSeconds
        if (s == recShownSecond) return
        recShownSecond = s
        val clock = "%d:%02d".format(s / 60, s % 60)
        binding.recordingBar.text =
            if (recMode == RecMode.HOLD) "● Listening $clock — release to send"
            else "● Listening $clock — tap Stop"
    }

    /** End the recording (either mode). Sub-half-second clips are VoiceRecorder's mis-tap
     *  discard; anything real flows on to [onRecorded]. */
    private fun finishRecording() {
        val wasHold = recMode == RecMode.HOLD
        recMode = RecMode.NONE
        recTicker?.let { uiHandler.removeCallbacks(it) }; recTicker = null
        pendingHoldSend = wasHold
        com.toolsboox.ot.VoiceRecorder.stop(save = true, onDiscarded = {
            pendingHoldSend = false
            resetVoiceChrome()
        })
    }

    /** Set by [finishRecording], read by [onRecorded]: does this transcript auto-send? */
    private var pendingHoldSend = false

    /** Whisper the clip, land the transcript IN THE INPUT FIELD (editable — the auditable
     *  record of what was actually asked), then either wait for the reader (tap path) or run
     *  the visible countdown (hold path). */
    private fun onRecorded(file: File) {
        val hold = pendingHoldSend; pendingHoldSend = false
        resetVoiceChrome()
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val t = com.toolsboox.plugin.calendar.nw.Transcribe.audio(requireContext(), file)
                file.delete()
                t
            }
            binding.progress.visibility = View.INVISIBLE
            if (text.isNullOrBlank()) {
                // The turn came in by voice, so its failure is said as well as shown —
                // hands-free means eyes-free.
                showMessage("Couldn't transcribe that — try again.")
                if (shouldSpeak(voiced = true)) speak("I couldn't transcribe that. Try again.")
                return@launch
            }
            binding.questionEdit.setText(text)
            binding.questionEdit.setSelection(text.length)
            if (hold) startCountdown()
        }
    }

    /** The RecognizerIntent fallback (some Boox units lack Google services; some lack a key). */
    private fun startDictationFallback(hold: Boolean) {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.ledger_chat_dictate))
        }
        fallbackHold = hold
        runCatching { speechLauncher.launch(intent) }
            .onFailure {
                fallbackHold = false
                showMessage("Voice needs an OpenAI key (Settings ⚙) — this device has no speech recognizer.")
            }
    }

    /** Put the input row back; clear recording chrome. */
    private fun resetVoiceChrome() {
        recMode = RecMode.NONE
        recTicker?.let { uiHandler.removeCallbacks(it) }; recTicker = null
        binding.recordingBar.visibility = View.GONE
        binding.inputRow.visibility = View.VISIBLE
    }

    /**
     * The hold-path's 2.5 s "Sending — tap to edit" window. Why auto-send is right here when it
     * was wrong for titles: a misheard title is a document filed under a name you'll never
     * search for; a misheard question produces a visibly wrong answer immediately, and the
     * correction is a follow-up turn — which a conversation is made of anyway. The pill counts
     * by the second (e-ink), and any tap cancels into the editable field.
     */
    private fun startCountdown() {
        cancelCountdown()
        countdownDeadline = SystemClock.elapsedRealtime() + 2500L
        countdownShownSecond = -1
        binding.countdownPill.visibility = View.VISIBLE
        val tick = object : Runnable {
            override fun run() {
                val remaining = countdownDeadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    binding.countdownPill.visibility = View.GONE
                    countdownTicker = null
                    ask(voiced = true)
                    return
                }
                val sec = ((remaining + 999) / 1000).toInt()
                if (sec != countdownShownSecond) {
                    countdownShownSecond = sec
                    binding.countdownPill.text = "● Sending in $sec — tap to edit"
                }
                uiHandler.postDelayed(this, 250)
            }
        }
        countdownTicker = tick
        tick.run()
    }

    /** Cancel the send; the transcript stays in the field for correction. */
    private fun cancelCountdown() {
        countdownTicker?.let { uiHandler.removeCallbacks(it) }
        countdownTicker = null
        binding.countdownPill.visibility = View.GONE
    }

    // ---- voice out -----------------------------------------------------------------------------

    /** Speaker override: "auto" (symmetry — the default), "on" (always speak), "off" (never).
     *  Remembered across visits; the symmetry rule is only the default. */
    private fun speakerMode(): String =
        encryptedPrefs().getString(KEY_SPEAKER, "auto") ?: "auto"

    private fun cycleSpeakerMode() {
        val next = when (speakerMode()) { "auto" -> "on"; "on" -> "off"; else -> "auto" }
        encryptedPrefs().edit().putString(KEY_SPEAKER, next).apply()
        updateSpeakerLabel()
    }

    private fun updateSpeakerLabel() {
        binding.speakerToggle.text = when (speakerMode()) {
            "on" -> "🔊 On"; "off" -> "🔇 Off"; else -> "🔊 Auto"
        }
    }

    /** Voice in → voice out; typed → silent; the toggle overrides in both directions. */
    private fun shouldSpeak(voiced: Boolean): Boolean = when (speakerMode()) {
        "on" -> true
        "off" -> false
        else -> voiced
    }

    private fun ensureTts(): com.toolsboox.ui.plugin.LedgerTts {
        tts?.let { return it }
        val t = com.toolsboox.ui.plugin.LedgerTts(requireContext())
        t.onStateChange = { speaking ->
            if (::binding.isInitialized)
                binding.speakingPill.visibility = if (speaking) View.VISIBLE else View.GONE
        }
        tts = t
        return t
    }

    /** Speak an answer. Citation tags are for eyes — `[2026-07-13 · book · …]` read aloud is
     *  noise — so they're stripped from the spoken copy only; the screen keeps them as doors. */
    private fun speak(text: String) {
        val spoken = text.replace(Regex("\\[[^\\[\\]]{0,80}·[^\\[\\]]{0,80}\\]"), "").trim()
        if (spoken.isNotEmpty()) ensureTts().speak(spoken)
    }

    private fun stopSpeaking() {
        tts?.takeIf { it.isActive }?.stop()
        if (::binding.isInitialized) binding.speakingPill.visibility = View.GONE
    }

    // ---- keeps, history, persona, presets ------------------------------------------------------

    /** Save the current answer into the local "Ask my Ledger" feed (shows up in Feed Ledger). */
    private fun saveAnswerToFeed() {
        if (lastAnswer.isBlank()) { showMessage("Ask something first, then save its answer."); return }
        val ok = com.toolsboox.plugin.feeds.nw.AskFeedStore.add(requireContext(), lastQuestion, lastAnswer)
        showMessage(if (ok) "Saved to your Ask Answers feed." else "Couldn't save that — try again.")
    }

    /** Browse past exchanges; tap one to re-read it as a turn in today's transcript. */
    private fun showHistoryDialog() {
        val ctx = requireContext()
        val turns = com.toolsboox.plugin.chat.nw.ChatHistoryStore.all(ctx)
        if (turns.isEmpty()) { showMessage("No history yet."); return }
        val labels = turns.map { t ->
            val date = t.at.take(10); val q = t.question.take(60)
            if (date.isNotBlank()) "$date · $q" else q
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Ask history")
            .setItems(labels) { _, which ->
                val t = turns[which]
                lastQuestion = t.question; lastAnswer = t.answer
                // The history keeps the question and the answer, not how they were retrieved.
                // Zero the receipt rather than let a live ask's tool list follow a reopened
                // answer into a saved note — a note claiming a grounding this exchange never
                // had would be worse than one claiming nothing. Reopened turns do NOT join
                // [sessionTurns] either: the model's context is this visit's conversation,
                // not a scrapbook.
                lastTools = emptyList()
                val (answerView, _) = appendTurn(t.question)
                setAnswerWithLinks(answerView, t.answer)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updatePersonaLabel() {
        val active = com.toolsboox.plugin.chat.nw.PersonaStore.activeName(requireContext())
        binding.personaButton.text = if (active != null) "🎭 $active" else getString(R.string.ledger_persona)
    }

    /** Pick the active persona (or None), or open the editor to add/edit one. */
    private fun showPersonaDialog() {
        val ctx = requireContext()
        // The built-ins arrive on first look rather than at install, so a fresh Ask isn't empty.
        com.toolsboox.plugin.chat.nw.PersonaStore.seedDefaults(ctx)
        val personas = com.toolsboox.plugin.chat.nw.PersonaStore.all(ctx)
        val names = personas.map { it.name }
        val labels = (listOf("🚫  None") + names).toTypedArray()
        val active = com.toolsboox.plugin.chat.nw.PersonaStore.activeName(ctx)
        val checked = if (active != null) (names.indexOf(active).takeIf { it >= 0 }?.plus(1) ?: 0) else 0
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Persona")
            .setSingleChoiceItems(labels, checked) { d, which ->
                com.toolsboox.plugin.chat.nw.PersonaStore.setActive(ctx, if (which == 0) null else names[which - 1])
                updatePersonaLabel(); d.dismiss()
            }
            .setNeutralButton("New / Edit…") { _, _ ->
                showPersonaEditor(personas.firstOrNull { it.name == active })
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * The persona editor, now the shared [NamedPromptEditor] rather than a hand-rolled dialog.
     *
     * It lost nothing in the move and gained the multi-line discipline the presets needed: the old
     * copy already had a four-line prompt box, and this one has six plus a footnote saying what the
     * field is for. What it really gained is that there is now ONE form to fix when either list
     * grows a habit — the second copy is where they drift.
     */
    private fun showPersonaEditor(existing: com.toolsboox.plugin.chat.nw.Persona?) {
        val ctx = requireContext()
        NamedPromptEditor.show(
            fragment = this,
            title = if (existing == null) "New persona" else "Edit persona",
            nameHint = "Persona name",
            promptHint = "System prompt — the voice / role the AI should take on",
            footnote = "The voice and stance the answers come back in. It stays put until you " +
                "change it, and it is not affected by which preset you tap.",
            original = existing?.name.orEmpty(),
            prompt = existing?.prompt.orEmpty(),
            onSave = { name, prompt ->
                // Personas key on name, so a rename has to retire the old key or you get two.
                if (existing != null && existing.name != name) {
                    com.toolsboox.plugin.chat.nw.PersonaStore.delete(ctx, existing.name)
                }
                com.toolsboox.plugin.chat.nw.PersonaStore.upsert(
                    ctx, com.toolsboox.plugin.chat.nw.Persona(name, prompt))
                com.toolsboox.plugin.chat.nw.PersonaStore.setActive(ctx, name)
                updatePersonaLabel()
            },
            onDelete = if (existing == null) null else { name ->
                com.toolsboox.plugin.chat.nw.PersonaStore.delete(ctx, name); updatePersonaLabel()
            },
        )
    }

    // ---- prompt presets ------------------------------------------------------------------------

    /**
     * The chip row over the transcript, rebuilt from the store.
     *
     * Tapping a chip ASKS it — that is the whole point of a preset, and a chip that opened a
     * confirmation first would cost more taps than typing the question out. Holding one edits or
     * deletes it, which is this app's standing answer to "where do the management verbs live"
     * (hold to act, no modes), and the trailing ＋ makes a new one. Presets fire as TURNS now —
     * the question lands in the transcript like any other.
     *
     * Tapping a preset does NOT touch the active persona, and that is the whole separation working:
     * the stance stays whatever it was while the question changes underneath it. Both the chip and
     * the persona button are read from their own stores at their own moments; neither writes to the
     * other's key.
     */
    private fun rebuildPresetRow() {
        val ctx = requireContext()
        val row = binding.presetRow
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val gap = (6 * density).toInt()

        fun chip(label: String, onTap: () -> Unit, onHold: (() -> Unit)?): android.widget.TextView =
            android.widget.TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(0xFF000000.toInt())
                // The e-ink edit background is the app's existing "this is a soft-edged control"
                // treatment; borrowing it keeps the row from needing a drawable of its own.
                setBackgroundResource(com.toolsboox.R.drawable.eink_edit_bg)
                setPadding((12 * density).toInt(), (7 * density).toInt(),
                    (12 * density).toInt(), (7 * density).toInt())
                isSingleLine = true
                setOnClickListener { onTap() }
                if (onHold != null) setOnLongClickListener { onHold(); true }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
            }

        for (preset in com.toolsboox.plugin.chat.nw.AskPresetStore.all(ctx)) {
            row.addView(chip(preset.name, onTap = {
                // The prompt goes into the visible question box rather than straight to the wire,
                // so what was asked is what you can read afterwards — and so a preset can be
                // amended in place before sending, which is the cheapest way to turn a recurring
                // question into a slightly different one.
                binding.questionEdit.setText(preset.prompt)
                binding.questionEdit.setSelection(binding.questionEdit.text?.length ?: 0)
                ask(voiced = false)
            }, onHold = { showPresetEditor(preset) }))
        }
        row.addView(chip("＋ Preset", onTap = { showPresetEditor(null) }, onHold = null))
    }

    /** New / edit / delete a preset, through the same form the personas use. */
    private fun showPresetEditor(existing: com.toolsboox.plugin.chat.nw.AskPreset?) {
        val ctx = requireContext()
        NamedPromptEditor.show(
            fragment = this,
            title = if (existing == null) "New prompt preset" else "Edit prompt preset",
            nameHint = "Short label — what the chip says",
            promptHint = "The question it asks",
            footnote = "A question or a whole workflow you run often. Write as many lines as it " +
                "takes — it is sent exactly as typed, and the passage you sent to Ask rides along " +
                "behind it.",
            original = existing?.name.orEmpty(),
            prompt = existing?.prompt.orEmpty(),
            onSave = { name, prompt ->
                if (existing != null && existing.name != name) {
                    com.toolsboox.plugin.chat.nw.AskPresetStore.delete(ctx, existing.name)
                }
                com.toolsboox.plugin.chat.nw.AskPresetStore.upsert(
                    ctx, com.toolsboox.plugin.chat.nw.AskPreset(name, prompt))
                rebuildPresetRow()
            },
            onDelete = if (existing == null) null else { name ->
                com.toolsboox.plugin.chat.nw.AskPresetStore.delete(ctx, name); rebuildPresetRow()
            },
        )
    }

    // ---- the sent passage ----------------------------------------------------------------------

    /** Show (or hide) the banner holding what was sent to Ask. Null/blank puts it away. */
    private fun showPassage(text: String?) {
        val sent = text?.trim().orEmpty()
        binding.passageText.text = sent
        binding.passageBanner.visibility = if (sent.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Save the exchange as a Text Note — Michael, choosing where answers live: "Answers can be
     * saved as text notes."
     *
     * ONE note per answer, filed on today, not one growing document per conversation. A single
     * accumulating note was the other candidate and it loses the thing that makes a note findable:
     * its title. A note titled by the question you asked comes back out of search, out of the
     * rhizome, out of the corpus Ask itself reads; a note called "Ask · Thursday" with nine answers
     * in it comes back as a wall. If a threaded read is ever wanted it can be assembled from these
     * — the reverse is not true.
     *
     * The body is markdown because Text Notes ARE markdown ([com.toolsboox.ot.MarkdownHighlight]
     * styles it in the editor, [com.toolsboox.ot.MarkdownRender] renders it for reading), so the
     * note styles itself with no extra work and the `.md` export off that surface is a real
     * markdown file. The provenance goes in as a blockquote at the FOOT rather than a preamble: the
     * answer is what you came back for, and where it came from is what you check afterwards.
     */
    private fun saveAnswerAsNote() {
        val ctx = requireContext()
        val q = lastQuestion.trim().ifEmpty { binding.questionEdit.text.toString().trim() }
        val a = lastAnswer.trim()
        if (a.isEmpty()) { showMessage("Ask something first, then save its answer."); return }

        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val sb = StringBuilder("*Asked ").append(stamp)
        com.toolsboox.plugin.chat.nw.PersonaStore.activeName(ctx)
            ?.let { sb.append(" · ").append(it) }
        sb.append("*\n\n")
        if (q.isNotEmpty()) sb.append("**").append(q).append("**\n\n")
        sb.append(a)
        // What was sent, verbatim from the same preamble the model read — so the note and the ask
        // cannot disagree about the provenance.
        askContext?.takeIf { it.isNotBlank() }?.let { g ->
            sb.append("\n\n---\n\n")
            sb.append(g.split("\n").joinToString("\n") { "> $it" })
            sb.append('\n')
        }
        // The receipt now names the TOOLS the turn used — Android has the loop, so the honest
        // receipt is the same one the screen's caption showed (the iOS "used <tools>" line).
        if (lastTools.isNotEmpty()) {
            sb.append("\n*Used: ").append(lastTools.joinToString(", ")).append(".*\n")
        }

        // Titled by the question, trimmed to a line — a title that ran to a paragraph would make
        // the Text Notes picker unreadable, and the full question is in the body regardless.
        val head = q.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "Ask my Ledger" }
        val title = "Ask · " + (if (head.length > 60) head.take(60) + "…" else head)
        runCatching {
            com.toolsboox.plugin.textnotes.TextNotesStore.addNote(
                ctx, java.time.LocalDate.now(), title, sb.toString())
        }.onSuccess { showMessage("Saved to today's Text Notes.") }
            .onFailure { showMessage("Couldn't save that note — try again.") }
    }

    // ---- the ask -------------------------------------------------------------------------------

    /**
     * One conversational turn through the Notebot tool loop ([NotebotLoop] — the ported iOS
     * registry/converse shape). Retrieval and creation are both TOOLS now; the old front-loaded
     * corpus + ```ledger-create path survives only for the grounded one-shot callers
     * (educate, zone prompts) in [LedgerChatService].
     *
     * [voiced] carries the voice-in → voice-out symmetry: it makes the answer (and any error)
     * spoken. Errors render in the answer slot as plain sentences and are spoken when the turn
     * was spoken — a blank answer area is never an error state.
     */
    private fun ask(voiced: Boolean = false) {
        if (asking) return
        val question = binding.questionEdit.text.toString().trim()
        if (question.isEmpty()) {
            showMessage(R.string.ledger_chat_need_question); return
        }
        val scope = selectedScope()
        val prefs = encryptedPrefs()
        val provider = prefs.getString(KEY_PROVIDER, LedgerChatService.ANTHROPIC) ?: LedgerChatService.ANTHROPIC
        val apiKey = prefs.getString(apiKeyKey(provider), "")?.trim().orEmpty()
        val model = prefs.getString(modelKey(provider), LedgerChatService.defaultModel(provider))
            ?: LedgerChatService.defaultModel(provider)

        asking = true
        lastVoiced = voiced
        stopSpeaking()
        cancelCountdown()
        binding.progress.visibility = View.VISIBLE
        binding.askButton.isEnabled = false

        // The question moves into the page — bold, full width — and the field clears for the
        // follow-up. The transcript, not the input box, is the record of what was asked.
        val (answerView, receiptView) = appendTurn(question)
        answerView.text = getString(R.string.ledger_chat_thinking)
        binding.questionEdit.setText("")

        // Grounding from "Ask about this" (AskBridge): rides the system context for the whole
        // visit until the banner's ✕ puts it down; the visible question stays what was asked.
        val askContext = this.askContext
        // The model sees the last 8 answered turns, not the whole history — the context window
        // is a cost surface, and a conversation that needs turn 40 needed a note at turn 39.
        val history = sessionTurns.takeLast(8)

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val persona = com.toolsboox.plugin.chat.nw.PersonaStore.activePrompt(requireContext())
                val registry = NotebotRegistry.build(
                    requireContext().applicationContext, corpusService, calendarDayService,
                    documentsRoot(), scope)
                NotebotLoop.converse(provider, apiKey, model, question, history, registry,
                    persona, askContext)
            }
            when (outcome) {
                is NotebotLoop.Outcome.Ok -> {
                    setAnswerWithLinks(answerView, outcome.text)
                    // The receipt caption: which arms the turn used. Small type, under the
                    // answer, exactly what the saved note will repeat.
                    if (outcome.toolsUsed.isNotEmpty()) {
                        receiptView.text = "· used " + outcome.toolsUsed.joinToString(", ")
                        receiptView.visibility = View.VISIBLE
                    }
                    lastQuestion = question; lastAnswer = outcome.text
                    lastTools = outcome.toolsUsed
                    sessionTurns += question to outcome.text
                    com.toolsboox.plugin.chat.nw.ChatHistoryStore.add(
                        requireContext(), question, outcome.text, sessionId)
                    // Voice in → voice out (or the remembered toggle's override).
                    if (shouldSpeak(voiced)) speak(outcome.text)
                }
                is NotebotLoop.Outcome.Err -> {
                    answerView.text = "⚠️ " + outcome.message
                    // A spoken question's failure is spoken: an error that only renders is an
                    // answer that silently never came.
                    if (shouldSpeak(voiced)) speak("I couldn't get an answer. " + outcome.message)
                }
            }
            binding.progress.visibility = View.INVISIBLE
            binding.askButton.isEnabled = true
            asking = false
            scrollTranscriptToBottom()
        }
    }

    override fun showLoading() {
        if (::binding.isInitialized) binding.progress.visibility = View.VISIBLE
    }

    override fun hideLoading() {
        if (::binding.isInitialized) binding.progress.visibility = View.INVISIBLE
    }


    private fun encryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(requireContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            requireContext(),
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val PREFS_NAME = "ledger_chat_encrypted_prefs"
        private const val KEY_API = "ledger_chat_api_key"
        private const val KEY_MODEL = "ledger_chat_model"
        private const val KEY_PROVIDER = "ledger_chat_provider"
        private const val KEY_SPEAKER = "notebot_speaker_mode"
    }
}
