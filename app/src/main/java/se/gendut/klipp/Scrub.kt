package se.gendut.klipp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Scrub speed by how far the finger has moved down from where it went down: a dead zone, then every [stepPx] halves
 * the speed (1×, ½, ¼, ⅛, 1/16). The time is re-anchored at each change, so nothing jumps – only the rate changes.
 * Plain logic (no Compose), shared by the timeline ruler, the program monitor and the source scrub bar.
 */
class ScrubTiers(private val deadPx: Float, private val stepPx: Float, private val maxTier: Int = 4) {
    var tier = 0
        private set
    /** The current speed factor (1, 0.5, 0.25 …). */
    val factor: Double get() = 1.0 / (1 shl tier)
    private var anchorX = 0f
    private var anchorT = 0.0
    private var startY = 0f

    fun start(x: Float, y: Float, t: Double) { anchorX = x; anchorT = t; startY = y; tier = 0 }

    /** The tier the finger's depth asks for, with a little hysteresis (no flicker at a boundary). */
    private fun wanted(dy: Float): Int {
        fun edge(k: Int) = deadPx + (k - 1) * stepPx // depth where tier k begins
        var k = tier
        while (k < maxTier && dy >= edge(k + 1)) k++
        while (k > 0 && dy < edge(k) - stepPx * 0.25f) k--
        return k
    }

    /** The time for the finger at ([x], [y]); [secPerPx] is the full-speed rate (negative = inverted). True in [changed] when the tier changed. */
    fun at(x: Float, y: Float, secPerPx: Double): Double {
        val want = wanted(y - startY)
        changed = want != tier
        if (changed) {
            anchorT += (x - anchorX) * secPerPx * factor
            anchorX = x
            tier = want
        }
        return anchorT + (x - anchorX) * secPerPx * factor
    }

    /** The last call to [at] changed the tier (for a haptic tick). */
    var changed = false
        private set

    companion object {
        fun label(tier: Int): String = when (tier) { 0 -> "1×"; 1 -> "½×"; 2 -> "¼×"; 3 -> "⅛×"; else -> "1/${1 shl tier}×" }
    }
}

/** A small pill with the scrub speed while it is slower than normal. */
@Composable
fun ScrubSpeedBadge(tier: Int, modifier: Modifier = Modifier) {
    AnimatedVisibility(tier > 0, modifier = modifier, enter = scaleIn(spring(0.5f, 600f), initialScale = 0.7f) + fadeIn(), exit = scaleOut(targetScale = 0.7f) + fadeOut()) {
        Text(
            ScrubTiers.label(tier.coerceAtLeast(1)), color = K.OnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(K.Primary).padding(horizontal = 9.dp, vertical = 3.dp).testTag("scrubSpeed"),
        )
    }
}

/** Full monitor width = this many seconds when the picture is scrubbed at full speed. */
private const val MONITOR_SCRUB_SECONDS = 6.0

/**
 * Touch on the program picture: drag sideways = scrub (slower the further down the finger goes, see [ScrubTiers]),
 * a second finger tapping while the first is held = one frame forward (right of it) or back (left of it), double tap =
 * [onDoubleTap]. Off while [enabled] is false (the picture's own tools – motion, color windows – have it then).
 */
fun Modifier.monitorTouch(
    state: EditorState,
    enabled: Boolean,
    haptics: Haptics,
    onTier: (Int) -> Unit,
    onDoubleTap: () -> Unit,
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    var lastTapUp = 0L
    var lastTapPos = androidx.compose.ui.geometry.Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture
        if (down.uptimeMillis - lastTapUp < viewConfiguration.doubleTapTimeoutMillis && (down.position - lastTapPos).getDistance() < 48.dp.toPx()) {
            lastTapUp = 0L
            haptics.confirm()
            onDoubleTap()
            return@awaitEachGesture
        }
        val tiers = ScrubTiers(32.dp.toPx(), 48.dp.toPx())
        val secPerPx = MONITOR_SCRUB_SECONDS / size.width.coerceAtLeast(1)
        var scrubbing = false
        var stepped = false
        var moved = false
        while (true) {
            val ev = awaitPointerEvent()
            for (c in ev.changes) if (c.id != down.id && c.changedToDownIgnoreConsumed() && !scrubbing) {
                val first = ev.changes.firstOrNull { it.id == down.id }?.position ?: down.position
                val dir = if (c.position.x >= first.x) 1 else -1
                state.playing = false
                state.playhead = snapFrame((state.playhead + dir * FRAME).coerceIn(0f, state.projectEnd.coerceAtLeast(0f)))
                haptics.tick()
                stepped = true
                c.consume()
            }
            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) {
                if (!scrubbing && !stepped && !moved && c.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                    lastTapUp = c.uptimeMillis; lastTapPos = c.position
                }
                break
            }
            val d = c.position - down.position
            if (d.getDistance() > viewConfiguration.touchSlop) moved = true
            if (!scrubbing && !stepped && ev.changes.count { it.pressed } == 1 && abs(d.x) > viewConfiguration.touchSlop && abs(d.x) > abs(d.y)) {
                scrubbing = true
                state.playing = false
                tiers.start(c.position.x, down.position.y, state.playhead.toDouble())
            }
            if (scrubbing) {
                val t = tiers.at(c.position.x, c.position.y, secPerPx).coerceIn(0.0, state.projectEnd.toDouble().coerceAtLeast(0.0))
                if (tiers.changed) { onTier(tiers.tier); haptics.tick() }
                state.playhead = t.toFloat()
                c.consume()
            }
        }
        if (scrubbing) onTier(0)
    }
}
