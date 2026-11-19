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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Sound effects that can be downloaded in the app – only CC0 material (free to use in any film, no credit required, and
 * free to redistribute):
 *  - packs from Kenney (kenney.nl), downloaded as a whole;
 *  - clips cut from single sounds of BigSoundBank (bigsoundbank.com, CC0);
 *  - (earlier) single CC0 sounds found on Freesound: the search was removed in 2026-11 (Freesound's API terms don't
 *    cover a paid app); sounds downloaded before stay in their folder and usable.
 * Every sound is converted to 16-bit WAV on download (the engine's waveform, loudness and ducking read WAV everywhere)
 * and kept in filesDir/sfx, shared by all projects.
 */
object SoundFx {
    /**
     * A sound pack. [name] is the publisher's (proper) name; [descRes] says what is in it; [page] is where the
     * publisher offers it. Either a zip of sound files (no [clips], Kenney) or [clips] cut from single files
     * (BigSoundBank). The packs come with the app ([bundled]): added to the library from the app's own files, without
     * the internet – nothing is fetched from the publishers. A pack without one can't be added (packs already on the
     * phone stay).
     */
    data class Pack(
        val id: String, val name: String, @StringRes val descRes: Int, val page: String?, val mb: Float,
        val source: String = KENNEY, val clips: List<Clip> = emptyList(), val bundled: Bundled? = BUNDLED[id],
    )

    /**
     * A pack as a file in the app (assets/sounds; tools/sounds: the publishers' original files, CC0, checked and packed
     * with their license and credits). Used only if its [sha256] and [bytes] match.
     */
    data class Bundled(val file: String, val sha256: String, val bytes: Long) {
        val asset get() = "sounds/$file"
    }

    /** The set of sound pack files (tools/sounds/sounds-1.json); a new set is sounds-2 …, never changed files. */
    const val SOUNDS_RELEASE = "sounds-1"

    /** The packs in the app – [SOUNDS_RELEASE]'s files (tools/sounds/sounds-1.json, made by tools/sounds/build_sound_assets.py). */
    val BUNDLED: Map<String, Bundled> = mapOf(
        "kenney-impact" to Bundled("kenney-impact-sounds-1.zip", "029d734af1582474edf3a694d1b0cebc97c1c152f2f39fa34d4c2bafc5de77f8", 800850),
        "kenney-interface" to Bundled("kenney-interface-sounds-1.zip", "f2193d072726d6758a5f7871b2dcc54dcce0d5c35c6f0a62f92549b327c81232", 834536),
        "kenney-ui" to Bundled("kenney-ui-sounds-1.zip", "946fc23a63d535d693eb31b2eabb80c8c28d6351e2186b344ceb71b2cb1d5eb6", 411949),
        "kenney-rpg" to Bundled("kenney-rpg-sounds-1.zip", "6dbeaf8544da958d8f2adcb4a4a4b76c1ade34a05f8ab9edccd327da7375f38b", 964837),
        "kenney-scifi" to Bundled("kenney-scifi-sounds-1.zip", "119340f351a5098ad814f78719438c0da355a9ce8a4c8a3af6a8d48aa3d49e04", 5875104),
        "kenney-digital" to Bundled("kenney-digital-sounds-1.zip", "24e6ce28b76a6d8c89cff4d331e0965ff5c3de8a73c612028e9d363cc64e4f06", 990367),
        "kenney-jingles" to Bundled("kenney-jingles-sounds-1.zip", "b729ba57959bd58793d2c5cafa348aaf2655d354f3da35ec4729e03ec77197b8", 1239525),
        "bsb-whoosh" to Bundled("bsb-whoosh-sounds-1.zip", "4a6da52949c6210df740288fe46850218e46ef906718703d1b000e492b6c1baa", 573838),
        "bsb-social" to Bundled("bsb-social-sounds-1.zip", "a765221a7ec780aeb7bbcb07e080c9381bfce54d09a647f9887a5706dce43d85", 734789),
        "bsb-comedy" to Bundled("bsb-comedy-sounds-1.zip", "ab5d259ac63b794f32803a03c9d53087494ad64d11603a8e14b25c4432efd8ac", 2584433),
        "bsb-accent" to Bundled("bsb-accent-sounds-1.zip", "72c6fc7ec6d151aecfa35973c86f368887e90f9a05595c49a143a0943d9fd8c2", 1898601),
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
        Pack("bsb-whoosh", "Whooshes & Transitions", R.string.sfx_pack_whoosh, "https://bigsoundbank.com", 0.6f, BIGSOUNDBANK, listOf(
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
        Pack("bsb-social", "Social & UI", R.string.sfx_pack_social, "https://bigsoundbank.com", 0.7f, BIGSOUNDBANK, listOf(
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
        Pack("bsb-comedy", "Comedy & Reactions", R.string.sfx_pack_comedy, "https://bigsoundbank.com", 2.6f, BIGSOUNDBANK, listOf(
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
        Pack("bsb-accent", "Accents & Hits", R.string.sfx_pack_accent, "https://bigsoundbank.com", 1.9f, BIGSOUNDBANK, listOf(
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
        Pack("kenney-impact", "Impact Sounds", R.string.sfx_pack_impact, "https://kenney.nl/assets/impact-sounds", 0.8f),
        Pack("kenney-interface", "Interface Sounds", R.string.sfx_pack_interface, "https://kenney.nl/assets/interface-sounds", 0.8f),
        Pack("kenney-ui", "UI Audio", R.string.sfx_pack_ui, "https://kenney.nl/assets/ui-audio", 0.4f),
        Pack("kenney-rpg", "RPG Audio", R.string.sfx_pack_rpg, "https://kenney.nl/assets/rpg-audio", 1.0f),
        Pack("kenney-scifi", "Sci-Fi Sounds", R.string.sfx_pack_scifi, "https://kenney.nl/assets/sci-fi-sounds", 5.9f),
        Pack("kenney-digital", "Digital Audio", R.string.sfx_pack_digital, "https://kenney.nl/assets/digital-audio", 1.0f),
        Pack("kenney-jingles", "Music Jingles", R.string.sfx_pack_jingles, "https://kenney.nl/assets/music-jingles", 1.2f),
    )

    /** Folder of the sounds downloaded from Freesound before its search was removed (kept, still listed). */
    const val FREESOUND = "freesound"

    /** A sound on the phone. */
    data class Sound(val file: File, val name: String, val seconds: Double, val author: String? = null)

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
            .map { f -> Sound(f, label(f), wavSeconds(f), sidecar(f)?.optString("user")?.takeIf { it.isNotBlank() }) }.sortedBy { it.name.lowercase() }

    /** The sound's sidecar (name, source, author, license), if it has one. */
    private fun sidecar(f: File): JSONObject? =
        File(f.path.removeSuffix(".wav") + ".json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

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
        // only the app's own pinned files: nothing is fetched from the publishers
        val hosted = pack.bundled ?: return@withContext Result.failure(PackUnavailable(pack.id))
        if (pack.clips.isNotEmpty()) return@withContext downloadHostedClips(context, pack, hosted)
        val out = packDir(context, pack.id)
        val zip = File(context.cacheDir, "sfx_${pack.id}.zip")
        progress[pack.id] = 0f
        try {
            fetchVerified(context, hosted, zip) { p -> progress[pack.id] = p * 0.5f }
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
            File(out, DONE).writeText("$SOUNDS_RELEASE/${hosted.file} ${hosted.sha256}")
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

    /** A hosted pack of [Pack.clips]: one verified zip with the source files, cut as [downloadClips] does. */
    private suspend fun downloadHostedClips(context: Context, pack: Pack, hosted: Bundled): Result<Int> {
        val zip = File(context.cacheDir, "sfx_${pack.id}.zip")
        val dir = File(context.cacheDir, "sfx_${pack.id}_src").apply { deleteRecursively(); mkdirs() }
        return try {
            progress[pack.id] = 0f
            fetchVerified(context, hosted, zip) { p -> progress[pack.id] = p * 0.3f }
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (e.isDirectory || !name.endsWith(".ogg")) continue
                    File(dir, name).outputStream().use { z.copyTo(it) }
                }
            }
            downloadClips(context, pack, marker = "$SOUNDS_RELEASE/${hosted.file} ${hosted.sha256}") { id, dst ->
                File(dir, "$id.ogg").takeIf { it.isFile }?.copyTo(dst, overwrite = true) ?: error("$id.ogg is not in ${hosted.file}")
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally {
            zip.delete(); dir.deleteRecursively()
        }
    }

    /** A pack that isn't in the app: it can't be added (see [Pack]). */
    class PackUnavailable(id: String) : Exception("$id is not in the app")

    /** Copies the app's file [h] to [out] and keeps it only if it is the pinned file (size and SHA-256); otherwise throws. */
    private fun fetchVerified(context: Context, h: Bundled, out: File, onProgress: (Float) -> Unit) {
        context.assets.open(h.asset).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        onProgress(1f)
        if (!verified(out, h)) { out.delete(); error("${h.file} is not the expected file") }
    }

    /** Is [f] the pinned file [h]? */
    fun verified(f: File, h: Bundled): Boolean =
        f.length() == h.bytes && se.gendut.klipp.engine.SpeechModels.sha256(f).equals(h.sha256, ignoreCase = true)

    /**
     * A pack of [Pack.clips]: each source file once (several sounds can come from the same file), cut and leveled.
     * [source] puts a source file (its BigSoundBank id) into the given file.
     */
    private suspend fun downloadClips(context: Context, pack: Pack, marker: String, source: (String, File) -> Unit): Result<Int> {
        val out = packDir(context, pack.id)
        progress[pack.id] = 0f
        val files = HashMap<String, File>()
        try {
            out.deleteRecursively(); out.mkdirs()
            var n = 0
            for ((i, c) in pack.clips.withIndex()) {
                currentCoroutineContext().ensureActive()
                val src = files.getOrPut(c.file) { File(context.cacheDir, "bsb_${c.file}.ogg").also { source(c.file, it) } }
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
            File(out, DONE).writeText(marker)
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

    /**
     * The phone's own audio file [uri] copied into the app (files/audio): the file, its name without extension, its
     * length in seconds. Throws if it has no audio.
     */
    fun copyAudio(context: Context, uri: android.net.Uri): Triple<File, String, Double> {
        val shown = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "audio"
        val dir = File(context.filesDir, "audio").apply { mkdirs() }
        val safe = shown.replace(Regex("[^A-Za-z0-9._ -]"), "_").takeLast(80)
        var out = File(dir, safe)
        var k = 1
        while (out.exists()) out = File(dir, "${safe.substringBeforeLast('.')}-${k++}.${safe.substringAfterLast('.', "m4a")}")
        context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: error("cannot open the file")
        val ex = MediaExtractor()
        try {
            ex.setDataSource(out.path)
            val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: error("no audio")
            val us = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            if (us <= 0L) error("no length")
            return Triple(out, shown.substringBeforeLast('.').ifBlank { shown }, us / 1e6)
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            ex.release()
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
