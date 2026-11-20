package se.gendut.klipp.engine

import android.graphics.RectF
import kotlin.math.abs

/**
 * "Fit to the safe area": the caption track's placement moved – and its lines made narrower when they are wider than
 * the free area, the text smaller when it still doesn't fit – so that every caption on it, as drawn (outline and
 * background plate included, a marked word at its largest), lies in a platform's clear area. The text and the times stay.
 */
object CaptionFit {
    enum class Problem {
        /** No captions on the track. */
        EMPTY,
        /** The blocks are taller than the free area. */
        TOO_TALL,
        /** Left or right aligned lines reach a side (only centered lines are made narrower). */
        TOO_WIDE,
    }

    /** The style values to set (null when nothing could be fitted), or why not. */
    class Result(val values: Map<String, Any>?, val problem: Problem?)

    /** Lines narrower than this aren't tried (a caption of a word or two per line reads badly). */
    private const val MIN_WRAP = 0.35f
    /** The smallest the text is made to fit, of its size. */
    private const val MIN_SIZE = 0.5f

    fun fit(texts: List<String>, st: CaptionStyle, w: Int, h: Int, z: SafeZones.Zones): Result {
        if (texts.none { it.isNotBlank() } || w <= 0 || h <= 0) return Result(null, Problem.EMPTY)
        val centered = st.align != "left" && st.align != "right"
        // the widest a centered line may be and stay off both sides (and off the button column, the narrower try)
        val half = minOf(0.5f - z.clear.left, z.clear.right - 0.5f)
        val tries = ArrayList<Float?>()
        if (!centered) tries += st.wrapWidth
        else {
            tries += minOf(st.wrapWidth ?: 0.9f, 2f * half - 0.01f)
            for (col in z.covered.drop(4)) if (col.left > 0.5f) (2f * minOf(half, col.left - 0.5f) - 0.01f).takeIf { it >= MIN_WRAP }?.let { tries += it }
        }
        // at the text's size first; when nothing fits (a long word reaches the sides, the block is taller than the room
        // above the button column), smaller in steps – down to [MIN_SIZE] of it
        var best: Pair<CaptionStyle, Float>? = null
        var wide = false
        var k = 1f
        while (best == null && k >= MIN_SIZE - 1e-3f) {
            for (ww in tries) {
                val s = st.copy(wrapWidth = ww, size = st.size * k)
                val blocks = blocks(texts, s, w, h)
                if (SafeZones.tooWide(z, blocks)) { wide = true; continue }
                val shift = SafeZones.verticalFit(z, blocks) ?: continue
                if (best == null || abs(shift) < abs(best.second) - 1e-4f) best = s to shift
            }
            k -= 0.05f
        }
        val (s, shift) = best ?: return Result(null, if (wide) Problem.TOO_WIDE else Problem.TOO_TALL)
        val out = HashMap<String, Any>()
        if (s.wrapWidth != st.wrapWidth) out["wrapWidth"] = s.wrapWidth ?: -1f
        if (s.size != st.size) out["size"] = s.size.toDouble()
        when {
            st.centerY != null -> out["centerY"] = st.centerY + shift
            st.anchor == "top" -> out["margin"] = (st.margin + shift).toDouble()
            st.anchor == "middle" -> out["centerY"] = 0.5f + shift
            else -> {
                val m = st.margin - shift
                if (m > 0.45f) {
                    // more than the margin allows: the block's center instead
                    val c = blocks(texts, s, w, h).let { b -> (b.minOf { it.top } + b.maxOf { it.bottom }) / 2f }
                    out["centerY"] = c + shift
                    out["placement"] = "under"
                } else out["margin"] = m.coerceAtLeast(0f).toDouble()
            }
        }
        return Result(out, null)
    }

    /** Each caption's block as drawn with [st], in fractions of the frame. */
    fun blocks(texts: List<String>, st: CaptionStyle, w: Int, h: Int): List<RectF> = texts.mapNotNull { t ->
        OverlayRenderer.captionBounds(t, st, w, h)?.let { r -> RectF(r.left / w, r.top / h, r.right / w, r.bottom / h) }
    }
}
