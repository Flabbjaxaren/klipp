package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.inspector.MediaExtractorCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import se.gendut.klipp.R
import se.gendut.klipp.Str
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * On-device speech recognition: whisper.cpp (src/main/cpp) with NEON/dotprod on the Snapdragon's big cores.
 * No audio leaves the phone; the network is only used to download the model once.
 */
object Whisper {
    private val loaded by lazy { runCatching { System.loadLibrary("klipp_whisper") }.isSuccess }
    val available: Boolean get() = loaded

    /** Called from whisper.cpp with the percentage (0–100) of what is currently being transcribed. */
    fun interface Listener { fun onProgress(percent: Int) }

    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeAbort(handle: Long)
    @JvmStatic external fun nativeTranscribe(handle: Long, pcm: FloatArray, language: String, threads: Int, maxLen: Int, prompt: String?, vadModel: String?, listener: Listener?): ByteArray?
}

/**
 * The speech models: KBLab's KB-Whisper (National Library of Sweden, trained on Swedish – clearly best for Swedish) and
 * OpenAI's Whisper (multilingual, and English-only variants). [language] = the model's fixed language, "" = any.
 * [tier]: 0 fast, 1 balanced, 2 best. The file name is the enum name (downloaded models keep their names).
 */
enum class SpeechModel(val title: String, val url: String, val bytes: Long, val language: String, val tier: Int) {
    SV_FAST("KB-Whisper base", "https://huggingface.co/KBLab/kb-whisper-base/resolve/main/ggml-model-q5_0.bin", 55_295_450, "sv", 0),
    SV_BEST("KB-Whisper small", "https://huggingface.co/KBLab/kb-whisper-small/resolve/main/ggml-model-q5_0.bin", 175_209_680, "sv", 1),
    SV_LARGE("KB-Whisper medium", "https://huggingface.co/KBLab/kb-whisper-medium/resolve/main/ggml-model-q5_0.bin", 539_212_484, "sv", 2),
    MULTI_BASE("Whisper base", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin", 59_707_625, "", 0),
    MULTI("Whisper small", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190_085_487, "", 1),
    MULTI_TURBO("Whisper large-v3-turbo", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin", 574_041_195, "", 2),
    EN_BASE("Whisper base.en", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin", 59_721_011, "en", 0),
    EN_SMALL("Whisper small.en", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin", 190_098_681, "en", 1);

    /** "KB-Whisper small · 175 MB". */
    val detail: String get() = "$title · ${bytes / 1_000_000} MB"
    val fileName get() = "${name.lowercase()}.bin"
}

/**
 * What to listen for: a language ("auto" = whisper detects it, per 30 s window) and a quality tier. The model follows
 * from both – KB-Whisper for Swedish, the English-only models for English (best: large-v3-turbo), multilingual Whisper
 * for everything else. Saved in the app's preferences; the default language is the phone's, when Whisper knows it.
 */
object SpeechChoice {
    /** Languages offered first (Whisper's codes); any of Whisper's others can be set too. */
    val LANGUAGES = listOf("auto", "en", "sv", "da", "no", "fi", "de", "nl", "fr", "es", "it", "pt", "pl", "cs", "ru", "uk", "tr", "ar", "fa", "hi", "ja", "ko", "zh")

    fun model(language: String, tier: Int): SpeechModel {
        val t = tier.coerceIn(0, 2)
        return when (language) {
            "sv" -> listOf(SpeechModel.SV_FAST, SpeechModel.SV_BEST, SpeechModel.SV_LARGE)[t]
            "en" -> listOf(SpeechModel.EN_BASE, SpeechModel.EN_SMALL, SpeechModel.MULTI_TURBO)[t]
            else -> listOf(SpeechModel.MULTI_BASE, SpeechModel.MULTI, SpeechModel.MULTI_TURBO)[t]
        }
    }

    /** The language whisper is told: the model's own (KB, .en) or the chosen one. */
    fun whisperLanguage(model: SpeechModel, language: String): String = model.language.ifEmpty { language }

    /** The phone's language when Whisper has it ("nb"/"nn" are Norwegian, "no"), otherwise automatic. */
    fun defaultLanguage(): String {
        val l = java.util.Locale.getDefault().language.let { if (it == "nb" || it == "nn") "no" else it }
        return if (l in LANGUAGES) l else "auto"
    }

    fun language(c: Context): String = c.getSharedPreferences("klipp", Context.MODE_PRIVATE).getString("speechLang", null) ?: defaultLanguage()
    fun tier(c: Context): Int = c.getSharedPreferences("klipp", Context.MODE_PRIVATE).getInt("speechTier", 1)
    fun save(c: Context, language: String, tier: Int) {
        c.getSharedPreferences("klipp", Context.MODE_PRIVATE).edit().putString("speechLang", language).putInt("speechTier", tier).apply()
    }

    /** The language's name in the app's language ("Svenska", "English" …). */
    fun label(language: String): String =
        if (language == "auto") Str.t(R.string.transcribe_language_auto)
        else java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.titlecase() }
}

object SpeechModels {
    private fun dir(c: Context) = File(c.filesDir, "speech").apply { mkdirs() }

    /**
     * The voice activity model (Silero VAD, MIT, 885 kB), shipped in the app's assets and copied out once – whisper.cpp
     * needs a file path. Null if it can't be copied (then speech recognition runs without VAD).
     */
    fun vad(c: Context): String? = runCatching {
        val f = File(dir(c), VAD_FILE)
        if (f.length() != VAD_BYTES) {
            val tmp = File(dir(c), "$VAD_FILE.part")
            c.assets.open(VAD_FILE).use { i -> tmp.outputStream().use { i.copyTo(it) } }
            if (tmp.length() != VAD_BYTES || !tmp.renameTo(f)) error("vad copy")
        }
        f.absolutePath
    }.getOrNull()

    private const val VAD_FILE = "silero-vad-v6.2.0.bin"
    private const val VAD_BYTES = 885_098L
    fun file(c: Context, m: SpeechModel) = File(dir(c), m.fileName)
    fun ready(c: Context, m: SpeechModel) = file(c, m).let { it.isFile && it.length() == m.bytes }

    /** Downloads the model (resumes an interrupted download). [onProgress] 0..1. */
    suspend fun download(c: Context, m: SpeechModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val out = file(c, m)
        if (ready(c, m)) return@withContext
        val part = File(out.path + ".part")
        var have = if (part.isFile) part.length() else 0L
        if (have > m.bytes) { part.delete(); have = 0 }
        val conn = (URL(m.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) error("server responded $code")
            if (code == 200) have = 0 // the whole file from the start
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { o ->
                    val buf = ByteArray(1 shl 16)
                    var n: Int
                    var last = 0L
                    while (input.read(buf).also { n = it } >= 0) {
                        currentCoroutineContext().ensureActive()
                        o.write(buf, 0, n)
                        have += n
                        if (have - last > 512 * 1024) { last = have; onProgress((have.toDouble() / m.bytes).toFloat().coerceIn(0f, 1f)) }
                    }
                    // flush to storage now (otherwise 50–200 MB is written out in the background while speech recognition
                    // is computing, and an interrupted download can leave a file that looks complete but isn't)
                    o.fd.sync()
                }
            }
        } finally {
            conn.disconnect()
        }
        if (part.length() != m.bytes) error("model download incomplete (${part.length()} of ${m.bytes} bytes)")
        if (!part.renameTo(out)) error("could not save the model")
        onProgress(1f)
    }

    fun delete(c: Context, m: SpeechModel) { file(c, m).delete(); File(file(c, m).path + ".part").delete() }
}

/** Audio from a file as 16 kHz mono (what whisper wants). Same extractors and time base as playback. */
object SpeechAudio {
    const val RATE = 16_000

    /** [from]–[to] seconds in the file. The decoder is the hardware/system one; mixed down to mono and resampled. */
    suspend fun decode(context: Context, uri: Uri, from: Double, to: Double): FloatArray = withContext(Dispatchers.Default) {
        val ex = MediaExtractorCompat(androidx.media3.extractor.DefaultExtractorsFactory(), androidx.media3.datasource.DefaultDataSource.Factory(context))
        try {
            ex.setDataSource(uri, 0)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return@withContext FloatArray(0)
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            val out = FloatArrayBuilder(((to - from).coerceAtLeast(0.0) * RATE).toInt() + RATE)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                ex.seekTo((from * 1e6).toLong().coerceAtLeast(0), MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
                var rate = runCatching { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48_000)
                var channels = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
                var float = false
                val rs = Resampler()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            val st = ex.sampleTime
                            if (n < 0 || st > ((to + 0.1) * 1e6).toLong()) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, st, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(rate)
                        channels = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channels)
                        float = runCatching { f.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrNull() == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0) {
                            val b = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder())
                            b.position(info.offset); b.limit(info.offset + info.size)
                            val frames = info.size / (channels * (if (float) 4 else 2))
                            val t0 = info.presentationTimeUs / 1e6
                            for (k in 0 until frames) {
                                var s = 0f
                                for (ch in 0 until channels) s += if (float) b.float else b.short / 32768f
                                val t = t0 + k.toDouble() / rate
                                if (t < from || t >= to) continue
                                rs.push(s / channels, rate, out)
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (eos) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            out.toArray()
        } finally {
            ex.release()
        }
    }

    /** Resampling to 16 kHz: the mean over each output sample's interval (low-pass and decimation in one). */
    private class Resampler {
        private var acc = 0f
        private var n = 0
        private var pos = 0.0
        fun push(v: Float, rate: Int, out: FloatArrayBuilder) {
            acc += v; n++
            pos += RATE.toDouble() / rate
            while (pos >= 1.0) {
                out.add(if (n > 0) acc / n else v)
                acc = 0f; n = 0
                pos -= 1.0
            }
        }
    }

    class FloatArrayBuilder(cap: Int) {
        private var a = FloatArray(cap.coerceAtLeast(1024))
        var size = 0; private set
        fun add(v: Float) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = v
        }
        fun toArray() = a.copyOf(size)
    }
}

/** One line in the result: timeline seconds and the text. */
data class SpeechLine(val start: Double, val end: Double, val text: String)

/** From whisper to captions (pure logic, testable). */
object SpeechText {
    /** Known "hallucinations" in silence (Whisper learned them from subtitled films) and pure sound annotations. */
    private val junk = Regex("(?i)(textning\\.nu|svensktextning|undertext(er|ning)? (av|från)|www\\.|^\\W*$|^[\\[(][^\\])]*[\\])]$)")

    /** JSON from whisper (ms within the chunk) → lines on the timeline. [map] maps source seconds → the timeline. */
    fun parse(json: String, srcOffset: Double, map: (Double) -> Double): List<SpeechLine> {
        val a = JSONArray(json)
        val out = ArrayList<SpeechLine>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val text = o.optString("text").trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty() || junk.containsMatchIn(text)) continue
            val s = map(srcOffset + o.optLong("t0") / 1000.0)
            val e = map(srcOffset + o.optLong("t1") / 1000.0)
            if (e - s < 0.05) continue
            out += SpeechLine(s, e, text)
        }
        return out
    }

