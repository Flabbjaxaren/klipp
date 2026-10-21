package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.KlippPrecision

/**
 * The video chain: precision, bit depth and the display surface.
 *
 * - Floating point (RGBA16F) through the whole Media3 chain ([KlippPrecision] in third_party/media3-effect): the
 *   decoder's image is sampled, the effects (nodes, film, look, titles, the compositor) and the final stage compute
 *   in 16-bit float.
 * - 10-bit out: the display surface (Program monitor) and the encoder input become RGBA_1010102
 *   ([TenBitGlObjectsProvider]). Export and proxies are encoded in HEVC Main10 when they should be 10-bit.
 * - 10-bit footage is recognized by the file's profile (HEVC Main10, AVC High10/4:2:2, VP9 profile 2/3, AV1 Main10,
 *   APV, ProRes/DNx) or HDR transfer ([bitDepth]).
 */
@OptIn(UnstableApi::class)
object VideoPipeline {
    private fun prefs(c: Context) = c.getSharedPreferences("klipp", Context.MODE_PRIVATE)

    /** Floating point through the whole chain (on by default). */
    var highPrecision by mutableStateOf(true)
        private set
    /** 10-bit display surface (read at app start – the player is created once). */
    var displayTenBit by mutableStateOf(true)
        private set
    /** HDR footage to SDR: 0 = OpenGL (in floating point, keeps 10 bits), 1 = the decoder (the phone's own tone mapping). */
    var hdrToneMap by mutableStateOf(0)
        private set
    /**
     * Preview short side (pixels): playback downscales the footage to it before the nodes, film and look are computed
     * (≈ 4× less work per stage for 4K at 1080). 0 = full resolution. Export and the node cache always compute at full
     * resolution from the originals.
     */
    var previewShort by mutableStateOf(1080)
        private set
    val PREVIEW_SIZES = listOf(720, 1080, 1440, 0)

    fun init(c: Context) {
        val p = prefs(c)
        highPrecision = p.getBoolean("video16f", true)
        displayTenBit = p.getBoolean("video10display", true)
        hdrToneMap = p.getInt("videoToneMap", 0).coerceIn(0, 1)
        // (older setting: on/off)
        previewShort = if (p.contains("videoPreviewShort")) p.getInt("videoPreviewShort", 1080).takeIf { it in PREVIEW_SIZES } ?: 1080
            else if (p.getBoolean("videoPreviewScale", true)) 1080 else 0
        KlippPrecision.highPrecision = highPrecision
    }

    fun setHighPrecision(c: Context, on: Boolean) {
        highPrecision = on; KlippPrecision.highPrecision = on
        prefs(c).edit().putBoolean("video16f", on).apply()
    }

    fun setDisplayTenBit(c: Context, on: Boolean) { displayTenBit = on; prefs(c).edit().putBoolean("video10display", on).apply() }
    fun setPreviewShort(c: Context, v: Int) { previewShort = v.takeIf { it in PREVIEW_SIZES } ?: 1080; prefs(c).edit().putInt("videoPreviewShort", previewShort).apply() }
    fun setHdrToneMap(c: Context, v: Int) { hdrToneMap = v.coerceIn(0, 1); prefs(c).edit().putInt("videoToneMap", hdrToneMap).apply() }

    /** The frame processor for an export/proxy: [tenBit] = encoder input RGBA_1010102. */
    fun processorFactory(tenBit: Boolean): DefaultVideoFrameProcessor.Factory =
        DefaultVideoFrameProcessor.Factory.Builder().setGlObjectsProvider(TenBitGlObjectsProvider(tenBit)).build()

    /** HDR → SDR per the setting (and the fallback if the first path didn't work on the phone). */
    fun toneMapMode(anyHdr: Boolean, firstFailed: Boolean): Int {
        if (!anyHdr) return androidx.media3.transformer.Composition.HDR_MODE_KEEP_HDR
        val gl = androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        val codec = androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC
        val canCodec = android.os.Build.VERSION.SDK_INT >= 33
        val first = if (hdrToneMap == 1 && canCodec) codec else gl
        val second = if (first == gl && canCodec) codec else gl
        return if (firstFailed) second else first
    }

    // ------------------------------------------------------------------------------------------- bit depth

    private val depthCache = HashMap<String, Int>()

    /** Already probed bit depth (8 or 10), otherwise null. */
    fun cachedBitDepth(uri: Uri): Int? = synchronized(depthCache) { depthCache[uri.toString()] }

