package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.FxCurves

/** Lasso, razor line, multi-selection, graphical EQ/compressor and the playhead setting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class EditToolsTest {
    @get:Rule
    val rule = createComposeRule()
    private val dir = "build/shots"

    /** Second and height in dp below the ruler (V3 0, V2 40, V1 80, A1 132, A2 176, A3 220). pps = 16 dp/s. */
    private fun pt(sec: Float, yDp: Float): Offset {
        val d = rule.density.density
        return Offset(sec * 16f * d, (28f + yDp) * d)
    }

    private fun timeline(st: EditorState, tool: Tool = Tool.SELECT) {
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { Timeline(st, tool, Modifier.fillMaxSize()) } } }
        rule.waitForIdle()
    }

    // ------------------------------------------------------------------ geometry and model

    @Test fun pausedSeekOnACutShowsTheIncomingClip() {
        val cuts = doubleArrayOf(8.0413667, 13.0)
        val cs = se.gendut.klipp.engine.CutSeek
        // 29.97 fps: the playhead (Float) on the edit point would otherwise round to 8041 ms = the last fraction of clip 1
        assertEquals(8043L, cs.ms(8.0413667f.toDouble(), cuts, 29.97, playing = false))
        assertEquals(13001L, cs.ms(13.0, cuts, 25.0, playing = false))
        assertEquals(13001L, cs.ms(12.999, cuts, 25.0, playing = false))
        assertEquals("in the middle of a clip: unchanged", 10500L, cs.ms(10.5, cuts, 25.0, playing = false))
        assertEquals("one frame before the edit point: unchanged", 12960L, cs.ms(12.96, cuts, 25.0, playing = false))
        assertEquals("during playback: unchanged", 13000L, cs.ms(13.0, cuts, 25.0, playing = true))
    }

    @Test fun lassoGeometry() {
        val square = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 100f), Offset(0f, 100f))
        assertTrue(TimelineTools.inside(Offset(50f, 50f), square))
        assertFalse(TimelineTools.inside(Offset(150f, 50f), square))
        val rects = mapOf(
            1L to Rect(10f, 10f, 30f, 30f),   // fully inside
            2L to Rect(90f, 40f, 200f, 60f),  // crosses the edge
            3L to Rect(-50f, -50f, 200f, 200f), // the lasso lies inside the clip
            4L to Rect(300f, 0f, 400f, 100f), // outside
        )
        assertEquals(setOf(1L, 2L, 3L), TimelineTools.lassoHits(square, rects))
        assertTrue("two points are no lasso", TimelineTools.lassoHits(square.take(2), rects).isEmpty())
    }

    @Test fun razorCutsOnlyCrossingClipsOnTheTracks() {
        val clips = EditorState.initialClips()
        val cut = TimelineTools.razorCuts(clips, 11f, 2..5).map { it.id }.toSet()
        assertEquals(setOf(2L, 9L, 11L), cut) // B-roll (V1), hamn_amb (A2), music (A3) – A1 has a gap
        assertTrue("no cut at a clip edge", TimelineTools.razorCuts(clips, 8f, 2..2).isEmpty())
    }

    @Test fun lassoSelectionLinkedAndMulti() {
        val clips = listOf(
            Clip(1, "A", ClipKind.VIDEO, 2, 0f, 5f, 0f, 10f, 0, link = 7),
            Clip(2, "A", ClipKind.AUDIO, 3, 0f, 5f, 0f, 10f, 0, link = 7),
            Clip(3, "B", ClipKind.VIDEO, 2, 5f, 5f, 0f, 10f, 0),
        )
        val st = EditorState(clips)
        // only the audio is caught: linked selection brings the video along = ordinary selection of the video clip
        st.lassoSelect(setOf(2L))
        assertEquals(1L, st.selected)
        assertTrue(st.multi.isEmpty())
        // two groups = multi-selection, with partners
        st.lassoSelect(setOf(2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), st.multi)
        assertEquals(null, st.selected)
        // a tap removes a group; one left = ordinary selection
        st.toggleMulti(1L)
        assertEquals(3L, st.selected)
        assertTrue(st.multi.isEmpty())
        // linked selection off: video and audio separately
        st.linkedSelection = false
        st.lassoSelect(setOf(1L, 2L))
        assertEquals(setOf(1L, 2L), st.multi)
        st.clearSelection()
        assertTrue(st.multi.isEmpty() && st.selected == null)
    }

    @Test fun multiMoveAndRazorUseOneBackendCall() {
        val log = mutableListOf<String>()
        val be = object : EditBackend {
            override fun move(clip: Clip, start: Float, track: Int, insert: Boolean) { log += "move ${clip.id}" }
            override fun trim(old: Clip, updated: Clip, ripple: Boolean) {}
            override fun split(id: Long, at: Float) { log += "split $id" }
            override fun remove(id: Long, ripple: Boolean) { log += "remove $id" }
            override fun undo() {}
            override fun redo() {}
            override fun moveMany(moves: List<Triple<Clip, Float, Int>>, insert: Boolean) { log += "moveMany " + moves.joinToString(",") { "${it.first.id}@${it.second}/${it.third}" } + " $insert" }
            override fun removeMany(ids: List<Long>, ripple: Boolean) { log += "removeMany ${ids.sorted()} $ripple" }
            override fun razorTracks(at: Float, tracks: List<Int>) { log += "razor $at $tracks" }
        }
        val st = EditorState().apply { backend = be }
        st.lassoSelect(setOf(2L, 9L))
        st.moveMarked(2f, insert = false)
        assertEquals("moveMany 2@10.0/2,9@10.0/4 false", log.last())
        // cannot move before zero: the whole group stops at 0 (already there = no call)
        st.lassoSelect(setOf(2L, 3L))
        st.moveMarked(-10f, insert = true)
        assertEquals("moveMany 2@0.0/2,3@5.0/2 true", log.last())
        val n = log.size
        st.lassoSelect(setOf(1L, 2L))
        st.moveMarked(-5f, insert = false)
        assertEquals(n, log.size)
        assertEquals(3, st.razorAcross(11f, 2..5))
        assertEquals("razor 11.0 [2, 4, 5]", log.last())
        assertEquals("nothing to cut", 0, st.razorAcross(8f, 2..2))
        // delete several: one call when all animations are done
        st.lassoSelect(setOf(2L, 9L))
        st.removeMarked(true)
        st.finishRemove(2L)
        assertFalse(log.last().startsWith("removeMany"))
        st.finishRemove(9L)
        assertEquals("removeMany [2, 9] true", log.last())
        assertFalse(log.any { it.startsWith("remove ") })
    }

    @Test fun localMultiEditsAreOneUndoStep() {
        val st = EditorState()
        val before = st.clips
        st.lassoSelect(setOf(2L, 9L))
        st.moveMarked(1.5f, insert = false)
        assertEquals(9.5f, st.clips.first { it.id == 2L }.start, 1e-4f)
        assertEquals(9.5f, st.clips.first { it.id == 9L }.start, 1e-4f)
        assertEquals("other clips untouched", before.first { it.id == 1L }, st.clips.first { it.id == 1L })
        st.undo()
        assertEquals(before, st.clips)
        // the razor: three cuts, one undo step
        assertEquals(3, st.razorAcross(11f, 2..5))
        assertEquals(before.size + 3, st.clips.size)
        val right = st.clips.first { it.track == 2 && it.start == 11f }
        assertEquals(before.first { it.id == 2L }.srcIn + 3f, right.srcIn, 1e-4f)
        st.undo()
        assertEquals(before, st.clips)
    }

    // ------------------------------------------------------------------ gestures in the timeline

    @Test fun longPressOnEmptyThenMarqueeSelectsAndGroupDragMovesAll() {
        val st = EditorState().apply { playhead = 30f }
        timeline(st)
        val tl = rule.onNodeWithTag("timeline")
        rule.mainClock.autoAdvance = false
        tl.performTouchInput { down(pt(10.5f, 70f)) } // empty space on V2 (the adjustment ends at 9 s)
        rule.mainClock.advanceTimeBy(800)
        // selection box: diagonally from V2 (10.5 s) down to A2 (12.7 s)
        tl.performTouchInput {
            val from = pt(10.5f, 70f)
            val to = pt(12.7f, 200f)
            for (i in 1..10) moveTo(from + (to - from) * (i / 10f))
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onRoot().captureRoboImage("$dir/60_lasso.png")
        tl.performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(setOf(2L, 9L), st.multi) // B-roll on V1 and hamn_amb on A2
        assertEquals(null, st.selected)
        rule.onRoot().captureRoboImage("$dir/61_lasso_markerat.png")
        // drag one of them: both move by the same amount, the tracks are kept
        tl.performTouchInput {
            val from = pt(10f, 106f)
            down(from)
            for (i in 1..10) moveTo(Offset(from.x + 16f * density * 2.5f * i / 10, from.y + 30f * density * i / 10))
            up()
        }
        rule.waitForIdle()
        val b = st.clips.first { it.id == 2L }
        val a = st.clips.first { it.id == 9L }
        assertEquals(b.start, a.start, 1e-3f)
        assertEquals(10.5f, b.start, 0.6f)
        assertEquals(2, b.track)
        assertEquals(4, a.track)
        assertEquals(setOf(2L, 9L), st.multi)
        // a tap on empty space deselects
        tl.performTouchInput { down(pt(18f, 60f)); up() }
        rule.waitForIdle()
        assertTrue(st.multi.isEmpty())
    }

    /** Delete right after a clip has appeared (the fly-in is running): the removal must always reach the engine. */
    @Test fun deleteWhileClipIsAnimatingStillReachesTheBackend() {
        val log = mutableListOf<String>()
        val st = EditorState().apply { playhead = 3f }
        timeline(st)
        rule.mainClock.autoAdvance = false
        st.addClipAtPlayhead()
        val id = st.selected!!
        rule.mainClock.advanceTimeBy(32) // the fly-in has started
        st.requestRemove(id, false)
        // another removal starts midway (the effect restarts)
        rule.mainClock.advanceTimeBy(48)
        st.requestRemove(2L, false)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        assertTrue("the clip is gone from the model", st.clips.none { it.id == id })
        assertTrue(st.clips.none { it.id == 2L })
        assertTrue("nothing lingers invisibly", st.removing.isEmpty())
        // with an engine: the removal reaches it even when started in the middle of an animation
        val calls = mutableListOf<String>()
        st.backend = object : EditBackend {
            override fun move(clip: Clip, start: Float, track: Int, insert: Boolean) {}
            override fun trim(old: Clip, updated: Clip, ripple: Boolean) {}
            override fun split(id: Long, at: Float) {}
            override fun remove(id: Long, ripple: Boolean) { calls += "remove $id $ripple" }
            override fun undo() {}
            override fun redo() {}
        }
        rule.mainClock.autoAdvance = false
        st.requestRemove(3L, true)
        rule.mainClock.advanceTimeBy(50)
        st.requestRemove(4L, false) // another one midway
        rule.mainClock.autoAdvance = true
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(listOf("remove 3 true", "remove 4 false"), calls.sorted())
        assertTrue(st.removing.isEmpty())
        st.backend = null
        log.clear()
    }

    @Test fun quickDragOnEmptyStillPansAndDoesNotLasso() {
        val st = EditorState().apply { playhead = 30f; centerPlayhead = true }
        timeline(st)
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(10.5f, 70f)
            down(from)
            for (i in 1..8) moveTo(Offset(from.x - 16f * density * 3f * i / 8, from.y))
            up()
        }
        rule.waitForIdle()
        assertTrue(st.multi.isEmpty())
        assertTrue("the timeline was dragged (the playhead moved)", st.playhead > 31f)
    }

    @Test fun razorDragDownCutsEveryTrackItPasses() {
        val st = EditorState().apply { playhead = 30f }
        timeline(st, Tool.RAZOR)
        val n0 = st.clips.size
        val tl = rule.onNodeWithTag("timeline")
        tl.performTouchInput {
            val from = pt(11f, 106f) // B-roll on V1
            down(from)
            for (i in 1..12) moveTo(Offset(from.x + 2f, from.y + (240f - 106f) * density * i / 12))
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/62_rakblad_linje.png")
        tl.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(n0 + 3, st.clips.size) // V1, A2, A3 (A1 has a gap at 11 s)
        assertEquals("V2/V3 above untouched", 1, st.clips.count { it.track == 1 })
        assertTrue(st.clips.any { it.track == 5 && it.start == 11f })
        // an ordinary tap with the razor still splits only the clip
        tl.performTouchInput { down(pt(17f, 106f)); up() }
        rule.waitForIdle()
        assertEquals(n0 + 4, st.clips.size)
        // upward works too: from A3 to V2 at 20.5 s (music, interview L on A1, A003 on V1)
        tl.performTouchInput {
            val from = pt(20.5f, 240f)
            down(from)
            for (i in 1..12) moveTo(Offset(from.x, from.y - (240f - 60f) * density * i / 12))
            up()
        }
        rule.waitForIdle()
        assertEquals(n0 + 7, st.clips.size)
    }

    // ------------------------------------------------------------------ graphical audio effects

    @Test fun curvesWithoutEngine() {
        val f = doubleArrayOf(30.0, 100.0, 1000.0, 10000.0)
        val flat = FxCurves.response("parametric_eq", emptyMap(), f)
        assertTrue(flat.all { kotlin.math.abs(it) < 0.01 })
        val boost = FxCurves.response("parametric_eq", mapOf("mid_gain" to 9.0), f)
        assertEquals(9.0, boost[2], 0.3)
        assertTrue(kotlin.math.abs(boost[0]) < 1.0)
        val hp = FxCurves.response("parametric_eq", mapOf("hp_on" to 1.0, "hp_freq" to 300.0), f)
        assertTrue(hp[0] < -15 && kotlin.math.abs(hp[3]) < 0.5)
        val t = FxCurves.transfer("dynamics_rack", mapOf("comp_threshold" to -20.0, "comp_ratio" to 4.0), doubleArrayOf(-40.0, -20.0, 0.0))
        assertEquals(-40.0, t[0], 1e-6); assertEquals(-20.0, t[1], 1e-6); assertEquals(-15.0, t[2], 1e-6)
        // the geometry round-trips
        val g = EqGeom(1000f, 200f)
        assertEquals(1000.0, g.f(g.x(1000.0)), 0.5)
        assertEquals(6.0, g.db(g.y(6.0)), 1e-3)
        val c = CompGeom(600f, 375f)
        assertEquals(-20.0, c.dbX(c.x(-20.0)), 1e-3)
        assertEquals(4.0, CompGeom.ratioFor(-20.0, -15.0), 1e-6)
        assertEquals(1.0, CompGeom.ratioFor(-20.0, 0.0), 1e-6)
        assertEquals(30.0, CompGeom.ratioFor(-20.0, -25.0), 1e-6)
    }

    @Test fun eqHandleDragsFrequencyAndGainAndDoubleTapResets() {
        val commits = mutableListOf<Map<String, Double>>()
        val lives = mutableListOf<String>()
        rule.setContent { KlippTheme { Column(Modifier.background(K.Bg)) { EqGraph("parametric_eq", emptyMap(), { k, _ -> lives += k }, { commits += it }) } } }
        rule.waitForIdle()
        val node = rule.onNodeWithTag("eqCanvas")
        node.performTouchInput {
            val g = EqGeom(width.toFloat(), height.toFloat())
            val from = Offset(g.x(1000.0), g.y(0.0))
            down(from)
            val to = Offset(g.x(2500.0), g.y(8.0))
            for (i in 1..10) moveTo(from + (to - from) * (i / 10f))
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/63_eq_dras.png")
        node.performTouchInput { up() }
        rule.waitForIdle()
        // b4 sits at 2 kHz – nearest 1 kHz is "mid" (band 3)
        val c = commits.single()
        assertEquals(2500.0, c["mid_freq"]!!, 120.0)
        assertEquals(8.0, c["mid_gain"]!!, 0.6)
        assertTrue("audible while dragging", "mid_gain" in lives)
        // double tap on a band: back to 0 dB
        node.performTouchInput {
            val g = EqGeom(width.toFloat(), height.toFloat())
            val p = Offset(g.x(5000.0), g.y(0.0))
            down(p); up()
            advanceEventTime(120)
            down(p); up()
        }
        rule.waitForIdle()
        assertEquals(mapOf("b5_gain" to 0.0), commits.last())
        // a tap beside the handles does nothing
        val n = commits.size
        node.performTouchInput { val g = EqGeom(width.toFloat(), height.toFloat()); down(Offset(g.x(700.0), g.y(-20.0))); moveBy(Offset(0f, 40f)); up() }
        rule.waitForIdle()
        assertEquals(n, commits.size)
    }

    /** In the (scrollable) properties panel with parameters replaced by the engine: the point follows the finger while dragging. */
    @Test fun eqHandleFollowsFingerInsideScrollingPanel() {
        val be = FakeProps().apply { p = p.copy(isAudio = true, name = "A003.wav", afx = listOf(se.gendut.klipp.engine.AfxSnap(0, "parametric_eq", true, mapOf("mid_gain" to 0.0)))) }
        val st = EditorState().apply { props = be; selected = 1L; inspecting = true }
        val lives = mutableListOf<Pair<String, Double>>()
        st.onFxLive = { _, _, k, v -> lives += k to v }
        rule.setContent { KlippTheme { PropertiesPanel(st) {} } }
        rule.waitForIdle()
        rule.onNodeWithTag("eqCanvas").performScrollTo()
        rule.waitForIdle()
        val node = rule.onNodeWithTag("eqCanvas")
        val readout = { rule.onNodeWithTag("eqReadout").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
        node.performTouchInput {
            val g = EqGeom(width.toFloat(), height.toFloat())
            val from = Offset(g.x(1000.0), g.y(0.0))
            down(from)
            for (i in 1..10) moveTo(from + (Offset(0f, g.y(10.0)) - Offset(0f, from.y)) * (i / 10f))
        }
        rule.waitForIdle()
        val mid = readout()
        assertTrue("the point follows the finger during the drag: $mid", mid.contains("+10,0 dB") || mid.contains("+9,"))
        assertTrue(lives.any { it.first == "mid_gain" && it.second > 8 })
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(10.0, be.p.afx.single().params["mid_gain"]!!, 0.6)
        // the second drag, after the engine sent new values: still follows the finger (it used to get stuck)
        node.performTouchInput {
            val g = EqGeom(width.toFloat(), height.toFloat())
            val from = Offset(g.x(1000.0), g.y(10.0))
            down(from)
            for (i in 1..10) moveTo(from + (Offset(0f, g.y(-6.0)) - Offset(0f, from.y)) * (i / 10f))
        }
        rule.waitForIdle()
        val mid2 = readout()
        assertTrue("the second drag shows immediately: $mid2", mid2.contains("−6,0 dB") || mid2.contains("−5,"))
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(-6.0, be.p.afx.single().params["mid_gain"]!!, 0.6)
    }

    @Test fun compressorKneeAndRatioHandles() {
        val commits = mutableListOf<Map<String, Double>>()
        rule.setContent { KlippTheme { Column(Modifier.background(K.Bg)) { CompGraph("dynamics_rack", emptyMap(), { _, _ -> }, { commits += it }) } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/64_kompressor.png")
        val node = rule.onNodeWithTag("compCanvas")
        node.performTouchInput {
            val g = CompGeom(width.toFloat(), height.toFloat())
            val from = Offset(g.x(-20.0), g.y(-20.0))
            down(from)
            val to = Offset(g.x(-32.0), g.y(-32.0))
            for (i in 1..10) moveTo(from + (to - from) * (i / 10f))
            up()
        }
        rule.waitForIdle()
        assertEquals(-32.0, commits.last()["comp_threshold"]!!, 0.6)
        // the ratio: the handle at 0 dB in, dragged down toward the knee level = harder (the knee is now at −32 dB)
        node.performTouchInput {
            val g = CompGeom(width.toFloat(), height.toFloat())
            val from = Offset(g.x(0.0) - 10 * density, g.y(-16.0)) // 2:1 above −32 dB → −16 dB
            down(from)
            val to = Offset(from.x, g.y(-24.0))
            for (i in 1..10) moveTo(from + (to - from) * (i / 10f))
            up()
        }
        rule.waitForIdle()
        assertEquals(4.0, commits.last()["comp_ratio"]!!, 0.3) // −32 + 32/4 = −24
    }

    // ------------------------------------------------------------------ setting

    @Test fun centerPlayheadDefaultSetting() {
        val ctx = RuntimeEnvironment.getApplication()
        assertFalse(AppPrefs.centerDefault(ctx))
        AppPrefs.setCenterDefault(ctx, true)
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        assertTrue(st.centerPlayhead)
        AppPrefs.setCenterDefault(ctx, false)
    }

    // ------------------------------------------------------------------ the trim tool and track states

    @Test fun slipAndRollInTheModel() {
        val st = EditorState()
        val b = st.clips.first { it.id == 2L } // B-roll 8–13 s, source from 2 s, 11 s long
        // slip: the content shifts, position and duration stay; limited to the source
        assertEquals(1.5f, st.slip(2L, 1.5f), 1e-4f)
        val b2 = st.clips.first { it.id == 2L }
        assertEquals(b.start, b2.start, 1e-4f); assertEquals(b.length, b2.length, 1e-4f)
        assertEquals(b.srcIn + 1.5f, b2.srcIn, 1e-4f)
        assertEquals("cannot slip before the source's start", -3.5f, st.slipLimit(b2, -10f), 1e-4f)
        st.undo()
        // roll: A003 (0–8) | B-roll (8–13): the edit point 1 s to the right
        assertTrue(st.cutPairs().any { it.first.id == 1L && it.second.id == 2L })
        assertEquals(1f, st.roll(1L, 2L, 1f), 1e-4f)
        assertEquals(9f, st.clips.first { it.id == 1L }.end, 1e-4f)
        assertEquals(9f, st.clips.first { it.id == 2L }.start, 1e-4f)
        assertEquals(13f, st.clips.first { it.id == 2L }.end, 1e-4f)
        assertEquals(b.srcIn + 1f, st.clips.first { it.id == 2L }.srcIn, 1e-4f)
        // limited by the left clip's source (A003: in 2, duration 8, source 14 → 4 s left)
        val l = st.clips.first { it.id == 1L }; val r = st.clips.first { it.id == 2L }
        assertEquals(3f, st.rollLimit(l, r, 10f), 1e-4f)
    }

    @Test fun lockedTrackBlocksEditsAndHiddenTrackIsNotPlayed() {
        val notes = mutableListOf<String>()
        val st = EditorState().apply { onNotice = { notes += it } }
        st.setTrackFlag(2, "locked", true) // V1
        val before = st.clips
        st.split(2L, 10f)
        st.requestRemove(2L, false)
        st.moveClip(st.clips.first { it.id == 2L }, 20f, 2, false)
        assertEquals(0, st.razorAcross(11f, 2..2))
        assertEquals(before, st.clips)
        assertTrue(st.removing.isEmpty())
        assertTrue(notes.all { it == "Spåret är låst" } && notes.size >= 3)
        // hidden video track: not played (the plan skips it)
        val json = javaClass.classLoader!!.getResource("snapshot.json")!!.readText()
        val s = se.gendut.klipp.engine.Snapshot.parse(org.json.JSONObject(json))
        assertTrue(se.gendut.klipp.engine.LayerPlanner.plan(s) { 30.0 }!!.layers.isNotEmpty())
        val hidden = s.copy(sequence = s.sequence!!.copy(video = s.sequence!!.video.map { it.copy(enabled = false) }))
        assertTrue(se.gendut.klipp.engine.LayerPlanner.plan(hidden) { 30.0 }?.layers.isNullOrEmpty())
    }

    @Test fun trackHeaderLongPressMenuAndLockedDragPans() {
        val st = EditorState().apply { playhead = 30f }
        timeline(st)
        rule.onNodeWithTag("trackHeader:V1").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/65_sparmeny.png")
        rule.onNodeWithTag("trackFlag:V1:locked").performClick()
        rule.waitForIdle()
        assertTrue(st.trackStates[2].locked)
        // V1 locked: dragging B-roll moves nothing
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(10f, 106f)
            down(from)
            for (i in 1..10) moveTo(Offset(from.x + 16f * density * 3f * i / 10, from.y))
            up()
        }
        rule.waitForIdle()
        assertEquals(8f, st.clips.first { it.id == 2L }.start, 1e-4f)
        // audio track: mute via the menu
        rule.onNodeWithTag("trackHeader:A2").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("trackFlag:A2:muted").performClick()
        rule.waitForIdle()
        assertTrue(st.trackStates[4].muted)
        rule.onRoot().captureRoboImage("$dir/66_spar_last_tyst.png")
    }

    @Test fun trimToolRollsTheCutAndSlipsTheContent() {
        val st = EditorState().apply { playhead = 30f; magnet = false } // (with the magnet the roll snaps to the adjustment's end, 9 s)
        timeline(st, Tool.TRIM)
        val tl = rule.onNodeWithTag("timeline")
        // roll: the edit point at 8 s on V1 is dragged 1.5 s to the right
        tl.performTouchInput {
            val from = pt(8f, 106f)
            down(from)
            for (i in 1..10) moveTo(Offset(from.x + 16f * density * 1.5f * i / 10, from.y))
            up()
        }
        rule.waitForIdle()
        assertEquals(9.5f, st.clips.first { it.id == 1L }.end, 0.15f)
        assertEquals(9.5f, st.clips.first { it.id == 2L }.start, 0.15f)
        assertEquals(13f, st.clips.first { it.id == 2L }.end, 1e-3f)
        // slip: drag A003 (13–22 s) left 2 s = later frames (source +2 s), position stays
        val c = st.clips.first { it.id == 4L }
        tl.performTouchInput {
            val from = pt(20.5f, 106f) // A003 13–22
            down(from)
            for (i in 1..10) moveTo(Offset(from.x - 16f * density * 2f * i / 10, from.y))
            up()
        }
        rule.waitForIdle()
        val a = st.clips.first { it.id == 3L }
        assertEquals(13f, a.start, 1e-3f)
        assertEquals(2f + 2f, a.srcIn, 0.1f)
        assertEquals(c, st.clips.first { it.id == 4L })
    }
}
