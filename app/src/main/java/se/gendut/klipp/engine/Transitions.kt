package se.gendut.klipp.engine

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.hypot
import se.gendut.klipp.R
import se.gendut.klipp.Str

/**
 * Transitions between two clips (the engine's effect names, so they are saved and undone in the engine). The cross
 * dissolve and the dip to black are done with opacity as before; the others with [SegFx] – the same geometry in the
 * export (the GL effect [TransitionFxEffect]) and in the preview's upper layer (the app draws the same frames).
 */
enum class TransitionType(val effect: String, @StringRes val labelRes: Int, val directional: Boolean) {
    CROSS("cross_dissolve", R.string.transitions_cross_dissolve, false),
    WHIP("whip", R.string.transitions_whip, true),
    ROTATE("radial_blur", R.string.transitions_rotate, true),
    WHIP_VERTICAL("roll", R.string.transitions_whip_vertical, true),
    LIGHT_LEAK("light_leak", R.string.transitions_light_leak, false),
    DIP_BLACK("dip_to_black", R.string.transitions_dip_to_black, false),
    DIP_WHITE("dip_to_white", R.string.transitions_dip_to_white, false),
    PUSH("push", R.string.transitions_push, true),
    ZOOM("cross_zoom", R.string.transitions_zoom, false);

    /** User-visible name, resolved at use (follows the current locale). */
    val label: String get() = Str.t(labelRes)

    /** Has motion blur (the amount can be set per transition). */
    val blurs: Boolean get() = this == WHIP || this == ZOOM || this == ROTATE || this == WHIP_VERTICAL

    companion object {
        /** Engine effect → type (unknown ones show as a cross dissolve). */
        fun of(effect: String): TransitionType = entries.firstOrNull { it.effect == effect } ?: RETIRED[effect] ?: CROSS
        /** Types that were removed: what a saved transition of that kind shows as now. */
        private val RETIRED = mapOf("zoom_blur" to ZOOM, "spin_3d" to WHIP, "slide" to PUSH, "wipe" to PUSH, "iris_round" to CROSS)
        /** The types a fade in/out (against nothing) can have. */
        val FADES = listOf(CROSS, DIP_WHITE)
    }
}

/** What is visible of a frame: [kind] and the edge position [v] (0..1 of the frame width, or the radius). */
data class Mask(val kind: Int, val v: Float) {
    companion object {
        const val LEFT_OF = 1   // visible where x < v
        const val RIGHT_OF = 2  // visible where x > v
        const val CIRCLE_IN = 3 // visible inside a circle of radius v (of the frame's half diagonal)
        const val CIRCLE_OUT = 4
    }
}

/** The frame's placement during the transition: offset (fraction of width/height, + right/down), scale, mask and tint toward a color. */
data class Geom(
    val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val mask: Mask? = null, val tint: Float = 0f, val tintWhite: Boolean = false,
    /** Motion blur: the length of the smear along x (fraction of the width), and toward the center (fraction of the distance to it). */
    val blurX: Float = 0f, val blurZoom: Float = 0f,
    /** The picture continues mirrored beyond its edges (nothing shows through). [soft]: and its edge facing the other clip fades over this width (fraction of the width). */
    val mirror: Boolean = false, val soft: Float = 0f,
    /** Turned around the center (radians), the smear along y (fraction of the height) and around the center (radians), and how far a light leak has burnt in (0..1). */
    val turn: Float = 0f, val blurY: Float = 0f, val blurTurn: Float = 0f, val leak: Float = 0f,
    /** How far the light leak's clouds have drifted (the transition's progress, 0..1). */
    val drift: Float = 0f,
) {
    val identity get() = dx == 0f && dy == 0f && scale == 1f && mask == null && tint == 0f && blurX == 0f && blurZoom == 0f && turn == 0f && blurY == 0f && blurTurn == 0f && leak == 0f
    /** Needs the expensive shader. */
    val heavy get() = mirror || blurX > 0f || blurZoom > 0f || blurY > 0f || blurTurn > 0f || leak > 0f
}

/**
 * A piece of a clip during a transition: [incoming] = the incoming clip (otherwise the outgoing one), [over] = the piece
 * lies in the overlap layer above the track (otherwise in the track). [t0]–[t1] = the transition, [cut] = the edit point.
 */
