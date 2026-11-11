package se.gendut.klipp.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A project as one file (`.klipp`, a zip): the engine's project, Klipp's side file (media links, grades, names) and
 * the thumbnail – and, when chosen, the media files themselves, so the project survives an uninstall or moves to
 * another phone. `klipp.json` says what is inside.
 */
object ProjectPackage {
    const val FORMAT = 1
    const val EXT = "klipp"
    private const val MANIFEST = "klipp.json"
    private const val PROJECT = "project.fcproj"
    private const val SIDE = "side.json"
    private const val THUMB = "thumb.jpg"

    /** A media file to pack: the engine's item, its name and how to read it (null = not readable, left out). */
    class Media(val item: Long, val name: String, val open: () -> InputStream?)

    /** What [write] packed: the media items with their files, and those that couldn't be read. */
    data class Written(val packed: List<Long>, val unreadable: List<Long>)

    /**
     * Writes the package to [out]. Media are stored without compression (video doesn't shrink, and it's faster);
     * [progress] gets the bytes written so far.
     */
    fun write(
        out: OutputStream, project: ByteArray, side: JSONObject, thumb: ByteArray?, media: List<Media>,
        app: String = "", progress: (Long) -> Unit = {},
    ): Written {
        val packed = ArrayList<Long>()
        val unreadable = ArrayList<Long>()
        var total = 0L
        val items = JSONArray()
        ZipOutputStream(out.buffered(1 shl 16)).use { z ->
            fun entry(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
                total += bytes.size; progress(total)
            }
            entry(PROJECT, project)
            entry(SIDE, side.toString().toByteArray(Charsets.UTF_8))
            thumb?.let { entry(THUMB, it) }
            z.setLevel(Deflater.NO_COMPRESSION)
            val buf = ByteArray(1 shl 16)
            for (m in media) {
                val input = runCatching { m.open() }.getOrNull()
                if (input == null) { unreadable += m.item; items.put(JSONObject().put("item", m.item).put("name", m.name)); continue }
                val path = "media/${m.item}_${safeName(m.name)}"
                var written = 0L
                input.use { i ->
                    z.putNextEntry(ZipEntry(path))
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        z.write(buf, 0, n)
                        written += n
                        total += n; progress(total)
                    }
                    z.closeEntry()
                }
                packed += m.item
                items.put(JSONObject().put("item", m.item).put("name", m.name).put("file", path).put("size", written))
            }
            z.setLevel(Deflater.DEFAULT_COMPRESSION)
            // "complete": every media file the project uses is inside (only meaningful for a package with media)
            val manifest = JSONObject().put("format", FORMAT).put("app", app).put("media", items)
                .put("withMedia", media.isNotEmpty()).put("complete", unreadable.isEmpty())
            if (unreadable.isNotEmpty()) manifest.put("missing", JSONArray(unreadable))
            entry(MANIFEST, manifest.toString().toByteArray(Charsets.UTF_8))
        }
        return Written(packed, unreadable)
    }

    /** A read package: the project, the side file, the thumbnail, and the unpacked media files per item. */
    class Unpacked(val project: ByteArray, val side: JSONObject, val thumb: ByteArray?, val media: Map<Long, File>)

    class NotAPackage(msg: String) : Exception(msg)

    /**
     * Reads a package from [input]; media files go into [mediaDir] (created). Throws [NotAPackage] if the project
     * or the side file is missing. Paths in the zip never leave [mediaDir].
     */
    fun read(input: InputStream, mediaDir: File, progress: (Long) -> Unit = {}): Unpacked {
        var project: ByteArray? = null
        var side: JSONObject? = null
        var thumb: ByteArray? = null
        var manifest: JSONObject? = null
        val files = HashMap<String, File>()
        var total = 0L
        val buf = ByteArray(1 shl 16)
        ZipInputStream(input.buffered(1 shl 16)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when {
                    e.isDirectory -> Unit
                    e.name == PROJECT -> project = z.readBytes()
                    e.name == SIDE -> side = runCatching { JSONObject(String(z.readBytes(), Charsets.UTF_8)) }.getOrNull()
                    e.name == THUMB -> thumb = z.readBytes()
                    e.name == MANIFEST -> manifest = runCatching { JSONObject(String(z.readBytes(), Charsets.UTF_8)) }.getOrNull()
                    e.name.startsWith("media/") -> {
                        val name = safeName(e.name.removePrefix("media/"))
                        if (name.isEmpty()) continue
                        mediaDir.mkdirs()
                        val f = File(mediaDir, name)
                        f.outputStream().buffered(1 shl 16).use { o ->
                            while (true) {
                                val n = z.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                total += n; progress(total)
                            }
                        }
                        files[e.name] = f
                    }
                }
            }
        }
        val p = project ?: throw NotAPackage("no project in the file")
        val s = side ?: throw NotAPackage("no side file in the file")
        val media = HashMap<Long, File>()
        manifest?.optJSONArray("media")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val f = files[o.optString("file")] ?: continue
                media[o.optLong("item", -1L).takeIf { it >= 0 } ?: continue] = f
            }
        }
        return Unpacked(p, s, thumb, media)
    }

    /** What [verify] found: [ok], or the first [problem]; [complete] = no media file was missing when it was written. */
    data class Verified(val ok: Boolean, val problem: String? = null, val complete: Boolean = true, val format: Int = 0)

    /**
     * Reads a whole package without unpacking it and checks that it is whole: the project, side file and manifest are
     * there, the format is one this app can read, and every media file the manifest lists is there with its size.
     * A cut-off or damaged zip is not ok.
     */
    fun verify(input: InputStream): Verified {
        var project = false
        var side = false
        var manifest: JSONObject? = null
        val sizes = HashMap<String, Long>()
        val buf = ByteArray(1 shl 16)
        // the zip's end record (the last 22 bytes – Klipp writes no comment): a file cut short after its last entry
        // still reads entry by entry, but has no end
        val tail = Tail(input)
        try {
            ZipInputStream(tail.buffered(1 shl 16)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    var n = 0L
                    if (e.name == MANIFEST) {
                        manifest = runCatching { JSONObject(String(z.readBytes(), Charsets.UTF_8)) }.getOrNull()
                    } else if (e.name == SIDE) {
                        side = runCatching { JSONObject(String(z.readBytes(), Charsets.UTF_8)) }.isSuccess
                    } else {
                        while (true) { val r = z.read(buf); if (r < 0) break; n += r }
                        if (e.name == PROJECT) project = n > 0
                        sizes[e.name] = n
                    }
                }
                // the rest (the zip's directory and end) straight from the source, past the zip reader's buffer
                while (tail.read(buf) >= 0) Unit
            }
        } catch (e: java.io.IOException) {
            return Verified(false, "damaged (${e.message ?: e.javaClass.simpleName})")
        }
        if (!tail.endsWithEndRecord()) return Verified(false, "cut short (no end of the zip)")
        val m = manifest ?: return Verified(false, "no manifest")
        if (!project) return Verified(false, "no project")
        if (!side) return Verified(false, "no side file")
        val format = m.optInt("format", 0)
        if (format > FORMAT) return Verified(false, "made by a newer Klipp (format $format)", format = format)
        val a = m.optJSONArray("media") ?: JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val f = o.optString("file")
            if (f.isEmpty()) continue
            val got = sizes[f] ?: return Verified(false, "a media file is missing: $f", format = format)
            if (o.has("size") && o.optLong("size") != got) return Verified(false, "a media file is cut short: $f", format = format)
        }
        return Verified(true, complete = m.optBoolean("complete", true), format = format)
    }

    /** Passes [src] through and keeps its last 22 bytes. */
    private class Tail(private val src: InputStream) : InputStream() {
        private val last = ByteArray(22)
        private var count = 0L
        private fun keep(b: Int) { last[(count % 22).toInt()] = b.toByte(); count++ }
        override fun read(): Int = src.read().also { if (it >= 0) keep(it) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len).also { n -> for (i in 0 until n) keep(b[off + i].toInt() and 0xFF) }
        override fun close() = src.close()
        /** The last 22 bytes start with the end record's signature (50 4B 05 06). */
        fun endsWithEndRecord(): Boolean {
            if (count < 22) return false
            val start = (count % 22).toInt()
            fun at(i: Int) = last[(start + i) % 22].toInt() and 0xFF
            return at(0) == 0x50 && at(1) == 0x4B && at(2) == 0x05 && at(3) == 0x06
        }
    }

    /** A file name without directories or odd characters (kept readable). */
    fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ ()åäöÅÄÖ-]"), "_").trimStart('.').take(120)

    /** The side file for the unpacked project: media links point to the unpacked files ([media], as URIs). */
    fun relinked(side: JSONObject, media: Map<Long, String>): JSONObject {
        val o = JSONObject(side.toString())
        val a = o.optJSONArray("media") ?: JSONArray()
        val seen = HashSet<Long>()
        for (i in 0 until a.length()) {
            val m = a.optJSONObject(i) ?: continue
            val id = m.optLong("item", -1L)
            media[id]?.let { m.put("uri", it); seen += id }
        }
        for ((id, u) in media) if (id !in seen) a.put(JSONObject().put("item", id).put("uri", u))
        o.put("media", a)
        return o
    }
}
