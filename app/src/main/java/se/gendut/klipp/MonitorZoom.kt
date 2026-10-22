package se.gendut.klipp

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * Zoom into the program image (color page): [scale] around the image's center, then moved by [pan] (px, in the
 * monitor). The image never leaves a gap at an edge it could cover: it stays centered along an axis where it is
 * smaller than the monitor.
 */
class MonitorZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set
    /** The fitted image's size and the monitor's (px), for the limits. */
    var image = Size.Zero
    var monitor = Size.Zero
    val zoomed get() = scale > 1.001f

    /**
     * A pinch: [zoom] around [centroid] (px from the monitor's center) and moved by [move].
     * The point under the fingers stays under them.
     */
    fun transform(centroid: Offset, zoom: Float, move: Offset) {
        val s = (scale * zoom).coerceIn(1f, MAX)
        val k = s / scale
        set(s, centroid - (centroid - pan) * k + move)
    }

    fun reset() = set(1f, Offset.Zero)

    /** Back to 100 %, animated. */
    suspend fun animateOut() {
        val s0 = scale
        val p0 = pan
        if (s0 <= 1f) { reset(); return }
        animate(s0, 1f, animationSpec = spring(1f, 500f)) { v, _ -> set(v, p0 * ((v - 1f) / (s0 - 1f))) }
        reset()
    }

    private fun set(s: Float, p: Offset) {
        scale = s
        pan = clampPan(p, s, image, monitor)
    }

    companion object {
        const val MAX = 8f

        /** How far the image may be moved at [scale]: half of what sticks out of the monitor along each axis (0 if nothing). */
        fun clampPan(p: Offset, scale: Float, image: Size, monitor: Size): Offset {
            val mx = maxOf(0f, (image.width * scale - monitor.width) / 2f)
            val my = maxOf(0f, (image.height * scale - monitor.height) / 2f)
            return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
        }
    }
}

/** The image's transform (on the image box, inside the monitor; the monitor clips). */
fun Modifier.zoomed(z: MonitorZoom): Modifier = graphicsLayer {
    scaleX = z.scale
    scaleY = z.scale
    translationX = z.pan.x
    translationY = z.pan.y
}

/**
 * The monitor's touch on the color page: two fingers zoom and move, one finger moves while zoomed; double tap =
 * 100 % again, or [onFullscreen] when already at 100 %. The image's own tools (window, eyedropper, comparison) get the
 * touch first: what they consume is left alone here.
 */
fun Modifier.zoomGestures(z: MonitorZoom, onDoubleTapOut: () -> Unit, onFullscreen: (() -> Unit)?): Modifier = this
    .pointerInput(z) {
        detectTapGestures(onDoubleTap = { if (z.zoomed) onDoubleTapOut() else onFullscreen?.invoke() })
    }
    .pointerInput(z) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var moving = false
            var travel = Offset.Zero
            while (true) {
                val ev = awaitPointerEvent()
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                // a tool in the image took the touch
                if (ev.changes.any { it.isConsumed }) break
                if (pressed.size < 2 && !z.zoomed) continue
                val zoom = ev.calculateZoom()
                val move = ev.calculatePan()
                if (!moving) {
                    travel += move
                    if (pressed.size >= 2 && abs(zoom - 1f) > 0.02f) moving = true
                    if (travel.getDistance() > viewConfiguration.touchSlop) moving = true
                }
                if (moving) {
                    val c = ev.calculateCentroid(useCurrent = true)
                    if (c != Offset.Unspecified) z.transform(c - Offset(size.width / 2f, size.height / 2f), zoom, move)
                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        }
    }
