package se.gendut.klipp.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Beats and downbeats of music with Beat This! (CPJKU, JKU Linz, ISMIR 2024; the small0 checkpoint as ONNX, MIT, 10 MB
 * in the app's assets), run with ONNX Runtime – a network trained on many genres instead of the level rises [Beats]
 * looks for, so syncopation, soft attacks and tempo changes don't throw it.
 *
 * The network wants the reference front end exactly: 22 050 Hz mono (the channels' mean), a 1024-point STFT with hop 441
 * (50 frames per second), periodic Hann, centred with reflected edges, magnitudes divided by √1024, a 128-band mel
 * filterbank (30 Hz–11 kHz, Slaney), then log(1 + 1000·x). It is fed windows of 1500 frames (30 s); 6 frames at each
 * window's seams are left to the neighbour, and the first window's answer wins where they overlap.
 */
object BeatNet {
    const val RATE = 22_050
    const val ASSET = "beat_this_small0.onnx"
    private const val N_FFT = 1024
    private const val HOP = 441
    private const val MELS = 128
    private const val CHUNK = 1500
    private const val BORDER = 6
    const val FPS = 50.0

    class Result(val beats: List<Double>, val downbeats: List<Double>)

    /** Beats in [pcm] (22 050 Hz mono); null if the network can't run. */
    suspend fun detect(context: Context, pcm: FloatArray): Result? = withContext(Dispatchers.Default) {
        if (pcm.size < RATE) return@withContext Result(emptyList(), emptyList())
        val env = Ort.env
        val bytes = runCatching { context.assets.open(ASSET).use { it.readBytes() } }.getOrNull() ?: return@withContext null
        val session = runCatching { env.createSession(bytes, ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }) }.getOrNull()
            ?: return@withContext null
        session.use { s ->
            val f = features(pcm)
            val (b, d) = logits(f) { win ->
                currentCoroutineContext().ensureActive()
                val t = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(win), longArrayOf(1, (win.size / MELS).toLong(), MELS.toLong()))
                try {
                    s.run(mapOf(s.inputNames.first() to t)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val beat = (r[0].value as Array<FloatArray>)[0]
                        @Suppress("UNCHECKED_CAST")
                        val down = (r[1].value as Array<FloatArray>)[0]
                        beat to down
                    }
                } finally { t.close() }
            }
            // a beat right at the start is the edge of the analysed piece (its padding), not the music
            val beats = peaks(b).filter { it >= 0.06 }
            Result(beats, snap(peaks(d).filter { it >= 0.06 }, beats))
        }
    }

    /** The network over all frames in windows ([run] gets a window's features, row-major frames × 128). */
    suspend fun logits(f: Array<FloatArray>, run: suspend (FloatArray) -> Pair<FloatArray, FloatArray>): Pair<FloatArray, FloatArray> {
        val n = f.size
        val beat = FloatArray(n) { -100f }
        val down = FloatArray(n) { -100f }
        val done = BooleanArray(n)
        var start = 0
        while (true) {
            // the last window ends with the piece (shifted back so it is whole when the piece is long enough)
            val s0 = if (start + CHUNK > n) max(0, n - CHUNK) else start
            val len = min(CHUNK, n - s0)
            val win = FloatArray(len * MELS)
            for (i in 0 until len) System.arraycopy(f[s0 + i], 0, win, i * MELS, MELS)
            val (b, d) = run(win)
            val keepFrom = if (s0 == 0) 0 else BORDER
            val keepTo = if (s0 + len >= n) len else len - BORDER
            for (i in keepFrom until keepTo) {
                val k = s0 + i
                if (!done[k]) { beat[k] = b[i]; down[k] = d[i]; done[k] = true }
            }
            if (s0 + len >= n) break
            start = s0 + len - 2 * BORDER
        }
        return beat to down
    }

    /** Frames whose logit is above 0 and the largest within ±3 frames; neighbours one frame apart merge (their mean). Seconds. */
    fun peaks(l: FloatArray): List<Double> {
        val out = ArrayList<Double>()
        var last = -10.0
        for (i in l.indices) {
            if (l[i] <= 0f) continue
            var top = true
            for (j in max(0, i - 3)..min(l.size - 1, i + 3)) if (l[j] > l[i]) { top = false; break }
            if (!top) continue
            if (out.isNotEmpty() && i - last <= 1.0) { out[out.lastIndex] = (last + i) / 2 / FPS; last = i.toDouble(); continue }
            out += i / FPS
            last = i.toDouble()
        }
        return out
    }

    /** Each downbeat moved to the nearest beat (no duplicates). */
    fun snap(down: List<Double>, beats: List<Double>): List<Double> =
        if (beats.isEmpty()) down else down.map { d -> beats.minBy { kotlin.math.abs(it - d) } }.distinct()

    /** Log-mel features, one row of 128 per frame (see the class). */
    fun features(x: FloatArray): Array<FloatArray> {
        val frames = 1 + x.size / HOP
        val win = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
        val fb = filterbank()
        val re = DoubleArray(N_FFT); val im = DoubleArray(N_FFT)
        val mag = FloatArray(N_FFT / 2 + 1)
        val norm = 1.0 / sqrt(N_FFT.toDouble())
        return Array(frames) { t ->
            val c = t * HOP - N_FFT / 2
            for (i in 0 until N_FFT) {
                var k = c + i
                // reflect at the edges (centre = true)
                if (k < 0) k = -k
                if (k >= x.size) k = 2 * (x.size - 1) - k
                re[i] = x[k.coerceIn(0, x.size - 1)] * win[i].toDouble(); im[i] = 0.0
            }
            fft(re, im)
            for (b in mag.indices) mag[b] = (sqrt(re[b] * re[b] + im[b] * im[b]) * norm).toFloat()
            FloatArray(MELS) { m ->
                var s = 0f
                val row = fb[m]
                for (b in row.first until row.first + row.second.size) s += mag[b] * row.second[b - row.first]
                ln(1f + 1000f * s)
            }
        }
    }

    /** The mel filterbank (torchaudio's melscale_fbanks, Slaney scale, no normalization): per band (first bin, weights). */
    fun filterbank(): Array<Pair<Int, FloatArray>> {
        val nf = N_FFT / 2 + 1
        fun hz2mel(f: Double): Double { val fsp = 200.0 / 3; val lin = 1000.0 / fsp; val ls = ln(6.4) / 27; return if (f >= 1000) lin + ln(f / 1000) / ls else f / fsp }
        fun mel2hz(m: Double): Double { val fsp = 200.0 / 3; val lin = 1000.0 / fsp; val ls = ln(6.4) / 27; return if (m >= lin) 1000 * exp(ls * (m - lin)) else fsp * m }
        val m0 = hz2mel(30.0); val m1 = hz2mel(11_000.0)
        val fp = DoubleArray(MELS + 2) { mel2hz(m0 + (m1 - m0) * it / (MELS + 1)) }
        return Array(MELS) { m ->
            val w = FloatArray(nf) { b ->
                val f = b * (RATE / 2.0) / (nf - 1)
                val down = (f - fp[m]) / (fp[m + 1] - fp[m])
                val up = (fp[m + 2] - f) / (fp[m + 2] - fp[m + 1])
                max(0.0, min(down, up)).toFloat()
            }
            val first = w.indexOfFirst { it > 0f }.coerceAtLeast(0)
            val last = w.indexOfLast { it > 0f }.coerceAtLeast(first)
            first to w.copyOfRange(first, last + 1)
        }
    }

    /** In-place radix-2 complex FFT (n a power of two). */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
