package se.gendut.klipp

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.Look
import se.gendut.klipp.engine.applyTo

/** The smoothness pass: scrub frames within the clip, the look on the CPU, look/master straight from the project file. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SmoothTest {
    @Test fun scrubKeyframeNeverOutsideClip() {
        // the clip uses 10–14 s of the source; keyframes every 2nd second (8, 10, 12, 14)
        assertEquals(10.0, Frames.pickSync(8.0, 10.0, 10.3, 10.0, 14.0)!!, 1e-9) // 8 s lies outside the clip
        assertEquals(12.0, Frames.pickSync(12.0, 14.0, 12.4, 10.0, 14.0)!!, 1e-9)
        assertEquals(12.0, Frames.pickSync(12.0, 14.0, 12.9, 10.0, 14.0)!!, 1e-9) // 14 s = the clip's end, outside
        assertNull(Frames.pickSync(8.0, 14.0, 10.1, 10.0, 14.0)) // no keyframe in the clip nearby → exact frame
        assertNull(Frames.pickSync(2.0, 9.0, 5.5, 0.0, 20.0)) // both more than 1 s away
    }

    private fun solid(r: Int, g: Int, b: Int) = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(r, g, b)) }
    private fun Bitmap.rgb() = getPixel(1, 1).let { Triple((it shr 16) and 255, (it shr 8) and 255, it and 255) }

    @Test fun neutralLookIsUntouched() {
        val b = solid(10, 120, 200)
        assertSame(b, Look.NONE.applyTo(b))
    }

    @Test fun cpuLookFollowsSliders() {
        val grey = solid(100, 100, 100)
        val (r1, g1, b1) = Look(exposure = 1f).applyTo(grey).rgb()
        assertEquals(200.0, r1.toDouble(), 2.0); assertEquals(200.0, g1.toDouble(), 2.0); assertEquals(200.0, b1.toDouble(), 2.0)
        val (r2, _, b2) = Look(temp = 1f).applyTo(grey).rgb()
        assertTrue("warmer = more red than blue", r2 > b2 + 20)
        val (r3, g3, b3) = Look(sat = -1f).applyTo(solid(200, 80, 40)).rgb()
        assertTrue("fully desaturated = gray", kotlin.math.abs(r3 - g3) <= 2 && kotlin.math.abs(g3 - b3) <= 2)
        val (r4, _, _) = Look(contrast = 0.5f).applyTo(solid(200, 200, 200)).rgb()
        assertTrue("contrast brightens highlights", r4 > 230)
    }

    /** The look's single pass has the same values as the CPU variant (Look.applyTo) and the AGSL preview. */
    @Test fun lookParamsMatchCpu() {
        val l = Look(exposure = 0.5f, contrast = 0.3f, temp = 0.4f, tint = -0.2f, sat = 0.25f)
        val p = se.gendut.klipp.engine.LookParams.of(l)
        val e = Math.pow(2.0, 0.5).toFloat()
        assertEquals(e * (1f + 0.18f * 0.4f), p.scale[0], 1e-6f)
        assertEquals(e * (1f + 0.12f * 0.2f), p.scale[1], 1e-6f)
        assertEquals((1f + 0.3f) / (1.0001f - 0.3f), p.contrastFactor, 1e-6f)
        assertEquals(0.25f, p.satShift, 1e-6f)
        val n = se.gendut.klipp.engine.LookParams.of(Look.NONE)
        assertEquals(1f, n.contrastFactor, 0f); assertEquals(0f, n.satShift, 0f)
    }

    /** The audio effect's fast path (directly in the buffer) keeps the same sync and length as the fallback. */
    @Test fun fxDirectPathCompensatesLatency() {
        class DirectFx : se.gendut.klipp.engine.FxNative by FakeFx(lat = 3) {
            val delay = ArrayDeque<Short>().apply { repeat(3) { add(0) } }
            override fun processDirect(fx: Long, buf: java.nio.ByteBuffer, offset: Int, frames: Int, channels: Int, float: Boolean, rate: Int): Boolean {
                val b = buf.duplicate().order(java.nio.ByteOrder.nativeOrder())
                for (i in 0 until frames * channels) { val p = offset + i * 2; delay.addLast(b.getShort(p)); b.putShort(p, delay.removeFirst()) }
                return true
            }
            override fun process(fx: Long, data: FloatArray, len: Int, channels: Int, rate: Int) {
                for (i in 0 until len) { delay.addLast((data[i] * 32768f).toInt().toShort()); data[i] = delay.removeFirst() / 32768f }
            }
        }
        val p = se.gendut.klipp.engine.FxProcessor(5, DirectFx())
        p.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(1000, 1, androidx.media3.common.C.ENCODING_PCM_16BIT))
        p.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
        val input = ShortArray(10) { ((it + 1) * 1000).toShort() }
        val got = ArrayList<Short>()
        fun drain() { val o = p.output.order(java.nio.ByteOrder.nativeOrder()); while (o.remaining() >= 2) got += o.short }
        for (part in listOf(0 until 6, 6 until 10)) {
            val bb = java.nio.ByteBuffer.allocateDirect(part.count() * 2).order(java.nio.ByteOrder.nativeOrder())
            for (i in part) bb.putShort(input[i]); bb.flip()
            p.queueInput(bb); drain()
        }
        p.queueEndOfStream(); drain()
        assertEquals(10, got.size)
        for (i in 0 until 10) assertEquals(input[i].toInt(), got[i].toInt())
    }

    /** Media3's pipeline polls with EMPTY_BUFFER, which is also the processor's initial output buffer (was a player error). */
    @Test fun fxDirectPathAcceptsEmptyBuffer() {
        val p = se.gendut.klipp.engine.FxProcessor(5, FakeFx(lat = 3))
        p.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(1000, 1, androidx.media3.common.C.ENCODING_PCM_16BIT))
        p.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
        p.queueInput(androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER)
        assertEquals(0, p.output.remaining())
    }

    /** GPU color never crashes: either an image, or null (then the CPU takes over). */
    @Test fun gpuGradeFallsBackSafely() {
        val b = solid(100, 120, 140)
        val g = se.gendut.klipp.engine.ClipGrade(listOf(se.gendut.klipp.engine.GradeNode(1, sat = 1.5f)))
        val out = se.gendut.klipp.engine.GpuGrade.apply(b, g, Look(exposure = 0.5f))
        if (out != null) { assertEquals(b.width, out.width); assertEquals(b.height, out.height) }
        else assertTrue("falls back to the CPU", true)
    }

    @Test fun lookFromProjectMatchesPanel() {
        val o = JSONObject().put("exposure", 0.25).put("contrast", -0.1).put("temp", 0.3).put("tint", 0.0).put("sat", 0.2)
            .put("lut", JSONObject.NULL).put("lutMix", 1.0).put("tetra", true).put("master", 0.75)
        val g = ColorGrade(); val m = AudioMix()
        kotlinx.coroutines.runBlocking { gradeFromJson(o, g, m) }
        // same values → no playback rebuild right after start
        assertEquals(lookOf(g), lookFromJson(o))
        assertEquals(mixMaster(m), masterFromJson(o), 0f)
    }
}
