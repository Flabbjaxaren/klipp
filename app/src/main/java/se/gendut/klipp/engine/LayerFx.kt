package se.gendut.klipp.engine

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import org.json.JSONObject
import se.gendut.klipp.R
import se.gendut.klipp.Str
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The clip as a layer on top of others: green screen ([key]), mask ([mask]) and blend mode ([blend]). The key and the mask
 * produce transparency in the clip's own image (after the grade, before fitting to the sequence – the mask follows the
 * clip's motion; with keyframes also the object in the frame); the layer below shows through. Saved with the clip's grade
 * (undoable, carried along on split).
 */
data class LayerFx(
    val key: ChromaKey? = null,
    val mask: LayerMask? = null,
    val blend: BlendMode = BlendMode.NORMAL,
) {
    val isNone get() = key == null && mask == null && blend == BlendMode.NORMAL
    /** The key or the mask (a separate shader pass). */
    val hasAlpha get() = key != null || mask != null

    fun toJson(): JSONObject = JSONObject().apply {
        key?.let { put("key", it.toJson()) }
        mask?.let { put("mask", it.toJson()) }
        if (blend != BlendMode.NORMAL) put("blend", blend.name)
    }

    /**
     * Opacity (0..1) and the color after spill suppression for a pixel (color 0..1, position 0..1 with y down,
     * aspect ratio [aspect]) – same math as the shader ([LayerFxEffect]).
     */
    fun apply(rgb: FloatArray, x: Float, y: Float, aspect: Float, src: Double? = null): Float {
        var a = 1f
        key?.let { k -> a *= k.apply(rgb) }
        mask?.let { m -> a *= m.at(src).coverage(x, y, aspect) }
        return a
    }

    companion object {
        fun fromJson(o: JSONObject?): LayerFx? {
            if (o == null) return null
            val l = LayerFx(
                ChromaKey.fromJson(o.optJSONObject("key")), LayerMask.fromJson(o.optJSONObject("mask")),
                BlendMode.entries.firstOrNull { it.name == o.optString("blend") } ?: BlendMode.NORMAL,
            )
            return l.takeUnless { it.isNone }
        }
    }
}

/**
 * Chroma key (green/blue screen): pixels near [color] in the chroma plane (Cb/Cr, independent of luminance) become
 * transparent. [tolerance] = how near (0..1, relative to the key color's saturation), [softness] = soft transition,
 * [spill] = how much of the screen's color is removed from edges (green cast in hair and skin).
 */
data class ChromaKey(val color: Int = GREEN, val tolerance: Float = 0.35f, val softness: Float = 0.15f, val spill: Float = 0.6f) {
    private val kr get() = ((color shr 16) and 255) / 255f
    private val kg get() = ((color shr 8) and 255) / 255f
    private val kb get() = (color and 255) / 255f
    /** The key color's strongest channel (0 red, 1 green, 2 blue) – the one spill is removed from. */
    val dominant: Int get() = if (kg >= kr && kg >= kb) 1 else if (kb >= kr) 2 else 0

    /** Coverage (1 = kept, 0 = removed) for [rgb]; spill is removed from [rgb] in place. */
    fun apply(rgb: FloatArray): Float {
        val (pb, pr) = cbcr(rgb[0], rgb[1], rgb[2])
        val (kcb, kcr) = cbcr(kr, kg, kb)
        // along the key color's direction (1 = equally saturated, shadowed screen less) and the angle to it (other hue) –
        // the angle, not the distance: a more or less saturated screen of the same hue is still the screen
        val kk = max(kcb * kcb + kcr * kcr, 0.0025f)
        val along = (pb * kcb + pr * kcr) / kk
        val perp = sqrt((pb - along * kcb) * (pb - along * kcb) + (pr - along * kcr) * (pr - along * kcr)) / sqrt(kk)
        val d = perp / max(along, 0.2f) + max(0f, SHADOW - along)
        val m = smoothstep(tolerance, tolerance + softness + 1e-4f, d)
        val i = dominant
        val lim = when (i) { 0 -> max(rgb[1], rgb[2]); 1 -> max(rgb[0], rgb[2]); else -> max(rgb[0], rgb[1]) }
        rgb[i] = rgb[i] + (min(rgb[i], lim) - rgb[i]) * spill
        return m
    }

