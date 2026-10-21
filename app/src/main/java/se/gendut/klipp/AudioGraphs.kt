package se.gendut.klipp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.FxCurves
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Geometry for the EQ graph: logarithmic frequency 20 Hz–20 kHz, ±[range] dB. Pure – tested without UI. */
class EqGeom(val w: Float, val h: Float, val range: Float = 24f) {
    private val pad = h * 0.08f
    fun x(f: Double): Float = (w * log10(f / 20.0) / 3.0).toFloat()
    fun f(x: Float): Double = (20.0 * 10.0.pow(3.0 * (x / w).coerceIn(0f, 1f))).coerceIn(20.0, 20000.0)
    fun y(db: Double): Float = (h / 2f - (db / range).toFloat() * (h / 2f - pad))
    fun db(y: Float): Double = ((h / 2f - y) / (h / 2f - pad) * range).toDouble().coerceIn(-range.toDouble(), range.toDouble())
}

/** Geometry for the compressor graph: input and output level −60…0 dB (output up to +[top] with makeup gain). */
class CompGeom(val w: Float, val h: Float, val lo: Float = -60f, val top: Float = 6f) {
    fun x(db: Double): Float = ((db - lo) / -lo).toFloat() * w
    fun dbX(x: Float): Double = (lo + (x / w).coerceIn(0f, 1f) * -lo).toDouble()
    fun y(db: Double): Float = h - ((db - lo) / (top - lo)).toFloat().coerceIn(-0.05f, 1.05f) * h
    fun dbY(y: Float): Double = (lo + (1f - y / h) * (top - lo)).toDouble()

    companion object {
        /** The ratio that gives output level [out] at 0 dB input for threshold [t] (the knee). */
        fun ratioFor(t: Double, out: Double): Double = if (out >= -0.01) 1.0 else if (out <= t + 0.05) 30.0 else (-t / (out - t)).coerceIn(1.0, 30.0)
    }
}

private val BandColors = listOf(
    Color(0xFFFF8A65), Color(0xFFFFD54F), Color(0xFFAED581), Color(0xFF4DD0E1), Color(0xFF64B5F6), Color(0xFFBA68C8), Color(0xFFF06292),
)

private fun hz(v: Double) = if (v >= 1000) "%.1f kHz".format(v / 1000) else "%.0f Hz".format(v)
private fun db(v: Double) = "%+.1f dB".format(v).replace('-', '−')

/**
 * Graphical parametric EQ: the engine's frequency response as a curve, one draggable handle per band (horizontal =
 * frequency, height = level), low/high cut as handles on the zero line. Q and slope below the graph.
 * Audible while dragging ([onLive]); the values are written when the finger lifts ([onCommit], one undo step).
 */
