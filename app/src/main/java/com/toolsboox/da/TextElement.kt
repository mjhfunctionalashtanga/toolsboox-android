package com.toolsboox.da

import com.squareup.moshi.JsonClass
import java.util.UUID

/**
 * Text element data class for on-canvas text annotations.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
@JsonClass(generateAdapter = true)
data class TextElement(
    var elementId: UUID = UUID.randomUUID(),
    var timestamp: Long = System.currentTimeMillis(),
    var x: Float,
    var y: Float,
    var width: Float = 300f,
    var height: Float = 60f,
    var text: String,
    var fontFamily: String = "atkinson_hyperlegible",
    var fontSize: Float = 24f,
    var color: Int = -16777216,  // Color.BLACK as ARGB int
    // Note page this text box belongs to ("default" = the plain day page).
    // Fresh field name (not "page") to avoid any legacy Moshi collisions.
    var pageKey: String = "default",
    // Original shared URL (share-to-Ledger): the on-canvas text soft-wraps long
    // URLs across lines, so the intact link rides here for drop-to-file on the
    // intake panels. New field with a default → backward-compatible JSON.
    var sourceUrl: String? = null,
    // Contact this text box / picking is linked to (null = none) — bidirectional CRM linking, so the
    // contact's page can surface it. New field with a default → backward-compatible. Mirrors iOS.
    var contactId: String? = null
)
