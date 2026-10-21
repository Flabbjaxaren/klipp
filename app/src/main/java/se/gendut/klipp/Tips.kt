package se.gendut.klipp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** First-run tips about the touch gestures, in this order (the key is saved when the tip has been read). */
private val TIPS = listOf(
    "knife" to R.string.tip_knife,
    "out" to R.string.tip_out,
    "edge" to R.string.tip_edge,
    "scrub" to R.string.tip_scrub,
    "undo" to R.string.tip_undo,
    "picture" to R.string.tip_picture,
    "text" to R.string.tip_text,
)

/** One tip at a time over the timeline: "Next" shows the next one, × hides them all for good. */
@Composable
fun TipCard(visible: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(TIPS.indexOfFirst { !AppPrefs.tipSeen(ctx, it.first) }) }
    AnimatedVisibility(
        visible && current >= 0, modifier = modifier,
        enter = slideInVertically(spring(0.7f, 400f)) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
    ) {
        val (key, text) = TIPS[current.coerceIn(0, TIPS.lastIndex)]
        Row(
            Modifier.shadow(8.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(K.SurfaceHighest)
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag("tip:$key"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Bulb, null, tint = K.Primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(text), color = K.OnSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                AppPrefs.setTipSeen(ctx, key)
                current = TIPS.indexOfFirst { !AppPrefs.tipSeen(ctx, it.first) }
            }) { Text(stringResource(if (current < TIPS.lastIndex) R.string.tip_next else R.string.tip_done), color = K.Primary, fontWeight = FontWeight.Bold) }
            RoundIcon(Ic.Close, stringResource(R.string.tip_hide_all), modifier = Modifier.size(40.dp)) {
                TIPS.forEach { AppPrefs.setTipSeen(ctx, it.first) }
                current = -1
            }
        }
    }
}
