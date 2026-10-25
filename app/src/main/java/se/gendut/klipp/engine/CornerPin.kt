package se.gendut.klipp.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * A title pinned by its corners: the text's box (see [TitleBounds]) is drawn into the quad [quad] – top left, top
 * right, bottom right, bottom left, as fractions of the frame (x, y down) – so it looks like it sits on a surface.
 * With [track] the four corners follow an object (each tracked on its own, so perspective changes are followed too).
 */
data class CornerPin(val quad: FloatArray, val track: PinTrack? = null) {
    fun toJson(): JSONObject = JSONObject().put("q", JSONArray().also { a -> quad.forEach { a.put(it.toDouble()) } })
        .apply { track?.let { put("track", it.toJson()) } }

    override fun equals(other: Any?) = other is CornerPin && other.quad.contentEquals(quad) && other.track == track
    override fun hashCode() = quad.contentHashCode() * 31 + (track?.hashCode() ?: 0)

    companion object {
        fun fromJson(o: JSONObject?): CornerPin? {
            val q = o?.optJSONArray("q")?.takeIf { it.length() == 8 } ?: return null
            return CornerPin(FloatArray(8) { q.optDouble(it).toFloat() }, PinTrack.fromJson(o.optJSONObject("track")))
        }

        /** A rectangle (fractions of the frame) as a quad. */
        fun of(l: Float, t: Float, r: Float, b: Float) = CornerPin(floatArrayOf(l, t, r, t, r, b, l, b))
    }
}

/**
 * The corners' tracking on clip [clip]: at timeline time [refT] the corners were at [corners] in the clip's own
 * picture (fractions, before stabilization and the clip's motion); each corner follows its window in [wins] (a small
 * tracked window just inside the corner) – the corner keeps its place relative to the window as it moves, grows and turns.
 */
data class PinTrack(val clip: Long, val refT: Double, val corners: FloatArray, val wins: List<Window>) {
    fun toJson(): JSONObject = JSONObject().put("clip", clip).put("t", refT)
        .put("c", JSONArray().also { a -> corners.forEach { a.put(it.toDouble()) } })
        .put("w", JSONArray().also { a -> wins.forEach { a.put(it.toJson()) } })

    override fun equals(other: Any?) = other is PinTrack && other.clip == clip && other.refT == refT && other.corners.contentEquals(corners) && other.wins == wins
    override fun hashCode() = (clip.hashCode() * 31 + refT.hashCode()) * 31 + corners.contentHashCode()

    companion object {
        fun fromJson(o: JSONObject?): PinTrack? {
            if (o == null) return null
            val c = o.optJSONArray("c")?.takeIf { it.length() == 8 } ?: return null
            val w = o.optJSONArray("w")?.takeIf { it.length() == 4 } ?: return null
            return PinTrack(o.optLong("clip"), o.optDouble("t"), FloatArray(8) { c.optDouble(it).toFloat() }, (0 until 4).map { Window.fromJson(w.optJSONObject(it)) })
        }
    }
}

object CornerPins {
    /**
     * The quad at timeline time [t] (fractions of the [w]×[h] frame): the pin's own without tracking; with tracking each
     * corner moves with its window in the clip's picture, then through the clip's stabilization and its own motion –
     * as the picture is built. Null = the tracked clip is gone. [clip] = the clip (id) at a timeline time: after a split
     * the piece that time falls in.
     */
    fun quadAt(pin: CornerPin, t: Double, w: Int, h: Int, clip: (Long, Double) -> ClipSnap?, grade: (Long) -> ClipGrade?): FloatArray? {
        val tr = pin.track ?: return pin.quad
        val c = clip(tr.clip, t) ?: return null
        val g = grade(tr.clip)
        val src = c.sourceAt(t); val ref = (clip(tr.clip, tr.refT) ?: c).sourceAt(tr.refT)
        val st = g?.stab?.transform(src, w.toFloat(), h.toFloat())
        val ct = c.at(t)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val win = tr.wins[i]
            val k = win.track
            var u = tr.corners[2 * i]; var v = tr.corners[2 * i + 1]
            if (k != null) {
                val a = k.at(ref); val b = k.at(src)
                // the corner relative to the window's center (pixels, so the turn is round), moved, scaled and turned with it
                val ax = (win.cx + a[0]) * w; val ay = (win.cy + a[1]) * h
                val bx = (win.cx + b[0]) * w; val by = (win.cy + b[1]) * h
                val s = if (a[2] > 1e-4f) b[2] / a[2] else 1f
                val th = (b[3] - a[3]).toDouble()
                val dx = u * w - ax; val dy = v * h - ay
                u = (bx + s * (dx * cos(th) - dy * sin(th)).toFloat()) / w
                v = (by + s * (dx * sin(th) + dy * cos(th)).toFloat()) / h
            }
            val p = st?.let { stab(it, u, v, w, h) } ?: floatArrayOf(u, v)
            val f = ct.toFrame(p[0], p[1], w, h)
            out[2 * i] = f[0]; out[2 * i + 1] = f[1]
        }
        return out
    }

    /** The perspective that draws the box [box] (pixels in the title's own image) into [quad] (fractions of a [w]×[h] frame), in frame pixels. */
    fun matrix(box: RectF, quad: FloatArray, w: Int, h: Int): Matrix = Matrix().apply {
        setPolyToPoly(floatArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom), 0,
            FloatArray(8) { quad[it] * if (it % 2 == 0) w else h }, 0, 4)
    }

    /**
     * Where the windows that track the corners go (clip picture fractions): a square just inside each corner, toward
     * the quad's middle, about a third of the way – mostly on the surface, so the corner has texture to hold on to.
     */
    fun windows(corners: FloatArray, w: Int, h: Int): List<Window> {
        val mx = (0 until 4).map { corners[2 * it] }.average().toFloat()
        val my = (0 until 4).map { corners[2 * it + 1] }.average().toFloat()
        return (0 until 4).map { i ->
            val x = corners[2 * i]; val y = corners[2 * i + 1]
            val cx = x + (mx - x) * 0.3f; val cy = y + (my - y) * 0.3f
            val d = kotlin.math.hypot((mx - x) * w, (my - y) * h)
            val r = (d * 0.3f).coerceIn(0.02f * h, 0.15f * h)
            Window(shape = 2, cx = cx, cy = cy, rx = r / w, ry = r / h, soft = 0f)
        }
    }
}

