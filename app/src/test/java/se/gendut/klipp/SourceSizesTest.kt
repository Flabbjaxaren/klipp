package se.gendut.klipp

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmEffect
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.MediaInfo
import se.gendut.klipp.engine.Perf
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.SourceSizes

/**
 * The film's reference size (N1) on a cold start: from the project's own media sizes at once, from the codec probe
 * when the project has none – and a size that comes late rebuilds the players once (never a lasting unscaled chain).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceSizesTest {
    companion object {
        @org.junit.BeforeClass @JvmStatic fun data() { se.gendut.klipp.engine.Spektra.open = { java.io.File("src/main/assets/spektrafilm/$it").inputStream() } }
    }

    @Before fun empty() = SourceSizes.clear()

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun media(id: Long, w: Int, h: Int, video: Boolean = true) = MediaInfo(id, "m$id", null, 10.0, video, true, w, h, 25.0, null, null)
    private fun snap(): Snapshot = Snapshot.parse(JSONObject(javaClass.classLoader!!.getResource("snapshot.json")!!.readText()))

    @Test fun theProjectsSizeIsThereAtOnce() {
        val u = Uri.parse("file:///s/a.mp4")
        val rev = SourceSizes.revision
        SourceSizes.remember(listOf(u to media(1, 3840, 2160)))
        idle()
        assertEquals(2160, SourceSizes.short(u))
        assertEquals(2160, CompositionBuilder.filmRefOf(u))
        assertEquals("known before anyone asked: no rebuild", rev, SourceSizes.revision)
        // portrait: the short side
        val p = Uri.parse("file:///s/p.mp4")
        SourceSizes.remember(listOf(p to media(2, 2160, 3840)))
        assertEquals(2160, SourceSizes.short(p))
        // audio only and sizeless items give nothing
        val a = Uri.parse("file:///s/a.m4a")
        SourceSizes.remember(listOf(a to media(3, 0, 0, video = false)))
        assertNull(SourceSizes.short(a))
        assertEquals(0, CompositionBuilder.filmRefOf(a))
    }

    @Test fun aLateSizeRebuildsOnceAndAFailedProbeNever() {
        val u = Uri.parse("file:///s/late.mp4")
        val rev0 = SourceSizes.revision
        assertNull("not known: scale 1 (the export's look), the file played unscaled", SourceSizes.short(u))
        assertEquals(0, CompositionBuilder.filmRefOf(u))
        // a failed probe: nothing changes, nothing is rebuilt
        SourceSizes.probed(u, 0, 0); idle()
        assertEquals(rev0, SourceSizes.revision)
        // the probe comes: once
        SourceSizes.probed(u, 3840, 2160); idle()
        assertEquals(rev0 + 1, SourceSizes.revision)
        assertEquals(2160, CompositionBuilder.filmRefOf(u))
        // again (another probe, the next refresh): no further rebuild
        SourceSizes.probed(u, 3840, 2160)
        SourceSizes.remember(listOf(u to media(1, 3840, 2160))); idle()
        assertEquals(rev0 + 1, SourceSizes.revision)
    }

    @Test fun theProjectWinsOverTheProbe() {
        val u = Uri.parse("file:///s/both.mp4")
        SourceSizes.remember(listOf(u to media(1, 1920, 1080)))
        SourceSizes.probed(u, 3840, 2160)
        assertEquals(1080, SourceSizes.short(u))
        // the project's file replaced by another size (same name): the new size, and a rebuild
        val rev = SourceSizes.revision
        SourceSizes.remember(listOf(u to media(1, 3840, 2160))); idle()
        assertEquals(2160, SourceSizes.short(u))
        assertEquals(rev + 1, SourceSizes.revision)
    }

    /** The built player chain: before the size is known no downscale and the film at scale 1; after, both. */
    @Test fun thePlayerChainFollowsTheSize() {
        val cb = CompositionBuilder
        val s = snap()
        val first = s.sequence!!.video[0].clips[0]
        val uriOf: (Long) -> Uri? = { Uri.parse("file:///n1/4k$it.mp4") }
        val g = ClipGrade(listOf(GradeNode(1, film = Film(grain = false))))
        fun chain() = cb.buildFull(s, uriOf, grades = mapOf(first.id to g), forceSingle = true, liveGrades = true, previewShort = 1080)!!
            .composition.sequences[0].editedMediaItems.first { it.mediaItem.localConfiguration?.uri == uriOf(first.item) }.effects.videoEffects
        val before = chain()
        assertTrue("unknown: no downscale", before.none { it is androidx.media3.effect.Presentation && before.indexOf(it) < before.indexOfFirst { e -> e is FilmEffect } })
        assertEquals(0, before.filterIsInstance<FilmEffect>().single().refShort)
        val rev = SourceSizes.revision
        SourceSizes.probed(uriOf(first.item)!!, 3840, 2160); idle()
        assertEquals("the size came: the players are rebuilt", rev + 1, SourceSizes.revision)
        val after = chain()
        val film = after.indexOfFirst { it is FilmEffect }
        assertTrue("known: the downscale before the film", after.take(film).any { it is androidx.media3.effect.Presentation })
        assertEquals(2160, (after[film] as FilmEffect).refShort)
        // the export: no downscale, the film at the source's size (scale 1 whatever the reference)
        val exp = cb.build(s, uriOf, grades = mapOf(first.id to g))!!.sequences[0].editedMediaItems.first { it.mediaItem.localConfiguration?.uri == uriOf(first.item) }.effects.videoEffects
        val ef = exp.indexOfFirst { it is FilmEffect }
        assertTrue("export: nothing scales before the film", exp.take(ef).none { it is androidx.media3.effect.Presentation })
    }
}
