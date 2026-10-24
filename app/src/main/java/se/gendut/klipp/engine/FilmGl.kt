package se.gendut.klipp.engine

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * The film (spektrafilm, [FilmFx]) on the GPU for playback and export: the same steps and tables as the
 * CPU path, in float textures (RGBA16F, OpenGL ES 3). If the phone has no float render targets, only the
 * color is applied (a baked LUT, as before) – better that than no image.
 *
 * [live] = (clip, node): the settings are read from [LiveGrades] every frame (playback), otherwise fixed (export).
 */
@UnstableApi
class FilmEffect(val film: Film, val live: Pair<Long, Int>? = null, val main: Boolean = false) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (FilmGl.floatTargets()) {
            try {
                return FilmGl.Program(film, live, useHdr, main)
            } catch (e: Exception) {
                se.gendut.klipp.CrashReport.note("film: GPU path could not start (${e.message}) – color only")
            }
        }
        // color only: spektrafilm's chain baked into a LUT (as the node did before the spatial effects); effects only:
        // nothing
        val p = if (film.effectsOnly) GradeNode(0).params() else GradeNode(0).params().let { n ->
            GradeNode.Params(n.lift, n.gammaExp, n.gain, n.offset, n.contrast, n.pivot, n.wb, n.sat, n.hueMat, 1f, null, Spektra.composite(null, 0f, film), 1f)
        }
        return GradeNodeEffect(p).toGlShaderProgram(context, useHdr)
    }

    override fun equals(other: Any?) = other is FilmEffect && other.film == film && other.live == live && other.main == main
    override fun hashCode() = (film.hashCode() * 31 + (live?.hashCode() ?: 0)) * 31 + main.hashCode()
}

@UnstableApi
object FilmGl {
    @Volatile private var probed: Boolean? = null

    /** EXT_disjoint_timer_query's GPU time query. */
    private const val GL_TIME_ELAPSED = 0x88BF

    /** Benchmarks only: each stage's GPU time (timer queries; waits for the results after each frame). Null in the app. */
    @Volatile var profile: ((String, Double) -> Unit)? = null

    /**
     * The full-resolution blurs of a pyramid in two passes (see [BLUR_H4], [BLUR_V_SUM]) instead of two per element
     * plus the sum. Off: the original pass per element (tests compare the two).
     */
    @Volatile var fusedBlur = true

    /** Can this context render to RGBA16F (OpenGL ES 3 + EXT_color_buffer_(half_)float)? Probed once. */
    fun floatTargets(): Boolean {
        probed?.let { return it }
        val ok = runCatching {
            val ver = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            if (!ver.contains("OpenGL ES 3")) return@runCatching false
            val tex = GlUtil.createTexture(16, 16, true)
            val fbo = GlUtil.createFboForTexture(tex)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            st == GLES20.GL_FRAMEBUFFER_COMPLETE
        }.getOrDefault(false)
        if (!ok) se.gendut.klipp.CrashReport.note("film: no float textures – color only (LUT)")
        probed = ok
        return ok
    }

    // ------------------------------------------------------------------------------------------- shaders

    private const val VS = """#version 300 es
in vec4 aPos;
void main() { gl_Position = aPos; }
"""

    /** The shaders' start without the single output (for the passes that write several images at once). */
    private const val HEAD_BASE = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
int reflectI(int i, int n) {
  if (i >= 0 && i < n) return i;
  int p = 2 * n;
  i = i % p;
  if (i < 0) i += p;
  return i >= n ? p - 1 - i : i;
}
ivec2 reflect2(ivec2 p, ivec2 n) { return ivec2(reflectI(p.x, n.x), reflectI(p.y, n.y)); }
"""

    private const val HEAD = HEAD_BASE + """out vec4 outColor;
"""

    /** The input (code values) → the film's raw exposure; the image is mirrored out to the padded size. */
    private const val EXPOSE = HEAD + """
uniform sampler2D uIn;
uniform ivec2 uInSize;
uniform sampler2D uDecode;
uniform float uDecodeN;
uniform sampler3D uLutE;
uniform float uLutN;
uniform float uEv;
float dec(float v) { return texture(uDecode, vec2((clamp(v, 0.0, 1.0) * (uDecodeN - 1.0) + 0.5) / uDecodeN, 0.5)).r; }
void main() {
  ivec2 p = reflect2(ivec2(gl_FragCoord.xy), uInSize);
  vec3 c = texelFetch(uIn, p, 0).rgb;
  vec3 lin = max(vec3(dec(c.r), dec(c.g), dec(c.b)), vec3(0.0));
  float m = max(lin.r, max(lin.g, lin.b));
  vec3 raw = vec3(0.0);
  if (m > 1e-12) {
    vec3 n = lin / m;
    raw = texture(uLutE, (n * (uLutN - 1.0) + 0.5) / uLutN).rgb * m * uEv;
  }
  outColor = vec4(max(raw, vec3(0.0)), 1.0);
}
"""

    /** Halving with the largest value (2×2) – the image's largest raw exposure, level by level down to 1×1. */
    private const val MAXHALF = HEAD + """
uniform sampler2D uSrc;
uniform ivec2 uSrcSize;
void main() {
  ivec2 q = ivec2(gl_FragCoord.xy) * 2;
  vec3 a = max(texelFetch(uSrc, reflect2(q, uSrcSize), 0).rgb, texelFetch(uSrc, reflect2(q + ivec2(1, 0), uSrcSize), 0).rgb);
  vec3 b = max(texelFetch(uSrc, reflect2(q + ivec2(0, 1), uSrcSize), 0).rgb, texelFetch(uSrc, reflect2(q + ivec2(1, 1), uSrcSize), 0).rgb);
  outColor = vec4(max(a, b), 1.0);
}
"""

    /** Highlight boost (spektrafilm's boost_highlights, like [FilmFx.boost]) with the image's largest raw exposure. */
    private const val BOOST = HEAD + """
uniform sampler2D uSrc;
uniform sampler2D uMax;
uniform float uBoostEv;
uniform float uProtectEv;
uniform float uRange;
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  vec3 x = texelFetch(uSrc, p, 0).rgb;
  vec3 m3 = texelFetch(uMax, ivec2(0, 0), 0).rgb;
  float mx = max(m3.r, max(m3.g, m3.b));
  float x0 = clamp(0.184 * exp2(uProtectEv), 0.0, mx);
  if (mx > 0.0 && x0 < mx) {
    float a = pow(28.0, 1.0 - uRange);
    float nx0 = x0 / mx;
    float den = exp(a * (1.0 - nx0)) - a * (1.0 - nx0) - 1.0;
    if (den > 0.0) {
      float scale = (exp2(uBoostEv) - 1.0) / den * mx;
      vec3 dx = max(x - vec3(x0), vec3(0.0)) / mx;
      vec3 b = scale * (exp(a * dx) - a * dx - vec3(1.0));
      x = mix(x, x + b, step(vec3(x0), x) * (1.0 - step(x, vec3(x0))));
    }
  }
  outColor = vec4(x, 1.0);
}
"""

    /** Separable Gaussian (mirrored edges). With uAcc: the result × uW is added to the accumulator. */
    private const val BLUR = HEAD + """
uniform sampler2D uSrc;
uniform ivec2 uSrcSize;
uniform ivec2 uDir;
uniform int uR;
uniform float uK[24];
uniform int uUseAcc;
uniform sampler2D uAcc;
uniform vec3 uW;
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  vec3 s = vec3(0.0);
  for (int t = 0; t <= 2 * uR; t++) s += uK[t] * texelFetch(uSrc, reflect2(p + uDir * (t - uR), uSrcSize), 0).rgb;
  if (uUseAcc == 1) s = texelFetch(uAcc, p, 0).rgb + uW * s;
  outColor = vec4(s, 1.0);
}
"""

    /** Vertical Gaussian and halving in one: 2×2 mean of four vertical blurs (the start of the next level). */
    private const val BLUR_V_HALF = HEAD + """