/** The text's own box in a title's image: what is drawn (text, plate), without the shadow – measured from the drawing. */
object TitleBounds {
    private val cache = object : LinkedHashMap<String, RectF>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RectF>?) = size > 32
    }

    fun of(t: TextSnap, w: Int, h: Int): RectF {
        val look = t.look
        val key = "${t.text}|${t.size}|${t.x}|${t.y}|${look.copy(shadow = TitleShadow.OFF, anim = TitleAnim.NONE).encode()}|$w|$h"
        synchronized(cache) { cache[key] }?.let { return RectF(it) }
        val plain = t.copy(name = look.copy(shadow = TitleShadow.OFF).encode())
        val r = measure(TitleArt.draw(plain, w, h)) ?: RectF(0f, 0f, w.toFloat(), h.toFloat())
        synchronized(cache) { cache[key] = RectF(r) }
        return r
    }

    /** The smallest rectangle around the pixels that are not transparent (null = nothing drawn). */
    fun measure(b: Bitmap): RectF? {
        val w = b.width; val h = b.height
        val row = IntArray(w)
        var top = -1; var bottom = -1; var left = w; var right = -1
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var any = false
            for (x in 0 until w) if ((row[x] ushr 24) > 8) { any = true; if (x < left) left = x; if (x > right) right = x }
            if (any) { if (top < 0) top = y; bottom = y }
        }
        if (top < 0) return null
        return RectF(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }
}

/**
 * A pinned title's image at the resolution its quad needs: the text's box (with room for the shadow) drawn as many
 * times larger as the quad is bigger than the box (at most [MAX_K], and at most [MAX_SIDE] pixels a side), so text
 * pinned large stays sharp in the preview and the export. [box] = the text's box in the bitmap's pixels.
 */
object PinArt {
    const val MAX_K = 6f
    const val MAX_SIDE = 4096

    class Art(val bitmap: Bitmap, val box: RectF)

    private val cache = object : LinkedHashMap<String, Art>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Art>?) = size > 6
    }

    /** How much larger the quad (fractions of a [w]×[h] frame) is than [box]: the longest of its edges against the box's. */
    fun magnification(box: RectF, quad: FloatArray, w: Int, h: Int): Float {
        fun len(i: Int, j: Int) = kotlin.math.hypot((quad[2 * i] - quad[2 * j]) * w, (quad[2 * i + 1] - quad[2 * j + 1]) * h)
        val kx = maxOf(len(0, 1), len(3, 2)) / box.width().coerceAtLeast(1f)
        val ky = maxOf(len(0, 3), len(1, 2)) / box.height().coerceAtLeast(1f)
        return maxOf(kx, ky)
    }

    fun of(t: TextSnap, w: Int, h: Int, quad: FloatArray): Art {
        val box = TitleBounds.of(t, w, h)
        // in half steps, so the image isn't redrawn for every small change in size
        var k = (kotlin.math.ceil(magnification(box, quad, w, h) * 2f) / 2f).coerceIn(1f, MAX_K)
        val m = t.size.toFloat() * 0.6f
        val crop = RectF(box.left - m, box.top - m, box.right + m, box.bottom + m)
        k = minOf(k, MAX_SIDE / crop.width(), MAX_SIDE / crop.height()).coerceAtLeast(1f)
        val key = "${t.hashCode()}|${t.text}|$w|$h|$k"
        synchronized(cache) { cache[key] }?.let { return it }
        val art = Art(TitleArt.draw(t, w, h, k, crop), RectF((box.left - crop.left) * k, (box.top - crop.top) * k, (box.right - crop.left) * k, (box.bottom - crop.top) * k))
        synchronized(cache) { cache[key] = art }
        return art
    }
}