    /** The file's bit depth (8 or 10) from the profile and color info. Not on the main thread. */
    fun bitDepth(context: Context, uri: Uri): Int {
        cachedBitDepth(uri)?.let { return it }
        val d = runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                f?.let { depthOf(it) } ?: 8
            } finally {
                ex.release()
            }
        }.getOrDefault(8)
        synchronized(depthCache) { depthCache[uri.toString()] = d }
        return d
    }

    /** The bit depth for a track format (8 or 10). */
    fun depthOf(f: MediaFormat): Int {
        val mime = f.getString(MediaFormat.KEY_MIME)?.lowercase() ?: return 8
        if (Perf.hdrFormat(f)) return 10
        if (mime.contains("apv") || mime.contains("prores") || mime.contains("dnx")) return 10
        val profile = if (f.containsKey(MediaFormat.KEY_PROFILE)) f.getInteger(MediaFormat.KEY_PROFILE) else return 8
        val p = MediaCodecInfo.CodecProfileLevel::class.java
        fun c(name: String) = runCatching { p.getField(name).getInt(null) }.getOrNull()
        val tenBit = when (mime) {
            MimeTypes.VIDEO_H265 -> listOf("HEVCProfileMain10", "HEVCProfileMain10HDR10", "HEVCProfileMain10HDR10Plus")
            MimeTypes.VIDEO_H264 -> listOf("AVCProfileHigh10", "AVCProfileHigh422", "AVCProfileHigh444")
            MimeTypes.VIDEO_VP9 -> listOf("VP9Profile2", "VP9Profile3", "VP9Profile2HDR", "VP9Profile3HDR", "VP9Profile2HDR10Plus", "VP9Profile3HDR10Plus")
            MimeTypes.VIDEO_AV1 -> listOf("AV1ProfileMain10", "AV1ProfileMain10HDR10", "AV1ProfileMain10HDR10Plus")
            else -> emptyList()
        }.mapNotNull { c(it) }
        return if (profile in tenBit) 10 else 8
    }

    // ------------------------------------------------------------------------------------------- encoders

    /** (profile, highest level) for 10-bit SDR in [mime] (HEVC Main10 / AV1 Main10), null = no encoder can. */
    fun tenBitProfile(mime: String): Pair<Int, Int>? = synchronized(profileCache) {
        profileCache.getOrPut(mime) {
            runCatching {
                val profile = when (mime) {
                    MimeTypes.VIDEO_H265 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    MimeTypes.VIDEO_AV1 -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                    else -> return@runCatching null
                }
                androidx.media3.transformer.EncoderUtil.getSupportedEncoders(mime).firstNotNullOfOrNull { info ->
                    val level = androidx.media3.transformer.EncoderUtil.findHighestSupportedEncodingLevel(info, mime, profile)
                    if (level != androidx.media3.transformer.EncoderUtil.LEVEL_UNSET) profile to level else null
                }
            }.getOrNull()
        }
    }
    private val profileCache = HashMap<String, Pair<Int, Int>?>()

    /** The codec for 10-bit SDR: HEVC if it supports Main10, otherwise AV1, otherwise none (null). */
    fun tenBitCodec(preferred: String): String? = when {
        preferred != MimeTypes.VIDEO_H264 && tenBitProfile(preferred) != null -> preferred
        tenBitProfile(MimeTypes.VIDEO_H265) != null -> MimeTypes.VIDEO_H265
        tenBitProfile(MimeTypes.VIDEO_AV1) != null -> MimeTypes.VIDEO_AV1
        else -> null
    }

    fun describe(): String =
        "video chain: ${if (highPrecision) "float (RGBA16F)" else "8-bit"} – pools ${KlippPrecision.floatPools} float / ${KlippPrecision.bytePools} 8-bit, " +
            "display ${if (displayTenBit) "10-bit requested" else "8-bit"}${TenBitGlObjectsProvider.lastDisplay?.let { " – got $it" } ?: ""}\n" +
            "playback: ${if (previewShort > 0) "preview ${previewShort}p" else "full resolution"}\n" + PassTimes.describe()
}

/**
 * GL objects for Media3: with [tenBit] the context and window surfaces (display, encoder input) are RGBA_1010102 even
 * for SDR – otherwise like Media3's own. If the phone lacks such a config it falls back to 8-bit.
 */
@OptIn(UnstableApi::class)
class TenBitGlObjectsProvider(private val tenBit: Boolean) : GlObjectsProvider {
    private val base = DefaultGlObjectsProvider()

    private fun config1010102(display: EGLDisplay): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102, 0, configs, 0, 1, n, 0)
        return if (ok && n[0] > 0) configs[0] else null
    }

    override fun createEglContext(eglDisplay: EGLDisplay, openGlVersion: Int, configAttributes: IntArray): EGLContext {
        val attrs = if (tenBit && config1010102(eglDisplay) != null) GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102 else configAttributes
        return base.createEglContext(eglDisplay, openGlVersion, attrs)
    }

    override fun createEglSurface(eglDisplay: EGLDisplay, surface: Any, colorTransfer: Int, isEncoderInputSurface: Boolean): EGLSurface {
        if (tenBit && (colorTransfer == C.COLOR_TRANSFER_SDR || colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2)) {
            val cfg = config1010102(eglDisplay)
            if (cfg != null) {
                val s = EGL14.eglCreateWindowSurface(eglDisplay, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
                if (s != null && s != EGL14.EGL_NO_SURFACE) {
                    if (!isEncoderInputSurface) lastDisplay = "RGBA_1010102"
                    return s
                }
            }
        }
        if (!isEncoderInputSurface) lastDisplay = if (colorTransfer == C.COLOR_TRANSFER_SDR || colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2) "RGBA_8888" else "HDR"
        return base.createEglSurface(eglDisplay, surface, colorTransfer, isEncoderInputSurface)
    }

    override fun createFocusedPlaceholderEglSurface(eglContext: EGLContext, eglDisplay: EGLDisplay): EGLSurface =
        base.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)

    override fun createBuffersForTexture(texId: Int, width: Int, height: Int): GlTextureInfo = base.createBuffersForTexture(texId, width, height)

    override fun release(eglDisplay: EGLDisplay) = base.release(eglDisplay)

    companion object {
        /** What kind of surface the Program monitor got last (diagnostics). */
        @Volatile var lastDisplay: String? = null
    }
}
