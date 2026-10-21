package se.gendut.klipp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.AudioPlanner
import se.gendut.klipp.engine.AudioRamp
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.GainRampProcessor
import se.gendut.klipp.engine.Snapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Audio fades: a cross dissolve between two clips and a fade-out, from a real engine snapshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioPlanTest {
    private fun snap() = Snapshot.parse(JSONObject(javaClass.classLoader!!.getResource("snapshot_audio.json")!!.readText()))

    @Test
    fun crossfadeOverlapsAndKeepsConstantPower() {
        val s = snap()
        val a1 = s.sequence!!.audio[0]
        val plan = AudioPlanner.plan(a1) { 30.0 }
        // the cross dissolve 2.6–3.4 s: the outgoing end and the incoming start play at the same time
        assertEquals(2, plan.over.size)
        assertEquals(2.6, plan.over[0].start, 1e-6)
        // at every point in the cross dissolve: the sum of the powers (g²) = 1
        for (t in listOf(2.65, 2.9, 3.0, 3.2, 3.35)) {
            val pieces = (plan.base + plan.over).filter { t >= it.start && t < it.end }
            assertEquals("two pieces play at $t", 2, pieces.size)
            val power = pieces.sumOf { (it.gain(t) * it.gain(t)).toDouble() }
            assertEquals("constant power at $t", 1.0, power, 1e-3)
        }
        // the fade-out at the end: 5.0–6.0 s
        val last = plan.base.last()
        assertEquals(0f, last.gain(last.end - 1e-9), 0.01f)
        val comp = CompositionBuilder.build(s, { android.net.Uri.parse("file:///m/$it.mp4") })!!
        // V1 + A1 + A1's cross dissolve pieces (+ V1's bottom layer) – at least one audio sequence with a ramp
        assertTrue(comp.sequences.flatMap { it.editedMediaItems }.any { e -> e.effects.audioProcessors.any { it is GainRampProcessor } })
    }

    @Test
    fun gainRampProcessorScalesPcmOverThePiece() {
        val piece = se.gendut.klipp.engine.AudioPiece(0.0, 1.0, 1, 0.0, 1.0, 0.0, listOf(AudioRamp(0.0, 1.0, true)))
        val p = GainRampProcessor(piece, 1f)
        p.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(2000).order(ByteOrder.nativeOrder())
        repeat(1000) { input.putShort(10000) }
        input.flip()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder())
        val first = out.getShort(0).toInt()
        val mid = out.getShort(500 * 2).toInt()
        val end = out.getShort(999 * 2).toInt()
        assertEquals(0, first)
        assertEquals((10000 * sqrt(0.5)).toInt().toDouble(), mid.toDouble(), 30.0)
        assertTrue(abs(end - 10000) < 30)
    }
}
