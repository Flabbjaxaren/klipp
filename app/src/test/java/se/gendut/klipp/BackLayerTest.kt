package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test

/** The order back closes things in (Back.kt): innermost first, one layer per press, the export's back left as it was. */
class BackLayerTest {
    /** Presses back until nothing is left, closing what each press names; returns the layers in order. */
    private fun pressAll(start: BackState): List<BackLayer> {
        var s = start
        val out = ArrayList<BackLayer>()
        while (true) {
            val l = backLayer(s)
            out += l
            if (l == BackLayer.NONE) return out
            s = when (l) {
                BackLayer.MEDIA -> s.copy(mediaOpen = false)
                BackLayer.SOURCE -> s.copy(sourceOpen = false)
                BackLayer.PROPERTIES -> s.copy(inspecting = false)
                BackLayer.CAPTION_EDITOR -> s.copy(editingCaption = false)
                BackLayer.DOCK_ROW -> s.copy(dockRow = false)
                BackLayer.COLOR_GRID -> s.copy(colorGrid = false)
                BackLayer.PAGE -> s.copy(page = 0)
                BackLayer.TRANSITION -> s.copy(transition = false)
                BackLayer.CUT -> s.copy(cut = false)
                BackLayer.SELECTION -> s.copy(selection = false, transition = false, cut = false)
                BackLayer.NONE -> s
            }
        }
    }

    @Test fun nothingOpenLeavesTheApp() = assertEquals(BackLayer.NONE, backLayer(BackState()))

    @Test fun oneLayerPerPressInnermostFirst() {
        // everything the edit page can have open at once
        val all = BackState(
            mediaOpen = true, sourceOpen = true, inspecting = true, editingCaption = true, dockRow = true,
            transition = true, cut = true, selection = true,
        )
        assertEquals(
            listOf(
                BackLayer.MEDIA, BackLayer.SOURCE, BackLayer.PROPERTIES, BackLayer.CAPTION_EDITOR, BackLayer.DOCK_ROW,
                BackLayer.TRANSITION, BackLayer.CUT, BackLayer.SELECTION, BackLayer.NONE,
            ),
            pressAll(all),
        )
    }

    @Test fun theDockRowClosesBeforeTheSelection() {
        assertEquals(listOf(BackLayer.DOCK_ROW, BackLayer.SELECTION, BackLayer.NONE), pressAll(BackState(dockRow = true, selection = true)))
    }

    @Test fun theCaptionEditorClosesBeforeTheCaptionIsLetGo() {
        assertEquals(listOf(BackLayer.CAPTION_EDITOR, BackLayer.SELECTION, BackLayer.NONE), pressAll(BackState(editingCaption = true, selection = true)))
    }

    @Test fun theColorGridClosesBeforeThePage() {
        // on the color page the selection is the graded clip: back goes to Edit, it does not let go of it
        assertEquals(listOf(BackLayer.COLOR_GRID, BackLayer.PAGE, BackLayer.SELECTION, BackLayer.NONE), pressAll(BackState(page = 1, colorGrid = true, selection = true)))
        // the grid only counts on the color page
        assertEquals(BackLayer.PAGE, backLayer(BackState(page = 2, colorGrid = true)))
    }

    @Test fun anotherPageGoesToEditFirst() {
        assertEquals(listOf(BackLayer.PAGE, BackLayer.NONE), pressAll(BackState(page = 3)))
        // the edit page's own layers don't count on another page
        assertEquals(BackLayer.PAGE, backLayer(BackState(page = 2, dockRow = true, editingCaption = true)))
    }

    @Test fun anExportKeepsItsBack() {
        // while an export runs, what back used to do stays (sheet, source, properties, page) – and nothing new is
        // taken from the export's own back (which sends the app to the background)
        val s = BackState(exporting = true, dockRow = true, editingCaption = true, transition = true, cut = true, selection = true)
        assertEquals(BackLayer.NONE, backLayer(s))
        // (another page goes to Edit as before; the grid is not a layer of its own then)
        assertEquals(BackLayer.PAGE, backLayer(s.copy(page = 1, colorGrid = true)))
        assertEquals(BackLayer.PAGE, backLayer(BackState(exporting = true, page = 2)))
        assertEquals(BackLayer.PROPERTIES, backLayer(BackState(exporting = true, inspecting = true)))
        assertEquals(BackLayer.MEDIA, backLayer(BackState(exporting = true, mediaOpen = true)))
    }
}
