package se.gendut.klipp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.sin

private fun gradeMatrix(g: ColorGrade): ColorMatrix {
    val m = ColorMatrix()
    m.setToSaturation((1f + g.sat.value).coerceIn(0f, 2f))
    val e = 2f.pow(g.exposure.value)
    val c = 1f + g.contrast.value * 0.6f
    val t = g.temp.value * 0.18f
    val ti = g.tint.value * 0.12f
    val off = (1f - c) * 0.5f * 255f
    val tone = ColorMatrix(
        floatArrayOf(
            e * c * (1f + t), 0f, 0f, 0f, off,
            0f, e * c * (1f - ti), 0f, 0f, off,
            0f, 0f, e * c * (1f - t), 0f, off,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    m.timesAssign(tone)
    return m
}

@Composable
fun Monitor(state: EditorState, grade: ColorGrade, title: TitleState, modifier: Modifier = Modifier) {
    val t = state.playhead
    val base = state.clips.firstOrNull { it.track == 2 && it.start <= t && it.end > t && it.id !in state.removing }
    val titleClip = state.clips.firstOrNull { it.track == 0 && it.kind == ClipKind.TITLE && it.start <= t && it.end > t }
    val adjust = state.clips.any { it.track == 1 && it.kind == ClipKind.ADJUST && it.start <= t && it.end > t }
    val scene = base?.let { SCENES[it.scene % SCENES.size] }
    val sky by animateColorAsState(scene?.first ?: Color.Black, tween(260), label = "sky")
    val ground by animateColorAsState(scene?.second ?: Color.Black, tween(260), label = "ground")
    val sun by animateColorAsState(scene?.third ?: Color.Black, tween(260), label = "sun")

    // Title animation
    var preview by remember { mutableStateOf(false) }
    LaunchedEffect(title.previewKey) {
        if (title.previewKey > 0) {
            preview = true
            delay(2600)
            preview = false
        }
    }
    val showTitle = titleClip != null || preview
    val tAnim = remember { Animatable(0f) }
    LaunchedEffect(showTitle, title.previewKey, title.style) {
        if (showTitle) {
            tAnim.snapTo(0f)
            val spec = when (title.style) {
                TitleStyle.SLIDE -> spring<Float>(0.85f, 260f)
                TitleStyle.BOUNCE -> spring(0.32f, 300f)
                TitleStyle.SCALE -> spring(0.5f, 380f)
            }
            tAnim.animateTo(1f, spec)
        } else tAnim.animateTo(0f, tween(180))
    }

    // recomputed only when the grade's (animated) values change – not a new matrix every frame
    val filter by remember(grade) { androidx.compose.runtime.derivedStateOf { ColorFilter.colorMatrix(gradeMatrix(grade)) } }
    val mountains = remember { Path() }

    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // gentle parallax when playing
            val drift = (sin(t * 0.6f) * 6.dp.toPx())
            drawRect(sky, size = size, colorFilter = filter)
            drawCircle(sun, h * 0.13f, Offset(w * 0.76f + drift * 0.3f, h * 0.27f), colorFilter = filter)
            mountains.apply {
                reset()
                moveTo(0f, h * 0.73f)
                lineTo(w * 0.23f + drift, h * 0.5f)
                lineTo(w * 0.38f, h * 0.64f)
                lineTo(w * 0.59f + drift * 0.5f, h * 0.37f)
                lineTo(w * 0.82f, h * 0.6f)
                lineTo(w, h * 0.46f)
                lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(mountains, ground, colorFilter = filter)
            if (adjust) drawRect(Color(0x22FFB77C), size = size)
            if (base == null) drawRect(Color.Black, size = size)
        }
        if (base == null) {
            Text(stringResource(R.string.monitor_no_clip), color = K.Outline, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
        // lower third
        if (tAnim.value > 0.001f) {
            val v = tAnim.value
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 22.dp)
                    .graphicsLayer {
                        when (title.style) {
                            TitleStyle.SLIDE -> { translationX = (1f - v) * -120.dp.toPx(); alpha = v.coerceIn(0f, 1f) }
                            TitleStyle.BOUNCE -> { translationY = (1f - v) * 60.dp.toPx(); alpha = v.coerceIn(0f, 1f) }
                            TitleStyle.SCALE -> { scaleX = 0.4f + 0.6f * v; scaleY = 0.4f + 0.6f * v; alpha = v.coerceIn(0f, 1f); transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f) }
                        }
                    }
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                val txt = when (title.template) { 1 -> stringResource(R.string.monitor_sample_chapter); 2 -> stringResource(R.string.monitor_sample_credits); else -> title.name }
                val sub = when (title.template) { 1 -> stringResource(R.string.monitor_sample_chapter_sub); 2 -> title.name + " · " + title.role; else -> title.role }
                Text(txt, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text(sub, color = Color(0xFFE2E2E9), fontSize = 12.sp)
            }
        }
        Chip(stringResource(R.string.monitor_program), Modifier.align(Alignment.TopStart).padding(10.dp))
        base?.let { Chip(it.name, Modifier.align(Alignment.TopEnd).padding(10.dp)) }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier) {
    Text(
        text,
        color = K.OnSurface,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
