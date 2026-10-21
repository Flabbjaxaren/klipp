package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.Kf
import se.gendut.klipp.engine.ease
import se.gendut.klipp.engine.valueAt

/** The app draws and previews keyframe curves with the engine's math (crates/project keyframe.rs `ease`). */
class KeyframeEaseTest {
    /** The engine's values for Bezier with out influence 0.6 → in influence 0.9, 0 → 80 (rust tests.rs keyframe_ease_and_influence). */
    @Test fun matchesTheEngine() {
        val ks = listOf(Kf(0, 0.0, listOf(0.0), "Bezier", outInf = 0.6), Kf(1000, 1.0, listOf(80.0), "Linear", inInf = 0.9))
        assertEquals(8.51905335501151, ks.valueAt(0.25)!!, 1e-6)
        assertEquals(62.66094097352554, ks.valueAt(0.5)!!, 1e-6)
        assertEquals(77.29654326012408, ks.valueAt(0.75)!!, 1e-6)
    }

    @Test fun shapes() {
        for (u in listOf(0.0, 0.2, 0.5, 0.9, 1.0)) assertEquals(u, ease("Linear", 1.0 / 3, "Linear", 1.0 / 3, u), 1e-9)
        assertEquals(0.0, ease("Hold", 1.0 / 3, "Linear", 1.0 / 3, 0.7), 0.0)
        // easy ease: symmetric, slow at both ends
        assertEquals(0.5, ease("Bezier", 1.0 / 3, "Linear", 1.0 / 3, 0.5), 1e-6)
        assertTrue(ease("Bezier", 1.0 / 3, "Linear", 1.0 / 3, 0.05) < 0.02)
        assertTrue(ease("Bezier", 1.0 / 3, "Linear", 1.0 / 3, 0.95) > 0.98)
        // ease out leaves slowly, ease in (on the next keyframe) lands slowly
        assertTrue(ease("EaseOut", 1.0 / 3, "Linear", 1.0 / 3, 0.1) < 0.1)
        assertTrue(ease("Linear", 1.0 / 3, "EaseIn", 1.0 / 3, 0.9) > 0.9)
        // more influence = softer start
        assertTrue(ease("EaseOut", 0.9, "Linear", 1.0 / 3, 0.3) < ease("EaseOut", 0.2, "Linear", 1.0 / 3, 0.3))
    }
}