@Composable
fun EqGraph(
    fxId: String,
    params: Map<String, Double>,
    onLive: (String, Double) -> Unit,
    onCommit: (Map<String, Double>) -> Unit,
) {
    val haptics = rememberHaptics()
    // values while dragging (and until the engine responds). Always the same map – the gesture holds a reference to it
    val live = remember(fxId) { mutableStateMapOf<String, Double>() }
    val dragging = remember { booleanArrayOf(false) }
    androidx.compose.runtime.LaunchedEffect(params) { if (!dragging[0]) live.clear() }
    fun v(k: String) = live[k] ?: FxCurves.value(fxId, params, k)
    var sel by remember { mutableStateOf("mid") }
    val eff = params + live
    val freqs = remember { DoubleArray(160) { 20.0 * 10.0.pow(3.0 * it / 159.0) } }
    val curve = remember(eff) { FxCurves.response(fxId, eff, freqs) }
    val cur by rememberUpdatedState(eff)
    val liveCb by rememberUpdatedState(onLive)
    val commitCb by rememberUpdatedState(onCommit)
    val lastTap = remember { longArrayOf(0L) }
    val lastKey = remember { arrayOf("") }

    Column(Modifier.fillMaxWidth().testTag("eqGraph")) {
        Canvas(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp)).background(K.Deep)
                .testTag("eqCanvas")
                .pointerInput(fxId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = EqGeom(size.width.toFloat(), size.height.toFloat())
                        val p = cur
                        fun pv(k: String) = p[k] ?: FxCurves.value(fxId, p, k)
                        // nearest handle
                        val cands = FxCurves.BANDS.map { b -> b.key to Offset(g.x(pv(b.f)), g.y(pv(b.g))) } +
                            listOf("hp" to Offset(g.x(pv("hp_freq")), g.y(0.0)), "lp" to Offset(g.x(pv("lp_freq")), g.y(0.0)))
                        val usable = cands.filter { (k, _) -> k !in setOf("hp", "lp") || pv("${k}_on") > 0.5 }
                        val near = usable.minByOrNull { (_, o) -> (o - down.position).getDistance() }
                        if (near == null || (near.second - down.position).getDistance() > 36.dp.toPx()) return@awaitEachGesture
                        val key = near.first
                        down.consume()
                        dragging[0] = true
                        sel = key
                        haptics.tick()
                        val band = FxCurves.BANDS.firstOrNull { it.key == key }
                        val fId = band?.f ?: "${key}_freq"
                        val gId = band?.g
                        val grab = down.position - near.second
                        val changed = HashMap<String, Double>()
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val pos = ch.position - grab
                            if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (!moved) continue
                            ch.consume()
                            val f = Math.round(g.f(pos.x) * 10) / 10.0
                            live[fId] = f; changed[fId] = f; liveCb(fId, f)
                            if (gId != null) {
                                var d = Math.round(g.db(pos.y) * 10) / 10.0
                                if (abs(d) < 0.6) d = 0.0 // magnetic zero
                                if ((live[gId] ?: 1.0) != 0.0 && d == 0.0) haptics.tick()
                                live[gId] = d; changed[gId] = d; liveCb(gId, d)
                                // a disabled band is turned on when dragged
                                if (pv(band.on) < 0.5) { live[band.on] = 1.0; changed[band.on] = 1.0; liveCb(band.on, 1.0) }
                            }
                        }
                        dragging[0] = false
                        if (moved) {
                            haptics.confirm()
                            commitCb(changed)
                        } else if (gId != null) {
                            // double tap = band back to 0 dB
                            val now = System.currentTimeMillis()
                            if (lastKey[0] == key && now - lastTap[0] < 350) {
                                live[gId] = 0.0; liveCb(gId, 0.0); commitCb(mapOf(gId to 0.0)); haptics.confirm(); lastTap[0] = 0
                            } else { lastTap[0] = now; lastKey[0] = key }
                        }
                    }
                }
        ) {
            val g = EqGeom(size.width, size.height)
            drawEqGrid(g)
            // the curve (filled toward 0 dB)
            val line = Path()
            val fill = Path()
            for (i in freqs.indices) {
                val x = g.x(freqs[i]); val y = g.y(curve[i].coerceIn(-40.0, 40.0))
                if (i == 0) { line.moveTo(x, y); fill.moveTo(x, g.y(0.0)); fill.lineTo(x, y) } else { line.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(size.width, g.y(0.0)); fill.close()
            drawPath(fill, K.Primary.copy(alpha = 0.16f))
            drawPath(line, K.Primary, style = Stroke(2.5.dp.toPx()))
            // handles
            for ((k, b) in FxCurves.BANDS.withIndex()) {
                val on = v(b.on) > 0.5
                val c = Offset(g.x(v(b.f)), g.y(v(b.g)))
                val r = if (sel == b.key) 10.dp.toPx() else 8.dp.toPx()
                drawCircle(Color(0xFF0C0E12), r + 2.dp.toPx(), c)
                if (on) drawCircle(BandColors[k], r, c) else drawCircle(BandColors[k].copy(alpha = 0.6f), r, c, style = Stroke(2.dp.toPx()))
                if (sel == b.key) drawCircle(Color.White, r + 3.5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            }
            for (k in listOf("hp", "lp")) if (v("${k}_on") > 0.5) {
                val c = Offset(g.x(v("${k}_freq")), g.y(0.0))
                val r = 7.dp.toPx()
                val tri = Path().apply {
                    if (k == "hp") { moveTo(c.x - r, c.y + r); lineTo(c.x + r, c.y - r); lineTo(c.x + r, c.y + r) } else { moveTo(c.x - r, c.y - r); lineTo(c.x + r, c.y + r); lineTo(c.x - r, c.y + r) }
                    close()
                }
                drawPath(tri, K.Teal)
                if (sel == k) drawCircle(Color.White, r + 4.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            }
        }
        // bands to select + low/high cut
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in listOf("hp", "lp")) {
                val on = v("${k}_on") > 0.5
                SpringChip(stringResource(if (k == "hp") R.string.audiographs_low_cut else R.string.audiographs_high_cut), on, {
                    haptics.tick()
                    val nv = if (on) 0.0 else 1.0
                    live["${k}_on"] = nv; onLive("${k}_on", nv); onCommit(mapOf("${k}_on" to nv)); sel = k
                }, Modifier.testTag("eq:$k"), selectedColor = K.Teal, selectedText = K.Bg)
            }
            for (b in FxCurves.BANDS) SpringChip(b.label, sel == b.key, { haptics.tick(); sel = b.key }, Modifier.testTag("eqBand:${b.key}"))
        }
        val band = FxCurves.BANDS.firstOrNull { it.key == sel }
        if (band != null) {
            val on = v(band.on) > 0.5
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${band.label}: ${hz(v(band.f))}  ${db(v(band.g))}", color = K.OnSurface, fontSize = 13.sp, modifier = Modifier.weight(1f).testTag("eqReadout"))
                SpringChip(stringResource(if (on) R.string.audiographs_on else R.string.audiographs_off), on, {
                    haptics.tick()
                    val nv = if (on) 0.0 else 1.0
                    live[band.on] = nv; onLive(band.on, nv); onCommit(mapOf(band.on to nv))
                }, Modifier.testTag("eqOn:${band.key}"))
            }
            PropSlider(stringResource(R.string.audiographs_q_width), v(band.qId).toFloat(), 0.1f, 10f, band.q.toFloat(), { "%.2f".format(it) }, "eqQ_${band.key}",
                onLive = { q -> live[band.qId] = q.toDouble(); onLive(band.qId, q.toDouble()) }, lane = false,
            ) { q -> onCommit(mapOf(band.qId to q.toDouble())) }
        } else if (sel == "hp" || sel == "lp") {
            val slope = v("${sel}_slope").toInt()
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(hz(v("${sel}_freq")), color = K.OnSurface, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                for ((i, l) in listOf("12", "24", "36", "48").withIndex()) SpringChip(stringResource(R.string.audiographs_slope, l), slope == i, {
                    haptics.tick(); val id = "${sel}_slope"; live[id] = i.toDouble(); onLive(id, i.toDouble()); onCommit(mapOf(id to i.toDouble()))
                }, Modifier.testTag("eqSlope:$i"))
            }
        }
    }
}