    fun toJson(): JSONObject = JSONObject().put("color", color).put("tol", tolerance.toDouble()).put("soft", softness.toDouble()).put("spill", spill.toDouble())

    companion object {
        /** This weak along the key color's direction still counts as the screen (shadows, uneven lighting). */
        const val SHADOW = 0.6f
        /** Default colors for green and blue screen. */
        const val GREEN = 0xFF00B140.toInt()
        const val BLUE = 0xFF0047BB.toInt()
        fun fromJson(o: JSONObject?): ChromaKey? = o?.let {
            val d = ChromaKey()
            ChromaKey(it.optInt("color", d.color), it.optDouble("tol", d.tolerance.toDouble()).toFloat(),
                it.optDouble("soft", d.softness.toDouble()).toFloat(), it.optDouble("spill", d.spill.toDouble()).toFloat())
        }
        /** The Rec. 709 chroma plane. */
        fun cbcr(r: Float, g: Float, b: Float): Pair<Float, Float> =
            (-0.1146f * r - 0.3854f * g + 0.5f * b) to (0.5f * r - 0.4542f * g - 0.0458f * b)
    }
}

/**
 * Layer mask: a rectangle or ellipse (center [cx], [cy] and size [w] × [h], fractions of the frame, y down) with
 * soft edge [feather] (fraction of the frame height). [invert] = everything except the shape shows. [keys] = keyframes
 * for position and size (in the clip's source time – they follow when the clip is trimmed, moved or split); without
 * them [cx]…[h] apply.
 */
