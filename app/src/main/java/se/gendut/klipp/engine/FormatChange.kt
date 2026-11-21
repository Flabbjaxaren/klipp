package se.gendut.klipp.engine

/**
 * Clip positions when the sequence's frame size changes. The engine keeps a clip's position in sequence pixels and
 * writes the frame's center there when a clip is placed, so a clip placed in 1920×1080 sits at (960, 540) – in a
 * 1080×1920 frame that is 420 px right of and 420 px above the center, and a portrait clip that filled the new frame
 * ended up half outside it. Each position is moved to the same place relative to the frame (the center stays the
 * center); scale is left alone, since 100 % already means "fitted to the frame" whatever its shape.
 */
object FormatChange {
    /** A static position to write, or the keyframes ([KfKeys.POSITION], by media time) to rewrite. */
    data class Move(val clip: Long, val x: Double, val y: Double, val keys: List<Pair<Long, List<Double>>> = emptyList())

    fun moves(seq: SequenceSnap, w: Int, h: Int): List<Move> {
        if (seq.width <= 0 || seq.height <= 0 || (seq.width == w && seq.height == h)) return emptyList()
        val kx = w.toDouble() / seq.width
        val ky = h.toDouble() / seq.height
        return seq.video.flatMap { it.clips }.mapNotNull { c ->
            val keys = c.kf[KfKeys.POSITION].orEmpty()
            if (keys.isNotEmpty()) {
                Move(c.id, (c.x ?: seq.width / 2.0) * kx, (c.y ?: seq.height / 2.0) * ky,
                    keys.filter { it.v.size >= 2 }.map { k -> k.mt to listOf(k.v[0] * kx, k.v[1] * ky) })
            } else if (c.x != null || c.y != null) {
                Move(c.id, (c.x ?: seq.width / 2.0) * kx, (c.y ?: seq.height / 2.0) * ky)
            } else null
        }
    }
}
