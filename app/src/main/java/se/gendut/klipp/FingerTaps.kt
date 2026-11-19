package se.gendut.klipp

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** The edit page's undo and redo, for the finger taps inside sheets (see [fingerTaps]). */
class UndoRedo(val undo: () -> Unit, val redo: () -> Unit)

/** Provided by the editor (App.kt) around everything it opens – its sheets are windows of their own. */
val LocalUndoRedo = compositionLocalOf<UndoRedo?> { null }

/**
 * The editor's finger taps inside a sheet: two-finger tap = undo, three-finger tap = redo, as on the edit page itself
 * (App.kt's global gestures). A sheet is a window of its own, so the edit page's gestures don't reach into it.
 * The touch is only watched (the initial pass, nothing consumed): a two-finger scroll or a pinch moves, and is no tap.
 */
@Composable
fun Modifier.fingerTaps(): Modifier {
    val ur = LocalUndoRedo.current ?: return this
    return this.pointerInput(ur) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val t0 = first.uptimeMillis
            var maxCount = 1
            var moved = false
            var end = t0
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = ev.changes.count { it.pressed }
                if (pressed > maxCount) maxCount = pressed
                end = ev.changes.maxOf { it.uptimeMillis }
                if (maxCount >= 2) {
                    val total = ev.changes.sumOf { (abs(it.position.x - it.previousPosition.x) + abs(it.position.y - it.previousPosition.y)).toDouble() }
                    if (total > 2.0) moved = true
                }
                if (pressed == 0) break
            }
            val dur = end - t0
            if (!moved && maxCount == 2 && dur < 300) ur.undo()
            else if (!moved && maxCount == 3 && dur < 400) ur.redo()
        }
    }
}