uniform sampler2D uSrc;
uniform ivec2 uSrcSize;
uniform int uR;
uniform float uK[24];
vec3 vblur(ivec2 p) {
  vec3 s = vec3(0.0);
  for (int t = 0; t <= 2 * uR; t++) s += uK[t] * texelFetch(uSrc, reflect2(p + ivec2(0, t - uR), uSrcSize), 0).rgb;
  return s;
}
void main() {
  ivec2 q = ivec2(gl_FragCoord.xy) * 2;
  outColor = vec4(0.25 * (vblur(q) + vblur(q + ivec2(1, 0)) + vblur(q + ivec2(0, 1)) + vblur(q + ivec2(1, 1))), 1.0);
}
"""

    /** Halving (2×2 mean) of a level. */
    private const val HALF = HEAD + """
uniform sampler2D uSrc;
uniform ivec2 uSrcSize;
void main() {
  ivec2 q = ivec2(gl_FragCoord.xy) * 2;
  vec3 s = texelFetch(uSrc, reflect2(q, uSrcSize), 0).rgb + texelFetch(uSrc, reflect2(q + ivec2(1, 0), uSrcSize), 0).rgb
         + texelFetch(uSrc, reflect2(q + ivec2(0, 1), uSrcSize), 0).rgb + texelFetch(uSrc, reflect2(q + ivec2(1, 1), uSrcSize), 0).rgb;
  outColor = vec4(0.25 * s, 1.0);
}
"""

    /** A level's sum: the coarser level's sum upscaled (bilinearly) + weight × element (up to two). */
    private const val LEVEL = HEAD + """
uniform int uUseUp;
uniform sampler2D uUp;
uniform vec2 uUpSize;
uniform int uN;
uniform sampler2D uE0;
uniform sampler2D uE1;
uniform vec3 uW0;
uniform vec3 uW1;
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  vec3 s = vec3(0.0);
  if (uUseUp == 1) s = texture(uUp, (vec2(p) + 0.5) * 0.5 / uUpSize).rgb;
  if (uN > 0) s += uW0 * texelFetch(uE0, p, 0).rgb;
  if (uN > 1) s += uW1 * texelFetch(uE1, p, 0).rgb;
  outColor = vec4(s, 1.0);
}
"""

    /**
     * The horizontal Gaussians of up to four elements in one pass (one image per element): the source is read once
     * per tap. Each element's taps sit centered in its array (zeros outside its radius), so every element's sum is
     * exactly the one [BLUR] makes.
     */
    private const val BLUR_H4 = HEAD_BASE + """
layout(location = 0) out vec4 o0;
layout(location = 1) out vec4 o1;
layout(location = 2) out vec4 o2;
layout(location = 3) out vec4 o3;
uniform sampler2D uSrc;
uniform ivec2 uSrcSize;
uniform int uR;
uniform float uK0[24];
uniform float uK1[24];
uniform float uK2[24];
uniform float uK3[24];
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  vec3 s0 = vec3(0.0); vec3 s1 = vec3(0.0); vec3 s2 = vec3(0.0); vec3 s3 = vec3(0.0);
  for (int t = 0; t <= 2 * uR; t++) {
    vec3 v = texelFetch(uSrc, reflect2(p + ivec2(t - uR, 0), uSrcSize), 0).rgb;
    s0 += uK0[t] * v; s1 += uK1[t] * v; s2 += uK2[t] * v; s3 += uK3[t] * v;
  }
  o0 = vec4(s0, 1.0); o1 = vec4(s1, 1.0); o2 = vec4(s2, 1.0); o3 = vec4(s3, 1.0);
}
"""

    /**
     * Full resolution in one pass: the coarser sum upscaled + weight × the image + each element's vertical Gaussian
     * (of its horizontal image from [BLUR_H4]) × its weight – what [LEVEL] and one [BLUR] per element did, summed in
     * 32 bits instead of being stored at 16 bits after every element.
     */
    private const val BLUR_V_SUM = HEAD + """
uniform sampler2D uSrc;
uniform ivec2 uSrcSize;
uniform vec3 uW0;
uniform int uUseUp;
uniform sampler2D uUp;
uniform vec2 uUpSize;
uniform int uN;
uniform sampler2D uH0;
uniform sampler2D uH1;
uniform sampler2D uH2;
uniform sampler2D uH3;
uniform int uR0;
uniform int uR1;
uniform int uR2;
uniform int uR3;
uniform float uK0[24];
uniform float uK1[24];
uniform float uK2[24];
uniform float uK3[24];
uniform vec3 uWe0;
uniform vec3 uWe1;
uniform vec3 uWe2;
uniform vec3 uWe3;
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  vec3 s = vec3(0.0);
  if (uUseUp == 1) s = texture(uUp, (vec2(p) + 0.5) * 0.5 / uUpSize).rgb;
  s += uW0 * texelFetch(uSrc, p, 0).rgb;
  if (uN > 0) { vec3 v = vec3(0.0); for (int t = 0; t <= 2 * uR0; t++) v += uK0[t] * texelFetch(uH0, reflect2(p + ivec2(0, t - uR0), uSrcSize), 0).rgb; s += uWe0 * v; }
  if (uN > 1) { vec3 v = vec3(0.0); for (int t = 0; t <= 2 * uR1; t++) v += uK1[t] * texelFetch(uH1, reflect2(p + ivec2(0, t - uR1), uSrcSize), 0).rgb; s += uWe1 * v; }
  if (uN > 2) { vec3 v = vec3(0.0); for (int t = 0; t <= 2 * uR2; t++) v += uK2[t] * texelFetch(uH2, reflect2(p + ivec2(0, t - uR2), uSrcSize), 0).rgb; s += uWe2 * v; }
  if (uN > 3) { vec3 v = vec3(0.0); for (int t = 0; t <= 2 * uR3; t++) v += uK3[t] * texelFetch(uH3, reflect2(p + ivec2(0, t - uR3), uSrcSize), 0).rgb; s += uWe3 * v; }
  outColor = vec4(s, 1.0);
}
"""

    private const val CURVES = """
uniform sampler2D uCurve;
uniform float uCurveN;
uniform vec2 uLe;
float curveAt(float row, int c, float v) {
  float x = clamp((v - uLe.x) / (uLe.y - uLe.x), 0.0, 1.0) * (uCurveN - 1.0);
  vec3 t = texture(uCurve, vec2((x + 0.5) / uCurveN, (row + 0.5) / 2.0)).rgb;
  return c == 0 ? t.r : (c == 1 ? t.g : t.b);
}
vec3 curves(float row, vec3 le) { return vec3(curveAt(row, 0, le.r), curveAt(row, 1, le.g), curveAt(row, 2, le.b)); }
"""

    /** The couplers' inhibition: density without couplers → silver → the inhibition per receiving layer. */
    private const val DEVELOP1 = HEAD + CURVES + """
uniform sampler2D uRaw;
uniform mat3 uM;
uniform int uPositive;
uniform vec3 uDmax;
void main() {
  vec3 le = log(max(texelFetch(uRaw, ivec2(gl_FragCoord.xy), 0).rgb, vec3(0.0)) + 1e-10) / log(10.0);
  vec3 d = curves(0.0, le);
  vec3 silver = uPositive == 1 ? uDmax - d : d;
  outColor = vec4(uM * silver, 1.0);
}
"""

    /** Development with the couplers (their diffused inhibition) and the grain. */
    private const val DEVELOP2 = HEAD + CURVES + """
