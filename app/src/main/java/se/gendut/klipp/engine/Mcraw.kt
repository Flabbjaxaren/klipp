package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONObject

/**
 * MotionCam .mcraw files: open one, read its metadata (as JSON, MotionCam's own keys), decode its frames into a buffer,
 * read its sound, and make a picker card's preview. The decoding is MotionCam's own decoder library (motioncam-decoder,
 * Apache-2.0, vendored in third_party/motioncam-decoder), see src/main/cpp/mcraw_jni.cpp. The develop is
 * [McrawConvert].
 */
object Mcraw {
    private val loadError: Throwable? = runCatching { System.loadLibrary("klipp_mcraw") }.exceptionOrNull()
    val available get() = loadError == null

    @JvmStatic private external fun nativeOpen(fd: Int): Long
    @JvmStatic private external fun nativeClose(h: Long)
    @JvmStatic private external fun nativeInfo(h: Long): String
    @JvmStatic private external fun nativeDecode(h: Long, index: Int): Long
    @JvmStatic private external fun nativePreview(h: Long, out: IntArray, w: Int, h2: Int): Boolean
    @JvmStatic private external fun nativeDecodeTo(h: Long, index: Int, out: java.nio.ByteBuffer): String?
    @JvmStatic private external fun nativeEmptyBottomRows(h: Long, limit: Int): Int
    @JvmStatic private external fun nativeLoadAudio(h: Long): Long
    @JvmStatic private external fun nativeAudioStart(h: Long): Long
    @JvmStatic private external fun nativeAudioCopy(h: Long, out: ShortArray)

    /** An open .mcraw: its handle, closed with the file descriptor it came from. */
    internal class Source(val h: Long, private val pfd: android.os.ParcelFileDescriptor) : AutoCloseable {
        override fun close() { runCatching { nativeClose(h) }; runCatching { pfd.close() } }
    }

    /** Opens [uri] for reading; null when it can't be read as MCRAW (or the library isn't there). */
    internal fun openSource(ctx: Context, uri: Uri): Source? {
        if (!available) return null
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        val h = runCatching { nativeOpen(pfd.fd) }.getOrDefault(0L)
        if (h == 0L) { pfd.close(); return null }
        return Source(h, pfd)
    }

    /** All the file's sound, interleaved 16-bit, and the time (ns, the frames' clock) of its first sample; null when none. */
    internal fun audio(h: Long): Pair<ShortArray, Long>? {
        val n = nativeLoadAudio(h)
        if (n <= 0 || n > Int.MAX_VALUE) return null
        val out = ShortArray(n.toInt())
        nativeAudioCopy(h, out)
        return out to nativeAudioStart(h)
    }

    /** Container metadata, the frame timestamps (ns) and the first frame's metadata, as one JSON object. */
    internal fun info(h: Long): String = nativeInfo(h)
    /** Decodes frame [index] into [out] (16-bit CFA samples); its metadata as JSON, or null on failure. */
    internal fun decodeTo(h: Long, index: Int, out: java.nio.ByteBuffer): String? = nativeDecodeTo(h, index, out)
    /** Padding rows at the bottom of the frame last decoded with [decodeTo] (all at or under [limit]). */
    internal fun emptyBottomRows(h: Long, limit: Int): Int = nativeEmptyBottomRows(h, limit)

    /** A picker card's preview (the middle frame, padding rows cut off) and what the file is. */
    class Thumb(val bitmap: Bitmap?, val width: Int, val height: Int, val fps: Double, val seconds: Double)

    /** Blocking (decodes one frame): call off the main thread. */
    fun thumb(ctx: Context, uri: Uri): Thumb? {
        val s = openSource(ctx, uri) ?: return null
        val h = s.h
        try {
            val info = JSONObject(nativeInfo(h))
            val frames = info.optJSONArray("frames") ?: return null
            val n = frames.length()
            if (n == 0) return null
            val ff = info.optJSONObject("firstFrame") ?: JSONObject()
            val w = ff.optInt("width"); var hh = ff.optInt("height")
            val dur = if (n > 1) (frames.getLong(n - 1) - frames.getLong(0)) / 1e9 else 0.0
            val fps = if (dur > 0) (n - 1) / dur else 0.0
            var bmp: Bitmap? = null
            if (nativeDecode(h, n / 2) >= 0 && w > 0 && hh > 0) {
                val black = info.optJSONObject("container")?.optJSONArray("blackLevel")
                val limit = (black?.let { a -> (0 until a.length()).maxOfOrNull { a.getDouble(it) } } ?: 0.0).toInt() + 1
                val empty = nativeEmptyBottomRows(h, limit)
                val ch = if (empty >= hh / 20) hh - empty else hh
                val pw = 320; val ph = (pw.toLong() * hh / w).toInt().coerceAtLeast(1)
                val px = IntArray(pw * ph)
                if (nativePreview(h, px, pw, ph)) {
                    val full = Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888)
                    bmp = Bitmap.createBitmap(full, 0, 0, pw, (ph.toLong() * ch / hh).toInt().coerceIn(1, ph))
                }
                hh = ch
            }
            return Thumb(bmp, w, hh, fps, dur + if (fps > 0) 1 / fps else 0.0)
        } catch (_: Throwable) {
            return null
        } finally { s.close() }
    }
}