data class SegFx(
    val type: TransitionType, val incoming: Boolean, val over: Boolean, val t0: Double, val t1: Double, val cut: Double, val reverse: Boolean,
    /** The motion blur, times the default ([TransitionLooks]). */
    val blur: Float = 1f,
) {
    fun progress(t: Double): Float = if (t1 <= t0) 1f else ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0).toFloat()

    /** How the piece is drawn at timeline time [t]. */
    fun at(t: Double): Geom = move(t).let { g -> if (blur == 1f) g else g.copy(blurX = g.blurX * blur, blurY = g.blurY * blur, blurZoom = g.blurZoom * blur, blurTurn = g.blurTurn * blur) }

    private fun move(t: Double): Geom {
        val p = progress(t)
        val sign = if (reverse) -1f else 1f
        // through the edit point (dips through a color, zoom): 0 at the edges, 1 at the edit point
        val pc = if (t1 > t0) ((cut - t0) / (t1 - t0)).toFloat().coerceIn(0.001f, 0.999f) else 0.5f
        val k = (if (t < cut) (p / pc) else ((1f - p) / (1f - pc))).coerceIn(0f, 1f)
        return when (type) {
            TransitionType.CROSS -> Geom()
            // into the color slowly, ever faster, black at the edit point; out of it at once and then a long soft tail (the
            // overlap layer is not visible)
            TransitionType.DIP_BLACK, TransitionType.DIP_WHITE -> Geom(
                tint = if (incoming) 1f - bezier(after(p, pc), 0.07f, 0.17f, 0.43f, 0.99f).first else bezier(before(p, pc), 0.64f, 0.02f, 0.94f, 0.61f).first,
                tintWhite = type == TransitionType.DIP_WHITE,
            )
            // push: the incoming clip comes from the right (reversed: left) and pushes out the outgoing one
            TransitionType.PUSH -> thrown(p, pc).let { (e, _) -> if (incoming) Geom(dx = sign * (1f - e)) else Geom(dx = -sign * e) }
            // zoom: one single move inward, through the edit point. The outgoing clip grows from its normal size, the incoming
            // one carries on from smaller than the frame (its picture mirrored around it) up to its normal size, both smeared as
            // a camera with a 360° shutter would see the move
            TransitionType.ZOOM -> arrive(p, pc).let { (u, v) ->
                val logSpeed = if (incoming) ZOOM_IN * SETTLE_SPEED * v / (t1 - cut).toFloat() else ZOOM_OUT * GATHER_SPEED * v / (cut - t0).toFloat()
                zoomed(u, (logSpeed * SHUTTER_S).coerceAtMost(MAX_ZOOM_BLUR), 1f)
            }
            // whip: the push's movement, smeared as fast as it moves. Instead of a line between the clips the upper one fades
            // into the lower one around its edge, both continuing mirrored beyond their edges
            TransitionType.WHIP -> thrown(p, pc).let { (e, v) ->
                val dx = if (incoming) sign * (1f - e) else -sign * e
                Geom(dx = dx, blurX = WHIP_BLUR * v, mirror = true, soft = if (over) WHIP_SOFT else 0f)
            }
            // rotate: no zoom, only the turn – the outgoing clip spins ever faster a third of the way round, the incoming one
            // takes over a quarter turn behind and settles level (the picture mirrored around the frame), smeared as by a 300°
            // shutter. Reversed: counterclockwise
            TransitionType.ROTATE -> {
                val (turn, speed) = if (incoming) bezier(after(p, pc), 0.19f, 0.52f, 0.33f, 0.99f).let { (u, d) -> ROTATE_IN * (u - 1f) to ROTATE_IN * d / (t1 - cut).toFloat() }
                    else gather(before(p, pc)).let { (u, v) -> ROTATE_OUT * u to ROTATE_OUT * GATHER_SPEED * v / (cut - t0).toFloat() }
                Geom(turn = sign * turn, blurTurn = (speed * SHUTTER_S * 300f / 360f).coerceAtMost(MAX_TURN_BLUR), mirror = true)
            }
            // straight down through the edit point: the picture continues mirrored, so nothing else has to be seen at once
            TransitionType.WHIP_VERTICAL -> arrive(p, pc).let { (u, v) -> Geom(dy = sign * (if (incoming) 1f - u else -u), blurY = PAN_BLUR * v, mirror = true) }
            // burns in toward the edit point, where the picture changes behind the light, and fades away
            TransitionType.LIGHT_LEAK -> Geom(leak = k * k * (3f - 2f * k), drift = p)
        }
    }

    /**
     * The move's progress [0] (the outgoing clip reaches 1 at the edit point, the incoming one starts from 0 and comes to rest
     * at 1) and its speed [1] (1 at the edit point, 0 at rest): it gathers speed from rest into the edit point, leaves it at
     * full speed and brakes at once, settling softly for a long while. The soft feel is in that tail.
     */
    private fun arrive(p: Float, pc: Float): Pair<Float, Float> = if (incoming) settle(after(p, pc)) else gather(before(p, pc))

    /** How far into the part before the edit point, and after it (0..1). */
    private fun before(p: Float, pc: Float) = (p / pc).coerceIn(0f, 1f)
    private fun after(p: Float, pc: Float) = ((p - pc) / (1f - pc)).coerceIn(0f, 1f)

    /** The push and the whip: one move from 0 to 1 that is [THROWN] far at the edit point, with [arrive]'s speeds on either side. */
    private fun thrown(p: Float, pc: Float): Pair<Float, Float> =
        if (p < pc) gather(p / pc).let { (u, v) -> THROWN * u to v }
        else settle(((p - pc) / (1f - pc)).coerceIn(0f, 1f)).let { (u, v) -> THROWN + (1f - THROWN) * u to v }

    /** The zoom: one move inward through the edit point (the incoming clip carries on from smaller than the frame, mirrored around it). */
    private fun zoomed(u: Float, speed: Float, blur: Float) =
        Geom(scale = kotlin.math.exp(if (incoming) ZOOM_IN * (u - 1f) else ZOOM_OUT * u), blurZoom = blur * speed, mirror = true)

    /** Whether the piece is visible at all (for the dips through a color and the zoom the overlap layer is hidden: they cut at the edit point). */
    val visible get() = !(over && (type == TransitionType.DIP_BLACK || type == TransitionType.DIP_WHITE || type == TransitionType.ZOOM || type == TransitionType.ROTATE || type == TransitionType.WHIP_VERTICAL || type == TransitionType.LIGHT_LEAK))

    companion object {
        /** The zoom as logarithms: the outgoing clip grows to ×2.46 at the edit point, the incoming one starts there from ×0.65. */
        const val ZOOM_OUT = 0.9f
        const val ZOOM_IN = 0.43f
        /** Exposure time of the zoom's motion blur: a 360° shutter at 25 fps. */
        const val SHUTTER_S = 1f / 25f
        /** The version of the moves above, in the render cache's keys: raise it when a transition's look changes. */
        const val LOOK = 4
        /** How far the push and the whip have come at the edit point: about where the speeds on either side meet. */
        const val THROWN = 0.7f
        /** The most smear (the zoom's in log scale, the turn's in radians) – short transitions move very fast. */
        const val MAX_ZOOM_BLUR = 1.3f
        const val MAX_TURN_BLUR = 1.2f
        /** Rotate: the outgoing clip's turn at the edit point (110°) and how far behind the incoming one starts (90°). */
        const val ROTATE_OUT = 1.92f
        const val ROTATE_IN = 1.571f
        private const val GATHER = 1.1f
        private val GATHER_E = kotlin.math.exp(GATHER)
        /** [gather]'s speed at the edit point and [settle]'s right after it, in move per part (for the zoom's true speed). */
        val GATHER_SPEED = GATHER * (GATHER_E - 1f) / (GATHER_E - 1f - GATHER)
        val SETTLE_SPEED = SETTLE_Y1 / SETTLE_X1
        private const val SETTLE_X1 = 0.1f
        private const val SETTLE_Y1 = 0.7f

        /** Up to the edit point, x = 0..1 of the part: from rest, ever faster (speed 1 at the end). */
        fun gather(x: Float): Pair<Float, Float> {
            val e = kotlin.math.exp(GATHER * x)
            return (e - 1f - GATHER * x) / (GATHER_E - 1f - GATHER) to (e - 1f) / (GATHER_E - 1f)
        }

        /**
         * After it: the ease cubic-bezier(0.1, 0.7, 0, 1) – leaving at full speed (1), half the way gone in a twentieth of the
         * part, then a long soft landing.
         */
        fun settle(x: Float): Pair<Float, Float> = bezier(x, SETTLE_X1, SETTLE_Y1, 0f, 1f).let { (u, d) -> u to (d / SETTLE_SPEED).coerceIn(0f, 1f) }

        /** The ease cubic-bezier([x1], [y1], [x2], [y2]) at [x] (0..1): the value and its slope. */
        fun bezier(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> {
            if (x <= 0f) return 0f to (if (x1 > 0f) y1 / x1 else 0f)
            if (x >= 1f) return 1f to (if (x2 < 1f) (1f - y2) / (1f - x2) else 0f)
            // the curve's parameter s where its x is [x] (x rises monotonically with s)
            var lo = 0f; var hi = 1f
            repeat(24) { val s = (lo + hi) * 0.5f; if (bez(s, x1, x2) < x) lo = s else hi = s }
            val s = (lo + hi) * 0.5f
            val dx = bezD(s, x1, x2); val dy = bezD(s, y1, y2)
            return bez(s, y1, y2) to (if (dx > 1e-6f) dy / dx else 0f)
        }

        /** One coordinate of a cubic from 0 to 1 with inner points [a] and [b], and its derivative. */
        private fun bez(s: Float, a: Float, b: Float): Float { val r = 1f - s; return 3f * r * r * s * a + 3f * r * s * s * b + s * s * s }
        private fun bezD(s: Float, a: Float, b: Float): Float { val r = 1f - s; return 3f * r * r * a + 6f * r * s * (b - a) + 3f * s * s * (1f - b) }
        const val WHIP_BLUR = 0.9f
        const val WHIP_SOFT = 0.4f
        const val PAN_BLUR = 0.8f

        /** Visible at (x, y) in the frame's normalized coordinates (0..1, y down) with aspect ratio [aspect]? */
        fun visibleAt(m: Mask?, x: Float, y: Float, aspect: Float): Boolean {
            m ?: return true
            return when (m.kind) {
                Mask.LEFT_OF -> x < m.v
                Mask.RIGHT_OF -> x > m.v
                Mask.CIRCLE_IN, Mask.CIRCLE_OUT -> {
                    val r = m.v * hypot(0.5f * aspect, 0.5f)
                    val d = hypot((x - 0.5f) * aspect, y - 0.5f)
                    if (m.kind == Mask.CIRCLE_IN) d < r else d >= r
                }
                else -> true
            }
        }
    }
}

