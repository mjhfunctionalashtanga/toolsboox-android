package com.toolsboox.plugin.michaelfilter.da

import com.squareup.moshi.JsonClass

/**
 * A single MichaelFilter intake submission, persisted to the on-disk queue
 * until it has been accepted by the mjh.yoga intake endpoint.
 *
 * Field names are deliberately fresh (no reuse of legacy model names like
 * "style"/"title"-on-Stroke etc.) to avoid Moshi adapter collisions.
 */
@JsonClass(generateAdapter = true)
data class IntakeSubmission(
    val linkUrl: String,
    val linkKind: String,
    val linkTitle: String? = null,
    val pastedBody: String? = null,
    val whyNote: String? = null,
    val queuedAtMs: Long = System.currentTimeMillis()
)
