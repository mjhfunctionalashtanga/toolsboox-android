package com.toolsboox.plugin.calendar.ot

/**
 * EVERY WORD OF "＋ ADD TO THIS PICK" — the add-to-pick pin's §1 and §7, byte for byte the iOS
 * twin's (LedgerCore `AddToPickWords`), held to add-to-pick.golden. Pure: the golden prints these.
 * Michael 2026-10-11: "Keep parity with features and names between Android and Apple."
 */
object AddToPickWords {
    // ── §1 ──
    /** The Pick sheet's first row, the ladder's title, the hold menu's and the Log's row. */
    const val ADD = "＋  Add to this pick"
    const val CHIP_INK = "✍"
    const val CHIP_PHOTO = "📷"
    fun chipVideo(clock: String) = "🎥 $clock"
    fun chipVoice(clock: String) = "🎤 $clock"

    /** The Highlight after-step (4 s) and its action. */
    const val HIGHLIGHTED = "❝ Highlighted — its pick is in All Picks"
    const val ADD_ACTION = "＋ Add"
    const val AFTER_STEP_MS = 4000

    // The ladder — the canon chooser's order with the extras taken out.
    const val HANDWRITE = "✍  Handwrite a note"
    const val VOICE = "🎤  Record a voice note"
    const val TAKE_PHOTO = "📷  Take a photo"
    const val TYPE = "⌨  Type a note"
    const val CHOOSE_PICTURE = "🖼  Choose a picture"
    const val VIDEO = "🎥  Video"
    val LADDER = listOf(HANDWRITE, VOICE, TAKE_PHOTO, TYPE, CHOOSE_PICTURE, VIDEO)

    /** Above row 1 when the pick already has things (the chooser's own open rows). */
    fun openNote(preview: String) = "🗒  “$preview” — open"
    const val OPEN_INK = "✍  The handwriting — open"
    /** Below row 6. */
    const val REMOVE = "🗑  Remove from this pick…"

    // The hold menu on a pick.
    const val PLAY_VIDEO = "▶  Play video note"
    const val PLAY_VOICE = "🎤  Play voice note"

    /** Android's article chooser regains the studio door (after 🔖, before 🔎). */
    const val STUDIO = "✏️  Make pick in the studio…"
    const val SAVE_HIGHLIGHT = "❝  Save highlight"

    // ── §7 — never a silent nothing ──
    const val CAMERA_OFF_PHOTO = "Camera is off for Ledgable. Turn it on in Settings to take a photo."
    const val CAMERA_OFF_VIDEO = "Camera is off for Ledgable. Turn it on in Settings to record a video."
    const val OPEN_SETTINGS = "Open Settings"
    const val NO_CAMERA = "No camera on this device — choose a picture instead."
    const val MIC_OFF = "Microphone is off for Ledgable. Turn it on in Settings to record."
    const val CANCEL = "Cancel"
    fun recordingStopped(clock: String) = "Recording stopped at $clock — kept."
    const val VIDEO_CAPPED = "Video kept · 3:00 — the most a pick holds."
    /** The longest video a pick holds (seconds). */
    const val VIDEO_MAX_S = 180
    fun videoNoPoster(clock: String) = "Video · $clock"
    const val OUT_OF_SPACE = "Couldn't save that — your device is out of space. Nothing else changed."
    const val NOT_HERE_YET = "Still on its way from your other device — try again in a minute."
    const val REMOVED_ELSEWHERE = "That highlight was removed on your other device — what you added is on its day's Log."
    /** A highlight's Log line hasn't synced to this device yet: the add is KEPT and joins the line
     *  when its day arrives (review 2026-10-16 #16 — the take used to be dropped, its file orphaned). */
    const val LINE_NOT_HERE = "This highlight's day hasn't reached this device yet — what you added joins it once it syncs."
    /** The same, for a removal (nothing to keep — it can be done once the day is here). */
    const val LINE_NOT_HERE_REMOVE = "This highlight's day hasn't reached this device yet — try again once it syncs."
    /** An add a day that won't read refused (the day-file guard — [refusal] is the shared table's
     *  line, "Couldn't change the line — the Oct 3 page won't read"): the add is KEPT and lands once
     *  the page reads (merge-addpick review #2 — it was said refused and then held by nothing). The
     *  Synth's kept reply says it the same way ("The reply is kept; it lands once the page reads."). */
    fun refusedKept(refusal: String) = "$refusal. What you added is kept; it lands once the page reads."

    /** The golden's words section: every string of §1 and §7, in the pin's order, once each. */
    fun golden(): List<String> = listOf(
        ADD, CHIP_INK, CHIP_PHOTO, chipVideo("0:41"), chipVoice("0:23"),
        HIGHLIGHTED, ADD_ACTION,
        HANDWRITE, VOICE, TAKE_PHOTO, TYPE, CHOOSE_PICTURE, VIDEO,
        openNote("…"), OPEN_INK, REMOVE,
        PLAY_VIDEO, PLAY_VOICE, STUDIO, SAVE_HIGHLIGHT,
        CAMERA_OFF_PHOTO, OPEN_SETTINGS, CAMERA_OFF_VIDEO, NO_CAMERA, MIC_OFF, CANCEL,
        recordingStopped("0:12"), VIDEO_CAPPED, videoNoPoster("m:ss"),
        OUT_OF_SPACE, NOT_HERE_YET, REMOVED_ELSEWHERE,
    ).distinct()

    /** The glyph summary an Annotations row wears for what rides on its line (✍ 📷 🎥 🎤). */
    fun summary(ink: Int, photos: Int, videos: Int, voices: Int): String =
        listOfNotNull(
            CHIP_INK.takeIf { ink > 0 }, CHIP_PHOTO.takeIf { photos > 0 },
            "🎥".takeIf { videos > 0 }, "🎤".takeIf { voices > 0 }
        ).joinToString(" ")
}
