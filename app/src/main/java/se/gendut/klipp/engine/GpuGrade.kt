package se.gendut.klipp.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * GPU grading for the preview (still images while dragging, scrubbing or waiting for the player): the same math
 * as playback's GLSL shaders (GradeShaders, TetraShaders, Media3's Contrast/HSL) written in AGSL and run with
 * RenderEffect – a few milliseconds per image instead of hundreds on the CPU. Requires Android 13; otherwise (or if
 * something fails) the CPU is used.
 */
object GpuGrade {
    // (not in the Robolectric test environment, where HardwareRenderer has no real GPU)
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33 && Build.FINGERPRINT != "robolectric"

    /** Set if the GPU path has failed on this phone (then the CPU is used for the rest of the session). */
    @Volatile var broken = false
        private set
    @Volatile var lastError: String? = null
        private set

    /**
     * The image with the clip's grade and the timeline's look, or (with [keyOf]) a node's key in grayscale.
     * null if the GPU path isn't possible – the caller then uses the CPU.
     */
    fun apply(src: Bitmap, grade: ClipGrade?, look: Look, keyOf: Int? = null): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 33 || !supported || broken) return null
        // film (grain, halation, diffusion) only exists on the CPU and in playback's GL path
        if (grade?.chain?.any { !it.isNeutral && it.film != null } == true) return null
        return try {
            val out = Impl.apply(src, grade, look, keyOf)
            // safety check: an all-black image from the GPU when the source isn't black = the path doesn't work
            // on this phone → the CPU takes over (better slow than black flashes)
            if (out != null && out !== src && keyOf == null && looksBlack(out) && !looksBlack(src) && !cpuIsBlack(src, grade, look)) {
                broken = true
                lastError = "GPU returned a black image"
                return null
            }
            out
        } catch (e: Throwable) {
            broken = true
            lastError = e.message ?: e.javaClass.simpleName
            se.gendut.klipp.CrashReport.note("gpu grade off: $lastError")
            null
        }
    }

    /**
     * Does the image turn black on the CPU too (a grade that intentionally makes it black, e.g. gain at the bottom)?
     * Then the GPU's black image is correct – otherwise the fast path was disabled for the rest of the session.
     * Checked on a tiny image.
     */
    private fun cpuIsBlack(src: Bitmap, grade: ClipGrade?, look: Look): Boolean = runCatching {
        val sw = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        var tiny = Bitmap.createScaledBitmap(sw, 32, 18, true)
        if (grade != null) tiny = grade.applyTo(tiny)
        looksBlack(look.applyTo(tiny))
    }.getOrDefault(false)

    /** Are all 25 sample points (5×5 over the image) black? */
    fun looksBlack(b: Bitmap): Boolean {
        if (b.width < 1 || b.height < 1) return true
        val sw = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        for (j in 0 until 5) for (i in 0 until 5) {
            val p = sw.getPixel(((i + 0.5f) / 5 * sw.width).toInt().coerceIn(0, sw.width - 1), ((j + 0.5f) / 5 * sw.height).toInt().coerceIn(0, sw.height - 1))
            if ((p and 0xFFFFFF) > 0x0A0A0A && ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255) > 24) return false
        }
        return true
    }

    @RequiresApi(33)
    private object Impl {
        private var reader: ImageReader? = null
        private var renderer: HardwareRenderer? = null
        private var rw = 0
        private var rh = 0
        private val lutCache = HashMap<CubeLut, Bitmap>()
        // reused between calls: compiling the AGSL shader and creating new bitmaps for every image
        // while dragging produced tens of MB of garbage per second (and CPU time for the SkSL interpreter)
        private val nodeShaders = ArrayList<RuntimeShader>()
        private var lookShader: RuntimeShader? = null
        private var shaderIdx = 0
        /** Curve tables → bitmap. The key is the hash, but the contents are compared too (two curves can share a hash). */
        private val curveCache = HashMap<Int, Pair<Array<FloatArray>?, Bitmap>>()
        private var softSrcOf: Bitmap? = null
        private var softSrc: Bitmap? = null
        private val renderNode = RenderNode("klipp-grade")

        @Synchronized
        fun apply(src0: Bitmap, grade: ClipGrade?, look: Look, keyOf: Int?): Bitmap? {
            val src = if (src0.config != Bitmap.Config.HARDWARE) src0
                else if (softSrcOf === src0 && softSrc != null) softSrc!!
                else src0.copy(Bitmap.Config.ARGB_8888, false).also { softSrcOf = src0; softSrc = it }
            shaderIdx = 0
            val w = src.width; val h = src.height
            val effect = effectFor(grade, look, keyOf, w, h) ?: return src0
            if (reader == null || rw != w || rh != h) {
                renderer?.destroy(); reader?.close()
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                renderer = HardwareRenderer().apply { setSurface(reader!!.surface) }
                rw = w; rh = h
            }
            // one render node for all images (previously a new one per image that was never released)
            val node = renderNode
            node.setPosition(0, 0, w, h)
            node.setRenderEffect(effect)
            val c = node.beginRecording()
            c.drawBitmap(src, 0f, 0f, null)
            node.endRecording()
            val r = renderer!!
            r.setContentRoot(node)
            r.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            // the image is drawn (we waited for it): the display list and the source image are released
            node.discardDisplayList()
            val img = reader!!.acquireLatestImage() ?: return null
            try {
                val hb = img.hardwareBuffer ?: return null
                // the reader's buffers are reused: copy the image out before the buffer is returned
                val wrapped = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
                val out = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                wrapped?.recycle()
                hb.close()
                return out
            } finally {
                img.close()
            }
        }

        private fun lutBitmap(l: CubeLut): Bitmap = lutCache.getOrPut(l) {
            val bytes = l.textureBytes()
            Bitmap.createBitmap(l.texWidth, l.texHeight, Bitmap.Config.ARGB_8888).apply {
                // RGBA bytes → ARGB pixels
                val px = IntArray(l.texWidth * l.texHeight)
                for (i in px.indices) {
                    val r = bytes.get(i * 4).toInt() and 255; val g = bytes.get(i * 4 + 1).toInt() and 255; val b = bytes.get(i * 4 + 2).toInt() and 255
                    px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                setPixels(px, 0, l.texWidth, 0, 0, l.texWidth, l.texHeight)
            }.also { if (lutCache.size > 6) lutCache.keys.firstOrNull()?.let { k -> if (k != l) lutCache.remove(k) } }
        }

        private fun shaderOf(b: Bitmap, linear: Boolean): BitmapShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = if (linear) BitmapShader.FILTER_MODE_LINEAR else BitmapShader.FILTER_MODE_NEAREST
        }

        private fun curvesBitmap(c: Array<FloatArray>?): Bitmap {
            val k = c?.contentDeepHashCode() ?: 0
            curveCache[k]?.let { (arr, bmp) -> if (arr === c || (arr != null && c != null && arr.contentDeepEquals(c)) || (arr == null && c == null)) return bmp }
            return makeCurves(c).also {
                if (curveCache.size > 16) curveCache.clear()
                curveCache[k] = c to it
            }
        }

        private fun makeCurves(c: Array<FloatArray>?): Bitmap {
            val px = IntArray(256) { i ->
                fun ch(k: Int) = ((c?.get(k)?.get(i) ?: (i / 255f)) * 255f + 0.5f).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
            }
            return Bitmap.createBitmap(px, 256, 1, Bitmap.Config.ARGB_8888)
        }

        private val identityLut by lazy { CubeLut.identity(2) }

        private fun setLut(s: RuntimeShader, l: CubeLut?, mix: Float) {
            val lut = l ?: identityLut
            s.setInputShader("lut", shaderOf(lutBitmap(lut), false))
            s.setFloatUniform("uUseLut", if (l != null) 1f else 0f)
            s.setFloatUniform("uLutSize", lut.size.toFloat())
            s.setFloatUniform("uLutTiles", lut.tilesPerRow.toFloat())
            s.setFloatUniform("uLutTex", lut.texWidth.toFloat(), lut.texHeight.toFloat())
            s.setFloatUniform("uLutMin", lut.domainMin)
            s.setFloatUniform("uLutMax", lut.domainMax)
            s.setFloatUniform("uLutMix", mix)
        }

        private fun nodeEffect(p: GradeNode.Params, showKey: Boolean, w: Int, h: Int): RenderEffect {
            val s = nodeShaders.getOrNull(shaderIdx) ?: RuntimeShader(AgslShaders.NODE).also { nodeShaders += it }
            shaderIdx++
            s.setFloatUniform("uSize", w.toFloat(), h.toFloat())
            s.setFloatUniform("uLift", p.lift)
            s.setFloatUniform("uGammaExp", p.gammaExp)
            s.setFloatUniform("uGain", p.gain)
            s.setFloatUniform("uOffset", p.offset)
            s.setFloatUniform("uWb", p.wb)
            s.setFloatUniform("uContrast", p.contrast)
            s.setFloatUniform("uPivot", p.pivot)
            s.setFloatUniform("uSat", p.sat)
            s.setFloatUniform("uHue", GradeNodeEffect.transpose3(p.hueMat))
            s.setFloatUniform("uMix", p.mix)
            s.setFloatUniform("uUseCurves", if (p.curves != null) 1f else 0f)
            s.setInputShader("curves", shaderOf(curvesBitmap(p.curves), true))
            setLut(s, p.lut, p.lutMix)
            val q = p.qual
            s.setFloatUniform("uUseQual", if (q != null) 1f else 0f)
            s.setFloatUniform("uQualHue", (q?.hue ?: 0f) / 360f, (q?.hueWidth ?: 360f) / 720f, q?.soft ?: 0.1f)
            s.setFloatUniform("uQualSat", q?.satLo ?: 0f, q?.satHi ?: 1f, q?.soft ?: 0.1f)
            s.setFloatUniform("uQualLum", q?.lumLo ?: 0f, q?.lumHi ?: 1f, q?.soft ?: 0.1f)
            s.setFloatUniform("uQualInv", if (q?.invert == true) 1f else 0f)
            val wi = p.win
            s.setFloatUniform("uUseWin", if (wi != null) 1f else 0f)
            s.setFloatUniform("uWin", wi?.cx ?: 0.5f, wi?.cy ?: 0.5f, wi?.rx ?: 0.3f, wi?.ry ?: 0.3f)
            s.setFloatUniform("uWinOpt", (wi?.shape ?: 1).toFloat(), wi?.soft ?: 0.3f, if (wi?.invert == true) 1f else 0f)
            val ang = Math.toRadians((wi?.angle ?: 0f).toDouble())
            s.setFloatUniform("uWinRot", kotlin.math.cos(ang).toFloat(), kotlin.math.sin(ang).toFloat())
            s.setFloatUniform("uBlur", if (showKey) 0f else p.blur)
            s.setFloatUniform("uPixel", if (showKey) 0f else p.pixel)
            s.setFloatUniform("uShowKey", if (showKey) 1f else 0f)
            return RenderEffect.createRuntimeShaderEffect(s, "content")
        }

        private fun lookEffect(l: Look): RenderEffect {
            val s = lookShader ?: RuntimeShader(AgslShaders.LOOK).also { lookShader = it }
            val p = LookParams.of(l)
            s.setFloatUniform("uScale", p.scale)
            s.setFloatUniform("uContrastFactor", p.contrastFactor)
            s.setFloatUniform("uSatShift", p.satShift)
            setLut(s, l.lut?.takeIf { l.lutMix > 0.005f }, l.lutMix.coerceIn(0f, 1f))
            return RenderEffect.createRuntimeShaderEffect(s, "content")
        }

        /** The chain: the nodes in order, then the look. With [keyOf]: the nodes up to it, and its key. */
        private fun effectFor(grade: ClipGrade?, look: Look, keyOf: Int?, w: Int, h: Int): RenderEffect? {
            var e: RenderEffect? = null
            fun add(x: RenderEffect) { e = e?.let { RenderEffect.createChainEffect(x, it) } ?: x }
            val nodes = grade?.chain.orEmpty()
            for (n in nodes) {
                if (keyOf != null && n.id == keyOf) {
                    if (n.enabled) add(nodeEffect(n.params(), true, w, h))
                    return e
                }
                if (!n.isNeutral) add(nodeEffect(n.params(), false, w, h))
            }
            if (keyOf == null && !look.isNeutral) add(lookEffect(look))
            return e
        }
    }
}