    /** Word segments from whisper (max_len 1) → words with media seconds; hallucinated junk and empty tokens dropped. */
    fun words(json: String, srcOffset: Double): List<SpeechLine> {
        val a = JSONArray(json)
        val out = ArrayList<SpeechLine>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val text = o.optString("text").trim()
            if (text.isEmpty()) continue
            val s = srcOffset + o.optLong("t0") / 1000.0
            val e = srcOffset + o.optLong("t1") / 1000.0
            // punctuation on its own belongs to the word before it
            if (text.none { it.isLetterOrDigit() }) { if (out.isNotEmpty()) out[out.lastIndex] = out.last().let { it.copy(text = it.text + text, end = maxOf(it.end, e)) }; continue }
            if (junk.containsMatchIn(text)) continue
            out += SpeechLine(s, maxOf(e, s), text)
        }
        return out
    }

    /** Sorted, without overlap (a line ends where the next begins), at least 0.7 s on screen when there is room. */
    fun tidy(lines: List<SpeechLine>): List<SpeechLine> {
        val s = lines.sortedBy { it.start }
        return s.mapIndexed { i, l ->
            val next = s.getOrNull(i + 1)?.start ?: Double.MAX_VALUE
            val end = maxOf(l.end, minOf(l.start + 0.7, next)).coerceAtMost(next)
            l.copy(end = end)
        }.filter { it.end - it.start >= 0.05 }
    }

    fun srt(lines: List<SpeechLine>): String = buildString {
        fun tc(t: Double): String {
            val ms = (t.coerceAtLeast(0.0) * 1000).roundToInt()
            return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }
        lines.forEachIndexed { i, l -> append(i + 1).append('\n').append(tc(l.start)).append(" --> ").append(tc(l.end)).append('\n').append(l.text).append("\n\n") }
    }
}

