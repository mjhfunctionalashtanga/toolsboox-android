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
        binding.saveFeedButton.setOnClickListener { saveAnswerToFeed() }
        binding.historyButton.setOnClickListener { showHistoryDialog() }
        updatePersonaLabel()

        // Seeded query (e.g. from a lasso "Find in Ledger") → prefill and auto-ask once.
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
        if (binding.scopePlanner.isChecked) s += Section.PLANNER
        if (binding.scopeMedia.isChecked) s += Section.MEDIA
        // Your own captured text — OCR'd page sections, Text Notes, tasks & events — is always in
        // scope so the whole corpus is searchable regardless of the classic-section checkboxes.
        s += Section.SECTIONS; s += Section.NOTES; s += Section.TASKS
        return s
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
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Ask history")
            .setItems(labels) { _, which ->
                val t = turns[which]
                lastQuestion = t.question; lastAnswer = t.answer
                binding.questionEdit.setText(t.question)
                binding.answerText.text = t.answer
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
        val personas = com.toolsboox.plugin.chat.nw.PersonaStore.all(ctx)
        val names = personas.map { it.name }
        val labels = (listOf("🚫  None") + names).toTypedArray()
        val active = com.toolsboox.plugin.chat.nw.PersonaStore.activeName(ctx)
        val checked = if (active != null) (names.indexOf(active).takeIf { it >= 0 }?.plus(1) ?: 0) else 0
        androidx.appcompat.app.AlertDialog.Builder(ctx)
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

    private fun showPersonaEditor(existing: com.toolsboox.plugin.chat.nw.Persona?) {
        val ctx = requireContext()
        val nameEdit = android.widget.EditText(ctx).apply { hint = "Persona name"; setText(existing?.name ?: "") }
        val promptEdit = android.widget.EditText(ctx).apply {
            hint = "System prompt — the voice / role the AI should take on"
            setText(existing?.prompt ?: ""); minLines = 4
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0); addView(nameEdit); addView(promptEdit)
        }
        val b = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "New persona" else "Edit persona")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val n = nameEdit.text.toString().trim(); val p = promptEdit.text.toString().trim()
                if (n.isNotEmpty() && p.isNotEmpty()) {
                    com.toolsboox.plugin.chat.nw.PersonaStore.upsert(ctx, com.toolsboox.plugin.chat.nw.Persona(n, p))
                    com.toolsboox.plugin.chat.nw.PersonaStore.setActive(ctx, n); updatePersonaLabel()
                } else showMessage("Give the persona a name and a prompt.")
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) b.setNeutralButton("Delete") { _, _ ->
            com.toolsboox.plugin.chat.nw.PersonaStore.delete(ctx, existing.name); updatePersonaLabel()
        }
        b.show()
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

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val root = documentsRoot()
                val all = corpusService.gather(root, scope)
                val hits = corpusService.retrieveHybrid(all, question)
                val context = corpusService.buildContext(hits)
                val persona = com.toolsboox.plugin.chat.nw.PersonaStore.activePrompt(requireContext())
                val answer = chatService.ask(provider, apiKey, model, question, context, persona)
                Triple(answer, hits.size, all.size)
            }
            val (answer, hitCount, corpusCount) = result
            when (answer) {
                is LedgerChatService.Result.Ok -> {
                    // If the model asked to create task/event/note, run it and confirm.
                    val (clean, creates) = extractCreates(answer.answer)
                    var shown = clean
                    if (creates.isNotEmpty()) {
                        val n = withContext(Dispatchers.IO) { executeCreates(creates) }
                        if (n > 0) shown += "\n\n✓ Created $n item(s) in your Ledger."
                    }
                    lastQuestion = question; lastAnswer = shown
                    com.toolsboox.plugin.chat.nw.ChatHistoryStore.add(requireContext(), question, shown)
                    binding.answerText.text = shown + "\n\n" + getString(R.string.ledger_chat_footer, hitCount, corpusCount)
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
                    // Stamp the item with the requested date + time (not "now") so it schedules
                    // correctly and syncs the right due date/time to CalDAV.
                    val hm = time?.split(":")?.mapNotNull { it.trim().toIntOrNull() }
                    val cal = java.util.Calendar.getInstance().apply {
                        clear()
                        set(date.year, date.monthValue - 1, date.dayOfMonth, hm?.getOrNull(0) ?: 12, hm?.getOrNull(1) ?: 0, 0)
                    }
                    val item = com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                        id = "ask-${java.util.UUID.randomUUID()}", kind = itemKind, text = text,
                        date = cal.time, time = time, source = "ask"
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

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

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