/** AGSL variants of the shaders (same math as the GLSL versions in GradeShaders/TetraShaders). */
object AgslShaders {
    private const val LUT_FNS = """
uniform shader lut;
uniform float uUseLut;
uniform float uLutSize;
uniform float uLutTiles;
uniform float2 uLutTex;
uniform float3 uLutMin;
uniform float3 uLutMax;
uniform float uLutMix;

float3 fetchLut(float3 i) {
  float tx = mod(i.b, uLutTiles);
  float ty = floor(i.b / uLutTiles);
  return lut.eval(float2(tx * uLutSize + i.r + 0.5, ty * uLutSize + i.g + 0.5)).rgb;
}

float3 lut3(float3 rgb) {
  float3 p = clamp((rgb - uLutMin) / (uLutMax - uLutMin), 0.0, 1.0) * (uLutSize - 1.0);
  float3 b = min(floor(p), float3(uLutSize - 2.0));
  float3 f = p - b;
  float3 c000 = fetchLut(b);
  float3 c111 = fetchLut(b + float3(1.0));
  float3 res;
  if (f.r >= f.g) {
    if (f.g >= f.b) {
      res = (1.0 - f.r) * c000 + (f.r - f.g) * fetchLut(b + float3(1.0, 0.0, 0.0)) + (f.g - f.b) * fetchLut(b + float3(1.0, 1.0, 0.0)) + f.b * c111;
    } else if (f.r >= f.b) {
      res = (1.0 - f.r) * c000 + (f.r - f.b) * fetchLut(b + float3(1.0, 0.0, 0.0)) + (f.b - f.g) * fetchLut(b + float3(1.0, 0.0, 1.0)) + f.g * c111;
    } else {
      res = (1.0 - f.b) * c000 + (f.b - f.r) * fetchLut(b + float3(0.0, 0.0, 1.0)) + (f.r - f.g) * fetchLut(b + float3(1.0, 0.0, 1.0)) + f.g * c111;
    }
  } else {
    if (f.b >= f.g) {
      res = (1.0 - f.b) * c000 + (f.b - f.g) * fetchLut(b + float3(0.0, 0.0, 1.0)) + (f.g - f.r) * fetchLut(b + float3(0.0, 1.0, 1.0)) + f.r * c111;
    } else if (f.b >= f.r) {
      res = (1.0 - f.g) * c000 + (f.g - f.b) * fetchLut(b + float3(0.0, 1.0, 0.0)) + (f.b - f.r) * fetchLut(b + float3(0.0, 1.0, 1.0)) + f.r * c111;
    } else {
      res = (1.0 - f.g) * c000 + (f.g - f.r) * fetchLut(b + float3(0.0, 1.0, 0.0)) + (f.r - f.b) * fetchLut(b + float3(1.0, 1.0, 0.0)) + f.b * c111;
    }
  }
  return res;
}

float3 toHsl(float3 c) {
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  float l = (mx + mn) * 0.5;
  float d = mx - mn;
  float s = 0.0;
  float h = 0.0;
  if (d > 0.00001) {
    s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
    if (mx == c.r) {
      h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
    } else if (mx == c.g) {
      h = (c.b - c.r) / d + 2.0;
    } else {
      h = (c.r - c.g) / d + 4.0;
    }
    h = h / 6.0;
  }
  return float3(h, s, l);
}
"""

