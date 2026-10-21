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
import androidx.media3.effect.SingleColorLut
import se.gendut.klipp.R
import se.gendut.klipp.Str
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A 3D LUT (.cube). `data` is RGB floats in .cube order: red varies fastest, then green, then blue.
 */
class CubeLut(val name: String, val size: Int, val data: FloatArray, val domainMin: FloatArray, val domainMax: FloatArray) {
    init {
        require(size in 2..129) { Str.t(R.string.lut_error_size_unsupported, size) }
        require(data.size == size * size * size * 3) { "LUT data has the wrong length" }
    }

    private fun idx(r: Int, g: Int, b: Int) = ((b * size + g) * size + r) * 3

    fun at(r: Int, g: Int, b: Int, out: FloatArray) {
        val i = idx(r, g, b)
        out[0] = data[i]; out[1] = data[i + 1]; out[2] = data[i + 2]
    }

    /** Tetrahedral interpolation (CPU reference, the same math as the GPU shader). */
    fun applyTetrahedral(rIn: Float, gIn: Float, bIn: Float): FloatArray {
        val n1 = (size - 1).toFloat()
        fun norm(v: Float, c: Int) = ((v - domainMin[c]) / (domainMax[c] - domainMin[c])).coerceIn(0f, 1f) * n1
        val pr = norm(rIn, 0); val pg = norm(gIn, 1); val pb = norm(bIn, 2)
        val r0 = minOf(floor(pr).toInt(), size - 2); val g0 = minOf(floor(pg).toInt(), size - 2); val b0 = minOf(floor(pb).toInt(), size - 2)
        val fr = pr - r0; val fg = pg - g0; val fb = pb - b0
        val c000 = FloatArray(3).also { at(r0, g0, b0, it) }
        val c111 = FloatArray(3).also { at(r0 + 1, g0 + 1, b0 + 1, it) }
        fun c(dr: Int, dg: Int, db: Int) = FloatArray(3).also { at(r0 + dr, g0 + dg, b0 + db, it) }
        val out = FloatArray(3)
        fun mix4(w0: Float, a: FloatArray, w1: Float, b: FloatArray, w2: Float, cc: FloatArray, w3: Float, d: FloatArray) {
            for (k in 0..2) out[k] = w0 * a[k] + w1 * b[k] + w2 * cc[k] + w3 * d[k]
        }
        if (fr >= fg) {
            when {
                fg >= fb -> mix4(1 - fr, c000, fr - fg, c(1, 0, 0), fg - fb, c(1, 1, 0), fb, c111)
                fr >= fb -> mix4(1 - fr, c000, fr - fb, c(1, 0, 0), fb - fg, c(1, 0, 1), fg, c111)
                else -> mix4(1 - fb, c000, fb - fr, c(0, 0, 1), fr - fg, c(1, 0, 1), fg, c111)
            }
        } else {
            when {
                fb >= fg -> mix4(1 - fb, c000, fb - fg, c(0, 0, 1), fg - fr, c(0, 1, 1), fr, c111)
                fb >= fr -> mix4(1 - fg, c000, fg - fb, c(0, 1, 0), fb - fr, c(0, 1, 1), fr, c111)
                else -> mix4(1 - fg, c000, fg - fr, c(0, 1, 0), fr - fb, c(1, 1, 0), fb, c111)
            }
        }
        return out
    }

    /** Number of LUT tiles per row in the texture (square packing so even 65-point LUTs fit). */
    val tilesPerRow: Int get() = ceil(sqrt(size.toDouble())).toInt()
    val texWidth: Int get() = size * tilesPerRow
    val texHeight: Int get() = size * ceil(size.toDouble() / tilesPerRow).toInt()

