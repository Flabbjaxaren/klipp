package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.LayerMask
import se.gendut.klipp.engine.MaskKey

/**
 * A mask's time contract under the constant frame rate: tracked keyframes follow the picture (the held frame's source
 * time), manual ones are animation (the slot's source time).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class MaskTimeTest {
    private fun mask(tracked: Boolean) = LayerMask(keys = listOf(MaskKey(1.0, 0.2f, 0.5f, 0.3f, 1f, tracked = tracked), MaskKey(1.5, 0.8f, 0.5f, 0.3f, 1f, tracked = tracked)))

    @Test fun trackedFollowsThePictureManualTheSlot() {
        // a held frame from source 1.1 shown on a slot whose own source time is 1.104
        assertEquals(0.32f, mask(true).atFor(1.1, 1.104).cx, 1e-5f)
        assertEquals(0.3248f, mask(false).atFor(1.1, 1.104).cx, 1e-5f)
        // a stretch between a manual and a tracked keyframe is animation
        val mixed = LayerMask(keys = listOf(MaskKey(1.0, 0.2f, 0.5f, 0.3f, 1f, tracked = true), MaskKey(1.5, 0.8f, 0.5f, 0.3f, 1f)))
        assertEquals(0.3248f, mixed.atFor(1.1, 1.104).cx, 1e-5f)
        // one time missing: the other
        assertEquals(0.32f, mask(false).atFor(1.1, null).cx, 1e-5f)
    }

    @Test fun theMarkSurvivesSavingAndOldProjectsAreManual() {
        val m = mask(true)
        val back = LayerMask.fromJson(m.toJson())!!
        assertEquals(listOf(true, true), back.keys.map { it.tracked })
        // a keyframe saved before the mark existed (six elements): manual
        val old = org.json.JSONObject().put("keys", org.json.JSONArray().put(org.json.JSONArray("[1.0,0.2,0.5,0.3,1.0,0]")))
        assertEquals(false, LayerMask.fromJson(old)!!.keys.single().tracked)
        // tracking marks its keyframes
        val track = se.gendut.klipp.engine.WindowTrack(1.0, 0.1, floatArrayOf(0f, 0.1f, 0.3f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, 1f))
        assertEquals(true, MaskKey.fromTrack(track, LayerMask(), 1.0, 1.2).all { it.tracked })
    }
}
