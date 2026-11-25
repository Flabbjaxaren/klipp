package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The clip volume slider sets the level where the sound is: a sound clip itself, a video clip's linked sound clips
 * (a video clip holds no level – setting one on it was refused by the engine: "no such effect on clip").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipLevelTargetsTest {
    private val clips = listOf(
        Clip(1, "A003", ClipKind.VIDEO, 2, 0f, 8f, 0f, 20f, 0, item = 1, link = 7),
        Clip(2, "A003", ClipKind.AUDIO, 3, 0f, 8f, 0f, 20f, 0, item = 1, link = 7),
        Clip(3, "A003", ClipKind.AUDIO, 4, 0f, 8f, 0f, 20f, 0, item = 1, link = 7),
        Clip(4, "tyst", ClipKind.VIDEO, 2, 8f, 4f, 0f, 4f, 0, item = 2),
        Clip(5, "Blade", ClipKind.AUDIO, 5, 2f, 1f, 0f, 1f, 0, item = 3),
        Clip(6, "Titel", ClipKind.TITLE, 1, 0f, 3f, 0f, 3f, 0, item = 4, link = 7),
    )

    @Test fun aVideoClipsLevelIsItsLinkedSound() {
        val st = EditorState(clips)
        // both linked sound clips (two audio streams), not the title that happens to share the link
        assertEquals(listOf(2L, 3L), st.levelTargets(1))
        // linked selection off changes nothing: the sound is still the video's
        st.linkedSelection = false
        assertEquals(listOf(2L, 3L), st.levelTargets(1))
    }

    @Test fun aSoundClipIsItsOwnLevel() {
        val st = EditorState(clips)
        assertEquals(listOf(2L), st.levelTargets(2))
        assertEquals(listOf(5L), st.levelTargets(5))
    }

    @Test fun nothingToSetWithoutSound() {
        val st = EditorState(clips)
        assertEquals(emptyList<Long>(), st.levelTargets(4))
        assertEquals(emptyList<Long>(), st.levelTargets(6))
        assertEquals(emptyList<Long>(), st.levelTargets(99))
        // a linked sound clip being removed is not set
        st.removing.addAll(listOf(2L, 3L))
        assertEquals(emptyList<Long>(), st.levelTargets(1))
    }
}