private fun DrawScope.drawEqGrid(g: EqGeom) {
    for (f in listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)) {
        val major = f == 100.0 || f == 1000.0 || f == 10000.0
        drawLine(K.OutlineVar.copy(alpha = if (major) 0.8f else 0.4f), Offset(g.x(f), 0f), Offset(g.x(f), size.height), 1f)
    }
    for (d in listOf(-18.0, -12.0, -6.0, 6.0, 12.0, 18.0)) drawLine(K.OutlineVar.copy(alpha = 0.35f), Offset(0f, g.y(d)), Offset(size.width, g.y(d)), 1f)
    drawLine(K.Outline.copy(alpha = 0.8f), Offset(0f, g.y(0.0)), Offset(size.width, g.y(0.0)), 1.5f)
}

/**
 * Graphical compressor: the transfer curve (in → out) from the engine's DSP. The knee (threshold) is dragged
 * sideways, the handle at 0 dB input is dragged up/down for the ratio. The diagonal = no effect.
 */
@Composable
fun CompGraph(
    fxId: String,
    params: Map<String, Double>,
    onLive: (String, Double) -> Unit,
    onCommit: (Map<String, Double>) -> Unit,
) {
    val haptics = rememberHaptics()
    // values while dragging (and until the engine responds). Always the same map – the gesture holds a reference to it
    val live = remember(fxId) { mutableStateMapOf<String, Double>() }
    val dragging = remember { booleanArrayOf(false) }
    androidx.compose.runtime.LaunchedEffect(params) { if (!dragging[0]) live.clear() }
    val eff = params + live
    fun v(k: String) = eff[k] ?: FxCurves.value(fxId, params, k)
    val levels = remember { DoubleArray(121) { -60.0 + it * 0.5 } }
    val curve = remember(eff) { FxCurves.transfer(fxId, eff, levels) }
    val cur by rememberUpdatedState(eff)
    val liveCb by rememberUpdatedState(onLive)
    val commitCb by rememberUpdatedState(onCommit)
    var active by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().testTag("compGraph")) {
        Canvas(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(14.dp)).background(K.Deep)
                .testTag("compCanvas")
                .pointerInput(fxId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = CompGeom(size.width.toFloat(), size.height.toFloat())
                        val p = cur
                        fun pv(k: String) = p[k] ?: FxCurves.value(fxId, p, k)
                        val t = pv("comp_threshold")
                        val outAt0 = FxCurves.transfer(fxId, p, doubleArrayOf(0.0))[0] - pv("comp_makeup") - pv("output")
                        val knee = Offset(g.x(t), g.y(t))
                        val ratioH = Offset(g.x(0.0) - 10.dp.toPx(), g.y(outAt0))
                        val dk = (knee - down.position).getDistance()
                        val dr = (ratioH - down.position).getDistance()
                        val lim = 40.dp.toPx()
                        val which = when {
                            dk <= lim && dk <= dr -> "knee"
                            dr <= lim -> "ratio"
                            else -> return@awaitEachGesture
                        }
                        down.consume()
                        dragging[0] = true
                        active = which
                        haptics.tick()
                        val changed = HashMap<String, Double>()
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (!moved) continue
                            ch.consume()
                            if (which == "knee") {
                                // the knee follows the finger along the diagonal: horizontal and vertical mean the same thing
                                val byX = g.dbX(ch.position.x)
                                val nt = (Math.round(byX * 2) / 2.0).coerceIn(-60.0, 0.0)
                                if (nt != live["comp_threshold"]) { live["comp_threshold"] = nt; changed["comp_threshold"] = nt; liveCb("comp_threshold", nt) }
                            } else {
                                val tt = live["comp_threshold"] ?: t
                                val r = Math.round(CompGeom.ratioFor(tt, g.dbY(ch.position.y)) * 10) / 10.0
                                if (r != live["comp_ratio"]) {
                                    if (r == 1.0 || r == 30.0) haptics.tick()
                                    live["comp_ratio"] = r; changed["comp_ratio"] = r; liveCb("comp_ratio", r)
                                }
                            }
                            if (pv("comp_on") < 0.5) { live["comp_on"] = 1.0; changed["comp_on"] = 1.0; liveCb("comp_on", 1.0) }
                        }
                        active = null
                        dragging[0] = false
                        if (moved && changed.isNotEmpty()) { haptics.confirm(); commitCb(changed) }
                    }
                }
        ) {
            val g = CompGeom(size.width, size.height)
            for (d in listOf(-48.0, -36.0, -24.0, -12.0, 0.0)) {
                drawLine(K.OutlineVar.copy(alpha = 0.4f), Offset(g.x(d), 0f), Offset(g.x(d), size.height), 1f)
                drawLine(K.OutlineVar.copy(alpha = 0.4f), Offset(0f, g.y(d)), Offset(size.width, g.y(d)), 1f)
            }
            // 1:1 – no effect
            drawLine(K.Outline.copy(alpha = 0.6f), Offset(g.x(-60.0), g.y(-60.0)), Offset(g.x(6.0), g.y(6.0)), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
            val t = v("comp_threshold")
            // the gain reduction above the knee
            val fill = Path()
            val line = Path()
            for (i in levels.indices) {
                val x = g.x(levels[i]); val y = g.y(curve[i].coerceIn(-70.0, 12.0))
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            fill.moveTo(g.x(t), g.y(t))
            for (i in levels.indices) if (levels[i] >= t) fill.lineTo(g.x(levels[i]), g.y(curve[i].coerceIn(-70.0, 12.0)))
            fill.lineTo(g.x(0.0), g.y(0.0)); fill.close()
            drawPath(fill, K.Primary.copy(alpha = 0.16f))
            drawPath(line, K.Primary, style = Stroke(2.5.dp.toPx()))
            // the knee
            val kc = Offset(g.x(t), g.y(curve[((t + 60) * 2).toInt().coerceIn(0, levels.lastIndex)].coerceIn(-70.0, 12.0)))
            val r = (if (active == "knee") 11 else 9).dp.toPx()
            drawCircle(Color(0xFF0C0E12), r + 2.dp.toPx(), kc)
            drawCircle(K.Primary, r, kc)
            // the ratio handle at 0 dB input (without makeup gain, so it sits on the knee's line)
            val out0 = curve.last() - v("comp_makeup") - v("output")
            val rc = Offset(g.x(0.0) - 10.dp.toPx(), g.y(out0))
            val rr = (if (active == "ratio") 9 else 7).dp.toPx()
            drawRoundRect(K.PrimarySoft, Offset(rc.x - rr, rc.y - rr), androidx.compose.ui.geometry.Size(rr * 2, rr * 2), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
        val r = v("comp_ratio")
        Text(
            stringResource(R.string.audiographs_comp_readout, db(v("comp_threshold")), r, db(-(-v("comp_threshold") * (1 - 1 / r.coerceAtLeast(1.0))))),
            color = K.OnSurface, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("compReadout"),
        )
    }
}
