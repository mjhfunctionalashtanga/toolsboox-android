package com.toolsboox.plugin.michaelfilter.nw

import com.toolsboox.plugin.michaelfilter.da.IntakeSubmission
import com.toolsboox.plugin.michaelfilter.nw.MichaelFilterIntakeClient.SubmitResult

/**
 * One pass over the MichaelFilter queue, with no Android in it — [IntakeQueueWorker] supplies the
 * files and the HTTP call, the unit tests supply lists and lambdas, and both run THIS loop.
 *
 * The rules it pins:
 *  - No key: nothing is sent and nothing is removed. Items wait for a key.
 *  - A refused KEY (a 401, or WordPress's own 403) stops the pass at once and removes nothing —
 *    the next item would get the same answer, and "retry it forever" was the old silent failure.
 *    Everything queued is re-sent once a key is saved.
 *  - A refused ITEM (only the codes [MichaelFilterIntakeClient.classify] calls the item's fault:
 *    400/409/410/413/415/422, or an explicit `"ok": false`) is dropped: that payload will never be
 *    accepted. A 404, 408, 429, firewall page or unreadable 2xx is NOT that — it is kept.
 *  - Accepted and duplicate items are removed; network trouble keeps the item and asks for a retry.
 */
object IntakeDrain {

    enum class Outcome {
        /** Everything that could be sent was; nothing left that a retry would help. */
        DONE,

        /** At least one item met network trouble and is still queued. */
        RETRY_LATER,

        /** No key saved — nothing was sent, everything is still queued. */
        NOT_CONNECTED,

        /** The server turned the key down — the pass stopped, everything unsent is still queued. */
        KEY_TURNED_DOWN
    }

    /**
     * @param key the saved MichaelFilter key ("" when not connected)
     * @param pending the queued items, oldest first
     * @param load reads one item; null means it is unreadable (dropped so it can't wedge the queue)
     * @param submit sends one item with the key
     * @param remove takes one item off the queue
     */
    fun <T> run(
        key: String,
        pending: List<T>,
        load: (T) -> IntakeSubmission?,
        submit: (IntakeSubmission, String) -> SubmitResult,
        remove: (T) -> Unit
    ): Outcome {
        if (pending.isEmpty()) return Outcome.DONE
        if (key.isBlank()) return Outcome.NOT_CONNECTED

        var networkFailures = 0
        for (item in pending) {
            val submission = load(item)
            if (submission == null) {
                remove(item)
                continue
            }
            when (submit(submission, key)) {
                is SubmitResult.Saved, is SubmitResult.Duplicate -> remove(item)
                is SubmitResult.Rejected -> remove(item)
                is SubmitResult.KeyRejected -> return Outcome.KEY_TURNED_DOWN
                is SubmitResult.NotConnected -> return Outcome.NOT_CONNECTED
                is SubmitResult.NetworkFailure -> networkFailures++
            }
        }
        return if (networkFailures > 0) Outcome.RETRY_LATER else Outcome.DONE
    }
}
