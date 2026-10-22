package se.gendut.klipp

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import se.gendut.klipp.engine.Meters
import kotlin.math.log10

/**
 * A peak meter's movement: up at once, down at [RELEASE_DB] per second; the highest peak stays for [HOLD_S], and a
 * peak at or above 0 dBFS lights the clip lamp for [CLIP_S]. In dBFS, [FLOOR_DB] = silence.
 */
class MeterBallistics {
    var db = FLOOR_DB
        private set
    var holdDb = FLOOR_DB
        private set
    private var holdLeft = 0.0
    private var clipLeft = 0.0
    val clipped get() = clipLeft > 0.0
    /** Still moving (something to draw after playback stopped)? */
    val moving get() = db > FLOOR_DB || holdDb > FLOOR_DB || clipped

    fun update(peak: Float, dt: Double) {
        val target = toDb(peak)
        db = if (target >= db) target else maxOf(target, db - RELEASE_DB * dt)
        if (target >= holdDb) { holdDb = target; holdLeft = HOLD_S } else {
            holdLeft -= dt
            if (holdLeft <= 0.0) holdDb = maxOf(db, holdDb - RELEASE_DB * dt)
        }
        if (peak >= CLIP) clipLeft = CLIP_S else clipLeft = maxOf(0.0, clipLeft - dt)
    }

    companion object {
        const val FLOOR_DB = -60.0
        const val RELEASE_DB = 24.0
        const val HOLD_S = 1.5
        const val CLIP_S = 2.0
        /** A sample this high is at 0 dBFS (16-bit full scale is 32767/32768). */
        const val CLIP = 0.999f

        fun toDb(peak: Float): Double = if (peak <= 1e-6f) FLOOR_DB else (20.0 * log10(peak.toDouble())).coerceIn(FLOOR_DB, 6.0)
        /** Height on the scale: −60 … 0 dBFS → 0 … 1. */
        fun frac(db: Double): Float = ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
    }
}

private val MeterGreen = Color(0xFF5BC27A)
private val MeterYellow = Color(0xFFE2C15B)

/** The same colours as the mixer's meters: green up to −12 dBFS, yellow to −3, red above. */
private fun meterColor(db: Double): Color = when {
    db > -3.0 -> K.Error
    db > -12.0 -> MeterYellow
    else -> MeterGreen
}

/**
 * Redraws the meters every frame while there is something to show: during playback and until the bars have fallen
 * after it. The state is read only in drawing, so nothing is recomposed.
 */
@Composable
private fun rememberMeterFrames(playing: Boolean, meters: Array<MeterBallistics>, read: () -> FloatArray): () -> Long {
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(0.1)
            last = now
            val peaks = read()
            for (k in meters.indices) meters[k].update(peaks.getOrElse(k) { 0f }, dt)
            frame.longValue = now
            if (!playing && meters.none { it.moving }) break
        }
    }
    return { frame.longValue }
}

/** One bar from the bottom up: background, level in its colours, the held peak as a line. */
private fun DrawScope.meterBar(m: MeterBallistics, x: Float, w: Float, top: Float, h: Float, back: Color) {
    val r = CornerRadius(w / 2)
    drawRoundRect(back, Offset(x, top), Size(w, h), r)
    val f = MeterBallistics.frac(m.db)
    if (f > 0f) {
        // the bar shows the colour zones it reaches (green at the bottom, yellow and red above)
        val zones = listOf(-12.0 to MeterGreen, -3.0 to MeterYellow, 6.0 to K.Error)
        var lo = 0f
        for ((edge, c) in zones) {
            val hi = minOf(f, MeterBallistics.frac(edge))
            if (hi > lo) drawRect(c, Offset(x, top + h * (1f - hi)), Size(w, h * (hi - lo)))
            lo = maxOf(lo, hi)
            if (lo >= f) break
        }
    }
    if (m.holdDb > MeterBallistics.FLOOR_DB) {
        val y = top + h * (1f - MeterBallistics.frac(m.holdDb))
        drawRect(meterColor(m.holdDb), Offset(x, y - 1.dp.toPx()), Size(w, 1.5.dp.toPx()))
    }
}

/**
 * The program's level (left/right peak, dBFS) as two slim bars – what is heard right now, after the master. Red
 * lamps on top when the mix reaches 0 dBFS. Sits over the picture's right edge; dim when nothing plays.
 */
@Composable
fun ProgramMeter(playing: Boolean, modifier: Modifier = Modifier) {
    val label = androidx.compose.ui.res.stringResource(R.string.meter_program)
    val meters = remember { Array(2) { MeterBallistics() } }
    val frame = rememberMeterFrames(playing, meters) { Meters.master().let { floatArrayOf(it.first, it.second) } }
    Canvas(modifier.testTag("programMeter").semantics { contentDescription = label }) {
        frame()
        val active = playing || meters.any { it.moving }
        drawRoundRect(Color.Black.copy(alpha = if (active) 0.55f else 0.35f), Offset.Zero, size, CornerRadius(6.dp.toPx()))
        val pad = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val w = (size.width - 2 * pad - gap) / 2
        val lamp = 4.dp.toPx()
        val top = pad + lamp + gap
        val h = size.height - top - pad
        // ticks: 0, −6, −12, −24, −48 dBFS
        val tick = Color.White.copy(alpha = 0.22f)
        for (db in doubleArrayOf(0.0, -6.0, -12.0, -24.0, -48.0)) {
            val y = top + h * (1f - MeterBallistics.frac(db))
            drawRect(tick, Offset(pad - 2.dp.toPx(), y), Size(size.width - 2 * pad + 4.dp.toPx(), 0.75.dp.toPx()))
        }
        val back = Color.White.copy(alpha = 0.08f)
        for (k in 0..1) {
            val x = pad + k * (w + gap)
            meterBar(meters[k], x, w, top, h, back)
            drawRoundRect(if (meters[k].clipped) K.Error else back, Offset(x, pad), Size(w, lamp), CornerRadius(1.5.dp.toPx()))
        }
    }
}

/** A track's level (peak after its volume) as a thin bar in the track header, in the colour of its loudest part. */
@Composable
fun TrackMeter(track: Int, playing: Boolean, modifier: Modifier = Modifier) {
    val meters = remember { arrayOf(MeterBallistics()) }
    val frame = rememberMeterFrames(playing, meters) { floatArrayOf(Meters.read(track)) }
    Canvas(modifier.testTag("trackMeter:$track")) {
        frame()
        // left to right (under the track name)
        val m = meters[0]
        val w = size.width
        val h = size.height
        drawRoundRect(K.SurfaceHighest, Offset.Zero, size, CornerRadius(h / 2))
        val f = MeterBallistics.frac(m.db)
        if (f > 0f) drawRoundRect(meterColor(m.db), Offset.Zero, Size(maxOf(w * f, h), h), CornerRadius(h / 2))
        if (m.holdDb > MeterBallistics.FLOOR_DB) drawRect(meterColor(m.holdDb), Offset(w * MeterBallistics.frac(m.holdDb) - 1.dp.toPx(), 0f), Size(1.5.dp.toPx(), h))
        if (m.clipped) drawRect(K.Error, Offset(w - 2.dp.toPx(), 0f), Size(2.dp.toPx(), h))
    }
}
