package com.toolsboox.plugin.chat.ui

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.R
import com.toolsboox.databinding.FragmentLedgerChatBinding
import com.toolsboox.plugin.chat.da.Section
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import com.toolsboox.plugin.chat.nw.LedgerChatService
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Ask my Ledger — a grounded chat over the whole corpus (book highlights, feed
 * annotations, planner text and A/V grams), scoped to everything or the sections you
 * pick. Reads the on-disk day JSON, retrieves the relevant snippets, and answers via
 * Claude with citations back to the source day/section.
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

    /**
     * The send-to-Ask bridge's provenance preamble — rides the system context (never the visible
     * question) for every ask in this visit, so follow-up questions stay grounded in where the
     * passage came from. Held in a field rather than read straight off [getArguments] at ask time
     * because it can now be PUT DOWN: the banner's ✕ drops the passage and the grounding together,
     * and an argument the fragment kept re-reading would resurrect it on the next question.
     */
    private var askContext: String? = null

    /** What the ask was grounded in, as the answer footer reports it — kept so a saved note can
     *  say the same thing the screen said rather than a second, differently-counted version. */
    private var lastHits = 0
    private var lastCorpus = 0
    private var lastCreated = 0

    /** System speech-to-text → appended into the question box, for quick dictation. */
    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            val cur = binding.questionEdit.text?.toString().orEmpty()
            binding.questionEdit.setText(if (cur.isBlank()) text else "$cur $text")
            binding.questionEdit.setSelection(binding.questionEdit.text?.length ?: 0)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerChatBinding.bind(view)

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
        binding.saveKeyButton.setOnClickListener {
            saveProviderFields(provider)
            prefs.edit().putString(KEY_PROVIDER, provider).apply()
            binding.modelEdit.setText(prefs.getString(modelKey(provider), LedgerChatService.defaultModel(provider)))
            binding.settingsPanel.visibility = View.GONE
            showMessage(R.string.ledger_chat_key_saved)
        }
        binding.askButton.setOnClickListener { ask() }
        binding.personaButton.setOnClickListener { showPersonaDialog() }
        binding.micButton.setOnClickListener { startDictation() }
        binding.saveNoteButton.setOnClickListener { saveAnswerAsNote() }
        binding.saveFeedButton.setOnClickListener { saveAnswerToFeed() }
        binding.historyButton.setOnClickListener { showHistoryDialog() }
        updatePersonaLabel()

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
                binding.questionEdit.post { ask() }
            }
        }
        // Shared ▦ Ledger directory — consistent "get in/out" nav across every surface.
        binding.ledgerButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }
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

    /**
     * Show an answer with its citations turned into doors.
     *
     * Every snippet the corpus hands the model is cited as `yyyy-MM-dd · kind · label`, so every
     * claim in an answer already carries the date it came from — it was just sitting there as
     * text. Making it tappable is the difference between "the machine says you wrote this" and
     * being able to go and look.
     *
     * The destination is the day itself, since that is where every kind of object actually lives;
     * a picking or a note page goes to its own page on that day rather than the day sheet.
     */
    private fun setAnswerWithLinks(text: String) {
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

        binding.answerText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        binding.answerText.text = span
    }

    /** Launch system speech recognition. Result is appended to the question box. Falls back with a
     *  message on devices without a recognizer (some Boox units lack Google services). */
    private fun startDictation() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.ledger_chat_dictate))
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { showMessage("Speech input isn't available on this device.") }
    }

    /** Save the current answer into the local "Ask my Ledger" feed (shows up in Feed Ledger). */
    private fun saveAnswerToFeed() {
        if (lastAnswer.isBlank()) { showMessage("Ask something first, then save its answer."); return }
        val ok = com.toolsboox.plugin.feeds.nw.AskFeedStore.add(requireContext(), lastQuestion, lastAnswer)
        showMessage(if (ok) "Saved to your Ask Answers feed." else "Couldn't save that — try again.")
    }

    /** Browse past Ask-my-Ledger exchanges; tap one to reopen its answer. */
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
                // The history keeps the question and the answer, not how they were retrieved. Zero
                // the receipt rather than let the last live ask's counts follow a reopened answer
                // into a saved note — a note that claimed a grounding this exchange never had would
                // be worse than one that claims nothing.
                lastHits = 0; lastCorpus = 0; lastCreated = 0
                binding.questionEdit.setText(t.question)
                setAnswerWithLinks(t.answer)
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
     * The chip row over the question field, rebuilt from the store.
     *
     * Tapping a chip ASKS it — that is the whole point of a preset, and a chip that opened a
     * confirmation first would cost more taps than typing the question out. Holding one edits or
     * deletes it, which is this app's standing answer to "where do the management verbs live"
     * (hold to act, no modes), and the trailing ＋ makes a new one.
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
                ask()
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
        // The iPad writes a "used <tools>" receipt here, because over there the answer comes out of
        // a tool loop that can name what it called. Android has no tool loop: retrieval happens up
        // front and creation is the ```ledger-create block. So the honest Android receipt is what
        // this fragment actually knows — how much of the corpus grounded the answer, and what the
        // answer went on to create. Naming iOS's tools here would be a nicer-looking lie.
        val receipt = buildList {
            if (lastCorpus > 0) add("grounded in $lastHits of $lastCorpus entries in scope")
            if (lastCreated > 0) add("created $lastCreated item(s) in the Ledger")
        }
        if (receipt.isNotEmpty()) sb.append("\n*Used: ").append(receipt.joinToString("; ")).append(".*\n")

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

    private fun ask() {
        if (asking) return
        val question = binding.questionEdit.text.toString().trim()
        if (question.isEmpty()) {
            showMessage(R.string.ledger_chat_need_question); return
        }
        val scope = selectedScope()
        if (scope.isEmpty()) {
            showMessage(R.string.ledger_chat_need_scope); return
        }
        val prefs = encryptedPrefs()
        val provider = prefs.getString(KEY_PROVIDER, LedgerChatService.ANTHROPIC) ?: LedgerChatService.ANTHROPIC
        val apiKey = prefs.getString(apiKeyKey(provider), "")?.trim().orEmpty()
        val model = prefs.getString(modelKey(provider), LedgerChatService.defaultModel(provider))
            ?: LedgerChatService.defaultModel(provider)

        asking = true
        binding.progress.visibility = View.VISIBLE
        binding.askButton.isEnabled = false
        binding.answerText.text = getString(R.string.ledger_chat_thinking)

        // Grounding from "Ask about this" (AskBridge): where the item came from, what was sent, and
        // what already connects to it, riding ahead of the corpus excerpts in the system prompt —
        // the model sees it, the visible question stays exactly what the reader typed or tapped.
        // Deliberately NOT one-shot like initial_query: it lives in [askContext] for the whole
        // visit, so follow-up questions and preset chips alike keep knowing what "this" is, until
        // the banner's ✕ puts it down.
        val askContext = this.askContext

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val root = documentsRoot()
                val all = corpusService.gather(root, scope)
                val hits = corpusService.retrieveHybrid(all, question)
                val context = corpusService.buildContext(hits)
                val grounded = if (askContext == null) context else askContext + "\n\n" + context
                val persona = com.toolsboox.plugin.chat.nw.PersonaStore.activePrompt(requireContext())
                val answer = chatService.ask(provider, apiKey, model, question, grounded, persona)
                Triple(answer, hits.size, all.size)
            }
            val (answer, hitCount, corpusCount) = result
            when (answer) {
                is LedgerChatService.Result.Ok -> {
                    // If the model asked to create task/event/note, run it and confirm.
                    val (clean, creates) = extractCreates(answer.answer)
                    var shown = clean
                    var created = 0
                    if (creates.isNotEmpty()) {
                        created = withContext(Dispatchers.IO) { executeCreates(creates) }
                        if (created > 0) shown += "\n\n✓ Created $created item(s) in your Ledger."
                    }
                    // Kept for the saved note's receipt, so the note reports the same retrieval the
                    // footer under the answer does rather than a second count of its own.
                    lastHits = hitCount; lastCorpus = corpusCount; lastCreated = created
                    lastQuestion = question; lastAnswer = shown
                    com.toolsboox.plugin.chat.nw.ChatHistoryStore.add(requireContext(), question, shown)
                    setAnswerWithLinks(shown + "\n\n" + getString(R.string.ledger_chat_footer, hitCount, corpusCount))
                }
                is LedgerChatService.Result.Err -> binding.answerText.text = "⚠️ " + answer.message
            }
            binding.progress.visibility = View.INVISIBLE
            binding.askButton.isEnabled = true
            asking = false
        }
    }

    /** Pull the ```ledger-create fenced block out of an answer. Returns (answer-without-block, actions). */
    private fun extractCreates(answer: String): Pair<String, List<org.json.JSONObject>> {
        val m = Regex("```ledger-create\\s*([\\s\\S]*?)```").find(answer) ?: return answer to emptyList()
        val body = m.groupValues[1].trim()
        val actions = runCatching {
            val tok = org.json.JSONTokener(body).nextValue()
            when (tok) {
                is org.json.JSONArray -> (0 until tok.length()).mapNotNull { tok.optJSONObject(it) }
                is org.json.JSONObject -> listOf(tok)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        return answer.removeRange(m.range).trim() to actions
    }

    /** Create the requested tasks/events (into the day's ledgerItems + CalDAV) and notes (Text Notes). */
    private fun executeCreates(actions: List<org.json.JSONObject>): Int {
        val ctx = requireContext()
        val root = documentsRoot()
        val locale = java.util.Locale.getDefault()
        var count = 0
        for (a in actions) {
            val kind = a.optString("kind").trim().lowercase()
            val text = a.optString("text").trim()
            if (text.isEmpty()) continue
            val date = runCatching { java.time.LocalDate.parse(a.optString("date")) }.getOrDefault(java.time.LocalDate.now())
            val time = a.optString("time").trim().ifBlank { null }
            when (kind) {
                "note" -> {
                    val title = a.optString("title").trim()
                    com.toolsboox.plugin.textnotes.TextNotesStore.addNote(ctx, date, title, text)
                    count++
                }
                "task", "event" -> {
                    val itemKind = if (kind == "event") com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT
                        else com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK
                    // Canonical item.date: the due DAY at 12:00 UTC — every other creation
                    // path and every reader (itemLocalDate, buildVTodo's UTC formatter) uses
                    // that convention. Building it in the DEVICE timezone at the clock time
                    // shifted evening items onto the wrong day. The clock time lives only in
                    // `time`, which the sync layers already apply in local time.
                    val item = com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                        id = "ask-${java.util.UUID.randomUUID()}", kind = itemKind, text = text,
                        date = java.util.Date(
                            date.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                        ),
                        time = time, source = "ask"
                    )
                    runCatching {
                        val day = calendarDayService.load(root, date, null, locale)
                        day.ledgerItems.add(item)
                        calendarDayService.save(root, date, day)
                    }
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(ctx, item)
                            com.toolsboox.plugin.calendar.nw.LedgerEventSync.pushEvent(ctx, item)
                        }
                    }
                    count++
                }
            }
        }
        return count
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
    }
}
