package com.toolsboox.plugin.michaelfilter.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.toolsboox.R
import com.toolsboox.databinding.FragmentMichaelfilterIntakeBinding
import com.toolsboox.plugin.michaelfilter.da.IntakeSubmission
import com.toolsboox.plugin.michaelfilter.nw.IntakeQueue
import com.toolsboox.plugin.michaelfilter.nw.MichaelFilterIntakeClient
import com.toolsboox.plugin.michaelfilter.ot.ShareTextParser
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * MichaelFilter Intake — save a link (plus pasted text and a "why") into the
 * mjh.yoga reading pipeline for tomorrow morning's edition.
 *
 * Typed-first surface: URL with a clipboard Paste button, Read/Listen/Watch
 * selector, optional title, pasted body text and "Why I saved this". Reached
 * from the dashboard or via the system share sheet (ACTION_SEND text/plain),
 * which pre-fills the fields through navigation arguments.
 */
@AndroidEntryPoint
class MichaelFilterIntakeFragment @Inject constructor() : ScreenFragment() {

    companion object {
        const val ARG_URL = "url"
        const val ARG_TITLE = "title"
        const val ARG_KIND = "kind"
        const val ARG_TEXT = "text"
    }

    /**
     * The inflated layout.
     */
    override val view = R.layout.fragment_michaelfilter_intake

    /**
     * The view binding.
     */
    private lateinit var binding: FragmentMichaelfilterIntakeBinding

    /**
     * True while a submit is in flight.
     */
    private var submitting: Boolean = false

    /**
     * OnViewCreated hook.
     *
     * @param view the parent view
     * @param savedInstanceState the saved instance state
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = FragmentMichaelfilterIntakeBinding.bind(view)

        // Pre-fill from share-target navigation arguments (only on first creation,
        // so a config change doesn't stomp user edits).
        if (savedInstanceState == null) {
            arguments?.getString(ARG_URL)?.let { binding.urlEdit.setText(it) }
            arguments?.getString(ARG_TITLE)?.let { binding.titleEdit.setText(it) }
            arguments?.getString(ARG_TEXT)?.let { binding.textEdit.setText(it) }
            arguments?.getString(ARG_KIND)?.let { checkKind(it) }
        }

        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.saveButton.setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()

        toolbar.root.title = getString(
            R.string.drawer_title,
            getString(R.string.app_name),
            getString(R.string.michaelfilter_intake_title)
        )

        updatePendingLabel()

        // Opportunistically drain anything left over from offline saves.
        if (IntakeQueue.pendingFiles(requireContext()).isNotEmpty()) {
            IntakeQueue.scheduleDrain(requireContext())
        }
    }

    /**
     * Read the clipboard into the URL field; infer kind and fall back to the
     * pasted-text field when the clipboard is not a URL.
     */
    private fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()?.trim()

        if (clipText.isNullOrEmpty()) {
            showMessage(R.string.michaelfilter_intake_clipboard_empty, binding.root)
            return
        }