    /** A grade node (like GradeShaders.FRAGMENT), plus the key view. */
    const val NODE = """
uniform shader content;
uniform shader curves;
uniform float2 uSize;
uniform float3 uLift;
uniform float3 uGammaExp;
uniform float3 uGain;
uniform float3 uOffset;
uniform float3 uWb;
uniform float uContrast;
uniform float uPivot;
uniform float uSat;
uniform float3x3 uHue;
uniform float uMix;
uniform float uUseCurves;
uniform float uUseQual;
uniform float3 uQualHue;
uniform float3 uQualSat;
uniform float3 uQualLum;
uniform float uQualInv;
uniform float uUseWin;
uniform float4 uWin;
uniform float3 uWinOpt;
uniform float2 uWinRot;
uniform float uBlur;
uniform float uPixel;
uniform float uShowKey;
""" + LUT_FNS + """
float band(float v, float lo, float hi, float soft) {
  float s = max(soft, 0.001);
  return smoothstep(lo - s, lo, v) * (1.0 - smoothstep(hi, hi + s, v));
}

float keyOf(float3 inRgb, float2 pos) {
  float k = 1.0;
  if (uUseQual > 0.5) {
    float3 hsl = toHsl(inRgb);
    float kh = 1.0;
    if (uQualHue.y < 0.5) {
      float dh = abs(fract(hsl.x - uQualHue.x + 0.5) - 0.5);
      kh = 1.0 - smoothstep(uQualHue.y, uQualHue.y + max(uQualHue.z, 0.001), dh);
    }
    float kq = kh * band(hsl.y, uQualSat.x, uQualSat.y, uQualSat.z) * band(hsl.z, uQualLum.x, uQualLum.y, uQualLum.z);
    if (uQualInv > 0.5) kq = 1.0 - kq;
    k = k * kq;
  }
  if (uUseWin > 0.5) {
    float2 p = pos / uSize;
    float aspect = uSize.x / uSize.y;
    float2 e = p - uWin.xy;
    e.x *= aspect;
    e = float2(e.x * uWinRot.x + e.y * uWinRot.y, -e.x * uWinRot.y + e.y * uWinRot.x);
    e.x /= aspect;
    float2 d2 = e / max(uWin.zw, float2(0.0001));
    float d = uWinOpt.x > 1.5 ? max(abs(d2.x), abs(d2.y)) : length(d2);
    float s = max(uWinOpt.y * 0.5, 0.001);
    float kw = 1.0 - smoothstep(1.0 - s, 1.0 + s, d);
    if (uWinOpt.z > 0.5) kw = 1.0 - kw;
    k = k * kw;
  }
  return k;
}

float3 colorIn(float3 src, float2 pos) {
  float2 q = pos;
  float3 cin = src;
  if (uPixel > 0.0) {
    float b = max(uPixel * uSize.x, 1.0);
    q = (floor(pos / b) + 0.5) * b;
    cin = float3(content.eval(q).rgb);
  }
  if (uBlur > 0.0) {
    float3 acc = float3(0.0);
    float ws = 0.0;
    float r = uBlur * uSize.x;
    for (int i = 0; i < 32; i++) {
      float fi = float(i);
      float rr = sqrt((fi + 0.5) / 32.0);
      float a = fi * 2.39996323;
      float wgt = 1.0 - 0.5 * rr;
      float2 o = q + float2(cos(a), sin(a)) * (rr * r);
      acc += float3(content.eval(o).rgb) * wgt;
      ws += wgt;
    }
    cin = acc / ws;
  }
  return cin;
}

half4 main(float2 pos) {
  half4 srcH = content.eval(pos);
  float3 src = float3(srcH.rgb);
  float k = keyOf(src, pos);
  if (uShowKey > 0.5) {
    return half4(half3(k), srcH.a);
  }
  float3 c = colorIn(src, pos) + uOffset;
  c = c + uLift * (float3(1.0) - c);
  c = pow(max(c, float3(0.0)), uGammaExp);
  c = c * uGain;
  c = (c - float3(uPivot)) * uContrast + float3(uPivot);
  c = c * uWb;
  c = uHue * c;
  float y = dot(c, float3(0.2126, 0.7152, 0.0722));
  c = clamp(float3(y) + (c - float3(y)) * uSat, 0.0, 1.0);
  if (uUseCurves > 0.5) {
    c = float3(curves.eval(float2(c.r * 255.0 + 0.5, 0.5)).r, curves.eval(float2(c.g * 255.0 + 0.5, 0.5)).g, curves.eval(float2(c.b * 255.0 + 0.5, 0.5)).b);
  }
  if (uUseLut > 0.5) {
    c = mix(c, clamp(lut3(c), 0.0, 1.0), uLutMix);
  }
  return half4(half3(mix(src, c, uMix * k)), srcH.a);
}
"""

    /** The timeline's look (like CompositionBuilder.videoEffects: RGB scale, LUT, contrast, saturation). */
    const val LOOK = """
uniform shader content;
uniform float3 uScale;
uniform float uContrastFactor;
uniform float uSatShift;
""" + LUT_FNS + """
half4 main(float2 pos) {
  half4 srcH = content.eval(pos);
  float3 c = clamp(float3(srcH.rgb) * uScale, 0.0, 1.0);
  if (uUseLut > 0.5) {
    c = mix(c, clamp(lut3(c), 0.0, 1.0), uLutMix);
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
      c = clamp(li + (c - li) * (s1 / s0), 0.0, 1.0);
    }
  }
  return half4(half3(c), srcH.a);
}
"""
}
