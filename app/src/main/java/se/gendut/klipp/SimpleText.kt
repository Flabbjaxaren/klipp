package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.Fonts
import se.gendut.klipp.engine.TitleAnim
import se.gendut.klipp.engine.TitleLook
import se.gendut.klipp.engine.TitlePlate
import se.gendut.klipp.engine.TitleShadow
import se.gendut.klipp.engine.TitleTemplate

/**
 * Simple mode's text styles (the mockup's Text sheet): four looks of the title's own settings – font, weight, shadow and
 * plate, as advanced mode's title panel sets them. A title made with one is an ordinary free title.
 */
enum class SimpleTitleStyle(val label: Int, val look: TitleLook) {
    ELEGANT(R.string.simple_text_elegant, TitleLook(TitleTemplate.FREE, TitleAnim.NONE, font = "Playfair Display", bold = true, shadow = TitleShadow())),
    BOLD(R.string.simple_text_bold, TitleLook(TitleTemplate.FREE, TitleAnim.NONE, font = "Archivo Black", bold = true, shadow = TitleShadow())),
    PLATE(R.string.simple_text_plate, TitleLook(TitleTemplate.FREE, TitleAnim.NONE, bold = true, shadow = TitleShadow.OFF, plate = TitlePlate(on = true))),
    PLAIN(R.string.simple_text_plain, TitleLook(TitleTemplate.FREE, TitleAnim.NONE, bold = false, shadow = TitleShadow())),
}

/** How long a new title shows: a few seconds from the playhead, or the whole storyline clip under it. */
enum class SimpleTitleLength(val seconds: Double?, val label: Int) {
    THREE(3.0, R.string.simple_text_3s), FIVE(5.0, R.string.simple_text_5s), CLIP(null, R.string.simple_text_clip);

    /** Where the title starts and how long it is, with the playhead at [at] (s). */
    fun span(state: EditorState, at: Float): Pair<Float, Double> {
        seconds?.let { return at to it }
        val c = SimpleStripModel.storyline(state).firstOrNull { at >= it.start - 1e-3f && at < it.end - 1e-3f }
            ?: SimpleStripModel.storyline(state).lastOrNull { it.start <= at }
            ?: return at to 3.0
        return c.start to c.length.toDouble()
    }
}

/**
 * A new title in simple mode, in the mockup's order: the text, its style, how long it shows – with the preview on
 * top. The add button is below, always in view ([SimpleTitleAddButton]).
 */
@Composable
internal fun SimpleTitleNew(state: EditorState) {
    val haptics = rememberHaptics()
    val d = state.titleDraft
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        TitlePreview(d.simpleStyle.look, d.lines.joinToString("\n").ifBlank { Str.t(R.string.titleui_title) }, Modifier.fillMaxWidth(), playing = false)
    }
    Column(Modifier.padding(horizontal = 4.dp)) {
        TextInput(stringResource(R.string.simple_text_yours), d.lines.joinToString("\n"), Modifier.testTag("titleField:0"), stacked = true,
            placeholder = stringResource(R.string.titleui_title)) { d.lines = it.split('\n') }
    }
    SectionLabel(stringResource(R.string.simple_text_style))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in SimpleTitleStyle.entries) StyleCard(s, d.simpleStyle == s, Modifier.weight(1f)) { haptics.confirm(); d.simpleStyle = s }
    }
    SectionLabel(stringResource(R.string.simple_text_how_long))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in SimpleTitleLength.entries) SpringChip(stringResource(l.label), d.simpleLength == l, { haptics.tick(); d.simpleLength = l },
            Modifier.weight(1f).testTag("simple:textLength:${l.name}"))
    }
}

/** A style as a card: "Aa" drawn in it, and its name. */
@Composable
private fun StyleCard(s: SimpleTitleStyle, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val name = stringResource(s.label)
    val family = remember(s) { runCatching { FontFamily(Fonts.typeface(s.look.font, s.look.bold, false)) }.getOrDefault(FontFamily.Default) }
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh)
            .border(2.dp, if (on) K.Primary else Color.Transparent, RoundedCornerShape(14.dp))
            .semantics { selected = on }
            .clickable(role = Role.RadioButton, onClickLabel = name, onClick = onClick).testTag("simple:textStyle:${s.name}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val plate = s.look.plate?.on == true
        Text("Aa", fontFamily = family, fontSize = if (plate) 15.sp else 20.sp, fontWeight = if (s.look.bold) FontWeight.Bold else FontWeight.Normal,
            color = K.OnSurface, modifier = if (plate) Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.63f)).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier)
        Text(name, color = K.OnSurfaceVar, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Adds the title being made: at the playhead for its length, or over the whole clip there. One undo step. */
@Composable
internal fun SimpleTitleAddButton(state: EditorState, backend: PropsBackend, onDone: () -> Unit) {
    val haptics = rememberHaptics()
    val d = state.titleDraft
    Row(Modifier.fillMaxWidth().background(K.Surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
        BigButton(stringResource(R.string.simple_text_add), {
            haptics.confirm()
            val (at, secs) = d.simpleLength.span(state, state.playhead)
            backend.addTitle(d.lines.joinToString("\n").trim('\n').ifBlank { Str.t(R.string.titleui_title) }, at, d.simpleStyle.look.encode(), secs) {
                d.lines = emptyList()
                onDone()
            }
        }, color = K.PrimaryContainer, textColor = K.PrimarySoft, modifier = Modifier.fillMaxWidth().testTag("title:add"))
    }
}
