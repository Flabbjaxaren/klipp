package se.gendut.klipp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Geometry for the lasso and razor line in the timeline (pure functions – tested without UI). */
object TimelineTools {
    /** Whether the point is inside the polygon (even-odd rule). */
    fun inside(p: Offset, poly: List<Offset>): Boolean {
        var c = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    private fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun segmentsCross(p1: Offset, p2: Offset, q1: Offset, q2: Offset): Boolean {
        val d1 = cross(q1, q2, p1)
        val d2 = cross(q1, q2, p2)
        val d3 = cross(p1, p2, q1)
        val d4 = cross(p1, p2, q2)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
    }

    /** The rectangle and the closed lasso overlap (part of the clip lies inside, or the lasso crosses it). */
    fun overlaps(r: Rect, poly: List<Offset>): Boolean {
        if (poly.size < 3) return false
        if (poly.any { r.contains(it) }) return true
        val corners = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)
        if (corners.any { inside(it, poly) } || inside(r.center, poly)) return true
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            for (k in 0..3) if (segmentsCross(a, b, corners[k], corners[(k + 1) % 4])) return true
        }
        return false
    }

    /** The clips (id → rectangle) the lasso catches. */
    fun lassoHits(poly: List<Offset>, rects: Map<Long, Rect>): Set<Long> =
        if (poly.size < 3) emptySet() else rects.filter { (_, r) -> overlaps(r, poly) }.keys

    /** Clips the razor cuts at [at] on tracks [tracks] (not too close to an edge). */
    fun razorCuts(clips: List<Clip>, at: Float, tracks: IntRange, margin: Float = 0.04f): List<Clip> =
        clips.filter { it.track in tracks && at > it.start + margin && at < it.end - margin }
}
