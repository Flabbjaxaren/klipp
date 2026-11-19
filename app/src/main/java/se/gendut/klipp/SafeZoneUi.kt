package se.gendut.klipp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.SafeZones

/** The platform whose safe zones are shown over the preview (the caption placement offers it first for "fit"). */
val LocalSafeZone = compositionLocalOf<SafeZones.Platform?> { null }

/**
 * A platform's safe zones over the program picture (inside the picture's own box, so it zooms with it): the covered
 * parts shaded, the clear area outlined. Drawn in the app's UI only – never part of the rendered or exported picture.
 * Showing it or switching platform never moves anything in the project.
 */
@Composable
fun SafeZoneOverlay(platform: SafeZones.Platform, alpha: Float, modifier: Modifier = Modifier, vertical: Boolean = true) {
    val z = remember(platform) { SafeZones.of(platform) }
    // not a 9:16 picture: the zones are drawn stretched – said on the picture, not only in the menu
    if (!vertical) Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(stringResource(R.string.safezone_not_vertical), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 3.dp).testTag("safeZones:notVertical"))
    }
    Canvas(modifier.fillMaxSize().testTag("safeZones:${platform.key}")) {
        val shade = Color(0xFF0C0E12).copy(alpha = alpha.coerceIn(0f, 1f))
        for (c in z.covered) drawRect(shade, Offset(c.left * size.width, c.top * size.height), Size(c.width() * size.width, c.height() * size.height))
        val r = z.clear
        drawRect(Color.White.copy(alpha = (0.35f + alpha).coerceAtMost(0.9f)), Offset(r.left * size.width, r.top * size.height),
            Size(r.width() * size.width, r.height() * size.height), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
    }
}

/**
 * The button on the preview that shows a platform's safe zones (or none), with how strongly they are shaded; the
 * choice is kept in the project. [vertical]: the sequence is 9:16 (otherwise the menu says the zones are made for it).
 */
@Composable
fun SafeZoneButton(state: EditorState, vertical: Boolean, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val on = state.safeZone != null
    Box(modifier) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (on) K.PrimaryContainer else Color.Black.copy(alpha = 0.45f))
                .clickable(onClickLabel = stringResource(R.string.safezone_button)) { haptics.tick(); open = true }.testTag("safeZoneButton"),
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.SafeZone, stringResource(R.string.safezone_button), tint = if (on) K.PrimarySoft else Color.White, modifier = Modifier.size(18.dp)) }
        DropdownMenu(open, { open = false }, containerColor = K.SurfaceHighest, modifier = Modifier.widthIn(min = 240.dp).testTag("safeZoneMenu")) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(stringResource(R.string.safezone_button), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                val opts = listOf<Pair<String?, String>>(null to stringResource(R.string.safezone_off)) +
                    SafeZones.Platform.entries.map { it.key to stringResource(it.label) }
                for ((k, l) in opts) {
                    val sel = state.safeZone == k
                    Text(l, color = if (sel) K.PrimarySoft else K.OnSurface, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { haptics.tick(); state.safeZone = k; onChanged() }
                            .padding(horizontal = 16.dp, vertical = 10.dp).testTag("safeZone:${k ?: "off"}"))
                }
                if (on) PropSlider(stringResource(R.string.safezone_opacity), state.safeZoneAlpha * 100f, 10f, 90f, 45f, { "%.0f %%".format(it) }, "safeZoneAlpha", lane = false,
                    onLive = { state.safeZoneAlpha = it / 100f }) { state.safeZoneAlpha = it / 100f; onChanged() }
                if (on && !vertical) Text(stringResource(R.string.safezone_not_vertical), color = K.Error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                Text(stringResource(R.string.safezone_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
}
