package com.toolsboox.plugin.michaelfilter.da

import com.squareup.moshi.JsonClass

/**
 * Typed (cut-and-paste) content of the day's MichaelFilter intake page.
 *
 * One JSON sidecar file per day (see IntakePageStore) — deliberately NOT part
 * of the CalendarDay model, so the shared Moshi calendar schema stays
 * untouched and there is zero risk of legacy field-name collisions. The ink
 * on the intake page lives in the normal noteStrokes["intake"] slot and rides
 * the existing mirrors; this sidecar is only the typed transport buffer.
 *
 * deliveredLinkUrls / deliveredEducateNote are dedup markers: what has
 * already been handed to the intake queue, so re-editing a panel does not
 * re-enqueue the same content.
 */
@JsonClass(generateAdapter = true)
data class IntakePageData(
    var readTyped: String = "",
    var watchTyped: String = "",
    var listenTyped: String = "",
    var educateTyped: String = "",
    var deliveredLinkUrls: MutableList<String> = mutableListOf(),
    var deliveredEducateNote: String = "",
    /**
     * Structured capture per panel — kind ("read"/"watch"/"listen"/"educate") → section
     * ("notes"/"quotes"/"image1"/"image2") → value (text, or a base64/data ref for images).
     * Additive to the flat *Typed fields above so existing link capture is untouched.
     */
    var sections: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
) {
    /** Read a structured section (empty if unset). */
    fun sectionFor(kindKey: String, section: String): String =
        sections[kindKey]?.get(section).orEmpty()

    /** Write a structured section, creating the panel bucket as needed. */
    fun setSectionFor(kindKey: String, section: String, value: String) {
        sections.getOrPut(kindKey) { mutableMapOf() }[section] = value
    }

    companion object {
        /** The capture sections every intake panel exposes, in display order. */
        val SECTIONS = listOf("notes", "quotes", "image1", "image2")
    }

    /**
     * Get the typed text of a panel by its kind key.
     */
    fun typedFor(kindKey: String): String = when (kindKey) {
        "read" -> readTyped
        "watch" -> watchTyped
        "listen" -> listenTyped
        "educate" -> educateTyped
        else -> ""
    }

    /**
     * Set the typed text of a panel by its kind key.
     */
    fun setTypedFor(kindKey: String, value: String) {
        when (kindKey) {
            "read" -> readTyped = value
            "watch" -> watchTyped = value
            "listen" -> listenTyped = value
            "educate" -> educateTyped = value
        }
    }
}