        val parsed = ShareTextParser.parse(clipText, null)
        if (parsed.url != null) {
            binding.urlEdit.setText(parsed.url)
            checkKind(parsed.kind)
            if (binding.titleEdit.text.isNullOrBlank() && parsed.title != null) {
                binding.titleEdit.setText(parsed.title)
            }
            if (binding.textEdit.text.isNullOrBlank() && parsed.leftoverText != null &&
                parsed.leftoverText != parsed.title
            ) {
                binding.textEdit.setText(parsed.leftoverText)
            }
        } else {
            // No URL in the clipboard — treat it as pasted body text.
            binding.textEdit.setText(clipText)
            showMessage(R.string.michaelfilter_intake_clipboard_no_url, binding.root)
        }
    }

    /**
     * Validate, queue and submit the intake form.
     */
    private fun save() {
        if (submitting) return

        val url = binding.urlEdit.text?.toString()?.trim().orEmpty()
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            binding.statusText.text = getString(R.string.michaelfilter_intake_status_url_required)
            return
        }

        val submission = IntakeSubmission(
            linkUrl = url,
            linkKind = selectedKind(),
            linkTitle = binding.titleEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            pastedBody = binding.textEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            whyNote = binding.whyEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        )

        submitting = true
        binding.saveButton.isEnabled = false
        binding.statusText.text = getString(R.string.michaelfilter_intake_status_sending)
        showLoading()

        val appContext = requireContext().applicationContext

        lifecycleScope.launch {
            // Persist first so nothing is lost if the process dies mid-flight.
            val queueFile = withContext(Dispatchers.IO) { IntakeQueue.enqueue(appContext, submission) }

            val result = withContext(Dispatchers.IO) { MichaelFilterIntakeClient.submit(submission) }

            when (result) {
                is MichaelFilterIntakeClient.SubmitResult.Saved -> {
                    withContext(Dispatchers.IO) { queueFile?.delete() }
                    runOnActivity {
                        binding.statusText.text = getString(R.string.michaelfilter_intake_status_saved)
                        clearForm()
                    }
                }

                is MichaelFilterIntakeClient.SubmitResult.Duplicate -> {
                    withContext(Dispatchers.IO) { queueFile?.delete() }
                    runOnActivity {
                        binding.statusText.text = getString(R.string.michaelfilter_intake_status_dup)
                        clearForm()
                    }
                }

                is MichaelFilterIntakeClient.SubmitResult.Rejected -> {
                    withContext(Dispatchers.IO) { queueFile?.delete() }
                    runOnActivity {
                        binding.statusText.text =
                            getString(R.string.michaelfilter_intake_status_rejected, result.httpCode)
                    }
                }

                is MichaelFilterIntakeClient.SubmitResult.NetworkFailure -> {
                    // Leave it in the queue; WorkManager retries when online.
                    IntakeQueue.scheduleDrain(appContext)
                    Timber.i("Intake queued offline: ${submission.linkUrl}")
                    runOnActivity {
                        binding.statusText.text = getString(R.string.michaelfilter_intake_status_queued)
                        clearForm()
                    }
                }
            }

            runOnActivity {
                submitting = false
                binding.saveButton.isEnabled = true
                hideLoading()
                updatePendingLabel()
            }
        }
    }

    /**
     * Clear the form after a successful (or queued) save.
     */
    private fun clearForm() {
        binding.urlEdit.setText("")
        binding.titleEdit.setText("")
        binding.textEdit.setText("")
        binding.whyEdit.setText("")
        binding.kindRead.isChecked = true
    }

    /**
     * The currently selected kind value.
     */
    private fun selectedKind(): String = when (binding.kindGroup.checkedRadioButtonId) {
        R.id.kind_listen -> "listen"
        R.id.kind_watch -> "watch"
        else -> "read"
    }

    /**
     * Check the radio button matching a kind value.
     */
    private fun checkKind(kind: String) {
        when (kind) {
            "listen" -> binding.kindListen.isChecked = true
            "watch" -> binding.kindWatch.isChecked = true
            else -> binding.kindRead.isChecked = true
        }
    }

    /**
     * Show/refresh the queued-for-retry label.
     */
    private fun updatePendingLabel() {
        val pending = IntakeQueue.pendingFiles(requireContext()).size
        if (pending > 0) {
            binding.pendingText.text = getString(R.string.michaelfilter_intake_pending, pending)
            binding.pendingText.visibility = View.VISIBLE
        } else {
            binding.pendingText.visibility = View.GONE
        }
    }

    /**
     * Show the progress bar.
     */
    override fun showLoading() {
        binding.mainProgress.visibility = View.VISIBLE
    }

    /**
     * Hide the progress bar.
     */
    override fun hideLoading() {
        binding.mainProgress.visibility = View.INVISIBLE
    }
}
