package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The volume line on audio clips: the fader scale, and tap / drag / double tap in the timeline. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class VolumeLineTest {
    @get:Rule
    val rule = createComposeRule()

    private class VolProps(val inner: FakeProps = FakeProps()) : PropsBackend by inner {
        val log = mutableListOf<String>()
        override fun addLevelKey(id: Long, at: Float) { log += "add $id %.2f".format(java.util.Locale.ROOT, at) }
        override fun moveLevelKey(id: Long, mt: Long, to: Float, db: Double) { log += "move $id $mt %.2f %.1f".format(java.util.Locale.ROOT, to, db) }
        override fun setLevelLine(id: Long, keys: List<Pair<Long, Double>>, db: Double) {
            log += "line $id ${keys.joinToString(",") { "%d=%.1f".format(java.util.Locale.ROOT, it.first, it.second) }} %.1f".format(java.util.Locale.ROOT, db)
        }
        override fun deleteLevelKey(id: Long, mt: Long) { log += "delete $id $mt" }
    }

    @Test fun faderScale() {
        for (db in listOf(-60f, -40f, -12f, -6f, 0f, 3f, 6f, 15f)) assertEquals(db, ClipLevel.dbAt(ClipLevel.pos(db)), 1e-3f)
        assertEquals(0.76f, ClipLevel.pos(0f), 1e-6f)
        val lv = ClipLevel(0f, listOf(UiKf(2f, 2000, "Linear", listOf(0.0)), UiKf(4f, 4000, "Linear", listOf(-20.0))))
        assertEquals(-10f, lv.at(3f), 1e-4f)
        assertEquals("before the first keyframe: its value", 0f, lv.at(0f), 0f)
        assertEquals(-20f, lv.at(9f), 0f)
    }

    /** A1 (0–8 s "intervju L"): the track starts 132 dp below the ruler, 44 × 1.6 dp tall with the lines on. */
    private val clipTop = 132f + 3f
    private val clipH = 44f * VOL_TRACK_SCALE - 6f
    private fun yOfDb(db: Float): Float { val bt = clipTop + 17f; val bb = clipTop + clipH - 3f; return bb - ClipLevel.pos(db) * (bb - bt) }
    private fun pt(sec: Float, yDp: Float): Offset { val d = rule.density.density; return Offset(sec * 16f * d, (28f + yDp) * d) }

    private fun setup(levels: (Long) -> ClipLevel? = { null }): Triple<EditorState, VolProps, Long> {
        val st = EditorState()
        val props = VolProps()
        st.props = props
        st.volLines = true
        val id = st.clips.first { it.track == 3 && it.start == 0f }.id
        levels(id)?.let { st.clipLevels = mapOf(id to it) }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { Timeline(st, Tool.SELECT, Modifier.fillMaxSize()) } } }
        rule.waitForIdle()
        return Triple(st, props, id)
    }

    @Test fun tapOnTheLineAddsAKeyframe() {
        val (_, props, id) = setup()
        rule.onNodeWithTag("timeline").performTouchInput { down(pt(4f, yOfDb(0f))); up() }
        rule.waitForIdle()
        assertEquals(listOf("add $id 4.00"), props.log)
    }

    @Test fun draggingTheLineSetsTheLevel() {
        val (st, props, id) = setup()
        st.playhead = 2f
        var heard: Float? = null
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(2f, yOfDb(0f))
            down(from)
            for (i in 1..12) moveTo(Offset(from.x, from.y - 4f * density * i))
        }
        rule.waitForIdle()
        heard = st.liveLevelDelta
        rule.onNodeWithTag("timeline").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("heard while dragging: the change at the playhead", 11f, heard!!, 0.05f)
        assertEquals("nothing left after release", null, st.liveLevelDelta)
        props.log.clear()
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(2f, yOfDb(0f))
            down(from)
            // 48 dp up, counted from where the drag began (past the 16 dp test slop, at 20 dp): 28 dp = 0.187 of the scale
            // (150 dp), from 0.76 to 0.947 → 11 dB
            for (i in 1..12) moveTo(Offset(from.x, from.y - 4f * density * i))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("line $id  11.0"), props.log)
        assertTrue("the clip didn't move", st.clips.first { it.id == id }.start == 0f)
    }

    @Test fun draggingBetweenKeyframesMovesBoth() {
        val lv = ClipLevel(0f, listOf(UiKf(2f, 2000, "Linear", listOf(0.0)), UiKf(6f, 6000, "Linear", listOf(-6.0))))
        val (_, props, id) = setup { lv }
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(4f, yOfDb(lv.at(4f)))
            down(from)
            for (i in 1..20) moveTo(Offset(from.x, from.y + 2f * density * i))
            up()
        }
        rule.waitForIdle()
        val line = props.log.single()
        val m = Regex("line $id 2000=(-?[0-9.]+),6000=(-?[0-9.]+) (-?[0-9.]+)").matchEntire(line)!!
        val (a, b, _) = m.destructured
        // both lowered by the same amount
        assertEquals(a.toDouble() - 0.0, b.toDouble() + 6.0, 0.15)
        assertTrue(a.toDouble() < -1.0)
    }

    @Test fun keyframeDragAndDoubleTap() {
        val lv = ClipLevel(0f, listOf(UiKf(2f, 2000, "Linear", listOf(0.0)), UiKf(6f, 6000, "Linear", listOf(0.0))))
        val (_, props, id) = setup { lv }
        // drag the 6 s keyframe left: time follows the finger from where the drag began (48 dp, it began at 20 dp past
        // the test's 16 dp touch slop: 28 dp = 1.75 s → 4.25 s, on a frame at 25p 4.24 s), the level stays
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(6f, yOfDb(0f))
            down(from)
            for (i in 1..12) moveTo(Offset(from.x - 4f * density * i, from.y))
            up()
        }
        rule.waitForIdle()
        assertEquals("move $id 6000 4.24 0.0", props.log.single())
        props.log.clear()
        // double tap the 2 s keyframe = delete
        rule.onNodeWithTag("timeline").performTouchInput {
            down(pt(2f, yOfDb(0f))); up()
            advanceEventTime(120)
            down(pt(2f, yOfDb(0f))); up()
        }
        rule.waitForIdle()
        assertEquals(listOf("delete $id 2000"), props.log)
    }
}
