package com.toolsboox.ot

import android.graphics.PointF
import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.StrokePoint
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton clipboard for cut/copy/paste of pen strokes.
 *
 * @author toolsboox
 */
@Singleton
class StrokeClipboard @Inject constructor() {

    /**
     * The copied strokes (deep-copied, in original coordinates).
     */
    var strokes: List<Stroke> = emptyList()
        private set

    /**
     * The top-left origin of the bounding box of the copied strokes.
     */
    var originX: Float = 0f
        private set
    var originY: Float = 0f
        private set

    /**
     * The copied image (deep copy), or null when the clipboard holds strokes / is empty.
     * Unified clipboard: copying an image clears strokes and vice versa — last grab wins.
     */
    var image: ImageElement? = null
        private set

    /** True when the clipboard holds an image. */
    val hasImage: Boolean get() = image != null

    /**
     * True when the clipboard holds at least one stroke or an image.
     */
    val hasContent: Boolean get() = strokes.isNotEmpty() || image != null

    /**
     * Deep-copy the given strokes into the clipboard and compute the bounding-box origin.
     *
     * @param selectedStrokes the strokes to copy
     */
    fun copy(selectedStrokes: List<Stroke>) {
        if (selectedStrokes.isEmpty()) return

        image = null
        strokes = Stroke.listDeepCopy(selectedStrokes)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        for (stroke in strokes) {
            for (pt in stroke.strokePoints) {
                if (pt.x < minX) minX = pt.x
                if (pt.y < minY) minY = pt.y
            }
        }
        originX = minX
        originY = minY
    }

    /**
     * Clear the clipboard.
     */
    fun clear() {
        strokes = emptyList()
        originX = 0f
        originY = 0f
        image = null
    }

    /**
     * Create a paste-ready copy of the clipboard strokes, offset so that the bounding-box
     * top-left lands at (targetX, targetY). Each stroke receives a fresh UUID.
     *
     * @param targetX the X coordinate for the top-left of the pasted group
     * @param targetY the Y coordinate for the top-left of the pasted group
     * @return new strokes positioned at the target, with new UUIDs
     */
    fun stampAt(targetX: Float, targetY: Float): List<Stroke> {
        val dx = targetX - originX
        val dy = targetY - originY
        val timestamp = System.currentTimeMillis()

        return Stroke.listDeepCopy(strokes).map { stroke ->
            val movedPoints = stroke.strokePoints.map { pt ->
                StrokePoint(pt.x + dx, pt.y + dy, pt.p, pt.t)
            }
            Stroke(UUID.randomUUID(), timestamp, movedPoints)
        }
    }

    /** Copy a single image into the clipboard (clears strokes — unified, last grab wins). */
    fun copyImage(element: ImageElement) {
        strokes = emptyList()
        image = element.copy()
    }

    /** A paste-ready copy of the clipboard image with a fresh id, top-left at (targetX, targetY). */
    fun stampImageAt(targetX: Float, targetY: Float): ImageElement? {
        val img = image ?: return null
        return img.copy(
            elementId = UUID.randomUUID(),
            timestamp = System.currentTimeMillis(),
            x = targetX,
            y = targetY
        )
    }

    companion object {
        /**
         * Ray-casting point-in-polygon test.
         *
         * @param point  the point to test
         * @param polygon the vertices of a closed polygon
         * @return true if point is inside
         */
        fun isPointInPolygon(point: PointF, polygon: List<PointF>): Boolean {
            var inside = false
            var j = polygon.size - 1
            for (i in polygon.indices) {
                if ((polygon[i].y > point.y) != (polygon[j].y > point.y) &&
                    point.x < (polygon[j].x - polygon[i].x) * (point.y - polygon[i].y) /
                    (polygon[j].y - polygon[i].y) + polygon[i].x
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        /**
         * Test whether an entire stroke lies inside the polygon (strict — all points must be inside).
         *
         * @param stroke  the stroke to test
         * @param polygon the lasso polygon vertices
         * @return true if every point of the stroke is inside the polygon
         */
        fun isStrokeInsidePolygon(stroke: Stroke, polygon: List<PointF>): Boolean {
            if (polygon.size < 3) return false
            return stroke.strokePoints.any { pt ->
                isPointInPolygon(PointF(pt.x, pt.y), polygon)
            }
        }
    }
}