/**
 * Auto captions for an audio track: downloads the model if needed, decodes each clip's audio and
 * transcribes it on the phone. One job at a time; keeps going even if you switch modes in the app.
 */
object AutoCaptions {
    var progress by mutableStateOf<Float?>(null)
        private set
    var phase by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
    private val scope = MainScope()
    @Volatile private var handle = 0L
    /** Running (read by the UI – e.g. animations pause so the phone can put everything into speech recognition). */
    var running by mutableStateOf(false)
        private set
    private var job: Job? = null

    /** Downloads the model (a separate step, so you see the size before anything is downloaded). */
    fun download(context: Context, model: SpeechModel) {
        if (running) return
        message = null
        progress = 0f
        phase = Str.t(R.string.transcribe_downloading, model.bytes / 1_000_000)
        running = true
        job = scope.launch {
            try {
                SpeechModels.download(context, model) { p -> scope.launch { progress = p } }
                message = Str.t(R.string.transcribe_downloaded)
                modelRevision++
            } catch (e: CancellationException) {
                message = Str.t(R.string.transcribe_canceled)
                throw e
            } catch (e: Throwable) {
                message = Str.t(R.string.transcribe_download_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                progress = null
                phase = ""
                running = false
            }
        }
    }

    /** Increments when a model has been downloaded or deleted (the UI re-reads which ones exist). */
    var modelRevision by mutableStateOf(0)
        private set

    /** The clips on audio track [track] (index into the sequence's audio tracks). The model must be downloaded. */
    fun start(context: Context, controller: ProjectController, model: SpeechModel, language: String, track: Int) {
        if (running) return
        val q = controller.snapshot.sequence ?: run { message = Str.t(R.string.transcribe_no_sequence); return }
        val clips = q.audio.getOrNull(track)?.clips.orEmpty().filter { it.duration > 0.2 }.sortedBy { it.start }
        if (clips.isEmpty()) { message = Str.t(R.string.transcribe_no_audio, track + 1); return }
        launchJob(context, model, language) { recognize ->
            val lines = ArrayList<SpeechLine>()
            val total = clips.sumOf { it.duration * kotlin.math.abs(it.speed) }.coerceAtLeast(1e-3)
            var done = 0.0
            for ((i, c) in clips.withIndex()) {
                val uri = controller.uriOf(c.item) ?: continue
                val sp = kotlin.math.abs(c.speed).coerceAtLeast(1e-3)
                val srcLen = c.duration * sp
                // long clips in chunks of at most 5 minutes (memory: the audio is held in memory as floats)
                var a = c.sourceIn
                val end = c.sourceIn + srcLen
                while (a < end - 0.2) {
                    currentCoroutineContext().ensureActive()
                    val b = minOf(end, a + 300.0)
                    phase = Str.t(R.string.transcribe_listening, i + 1, clips.size)
                    val pcm = SpeechAudio.decode(context, uri, a, b)
                    if (pcm.size > SpeechAudio.RATE / 4) {
                        val base = done
                        val part = b - a
                        val json = recognize(pcm, 42) { pct -> progress = ((base + part * pct / 100.0) / total).toFloat().coerceIn(0f, 1f) }
                        lines += SpeechText.parse(json, a) { src -> c.start + (src - c.sourceIn) / sp }
                    }
                    done += b - a
                    progress = (done / total).toFloat().coerceIn(0f, 1f)
                    a = b
                }
            }
            val tidy = SpeechText.tidy(lines)
            if (tidy.isEmpty()) return@launchJob Str.t(R.string.transcribe_no_speech)
            val f = File(File(context.cacheDir, "captions").apply { mkdirs() }, "auto_${System.currentTimeMillis()}.srt")
            withContext(Dispatchers.IO) { f.writeText(SpeechText.srt(tidy)) }
            controller.importCaptionFile(f, Str.t(R.string.transcribe_track_name))
            Str.p(R.plurals.transcribe_done, tidy.size, tidy.size, seconds())
        }
    }

    /**
     * A word-level transcript of every media item on the audio tracks (text-based editing): each file's audio, word by
     * word, stored in the project with media times (the engine maps them to the timeline and follows every edit).
     */
    fun transcript(context: Context, controller: ProjectController, model: SpeechModel, language: String) {
        if (running) return
        val items = controller.speechItems()
        if (items.isEmpty()) { message = Str.t(R.string.transcribe_no_speech_clips); return }
        transcriptOf(context, controller, model, language, items)
    }

    /** The media item being transcribed by [transcriptOf] (the source monitor shows the progress for its clip). */
    var item by mutableStateOf<Long?>(null)
        private set

    /** A word-level transcript of the given media items (the source monitor: one clip from the media library). */
    fun transcriptOf(context: Context, controller: ProjectController, model: SpeechModel, language: String, items: List<Long>) {
        if (running || items.isEmpty()) return
        item = items.singleOrNull()
        launchJob(context, model, language) { recognize ->
            val durations = items.associateWith { id -> controller.snapshot.media.firstOrNull { it.id == id }?.duration ?: 0.0 }
            val total = durations.values.sum().coerceAtLeast(1e-3)
            var done = 0.0
            var count = 0
            for ((i, item) in items.withIndex()) {
                val uri = controller.uriOf(item) ?: continue
                val len = durations[item] ?: 0.0
                val words = ArrayList<SpeechLine>()
                var a = 0.0
                while (a < len - 0.2) {
                    currentCoroutineContext().ensureActive()
                    val b = minOf(len, a + 300.0)
                    phase = Str.t(R.string.transcribe_listening, i + 1, items.size)
                    val pcm = SpeechAudio.decode(context, uri, a, b)
                    if (pcm.size > SpeechAudio.RATE / 4) {
                        val base = done
                        val part = b - a
                        // one word per segment (max_len 1 with word splitting), with token timestamps
                        val json = recognize(pcm, 1) { pct -> progress = ((base + part * pct / 100.0) / total).toFloat().coerceIn(0f, 1f) }
                        words += SpeechText.words(json, a)
                    }
                    done += b - a
                    progress = (done / total).toFloat().coerceIn(0f, 1f)
                    a = b
                }
                // no speech found: an earlier transcript of the item (another language, model) must not stay behind
                if (words.isNotEmpty()) controller.setTranscript(item, SpeechChoice.whisperLanguage(model, language).takeIf { it != "auto" } ?: "", words)
                else controller.deleteTranscript(item)
                count += words.size
            }
            if (count == 0) Str.t(R.string.transcribe_no_speech) else Str.p(R.plurals.transcribe_words_done, count, count, seconds())
        }
    }

    /** The job's start time (for "… in 12 s"). */
    private class Run(val t0: Long = System.nanoTime()) { fun seconds() = (System.nanoTime() - t0) / 1e9 }

    /**
     * One speech job: the model is loaded (on the CPU) and freed afterwards. [work] gets a recognizer (pcm, max segment
     * length, progress → JSON) and returns the message to show.
     */
    private fun launchJob(context: Context, model: SpeechModel, language: String, work: suspend Run.(recognize: suspend (FloatArray, Int, (Int) -> Unit) -> String) -> String) {
        if (!Whisper.available) { message = Str.t(R.string.transcribe_unavailable); item = null; return }
        message = null
        progress = 0f
        running = true
        job = scope.launch {
            val run = Run()
            try {
                if (!SpeechModels.ready(context, model)) error("the model is not downloaded")
                phase = Str.t(R.string.transcribe_starting)
                val path = SpeechModels.file(context, model).absolutePath
                val lang = SpeechChoice.whisperLanguage(model, language)
                val vad = withContext(Dispatchers.IO) { SpeechModels.vad(context) }
                se.gendut.klipp.CrashReport.note("speech: start ${model.name}, language=$lang, vad=${vad != null}")
                val h = withContext(Dispatchers.IO) { Whisper.nativeInit(path) }
                if (h == 0L) error("the model could not be loaded")
                synchronized(handleLock) { handle = h }
                val threads = (Runtime.getRuntime().availableProcessors() / 2 + 1).coerceIn(2, 6)
                try {
                    message = run.work { pcm, maxLen, onPct ->
                        val out = withContext(Dispatchers.Default) {
                            Whisper.nativeTranscribe(h, pcm, lang, threads, maxLen, null, vad) { pct -> scope.launch { onPct(pct) } }
                        }
                        out?.toString(Charsets.UTF_8) ?: run { currentCoroutineContext().ensureActive(); error("speech recognition failed") }
                    }
                } finally {
                    // first remove it from [handle] (under the lock) – then no abort can reach the memory being freed
                    synchronized(handleLock) { handle = 0L }
                    withContext(NonCancellable + Dispatchers.IO) { Whisper.nativeFree(h) }
                }
            } catch (e: CancellationException) {
                message = Str.t(R.string.transcribe_canceled)
                throw e
            } catch (e: Throwable) {
                message = Str.t(R.string.transcribe_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                se.gendut.klipp.CrashReport.note("speech: finished after %.1f s".format(java.util.Locale.ROOT, run.seconds()))
                progress = null
                phase = ""
                running = false
                item = null
            }
        }
    }

    /** Guards [handle]: abort and free must never interleave (otherwise writes into freed memory). */
    private val handleLock = Any()

    fun cancel() {
        synchronized(handleLock) { handle.takeIf { it != 0L }?.let { Whisper.nativeAbort(it) } }
        job?.cancel()
    }
}