uniform sampler2D uRaw;
uniform int uCouplers;
uniform sampler2D uCorr;
uniform int uGrain;
uniform sampler2D uLayer;
uniform float uLayerN;
uniform vec3 uDmax;
uniform vec3 uN[3];
uniform vec3 uOd[3];
uniform vec3 uLo[3];
uniform vec3 uHi[3];
uniform vec3 uUniformity;
uniform vec3 uGrainDmin;
uniform float uChroma;
uniform float uGrainAmount;
uniform uint uSeed;
uint st;
uint pcg(uint v) { uint s = v * 747796405u + 2891336453u; uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u; return (w >> 22u) ^ w; }
float uni() { st = pcg(st); return (float(st >> 8u) + 0.5) / 16777216.0; }
float normal() { return sqrt(-2.0 * log(uni())) * cos(6.283185307179586 * uni()); }
float poisson(float mu) {
  if (mu <= 0.0) return 0.0;
  if (mu < 6.0) {
    float l = exp(-mu);
    float k = 0.0;
    float p = uni();
    for (int i = 0; i < 64; i++) { if (p <= l) break; k += 1.0; p *= uni(); }
    return k;
  }
  return max(0.0, floor(mu + sqrt(mu) * normal() + 0.5));
}
float layerAt(int c, int l, float d) {
  float dm = c == 0 ? uDmax.r : (c == 1 ? uDmax.g : uDmax.b);
  float x = dm > 0.0 ? clamp(d / dm, 0.0, 1.0) * (uLayerN - 1.0) : 0.0;
  return texture(uLayer, vec2((x + 0.5) / uLayerN, (float(c * 3 + l) + 0.5) / 9.0)).r;
}
float comp(vec3 v, int c) { return c == 0 ? v.r : (c == 1 ? v.g : v.b); }
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  vec3 le = log(max(texelFetch(uRaw, p, 0).rgb, vec3(0.0)) + 1e-10) / log(10.0);
  vec3 d;
  if (uCouplers == 1) d = curves(1.0, le - texelFetch(uCorr, p, 0).rgb); else d = curves(0.0, le);
  if (uGrain == 1) {
    st = pcg(uint(p.x) + pcg(uint(p.y) + pcg(uSeed)));
    vec3 g = vec3(0.0);
    for (int c = 0; c < 3; c++) {
      float di = comp(d, c);
      float sum = 0.0;
      for (int l = 0; l < 3; l++) {
        float n = comp(uN[l], c);
        if (n <= 0.0) continue;
        float dl = layerAt(c, l, di) + comp(uLo[l], c);
        float pr = clamp(dl / comp(uHi[l], c), 1e-6, 1.0 - 1e-6);
        float sat = 1.0 - pr * comp(uUniformity, c) * (1.0 - 1e-6);
        sum += poisson(n * pr / sat) * comp(uOd[l], c) * sat;
      }
      g[c] = sum - comp(uGrainDmin, c);
    }
    // grain color: the noise is pulled toward its mean over the channels; the amount amplifies it
    vec3 nz = g - d;
    float m = (nz.r + nz.g + nz.b) / 3.0;
    d = d + uGrainAmount * (vec3(m) + uChroma * (nz - vec3(m)));
  }
  outColor = vec4(d, 1.0);
}
"""

    /** Printing and scanning → linear sRGB (+ glare in the paper). */
    private const val PRINT = HEAD + """
uniform sampler2D uD;
uniform int uPrinting;
uniform sampler3D uLutP;
uniform float uPrintOff;
uniform vec3 uPLo;
uniform vec3 uPHi;
uniform float uLutN;
uniform sampler2D uPrintCurve;
uniform float uCurveN;
uniform vec2 uPrLe;
uniform sampler3D uScan;
uniform float uScanN;
uniform vec3 uScanLo;
uniform vec3 uScanHi;
uniform int uGlare;
uniform vec2 uGlareLn;
uniform uint uSeed;
uniform int uMode;
uint pcg(uint v) { uint s = v * 747796405u + 2891336453u; uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u; return (w >> 22u) ^ w; }
vec3 fracOf(vec3 v, vec3 lo, vec3 hi) { return clamp((v - lo) / (hi - lo), 0.0, 1.0); }
float pc(int c, float v) {
  float x = clamp((v - uPrLe.x) / (uPrLe.y - uPrLe.x), 0.0, 1.0) * (uCurveN - 1.0);
  vec3 t = texture(uPrintCurve, vec2((x + 0.5) / uCurveN, 0.5)).rgb;
  return c == 0 ? t.r : (c == 1 ? t.g : t.b);
}
float dec(float v) { return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4); }
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  vec3 c = texelFetch(uD, p, 0).rgb;
  if (uPrinting == 1) {
    // uMode 1: uD is the paper's exposure (linear, blurred by the enlarger's filter); 2: write it and stop
    vec3 le = uMode == 1 ? log(max(c, vec3(0.0)) + 1e-10) / log(10.0)
                         : texture(uLutP, (fracOf(c, uPLo, uPHi) * (uLutN - 1.0) + 0.5) / uLutN).rgb + vec3(uPrintOff);
    if (uMode == 2) { outColor = vec4(exp2(le * log2(10.0)), 1.0); return; }
    c = vec3(pc(0, le.r), pc(1, le.g), pc(2, le.b));
  }
  vec3 e = texture(uScan, (fracOf(c, uScanLo, uScanHi) * (uScanN - 1.0) + 0.5) / uScanN).rgb;
  vec3 lin = vec3(dec(e.r), dec(e.g), dec(e.b));
  if (uGlare == 1) {
    uint s = pcg(uint(p.x) * 7919u + pcg(uint(p.y) * 104729u + pcg(uSeed ^ 0x9e3779b9u)));
    s = pcg(s); float u1 = (float(s >> 8u) + 0.5) / 16777216.0;
    s = pcg(s); float u2 = (float(s >> 8u) + 0.5) / 16777216.0;
    float z = sqrt(-2.0 * log(u1)) * cos(6.283185307179586 * u2);
    lin += vec3(exp(uGlareLn.x + uGlareLn.y * z) / 100.0);
  }
  outColor = vec4(lin, 1.0);
}
"""

    /** Last: the scanner's sharpening (vertical half of the blur here), the display encoding. */
    private const val FINAL = HEAD + """
uniform sampler2D uL;
uniform int uSharp;
uniform sampler2D uH;
uniform ivec2 uSrcSize;
uniform int uR;
uniform float uK[24];
uniform float uAmount;
uniform int uDisplay;
// the image's own alpha (green screen before the grade) – the film steps only process color
uniform sampler2D uA;
// effects only (FilmFx.keepColors): the input × the film's output / the film's colors alone (uLut), in linear light
uniform int uKeep;
uniform sampler3D uLut;
uniform float uLutN;
uniform sampler2D uDecode;
uniform float uDecodeN;
uniform sampler2D uEncode;
uniform float uEncodeN;
uniform float uLinMax;
float enc(float v) { return v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1.0 / 2.4) - 0.055; }
float dsp(float v) { return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4); }
float tab(sampler2D t, float n, float x) { return texture(t, vec2((clamp(x, 0.0, 1.0) * (n - 1.0) + 0.5) / n, 0.5)).r; }
vec3 encodeLin(vec3 lin) {
  vec3 u = sqrt(sqrt(clamp(lin / uLinMax, 0.0, 1.0)));
  return vec3(tab(uEncode, uEncodeN, u.r), tab(uEncode, uEncodeN, u.g), tab(uEncode, uEncodeN, u.b));
}
vec3 keepColors(vec3 l, vec3 c) {
  vec3 f0 = clamp(texture(uLut, (clamp(c, 0.0, 1.0) * (uLutN - 1.0) + 0.5) / uLutN).rgb, 0.0, 1.0);
  vec3 l0 = uDisplay == 1 ? pow(f0, vec3(2.4)) : vec3(dsp(f0.r), dsp(f0.g), dsp(f0.b));
  vec3 r = clamp((l + vec3(0.002)) / (l0 + vec3(0.002)), 0.0, 16.0);
  vec3 lin = vec3(tab(uDecode, uDecodeN, c.r), tab(uDecode, uDecodeN, c.g), tab(uDecode, uDecodeN, c.b));
  // as a change in code values (below a log curve's black the code stays)
  return c + encodeLin(lin * r) - encodeLin(lin);
}
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  vec3 l = texelFetch(uL, p, 0).rgb;
  if (uSharp == 1) {
    vec3 b = vec3(0.0);
    for (int t = 0; t <= 2 * uR; t++) b += uK[t] * texelFetch(uH, reflect2(p + ivec2(0, t - uR), uSrcSize), 0).rgb;
    l = l + uAmount * (l - b);
  }
  l = clamp(l, 0.0, 1.0);
  vec4 src = texelFetch(uA, p, 0);
  vec3 o = uKeep == 1 ? keepColors(l, src.rgb) : (uDisplay == 1 ? pow(l, vec3(1.0 / 2.4)) : vec3(enc(l.r), enc(l.g), enc(l.b)));
  outColor = vec4(o, src.a);
}
"""

    /** Fallback: color only (spektrafilm's chain baked into a LUT), if the float path fails on the phone. */
    private const val COLOR_ONLY = HEAD + """
