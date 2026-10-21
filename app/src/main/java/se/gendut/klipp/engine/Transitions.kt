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
    DIP_BLACK("dip_to_black", R.string.transitions_dip_to_black, false),
    DIP_WHITE("dip_to_white", R.string.transitions_dip_to_white, false),
    WIPE("wipe", R.string.transitions_wipe, true),
    PUSH("push", R.string.transitions_push, true),
    SLIDE("slide", R.string.transitions_slide, true),
    ZOOM("cross_zoom", R.string.transitions_zoom, false),
    IRIS("iris_round", R.string.transitions_iris, false);

    /** User-visible name, resolved at use (follows the current locale). */
    val label: String get() = Str.t(labelRes)

    companion object {
        /** Engine effect → type (unknown ones show as a cross dissolve). */
        fun of(effect: String): TransitionType = entries.firstOrNull { it.effect == effect } ?: CROSS
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
data class Geom(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val mask: Mask? = null, val tint: Float = 0f, val tintWhite: Boolean = false) {
    val identity get() = dx == 0f && dy == 0f && scale == 1f && mask == null && tint == 0f
}

/**
 * A piece of a clip during a transition: [incoming] = the incoming clip (otherwise the outgoing one), [over] = the piece
 * lies in the overlap layer above the track (otherwise in the track). [t0]–[t1] = the transition, [cut] = the edit point.
 */
data class SegFx(val type: TransitionType, val incoming: Boolean, val over: Boolean, val t0: Double, val t1: Double, val cut: Double, val reverse: Boolean) {
    fun progress(t: Double): Float = if (t1 <= t0) 1f else ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0).toFloat()

    /** How the piece is drawn at timeline time [t]. */
    fun at(t: Double): Geom {
        val p = progress(t)
        val sign = if (reverse) -1f else 1f
        // through the edit point (dips through a color, zoom): 0 at the edges, 1 at the edit point
        val pc = if (t1 > t0) ((cut - t0) / (t1 - t0)).toFloat().coerceIn(0.001f, 0.999f) else 0.5f
        val k = (if (t < cut) (p / pc) else ((1f - p) / (1f - pc))).coerceIn(0f, 1f)
        return when (type) {
            TransitionType.CROSS -> Geom()
            // toward the color up to the edit point, then away from it (the overlap layer is not visible)
            TransitionType.DIP_BLACK, TransitionType.DIP_WHITE -> Geom(tint = k, tintWhite = type == TransitionType.DIP_WHITE)
            // wipe and iris: only the piece in the overlap layer (above) is masked – the track below shows fully where the
            // mask does not cover. If both were masked they each got an edge that had to meet exactly; in the preview (two
            // players) they drifted apart slightly and a black gap showed between the clips
            // wipe: the incoming clip shows left of the edge (reversed: to the right), the outgoing is the complement
            TransitionType.WIPE -> when {
                !over -> Geom()
                incoming -> Geom(mask = Mask(if (reverse) Mask.RIGHT_OF else Mask.LEFT_OF, if (reverse) 1f - p else p))
                else -> Geom(mask = Mask(if (reverse) Mask.LEFT_OF else Mask.RIGHT_OF, if (reverse) 1f - p else p))
            }
            // iris: the incoming clip shows in a growing circle
            TransitionType.IRIS -> when {
                !over -> Geom()
                incoming -> Geom(mask = Mask(Mask.CIRCLE_IN, p))
                else -> Geom(mask = Mask(Mask.CIRCLE_OUT, p))
            }
            // push: the incoming clip comes from the right (reversed: left) and pushes out the outgoing one
            TransitionType.PUSH -> if (incoming) Geom(dx = sign * (1f - p)) else Geom(dx = -sign * p)
            // slide: the incoming clip slides in over the outgoing one, which stays still
            TransitionType.SLIDE -> when {
                incoming -> Geom(dx = sign * (1f - p))
                // the outgoing clip in the overlap layer (above): only where the incoming one does not cover yet
                over -> Geom(mask = Mask(if (reverse) Mask.RIGHT_OF else Mask.LEFT_OF, if (reverse) p else 1f - p))
                else -> Geom()
            }
            // zoom (like Premiere's Cross Zoom, without the blur): into the outgoing clip up to the edit point, out of the incoming one after
            TransitionType.ZOOM -> Geom(scale = 1f + ZOOM_MAX * k * k)
        }
    }

    /** Whether the piece is visible at all (for the dips through a color and the zoom the overlap layer is hidden: they cut at the edit point). */
    val visible get() = !(over && (type == TransitionType.DIP_BLACK || type == TransitionType.DIP_WHITE || type == TransitionType.ZOOM))

    companion object {
        /** The zoom's largest magnification beyond 1 (at the edit point). */
        const val ZOOM_MAX = 3f

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
        private val program = GlProgram(TetraShaders.VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private var aspect = 16f / 9f

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth > 0 && inputHeight > 0) aspect = inputWidth.toFloat() / inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // unknown time: frame unchanged (like the other time-dependent effects – never an empty frame from a time error)
                // outside the transition's window: unchanged
                val g = time.seconds(presentationTimeUs)?.let { seg.geomAt(it) } ?: Geom()
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uOffset", floatArrayOf(g.dx, g.dy))
                program.setFloatUniform("uScale", g.scale)
                program.setFloatUniform("uMaskKind", (g.mask?.kind ?: 0).toFloat())
                program.setFloatUniform("uMaskV", g.mask?.v ?: 0f)
                program.setFloatUniform("uAspect", aspect)
                program.setFloatUniform("uTint", g.tint)
                program.setFloatUniform("uTintWhite", if (g.tintWhite) 1f else 0f)
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
    }
}
