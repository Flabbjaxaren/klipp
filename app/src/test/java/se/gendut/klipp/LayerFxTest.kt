package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.BlendMode
import se.gendut.klipp.engine.ChromaKey
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.LayerFx
import se.gendut.klipp.engine.LayerMask
import se.gendut.klipp.engine.LiveGrades

/** Green screen, mask and blend modes: the math, saving, and what carries over to other clips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayerFxTest {
    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)

    @Test fun greenScreenGoesAwayAndThePersonStays() {
        val k = ChromaKey()
        // the screen in different lighting (same hue): removed
        // (also a more saturated green of a slightly different hue: the test image's 00FF00 and the color bars' 00C000)
        for (c in listOf(0x00B140, 0x109A3A, 0x2BD158, 0x0E7A2E, 0x00FF00, 0x00C000, 0x3AA655)) assertEquals("%06x".format(c), 0f, k.apply(rgb(c)), 0.02f)
        // skin, hair, white shirt, blue jeans, gray, yellow, cyan, red: kept
        for (c in listOf(0xE0AC90, 0x8D5524, 0x3B2A20, 0xF2F2F2, 0x2B4A7A, 0x808080, 0xFFFF00, 0x00FFFF, 0xFF0000, 0xC0C000)) assertEquals("%06x".format(c), 1f, k.apply(rgb(c)), 0.02f)
        // spill: a greenish skin tone becomes less green
        val px = floatArrayOf(0.7f, 0.75f, 0.55f)
        k.apply(px)
        assertEquals(0.75f + (0.7f - 0.75f) * 0.6f, px[1], 1e-6f)
        // blue screen keys blue, not green
        val b = ChromaKey(color = ChromaKey.BLUE)
        assertEquals(0f, b.apply(rgb(0x0047BB)), 0.02f)
        assertEquals(1f, b.apply(rgb(0x00B140)), 0.02f)
        assertEquals(2, b.dominant); assertEquals(1, k.dominant)
    }

    @Test fun maskCoversItsShapeWithASoftEdge() {
        val r = LayerMask(LayerMask.RECT, 0.5f, 0.5f, 0.5f, 0.5f, feather = 0f)
        assertEquals(1f, r.coverage(0.5f, 0.5f, 16 / 9f), 1e-4f)
        assertEquals(1f, r.coverage(0.74f, 0.26f, 16 / 9f), 1e-4f)
        assertEquals(0f, r.coverage(0.76f, 0.5f, 16 / 9f), 1e-4f)
        assertEquals(0f, r.coverage(0.5f, 0.8f, 16 / 9f), 1e-4f)
        assertEquals(1f, r.copy(invert = true).coverage(0.9f, 0.9f, 16 / 9f), 1e-4f)
        // soft edge: halfway along the edge
        assertEquals(0.5f, r.copy(feather = 0.1f).coverage(0.75f, 0.5f, 16 / 9f), 0.01f)
        val e = LayerMask(LayerMask.ELLIPSE, 0.5f, 0.5f, 0.5f, 0.5f, feather = 0f)
        assertEquals(1f, e.coverage(0.5f, 0.5f, 1f), 1e-4f)
        assertEquals(0f, e.coverage(0.74f, 0.26f, 1f), 1e-4f) // the rectangle's corner: outside the ellipse
        assertEquals(1f, e.coverage(0.74f, 0.5f, 1f), 1e-4f)
        // key and mask together: multiplied
        val fx = LayerFx(key = ChromaKey(), mask = r)
        assertEquals(0f, fx.apply(rgb(0xE0AC90), 0.9f, 0.5f, 16 / 9f), 1e-4f)
        assertEquals(1f, fx.apply(rgb(0xE0AC90), 0.5f, 0.5f, 16 / 9f), 1e-4f)
        assertEquals(0f, fx.apply(rgb(0x00B140), 0.5f, 0.5f, 16 / 9f), 0.02f)
    }

    @Test fun blendModesAreTheUsualFormulas() {
        assertEquals(0.25f, BlendMode.MULTIPLY.mix(0.5f, 0.5f), 1e-6f)
        assertEquals(0.75f, BlendMode.SCREEN.mix(0.5f, 0.5f), 1e-6f)
        assertEquals(0.18f, BlendMode.OVERLAY.mix(0.3f, 0.3f), 1e-6f)
        assertEquals(1f, BlendMode.ADD.mix(0.7f, 0.6f), 1e-6f)
        assertEquals(0.2f, BlendMode.DIFFERENCE.mix(0.7f, 0.5f), 1e-6f)
        // soft light at 0.5: no change
        for (d in listOf(0.1f, 0.5f, 0.9f)) assertEquals(d, BlendMode.SOFT_LIGHT.mix(0.5f, d), 1e-6f)
    }

    @Test fun savedWithTheGradeAndNotCopiedToOtherClips() {
        val fx = LayerFx(ChromaKey(tolerance = 0.42f), LayerMask(LayerMask.ELLIPSE, 0.3f, 0.6f, invert = true), BlendMode.SCREEN)
        val g = ClipGrade(layer = fx)
        val back = ClipGrade.fromJson(JSONObject(g.toJson().toString()))
        assertEquals(fx, back.layer)
        assertNull(ClipGrade.fromJson(ClipGrade().toJson()).layer)
        // a grade copied to another clip does not bring the layer along – the receiver's own is kept
        val other = LayerFx(blend = BlendMode.MULTIPLY)
        assertEquals(other, g.forOtherClip(null, other).layer)
        assertNull(g.forOtherClip(null).layer)
        // only a layer (no color): saved anyway
        val gs = GradeState()
        gs.set(1, g)
        assertEquals(fx, gs.grades[1]!!.layer)
        // and a compound group's shared grade does not touch the others' layers
        gs.siblings = { id -> if (id == 1L) listOf(2L) else listOf(1L) }
        gs.set(2, ClipGrade(layer = other))
        gs.set(1, gs.of(1).let { it.copy(nodes = it.nodes.map { n -> n.copy(sat = 0.5f) }) })
        assertEquals(other, gs.grades[2]!!.layer); assertEquals(fx, gs.grades[1]!!.layer)
        assertEquals(0.5f, gs.grades[2]!!.nodes.first().sat, 1e-6f)
    }

    @Test fun liveValuesAndRebuildStructure() {
        val fx = LayerFx(key = ChromaKey())
        LiveGrades.publish(mapOf(5L to ClipGrade(layer = fx)))
        assertEquals(fx, LiveGrades.layer(5))
        val a = LiveGrades.structureOf(ClipGrade(layer = fx))
        // changed values: same structure (live); blend mode or new mask: new
        assertEquals(a, LiveGrades.structureOf(ClipGrade(layer = fx.copy(key = ChromaKey(tolerance = 0.1f)))))
        assertTrue(a != LiveGrades.structureOf(ClipGrade(layer = fx.copy(blend = BlendMode.SCREEN))))
        assertTrue(a != LiveGrades.structureOf(ClipGrade()))
    }
}