uniform sampler2D uIn;
uniform sampler3D uLut;
uniform float uLutN;
// effects only: the image unchanged
uniform int uPass;
void main() {
  vec4 c = texelFetch(uIn, ivec2(gl_FragCoord.xy), 0);
  outColor = uPass == 1 ? c : vec4(texture(uLut, (clamp(c.rgb, 0.0, 1.0) * (uLutN - 1.0) + 0.5) / uLutN).rgb, c.a);
}
"""

    private const val COPY = HEAD + """
uniform sampler2D uIn;
void main() { outColor = texelFetch(uIn, ivec2(gl_FragCoord.xy), 0); }
"""

    // ------------------------------------------------------------------------------------------- GL helpers

    private class Prog(fs: String) {
        val id: Int
        private val loc = HashMap<String, Int>()
        private var unit = 0

        init {
            val v = compile(GLES20.GL_VERTEX_SHADER, VS)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, v); GLES20.glAttachShader(id, f)
            GLES20.glBindAttribLocation(id, 0, "aPos")
            GLES20.glLinkProgram(id)
            val st = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, st, 0)
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            if (st[0] != GLES20.GL_TRUE) { val log = GLES20.glGetProgramInfoLog(id); GLES20.glDeleteProgram(id); throw IllegalStateException("film: link: $log") }
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val st = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
            if (st[0] != GLES20.GL_TRUE) { val log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw IllegalStateException("film: shader: $log") }
            return s
        }

        private fun l(name: String) = loc.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

        fun use() { GLES20.glUseProgram(id); unit = 0 }
        fun i1(n: String, v: Int) = GLES20.glUniform1i(l(n), v)
        fun i2(n: String, a: Int, b: Int) = GLES20.glUniform2i(l(n), a, b)
        fun u1(n: String, v: Int) = GLES30.glUniform1ui(l(n), v)
        fun f1(n: String, v: Float) = GLES20.glUniform1f(l(n), v)
        fun f2(n: String, a: Float, b: Float) = GLES20.glUniform2f(l(n), a, b)
        fun f3(n: String, a: Float, b: Float, c: Float) = GLES20.glUniform3f(l(n), a, b, c)
        fun f3v(n: String, v: FloatArray) = GLES20.glUniform3fv(l(n), v.size / 3, v, 0)
        fun fv(n: String, v: FloatArray) = GLES20.glUniform1fv(l(n), v.size, v, 0)
        fun mat3(n: String, m: FloatArray) = GLES20.glUniformMatrix3fv(l(n), 1, false, m, 0)
        fun tex(n: String, target: Int, texId: Int) {
            val u = unit++
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + u)
            GLES20.glBindTexture(target, texId)
            GLES20.glUniform1i(l(n), u)
        }
        fun delete() = GLES20.glDeleteProgram(id)
    }

    /** A float image to render into. */
    private class Tex(val w: Int, val h: Int) {
        val id: Int = GlUtil.createTexture(w, h, true)
        val fbo: Int = GlUtil.createFboForTexture(id)
        init {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        fun delete() { GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); GLES20.glDeleteTextures(1, intArrayOf(id), 0) }
    }

    // Half.toHalf gives the half-float bit pattern, which is exactly what the 16-bit texture upload expects
    @android.annotation.SuppressLint("HalfFloat")
    private fun halfBuf(v: FloatArray, comps: Int, outComps: Int): ShortBuffer {
        val n = v.size / comps
        val b = ByteBuffer.allocateDirect(n * outComps * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in 0 until n) for (c in 0 until outComps) b.put(android.util.Half.toHalf(if (c < comps) v[i * comps + c] else 1f))
        b.flip()
        return b
    }

    private fun tex3(data: FloatArray, n: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, ids[0])
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, n, n, n, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, halfBuf(data, 3, 4))
        return ids[0]
    }

    /** A table (width × rows) as a 2D float texture, linearly filtered. */
    private fun tex2(data: FloatArray, w: Int, h: Int, comps: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, halfBuf(data, comps, 4))
        return ids[0]
    }

    private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); flip()
    }

    // ------------------------------------------------------------------------------------------- the program

    /** [main]: in the main player's chain (see Occlusion.hideMain). */
    class Program(private var film: Film, private val live: Pair<Long, Int>?, useHdr: Boolean, private val main: Boolean = false) : BaseGlShaderProgram(useHdr, 1) {
        private val expose = Prog(EXPOSE)
        private val blur = Prog(BLUR)
        private val blurVHalf = Prog(BLUR_V_HALF)
        private val half = Prog(HALF)
        private val level = Prog(LEVEL)
        private val develop1 = Prog(DEVELOP1)
        private val develop2 = Prog(DEVELOP2)
        private val print = Prog(PRINT)
        private val final = Prog(FINAL)
        private val maxHalf = Prog(MAXHALF)
        private val boostP = Prog(BOOST)
        private val blurH4 = Prog(BLUR_H4)
        private val blurVSum = Prog(BLUR_V_SUM)
        /** A framebuffer with up to four images attached (for [BLUR_H4]). */
        private val mrtFbo: Int = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        /** How many images one pass can write (OpenGL ES 3 guarantees four). */
        private val maxDraw: Int = IntArray(1).also { GLES20.glGetIntegerv(GLES30.GL_MAX_DRAW_BUFFERS, it, 0) }[0]

        private var setup: FilmFx.Setup = FilmFx.setup(film)
        /** The float path failed at runtime (e.g. out of GPU memory): color only from here on. */
        private var broken = false
        private var colorOnly: Prog? = null
        private var tColor = 0
        private var colorFor: Film? = null
        // the tables on the GPU (reloaded when the settings change)
        private var tLutE = 0; private var tDecode = 0; private var tCurve = 0; private var tLayer = 0
        private var tLutP = 0; private var tPrintCurve = 0; private var tScan = 0
        /** Linear → input code value (effects only), made when first needed. */
        private var tEncode = 0

        private var w = 0; private var h = 0
        private var pw = 0; private var ph = 0
        private var frame: FilmFx.Frame? = null
        /** Five images at (padded) full resolution and the coarser levels' images (per level k: accumulator, element, intermediate). */
        private val full = ArrayList<Tex>()
        private val levels = HashMap<String, Tex>()

        init { upload() }

        private fun upload() {
            deleteTables()
            val s = setup
            tLutE = tex3(s.lutE, FilmFx.LUT_N)
            tDecode = tex2(FloatArray(FilmFx.DECODE_N * 3) { s.decode[it / 3] }, FilmFx.DECODE_N, 1, 3)
            tCurve = tex2(s.curve + s.curve0, FilmFx.CURVE_N, 2, 3)
            tLayer = tex2(FloatArray(9 * FilmFx.LAYER_N * 3) { s.layerTab[it / 3] }, FilmFx.LAYER_N, 9, 3)
            s.lutP?.let { tLutP = tex3(it, FilmFx.LUT_N) }
            s.printCurve?.let { tPrintCurve = tex2(it, FilmFx.CURVE_N, 1, 3) }
            tScan = tex3(s.scan, s.scanN)
        }

        private fun deleteTables() {
            val ids = intArrayOf(tLutE, tDecode, tCurve, tLayer, tLutP, tPrintCurve, tScan, tEncode).filter { it != 0 }.toIntArray()
            if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
            tLutE = 0; tDecode = 0; tCurve = 0; tLayer = 0; tLutP = 0; tPrintCurve = 0; tScan = 0; tEncode = 0
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth != w || inputHeight != h) { w = inputWidth; h = inputHeight; frame = null; freeImages() }
            PassTimes.noteSize(inputWidth, inputHeight)
            return Size(inputWidth, inputHeight)
        }

        private fun freeImages() {
            full.forEach { it.delete() }; full.clear()
            levels.values.forEach { it.delete() }; levels.clear()
            pw = 0; ph = 0
        }

        private fun lv(name: String, k: Int): Tex {
            val d = FilmFx.Pyramid.dims(pw, ph, k)
            return levels.getOrPut("$name$k") { Tex(d[0], d[1]) }
        }

        private fun prepare() {
            if (frame != null) return
            FilmFx.noteSize(w, h)
            val fr = setup.forImage(w, h)
            frame = fr
            val shift = maxOf(FilmFx.Pyramid.padShift(fr.camera), fr.couplers?.let { FilmFx.Pyramid.padShift(it) } ?: 0, fr.enlarger?.let { FilmFx.Pyramid.padShift(it) } ?: 0)
            val step = 1 shl shift
            val nw = (w + step - 1) / step * step; val nh = (h + step - 1) / step * step
            if (nw != pw || nh != ph) {
                freeImages()
                pw = nw; ph = nh
                // five for the chain; a sixth for the fused pyramid (it keeps each full-resolution element's horizontal
                // image until the sum)
                repeat(6) { full += Tex(pw, ph) }
            }
        }

        private fun target(t: Tex) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
            GLES20.glViewport(0, 0, t.w, t.h)
        }

        private fun draw() {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun taps(p: Prog, k: FloatArray) {
            val a = FloatArray(24); k.copyInto(a)
            p.i1("uR", k.size / 2)
            p.fv("uK", a)
        }

        /** Separable blur [src] → [dst] (horizontal and vertical via [tmp]); with [acc]/[out] weight × result is added. */
        private fun blurPass(src: Tex, dst: Tex, dir: Int, k: FloatArray, acc: Tex? = null, wt: FloatArray? = null) {
            target(dst)
            blur.use()
            blur.tex("uSrc", GLES20.GL_TEXTURE_2D, src.id)
            blur.i2("uSrcSize", src.w, src.h)
            blur.i2("uDir", if (dir == 0) 1 else 0, if (dir == 0) 0 else 1)
            taps(blur, k)
            blur.i1("uUseAcc", if (acc != null) 1 else 0)
            blur.tex("uAcc", GLES20.GL_TEXTURE_2D, (acc ?: src).id)
            blur.f3("uW", wt?.get(0) ?: 1f, wt?.get(1) ?: 1f, wt?.get(2) ?: 1f)
            draw()
        }

        private fun wOf(fit: FilmFx.Fit, j: Int) = floatArrayOf(fit.w[0][j], fit.w[1][j], fit.w[2][j])
        private fun used(fit: FilmFx.Fit, e: Int) = e < fit.count && (0..2).any { fit.w[it][e + 1] != 0f }

        /**
         * The kernel [fit] on [src] (full resolution, padded). [tmp], [a], [b] = free images at full resolution.
         * The result ends up in [a] or [b] (returned). The same order and math as [FilmFx.Pyramid].
         */
        /** The kernel [fit] on [src] (full resolution, padded) using images from [pool] (not [src]); returns one of them. */
        private fun pyramid(src: Tex, fit: FilmFx.Fit, pool: List<Tex>, tag: String): Tex =
            (if (fusedBlur) pyramidFused(src, fit, pool, tag) else null) ?: pyramidSplit(src, fit, pool[0], pool[1], pool[2], tag)

        /**
         * [pyramidSplit] with the full-resolution work in two passes: [BLUR_H4] writes the horizontal Gaussian of every
         * full-resolution element (and of the coarse chain's start, which [pyramidSplit] computed a second time) into
         * images of their own, [BLUR_V_SUM] adds their vertical Gaussians, the image and the coarser sum. The coarse
         * levels are as before. Null when it doesn't apply (more than four such elements, too few free images).
         */
        /** Benchmarks: marks a stage inside a pyramid (set by drawFilm while profiling). */
        private var subMark: ((String) -> Unit)? = null

        private fun pyramidFused(src: Tex, fit: FilmFx.Fit, pool: List<Tex>, tag: String): Tex? {
            val els = FilmFx.Pyramid.ELS
            val need = FilmFx.Pyramid.needed(fit)
            val k0 = (0 until fit.count).filter { els[it].k == 0 && used(fit, it) }
            // the coarse chain starts from a full-resolution element's Gaussian (blurred, then halved)
            val chainParents = els.indices.filter { need[it] && els[it].k > 0 && els[it].decimate && els[els[it].parent].k == 0 }.map { els[it].parent }
            val hEls = (k0 + chainParents).distinct()
            if (k0.size > 4 || hEls.size > 4 || hEls.size > maxDraw || pool.size < hEls.size + 1) return null
            val hTex = HashMap<Int, Tex>()
            hEls.forEachIndexed { i, e -> hTex[e] = pool[i] }
            val out = pool[hEls.size]
            // 1. the horizontal Gaussians, all at once
            if (hEls.isNotEmpty()) {
                val r = hEls.maxOf { FilmFx.Pyramid.TAPS[it].size / 2 }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mrtFbo)
                for (i in 0 until 4) GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0 + i, GLES20.GL_TEXTURE_2D, if (i < hEls.size) hTex.getValue(hEls[i]).id else 0, 0)
                GLES30.glDrawBuffers(hEls.size, IntArray(hEls.size) { GLES20.GL_COLOR_ATTACHMENT0 + it }, 0)
                GLES20.glViewport(0, 0, src.w, src.h)
                blurH4.use()
                blurH4.tex("uSrc", GLES20.GL_TEXTURE_2D, src.id)
                blurH4.i2("uSrcSize", src.w, src.h)
                blurH4.i1("uR", r)
                for (i in 0 until 4) {
                    val a = FloatArray(24)
                    if (i < hEls.size) { val k = FilmFx.Pyramid.TAPS[hEls[i]]; k.copyInto(a, r - k.size / 2) }
                    blurH4.fv("uK$i", a)
                }
                draw()
                // (back to a single output for the other passes on this framebuffer)
                GLES30.glDrawBuffers(1, intArrayOf(GLES20.GL_COLOR_ATTACHMENT0), 0)
                subMark?.invoke("$tag:h4")
            }
            // 2. the coarser levels: the elements (the chain) level by level, as in pyramidSplit
            val maxK = (0 until fit.count).filter { used(fit, it) }.maxOfOrNull { els[it].k } ?: 0
            for (e in els.indices) {
                if (!need[e]) continue
                val el = els[e]
                if (el.k == 0) continue
                val parent = el.parent
                val pk = els[parent].k
                val base: Tex
                if (el.decimate) {
                    base = lv("${tag}half", el.k)
                    if (pk == 0) {
                        val h = hTex.getValue(parent)
                        target(base)
                        blurVHalf.use()
                        blurVHalf.tex("uSrc", GLES20.GL_TEXTURE_2D, h.id)
                        blurVHalf.i2("uSrcSize", h.w, h.h)
                        taps(blurVHalf, FilmFx.Pyramid.TAPS[parent])
                        draw()
                    } else {
                        val pt = lv("${tag}e$parent", pk)
                        target(base)
                        half.use()
                        half.tex("uSrc", GLES20.GL_TEXTURE_2D, pt.id)
                        half.i2("uSrcSize", pt.w, pt.h)
                        draw()
                    }
                } else base = lv("${tag}e$parent", pk)
                val t = lv("${tag}tmp", el.k)
                val o = lv("${tag}e$e", el.k)
                blurPass(base, t, 0, FilmFx.Pyramid.TAPS[e])
                blurPass(t, o, 1, FilmFx.Pyramid.TAPS[e])
            }
            // 3. the coarser sum, bottom-up, as in pyramidSplit
            var acc: Tex? = null
            for (k in maxK downTo 1) {
                val es = (0 until fit.count).filter { els[it].k == k && used(fit, it) }
                val lo = lv("${tag}acc", k)
                target(lo)
                level.use()
                level.i1("uUseUp", if (acc != null) 1 else 0)
                level.tex("uUp", GLES20.GL_TEXTURE_2D, (acc ?: lo).id)
                level.f2("uUpSize", (acc ?: lo).w.toFloat(), (acc ?: lo).h.toFloat())
                level.i1("uN", es.size)
                level.tex("uE0", GLES20.GL_TEXTURE_2D, es.getOrNull(0)?.let { lv("${tag}e$it", k).id } ?: lo.id)
                level.tex("uE1", GLES20.GL_TEXTURE_2D, es.getOrNull(1)?.let { lv("${tag}e$it", k).id } ?: lo.id)
                es.getOrNull(0)?.let { val wv = wOf(fit, it + 1); level.f3("uW0", wv[0], wv[1], wv[2]) }
                es.getOrNull(1)?.let { val wv = wOf(fit, it + 1); level.f3("uW1", wv[0], wv[1], wv[2]) }
                draw()
                acc = lo
            }
            subMark?.invoke("$tag:coarse")
            // 4. full resolution in one pass
            target(out)
            blurVSum.use()
            blurVSum.tex("uSrc", GLES20.GL_TEXTURE_2D, src.id)
            blurVSum.i2("uSrcSize", src.w, src.h)
            val w0 = wOf(fit, 0); blurVSum.f3("uW0", w0[0], w0[1], w0[2])
            blurVSum.i1("uUseUp", if (acc != null) 1 else 0)
            blurVSum.tex("uUp", GLES20.GL_TEXTURE_2D, (acc ?: src).id)
            blurVSum.f2("uUpSize", (acc ?: src).w.toFloat(), (acc ?: src).h.toFloat())
            blurVSum.i1("uN", k0.size)
            for (i in 0 until 4) {
                val e = k0.getOrNull(i)
                blurVSum.tex("uH$i", GLES20.GL_TEXTURE_2D, (e?.let { hTex.getValue(it) } ?: src).id)
                val k = e?.let { FilmFx.Pyramid.TAPS[it] } ?: floatArrayOf(1f)
                val a = FloatArray(24); k.copyInto(a)
                blurVSum.i1("uR$i", k.size / 2)
                blurVSum.fv("uK$i", a)
                val wv = e?.let { wOf(fit, it + 1) } ?: floatArrayOf(0f, 0f, 0f)
                blurVSum.f3("uWe$i", wv[0], wv[1], wv[2])
            }
            draw()
            subMark?.invoke("$tag:vsum")
            return out
        }

        /** The kernel [fit] on [src] one pass per element (the original order, see [pyramidFused]). */
        private fun pyramidSplit(src: Tex, fit: FilmFx.Fit, tmp: Tex, a: Tex, b: Tex, tag: String): Tex {
            val els = FilmFx.Pyramid.ELS
            val need = FilmFx.Pyramid.needed(fit)
            val maxK = (0 until fit.count).filter { used(fit, it) }.maxOfOrNull { els[it].k } ?: 0
            // coarser levels: the elements (the chain) level by level
            for (e in els.indices) {
                if (!need[e]) continue
                val el = els[e]
                if (el.k == 0) continue
                val parent = el.parent
                val pk = els[parent].k
                val base: Tex
                if (el.decimate) {
                    base = lv("${tag}half", el.k)
                    if (pk == 0) {
                        // the parent (σ = 2 at full resolution) is blurred and halved in one: horizontal into tmp, vertical + 2×2
                        blurPass(src, tmp, 0, FilmFx.Pyramid.TAPS[parent])
                        target(base)
                        blurVHalf.use()
                        blurVHalf.tex("uSrc", GLES20.GL_TEXTURE_2D, tmp.id)
                        blurVHalf.i2("uSrcSize", tmp.w, tmp.h)
                        taps(blurVHalf, FilmFx.Pyramid.TAPS[parent])
                        draw()
                    } else {
                        val pt = lv("${tag}e$parent", pk)
                        target(base)
                        half.use()
                        half.tex("uSrc", GLES20.GL_TEXTURE_2D, pt.id)
                        half.i2("uSrcSize", pt.w, pt.h)
                        draw()
                    }
                } else base = lv("${tag}e$parent", pk)
                val t = lv("${tag}tmp", el.k)
                val out = lv("${tag}e$e", el.k)
                blurPass(base, t, 0, FilmFx.Pyramid.TAPS[e])
                blurPass(t, out, 1, FilmFx.Pyramid.TAPS[e])
            }
            // the sum bottom-up: coarsest level first, upscaled level by level
            var acc: Tex? = null
            for (k in maxK downTo 1) {
                val es = (0 until fit.count).filter { els[it].k == k && used(fit, it) }
                val out = lv("${tag}acc", k)
                target(out)
                level.use()
                level.i1("uUseUp", if (acc != null) 1 else 0)
                level.tex("uUp", GLES20.GL_TEXTURE_2D, (acc ?: out).id)
                level.f2("uUpSize", (acc ?: out).w.toFloat(), (acc ?: out).h.toFloat())
                level.i1("uN", es.size)
                level.tex("uE0", GLES20.GL_TEXTURE_2D, es.getOrNull(0)?.let { lv("${tag}e$it", k).id } ?: out.id)
                level.tex("uE1", GLES20.GL_TEXTURE_2D, es.getOrNull(1)?.let { lv("${tag}e$it", k).id } ?: out.id)
                es.getOrNull(0)?.let { val wv = wOf(fit, it + 1); level.f3("uW0", wv[0], wv[1], wv[2]) }
                es.getOrNull(1)?.let { val wv = wOf(fit, it + 1); level.f3("uW1", wv[0], wv[1], wv[2]) }
                draw()
                acc = out
            }
            // full resolution: the image × weight + the coarser sum, then each element (directly from the image)
            target(a)
            level.use()
            level.i1("uUseUp", if (acc != null) 1 else 0)
            level.tex("uUp", GLES20.GL_TEXTURE_2D, (acc ?: src).id)
            level.f2("uUpSize", (acc ?: src).w.toFloat(), (acc ?: src).h.toFloat())
            level.i1("uN", 1)
            level.tex("uE0", GLES20.GL_TEXTURE_2D, src.id)
            level.tex("uE1", GLES20.GL_TEXTURE_2D, src.id)
            val w0 = wOf(fit, 0); level.f3("uW0", w0[0], w0[1], w0[2])
            draw()
            var cur = a; var other = b
            for (e in 0 until fit.count) {
                if (els[e].k != 0 || !used(fit, e)) continue
                blurPass(src, tmp, 0, FilmFx.Pyramid.TAPS[e])
                blurPass(tmp, other, 1, FilmFx.Pyramid.TAPS[e], acc = cur, wt = wOf(fit, e + 1))
                val t = cur; cur = other; other = t
            }
            return cur
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) = PassTimes.measure("film") { drawTimed(inputTexId, presentationTimeUs) }

        private fun drawTimed(inputTexId: Int, presentationTimeUs: Long) {
            // covered by an opaque layer above (preview playback): the picture isn't seen – passed through, see Occlusion
            // (the main player's picture also while the edit point layer covers it, see Occlusion.hideMain)
            if (live != null && (Occlusion.hidden(live.first) || main && Occlusion.hiddenMain(live.first))) { Occlusion.skipped++; drawCopy(inputTexId, presentationTimeUs); return }
            if (main && live != null) Occlusion.filmed(live.first)
            live?.let { LiveGrades.get(it) }?.film?.let { f -> if (f != film) adopt(f) }
            if (broken) { drawColorOnly(inputTexId, presentationTimeUs); return }
            val prevFbo = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
            val vp = IntArray(4); GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)
            try {
                drawFilm(inputTexId, presentationTimeUs, prevFbo[0], vp)
            } catch (e: Exception) {
                // better color only than no image: free the images and continue with the baked LUT
                se.gendut.klipp.CrashReport.note("film: GPU path failed (${e.message}) – color only")
                broken = true
                runCatching { freeImages() }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
                GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
                drawColorOnly(inputTexId, presentationTimeUs)
            }
        }

        /** The film's colors alone (spektrafilm's chain baked into a LUT) on the GPU, for the current settings. */
        private fun colorLut(): Int {
            if (colorFor != film || tColor == 0) {
                if (tColor != 0) GLES20.glDeleteTextures(1, intArrayOf(tColor), 0)
                val lut = Spektra.composite(null, 0f, film)
                tColor = tex3(lut.data, lut.size)
                colorFor = film
            }
            return tColor
        }

        private var copy: Prog? = null

        /** The input unchanged (a covered clip, see [Occlusion]). */
        private fun drawCopy(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val p = copy ?: Prog(COPY).also { copy = it }
                p.use()
                p.tex("uIn", GLES20.GL_TEXTURE_2D, inputTexId)
                draw()
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun drawColorOnly(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val p = colorOnly ?: Prog(COLOR_ONLY).also { colorOnly = it }
                val lut = colorLut()
                p.use()
                p.tex("uIn", GLES20.GL_TEXTURE_2D, inputTexId)
                p.tex("uLut", GLES30.GL_TEXTURE_3D, lut)
                p.i1("uPass", if (film.effectsOnly) 1 else 0)
                p.f1("uLutN", Spektra.composite(null, 0f, film).size.toFloat())
                draw()
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun drawFilm(inputTexId: Int, presentationTimeUs: Long, outFbo: Int, vp: IntArray) {
            run {
                prepare()
                val fr = frame!!
                val s = setup
                // benchmarks: each stage's GPU time from timer queries (no glFinish – it would let the clock drop)
                val prof = profile
                val marks = ArrayList<Pair<String, Int>>()
                fun newQuery(): Int { val q = IntArray(1); GLES30.glGenQueries(1, q, 0); GLES30.glBeginQuery(GL_TIME_ELAPSED, q[0]); return q[0] }
                var q0 = if (prof != null) newQuery() else 0
                fun mark(stage: String) { if (prof != null) { GLES30.glEndQuery(GL_TIME_ELAPSED); marks += stage to q0; q0 = newQuery() } }
                subMark = if (prof != null) { st -> mark(st) } else null
                GLES20.glDisable(GLES20.GL_BLEND)
                val (f0, f1, f2, f3, f4) = full
                // 1. exposure
                target(f0)
                expose.use()
                expose.tex("uIn", GLES20.GL_TEXTURE_2D, inputTexId)
                expose.i2("uInSize", w, h)
                expose.tex("uDecode", GLES20.GL_TEXTURE_2D, tDecode)
                expose.f1("uDecodeN", FilmFx.DECODE_N.toFloat())
                expose.tex("uLutE", GLES30.GL_TEXTURE_3D, tLutE)
                expose.f1("uLutN", FilmFx.LUT_N.toFloat())
                expose.f1("uEv", s.evScale)
                draw()
                mark("expose")
                // highlight boost: the image's largest raw exposure (as FilmFx.boostRef: averaged down to at most
                // BOOST_REF_W wide, then max-halving down to 1×1), then the curve. Only the image itself (w × h, not the
                // mirrored padding), so the averages match the CPU's
                var exposed = f0
                if (s.f.boostEv > 0f) {
                    var src = f0; var sw = w; var sh = h; var k = 0
                    while (sw > 1 || sh > 1) {
                        k++
                        val d = FilmFx.Pyramid.dims(w, h, k)
                        val dst = levels.getOrPut("mx${d[0]}x${d[1]}") { Tex(d[0], d[1]) }
                        target(dst)
                        val p = if (sw > FilmFx.BOOST_REF_W) half else maxHalf
                        p.use()
                        p.tex("uSrc", GLES20.GL_TEXTURE_2D, src.id)
                        p.i2("uSrcSize", sw, sh)
                        draw()
                        src = dst; sw = d[0]; sh = d[1]
                    }
                    target(f4)
                    boostP.use()
                    boostP.tex("uSrc", GLES20.GL_TEXTURE_2D, f0.id)
                    boostP.tex("uMax", GLES20.GL_TEXTURE_2D, src.id)
                    boostP.f1("uBoostEv", s.f.boostEv)
                    boostP.f1("uProtectEv", s.f.protectEv)
                    boostP.f1("uRange", s.f.boostRange.coerceIn(0f, 1f))
                    draw()
                    exposed = f4
                    mark("boost")
                }
                // 2. the camera's blurs (diffusion filter, lens, scatter, halation)
                val spare = full.filter { it !== exposed }
                val raw = if (fr.camera.isIdentity) exposed else pyramid(exposed, fr.camera, spare, "c")
                mark("camera")
                val free = full.filter { it !== raw }.toMutableList()
                // 3. the couplers' inhibition and its diffusion
                var corr: Tex? = null
                if (s.couplersOn) {
                    val c0 = free.removeAt(0)
                    target(c0)
                    develop1.use()
                    curveUniforms(develop1)
                    develop1.tex("uRaw", GLES20.GL_TEXTURE_2D, raw.id)
                    develop1.mat3("uM", s.couplerM)
                    develop1.i1("uPositive", if (s.positive) 1 else 0)
                    develop1.f3("uDmax", s.dmax[0], s.dmax[1], s.dmax[2])
                    draw()
                    mark("develop1")
                    val cf = fr.couplers
                    corr = if (cf == null || cf.isIdentity) c0 else pyramid(c0, cf, free, "k")
                    free.remove(corr); if (corr !== c0) free.add(c0)
                    mark("couplers")
                }
                // 4. development and grain
                val seed = (presentationTimeUs / 1000L).toInt() * 1103515245 + 12345
                val dens = free.removeAt(0)
                target(dens)
                develop2.use()
                curveUniforms(develop2)
                develop2.tex("uRaw", GLES20.GL_TEXTURE_2D, raw.id)
                develop2.i1("uCouplers", if (corr != null) 1 else 0)
                develop2.tex("uCorr", GLES20.GL_TEXTURE_2D, (corr ?: raw).id)
                develop2.i1("uGrain", if (fr.grainOn) 1 else 0)
                develop2.tex("uLayer", GLES20.GL_TEXTURE_2D, tLayer)
                develop2.f1("uLayerN", FilmFx.LAYER_N.toFloat())
                develop2.f3("uDmax", s.dmax[0], s.dmax[1], s.dmax[2])
                develop2.f3v("uN", fr.grainN)
                develop2.f3v("uOd", fr.grainOd)
                develop2.f3v("uLo", fr.grainLo)
                develop2.f3v("uHi", fr.grainHi)
                develop2.f3("uUniformity", s.uniformity[0], s.uniformity[1], s.uniformity[2])
                develop2.f3("uGrainDmin", s.grainDmin[0], s.grainDmin[1], s.grainDmin[2])
                develop2.f1("uChroma", s.f.grainColor)
                develop2.f1("uGrainAmount", s.f.grainAmount)
                develop2.u1("uSeed", seed)
                draw()
                mark("develop")
                free.add(raw); corr?.let { free.add(it) }
                var d = dens
                fr.grainBlur?.let { k ->
                    val t = free.removeAt(0); val o = free.removeAt(0)
                    blurPass(d, t, 0, k); blurPass(t, o, 1, k)
                    free.add(d); free.add(t); d = o
                    mark("grainBlur")
                }
                // 5. printing and scanning; with the enlarger's diffusion filter, first the paper's exposure, blurred
                var src = d
                var mode = 0
                val ek = fr.enlarger
                if (ek != null && s.printing) {
                    val pr = free.removeAt(0)
                    target(pr)
                    printUniforms(s, d, 2, seed)
                    draw()
                    val blurred = pyramid(pr, ek, free, "p")
                    free.remove(blurred); free.add(pr); free.add(d)
                    src = blurred; mode = 1
                    mark("enlarger")
                }
                val lin = free.removeAt(0)
                target(lin)
                printUniforms(s, src, mode, seed)
                draw()
                free.add(src)
                mark("print")
                // the scanner's blur
                var l = lin
                fr.scanBlur?.let { k ->
                    val t = free.removeAt(0); val o = free.removeAt(0)
                    blurPass(l, t, 0, k); blurPass(t, o, 1, k)
                    free.add(l); free.add(t); l = o
                    mark("scanBlur")
                }
                // 6. sharpening (horizontal here, vertical in the last step) and the display encoding
                val us = fr.unsharp
                var hb: Tex? = null
                if (us != null) { hb = free.removeAt(0); blurPass(l, hb, 0, us); mark("sharpenH") }
                // effects only: the film's colors alone and the inverse input curve – made before anything is bound
                // (making a texture binds it to the active unit and would replace an input bound just before)
                if (s.f.effectsOnly && tEncode == 0) tEncode = tex2(FloatArray(FilmFx.ENCODE_N * 3) { s.encode[it / 3] }, FilmFx.ENCODE_N, 1, 3)
                val keepLut = if (s.f.effectsOnly) colorLut() else tScan
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo)
                GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
                final.use()
                final.tex("uL", GLES20.GL_TEXTURE_2D, l.id)
                final.i1("uSharp", if (us != null) 1 else 0)
                final.tex("uH", GLES20.GL_TEXTURE_2D, (hb ?: l).id)
                final.i2("uSrcSize", l.w, l.h)
                taps(final, us ?: floatArrayOf(1f))
                final.f1("uAmount", s.f.sharpen)
                final.i1("uDisplay", s.f.display)
                final.tex("uA", GLES20.GL_TEXTURE_2D, inputTexId)
                final.i1("uKeep", if (s.f.effectsOnly) 1 else 0)
                // every sampler gets its own unit even when unused: an unbound sampler3D would share unit 0 with a
                // sampler2D, and then the draw fails (the film fell back to color only)
                final.tex("uLut", GLES30.GL_TEXTURE_3D, keepLut)
                final.tex("uDecode", GLES20.GL_TEXTURE_2D, tDecode)
                final.tex("uEncode", GLES20.GL_TEXTURE_2D, if (tEncode != 0) tEncode else tDecode)
                if (s.f.effectsOnly) {
                    final.f1("uLutN", Spektra.composite(null, 0f, film).size.toFloat())
                    final.f1("uDecodeN", FilmFx.DECODE_N.toFloat())
                    final.f1("uEncodeN", FilmFx.ENCODE_N.toFloat())
                    final.f1("uLinMax", s.linMax)
                }
                draw()
                mark("final")
                if (prof != null) {
                    GLES30.glEndQuery(GL_TIME_ELAPSED); GLES30.glDeleteQueries(1, intArrayOf(q0), 0)
                    val v = IntArray(1)
                    for ((stage, q) in marks) { GLES30.glGetQueryObjectuiv(q, GLES30.GL_QUERY_RESULT, v, 0); prof(stage, (v[0].toLong() and 0xffffffffL) / 1e6); GLES30.glDeleteQueries(1, intArrayOf(q), 0) }
                }
                GlUtil.checkGlError()
            }
        }

        /** The print and scan uniforms ([mode] 0: from the density, 1: from the paper's exposure, 2: write the exposure). */
        private fun printUniforms(s: FilmFx.Setup, src: Tex, mode: Int, seed: Int) {
            print.use()
            print.tex("uD", GLES20.GL_TEXTURE_2D, src.id)
            print.i1("uMode", mode)
            print.i1("uPrinting", if (s.printing) 1 else 0)
            print.tex("uLutP", GLES30.GL_TEXTURE_3D, if (tLutP != 0) tLutP else tScan)
            print.f3("uPLo", s.pLo[0].toFloat(), s.pLo[1].toFloat(), s.pLo[2].toFloat())
            print.f3("uPHi", s.pHi[0].toFloat(), s.pHi[1].toFloat(), s.pHi[2].toFloat())
            print.f1("uLutN", FilmFx.LUT_N.toFloat())
            print.f1("uPrintOff", s.printOffset)
            print.tex("uPrintCurve", GLES20.GL_TEXTURE_2D, if (tPrintCurve != 0) tPrintCurve else tCurve)
            print.f1("uCurveN", FilmFx.CURVE_N.toFloat())
            print.f2("uPrLe", s.prLo.toFloat(), s.prHi.toFloat())
            print.tex("uScan", GLES30.GL_TEXTURE_3D, tScan)
            print.f1("uScanN", s.scanN.toFloat())
            print.f3("uScanLo", s.scanLo[0].toFloat(), s.scanLo[1].toFloat(), s.scanLo[2].toFloat())
            print.f3("uScanHi", s.scanHi[0].toFloat(), s.scanHi[1].toFloat(), s.scanHi[2].toFloat())
            val glare = FilmFx.glareLn(s)
            print.i1("uGlare", if (glare != null) 1 else 0)
            if (glare != null) print.f2("uGlareLn", glare.first.toFloat(), glare.second.toFloat())
            print.u1("uSeed", seed)
        }

        private fun curveUniforms(p: Prog) {
            p.tex("uCurve", GLES20.GL_TEXTURE_2D, tCurve)
            p.f1("uCurveN", FilmFx.CURVE_N.toFloat())
            p.f2("uLe", setup.leLo.toFloat(), setup.leHi.toFloat())
        }

        /** New settings from [LiveGrades] (playback): the tables and kernels are replaced. */
        private fun adopt(f: Film) {
            film = f
            setup = FilmFx.setup(f)
            upload()
            frame = null
        }

        override fun release() {
            super.release()
            try {
                freeImages()
                deleteTables()
                if (tColor != 0) GLES20.glDeleteTextures(1, intArrayOf(tColor), 0)
                colorOnly?.delete()
                copy?.delete()
                listOf(expose, blur, blurVHalf, half, level, develop1, develop2, print, final, maxHalf, boostP, blurH4, blurVSum).forEach { it.delete() }
                GLES20.glDeleteFramebuffers(1, intArrayOf(mrtFbo), 0)
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e)
            }
        }
    }
}