data class LayerMask(
    val shape: Int = RECT, val cx: Float = 0.5f, val cy: Float = 0.5f, val w: Float = 0.6f, val h: Float = 0.6f,
    val feather: Float = 0.03f, val invert: Boolean = false,
    val keys: List<MaskKey> = emptyList(),
) {
    /** Position and size are animated (keyframes, e.g. from tracking). */
    val animated get() = keys.isNotEmpty()

    /**
     * The mask at source time [src] (seconds in the clip's file), without keyframes: between two keyframes per the
     * first one's interpolation, before the first and after the last their values. Without keyframes (or time) as is.
     */
    fun at(src: Double?): LayerMask {
        if (keys.isEmpty()) return this
        if (src == null) return copy(keys = emptyList())
        val k = keys
        if (src <= k.first().t) return k.first().let { copy(cx = it.cx, cy = it.cy, w = it.w, h = it.h, keys = emptyList()) }
        if (src >= k.last().t) return k.last().let { copy(cx = it.cx, cy = it.cy, w = it.w, h = it.h, keys = emptyList()) }
        // binary search: tracking yields hundreds of keyframes and this runs for every frame
        var lo = 0; var hi = k.size - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (k[m].t <= src) lo = m else hi = m }
        val a = k[lo]; val b = k[hi]
        val u0 = ((src - a.t) / (b.t - a.t).coerceAtLeast(1e-9)).toFloat().coerceIn(0f, 1f)
        val u = when (a.interp) { MaskKey.HOLD -> 0f; MaskKey.SMOOTH -> u0 * u0 * (3f - 2f * u0); else -> u0 }
        fun l(x: Float, y: Float) = x + (y - x) * u
        return copy(cx = l(a.cx, b.cx), cy = l(a.cy, b.cy), w = l(a.w, b.w), h = l(a.h, b.h), keys = emptyList())
    }

    /** The keyframe at source time [src] (within [tol] seconds), or null. */
    fun keyAt(src: Double, tol: Double): MaskKey? = keys.minByOrNull { abs(it.t - src) }?.takeIf { abs(it.t - src) <= tol }

    /**
     * Position and size [v] at source time [src]: an animated mask gets a keyframe there (an existing one within [tol]
     * seconds is changed), otherwise the static values change.
     */
    fun withValues(v: LayerMask, src: Double?, tol: Double): LayerMask {
        if (!animated || src == null) return copy(cx = v.cx, cy = v.cy, w = v.w, h = v.h)
        val old = keyAt(src, tol)
        val nk = MaskKey(old?.t ?: src, v.cx, v.cy, v.w, v.h, old?.interp ?: MaskKey.LINEAR)
        return copy(keys = (keys.filter { it !== old } + nk).sortedBy { it.t })
    }

    /** Animation on (one keyframe with the values at [src]) or off (the static values become those at [src]). */
    fun toggleAnimation(src: Double): LayerMask =
        if (animated) at(src) else copy(keys = listOf(MaskKey(src, cx, cy, w, h)))

    /** Adds a keyframe at [src] (with the values there) or removes one (within [tol]); with no keyframes left the mask becomes static there. */
    fun toggleKey(src: Double, tol: Double): LayerMask {
        val old = keyAt(src, tol)
        if (old == null) return at(src).let { cur -> copy(keys = (keys + MaskKey(src, cur.cx, cur.cy, cur.w, cur.h)).sortedBy { it.t }) }
        val rest = keys.filter { it !== old }
        return if (rest.isEmpty()) at(src) else copy(keys = rest)
    }

    /** Keyframe [k] moved to source time [to] (another one already there is replaced). */
    fun moveKey(k: MaskKey, to: Double, tol: Double): LayerMask =
        copy(keys = (keys.filter { it != k && abs(it.t - to) > tol } + k.copy(t = to)).sortedBy { it.t })

    /** The interpolation after keyframe [k]. */
    fun setInterp(k: MaskKey, interp: Int): LayerMask = copy(keys = keys.map { if (it == k) it.copy(interp = interp) else it })

    /** Keyframes in the source range [lo]–[hi] replaced with [fresh] (a track); those outside remain. */
    fun replaceKeys(lo: Double, hi: Double, fresh: List<MaskKey>): LayerMask {
        val eps = 1e-6
        return copy(keys = (keys.filter { it.t < lo - eps || it.t > hi + eps } + fresh).sortedBy { it.t })
    }

    /** How much of the point (x, y) is visible (0..1). Animated: call on [at]. */
    fun coverage(x: Float, y: Float, aspect: Float): Float {
        val dist = if (shape == RECT) {
            // distance to the edge (in frame heights), negative inside
            val ex = (abs(x - cx) - w * 0.5f) * aspect
            val ey = abs(y - cy) - h * 0.5f
            sqrt(max(ex, 0f) * max(ex, 0f) + max(ey, 0f) * max(ey, 0f)) + min(max(ex, ey), 0f)
        } else {
            val dx = (x - cx) / max(w * 0.5f, 1e-4f)
            val dy = (y - cy) / max(h * 0.5f, 1e-4f)
            (sqrt(dx * dx + dy * dy) - 1f) * min(w * 0.5f * aspect, h * 0.5f)
        }
        val f = max(feather * 0.5f, 1e-4f)
        val inside = 1f - smoothstep(-f, f, dist)
        return if (invert) 1f - inside else inside
    }

    fun toJson(): JSONObject = JSONObject().put("shape", shape).put("cx", cx.toDouble()).put("cy", cy.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("feather", feather.toDouble()).put("invert", invert)
        .apply { if (keys.isNotEmpty()) put("keys", org.json.JSONArray().also { a -> keys.forEach { a.put(it.toJson()) } }) }

    companion object {
        const val RECT = 0
        const val ELLIPSE = 1
        fun fromJson(o: JSONObject?): LayerMask? = o?.let {
            val d = LayerMask()
            val ka = it.optJSONArray("keys")
            val keys = if (ka == null) emptyList() else (0 until ka.length()).mapNotNull { i -> MaskKey.fromJson(ka.optJSONArray(i)) }.sortedBy { k -> k.t }
            LayerMask(it.optInt("shape", d.shape), it.optDouble("cx", 0.5).toFloat(), it.optDouble("cy", 0.5).toFloat(), it.optDouble("w", 0.6).toFloat(),
                it.optDouble("h", 0.6).toFloat(), it.optDouble("feather", d.feather.toDouble()).toFloat(), it.optBoolean("invert", false), keys)
        }
    }
}

