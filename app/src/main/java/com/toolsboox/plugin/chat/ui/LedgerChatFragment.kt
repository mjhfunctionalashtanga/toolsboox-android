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

    override val view = R.layout.fragment_ledger_chat

    private lateinit var binding: FragmentLedgerChatBinding

    private var asking = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerChatBinding.bind(view)

        val prefs = encryptedPrefs()
        binding.apiKeyEdit.setText(prefs.getString(KEY_API, "") ?: "")
        binding.modelEdit.setText(prefs.getString(KEY_MODEL, LedgerChatService.DEFAULT_MODEL))

        binding.settingsToggle.setOnClickListener {
            binding.settingsPanel.visibility =
                if (binding.settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        binding.saveKeyButton.setOnClickListener {
            val model = binding.modelEdit.text.toString().ifBlank { LedgerChatService.DEFAULT_MODEL }
            prefs.edit()
                .putString(KEY_API, binding.apiKeyEdit.text.toString().trim())
                .putString(KEY_MODEL, model)
                .apply()
            binding.modelEdit.setText(model)
            binding.settingsPanel.visibility = View.GONE
            showMessage(R.string.ledger_chat_key_saved)
        }
        binding.askButton.setOnClickListener { ask() }
    }

    private fun selectedScope(): Set<Section> {
        val s = mutableSetOf<Section>()
        if (binding.scopeBooks.isChecked) s += Section.BOOKS
        if (binding.scopeArticles.isChecked) s += Section.ARTICLES
        if (binding.scopePlanner.isChecked) s += Section.PLANNER
        if (binding.scopeMedia.isChecked) s += Section.MEDIA
        return s
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
        val apiKey = prefs.getString(KEY_API, "")?.trim().orEmpty()
        val model = prefs.getString(KEY_MODEL, LedgerChatService.DEFAULT_MODEL) ?: LedgerChatService.DEFAULT_MODEL

        asking = true
        binding.progress.visibility = View.VISIBLE
        binding.askButton.isEnabled = false
        binding.answerText.text = getString(R.string.ledger_chat_thinking)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val root = documentsRoot()
                val all = corpusService.gather(root, scope)
                val hits = corpusService.retrieve(all, question)
                val context = corpusService.buildContext(hits)
                val answer = chatService.ask(apiKey, model, question, context)
                Triple(answer, hits.size, all.size)
            }
            val (answer, hitCount, corpusCount) = result
            binding.progress.visibility = View.INVISIBLE
            binding.askButton.isEnabled = true
            asking = false
            binding.answerText.text = when (answer) {
                is LedgerChatService.Result.Ok ->
                    answer.answer + "\n\n" + getString(R.string.ledger_chat_footer, hitCount, corpusCount)
                is LedgerChatService.Result.Err -> "⚠️ " + answer.message
            }
        }
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
    }
}
