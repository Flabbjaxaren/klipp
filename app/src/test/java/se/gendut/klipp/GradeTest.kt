package se.gendut.klipp

import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.Curve
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.GradeNodeEffect
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.Wheel

/** The color math (the CPU variant = reference for the shader) and how grades end up in the composition. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GradeTest {
    private fun run(n: GradeNode, r: Float, g: Float, b: Float): FloatArray {
        val c = floatArrayOf(r, g, b)
        GradeNode.apply(n.params(), c)
        return c
    }

    @Test fun neutralNodeIsIdentity() {
        val n = GradeNode(1)
        assertTrue(n.isNeutral)
        for (v in listOf(0f, 0.1f, 0.435f, 0.8f, 1f)) {
            val o = run(n, v, v * 0.5f, 1f - v)
            assertEquals(v, o[0], 1e-4f); assertEquals(v * 0.5f, o[1], 1e-4f); assertEquals(1f - v, o[2], 1e-4f)
        }
    }

    @Test fun wheelsMoveTonesTheRightWay() {
        assertEquals(0f, Wheel(0.6f, 0.3f).rgb().sum(), 1e-5f) // the color shift does not change the luminance
        assertTrue("puck to the right = redder", Wheel(1f, 0f).rgb()[0] > 0.5f)
        val gain = run(GradeNode(1, gain = Wheel(master = 0.5f)), 0.5f, 0.5f, 0.5f)
        assertEquals(0.75f, gain[0], 1e-4f)
        val lift = run(GradeNode(1, lift = Wheel(master = -0.2f)), 0.1f, 0.1f, 0.1f)
        assertTrue("negative lift = darker shadows", lift[0] < 0.05f)
        val liftHigh = run(GradeNode(1, lift = Wheel(master = -0.2f)), 1f, 1f, 1f)
        assertEquals("lift leaves white", 1f, liftHigh[0], 1e-4f)
        val gamma = run(GradeNode(1, gamma = Wheel(master = 0.3f)), 0.5f, 0.5f, 0.5f)
        assertTrue("positive gamma = brighter midtones", gamma[0] > 0.57f)
        val warm = run(GradeNode(1, gain = Wheel(1f, 0f)), 0.5f, 0.5f, 0.5f)
        assertTrue("gain toward red", warm[0] > warm[1] && warm[0] > warm[2])
    }

    @Test fun contrastPivotSaturationHue() {
        val c = run(GradeNode(1, contrast = 1.5f), 0.435f, 0.2f, 0.8f)
        assertEquals("pivot stays put", 0.435f, c[0], 1e-4f)
        assertTrue(c[1] < 0.2f && c[2] > 0.8f)
        val grey = run(GradeNode(1, sat = 0f), 0.9f, 0.2f, 0.1f)
        assertEquals(grey[0], grey[1], 1e-4f); assertEquals(grey[1], grey[2], 1e-4f)
        val h = run(GradeNode(1, hue = 120f), 0.8f, 0.2f, 0.2f)
        assertTrue("120° rotates red toward green", h[1] > h[0] && h[1] > h[2])
    }

    @Test fun curvesAreSmoothMonotoneAndCompose() {
        val id = Curve().table()
        assertEquals(0.5f, id[128], 0.01f)
        val lifted = Curve(listOf(0f to 0f, 0.5f to 0.7f, 1f to 1f)).table()
        assertEquals(0.7f, lifted[128], 0.02f)
        for (i in 1 until 256) assertTrue("monotonic", lifted[i] >= lifted[i - 1] - 1e-6f)
        val o = run(GradeNode(1, curveY = Curve(listOf(0f to 0f, 0.5f to 0.7f, 1f to 1f)), curveB = Curve(listOf(0f to 0f, 1f to 0.5f))), 0.5f, 0.5f, 0.5f)
        assertEquals(0.7f, o[0], 0.02f)
        assertEquals("Y first, then B", 0.35f, o[2], 0.02f)
    }

    @Test fun mixBlendsAndDisabledNodeIsSkipped() {
        val half = run(GradeNode(1, gain = Wheel(master = 1f), mix = 0.5f), 0.4f, 0.4f, 0.4f)
        assertEquals(0.6f, half[0], 1e-4f)
        val g = ClipGrade(listOf(GradeNode(1, gain = Wheel(master = 1f), enabled = false), GradeNode(2, sat = 0.5f)))
        assertEquals("a disabled node gives no shader pass", 1, g.effects().size)
        assertTrue(g.effects().single() is GradeNodeEffect)
    }

    @Test fun jsonRoundTrip() {
        val g = ClipGrade(listOf(
            GradeNode(1, "Primär", lift = Wheel(0.1f, -0.2f, -0.05f), contrast = 1.2f, curveY = Curve(listOf(0f to 0.05f, 0.5f to 0.55f, 1f to 1f))),
            GradeNode(2, "Look", enabled = false, hue = 12f, sat = 1.3f, mix = 0.6f),
        ))
        val back = ClipGrade.fromJson(JSONObject(g.toJson().toString()))
        assertEquals(g, back)
        val gs = GradeState()
        gs.set(7, g)
        val gs2 = GradeState().apply { load(JSONObject(gs.toJson().toString())) }
        assertEquals(g, gs2.grades[7])
    }

    @Test fun bitmapGradeAndScopes() {
        val w = 64; val h = 36
        val px = IntArray(w * h) { i -> val v = (i % w) * 255 / (w - 1); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val src = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val dark = ClipGrade(listOf(GradeNode(1, gain = Wheel(master = -0.5f)))).applyTo(src)
        val hist = ScopeMath.lumaHistogram(dark)
        assertTrue("halved gain: nothing above 55 %", hist.drop(6).sum() < 1e-6f)
        val wave = ScopeMath.render(src, 1)
        assertEquals(w, wave.width)
        val parade = ScopeMath.render(src, 2)
        assertEquals(w * 3, parade.width)
        assertEquals(128, ScopeMath.render(src, 3).width)
    }

    @Test fun gradesGoIntoTheCompositionBeforeTheTimelineLook() {
        val s = Snapshot.parse(JSONObject(javaClass.classLoader!!.getResource("snapshot.json")!!.readText()))
        val first = s.sequence!!.video[0].clips[0]
        val g = ClipGrade(listOf(GradeNode(1, contrast = 1.3f), GradeNode(2, sat = 1.4f)))
        val comp = CompositionBuilder.build(s, { Uri.parse("file:///m/$it.mp4") }, se.gendut.klipp.engine.Look(contrast = 0.2f), grades = mapOf(first.id to g))!!
        val fx = comp.sequences[0].editedMediaItems.first { it.mediaItem.localConfiguration != null }.effects.videoEffects.filterNot { it is androidx.media3.effect.Presentation || it is se.gendut.klipp.engine.PrerollDrop }
        assertEquals(2, fx.size)
        assertTrue(fx[0] is GradeNodeEffect && fx[1] is GradeNodeEffect)
        assertTrue("the timeline's look after the clips, on the whole composition", comp.effects.videoEffects.first() is se.gendut.klipp.engine.LookEffect)
        val other = comp.sequences[0].editedMediaItems.filter { it.mediaItem.localConfiguration != null }[1].effects.videoEffects.filterNot { it is androidx.media3.effect.Presentation || it is se.gendut.klipp.engine.PrerollDrop }
        assertEquals("ungraded clip: no effects of its own", 0, other.size)
    }

    @Test fun rebuildKeyTellsGradedFromUngraded() {
        // clip 32 with node 1: the structure map's hashCode is 0, as for no grade at all – the key must still differ
        val graded = mapOf(32L to ClipGrade(listOf(GradeNode(1, "Primär", sat = 0.5f))))
        val structure = se.gendut.klipp.engine.LiveGrades.structure(graded)
        assertEquals(structure.hashCode(), emptyMap<Long, List<Int>>().hashCode())
        assertTrue(se.gendut.klipp.engine.LiveGrades.structureKey(graded) != se.gendut.klipp.engine.LiveGrades.structureKey(emptyMap()))
    }

    /** The node's mix covers its film too: halfway, the picture lies halfway between the input and the full film. */
    @Test fun nodeMixBlendsTheFilm() {
        val w = 48; val h = 27
        val px = IntArray(w * h) { i -> val v = 30 + (i % w) * 190 / (w - 1); val r = (v * 1.1).toInt().coerceAtMost(255); (0xFF shl 24) or (r shl 16) or (v shl 8) or (v * 3 / 4) }
        val src = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        se.gendut.klipp.engine.Spektra.open = { java.io.File("src/main/assets/spektrafilm/$it").inputStream() }
        val film = se.gendut.klipp.engine.Film(grainAmount = 0f)
        fun graded(mix: Float) = se.gendut.klipp.engine.ClipGrade(listOf(se.gendut.klipp.engine.GradeNode(1, film = film, mix = mix))).applyTo(src)
        val full = graded(1f); val half = graded(0.5f)
        fun ch(b: android.graphics.Bitmap, i: Int, c: Int) = (b.getPixel(i % w, i / w) shr (16 - 8 * c)) and 255
        var worst = 0; var moved = 0
        for (i in 0 until w * h) for (c in 0..2) {
            val s0 = ch(src, i, c); val f = ch(full, i, c); val m = ch(half, i, c)
            if (kotlin.math.abs(f - s0) > 8) moved++
            worst = kotlin.math.max(worst, kotlin.math.abs(m - (s0 + f) / 2))
        }
        assertTrue("the film changes the picture ($moved)", moved > w * h / 4)
        assertTrue("mix 50 % lies halfway (worst $worst)", worst <= 2)
    }

    /** A node's window holds its film too: the film changes the picture inside the window only. */
    @Test fun nodeWindowHoldsTheFilm() {
        val w = 48; val h = 27
        val px = IntArray(w * h) { i -> val v = 30 + (i % w) * 190 / (w - 1); (0xFF shl 24) or ((v * 1.1).toInt().coerceAtMost(255) shl 16) or (v shl 8) or (v * 3 / 4) }
        val src = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        se.gendut.klipp.engine.Spektra.open = { java.io.File("src/main/assets/spektrafilm/$it").inputStream() }
        val film = se.gendut.klipp.engine.Film(grainAmount = 0f)
        // a hard-edged rectangle over the left half
        val win = se.gendut.klipp.engine.Window(shape = 2, cx = 0.25f, cy = 0.5f, rx = 0.25f, ry = 0.6f, soft = 0f)
        val out = ClipGrade(listOf(GradeNode(1, film = film, win = win))).applyTo(src)
        fun diff(x: Int, y: Int): Int { val a = src.getPixel(x, y); val b = out.getPixel(x, y); return (0..2).maxOf { c -> kotlin.math.abs(((a shr (8 * c)) and 255) - ((b shr (8 * c)) and 255)) } }
        val inside = (2 until w / 2 - 2).count { x -> diff(x, h / 2) > 8 }
        val outside = (w / 2 + 3 until w).maxOf { x -> diff(x, h / 2) }
        assertTrue("the film inside the window ($inside of ${w / 2 - 4})", inside > (w / 2 - 4) / 2)
        assertTrue("nothing outside the window (worst $outside)", outside <= 1)
    }
}