/** A mask keyframe: source time [t] (seconds in the clip's file), position and size, interpolation to the next. */
data class MaskKey(val t: Double, val cx: Float, val cy: Float, val w: Float, val h: Float, val interp: Int = LINEAR) {
    fun toJson(): org.json.JSONArray = org.json.JSONArray().put(r(t, 1e6)).put(r(cx.toDouble(), 1e5)).put(r(cy.toDouble(), 1e5))
        .put(r(w.toDouble(), 1e5)).put(r(h.toDouble(), 1e5)).put(interp)

    companion object {
        const val LINEAR = 0
        /** Ease in and out. */
        const val SMOOTH = 1
        /** Holds until the next keyframe. */
        const val HOLD = 2
        private fun r(v: Double, k: Double) = Math.round(v * k) / k
        fun fromJson(a: org.json.JSONArray?): MaskKey? {
            if (a == null || a.length() < 5) return null
            val t = a.optDouble(0, Double.NaN)
            if (!t.isFinite()) return null
            return MaskKey(t, a.optDouble(1, 0.5).toFloat(), a.optDouble(2, 0.5).toFloat(), a.optDouble(3, 0.6).toFloat(), a.optDouble(4, 0.6).toFloat(),
                a.optInt(5, LINEAR).coerceIn(LINEAR, HOLD))
        }

        /**
         * A track ([track], relative to mask [base] at the reference frame) as keyframes in [lo]–[hi]: only as many as
         * needed so the mask between them (linear) never deviates more than [tol] (fraction of the frame) from the track
         * (Ramer–Douglas–Peucker over the frames, in position and size together). The tracked rotation is not used.
         */
        fun fromTrack(track: WindowTrack, base: LayerMask, lo: Double, hi: Double, tol: Float = 0.0015f): List<MaskKey> {
            if (track.size == 0) return emptyList()
            val a = maxOf(lo, track.t0); val b = minOf(hi, track.end)
            val n = if (b <= a) 1 else (Math.round((b - a) / track.dt).toInt() + 1).coerceAtLeast(2)
            val dense = List(n) { i ->
                val t = if (n == 1) a else a + (b - a) * i / (n - 1)
                val m = track.at(t)
                MaskKey(t, base.cx + m[0], base.cy + m[1], base.w * m[2], base.h * m[2])
            }
            if (dense.size <= 2) return dense
            val keep = BooleanArray(dense.size).also { it[0] = true; it[it.size - 1] = true }
            val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to dense.size - 1) }
            while (stack.isNotEmpty()) {
                val (i, j) = stack.removeLast()
                if (j - i < 2) continue
                val p = dense[i]; val q = dense[j]
                var worst = -1; var dmax = 0f
                for (k in i + 1 until j) {
                    val u = ((dense[k].t - p.t) / (q.t - p.t)).toFloat()
                    val e = maxOf(
                        abs(p.cx + (q.cx - p.cx) * u - dense[k].cx), abs(p.cy + (q.cy - p.cy) * u - dense[k].cy),
                        abs(p.w + (q.w - p.w) * u - dense[k].w), abs(p.h + (q.h - p.h) * u - dense[k].h),
                    )
                    if (e > dmax) { dmax = e; worst = k }
                }
                if (dmax > tol && worst > 0) { keep[worst] = true; stack.add(i to worst); stack.add(worst to j) }
            }
            return dense.filterIndexed { i, _ -> keep[i] }
        }
    }
}

