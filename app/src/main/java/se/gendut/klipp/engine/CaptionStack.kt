package se.gendut.klipp.engine

import android.graphics.RectF

/**
 * Captions of several visible tracks at the same time and place don't land on each other: a caption that would cover
 * one of an earlier track (in the tracks' order) at the same time moves away from its edge – up from the bottom or the
 * middle, down from the top – just clear of it. Nothing in the project changes; preview, playback and export draw the
 * same. One shift per caption (it doesn't jump while it is shown). A fraction of the frame's height.
 */
object CaptionStack {
    private const val GAP = 0.01f

    fun shifts(captions: List<CaptionSnap>, tracks: List<CaptionTrackSnap>, w: Int, h: Int): Map<Long, Float> {
        if (tracks.count { it.enabled } < 2 || w <= 0 || h <= 0) return emptyMap()
        val order = tracks.withIndex().associate { (i, t) -> t.id to i }
        val styles = tracks.associate { it.id to it.style }
        val placed = ArrayList<Triple<CaptionSnap, RectF, Int>>()
        val out = HashMap<Long, Float>()
        for (c in captions.filter { it.text.isNotBlank() && it.track in order }.sortedWith(compareBy({ order[it.track] }, { it.start }))) {
            val st = styles[c.track] ?: continue
            val r0 = OverlayRenderer.captionBounds(c.text, st, w, h) ?: continue
            val r = RectF(r0.left / w, r0.top / h, r0.right / w, r0.bottom / h)
            val down = st.centerY == null && st.anchor == "top"
            var shift = 0f
            // move past every earlier track's caption it meets in time and space (again after each move)
            var moved = true
            var guard = 0
            while (moved && guard++ < 16) {
                moved = false
                for ((o, b, ti) in placed) {
                    if (ti == order[c.track] || o.end <= c.start + 1e-4 || o.start >= c.end - 1e-4) continue
                    val top = r.top + shift
                    val bottom = r.bottom + shift
                    if (bottom <= b.top || top >= b.bottom || r.right <= b.left || r.left >= b.right) continue
                    shift = if (down) b.bottom + GAP - r.top else b.top - GAP - r.bottom
                    moved = true
                }
            }
            if (shift != 0f) out[c.id] = shift
            placed += Triple(c, RectF(r.left, r.top + shift, r.right, r.bottom + shift), order[c.track] ?: 0)
        }
        return out
    }
}
