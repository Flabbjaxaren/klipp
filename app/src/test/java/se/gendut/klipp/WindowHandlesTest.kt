package se.gendut.klipp

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gendut.klipp.engine.Window

/** A color window's handles: width, height and rotation, turned with the window; a tracked window keeps its tracking. */
class WindowHandlesTest {
    private val w = 1000f; private val h = 500f
    private val win = Window(shape = 1, cx = 0.5f, cy = 0.5f, rx = 0.2f, ry = 0.2f)

    @Test fun theHandlesAreFoundWhereTheyAreDrawn() {
        // right edge at (700, 250), top at (500, 150), the rotation 30 px above that
        assertEquals(WindowHandle.WIDTH, windowHandleAt(win, Offset(705f, 252f), w, h, 30f, 30f))
        assertEquals(WindowHandle.HEIGHT, windowHandleAt(win, Offset(500f, 352f), w, h, 30f, 30f))
        assertEquals(WindowHandle.TURN, windowHandleAt(win, Offset(500f, 118f), w, h, 30f, 30f))
        assertNull("the middle: the whole window, as before", windowHandleAt(win, Offset(500f, 250f), w, h, 30f, 30f))
        // turned 90°: the width handles lie above and below the center
        assertEquals(WindowHandle.WIDTH, windowHandleAt(win.copy(angle = 90f), Offset(500f, 450f), w, h, 30f, 30f))
    }

    @Test fun aHandleSetsItsOwnValue() {
        assertEquals(0.3f, windowByHandle(win, win, WindowHandle.WIDTH, Offset(800f, 250f), w, h).rx, 1e-5f)
        assertEquals(0.4f, windowByHandle(win, win, WindowHandle.HEIGHT, Offset(500f, 50f), w, h).ry, 1e-5f)
        assertEquals(90f, windowByHandle(win, win, WindowHandle.TURN, Offset(800f, 250f), w, h).angle, 1e-3f)
    }

    @Test fun aTrackedWindowKeepsItsTrackingScaleAndTurn() {
        // shown twice as big and turned 10° by its tracking: the base value is what the finger shows, without the tracking
        val shown = win.copy(rx = 0.4f, ry = 0.4f, angle = 10f)
        val r = windowByHandle(win, shown, WindowHandle.WIDTH, Offset(500f + 0.6f * w * kotlin.math.cos(Math.toRadians(10.0)).toFloat(), 250f + 0.6f * w * kotlin.math.sin(Math.toRadians(10.0)).toFloat()), w, h)
        assertEquals(0.3f, r.rx, 1e-4f)
        val t = windowByHandle(win, shown, WindowHandle.TURN, Offset(800f, 250f), w, h)
        assertEquals("90° shown = 80° of its own", 80f, t.angle, 1e-3f)
    }
}

class WindowHandlesSmallTest {
    @Test fun aSmallWindowsMiddleMovesIt() {
        // 40 px across: the middle is within reach of every handle, but nearer the center
        val small = Window(shape = 1, cx = 0.5f, cy = 0.5f, rx = 0.02f, ry = 0.04f)
        org.junit.Assert.assertNull(windowHandleAt(small, Offset(502f, 251f), 1000f, 500f, 36f, 30f))
        org.junit.Assert.assertEquals(WindowHandle.WIDTH, windowHandleAt(small, Offset(522f, 250f), 1000f, 500f, 36f, 30f))
    }
}