/** How the layer blends with the one below (as in Premiere/Photoshop). Stored by name; [label] is the display name. */
enum class BlendMode(@androidx.annotation.StringRes private val labelRes: Int) {
    NORMAL(R.string.layerfx_blend_normal), MULTIPLY(R.string.layerfx_blend_multiply), SCREEN(R.string.layerfx_blend_screen),
    OVERLAY(R.string.layerfx_blend_overlay), SOFT_LIGHT(R.string.layerfx_blend_soft_light), ADD(R.string.layerfx_blend_add),
    DARKEN(R.string.layerfx_blend_darken), LIGHTEN(R.string.layerfx_blend_lighten), DIFFERENCE(R.string.layerfx_blend_difference);

    val label: String get() = Str.t(labelRes)

    /** Blend of color [s] (the layer) with [d] (below), without opacity – same as the compositor's shader. */
    fun mix(s: Float, d: Float): Float = when (this) {
        NORMAL -> s
        MULTIPLY -> s * d
        SCREEN -> s + d - s * d
        OVERLAY -> if (d < 0.5f) 2f * s * d else 1f - 2f * (1f - s) * (1f - d)
        SOFT_LIGHT -> if (s < 0.5f) d - (1f - 2f * s) * d * (1f - d) else d + (2f * s - 1f) * ((if (d < 0.25f) ((16f * d - 12f) * d + 4f) * d else sqrt(d)) - d)
        ADD -> min(s + d, 1f)
        DARKEN -> min(s, d)
        LIGHTEN -> max(s, d)
        DIFFERENCE -> abs(s - d)
    }
}

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The key or the mask as a Media3 effect ([part]): the key first, on the source image (the grade doesn't change what is
 * the screen; opacity carries through the grade), the mask last. [clip] = the clip whose current values are read from
 * [LiveGrades] (playback; sliders show immediately). [clock] = the frame's source time (mask keyframes). [out]: [STRAIGHT]
 * for the compositor (export), [FLATTEN] when there's no layer below (bottom layer, a single image: transparent becomes
 * black – otherwise the green screen would show when opacity is dropped), [PREMULTIPLIED] for the preview's TextureView
 * (Android blends that way).
 */
