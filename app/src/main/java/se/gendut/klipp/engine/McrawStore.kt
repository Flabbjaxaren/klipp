package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The developed MotionCam RAW clips. A .mcraw is developed at import into the app's own storage (filesDir/mcraw, never
 * the gallery) and the timeline uses that file; the .mcraw stays the source. The same file at the same settings reuses
 * its develop. index.json keeps what each develop was made from (source, EI, output), for the RAW settings. A develop
 * no project uses any more (the project or the clip deleted) is removed by [clean].
 */
object McrawStore {
    fun dir(c: Context) = File(c.filesDir, "mcraw").apply { mkdirs() }

    /** Bumped when develops are made or removed, so the storage figures in Settings follow. */
    var revision by mutableIntStateOf(0)
    private fun index(c: Context) = File(dir(c), "index.json")

    private val DEFAULT_OUT = OpenDrt.Input.SLOG3.key

    /** A quick develop's file name mark (half size, see [develop]'s draft). */
    private const val DRAFT = "_draft"

    /**
     * The develop of [src] (named [name]) at [ei] into [out] (an [OpenDrt.Input] key): the same source at the same
     * settings is the same file.
     */
    fun fileFor(c: Context, src: Uri, name: String, ei: Int, out: String = DEFAULT_OUT, draft: Boolean = false): File {
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_.-]"), "_").take(60)
        val key = src.toString() + "|ei" + ei + (if (out != DEFAULT_OUT) "|$out" else "") + (if (draft) "|draft" else "")
        val h = key.hashCode().toUInt().toString(16)
        return File(dir(c), "${base}_ei${ei}${if (out != DEFAULT_OUT) "_$out" else ""}${if (draft) DRAFT else ""}_$h.mp4")
    }

    /** A quick (half size) develop, still waiting for its full one ([finishDrafts]). */
    fun isDraft(c: Context, uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "file") return false
        val f = File(uri.path ?: return false)
        return f.parentFile?.absolutePath == dir(c).absolutePath && f.name.contains(DRAFT + "_")
    }

    /**
     * Develops [src] (or reuses its develop). [ei0] ≤ 0: Auto ([McrawConvert.autoEi], 800 when the file can't be
     * measured). [draft]: half size, no demosaic – quick, for editing; [finishDrafts] makes the full one in the
     * background. [auto]: the EI was picked by Auto (kept when a draft's EI is used again for its full develop).
     * Blocking: call off the main thread.
     */
    fun develop(c: Context, src: Uri, name: String, ei0: Int, out: String = DEFAULT_OUT, draft: Boolean = false,
                auto: Boolean = ei0 <= 0, progress: (Float) -> Unit): McrawConvert.Result {
        val ei = if (ei0 <= 0) McrawConvert.autoEi(c, src) ?: 800 else ei0
        val f = fileFor(c, src, name, ei, out, draft)
        if (f.exists() && f.length() > 0) {
            if (infoOfFile(c, f) == null) record(c, f, src, name, ei, out, auto)
            CameraLog.remember(Uri.fromFile(f), OpenDrt.Input.of(out))
            return McrawConvert.Result(Uri.fromFile(f), "Reused ${f.name}", "")
        }
        val r = McrawConvert.convert(c, src, f, ei, out, draft, progress)
        if (r.uri != null) {
            record(c, f, src, name, ei, out, auto); revision++
            CameraLog.remember(r.uri, OpenDrt.Input.of(out))
        }
        return r
    }

    @Synchronized private fun record(c: Context, f: File, src: Uri, name: String, ei: Int, out: String, auto: Boolean) {
        val o = runCatching { JSONObject(index(c).readText()) }.getOrDefault(JSONObject())
        o.put(f.name, JSONObject().put("source", src.toString()).put("name", name).put("ei", ei).put("auto", auto).put("out", out)
            .put("made", System.currentTimeMillis()))
        runCatching { index(c).writeText(o.toString()) }
    }

    /** What a develop was made from. */
    /**
     * A placeholder ([Placeholder]) for [src]'s develop at [ei] (≤ 0: Auto) into [out]: noted like a develop, so a
     * placeholder left behind (the app closed while it was developing) is developed when its project is opened again.
     */
    fun notePlaceholder(c: Context, placeholder: Uri, src: Uri, name: String, ei: Int, out: String) {
        val f = File(placeholder.path ?: return)
        if (f.parentFile?.absolutePath != dir(c).absolutePath) return
        record(c, f, src, name, ei, out, ei <= 0)
    }

    data class Info(val source: Uri, val name: String, val ei: Int, val out: String, val auto: Boolean = false)

    /** The develop behind [uri] (a clip's file), or null when it is not one. Reads a small file: off the main thread. */
    fun infoOf(c: Context, uri: Uri?): Info? {
        if (uri == null || uri.scheme != "file") return null
        val f = File(uri.path ?: return null)
        if (f.parentFile?.absolutePath != dir(c).absolutePath) return null
        return infoOfFile(c, f)
    }

    private fun infoOfFile(c: Context, f: File): Info? {
        val o = runCatching { JSONObject(index(c).readText()).optJSONObject(f.name) }.getOrNull() ?: return null
        val src = o.optString("source").takeIf { it.isNotEmpty() } ?: return null
        return Info(Uri.parse(src), o.optString("name", f.name), o.optInt("ei", 800), o.optString("out", DEFAULT_OUT), o.optBoolean("auto", false))
    }

    /**
     * The log each develop among [uris] was made into, for [CameraLog]: a new OpenDRT or film node on such a clip starts
     * from it. Reads the index: off the main thread.
     */
    fun rememberLogs(c: Context, uris: Collection<Uri>) {
        val mine = uris.filter { it.scheme == "file" && File(it.path ?: "").parentFile?.absolutePath == dir(c).absolutePath }
        if (mine.isEmpty()) return
        val o = runCatching { JSONObject(index(c).readText()) }.getOrNull() ?: return
        for (u in mine) o.optJSONObject(File(u.path ?: continue).name)?.let { CameraLog.remember(u, OpenDrt.Input.of(it.optString("out", DEFAULT_OUT))) }
    }

    /**
     * Links every media item of the open project that uses `old` to `new` (set by the open project). The develop left
     * behind is removed by [clean] once no project uses it.
     */
    @Volatile var relink: ((old: Uri, new: Uri) -> Unit)? = null
    /** Whether the open project has a media item on this file (a placeholder handed over, see [Placeholder]). Main thread. */
    @Volatile var has: ((Uri) -> Boolean)? = null
    /** Takes this file's media out of the open project, with its clips (a placeholder whose develop failed). */
    @Volatile var remove: ((Uri) -> Unit)? = null

    /** The develop running in the background: "name: 42 %", null = none. */
    var working by mutableStateOf<String?>(null)
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun workingText(name: String, p: Float, full: Boolean = false) =
        se.gendut.klipp.Str.t(if (full) se.gendut.klipp.R.string.mcraw_working_full else se.gendut.klipp.R.string.mcraw_working, name, (p * 100).toInt())

    /**
     * Develops [info]'s source again at [ei] into [out] in the background and, when done, puts it in the place of
     * [old] in the project; [done] (on the main thread) gets the new file, or null and the reason.
     */
    fun redevelop(c: Context, old: Uri, info: Info, ei: Int, out: String, done: (Uri?, String) -> Unit) {
        if (working != null) return
        val app = c.applicationContext
        working = workingText(info.name, 0f)
        bg.launch {
            val r = runCatching { develop(app, info.source, info.name, ei, out) { p -> working = workingText(info.name, p) } }
                .getOrElse { McrawConvert.Result(null, it.message ?: it.toString(), "") }
            withContext(Dispatchers.Main) {
                working = null
                if (r.uri != null && r.uri != old) relink?.invoke(old, r.uri)
                done(r.uri, r.summary)
            }
        }
    }

    /** A develop running or waiting (a redevelop, quick develops' full ones, an import): renders wait. */
    val busy: Boolean get() = working != null || drafts > 0 || importing

    /** An import is developing its .mcraw files ([finishDrafts] waits for it). */
    @Volatile var importing = false

    /** Drafts waiting for (or making) their full develop. */
    var drafts by mutableIntStateOf(0)
    private val queued = java.util.Collections.synchronizedSet(HashSet<String>())
    private val fullLock = Mutex()

    /**
     * The quick develops among [uris] (the open project's files) get their full develop in the background, one at a
     * time, each put in the draft's place when done ([relink]). Called whenever the project changes, so a project
     * opened again finishes what was left (a full develop already made is reused at once). Main thread.
     */
    fun finishDrafts(c: Context, uris: Collection<Uri>) {
        val app = c.applicationContext
        for (u in uris) {
            // a quick develop's full one; and a placeholder no import is developing any more (the app was closed
            // meanwhile – it stayed a placeholder for good)
            val left = Placeholder.isPlaceholder(u) && !importing
            if (!(isDraft(app, u) || left) || !queued.add(u.toString())) continue
            if (drafts++ == 0) Proxies.hold(true)
            bg.launch {
                // a moment first: the import that brought the draft in is still adding it to the project
                delay(1500)
                fullLock.withLock {
                    val info = infoOf(app, u)
                    val r = if (info == null) null else {
                        // after an import still developing (both at once would slow each other) and a Develop from the RAW settings
                        while (importing || working != null) delay(500)
                        working = workingText(info.name, 0f, full = true)
                        runCatching {
                            develop(app, info.source, info.name, info.ei, info.out, auto = info.auto) { p ->
                                working = workingText(info.name, p, full = true)
                            }
                        }.getOrNull()
                    }
                    withContext(Dispatchers.Main) {
                        if (info != null) working = null
                        drafts = (drafts - 1).coerceAtLeast(0)
                        queued.remove(u.toString())
                        // the full develop takes the draft's place first, then the proxies go on (none for the draft)
                        if (r?.uri != null) relink?.invoke(u, r.uri)
                        if (drafts == 0 && !importing) Proxies.hold(false)
                    }
                }
            }
        }
    }

    /** Deletes every develop (Settings → Clear). */
    fun clearAll(c: Context) { dir(c).listFiles()?.forEach { it.delete() }; revision++ }

    fun bytes(c: Context): Long = dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }?.sumOf { it.length() } ?: 0L

    /**
     * Removes the develops that no project (nor a project's saved versions) mentions any more, and leftovers of
     * develops that were stopped. [projects]: the projects' folder.
     */
    @Synchronized fun clean(c: Context, projects: File) {
        val files = dir(c).listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return
        val texts = projects.walkTopDown().filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".fcproj") || it.name.endsWith(".bak")) }
            .mapNotNull { runCatching { it.readText() }.getOrNull() }.toList()
        val o = runCatching { JSONObject(index(c).readText()) }.getOrDefault(JSONObject())
        var changed = false
        for (f in files) {
            if (f.name == "index.json") continue
            val stale = f.name.endsWith(".part") && System.currentTimeMillis() - f.lastModified() > 60 * 60 * 1000L
            // (a placeholder or develop just made may not be in a saved project yet: it is left a while)
            val young = System.currentTimeMillis() - f.lastModified() < 30 * 60 * 1000L
            val unused = f.name.endsWith(".mp4") && !young && texts.none { it.contains(f.name) }
            if (stale || unused) {
                f.delete()
                if (o.has(f.name)) { o.remove(f.name); changed = true }
            }
        }
        if (changed) { runCatching { index(c).writeText(o.toString()) }; revision++ }
    }
}
