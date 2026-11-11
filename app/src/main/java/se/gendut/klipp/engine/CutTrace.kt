package se.gendut.klipp.engine

import android.util.Log

/**
 * Diagnostics for edit points in playback (plan PR 8.7). Off unless the app is started with the intent extras below –
 * not a user setting:
 *
 *   adb shell am start -n <package>/se.gendut.klipp.MainActivity --ez klipp_cuttrace true --ei klipp_cutdiag 0|1|2
 *
 * Every event goes to logcat (tag `KlippCut`) and to a buffer the agent bridge reads (tool `cut_trace`), with [System.nanoTime] – the same monotonic clock as
 * `adb logcat -v monotonic` – so events from the main player, the edit point layer and the video chain line up.
 * What a line tells: `pos` = a player's reported position, `tex` = a frame reached a layer's TextureView (its
 * SurfaceTexture time), `gl` = the video chain handed a frame to its surface (not yet that it was on the screen), `vis` =
 * whether the app shows the layer. What was actually on the screen is measured separately (screen recording of
 * frame-coded clips, tools/framecode).
 */
object CutTrace {
    @Volatile var on = false
        private set

    /** 0 = normal; 1 = no edit point layer (the main player alone); 2 = the edit point layer plays but never takes over. */
    @Volatile var mode = 0
        private set

    fun configure(trace: Boolean, diagMode: Int) {
        on = trace
        mode = diagMode.coerceIn(0, 2)
        androidx.media3.effect.KlippPrecision.frameTrace = if (trace) androidx.media3.effect.KlippPrecision.FrameTrace { chain, ptsUs, presentNs, dropped ->
            log("gl", "chain=$chain pts=$ptsUs ${if (dropped) "DROPPED" else "present=$presentNs"} thread=${Thread.currentThread().name}")
        } else null
        log("config", "trace=$trace mode=$mode")
    }

    // the lines kept in the app too (the phone may throttle an app's logcat output): read with the bridge's cut_trace
    private val lines = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val count = java.util.concurrent.atomic.AtomicInteger()

    fun log(kind: String, msg: String) {
        if (!on) return
        val line = "${System.nanoTime()} $kind $msg"
        Log.i("KlippCut", line)
        lines.add(line)
        if (count.incrementAndGet() > 200_000) { lines.poll(); count.decrementAndGet() }
    }

    /** The lines since the last call (and forgets them). */
    fun drain(): String {
        val sb = StringBuilder()
        while (true) { val l = lines.poll() ?: break; count.decrementAndGet(); sb.append(l).append('\n') }
        return sb.toString()
    }
}
