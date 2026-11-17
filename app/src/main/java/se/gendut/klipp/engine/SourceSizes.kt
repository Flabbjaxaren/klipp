package se.gendut.klipp.engine

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * The source files' sizes (short side, pixels): what the film's sizes are measured against (N1, see FilmFx.Frame). The
 * export runs a clip's effects on the source file's decoded frames – the film's working image there is the source's own
 * size, whatever the project's or the export's size – so the reference is the source file's short side.
 *
 * Known from the project itself (the media's size, stored with the project: there at once on a cold start); a file
 * the project has no size for gets the codec probe's ([Perf.codec], on a background thread). Until then nothing is
 * known (null): the playback then plays the file unscaled and the film at scale 1 – the export's own look, the same
 * fallback as before N1. When a size asked for and missing becomes known, [revision] goes up once: the players are
 * rebuilt with it (an explicit change, never per frame; a failed probe changes nothing).
 */
object SourceSizes {
    private val sizes = HashMap<String, Int>()
    /** Asked for while unknown: their becoming known rebuilds the players. */
    private val missing = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Goes up when a size that was asked for and missing becomes known (the players' rebuild key). */
    var revision by mutableIntStateOf(0)
        private set

    /** The source's short side, null = not known (yet). */
    fun short(uri: Uri?): Int? {
        val k = uri?.toString() ?: return null
        synchronized(sizes) {
            sizes[k]?.let { return it }
            missing += k
        }
        return null
    }

    /** The project's media (each refresh): their sizes as the project states them. */
    fun remember(media: List<Pair<Uri, MediaInfo>>) {
        var changed = false
        synchronized(sizes) {
            for ((u, m) in media) {
                if (!m.hasVideo || m.width <= 0 || m.height <= 0) continue
                changed = put(u.toString(), minOf(m.width, m.height)) || changed
            }
        }
        if (changed) bump()
    }

    /** A probed file's size (see [Perf.codec]); only where the project gave none. */
    fun probed(uri: Uri, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val changed = synchronized(sizes) { if (sizes.containsKey(uri.toString())) false else put(uri.toString(), minOf(w, h)) }
        if (changed) bump()
    }

    /** True when someone asked for it while it was missing, or it changed (a file replaced under the same name). */
    private fun put(k: String, s: Int): Boolean {
        val old = sizes.put(k, s)
        val asked = missing.remove(k)
        return (old == null && asked) || (old != null && old != s)
    }

    private fun bump() {
        if (Looper.myLooper() == Looper.getMainLooper()) revision++ else main.post { revision++ }
    }

    /** For tests. */
    internal fun clear() = synchronized(sizes) { sizes.clear(); missing.clear() }

    /** Test builds: a size forgotten (as on a cold start without it) – the players are rebuilt without it. */
    fun forgetForTest(uri: Uri) { synchronized(sizes) { sizes.remove(uri.toString()) }; bump() }

    /** Test builds: the players rebuilt (a test switch changed what they read). */
    fun touchForTest() = bump()
}
