package se.gendut.klipp.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Exact word edges for text-based editing: Whisper says *what* was said, a CTC speech model says *where* each letter
 * sounds. The words Whisper heard are forced through the CTC model's letter probabilities (Viterbi), which places each
 * word within one 20 ms frame; the edges are then moved to where the word's sound really starts and dies away (level
 * against the word's own peak and the recording's noise floor). Measured on Swedish speech with known word edges
 * (2026-11-21, noise at 20 and 10 dB SNR): starts and ends within a few ms in the typical case, against about 40 ms
 * with Whisper's DTW times.
 *
 * Model: KBLab's wav2vec2 large VoxRex Swedish (CC0), as ONNX (OpenVoiceOS export, pinned revision), run with ONNX
 * Runtime on the CPU. Swedish only for now; other languages keep Whisper's times.
 */
object WordAligner {
    /** One file of the model: pinned URL, SHA-256 and size. */
    class Part(val name: String, val url: String, val sha256: String, val bytes: Long)

    private const val REV = "ca6e90fe72df91cab9bd40118a9661f741c03b12"
    private const val BASE = "https://huggingface.co/OpenVoiceOS/wav2vec2-large-voxrex-swedish-onnx/resolve/$REV"
    val PARTS = listOf(
        Part("model.onnx", "$BASE/model.onnx", "cb02e1e3bf3aab0ec7fce34396524ee8e18dee595542b75475d21934810dd04c", 3_313_972),
        Part("model.onnx.data", "$BASE/model.onnx.data", "966a0c7dd7ca041e3a2a9ca82f651de440ade394c4d675321a91b114fba65e4a", 1_261_961_216),
    )
    val BYTES = PARTS.sumOf { it.bytes }
    const val LANGUAGE = "sv"

    fun dir(c: Context) = File(c.filesDir, "speech/align-sv-$REV").apply { mkdirs() }
    fun ready(c: Context) = PARTS.all { p -> File(dir(c), p.name).let { it.isFile && it.length() == p.bytes } }
    fun delete(c: Context) { dir(c).deleteRecursively() }

    /** Whether the user turned exact word edges on (the model is downloaded on demand). */
    fun enabled(c: Context) = c.getSharedPreferences("klipp", Context.MODE_PRIVATE).getBoolean("alignWords", true)
    fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences("klipp", Context.MODE_PRIVATE).edit().putBoolean("alignWords", on).apply()

    /** Downloads both files (resumable, checked against the pinned hashes). [onProgress] 0..1 over all bytes. */
    suspend fun download(c: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        var before = 0L
        for (p in PARTS) {
            val m = Download(p.url, p.sha256, p.bytes, File(dir(c), p.name))
            val base = before
            m.run { got -> onProgress(((base + got).toDouble() / BYTES).toFloat()) }
            before += p.bytes
        }
        onProgress(1f)
    }

    /** The CTC vocabulary (index = token id): blank, specials, word separator, then the letters. */
    val VOCAB: List<String> = listOf("<blk>", "<s>", "</s>", "<unk>", "|") +
        "TEANRSILDOMKGUVFHÄÅPÖBJCYXWZÉQ8259167340'".map { it.toString() }
    const val BLANK = 0
    const val SEP = 4
    /** Model frame: 320 samples at 16 kHz. */
    const val FRAME_S = 0.02

