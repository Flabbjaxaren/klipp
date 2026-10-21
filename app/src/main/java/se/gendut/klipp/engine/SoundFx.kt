package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import se.gendut.klipp.R
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Sound effects that can be downloaded in the app – only CC0 material (free to use in any film, no credit required, and
 * free to redistribute):
 *  - packs from Kenney (kenney.nl), downloaded as a whole;
 *  - single sounds found on Freesound (freesound.org, CC0 only), with the user's own free API key.
 * Every sound is converted to 16-bit WAV on download (the engine's waveform, loudness and ducking read WAV everywhere)
 * and kept in filesDir/sfx, shared by all projects.
 */
object SoundFx {
    /**
     * A downloadable pack. [name] is the publisher's (proper) name; [descRes] says what is in it. Either a zip of
     * sound files ([url], Kenney) or a list of [clips] cut from single files (BigSoundBank).
     */
    data class Pack(
        val id: String, val name: String, @StringRes val descRes: Int, val url: String?, val mb: Float,
        val source: String = KENNEY, val clips: List<Clip> = emptyList(),
    )

    /**
     * One sound cut from a BigSoundBank file (its id): [t0]–[t1] s, raised [gainDb] (to a peak of about −1 dBFS),
     * faded out over [fade] s. The selection was made by measuring every candidate (length, level, attack) – files
     * often hold several takes, which are cut apart here.
     */
    data class Clip(val file: String, val t0: Double, val t1: Double, val name: String, val gainDb: Float, val fade: Double)

    const val KENNEY = "Kenney"
    const val BIGSOUNDBANK = "BigSoundBank"

    val PACKS = listOf(
        Pack("bsb-whoosh", "Whooshes & Transitions", R.string.sfx_pack_whoosh, null, 0.6f, BIGSOUNDBANK, listOf(
            Clip("1795", 0.249, 0.619, "Whoosh 3", 0.8f, 0.03),
            Clip("1796", 0.19, 0.668, "Whoosh 4", 1.3f, 0.03),
            Clip("1797", 0.18, 0.439, "Whoosh 5", 4.6f, 0.03),
            Clip("1799", 0.13, 0.439, "Whoosh 6", 0.8f, 0.03),
            Clip("1800", 0.2, 0.619, "Whoosh 7", 0.9f, 0.03),
            Clip("1801", 0.19, 0.639, "Whoosh 8", 0.8f, 0.03),
            Clip("1802", 0.06, 0.329, "Whoosh 9", 0.9f, 0.03),
            Clip("1798", 0.18, 0.449, "Whoosh 10", -1.1f, 0.03),
            Clip("0572", 0.08, 0.329, "Whoosh A 1", 2.5f, 0.03),
            Clip("0572", 1.147, 1.377, "Whoosh A 2", 1.8f, 0.03),
            Clip("0572", 6.535, 6.775, "Whoosh A 3", -0.5f, 0.03),
            Clip("0572", 7.543, 7.782, "Whoosh A 4", 0.9f, 0.03),
            Clip("0572", 9.598, 9.838, "Whoosh A 5", 3.9f, 0.03),
            Clip("0573", 1.377, 1.606, "Whoosh B 1", 6.6f, 0.03),
            Clip("0573", 2.514, 2.754, "Whoosh B 2", 4.3f, 0.03),
            Clip("0573", 5.857, 6.086, "Whoosh B 3", 8.5f, 0.03),
            Clip("0573", 9.429, 9.658, "Whoosh B 4", 6.1f, 0.03),
            Clip("0573", 12.033, 12.262, "Whoosh B 5", 5.0f, 0.03),
            Clip("0128", 0.02, 0.469, "Swish", 1.0f, 0.03),
            Clip("0127", 0.06, 0.619, "Blade swish", -0.5f, 0.03),
        )),
        Pack("bsb-social", "Social & UI", R.string.sfx_pack_social, null, 0.7f, BIGSOUNDBANK, listOf(
            Clip("2063", 0.0, 0.389, "Notification 1", -0.4f, 0.03),
            Clip("2064", 0.0, 0.389, "Notification 2", -0.4f, 0.03),
            Clip("2065", 0.0, 0.389, "Notification 3", -0.5f, 0.03),
            Clip("2066", 0.0, 0.389, "Notification 4", -0.5f, 0.03),
            Clip("2067", 0.0, 0.399, "Notification 5", -1.1f, 0.03),
            Clip("2059", 0.0, 0.289, "Notification 6", -1.0f, 0.03),
            Clip("2060", 0.0, 0.289, "Notification 7", -0.9f, 0.03),
            Clip("2061", 0.0, 0.289, "Notification 8", -1.1f, 0.03),
            Clip("1313", 0.05, 0.459, "Message sent", -1.2f, 0.03),
            Clip("1111", 0.0, 1.896, "Message", 10.9f, 0.03),
            Clip("1398", 0.04, 1.626, "Idea", -1.2f, 0.03),
            Clip("1399", 0.03, 1.546, "Idea 2", -1.3f, 0.03),
            Clip("0448", 0.229, 0.718, "Phone camera", -0.7f, 0.03),
            Clip("2394", 0.0, 0.359, "Camera shutter", 0.1f, 0.03),
            Clip("2395", 0.0, 0.379, "Camera shutter 2", -0.6f, 0.03),
            Clip("1417", 0.06, 0.190, "Register beep", -1.4f, 0.03),
            Clip("0462", 1.546, 1.766, "Pop 1", 6.4f, 0.03),
            Clip("0462", 2.644, 2.873, "Pop 2", 8.8f, 0.03),
            Clip("0551", 0.698, 0.938, "Balloon pop", -1.0f, 0.03),
            Clip("0479", 0.01, 0.898, "Counter bell", -0.9f, 0.03),
            Clip("2845", 0.09, 1.277, "Typewriter bell", -1.5f, 0.03),
            Clip("1742", 0.07, 0.379, "Mouse click", 0.6f, 0.03),
            Clip("1740", 0.06, 0.349, "Trackpad click", 1.6f, 0.03),
        )),
        Pack("bsb-comedy", "Comedy & Reactions", R.string.sfx_pack_comedy, null, 2.6f, BIGSOUNDBANK, listOf(
            Clip("2277", 0.08, 0.988, "Boing 1", -1.1f, 0.03),
            Clip("2279", 0.13, 1.157, "Boing 3", -1.1f, 0.03),
            Clip("2281", 0.11, 1.267, "Boing 5", -1.1f, 0.03),
            Clip("2283", 0.08, 0.738, "Boing 7", -1.0f, 0.03),
            Clip("0433", 0.0, 2.115, "Rimshot", -1.0f, 0.03),
            Clip("2410", 0.0, 0.399, "Rimshot (fail)", 5.0f, 0.03),
            Clip("2412", 0.0, 0.389, "Rimshot 2", 4.1f, 0.03),
            Clip("2401", 0.0, 3.342, "Drum roll", -0.3f, 0.03),
            Clip("2405", 0.0, 4.320, "Drum roll long", 0.4f, 0.03),
            Clip("2858", 0.02, 1.227, "Record scratch 1", -2.1f, 0.03),
            Clip("2861", 0.03, 1.407, "Record scratch 4", -2.4f, 0.03),
            Clip("2865", 0.03, 1.107, "Record scratch 8", -1.3f, 0.03),
            Clip("2869", 0.03, 0.639, "Record scratch 12", -1.6f, 0.03),
            Clip("2479", 0.02, 6.415, "Applause", -1.0f, 0.03),
            Clip("0020", 0.0, 8.000, "Applause, big crowd", -1.0f, 1.5),
            Clip("2093", 0.11, 3.572, "Laughter", -1.1f, 0.03),
            Clip("2097", 0.18, 2.574, "Laughter 2", -1.0f, 0.03),
            Clip("0475", 0.02, 3.751, "Laugh", -0.9f, 0.03),
            Clip("1483", 0.11, 5.328, "Gong", -1.1f, 0.03),
            Clip("2054", 0.18, 6.156, "Slide whistle", -1.1f, 0.03),
            Clip("2056", 0.06, 6.984, "Slide whistle 2", -1.1f, 0.03),
            Clip("2270", 0.0, 0.160, "Cartoon knock", -0.9f, 0.03),
            Clip("2261", 0.0, 0.329, "Cartoon bonk", -1.0f, 0.03),
        )),
        Pack("bsb-accent", "Accents & Hits", R.string.sfx_pack_accent, null, 1.9f, BIGSOUNDBANK, listOf(
            Clip("0243", 0.03, 0.619, "Heartbeat", 0.1f, 0.03),
            Clip("0244", 0.01, 0.619, "Heartbeat 2", 3.1f, 0.03),
            Clip("2079", 0.07, 2.245, "Magic chimes", -0.8f, 0.03),
            Clip("2081", 0.06, 1.058, "Magic chimes 2", -0.0f, 0.03),
            Clip("2086", 0.12, 2.205, "Magic chimes 3", -0.5f, 0.03),
            Clip("2088", 0.17, 2.085, "Wind chimes", -0.3f, 0.03),
            Clip("0920", 15.006, 17.191, "Glockenspiel", -1.1f, 0.03),
            Clip("2459", 0.07, 0.299, "Punch", 2.8f, 0.03),
            Clip("2461", 0.03, 0.349, "Punch 2", 1.8f, 0.03),
            Clip("2463", 0.02, 0.289, "Punch 3", 1.1f, 0.03),
            Clip("0597", 0.259, 0.469, "Slap", -0.5f, 0.03),
            Clip("1808", 0.0, 0.330, "Bang", -1.1f, 0.05),
            Clip("1023", 0.0, 1.227, "Distant boom", -1.0f, 0.03),
            Clip("0771", 6.216, 6.475, "Glass break", 4.7f, 0.03),
            Clip("1926", 0.0, 2.983, "Boxing bell", 1.7f, 0.03),
            Clip("2311", 0.0, 1.876, "Cymbal crash", -1.1f, 0.03),
            Clip("2274", 0.02, 1.317, "Finger cymbals", -0.7f, 0.03),
            Clip("0095", 5.178, 5.408, "Door knock", 1.1f, 0.03),
        )),
        Pack("kenney-impact", "Impact Sounds", R.string.sfx_pack_impact, "https://kenney.nl/media/pages/assets/impact-sounds/87b4ddecda-1677589768/kenney_impact-sounds.zip", 0.8f),
        Pack("kenney-interface", "Interface Sounds", R.string.sfx_pack_interface, "https://kenney.nl/media/pages/assets/interface-sounds/fa43c1dd4d-1677589452/kenney_interface-sounds.zip", 0.8f),
        Pack("kenney-ui", "UI Audio", R.string.sfx_pack_ui, "https://kenney.nl/media/pages/assets/ui-audio/490d233f68-1677590494/kenney_ui-audio.zip", 0.4f),
        Pack("kenney-rpg", "RPG Audio", R.string.sfx_pack_rpg, "https://kenney.nl/media/pages/assets/rpg-audio/8e99002d76-1677590336/kenney_rpg-audio.zip", 1.0f),
        Pack("kenney-scifi", "Sci-Fi Sounds", R.string.sfx_pack_scifi, "https://kenney.nl/media/pages/assets/sci-fi-sounds/6b296f9ecf-1677589334/kenney_sci-fi-sounds.zip", 5.9f),
        Pack("kenney-digital", "Digital Audio", R.string.sfx_pack_digital, "https://kenney.nl/media/pages/assets/digital-audio/216eac4753-1677590265/kenney_digital-audio.zip", 1.0f),
        Pack("kenney-jingles", "Music Jingles", R.string.sfx_pack_jingles, "https://kenney.nl/media/pages/assets/music-jingles/f37e530b9e-1677590399/kenney_music-jingles.zip", 1.2f),
    )

    /** Folder for the sounds found on Freesound. */
    const val FREESOUND = "freesound"

    /** A sound on the phone. */
    data class Sound(val file: File, val name: String, val seconds: Double)

    /** A Freesound search hit. */
    data class Hit(val id: Long, val name: String, val seconds: Double, val preview: String, val user: String)

    /** Rises when a pack or sound has been added or removed (the lists read the folders again). */
    var revision by mutableIntStateOf(0)
        private set

    /** Packs being downloaded: id → progress 0..1. */
    val progress = mutableStateMapOf<String, Float>()

    fun dir(context: Context) = File(context.filesDir, "sfx")

    private fun packDir(context: Context, id: String) = File(dir(context), id)

    fun installed(context: Context, pack: Pack) = File(packDir(context, pack.id), DONE).exists()

    /** The sounds of a pack (or [FREESOUND]), by name. */
    fun sounds(context: Context, id: String): List<Sound> =
        packDir(context, id).listFiles { f -> f.name.endsWith(".wav") }.orEmpty()
            .map { f -> Sound(f, label(f), wavSeconds(f)) }.sortedBy { it.name.lowercase() }

    /** The shown name: the sidecar's (Freesound) or the file name made readable. */
    private fun label(f: File): String =
        File(f.path.removeSuffix(".wav") + ".json").takeIf { it.exists() }
            ?.let { runCatching { JSONObject(it.readText()).optString("name") }.getOrNull() }?.takeIf { it.isNotBlank() }
            ?: humanize(f.nameWithoutExtension)

    fun remove(context: Context, pack: Pack) {
        packDir(context, pack.id).deleteRecursively()
        revision++
    }

    /**
     * Downloads [pack], unpacks its sounds (the zip's audio files, without the preview) and converts them to WAV.
     * Returns the number of sounds.
     */
    suspend fun download(context: Context, pack: Pack): Result<Int> = withContext(Dispatchers.IO) {
        if (pack.url == null) return@withContext downloadClips(context, pack)
        val out = packDir(context, pack.id)
        val zip = File(context.cacheDir, "sfx_${pack.id}.zip")
        progress[pack.id] = 0f
        try {
            fetch(pack.url, zip) { p -> progress[pack.id] = p * 0.5f }
            out.deleteRecursively(); out.mkdirs()
            val entries = ZipInputStream(zip.inputStream().buffered()).use { z ->
                generateSequence { z.nextEntry }.count { isSound(it.name) }
            }.coerceAtLeast(1)
            var n = 0
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!isSound(e.name)) continue
                    currentCoroutineContext().ensureActive()
                    val tmp = File(context.cacheDir, "sfx_src.${e.name.substringAfterLast('.')}")
                    tmp.outputStream().use { z.copyTo(it) }
                    // sub folders become part of the name ("8-Bit jingles/x.ogg" → "8-Bit jingles x")
                    val base = e.name.substringAfter("Audio/").substringBeforeLast('.').replace('/', ' ')
                    runCatching { decodeToWav(tmp.path, File(out, safe(base) + ".wav")) }
                        .onFailure { se.gendut.klipp.CrashReport.note("sfx: ${e.name}: ${it.message}") }
                        .onSuccess { n++ }
                    tmp.delete()
                    progress[pack.id] = 0.5f + 0.5f * n / entries
                }
            }
            if (n == 0) error("no sounds in the pack")
            File(out, DONE).writeText(pack.url)
            Result.success(n)
        } catch (e: Throwable) {
            out.deleteRecursively()
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally {
            zip.delete()
            progress.remove(pack.id)
            revision++
        }
    }

    /** A pack of [Pack.clips]: each source file once (several sounds can come from the same file), cut and leveled. */
    private suspend fun downloadClips(context: Context, pack: Pack): Result<Int> {
        val out = packDir(context, pack.id)
        progress[pack.id] = 0f
        val files = HashMap<String, File>()
        try {
            out.deleteRecursively(); out.mkdirs()
            var n = 0
            for ((i, c) in pack.clips.withIndex()) {
                currentCoroutineContext().ensureActive()
                val src = files.getOrPut(c.file) {
                    File(context.cacheDir, "bsb_${c.file}.ogg").also { fetch("https://bigsoundbank.com/UPLOAD/ogg/${c.file}.ogg", it) {} }
                }
                runCatching {
                    decodeToWav(src.path, File(out, safe(c.name) + ".wav"), c.t0, c.t1, c.gainDb, c.fade)
                    // the shown name (the file name can't hold all characters)
                    File(out, safe(c.name) + ".json").writeText(JSONObject().put("name", c.name).put("source", pack.source).put("file", c.file).toString())
                }
                    .onFailure { se.gendut.klipp.CrashReport.note("sfx: ${pack.id} ${c.name}: ${it.message}") }
                    .onSuccess { n++ }
                progress[pack.id] = (i + 1f) / pack.clips.size
            }
            if (n == 0) error("no sounds in the pack")
            File(out, DONE).writeText(pack.id)
            return Result.success(n)
        } catch (e: Throwable) {
            out.deleteRecursively()
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result.failure(e)
        } finally {
            files.values.forEach { it.delete() }
            progress.remove(pack.id)
            revision++
        }
    }

    private fun isSound(name: String) = !name.endsWith("/") && name.substringAfterLast('/') != "Preview.ogg" &&
        name.substringAfterLast('.').lowercase() in setOf("ogg", "wav", "mp3")

    /** Searches Freesound for CC0 sounds (the user's API key). */
    suspend fun search(key: String, query: String): Result<List<Hit>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val filter = URLEncoder.encode("license:\"Creative Commons 0\"", "UTF-8")
            val url = "https://freesound.org/apiv2/search/text/?query=$q&filter=$filter&page_size=30" +
                "&fields=id,name,duration,previews,username&token=" + URLEncoder.encode(key.trim(), "UTF-8")
            parseSearch(get(url))
        }
    }

    /** Freesound's answer → hits (with a high-quality preview to download). */
    fun parseSearch(json: String): List<Hit> {
        val o = JSONObject(json)
        o.optString("detail").takeIf { it.isNotBlank() }?.let { error(it) }
        val a = o.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val p = r.optJSONObject("previews") ?: return@mapNotNull null
            val url = p.optString("preview-hq-mp3").ifBlank { p.optString("preview-lq-mp3") }.ifBlank { return@mapNotNull null }
            Hit(r.optLong("id"), r.optString("name").removeSuffix(".wav").removeSuffix(".mp3"), r.optDouble("duration", 0.0), url, r.optString("username"))
        }
    }

    /** Downloads a Freesound hit (its preview) as WAV into the Freesound folder; already there = that file. */
    suspend fun fetch(context: Context, hit: Hit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val d = packDir(context, FREESOUND).apply { mkdirs() }
            val out = File(d, "${hit.id}.wav")
            if (!out.exists()) {
                val tmp = File(context.cacheDir, "fs_${hit.id}.mp3")
                try {
                    fetch(hit.preview, tmp) {}
                    decodeToWav(tmp.path, out)
                } finally { tmp.delete() }
                File(d, "${hit.id}.json").writeText(JSONObject().put("name", hit.name).put("user", hit.user).put("id", hit.id).put("license", "CC0").toString())
                revision++
            }
            out
        }
    }

    /**
     * The audio track for a sound effect at [at] lasting [dur] s: the first of A2, A3, A1 with nothing there (A1 is
     * usually the dialogue), else A3 (the sound is placed over what is there). [busy] = each track's clips (start, end).
     */
    fun freeTrack(busy: List<List<Pair<Double, Double>>>, at: Double, dur: Double): Int =
        listOf(1, 2, 0).firstOrNull { i -> busy.getOrNull(i).orEmpty().none { (a, b) -> a < at + dur - 1e-6 && b > at + 1e-6 } } ?: 2

    /** "footstep_carpet_000" → "Footstep carpet 000", "highUp" → "High up". */
    fun humanize(base: String): String {
        val s = base.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        return s.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifBlank { "sound" }

    private fun fetch(url: String, out: File, onProgress: (Float) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Klipp (Android video editor)")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        try {
            val ok = c.responseCode in 200..299
            val body = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "HTTP ${c.responseCode}")
            }
            return body
        } finally { c.disconnect() }
    }

    /**
     * Decodes any audio file Android can read (OGG Vorbis, MP3, …) to 16-bit PCM WAV – optionally only [t0]–[t1] s,
     * raised [gainDb] (with a soft limit instead of hard clipping), faded in 5 ms and out over [fade] s (no clicks at the cuts).
     */
    internal fun decodeToWav(src: String, out: File, t0: Double = 0.0, t1: Double = Double.MAX_VALUE, gainDb: Float = 0f, fade: Double = 0.0) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val tmp = File(out.path + ".tmp")
        try {
            ex.setDataSource(src)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(t)
            val f = ex.getTrackFormat(t)
            val dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            dec.configure(f, null, null, 0)
            dec.start()
            var rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                var bytes = 0L
                var framePos = 0L
                while (true) {
                    if (!inDone) {
                        val i = dec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                            if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        dec.outputFormat.let { of -> rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val b = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            val arr = ByteArray(info.size)
                            b.position(info.offset); b.get(arr)
                            val whole = t0 <= 0.0 && t1 == Double.MAX_VALUE && gainDb == 0f && fade <= 0.0
                            if (whole) { raf.write(arr); bytes += arr.size }
                            else {
                                // frames from [t0] to [t1] (counted from the decoder's output), gain and fades
                                val ch = channels.coerceAtLeast(1)
                                val frames = arr.size / (2 * ch)
                                val a0 = (t0 * rate).toLong(); val a1 = if (t1 == Double.MAX_VALUE) Long.MAX_VALUE else (t1 * rate).toLong()
                                val g = Math.pow(10.0, gainDb / 20.0)
                                val fadeIn = (0.005 * rate).toLong().coerceAtLeast(1); val fadeOut = (fade * rate).toLong()
                                val sb = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
                                val keep = ByteBuffer.allocate(arr.size).order(ByteOrder.LITTLE_ENDIAN)
                                for (f in 0 until frames) {
                                    val pos = framePos + f
                                    if (pos < a0 || pos >= a1) continue
                                    var k = g
                                    if (pos - a0 < fadeIn) k *= (pos - a0).toDouble() / fadeIn
                                    if (fadeOut > 0 && a1 != Long.MAX_VALUE && a1 - pos < fadeOut) k *= (a1 - pos).toDouble() / fadeOut
                                    for (c in 0 until ch) {
                                        val v = sb.getShort((f * ch + c) * 2) * k / 32768.0
                                        keep.putShort((softLimit(v) * 32767).toInt().toShort())
                                    }
                                }
                                raf.write(keep.array(), 0, keep.position())
                                bytes += keep.position()
                                framePos += frames
                                if (framePos >= a1) { dec.releaseOutputBuffer(o, false); break }
                            }
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                raf.seek(0)
                raf.write(wavHeader(bytes, rate, channels))
            }
            if (!tmp.renameTo(out)) error("could not save")
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            ex.release()
            tmp.delete()
        }
    }

    /** Linear up to 0.9 of full scale, then a smooth knee towards 1 (a raised peak never wraps or clips hard). */
    internal fun softLimit(v: Double): Double {
        val a = kotlin.math.abs(v)
        if (a <= 0.9) return v
        val y = 0.9 + 0.1 * kotlin.math.tanh((a - 0.9) / 0.1)
        return if (v < 0) -y else y
    }

    /** A 44-byte header for 16-bit PCM. */
    fun wavHeader(dataBytes: Long, rate: Int, channels: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort()); b.putInt(rate)
        b.putInt(rate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }

    /** Length of a 16-bit PCM WAV written by [decodeToWav] (from its header). */
    fun wavSeconds(f: File): Double = runCatching {
        val h = ByteArray(44)
        f.inputStream().use { it.read(h) }
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = b.getInt(28)
        val data = b.getInt(40)
        if (byteRate > 0) data.toDouble() / byteRate else 0.0
    }.getOrDefault(0.0)

    private const val DONE = ".done"
}
