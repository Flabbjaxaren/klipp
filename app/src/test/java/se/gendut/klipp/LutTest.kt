package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.CubeLut
import se.gendut.klipp.engine.Look
import se.gendut.klipp.engine.LutStore
import se.gendut.klipp.engine.TetrahedralLutEffect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LutTest {
    private val cube2 = """
        # kommentar
        TITLE "Test"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0 0 0
        DOMAIN_MAX 1 1 1
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    @Test
    fun parsesCubeWithRedFastest() {
        val l = CubeLut.parseCube("t", cube2)
        assertEquals(2, l.size)
        val px = FloatArray(3)
        l.at(1, 0, 0, px); assertEquals(1f, px[0]); assertEquals(0f, px[1])
        l.at(0, 1, 0, px); assertEquals(1f, px[1])
        l.at(0, 0, 1, px); assertEquals(1f, px[2])
    }

    @Test
    fun valueLinesReadAsBefore() {
        // the fast value-line reader against the platform's parser: tabs, CRLF, comments after the values, signs,
        // exponents, leading points and many digits; lines that aren't three numbers are skipped as before
        val rnd = Random(7)
        val vals = FloatArray(27 * 3) { rnd.nextFloat() * 1.2f - 0.1f }
        val sb = StringBuilder("LUT_3D_SIZE 3\r\n")
        val forms = listOf<(Float) -> String>(
            { "%.6f".format(java.util.Locale.ROOT, it) }, { "%.10f".format(java.util.Locale.ROOT, it) },
            { "%e".format(java.util.Locale.ROOT, it) }, { "%E".format(java.util.Locale.ROOT, it) },
            { it.toString() }, { "%.4f".format(java.util.Locale.ROOT, it).replace("0.", ".") },
        )
        for (i in 0 until 27) {
            val f = forms[i % forms.size]
            sb.append(if (i % 2 == 0) "\t" else "  ").append(f(vals[i * 3])).append(' ').append(f(vals[i * 3 + 1]))
                .append('\t').append(f(vals[i * 3 + 2])).append(if (i % 3 == 0) " # note" else "").append("\r\n")
            if (i == 5) sb.append("1 2\n0 0 0 0 0\n1 x 1\n")
        }
        val l = CubeLut.parseCube("t", sb.toString())
        val px = FloatArray(3)
        var i = 0
        for (b in 0 until 3) for (g in 0 until 3) for (r in 0 until 3) {
            l.at(r, g, b, px)
            val f = forms[i % forms.size]
            for (c in 0..2) {
                val want = f(vals[i * 3 + c]).toFloat()
                assertEquals(want, px[c], abs(want) * 1e-6f + 1e-9f)
            }
            i++
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects1dLut() { CubeLut.parseCube("x", "LUT_1D_SIZE 4\n0 0 0\n") }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongCount() { CubeLut.parseCube("x", "LUT_3D_SIZE 2\n0 0 0\n1 1 1\n") }

    @Test
    fun identityIsExactEverywhere() {
        val l = CubeLut.identity(17)
        val rnd = Random(1)
        repeat(500) {
            val r = rnd.nextFloat(); val g = rnd.nextFloat(); val b = rnd.nextFloat()
            val o = l.applyTetrahedral(r, g, b)
            assertEquals(r, o[0], 1e-5f); assertEquals(g, o[1], 1e-5f); assertEquals(b, o[2], 1e-5f)
        }
    }

    @Test
    fun tetrahedralIsExactAtNodesAndContinuous() {
        val l = LutStore.get(LutStore.TEAL_ORANGE)!!
        val px = FloatArray(3)
        val n = l.size - 1f
        for (r in listOf(0, 7, 16, 32)) for (g in listOf(0, 9, 32)) for (b in listOf(0, 20, 32)) {
            l.at(r, g, b, px)
            val o = l.applyTetrahedral(r / n, g / n, b / n)
            for (k in 0..2) assertEquals(px[k], o[k], 1e-5f)
        }
        // continuity across a tetrahedron boundary
        val a = l.applyTetrahedral(0.5f, 0.5001f, 0.3f)
        val c = l.applyTetrahedral(0.5001f, 0.5f, 0.3f)
        for (k in 0..2) assertTrue(abs(a[k] - c[k]) < 1e-3f)
    }

    /** Emulates the shader's fetchLut() against the packed texture and compares with the CPU reference. */
    @Test
    fun textureLayoutMatchesShaderAddressing() {
        for (lut in listOf(LutStore.get(LutStore.BLEACH)!!, CubeLut.identity(65))) {
            val tex = lut.textureBytes()
            val w = lut.texWidth; val h = lut.texHeight; val n = lut.size.toFloat(); val tpr = lut.tilesPerRow.toFloat()
            fun fetch(ri: Float, gi: Float, bi: Float): FloatArray {
                val tx = bi % tpr; val ty = floor(bi / tpr)
                val u = (tx * n + ri + 0.5f) / w; val v = (ty * n + gi + 0.5f) / h
                val x = floor(u * w).toInt(); val y = floor(v * h).toInt()  // NEAREST
                val o = (y * w + x) * 4
                return FloatArray(3) { k -> (tex.get(o + k).toInt() and 0xFF) / 255f }
            }
            val px = FloatArray(3)
            val rnd = Random(7)
            repeat(300) {
                val r = rnd.nextInt(lut.size); val g = rnd.nextInt(lut.size); val b = rnd.nextInt(lut.size)
                lut.at(r, g, b, px)
                val f = fetch(r.toFloat(), g.toFloat(), b.toFloat())
                for (k in 0..2) assertEquals("${lut.name} ($r,$g,$b)", px[k], f[k], 0.5f / 255f + 1e-6f)
            }
            assertTrue("texture ≤ 4096 px for wide GPUs", w <= 4096 && h <= 4096)
        }
    }

    @Test
    fun lutLandsInCompositionAsTetraOrTrilinear() {
        val lut = LutStore.get(LutStore.TEAL_ORANGE)!!
        val v1 = se.gendut.klipp.engine.ClipSnap(1, 1, "a", 0.0, 2.0, 0.0, 1.0, null)
        val snap = se.gendut.klipp.engine.Snapshot(
            0.0, 25.0, false, false,
            listOf(se.gendut.klipp.engine.MediaInfo(1, "a", null, 10.0, true, false, 1280, 720, 25.0, null, null)),
            se.gendut.klipp.engine.SequenceSnap(1280, 720, 2.0, listOf(se.gendut.klipp.engine.TrackSnap(1, "V1", false, false, listOf(v1))), emptyList()),
        )
        val uri = { _: Long -> android.net.Uri.parse("file:///a.mp4") }
        val tetra = CompositionBuilder.build(snap, uri, Look(lut = lut, lutMix = 0.8f))!!
        val fx = tetra.effects.videoEffects.filterNot { it is se.gendut.klipp.engine.FrameRateEffect }
        // the LUT is part of the look's single pass (tetrahedral), with its strength
        val le = fx.single() as se.gendut.klipp.engine.LookEffect
        assertEquals(lut, le.look.lut)
        assertEquals(0.8f, le.look.lutMix)
        assertTrue("the clip itself without the LUT", tetra.sequences[0].editedMediaItems[0].effects.videoEffects.none { it is se.gendut.klipp.engine.LookEffect })
        val tri = CompositionBuilder.build(snap, uri, Look(lut = lut, tetra = false))!!
        assertTrue(tri.effects.videoEffects.first() is androidx.media3.effect.SingleColorLut)
        assertNotNull(tri)
    }
}