    /** A loaded model (close it; it holds about 1.3 GB). */
    class Session(c: Context) : AutoCloseable {
        private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        private val s = env.createSession(File(dir(c), "model.onnx").absolutePath, ai.onnxruntime.OrtSession.SessionOptions().apply {
            setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2 + 1).coerceIn(2, 6))
            setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
        })

        /** Letter log-probabilities, one row per 20 ms frame, for 16 kHz mono [pcm] (windows of 30 s, 1 s overlap). */
        suspend fun logProbs(pcm: FloatArray): Array<FloatArray> {
            val win = 30 * SpeechAudio.RATE
            val ctxS = SpeechAudio.RATE
            val hop = 320
            val frames = pcm.size / hop
            val out = arrayOfNulls<FloatArray>(frames)
            var a = 0
            while (a < pcm.size) {
                currentCoroutineContext().ensureActive()
                val from = (a - ctxS).coerceAtLeast(0)
                val to = minOf(pcm.size, a + win - ctxS)
                val n = to - from
                if (n < hop * 2) break
                val x = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(pcm, from, n), longArrayOf(1, n.toLong()))
                val l = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(longArrayOf(n.toLong())), longArrayOf(1))
                try {
                    s.run(mapOf("input_values" to x, "input_lengths" to l)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val lp = (r[0].value as Array<Array<FloatArray>>)[0]
                        // keep the frames from [a] on (the context before belongs to the previous window), up to the next window
                        val f0 = from / hop
                        for (k in lp.indices) {
                            val f = f0 + k
                            if (f * hop < a || f >= frames) continue
                            if (out[f] == null) out[f] = lp[k]
                        }
                    }
                } finally { x.close(); l.close() }
                a = to
            }
            val empty = FloatArray(VOCAB.size) { if (it == BLANK) 0f else -30f }
            return Array(frames) { out[it] ?: empty }
        }

        override fun close() = s.close()
    }

    /**
     * Words with exact times: [words] (Whisper's, seconds relative to [pcm]'s start) are aligned in blocks (split
     * where Whisper heard a pause, at most ~20 s each) inside a little more than their own time. A word without any
     * letter the model knows, or a block that can't be aligned, keeps Whisper's times.
     */
    suspend fun align(session: Session, pcm: FloatArray, words: List<SpeechLine>): List<SpeechLine> = withContext(Dispatchers.Default) {
        if (words.isEmpty() || pcm.size < SpeechAudio.RATE / 4) return@withContext words
        val lp = session.logProbs(pcm)
        val level = Level(pcm)
        val out = words.toMutableList()
        for (block in Ctc.blocks(words)) {
            currentCoroutineContext().ensureActive()
            val ws = block.map { words[it] }
            val f0 = ((ws.first().start - 0.6) / FRAME_S).toInt().coerceIn(0, lp.size)
            val f1 = ((ws.last().end + 0.6) / FRAME_S).toInt().coerceIn(f0, lp.size)
            val times = Ctc.align(lp, f0, f1, ws.map { it.text }) ?: continue
            val edges = level.refine(times.map { p -> p?.let { it.first * FRAME_S to it.second * FRAME_S } })
            for ((k, i) in block.withIndex()) {
                val e = edges[k] ?: continue
                out[i] = words[i].copy(start = e.first, end = maxOf(e.second, e.first + 0.02))
            }
        }
        out
    }

    /** Level in 5 ms steps (dB), for moving the edges to where the sound starts and dies away. */
    class Level(pcm: FloatArray) {
        val hop = 80
        val db = FloatArray(pcm.size / hop) { i ->
            var s = 0.0
            for (j in i * hop until (i + 1) * hop) s += pcm[j] * pcm[j]
            (10 * log10(s / hop + 1e-12)).toFloat()
        }
        /** The recording's noise floor: the 10th percentile of the level. */
        val floor = if (db.isEmpty()) -120f else db.sortedArray()[db.size / 10]

        /** Each (start, end) in seconds moved outward while the level stays within 30 dB of the word's peak and 8 dB above the floor (never past a neighbour). */
        fun refine(edges: List<Pair<Double, Double>?>): List<Pair<Double, Double>?> {
            val r = 1.0 * hop / SpeechAudio.RATE
            val idx = edges.indices.filter { edges[it] != null }
            val res = edges.toMutableList()
            for ((j, k) in idx.withIndex()) {
                val (s, e) = edges[k]!!
                var a = (s / r).toInt().coerceIn(0, db.size)
                var b = (e / r).toInt().coerceIn(a, db.size)
                val lo = if (j > 0) (edges[idx[j - 1]]!!.second / r).toInt().coerceIn(0, a) else 0
                val hi = if (j + 1 < idx.size) (edges[idx[j + 1]]!!.first / r).toInt().coerceIn(b, db.size) else db.size
                if (b <= a || a >= db.size) continue
                var peak = -200f
                for (i in a until b) if (db[i] > peak) peak = db[i]
                val thr = maxOf(peak - 30f, floor + 8f)
                while (b < hi && db[b] > thr) b++
                while (a > lo && db[a - 1] > thr) a--
                res[k] = a * r to b * r
            }
            return res
        }
    }
}