/**
 * The transition's motion, mask and tint on a piece (after fitting to the sequence frame, in its coordinates) – see
 * [Seg.geomAt]: the whole piece or only within the transitions' window.
 * Outside the frame and outside the mask it becomes transparent – the compositor then shows the layer below.
 */
@OptIn(UnstableApi::class)
class TransitionFxEffect(private val seg: Seg, private val time: TimeBase) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(seg, time, useHdr)

    private class Program(private val seg: Seg, private val time: TimeBase, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private fun build(fragment: String) = GlProgram(TetraShaders.VERTEX, fragment).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        // the effect stays on the whole clip: outside the moments with motion blur it must cost next to nothing
        private val plain = build(FRAGMENT)
        // (built at the first frame that needs it, and before the frame's program is taken into use: building one makes it the current program)
        private var blurred: GlProgram? = null
        private var aspect = 16f / 9f

        private fun built(): GlProgram {
            val t = System.nanoTime()
            val program = build(FRAGMENT_BLUR)
            // a driver that takes long over it shows as a stall at the transition: leave a trace of it
            val ms = (System.nanoTime() - t) / 1_000_000
            if (ms >= 30) se.gendut.klipp.CrashReport.note("transition blur shader built in $ms ms")
            return program
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth > 0 && inputHeight > 0) aspect = inputWidth.toFloat() / inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // unknown time: frame unchanged (like the other time-dependent effects – never an empty frame from a time error)
                // outside the transition's window: unchanged
                val g = time.seconds(presentationTimeUs)?.let { seg.geomAt(it) } ?: Geom()
                val heavy = g.heavy
                val program = if (heavy) blurred ?: built().also { blurred = it } else plain
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uOffset", floatArrayOf(g.dx, g.dy))
                program.setFloatUniform("uScale", g.scale)
                program.setFloatUniform("uMaskKind", (g.mask?.kind ?: 0).toFloat())
                program.setFloatUniform("uMaskV", g.mask?.v ?: 0f)
                program.setFloatUniform("uAspect", aspect)
                program.setFloatUniform("uTint", g.tint)
                program.setFloatUniform("uTintWhite", if (g.tintWhite) 1f else 0f)
                if (heavy) {
                    program.setFloatUniform("uBlurX", g.blurX)
                    program.setFloatUniform("uBlurZoom", g.blurZoom)
                    program.setFloatUniform("uMirror", if (g.mirror) 1f else 0f)
                    program.setFloatUniform("uSoft", g.soft)
                    program.setFloatUniform("uTurn", g.turn)
                    program.setFloatUniform("uBlurY", g.blurY)
                    program.setFloatUniform("uBlurTurn", g.blurTurn)
                    program.setFloatUniform("uLeak", g.leak)
                    program.setFloatUniform("uDrift", g.drift)
                }
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try { plain.delete(); blurred?.delete() } catch (e: Exception) { throw VideoFrameProcessingException(e) }
        }
    }

    private companion object {
        // texture coordinates: y up (0 = bottom edge); the masks are computed with y down as in the app
        const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uOffset;
uniform float uScale;
uniform float uMaskKind;
uniform float uMaskV;
uniform float uAspect;
uniform float uTint;
uniform float uTintWhite;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 o = vTexSamplingCoord;
  // the frame point that lands here: offset (+x right, +y down) and scale around the center
  vec2 src = (o - vec2(0.5) - vec2(uOffset.x, -uOffset.y)) / uScale + vec2(0.5);
  vec4 c = texture2D(uTexSampler, src);
  float a = c.a;
  if (src.x < 0.0 || src.x > 1.0 || src.y < 0.0 || src.y > 1.0) a = 0.0;
  float x = o.x; float y = 1.0 - o.y;
  if (uMaskKind > 0.5 && uMaskKind < 1.5) { if (!(x < uMaskV)) a = 0.0; }
  else if (uMaskKind > 1.5 && uMaskKind < 2.5) { if (!(x > uMaskV)) a = 0.0; }
  else if (uMaskKind > 2.5 && uMaskKind < 4.5) {
    float r = uMaskV * length(vec2(0.5 * uAspect, 0.5));
    float d = length(vec2((x - 0.5) * uAspect, y - 0.5));
    bool inside = d < r;
    if (uMaskKind < 3.5) { if (!inside) a = 0.0; } else { if (inside) a = 0.0; }
  }
  vec3 rgb = mix(c.rgb, vec3(uTintWhite), uTint);
  gl_FragColor = vec4(rgb * (a > 0.0 ? 1.0 : 0.0), a);
}
"""

        /** With motion blur and mirrored edges (whip, fast zoom). */
        const val FRAGMENT_BLUR = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uOffset;
uniform float uScale;
uniform float uMaskKind;
uniform float uMaskV;
uniform float uAspect;
uniform float uTint;
uniform float uTintWhite;
uniform float uBlurX;
uniform float uBlurZoom;
uniform float uMirror;
uniform float uSoft;
uniform float uTurn;
uniform float uBlurY;
uniform float uBlurTurn;
uniform float uLeak;
uniform float uDrift;
varying vec2 vTexSamplingCoord;
vec2 turned(vec2 p, float a) {
  vec2 v = (p - vec2(0.5)) * vec2(uAspect, 1.0);
  float c = cos(a); float s = sin(a);
  return vec2(c * v.x - s * v.y, s * v.x + c * v.y) / vec2(uAspect, 1.0) + vec2(0.5);
}
vec2 tap(vec2 p) { return uMirror > 0.5 ? abs(mod(p + 1.0, 2.0) - 1.0) : clamp(p, 0.0, 1.0); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float clouds(vec2 p) {
  float v = 0.0; float a = 0.5;
  for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.03 + vec2(1.7, 9.2); a *= 0.5; }
  return v / 0.9375;
}
void main() {
  vec2 o = vTexSamplingCoord;
  // the frame point that lands here: offset (+x right, +y down) and scale around the center
  vec2 src = (o - vec2(0.5) - vec2(uOffset.x, -uOffset.y)) / uScale + vec2(0.5);
  if (uTurn != 0.0) src = turned(src, uTurn);
  vec4 c = texture2D(uTexSampler, uMirror > 0.5 ? tap(src) : src);
  float a = c.a;
  if (uMirror > 0.5) {
    // only the edge facing the other clip fades
    if (uSoft > 0.0 && uOffset.x != 0.0) a *= smoothstep(-uSoft * 0.5, uSoft * 0.5, uOffset.x < 0.0 ? 1.0 - src.x : src.x);
  } else if (src.x < 0.0 || src.x > 1.0 || src.y < 0.0 || src.y > 1.0) a = 0.0;
  float x = o.x; float y = 1.0 - o.y;
  if (uMaskKind > 0.5 && uMaskKind < 1.5) { if (!(x < uMaskV)) a = 0.0; }
  else if (uMaskKind > 1.5 && uMaskKind < 2.5) { if (!(x > uMaskV)) a = 0.0; }
  else if (uMaskKind > 2.5 && uMaskKind < 4.5) {
    float r = uMaskV * length(vec2(0.5 * uAspect, 0.5));
    float d = length(vec2((x - 0.5) * uAspect, y - 0.5));
    bool inside = d < r;
    if (uMaskKind < 3.5) { if (!inside) a = 0.0; } else { if (inside) a = 0.0; }
  }
  vec3 color = c.rgb;
  if (uBlurX > 0.0 || uBlurY > 0.0 || uBlurZoom > 0.0 || uBlurTurn > 0.0) {
    color = vec3(0.0);
    // the samples along the smear: the turn steps by a fixed rotation (no sine or cosine inside the loop)
    float a0 = -0.5 * uBlurTurn; float da = uBlurTurn / 31.0;
    vec2 r = vec2(cos(a0), sin(a0)); vec2 dr = vec2(cos(da), sin(da));
    vec2 aspect = vec2(uAspect, 1.0);
    // each pixel's taps start a little apart (interleaved gradient noise): a long smear turns into a soft streak instead
    // of 32 faint copies of the picture
    float j = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    for (int i = 0; i < 32; i++) {
      float f = (float(i) + j) / 32.0 - 0.5;
      vec2 v = (src + vec2(uBlurX, uBlurY) * f + (src - vec2(0.5)) * uBlurZoom * f - vec2(0.5)) * aspect;
      color += texture2D(uTexSampler, tap(vec2(r.x * v.x - r.y * v.y, r.y * v.x + r.x * v.y) / aspect + vec2(0.5))).rgb;
      r = vec2(r.x * dr.x - r.y * dr.y, r.y * dr.x + r.x * dr.y);
    }
    color /= 32.0;
  }
  vec3 rgb = mix(color, vec3(uTintWhite), uTint);
  if (uLeak > 0.0) {
    // turbulent clouds of light drifting across, warm orange to pink with a hot yellow core
    // screen blend of a warm-to-pink glow sweeping in from the right; at the edit point almost all white
    float cloud = clouds(vec2(o.x * uAspect, o.y) * 1.7 + vec2(-1.4 * uDrift, 0.5 * uDrift));
    float glow = smoothstep(1.2 - 1.6 * uLeak, 1.2, o.x + 0.25 * (1.0 - o.y) + (cloud - 0.5) * 0.7);
    vec3 warm = mix(vec3(1.0, 0.42, 0.12), vec3(1.0, 0.38, 0.72), smoothstep(0.35, 0.75, cloud + 0.3 * o.y));
    warm = mix(warm, vec3(1.0, 0.85, 0.45), smoothstep(0.6, 0.85, cloud) * glow);
    vec3 leak = warm * glow * (0.6 + 0.8 * cloud) * uLeak * 1.4;
    rgb = vec3(1.0) - (vec3(1.0) - rgb) * (vec3(1.0) - clamp(leak, 0.0, 1.0));
    rgb = mix(rgb, vec3(1.0), uLeak * uLeak * uLeak * uLeak * 0.9);
  }
  gl_FragColor = vec4(rgb * (a > 0.0 ? 1.0 : 0.0), a);
}
"""
    }
}

/** What the engine does not keep about a transition: its motion blur (× the default), by "from>to" clip ids – saved with the project. */
object TransitionLooks {
    val blur = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    const val MAX_BLUR = 3f

    fun blurOf(from: Long?, to: Long?): Float = if (from == null || to == null) 1f else blur[RenderCache.pairKey(from, to)] ?: 1f

    fun setBlur(from: Long, to: Long, v: Float) {
        val k = RenderCache.pairKey(from, to)
        val c = v.coerceIn(0f, MAX_BLUR)
        if (kotlin.math.abs(c - 1f) < 0.02f) blur.remove(k) else blur[k] = c
    }

    fun toJson() = org.json.JSONObject().apply { for ((k, v) in blur) put(k, v.toDouble()) }

    fun load(o: org.json.JSONObject?) {
        blur.clear()
        o?.keys()?.forEach { k -> o.optDouble(k, 1.0).takeIf { !it.isNaN() }?.let { blur[k] = it.toFloat().coerceIn(0f, MAX_BLUR) } }
    }
}
