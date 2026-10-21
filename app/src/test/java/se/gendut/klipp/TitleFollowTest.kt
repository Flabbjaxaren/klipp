package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.Follow
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.TitleFollow
import se.gendut.klipp.engine.Window
import se.gendut.klipp.engine.WindowTrack

/** A title that follows a tracked window: the displacement since the title's start, and that the link is saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TitleFollowTest {
    // the object moves 0.01 of the frame width to the right per frame (25 fps), from source time 2 s
    private val track = WindowTrack(2.0, 0.04, FloatArray(100) { it * 0.01f }, FloatArray(100) { -it * 0.002f }, FloatArray(100) { 1f }, FloatArray(100) { 1f }, smooth = 0f)
    private val grade = ClipGrade(listOf(GradeNode(1, win = Window(shape = 1, track = track))))
    // the clip sits on the timeline at 10 s with source start 2 s
    private val clip = ClipSnap(7, 1, "A", 10.0, 4.0, 2.0, 1.0, null)

    @Test fun offsetSinceTitleStart() {
        val f = Follow(7, 1)
        // the title starts at 10.4 s (frame 10); at 11.2 s (frame 30) the object has moved 20 frames
        val o = TitleFollow.offsetOf(f, clip, grade, 10.4, 11.2)!!
        assertEquals(0.2f, o[0], 1e-5f)
        assertEquals(-0.04f, o[1], 1e-5f)
        // at the title's start: no displacement
        assertEquals(0f, TitleFollow.offsetOf(f, clip, grade, 10.4, 10.4)!![0], 1e-6f)
        // the node is missing or has no tracking: none
        assertNull(TitleFollow.offsetOf(Follow(7, 9), clip, grade, 10.4, 11.0))
    }

    @Test fun followIsSavedWithGrades() {
        val gs = GradeState()
        gs.set(7, grade)
        gs.setFollow(42, Follow(7, 1))
        val back = GradeState().apply { load(JSONObject(gs.toJson().toString())) }
        assertEquals(Follow(7, 1), back.follows[42])
        assertEquals(1, back.grades.size)
    }
}
