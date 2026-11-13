package se.gendut.klipp

import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlShaderProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import se.gendut.klipp.engine.FrameRateEffect
import se.gendut.klipp.engine.LayerGridEffect

/**
 * The grid's GL stages keep Media3's frame contract: a dropped frame's texture goes straight back to the stage before
 * and the next frame is asked for; a kept one goes on and is handed back only when the stage after releases it. The
 * chain never waits on a frame that went nowhere.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GridEffectFramesTest {
    /** One stage with recording listeners; the stage after releases each output frame at once. */
    private class Rig(val program: GlShaderProgram) {
        val processed = ArrayList<Int>()
        var ready = 0
        val out = ArrayList<Pair<Int, Long>>()
        init {
            program.setInputListener(object : GlShaderProgram.InputListener {
                override fun onReadyToAcceptInputFrame() { ready++ }
                override fun onInputFrameProcessed(inputTexture: GlTextureInfo) { processed += inputTexture.texId }
            })
            program.setOutputListener(object : GlShaderProgram.OutputListener {
                override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
                    out += outputTexture.texId to presentationTimeUs
                    program.releaseOutputFrame(outputTexture)
                }
            })
        }
        fun queue(tex: Int, pts: Long) {
            // (the stage before only sends when asked: one request used per frame)
            assertTrue("asked for a frame before frame $tex", ready > 0)
            ready--
            program.queueInputFrame(androidx.media3.effect.DefaultGlObjectsProvider(), GlTextureInfo(tex, -1, -1, 16, 16), pts)
        }
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun droppedFramesGoBackAtOnceAndKeptOnesAfterRelease() {
        val rig = Rig(FrameRateEffect(25.0).toGlShaderProgram(ctx, false))
        // 120 fps at 25 %: 30 a second on the timeline – every sixth or so is dropped
        val times = (0 until 30).map { 7_771_000L + it * 33_333L }
        times.forEachIndexed { k, t -> rig.queue(k + 1, t) }
        // every texture came back exactly once – dropped or released by the stage after
        assertEquals((1..30).toList(), rig.processed.sorted())
        assertEquals(30, rig.processed.size)
        // the chain kept asking: one request left after the last frame
        assertEquals(1, rig.ready)
        assertTrue(rig.out.size in 24..26)
        rig.out.forEach { (_, t) -> assertEquals(0L, t % 40_000L) }
    }

    @Test fun layerStagesSelectThenPresent() {
        // the layer's clock: timestamps in file µs (120 fps), the segment at 7.76 s from 7.03 s in the file, at 25 %
        val tl = { pts: Long -> 7_760_000L + Math.round((pts - 7_030_000L) / 0.25) }
        val fx = LayerGridEffect(25.0, tl, { 7_760_000L }, { 0.25 })
        val select = Rig(fx.select.toGlShaderProgram(ctx, false))
        val present = Rig(fx.present.toGlShaderProgram(ctx, false))
        val pts = (0 until 30).map { 6_970_000L + Math.round(it * 1e6 / 120) } // pre-roll 60 ms of file, then the segment
        pts.forEachIndexed { k, t -> select.queue(k + 1, t) }
        // select leaves the timestamps alone (the effects between read the frames' own times)
        select.out.forEach { (tex, t) -> assertEquals(pts[tex - 1], t) }
        select.out.forEach { (tex, t) -> present.queue(tex, t) }
        assertEquals(30, select.processed.size)
        assertEquals(1, select.ready)
        assertEquals(1, present.ready)
        // present: rising; the segment's frames shown at slot times on the timeline (within a µs: file time is rounded)
        val shown = present.out.map { it.second }
        assertTrue(shown.zipWithNext().all { (a, b) -> b > a })
        shown.map(tl).filter { it >= 7_759_000L }.forEach { val r = it % 40_000L; assertTrue("on the grid: $it", r <= 4 || r >= 39_996) }
        // pre-roll frames pass unmoved
        present.out.filter { tl(it.second) < 7_759_000L }.forEach { (tex, t) -> assertEquals(pts[tex - 1], t) }
    }

    @Test fun aFlushStartsOver() {
        val p = FrameRateEffect(25.0).toGlShaderProgram(ctx, false)
        val rig = Rig(p)
        rig.queue(1, 1_000_000L)
        p.flush()
        // after the flush the same time is a new stream's first frame, not a slot already taken
        rig.queue(2, 1_000_000L)
        assertEquals(2, rig.out.size)
    }
}
