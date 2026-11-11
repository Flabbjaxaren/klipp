package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The color page as the app lays it out, without an engine: the top bar, the picture's card with the color page's
 * tools over it (and its scrub, kept inside the clip), the transport, the page, and undo and redo over the corner.
 */
@Composable
internal fun ColorScreen(st: EditorState, gs: GradeState, clips: List<ColorClip>, timeline: @Composable () -> Unit = { Text("tidslinje") }) {
    Box(Modifier.fillMaxSize().background(K.Bg)) {
        Column {
            TopBar({ 1f }, {}, {}, {})
            Box(
                Modifier.fillMaxWidth().height(214.dp).padding(start = 8.dp, end = 8.dp, top = 4.dp).clipToBounds().background(Color.Black)
                    .monitorTouch(
                        st, enabled = gs.pick == null && gs.compare == null && gs.windowEdit == null, haptics = rememberHaptics(), onTier = {}, onDoubleTap = {},
                        within = { currentColorClip(clips, st)?.let { it.start..(it.end - 1f / FPS) } },
                    ).testTag("monitorArea"),
            ) { ColorViewerTools(st, gs, clips, se.gendut.klipp.engine.Look.NONE, onFullscreen = {}) }
            Transport(st)
            // (the bar lies over the page's bottom 84 dp, as in the app)
            CompositionLocalProvider(LocalBarSpace provides 84.dp) { ColorPage(st, gs, clips, timeline = timeline) }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 12.dp)) { UndoRedo(st, {}, {}) }
    }
}
