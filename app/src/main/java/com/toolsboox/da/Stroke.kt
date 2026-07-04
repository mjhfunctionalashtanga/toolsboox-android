package com.toolsboox.da

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.squareup.moshi.JsonClass
import java.lang.reflect.Type
import java.util.*

/**
 * Stroke data class.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
@JsonClass(generateAdapter = true)
data class Stroke(
    var strokeId: UUID,
    var timestamp: Long = 0L,
    var strokePoints: List<StrokePoint>,
    var color: Int = -16777216,
    var strokeWidth: Float = 3.0f,
    // NB: named `inkStyle`, NOT `style` — legacy/upstream data stores a STRING `style`
    // (e.g. "ballpoint"), which would crash Moshi if this int reused that key.
    var inkStyle: Int = STYLE_NORMAL
) {
    companion object {
        /** Uniform-width ink (the default; legacy strokes deserialize to this). */
        const val STYLE_NORMAL = 0

        /** Calligraphy: per-point pressure-variable width, baked to match the Onyx fountain nib. */
        const val STYLE_CALLIGRAPHY = 1

        /**
         * Deep copy of strokes.
         *
         * @param strokes the source list
         * @return the destination list
         */
        fun listDeepCopy(strokes: List<Stroke>): List<Stroke> {
            val listType: Type = object : TypeToken<List<Stroke>>() {}.type
            val gson = Gson()
            val json: String = gson.toJson(strokes, listType)
            return gson.fromJson(json, listType)
        }
    }
}