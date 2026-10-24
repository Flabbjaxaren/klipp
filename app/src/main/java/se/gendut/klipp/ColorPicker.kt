package se.gendut.klipp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Quick colors above the picker. */
val PICKER_SWATCHES = listOf(0xFFFFFF, 0xC8C8C8, 0x808080, 0x111318, 0xFF5A5F, 0xFF9F43, 0xFFD43B, 0x51CF66, 0x20C9B4, 0x4DABF7, 0x5C7CFA, 0xB37CFF, 0xFF7EB6, 0xFFB77C)

/** RGB (0xRRGGBB) ↔ HSV (hue 0..360, saturation and value 0..1). */
object Hsv {
    fun of(rgb: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(rgb or (0xFF shl 24), it) }
    fun rgb(h: Float, s: Float, v: Float): Int = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF
    fun hex(rgb: Int) = "%06X".format(rgb and 0xFFFFFF)
    fun parse(s: String): Int? = s.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
}

/**
 * Any color: quick swatches, a saturation/brightness field, a hue strip and the hex code. [onLive] while a finger is
 * on the field or the strip, [onCommit] when it lifts (and for a swatch or a typed code).
 */
@Composable
fun ColorPicker(color: Int, tag: String, onLive: (Int) -> Unit = {}, onCommit: (Int) -> Unit) {
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)
    // the hue is kept on its own: gray/black has none, and dragging to it must not lose the hue the user chose
    var hsv by remember { mutableStateOf(Hsv.of(color)) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(color) {
        if (!dragging && Hsv.rgb(hsv[0], hsv[1], hsv[2]) != (color and 0xFFFFFF)) {
            val n = Hsv.of(color)
            hsv = if (n[1] < 1e-3f || n[2] < 1e-3f) floatArrayOf(hsv[0], n[1], n[2]) else n
        }
    }
    val cur = Hsv.rgb(hsv[0], hsv[1], hsv[2])
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in PICKER_SWATCHES) {
                val on = cur == c
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                        .border(if (on) 3.dp else 1.dp, if (on) K.Primary else K.OutlineVar, CircleShape)
                        .clickable { hsv = Hsv.of(c); commit(c) }.testTag("$tag:sw:${Hsv.hex(c)}")
                )
            }
        }
        // saturation (→) and brightness (↑) at the chosen hue
        Box(
            Modifier.fillMaxWidth().height(140.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).testTag("$tag:sv")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragging = true
                        fun set(p: Offset) {
                            val s = (p.x / size.width).coerceIn(0f, 1f); val v = 1f - (p.y / size.height).coerceIn(0f, 1f)
                            hsv = floatArrayOf(hsv[0], s, v); live(Hsv.rgb(hsv[0], s, v))
                        }
                        set(down.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            set(ch.position); ch.consume()
                        }
                        dragging = false
                        commit(Hsv.rgb(hsv[0], hsv[1], hsv[2]))
                    }
                }
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color(0xFF000000 or Hsv.rgb(hsv[0], 1f, 1f).toLong()))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val p = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
                drawCircle(Color.Black.copy(alpha = 0.5f), 11.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
            }
        }
        // the hue
        Box(
            Modifier.fillMaxWidth().height(28.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).testTag("$tag:hue")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragging = true
                        fun set(x: Float) {
                            val h = (x / size.width).coerceIn(0f, 1f) * 360f
                            // a gray color gets full saturation and brightness, otherwise turning the hue would show nothing
                            val s = if (hsv[1] < 0.05f) 1f else hsv[1]; val v = if (hsv[2] < 0.05f) 1f else hsv[2]
                            hsv = floatArrayOf(h, s, v); live(Hsv.rgb(h, s, v))
                        }
                        set(down.position.x)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            set(ch.position.x); ch.consume()
                        }
                        dragging = false
                        commit(Hsv.rgb(hsv[0], hsv[1], hsv[2]))
                    }
                }
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.horizontalGradient((0..6).map { Color(0xFF000000 or Hsv.rgb(it * 60f, 1f, 1f).toLong()) }))
                val x = hsv[0] / 360f * size.width
                drawRect(Color.White, Offset(x - 2.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
            }
        }
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF000000 or cur.toLong())).border(1.dp, K.OutlineVar, RoundedCornerShape(8.dp)))
            Text("#", color = K.OnSurfaceVar, fontSize = 15.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 10.dp))
            var text by remember { mutableStateOf(Hsv.hex(cur)) }
            LaunchedEffect(cur) { if (Hsv.parse(text) != cur) text = Hsv.hex(cur) }
            BasicTextField(
                value = text,
                onValueChange = { s ->
                    text = s.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    Hsv.parse(text)?.let { c -> hsv = Hsv.of(c); commit(c) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                textStyle = TextStyle(color = K.OnSurface, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(K.Primary),
                modifier = Modifier.width(110.dp).clip(RoundedCornerShape(10.dp)).background(K.SurfaceHigh).padding(horizontal = 10.dp, vertical = 8.dp).testTag("$tag:hex"),
            )
        }
    }
}
