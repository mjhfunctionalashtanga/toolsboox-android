package com.toolsboox.ui.plugin

import android.content.Context

/**
 * The model used for handwriting OCR (the lasso "Create task/event/gram/Copy text" flows). OCR
 * reuses the Ask-my-Ledger key, but you can pick a different model here — e.g. Haiku for fast,
 * cheap recognition, or Sonnet for the hardest handwriting. A model name is not sensitive, so this
 * lives in plain prefs (the API key stays in the encrypted store).
 */
object OcrModel {
    private const val PREFS = "ledger_ocr"
    private const val KEY = "model"

    /** Anthropic choices offered in the picker (label → model id). "" = use the chat model. */
    val CHOICES: List<Pair<String, String>> = listOf(
        "Use chat model (default)" to "",
        "Haiku 4.5 — fast" to "claude-haiku-4-5-20251001",
        "Sonnet 5 — accurate" to "claude-sonnet-5"
    )

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString(KEY, "").orEmpty()

    fun set(context: Context, model: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, model).apply()
    }

    /** The override to apply for [provider], or null to fall back to the chat model. Only applies to
     *  the Anthropic family (Haiku/Sonnet); OpenAI keeps its configured chat model. */
    fun override(context: Context, provider: String): String? {
        if (provider != "anthropic") return null
        return current(context).takeIf { it.isNotBlank() }
    }

    /** Show the model chooser. Self-contained so it can be hung off the Settings menu. */
    fun showPicker(context: Context) {
        val labels = CHOICES.map { it.first }.toTypedArray()
        val curModel = current(context)
        val checked = CHOICES.indexOfFirst { it.second == curModel }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setTitle("OCR model")
            .setSingleChoiceItems(labels, checked) { d, which ->
                set(context, CHOICES[which].second)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