    /** RGBA8 texture: tile b sits at (b % tpr, b / tpr); within the tile x = red, y = green. */
    fun textureBytes(): ByteBuffer {
        val w = texWidth
        val buf = ByteBuffer.allocateDirect(w * texHeight * 4).order(ByteOrder.nativeOrder())
        val px = FloatArray(3)
        val tpr = tilesPerRow
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            at(r, g, b, px)
            val x = (b % tpr) * size + r
            val y = (b / tpr) * size + g
            val o = (y * w + x) * 4
            buf.put(o, (px[0].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            buf.put(o + 1, (px[1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            buf.put(o + 2, (px[2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            buf.put(o + 3, 255.toByte())
        }
        buf.position(0)
        return buf
    }

    /** Media3's own (trilinear) LUT – fallback if the tetrahedral shader does not work. */
    @OptIn(UnstableApi::class)
    fun toTrilinear(): SingleColorLut {
        val px = FloatArray(3)
        val cube = Array(size) { r -> Array(size) { g -> IntArray(size) { b ->
            at(r, g, b, px)
            val ri = (px[0].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val gi = (px[1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val bi = (px[2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        } } }
        return SingleColorLut.createFromCube(cube)
    }

    companion object {
        fun identity(n: Int, name: String = "Identity"): CubeLut {
            val d = FloatArray(n * n * n * 3)
            var i = 0
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                d[i++] = r / (n - 1f); d[i++] = g / (n - 1f); d[i++] = b / (n - 1f)
            }
            return CubeLut(name, n, d, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        }

        private val WS = Regex("\\s+")

        /** Parses an Adobe/Resolve .cube (3D). */
        fun parseCube(name: String, text: String): CubeLut {
            var size = 0
            var dmin = floatArrayOf(0f, 0f, 0f)
            var dmax = floatArrayOf(1f, 1f, 1f)
            // the values in a growing FloatArray (not a list of boxed Floats – a 65³ LUT has 824,000 values)
            var values = FloatArray(3 * 33 * 33 * 33)
            var count = 0
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(WS)
                when (parts[0].uppercase()) {
                    "TITLE" -> {}
                    "LUT_3D_SIZE" -> size = parts[1].toInt()
                    "LUT_1D_SIZE" -> throw IllegalArgumentException(Str.t(R.string.lut_error_1d_unsupported))
                    "DOMAIN_MIN" -> dmin = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    "DOMAIN_MAX" -> dmax = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    "LUT_3D_INPUT_RANGE" -> { dmin = FloatArray(3) { parts[1].toFloat() }; dmax = FloatArray(3) { parts[2].toFloat() } }
                    else -> {
                        if (parts.size != 3) continue
                        val r = parts[0].toFloatOrNull() ?: continue
                        val g = parts[1].toFloatOrNull() ?: continue
                        val b = parts[2].toFloatOrNull() ?: continue
                        if (count + 3 > values.size) values = values.copyOf(maxOf(values.size * 2, size * size * size * 3))
                        values[count++] = r; values[count++] = g; values[count++] = b
                    }
                }
            }
            require(size > 0) { Str.t(R.string.lut_error_no_size) }
            require(count == size * size * size * 3) { Str.t(R.string.lut_error_value_count, count / 3, size * size * size) }
            // an empty or reversed range caused division by zero (black or NaN in the image)
            for (i in 0..2) require(dmax[i] > dmin[i]) { Str.t(R.string.lut_error_domain) }
            return CubeLut(name, size, if (count == values.size) values else values.copyOf(count), dmin, dmax)
        }
    }
}

/** GLSL for the tetrahedral LUT effect (OpenGL ES 2.0). Validated with glslang in the build tests. */
object TetraShaders {
    const val VERTEX = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

    const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uLut;
uniform float uSize;
uniform float uTilesPerRow;
uniform vec2 uTexSize;
uniform vec3 uDomainMin;
uniform vec3 uDomainMax;
uniform float uMix;
varying vec2 vTexSamplingCoord;

vec3 fetchLut(vec3 i) {
  float tile = i.b;
  float tx = mod(tile, uTilesPerRow);
  float ty = floor(tile / uTilesPerRow);
  vec2 uv = vec2((tx * uSize + i.r + 0.5) / uTexSize.x, (ty * uSize + i.g + 0.5) / uTexSize.y);
  return texture2D(uLut, uv).rgb;
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 p = clamp((src.rgb - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0) * (uSize - 1.0);
  vec3 b = min(floor(p), vec3(uSize - 2.0));
  vec3 f = p - b;
  vec3 c000 = fetchLut(b);
  vec3 c111 = fetchLut(b + vec3(1.0));
  vec3 res;
  if (f.r >= f.g) {
    if (f.g >= f.b) {
      res = (1.0 - f.r) * c000 + (f.r - f.g) * fetchLut(b + vec3(1.0, 0.0, 0.0)) + (f.g - f.b) * fetchLut(b + vec3(1.0, 1.0, 0.0)) + f.b * c111;
    } else if (f.r >= f.b) {
      res = (1.0 - f.r) * c000 + (f.r - f.b) * fetchLut(b + vec3(1.0, 0.0, 0.0)) + (f.b - f.g) * fetchLut(b + vec3(1.0, 0.0, 1.0)) + f.g * c111;
    } else {
      res = (1.0 - f.b) * c000 + (f.b - f.r) * fetchLut(b + vec3(0.0, 0.0, 1.0)) + (f.r - f.g) * fetchLut(b + vec3(1.0, 0.0, 1.0)) + f.g * c111;
    }
  } else {
    if (f.b >= f.g) {
      res = (1.0 - f.b) * c000 + (f.b - f.g) * fetchLut(b + vec3(0.0, 0.0, 1.0)) + (f.g - f.r) * fetchLut(b + vec3(0.0, 1.0, 1.0)) + f.r * c111;
    } else if (f.b >= f.r) {
      res = (1.0 - f.g) * c000 + (f.g - f.b) * fetchLut(b + vec3(0.0, 1.0, 0.0)) + (f.b - f.r) * fetchLut(b + vec3(0.0, 1.0, 1.0)) + f.r * c111;
    } else {
      res = (1.0 - f.g) * c000 + (f.g - f.r) * fetchLut(b + vec3(0.0, 1.0, 0.0)) + (f.r - f.b) * fetchLut(b + vec3(1.0, 1.0, 0.0)) + f.b * c111;
    }
  }
  gl_FragColor = vec4(mix(src.rgb, res, uMix), src.a);
}
"""
}

/** Media3 effect: tetrahedral 3D LUT on the GPU. */
@OptIn(UnstableApi::class)
class TetrahedralLutEffect(val lut: CubeLut, val mix: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(lut, mix, useHdr)

    private class Program(private val lut: CubeLut, private val mix: Float, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {
        private val program: GlProgram
        private val lutTex: Int

        /** The texture size as a uniform (created once, not every frame). */
        private val texSize = floatArrayOf(lut.texWidth.toFloat(), lut.texHeight.toFloat())

        init {
            var pg: GlProgram? = null
            val ids = IntArray(1)
            try {
                pg = GlProgram(TetraShaders.VERTEX, TetraShaders.FRAGMENT)
                program = pg
                program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                GLES20.glGenTextures(1, ids, 0)
                lutTex = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lut.texWidth, lut.texHeight, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lut.textureBytes()
                )
                GlUtil.checkGlError()
            } catch (e: Exception) {
                runCatching { pg?.delete() }
                runCatching { GLES20.glDeleteTextures(1, ids, 0) }
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) = PassTimes.measure("lut") { drawTimed(inputTexId, presentationTimeUs) }

        private fun drawTimed(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uLut", lutTex, 1)
                program.setFloatUniform("uSize", lut.size.toFloat())
                program.setFloatUniform("uTilesPerRow", lut.tilesPerRow.toFloat())
                program.setFloatsUniform("uTexSize", texSize)
                program.setFloatsUniform("uDomainMin", lut.domainMin)
                program.setFloatsUniform("uDomainMax", lut.domainMax)
                program.setFloatUniform("uMix", mix)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
                GLES20.glDeleteTextures(1, intArrayOf(lutTex), 0)
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e)
            }
        }
    }
}

/** LUT library: built-in + imported (.cube in the app's files). */
object LutStore {
    val names = androidx.compose.runtime.mutableStateListOf<String>()
    /** Used from several threads (image, playback, color tools) – hence thread-safe. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CubeLut>()
    /** LUTs that do not exist (so the disk is not queried on every draw); cleared on import. */
    private val missing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var dir: java.io.File? = null

    const val TEAL_ORANGE = "Teal & Orange"
    // (stored by name in projects and grades – do not translate)
    const val BLEACH = "Blekt"

    init {
        names += TEAL_ORANGE
        names += BLEACH
    }

    fun init(context: Context) {
        if (dir != null) return
        dir = java.io.File(context.filesDir, "luts").apply { mkdirs() }
        refreshNames()
    }

    private fun refreshNames() {
        names.clear()
        names += TEAL_ORANGE
        names += BLEACH
        dir?.listFiles { f -> f.name.endsWith(".cube", true) }?.sortedBy { it.name.lowercase() }?.forEach { names += it.nameWithoutExtension }
    }

    fun get(name: String): CubeLut? {
        cache[name]?.let { return it }
        if (name in missing) return null
        val l = load(name)
        if (l == null) { missing += name; return null }
        // two threads at once: both get the same object (the GPU uploads it once)
        return cache.putIfAbsent(name, l) ?: l
    }

    private fun load(name: String): CubeLut? = run {
        when (name) {
            TEAL_ORANGE -> generated(name, 33) { r, g, b ->
                // shadows toward teal, highlights and skin tones toward orange
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val t = (l - 0.5f) * 0.16f
                floatArrayOf(r + t + 0.02f, g + 0.01f, b - t * 1.2f + 0.01f)
            }
            BLEACH -> generated(name, 33) { r, g, b ->
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val c = { v: Float -> 0.5f + (v - 0.5f) * 1.25f }
                floatArrayOf(c(l + (r - l) * 0.45f), c(l + (g - l) * 0.45f), c(l + (b - l) * 0.45f))
            }
            else -> {
                val f = java.io.File(dir ?: return null, "$name.cube")
                if (!f.exists()) return null
                runCatching { CubeLut.parseCube(name, f.readText()) }.getOrNull() ?: return null
            }
        }
    }

    /** Imports a .cube from a Uri. Returns the name or throws with an understandable error. */
    fun import(context: Context, uri: android.net.Uri): String {
        init(context)
        val raw = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "LUT"
        val name = raw.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").take(40)
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val lut = CubeLut.parseCube(name, text) // throws if the file is not a valid 3D LUT
        java.io.File(dir, "$name.cube").writeText(text)
        cache[name] = lut
        missing.remove(name)
        // a LUT with the same name may have been baked together with OpenDRT – those are re-baked with the new one
        OpenDrt.clearCache()
        Spektra.clearCache()
        // the name list is UI state: changed on the main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post { refreshNames() }
        return name
    }

    private fun generated(name: String, n: Int, f: (Float, Float, Float) -> FloatArray): CubeLut {
        val d = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val o = f(r / (n - 1f), g / (n - 1f), b / (n - 1f))
            d[i++] = o[0].coerceIn(0f, 1f); d[i++] = o[1].coerceIn(0f, 1f); d[i++] = o[2].coerceIn(0f, 1f)
        }
        return CubeLut(name, n, d, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }
}


/** GLSL for the timeline's look in a single pass (RGB scale, LUT, contrast, saturation) – see [LookEffect]. */
object LookShaders {
    const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uLut;
uniform vec3 uScale;
uniform float uContrastFactor;
uniform float uSatShift;
uniform float uUseLut;
uniform float uSize;
uniform float uTilesPerRow;
uniform vec2 uTexSize;
uniform vec3 uDomainMin;
uniform vec3 uDomainMax;
uniform float uMix;
varying vec2 vTexSamplingCoord;

vec3 fetchLut(vec3 i) {
  float tx = mod(i.b, uTilesPerRow);
  float ty = floor(i.b / uTilesPerRow);
  vec2 uv = vec2((tx * uSize + i.r + 0.5) / uTexSize.x, (ty * uSize + i.g + 0.5) / uTexSize.y);
  return texture2D(uLut, uv).rgb;
}

vec3 lut3(vec3 rgb) {
  vec3 p = clamp((rgb - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0) * (uSize - 1.0);
  vec3 b = min(floor(p), vec3(uSize - 2.0));
  vec3 f = p - b;
  vec3 c000 = fetchLut(b);
  vec3 c111 = fetchLut(b + vec3(1.0));
  vec3 res;
  if (f.r >= f.g) {
    if (f.g >= f.b) {
      res = (1.0 - f.r) * c000 + (f.r - f.g) * fetchLut(b + vec3(1.0, 0.0, 0.0)) + (f.g - f.b) * fetchLut(b + vec3(1.0, 1.0, 0.0)) + f.b * c111;
    } else if (f.r >= f.b) {
      res = (1.0 - f.r) * c000 + (f.r - f.b) * fetchLut(b + vec3(1.0, 0.0, 0.0)) + (f.b - f.g) * fetchLut(b + vec3(1.0, 0.0, 1.0)) + f.g * c111;
    } else {
      res = (1.0 - f.b) * c000 + (f.b - f.r) * fetchLut(b + vec3(0.0, 0.0, 1.0)) + (f.r - f.g) * fetchLut(b + vec3(1.0, 0.0, 1.0)) + f.g * c111;
    }
  } else {
    if (f.b >= f.g) {
      res = (1.0 - f.b) * c000 + (f.b - f.g) * fetchLut(b + vec3(0.0, 0.0, 1.0)) + (f.g - f.r) * fetchLut(b + vec3(0.0, 1.0, 1.0)) + f.r * c111;
    } else if (f.b >= f.r) {
      res = (1.0 - f.g) * c000 + (f.g - f.b) * fetchLut(b + vec3(0.0, 1.0, 0.0)) + (f.b - f.r) * fetchLut(b + vec3(0.0, 1.0, 1.0)) + f.r * c111;
    } else {
      res = (1.0 - f.g) * c000 + (f.g - f.r) * fetchLut(b + vec3(0.0, 1.0, 0.0)) + (f.r - f.b) * fetchLut(b + vec3(1.0, 1.0, 0.0)) + f.b * c111;
    }
  }
  return res;
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = clamp(src.rgb * uScale, 0.0, 1.0);
  if (uUseLut > 0.5) {
    c = mix(c, clamp(lut3(c), 0.0, 1.0), uMix);
  }
  c = clamp(uContrastFactor * (c - 0.5) + 0.5, 0.0, 1.0);
  if (abs(uSatShift) > 0.0001) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float li = (mx + mn) * 0.5;
    float d = mx - mn;
    if (d > 0.00001) {
      float s0 = li > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
      float s1 = clamp(s0 + uSatShift, 0.0, 1.0);
      c = clamp(vec3(li) + (c - vec3(li)) * (s1 / s0), 0.0, 1.0);
    }
  }
  gl_FragColor = vec4(c, src.a);
}
"""
}

/**
 * The timeline's whole look in a single GPU pass (instead of up to four: RGB, LUT, contrast, saturation) –
 * one read and write of the image per frame instead of four, which is noticeable in 4K on mobile GPUs.
 * The same math as Look.applyTo (the CPU) and AgslShaders.LOOK (the preview).
 */
@OptIn(UnstableApi::class)
class LookEffect(val look: Look) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(look, useHdr)

    private class Program(private val look: Look, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program: GlProgram
        private val lutTex: Int
        private val lut: CubeLut = look.lut?.takeIf { look.lutMix > 0.005f } ?: CubeLut.identity(2)
        private val useLut = look.lut != null && look.lutMix > 0.005f

        /** The texture size as a uniform (created once, not every frame). */
        private val texSize = floatArrayOf(lut.texWidth.toFloat(), lut.texHeight.toFloat())

        init {
            var pg: GlProgram? = null
            val ids = IntArray(1)
            try {
                pg = GlProgram(TetraShaders.VERTEX, LookShaders.FRAGMENT)
                program = pg
                program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                GLES20.glGenTextures(1, ids, 0)
                lutTex = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lut.texWidth, lut.texHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lut.textureBytes())
                GlUtil.checkGlError()
            } catch (e: Exception) {
                runCatching { pg?.delete() }
                runCatching { GLES20.glDeleteTextures(1, ids, 0) }
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) = PassTimes.measure("look") { drawTimed(inputTexId, presentationTimeUs) }

        private fun drawTimed(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uLut", lutTex, 1)
                val p = LookParams.of(look)
                program.setFloatsUniform("uScale", p.scale)
                program.setFloatUniform("uContrastFactor", p.contrastFactor)
                program.setFloatUniform("uSatShift", p.satShift)
                program.setFloatUniform("uUseLut", if (useLut) 1f else 0f)
                program.setFloatUniform("uSize", lut.size.toFloat())
                program.setFloatUniform("uTilesPerRow", lut.tilesPerRow.toFloat())
                program.setFloatsUniform("uTexSize", texSize)
                program.setFloatsUniform("uDomainMin", lut.domainMin)
                program.setFloatsUniform("uDomainMax", lut.domainMax)
                program.setFloatUniform("uMix", look.lutMix.coerceIn(0f, 1f))
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
                GLES20.glDeleteTextures(1, intArrayOf(lutTex), 0)
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e)
            }
        }
    }
}

/** The look's values as shader uniforms (shared by GLSL, AGSL and tests). */
data class LookParams(val scale: FloatArray, val contrastFactor: Float, val satShift: Float) {
    companion object {
        fun of(l: Look): LookParams {
            val e = Math.pow(2.0, l.exposure.toDouble()).toFloat()
            val c = l.contrast.coerceIn(-1f, 1f)
            return LookParams(
                floatArrayOf(e * (1f + 0.18f * l.temp), e * (1f - 0.12f * l.tint), e * (1f - 0.18f * l.temp)),
                if (kotlin.math.abs(l.contrast) > 0.005f) (1f + c) / (1.0001f - c) else 1f,
                if (kotlin.math.abs(l.sat) > 0.005f) (l.sat * 100f).coerceIn(-100f, 100f) / 100f else 0f,
            )
        }
    }
}
