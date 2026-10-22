package se.gendut.klipp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorZoomTest {
    private fun zoom() = MonitorZoom().apply { image = Size(400f, 225f); monitor = Size(400f, 300f) }

    @Test
    fun pinchKeepsThePointUnderTheFingers() {
        val z = zoom()
        // fingers 100 px right of the center: that point of the image stays there at 2×
        z.transform(Offset(100f, 0f), 2f, Offset.Zero)
        assertEquals(2f, z.scale, 1e-4f)
        assertEquals(-100f, z.pan.x, 1e-3f)
        // image point under the fingers: (100 - pan) / scale = 100 → unchanged
        assertEquals(100f, (100f - z.pan.x) / z.scale, 1e-3f)
    }

    @Test
    fun staysWithinLimits() {
        val z = zoom()
        z.transform(Offset.Zero, 0.5f, Offset(50f, 50f))
        assertEquals(1f, z.scale)
        assertEquals(Offset.Zero, z.pan)
        assertFalse(z.zoomed)
        z.transform(Offset.Zero, 100f, Offset.Zero)
        assertEquals(MonitorZoom.MAX, z.scale)
        assertTrue(z.zoomed)
        // at 2× the 400×225 image is 800×450 in a 400×300 monitor: ±200 sideways, ±75 up/down
        assertEquals(Offset(200f, -75f), MonitorZoom.clampPan(Offset(999f, -999f), 2f, Size(400f, 225f), Size(400f, 300f)))
        // smaller than the monitor along an axis: centered along it
        assertEquals(0f, MonitorZoom.clampPan(Offset(0f, 40f), 1.2f, Size(400f, 225f), Size(400f, 300f)).y)
        z.reset()
        assertEquals(1f, z.scale)
        assertEquals(Offset.Zero, z.pan)
    }

    @Test
    fun fullscreenTurnsWithThePicture() {
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, fullscreenOrientation(16f / 9f))
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, fullscreenOrientation(9f / 16f))
        assertNull(fullscreenOrientation(1f))
    }
}
