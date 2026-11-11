package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The tonal palettes behind the accent: Material 3's reference values, contrast of the roles, and the lime default. */
class TonesTest {
    private fun near(expected: Int, actual: Int, tol: Int = 4) {
        for (shift in intArrayOf(16, 8, 0)) {
            val e = expected shr shift and 0xFF
            val a = actual shr shift and 0xFF
            assertTrue("expected #%06X, got #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF), abs(e - a) <= tol)
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        val ya = Tones.yOf(Tones.toneOf(a)) + 5.0
        val yb = Tones.yOf(Tones.toneOf(b)) + 5.0
        return maxOf(ya, yb) / minOf(ya, yb)
    }

    @Test
    fun `hue chroma and tone of a color come back as the same color`() {
        for (c in intArrayOf(0xFF6750A4.toInt(), 0xFFD5F15E.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF808080.toInt(), 0xFF123456.toInt())) {
            val h = Tones.hct(c)
            near(c, Tones.color(h[0], h[1], h[2]), 2)
        }
        // known values: pure blue is hue 282.8, chroma 87.2, tone 32.3
        val blue = Tones.hct(0xFF0000FF.toInt())
        assertEquals(282.8, blue[0], 0.5)
        assertEquals(87.2, blue[1], 0.6)
        assertEquals(32.3, blue[2], 0.1)
    }

    @Test
    fun `the baseline purple gives Material 3's baseline tones`() {
        val r = AccentRoles(0xFF6750A4.toInt())
        near(0xD0BCFF, r.primaryPalette.tone(80))
        near(0x381E72, r.primaryPalette.tone(20))
        near(0x4F378B, r.primaryPalette.tone(30))
        near(0xEADDFF, r.primaryPalette.tone(90))
    }

    @Test
    fun `a tone is that light whatever the hue`() {
        for (hue in 0 until 360 step 15) for (t in intArrayOf(20, 30, 60, 80, 90)) {
            assertEquals("hue $hue tone $t", t.toDouble(), Tones.toneOf(Tones.color(hue.toDouble(), 80.0, t.toDouble())), 0.8)
        }
    }

    @Test
    fun `every accent keeps its text readable`() {
        for (a in Accent.entries) {
            val r = a.roles
            println("%-7s primary #%06X (T%d) on #%06X  container #%06X on #%06X".format(
                a.name, r.primary and 0xFFFFFF, r.primaryTone, r.onPrimary and 0xFFFFFF, r.container and 0xFFFFFF, r.onContainer and 0xFFFFFF))
            assertTrue("${a.name} button", contrast(r.primary, r.onPrimary) >= 7.0)
            assertTrue("${a.name} container", contrast(r.container, r.onContainer) >= 7.0)
            assertTrue("${a.name} tone", r.primaryTone in 80..90)
        }
    }

    @Test
    fun `the default accent is the design's lime`() {
        val r = Accent.LIME.roles
        near(0xD5F15E, r.primary, 12)
        // dark container, light text on it: the lime family
        near(0x35410D, r.container, 16)
    }
}
