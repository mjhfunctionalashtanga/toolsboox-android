package com.toolsboox.da

import com.squareup.moshi.JsonClass

/**
 * ONE TAPPABLE PLACE ON A PICK'S FACE (add-to-pick pin §3/§5) — what is drawn there and where:
 * [k] "photo" | "video" | "voice" | "ink", [a] the attachment id (on the highlight's Log line; a
 * clip also in its card day's `avGrams`), [r] [x, y, w, h] in per-mille (int 0–1000) of the STORED
 * face image — the element's own rect — so a tap maps the same on both apps whatever each bakes
 * around the face. Rewritten on every re-face. Wire shape shared with iOS `ImageElement.faceSlots`.
 */
@JsonClass(generateAdapter = true)
data class FaceSlot(val k: String, val a: String, val r: List<Int>) {
    /** True when the canvas point ([px], [py]) relative to the element's top-left, in an element
     *  [w] × [h] on the page, falls inside this slot. */
    fun hit(px: Float, py: Float, w: Float, h: Float): Boolean {
        if (r.size < 4 || w <= 0f || h <= 0f) return false
        val x = px * 1000f / w; val y = py * 1000f / h
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]
    }
}