/** CTC forced alignment (pure, testable): the letters of known words through per-frame log-probabilities. */
object Ctc {
    /** A word's letters as token ids (upper case; letters the model doesn't know are skipped). */
    fun tokens(word: String): IntArray = word.uppercase().mapNotNull { ch -> WordAligner.VOCAB.indexOf(ch.toString()).takeIf { it > WordAligner.SEP } }.toIntArray()

    /** Word indices in blocks: a new block after a pause of 0.7 s or more, or when a block reaches ~20 s. */
    fun blocks(words: List<SpeechLine>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        for (i in words.indices) {
            if (cur.isNotEmpty()) {
                val prev = words[cur.last()]; val first = words[cur.first()]
                if (words[i].start - prev.end >= 0.7 || words[i].end - first.start > 20.0) { out += cur; cur = ArrayList() }
            }
            cur += i
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /**
     * The frames (start inclusive, end exclusive, absolute frame numbers) of each word in frames [f0, f1) of [lp], or
     * null if the words don't fit. A word without known letters gets null.
     */
    fun align(lp: Array<FloatArray>, f0: Int, f1: Int, words: List<String>): List<Pair<Int, Int>?>? {
        val toks = ArrayList<Int>(); val owner = ArrayList<Int>()
        for ((k, w) in words.withIndex()) {
            val t = tokens(w)
            if (t.isEmpty()) continue
            if (toks.isNotEmpty()) { toks += WordAligner.SEP; owner += -1 }
            for (x in t) { toks += x; owner += k }
        }
        val n = f1 - f0
        val states = 2 * toks.size + 1
        if (toks.isEmpty() || n < toks.size) return null
        val lab = IntArray(states) { if (it % 2 == 0) WordAligner.BLANK else toks[it / 2] }
        val neg = -1e30f
        var prev = FloatArray(states) { neg }
        var cur = FloatArray(states)
        val back = Array(n) { ByteArray(states) }
        prev[0] = lp[f0][lab[0]]; prev[1] = lp[f0][lab[1]]
        for (t in 1 until n) {
            val row = lp[f0 + t]
            for (s in 0 until states) {
                var best = prev[s]; var k = 0
                if (s >= 1 && prev[s - 1] > best) { best = prev[s - 1]; k = 1 }
                if (s >= 2 && s % 2 == 1 && lab[s] != lab[s - 2] && prev[s - 2] > best) { best = prev[s - 2]; k = 2 }
                cur[s] = if (best <= neg) neg else best + row[lab[s]]
                back[t][s] = k.toByte()
            }
            val x = prev; prev = cur; cur = x
        }
        var s = if (prev[states - 1] >= prev[states - 2]) states - 1 else states - 2
        if (prev[s] <= neg / 2) return null
        val first = IntArray(words.size) { -1 }; val last = IntArray(words.size) { -1 }
        for (t in n - 1 downTo 0) {
            if (s % 2 == 1) {
                val w = owner[s / 2]
                if (w >= 0) { first[w] = f0 + t; if (last[w] < 0) last[w] = f0 + t + 1 }
            }
            s -= back[t][s]
        }
        return words.indices.map { k -> if (first[k] >= 0) first[k] to last[k] else null }
    }
}

/** A resumable download of one pinned file (checked against its SHA-256). */
class Download(private val url: String, private val sha256: String, private val bytes: Long, private val out: File) {
    suspend fun run(onBytes: (Long) -> Unit) = withContext(Dispatchers.IO) {
        if (out.isFile && out.length() == bytes) { onBytes(bytes); return@withContext }
        val part = File(out.path + ".part")
        var have = if (part.isFile) part.length() else 0L
        if (have > bytes) { part.delete(); have = 0 }
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) error("server responded $code")
            if (code == 200) have = 0
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { o ->
                    val buf = ByteArray(1 shl 16)
                    var n: Int
                    var last = 0L
                    while (input.read(buf).also { n = it } >= 0) {
                        currentCoroutineContext().ensureActive()
                        o.write(buf, 0, n); have += n
                        if (have - last > 1 shl 20) { last = have; onBytes(have) }
                    }
                    o.fd.sync()
                }
            }
        } finally { conn.disconnect() }
        if (part.length() != bytes) error("download incomplete (${part.length()} of $bytes bytes)")
        if (!SpeechModels.sha256(part).equals(sha256, ignoreCase = true)) { part.delete(); error("the downloaded file is not the expected one – try again") }
        if (!part.renameTo(out)) error("could not save the file")
        onBytes(bytes)
    }
}
