package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.KlippPrecision
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * SDR video sampled as YUV with the file's own matrix and unclamped ([androidx.media3.effect.KlippYuv]) – a camera's
 * super-whites and sub-blacks reach the grade, and no driver picks another matrix than the file's. Only on a phone
 * that has passed this check: two bundled clips (H.264 8-bit, HEVC 10-bit) with known YUV codes, among them a
 * super-white and a sub-black, are decoded that way and must come out within [LIMIT] of the exact values. The result
 * is saved per phone (fingerprint); until then (and on failure) Media3's own sampling is used.
 */
@OptIn(UnstableApi::class)
object SdrYuvCheck {
    private const val VERSION = 1
    /** Largest allowed deviation (0..1) on any patch. */
    private const val LIMIT = 3.0 / 255

    /** The patches' codes (Y, Cb, Cr; 8-bit), row by row – as in the bundled clips (4 × 4 patches). */
    private val CODES8 = arrayOf(
        intArrayOf(16, 128, 128), intArrayOf(126, 128, 128), intArrayOf(235, 128, 128), intArrayOf(250, 128, 128),
        intArrayOf(5, 128, 128), intArrayOf(81, 90, 240), intArrayOf(145, 54, 34), intArrayOf(41, 240, 110),
        intArrayOf(170, 166, 16), intArrayOf(106, 202, 222), intArrayOf(210, 16, 146), intArrayOf(100, 160, 90),
        intArrayOf(150, 90, 170), intArrayOf(80, 140, 200), intArrayOf(190, 110, 140), intArrayOf(60, 150, 120),
    )

    /** The 10-bit clip's codes: the 8-bit ones × 4 (the super-white at 1000). */
    private fun codes10(): List<IntArray> = CODES8.map { c ->
        val y = if (c[0] == 250) 1000 else c[0] * 4
        intArrayOf(y.coerceIn(4, 1019), (c[1] * 4).coerceIn(4, 1019), (c[2] * 4).coerceIn(4, 1019))
    }

    /** The exact RGB (BT.709, limited range, unclamped) of [codes] at [bits]. */
    fun truth(codes: List<IntArray>, bits: Int): List<FloatArray> {
        val k = if (bits == 10) 4.0 else 1.0
        val kr = 0.2126; val kb = 0.0722; val kg = 1 - kr - kb
        return codes.map { c ->
            val y = (c[0] - 16 * k) / (219 * k); val cb = (c[1] - 128 * k) / (224 * k); val cr = (c[2] - 128 * k) / (224 * k)
            floatArrayOf((y + 2 * (1 - kr) * cr).toFloat(), (y - 2 * kb * (1 - kb) / kg * cb - 2 * kr * (1 - kr) / kg * cr).toFloat(), (y + 2 * (1 - kb) * cb).toFloat())
        }
    }

    /** The largest deviation of [decoded] from [truth] (0..1). */
    fun deviation(decoded: List<FloatArray>, truth: List<FloatArray>): Double {
        var d = 0.0
        for (i in 0 until minOf(decoded.size, truth.size)) for (c in 0..2) d = max(d, abs(decoded[i][c] - truth[i][c]).toDouble())
        return if (decoded.size < truth.size) Double.MAX_VALUE else d
    }

    private fun prefs(c: Context) = c.getSharedPreferences("sdryuv", Context.MODE_PRIVATE)
    private fun prefKey() = "v$VERSION|${android.os.Build.FINGERPRINT}|${KlippPrecision.highPrecision}"

    /** For the diagnostics. */
    @Volatile var line: String = ""
        private set
    @Volatile private var running = false

    /** Turns the YUV sampling on if this phone has passed (saved result). */
    fun init(context: Context) {
        val s = prefs(context).getString(prefKey(), null)
        KlippPrecision.sdrYuv = s == "on"
        line = when (s) { null -> "not checked"; "on" -> "on"; else -> "off ($s)" }
    }

    fun describe(): String = "YUV sampling: $line\n"

    /** Runs the check once per phone (and precision setting), on the main thread; [done] when it has finished. */
    fun check(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        if (running || prefs(app).getString(prefKey(), null) != null) { done(); return }
        running = true
        line = "checking"
        val main = Handler(Looper.getMainLooper())
        val dir = File(app.cacheDir, "sdryuv").apply { mkdirs() }
        var finished = false
        fun finish(result: String) {
            if (finished) return
            finished = true
            running = false
            prefs(app).edit().putString(prefKey(), result).apply()
            val on = result == "on"
            line = if (on) "on" else "off ($result)"
            se.gendut.klipp.CrashReport.note("YUV sampling check: $line")
            if (on != KlippPrecision.sdrYuv) {
                KlippPrecision.sdrYuv = on
                DecodeCalibration.decodingChanged()
                Proxies.decodingChanged(app)
            }
            dir.listFiles()?.forEach { it.delete() }
            done()
        }
        main.postDelayed({ finish("took too long") }, 40_000)
        // 8-bit first, then 10-bit (a phone without a 10-bit HEVC decoder skips it: it never plays such files)
        decode(app, "asset:///yuvcheck/yuvcheck8.mp4", File(dir, "out8.mp4"), main) { d8, err8 ->
            if (d8 == null) return@decode finish("8-bit: ${err8 ?: "no image"}")
            val e8 = deviation(d8, truth(CODES8.toList(), 8))
            if (e8 > LIMIT) return@decode finish("8-bit off by %.1f code values".format(java.util.Locale.US, e8 * 255))
            decode(app, "asset:///yuvcheck/yuvcheck10.mp4", File(dir, "out10.mp4"), main) { d10, err10 ->
                if (d10 == null) {
                    if (err10 != null && err10.contains("DECODER")) return@decode finish("on")
                    return@decode finish("10-bit: ${err10 ?: "no image"}")
                }
                val e10 = deviation(d10, truth(codes10(), 10))
                finish(if (e10 > LIMIT) "10-bit off by %.1f code values".format(java.util.Locale.US, e10 * 255) else "on")
            }
        }
    }

    /** Decodes [uri] through the YUV sampling and reads the patches (-0.25..1.25); [result] on the main thread. */
    private fun decode(app: Context, uri: String, out: File, main: Handler, result: (List<FloatArray>?, String?) -> Unit) {
        var read: List<FloatArray>? = null
        var called = false
        fun once(v: List<FloatArray>?, e: String?) { if (!called) { called = true; result(v, e) } }
        val probe = DecodeCalibration.ProbeEffect(skip = 3, wide = true) { v -> main.post { if (read == null) read = v } }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(uri)))
            .setRemoveAudio(true).setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(probe))).build()
        val t = Transformer.Builder(app)
            .setAssetLoaderFactory(Perf.assetLoaderFactory(app))
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setVideoFrameProcessorFactory(VideoPipeline.processorFactory(false, sdrYuv = true))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = once(read, null)
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                    once(null, exportException.errorCodeName)
            })
            .build()
        runCatching {
            t.start(Composition.Builder(EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItem(item).build()).build(), out.absolutePath)
        }.onFailure { once(null, it.message ?: "could not start") }
    }
}
