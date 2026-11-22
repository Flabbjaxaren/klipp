package se.gendut.klipp.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * A quick look into a QuickTime/MP4 file's boxes – without decoding anything and without FFmpeg: the sample entries'
 * codec fourccs per track (is the picture ProRes?) and the text metadata cameras leave in the file (QuickTime `keys`/
 * `ilst` entries such as Panasonic's com.panasonic.SemiPro.metadata.xml, and `xml ` boxes such as Sony's
 * NonRealTimeMeta). Only box headers and the few small boxes needed are read – a few kilobytes even for big files.
 */
object QtBoxes {
    /** What was found: [video]/[audio] = the first sample entry's fourcc per track, [meta] = text metadata by key, [xml] = embedded XML documents. */
    data class Info(val video: List<String>, val audio: List<String>, val meta: Map<String, String>, val xml: List<String>) {
        /** Every text the camera left (the metadata values and the XML documents). */
        val texts: List<String> get() = meta.values.toList() + xml
    }

    /** Reads [len] bytes at [pos] (fewer at the end). */
    fun interface Source { fun read(pos: Long, len: Int): ByteArray }

    /** Metadata values and XML larger than this are not read (camera XML is a few kilobytes). */
    private const val MAX_TEXT = 1 shl 20
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "edts")

    fun of(ch: FileChannel): Info? = probe(ch.size()) { pos, len ->
        val b = ByteBuffer.allocate(len)
        var p = pos
        while (b.hasRemaining()) { val n = ch.read(b, p); if (n <= 0) break; p += n }
        b.array().copyOf(b.position())
    }

    fun of(bytes: ByteArray): Info? = probe(bytes.size.toLong()) { pos, len ->
        if (pos >= bytes.size) ByteArray(0) else bytes.copyOfRange(pos.toInt(), minOf(bytes.size.toLong(), pos + len).toInt())
    }

    /** null = not a QuickTime/MP4 file (its first box isn't one of theirs). */
    fun probe(size: Long, src: Source): Info? {
        val first = header(src, 0, size) ?: return null
        if (first.type !in setOf("ftyp", "moov", "mdat", "wide", "free", "skip", "pnot", "uuid")) return null
        val st = State()
        walk(src, 0, size, st, depth = 0, track = null)
        return Info(st.video, st.audio, st.meta, st.xml)
    }

    private class Box(val type: String, val start: Long, val body: Long, val end: Long)

    private class State {
        val video = ArrayList<String>()
        val audio = ArrayList<String>()
        val meta = LinkedHashMap<String, String>()
        val xml = ArrayList<String>()
    }

    /** The track being walked: its handler ("vide", "soun") and its first sample entry. */
    private class Track { var handler: String? = null; var entry: String? = null }

    private fun header(src: Source, pos: Long, limit: Long): Box? {
        if (pos + 8 > limit) return null
        val h = src.read(pos, 16)
        if (h.size < 8) return null
        val b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
        var size = b.int.toLong() and 0xffffffffL
        val type = fourcc(h, 4)
        var body = pos + 8
        when (size) {
            1L -> { if (h.size < 16) return null; size = b.getLong(8); body = pos + 16 }
            0L -> size = limit - pos
        }
        if (size < body - pos || pos + size > limit) return null
        return Box(type, pos, body, pos + size)
    }

    private fun fourcc(b: ByteArray, at: Int): String = String(CharArray(4) { i -> (b[at + i].toInt() and 0xff).toChar() })

    private fun walk(src: Source, from: Long, to: Long, st: State, depth: Int, track: Track?) {
        if (depth > 8) return
        var pos = from
        var guard = 0
        while (pos < to && guard++ < 10_000) {
            val box = header(src, pos, to) ?: return
            when (box.type) {
                "trak" -> {
                    val t = Track()
                    walk(src, box.body, box.end, st, depth + 1, t)
                    when (t.handler) {
                        "vide" -> t.entry?.let { st.video += it }
                        "soun" -> t.entry?.let { st.audio += it }
                    }
                }
                in CONTAINERS -> walk(src, box.body, box.end, st, depth + 1, track)
                "hdlr" -> if (track != null && track.handler == null) {
                    // version/flags, pre_defined (QuickTime: component type), then the handler type
                    val b = src.read(box.body, 12)
                    if (b.size >= 12) track.handler = fourcc(b, 8)
                }
                "stsd" -> if (track != null && track.entry == null) {
                    // version/flags, entry count, then the first entry: size, format
                    val b = src.read(box.body, 16)
                    if (b.size >= 16) track.entry = fourcc(b, 12)
                }
                "meta" -> meta(src, box, st, depth)
                "xml " -> text(src, box.body + 4, box.end)?.let { st.xml += it }
            }
            pos = box.end
        }
    }

    /**
     * QuickTime's `meta` (a plain box) or ISO's (a full box: four bytes of version and flags first): its `keys` and
     * `ilst` give named values, an `xml ` child an XML document.
     */
    private fun meta(src: Source, box: Box, st: State, depth: Int) {
        val peek = src.read(box.body, 12)
        if (peek.size < 8) return
        val full = fourcc(peek, 4) !in setOf("hdlr", "keys", "ilst", "xml ", "free") && peek.size >= 12 &&
            fourcc(peek, 8) in setOf("hdlr", "keys", "ilst", "xml ", "free", "iinf", "pitm", "dinf", "iloc")
        val from = box.body + if (full) 4 else 0
        var keys: List<String> = emptyList()
        var ilst: Box? = null
        var pos = from
        var guard = 0
        while (pos < box.end && guard++ < 1000) {
            val c = header(src, pos, box.end) ?: break
            when (c.type) {
                "keys" -> keys = keys(src, c)
                "ilst" -> ilst = c
                "xml " -> text(src, c.body + 4, c.end)?.let { st.xml += it }
                // (anything nested deeper, e.g. a meta inside udta's meta)
                "udta", "meta" -> walk(src, c.start, c.end, st, depth + 1, null)
            }
            pos = c.end
        }
        val list = ilst ?: return
        pos = list.body
        guard = 0
        while (pos < list.end && guard++ < 10_000) {
            val item = header(src, pos, list.end) ?: break
            // the item's type is its key's 1-based index (QuickTime metadata), or a classic four-character tag
            val raw = src.read(item.start + 4, 4)
            val index = if (raw.size == 4) ByteBuffer.wrap(raw).int else 0
            val name = keys.getOrNull(index - 1) ?: item.type
            value(src, item)?.let { st.meta[name] = it }
            pos = item.end
        }
    }

    private fun keys(src: Source, box: Box): List<String> {
        val len = (box.end - box.body).coerceAtMost(MAX_TEXT.toLong()).toInt()
        val b = src.read(box.body, len)
        if (b.size < 8) return emptyList()
        val bb = ByteBuffer.wrap(b)
        bb.position(4)
        val n = bb.int
        val out = ArrayList<String>()
        for (i in 0 until n.coerceAtMost(4096)) {
            if (bb.remaining() < 8) break
            val size = bb.int
            if (size < 8 || size - 8 > bb.remaining() + 4) break
            bb.position(bb.position() + 4) // namespace ("mdta")
            val name = ByteArray(size - 8).also { bb.get(it) }
            out += String(name, Charsets.UTF_8)
        }
        return out
    }

    /** An ilst item's `data` child as text (any well-formed UTF-8; binary values are skipped). */
    private fun value(src: Source, item: Box): String? {
        var pos = item.body
        while (pos < item.end) {
            val c = header(src, pos, item.end) ?: return null
            // data: type indicator (4), locale (4), then the value
            if (c.type == "data") return text(src, c.body + 8, c.end)
            pos = c.end
        }
        return null
    }

    private fun text(src: Source, from: Long, to: Long): String? {
        val len = to - from
        if (len <= 0 || len > MAX_TEXT) return null
        val b = src.read(from, len.toInt())
        val dec = Charsets.UTF_8.newDecoder()
        val s = runCatching { dec.decode(ByteBuffer.wrap(b)).toString() }.getOrNull() ?: return null
        val t = s.trimEnd('\u0000')
        return t.takeIf { v -> v.isNotBlank() && v.none { it.code < 0x09 } }
    }
}