@OptIn(UnstableApi::class)
class LayerFxEffect(private val fx: LayerFx, private val clip: Long?, private val part: Int, private val out: Int, private val clock: ((Long) -> Double?)? = null) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(fx, clip, part, out, clock, useHdr)

    companion object {
        const val KEY = 0
        const val MASK = 1
        const val STRAIGHT = 0
        const val FLATTEN = 1
        const val PREMULTIPLIED = 2
    }

    private class Program(private val fx0: LayerFx, private val clip: Long?, private val part: Int, private val out: Int, private val clock: ((Long) -> Double?)?, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program = GlProgram(TetraShaders.VERTEX, Shaders.FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private var aspect = 16f / 9f

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth > 0 && inputHeight > 0) aspect = inputWidth.toFloat() / inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val fx = clip?.let { LiveGrades.layer(it) } ?: fx0
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                val k = fx.key?.takeIf { part == KEY }
                program.setFloatUniform("uKeyOn", if (k != null) 1f else 0f)
                val kc = k?.color ?: 0
                program.setFloatsUniform("uKey", floatArrayOf(((kc shr 16) and 255) / 255f, ((kc shr 8) and 255) / 255f, (kc and 255) / 255f))
                program.setFloatUniform("uTol", k?.tolerance ?: 0f)
                program.setFloatUniform("uSoft", k?.softness ?: 0f)
                program.setFloatUniform("uSpill", k?.spill ?: 0f)
                program.setFloatUniform("uDom", (k?.dominant ?: 1).toFloat())
                // animated: the position at the frame's source time (without a clock, the first keyframe's)
                val m = fx.mask?.takeIf { part == MASK }?.let { if (it.animated) it.at(clock?.invoke(presentationTimeUs) ?: it.keys.first().t) else it }
                program.setFloatUniform("uMaskOn", if (m != null) 1f else 0f)
                program.setFloatUniform("uShape", (m?.shape ?: 0).toFloat())
                program.setFloatsUniform("uC", floatArrayOf(m?.cx ?: 0.5f, m?.cy ?: 0.5f))
                program.setFloatsUniform("uSize", floatArrayOf(m?.w ?: 1f, m?.h ?: 1f))
                program.setFloatUniform("uFeather", m?.feather ?: 0f)
                program.setFloatUniform("uInvert", if (m?.invert == true) 1f else 0f)
                program.setFloatUniform("uAspect", aspect)
                program.setFloatUniform("uOut", out.toFloat())
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try { program.delete() } catch (e: Exception) { throw VideoFrameProcessingException(e) }
        }
    }

    private object Shaders {
        // same math as ChromaKey.apply and LayerMask.coverage
        const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform float uKeyOn;
uniform vec3 uKey;
uniform float uTol;
uniform float uSoft;
uniform float uSpill;
uniform float uDom;
uniform float uMaskOn;
uniform float uShape;
uniform vec2 uC;
uniform vec2 uSize;
uniform float uFeather;
uniform float uInvert;
uniform float uAspect;
uniform float uOut;
varying vec2 vTexSamplingCoord;
vec2 cbcr(vec3 c) { return vec2(-0.1146 * c.r - 0.3854 * c.g + 0.5 * c.b, 0.5 * c.r - 0.4542 * c.g - 0.0458 * c.b); }
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 rgb = c.rgb;
  float a = c.a;
  if (uKeyOn > 0.5) {
    vec2 k = cbcr(uKey);
    vec2 p = cbcr(rgb);
    float kk = max(dot(k, k), 0.0025);
    float along = dot(p, k) / kk;
    float d = length(p - along * k) / sqrt(kk) / max(along, 0.2) + max(0.0, 0.6 - along);
    a *= smoothstep(uTol, uTol + uSoft + 0.0001, d);
    if (uDom > 1.5) rgb.b = mix(rgb.b, min(rgb.b, max(rgb.r, rgb.g)), uSpill);
    else if (uDom > 0.5) rgb.g = mix(rgb.g, min(rgb.g, max(rgb.r, rgb.b)), uSpill);
    else rgb.r = mix(rgb.r, min(rgb.r, max(rgb.g, rgb.b)), uSpill);
  }
  if (uMaskOn > 0.5) {
    vec2 q = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
    float dist;
    if (uShape < 0.5) {
      vec2 e = vec2((abs(q.x - uC.x) - uSize.x * 0.5) * uAspect, abs(q.y - uC.y) - uSize.y * 0.5);
      dist = length(max(e, 0.0)) + min(max(e.x, e.y), 0.0);
    } else {
      vec2 dd = (q - uC) / max(uSize * 0.5, vec2(0.0001));
      dist = (length(dd) - 1.0) * min(uSize.x * 0.5 * uAspect, uSize.y * 0.5);
    }
    float f = max(uFeather * 0.5, 0.0001);
    float inside = 1.0 - smoothstep(-f, f, dist);
    if (uInvert > 0.5) inside = 1.0 - inside;
    a *= inside;
  }
  if (uOut > 1.5) gl_FragColor = vec4(rgb * a, a);
  else if (uOut > 0.5) gl_FragColor = vec4(rgb * a, 1.0);
  else gl_FragColor = vec4(rgb, a);
}
"""
    }
}
