package se.gendut.klipp

import android.app.Activity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Interface size and full screen (Settings → Appearance).
 * - [scale] shrinks the whole interface (dp and text) by one factor, set in [KlippTheme]: 1 = Klipp's own size.
 * - [immersive] hides the status and navigation bars in the whole app; a swipe from the edge shows them for a moment.
 *
 * From Paulo Gonçalves' fork.
 */
object UiLook {
    /** Normal, Compact, Small. */
    val SCALES = listOf(1f, 0.9f, 0.8f)
    var scale by mutableFloatStateOf(1f)
    var immersive by mutableStateOf(false)

    /** Hides or shows the system bars as [immersive] says (on start, on focus, after the full-screen viewer). */
    fun applyBars(activity: Activity?) {
        val window = activity?.window ?: return
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (immersive) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * A page's safe area. In full screen the top keeps clear of the camera hole only (its own outline, from the display
 * cutout's bounding rects, plus a few dp), not of the whole band the status bar used: on a punch-hole phone that band
 * is mostly empty, and the top bar moves up into it. Otherwise (bars shown, or no outline known) the usual safeDrawing
 * padding.
 */
@Composable
fun Modifier.safeArea(): Modifier {
    val safe = WindowInsets.safeDrawing
    if (!UiLook.immersive) return this.windowInsetsPadding(safe)
    val density = LocalDensity.current
    val view = LocalView.current
    val config = LocalConfiguration.current
    // read so the outline is looked up again once the insets arrive (and after a rotation)
    val cutTop = WindowInsets.displayCutout.getTop(density)
    val holeBottom = remember(cutTop, config.orientation, view) {
        if (cutTop == 0) 0
        else view.rootWindowInsets?.displayCutout?.boundingRects?.filter { it.top < cutTop }?.maxOfOrNull { it.bottom } ?: cutTop
    }
    val top = if (holeBottom > 0) with(density) { holeBottom.toDp() } + 4.dp else 0.dp
    return this.windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).padding(top = top)
}
