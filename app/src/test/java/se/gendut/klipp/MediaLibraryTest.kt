package se.gendut.klipp

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.BinSnap
import se.gendut.klipp.engine.MediaInfo
import se.gendut.klipp.engine.Snapshot

/** Fake media panel: the same rules as the engine (bins, move, rename, delete with clips). */
class FakeLibrary : MediaLibrary {
    override var media by mutableStateOf(listOf(
        MediaInfo(1, "intervju.mp4", null, 60.0, true, true, 1920, 1080, 25.0, null, null),
        MediaInfo(2, "hamn.mp4", null, 12.0, true, true, 1080, 1920, 30.0, null, null),
        MediaInfo(3, "drönare.mp4", null, 20.0, true, false, 3840, 2160, 30.0, null, null),
    ))
    override var bins by mutableStateOf(listOf<BinSnap>())
    override val busy = false
    var used = mutableMapOf(1L to 2)
    val log = mutableListOf<String>()
    private var next = 100L
    override fun uriOf(item: Long): Uri? = null
    override fun usage(item: Long) = used[item] ?: 0
    override fun newBin(name: String, parent: Long?) { log += "newBin $name $parent"; bins = bins + BinSnap(next++, name, parent) }
    override fun moveToBin(items: List<Long>, bin: Long?) {
        log += "move $items $bin"
        media = media.map { if (it.id in items) it.copy(bin = bin) else it }
        bins = bins.map { if (it.id in items) it.copy(parent = bin) else it }
    }
    override fun rename(item: Long, name: String) { log += "rename $item $name"; media = media.map { if (it.id == item) it.copy(name = name) else it } }
    override fun renameBin(bin: Long, name: String) { log += "renameBin $bin $name"; bins = bins.map { if (it.id == bin) it.copy(name = name) else it } }
    override fun delete(items: List<Long>, bins: List<Long>) {
        log += "delete $items $bins"
        val gone = items + bins.flatMap { mediaInBin(it) }
        media = media.filter { it.id !in gone }
        this.bins = this.bins.filter { it.id !in bins }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class MediaLibraryTest {
    @get:Rule
    val rule = createComposeRule()
    private val dir = "build/shots"

    private fun show(lib: FakeLibrary, opened: MutableList<Long> = mutableListOf()) {
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { MediaSheet(true, lib, {}, { opened += it.id }, {}) } } }
        rule.waitForIdle()
    }

    private fun idle() = rule.waitForIdle()

    private fun name(text: String) {
        rule.onNodeWithTag("nameField").performTextReplacement(text)
        idle()
        rule.onNodeWithTag("nameOk").performClick()
        idle()
    }

    @Test fun foldersMoveRenameAndOpen() {
        val lib = FakeLibrary()
        val opened = mutableListOf<Long>()
        show(lib, opened)
        // new bin
        rule.onNodeWithTag("newBin").performClick(); idle()
        name("B-roll")
        assertEquals("B-roll", lib.bins.single().name)
        // long-press two clips → select → move to the bin
        rule.onNodeWithTag("media:hamn.mp4").performTouchInput { longClick() }
        rule.onNodeWithTag("media:drönare.mp4").performClick()
        idle()
        rule.onRoot().captureRoboImage("$dir/70_media_markerat.png")
        rule.onNodeWithTag("mediaMove").performClick(); idle()
        rule.onNodeWithTag("moveTo:${lib.bins.single().id}").performClick(); idle()
        assertEquals(setOf(2L, 3L), lib.media.filter { it.bin == lib.bins.single().id }.map { it.id }.toSet())
        // in the bin: the clips show, a tap opens in the source monitor
        rule.onNodeWithTag("bin:B-roll").performClick(); idle()
        rule.onNodeWithTag("media:hamn.mp4").assertIsDisplayed()
        rule.onRoot().captureRoboImage("$dir/71_media_mapp.png")
        rule.onNodeWithTag("media:hamn.mp4").performClick(); idle()
        assertEquals(listOf(2L), opened)
        // rename a clip
        rule.onNodeWithTag("media:drönare.mp4").performTouchInput { longClick() }; idle()
        rule.onNodeWithTag("mediaRename").performClick(); idle()
        name("Drönare hamnen")
        assertTrue(lib.media.any { it.id == 3L && it.name == "Drönare hamnen" })
        // go up and rename the bin
        rule.onNodeWithTag("binUp").performClick(); idle()
        rule.onNodeWithTag("bin:B-roll").performTouchInput { longClick() }; idle()
        rule.onNodeWithTag("mediaRename").performClick(); idle()
        name("Miljöer")
        assertEquals("Miljöer", lib.bins.single().name)
    }

    @Test fun deleteAsksAndMentionsTimelineUse() {
        val lib = FakeLibrary()
        show(lib)
        rule.onNodeWithTag("media:intervju.mp4").performTouchInput { longClick() }; rule.waitForIdle()
        rule.onNodeWithTag("mediaDelete").performClick(); rule.waitForIdle()
        val text = rule.onNodeWithTag("deleteText").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        assertTrue(text, text.contains("används 2 gånger i tidslinjen"))
        assertTrue(text, text.contains("Filerna på telefonen ligger kvar"))
        rule.onRoot().captureRoboImage("$dir/72_media_ta_bort.png")
        rule.onNodeWithTag("deleteOk").performClick(); rule.waitForIdle()
        assertEquals("delete [1] []", lib.log.last())
        assertTrue(lib.media.none { it.id == 1L })
        // a bin is deleted with its contents
        lib.newBin("Gammalt", null)
        lib.moveToBin(listOf(2L), lib.bins.single().id)
        rule.waitForIdle()
        rule.onNodeWithTag("bin:Gammalt").performTouchInput { longClick() }; rule.waitForIdle()
        rule.onNodeWithTag("mediaDelete").performClick(); rule.waitForIdle()
        val t2 = rule.onNodeWithTag("deleteText").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        assertTrue(t2, t2.startsWith("1 klipp och 1 mapp"))
        rule.onNodeWithTag("deleteOk").performClick(); rule.waitForIdle()
        assertTrue(lib.media.none { it.id == 2L } && lib.bins.isEmpty())
    }

    @Test fun snapshotParsesBins() {
        val s = Snapshot.parse(JSONObject("""{"media":[{"id":1,"name":"a","bin":10},{"id":2,"name":"b","bin":null}],"bins":[{"id":10,"name":"B-roll","parent":null},{"id":11,"name":"Inne","parent":10}]}"""))
        assertEquals(10L, s.media[0].bin)
        assertEquals(null, s.media[1].bin)
        assertEquals(listOf(BinSnap(10, "B-roll", null), BinSnap(11, "Inne", 10)), s.bins)
    }
}
