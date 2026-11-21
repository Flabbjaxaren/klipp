package se.gendut.klipp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.AfxSnap
import se.gendut.klipp.engine.AudioFxCatalog

/**
 * What a rack of audio effects acts on – a clip's own effects or a track's (the mixer's inserts): the same controls for
 * both. [index] is the effect's place in the rack ([AfxSnap.index]). [live] changes the playing chain while a control
 * is dragged; the others write to the engine (one undo step each).
 */
class AfxRackOps(
    val add: (String) -> Unit,
    val toggle: (Int) -> Unit,
    val remove: (Int) -> Unit,
    val setParam: (Int, String, Double) -> Unit,
    val setParams: (Int, Map<String, Double>) -> Unit,
    val live: (Int, String, Double) -> Unit,
    /** How many effects the rack holds at most (a track has five slots); null = no limit. */
    val max: Int? = null,
)

/** Audio effects on the selected audio clip (Properties). */
@Composable
fun AudioFxSection(state: EditorState, p: ClipProps, backend: PropsBackend) {
    val ops = remember(p.id, backend) {
        AfxRackOps(
            add = { backend.addAudioFx(p.id, it) },
            toggle = { backend.toggleAudioFx(p.id, it) },
            remove = { backend.removeAudioFx(p.id, it) },
            setParam = { i, k, v -> backend.setAudioFxParam(p.id, i, k, v) },
            setParams = { i, m -> backend.setAudioFxParams(p.id, i, m) },
            live = { i, k, v -> state.onFxLive?.invoke(p.id, i, k, v) },
        )
    }
    AudioFxRack(p.afx, ops, stringResource(R.string.audioui_effects), stringResource(R.string.audioui_clip_effects_hint), key = p.id)
}

/**
 * A rack of audio effects: the effects in their order as cards – the power button turns one on or off, a tap on the
 * card opens its controls in place (one at a time, so the page never grows into a long list of every setting). New
 * effects are picked from tiles that say what each does; the one just added opens where it lands. [key] = whose rack
 * (a new clip or track starts closed).
 */
@Composable
fun AudioFxRack(effects: List<AfxSnap>, ops: AfxRackOps, title: String, hint: String, key: Any? = null) {
    val haptics = rememberHaptics()
    // the open card (its place in the rack) and the picker; a new rack starts with everything closed
    var open by remember(key) { mutableStateOf<Int?>(null) }
    var picking by remember(key) { mutableStateOf(false) }
    // an effect just added: opened once the engine has it (the slots that were there before don't count)
    var added by remember(key) { mutableStateOf<Pair<String, Set<Int>>?>(null) }
    LaunchedEffect(effects) {
        val a = added ?: return@LaunchedEffect
        effects.firstOrNull { it.id == a.first && it.index !in a.second }?.let { open = it.index; added = null }
    }
    // the open card is closed again if its effect went away (removed, undo)
    if (open != null && effects.none { it.index == open }) open = null
    val full = ops.max != null && effects.size >= ops.max
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 2.dp, bottom = 2.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (effects.isNotEmpty() && !full) SpringChip(if (picking) stringResource(R.string.audioui_done_adding) else "+ " + stringResource(R.string.audioui_add), picking,
            { haptics.tick(); picking = !picking }, Modifier.testTag("fxAdd"), tight = true)
    }
    if (effects.isEmpty()) Text(hint, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
    for ((n, e) in effects.withIndex()) key(e.index, e.id) {
        FxCard(e, n + 1, open == e.index, ops, onOpen = { open = if (open == e.index) null else e.index; picking = false })
    }
    // the picker: shown by itself while the rack is empty, otherwise behind "+ Add"
    AnimatedVisibility(effects.isEmpty() || picking, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        FxPicker(effects, full) { id ->
            haptics.confirm()
            added = id to effects.map { it.index }.toSet()
            picking = false
            ops.add(id)
        }
    }
    if (full) Text(stringResource(R.string.audioui_rack_full, ops.max ?: 0), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
}

/** The effects that can be added, as tiles: the name and what it does. One already in the rack is marked. */
@Composable
private fun FxPicker(effects: List<AfxSnap>, full: Boolean, onPick: (String) -> Unit) {
    val all = AudioFxCatalog.all.filter { it.addable }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in all.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (fx in row) {
                val has = effects.any { it.id == fx.id }
                val src = remember { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).pressPop(src, 0.95f).clip(RoundedCornerShape(16.dp)).background(K.SurfaceHigh)
                        .border(1.dp, if (has) K.Primary.copy(alpha = 0.6f) else K.OutlineVar, RoundedCornerShape(16.dp))
                        .clickable(interactionSource = src, indication = null, enabled = !full) { onPick(fx.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp).heightIn(min = 64.dp).testTag("addFx:${fx.id}"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fx.label, color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (has) Icon(Ic.Check, null, tint = K.Primary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                    }
                    Text(fx.hint, color = K.OnSurfaceVar, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** One effect: its number in the chain, name and a summary of its settings; open, the controls below. */
@Composable
private fun FxCard(e: AfxSnap, place: Int, open: Boolean, ops: AfxRackOps, onOpen: () -> Unit) {
    val haptics = rememberHaptics()
    val def = AudioFxCatalog.of(e.id)
    val bring = remember { BringIntoViewRequester() }
    // an opened card comes into view (a long rack scrolls to it)
    LaunchedEffect(open) { if (open) { androidx.compose.runtime.withFrameNanos { }; runCatching { bring.bringIntoView() } } }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (open) K.SurfaceHigh else K.SurfaceHigh.copy(alpha = 0.6f)).bringIntoViewRequester(bring).testTag("fx:${e.id}"),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { haptics.tick(); onOpen() }.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp).testTag("fxOpen:${e.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // on/off: lit when on
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.pressPop(src, 0.85f).size(36.dp).clip(CircleShape).background(if (e.on) K.PrimaryContainer else K.Surface)
                    .border(1.dp, if (e.on) K.PrimaryContainer else K.OutlineVar, CircleShape)
                    .clickable(interactionSource = src, indication = null) { haptics.tick(); ops.toggle(e.index) }.testTag("fxOn:${e.id}"),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Power, stringResource(if (e.on) R.string.audioui_on else R.string.audioui_off), tint = if (e.on) K.PrimarySoft else K.OnSurfaceVar, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("$place · ${AudioFxCatalog.label(e.id)}", color = if (e.on) K.OnSurface else K.OnSurfaceVar, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (e.on) fxSummary(e) else stringResource(R.string.audioui_bypassed), color = K.OnSurfaceVar, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Ic.ChevronDown, null, tint = K.OnSurfaceVar, modifier = Modifier.size(20.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (def == null) {
                    Text(stringResource(R.string.audioui_not_controlled), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                } else {
                    Text(def.hint, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                    val live: (String, Double) -> Unit = { k, nv -> ops.live(e.index, k, nv) }
                    val commit: (Map<String, Double>) -> Unit = { m -> ops.setParams(e.index, m) }
                    when (def.graph) {
                        "eq" -> EqGraph(e.id, e.params, live, commit)
                        "comp" -> CompGraph(e.id, e.params, live, commit)
                    }
                    for (prm in def.params) {
                        val v = (e.params[prm.id] ?: prm.default.toDouble()).toFloat()
                        if (prm.onlyIf != null && (e.params[prm.onlyIf] ?: 0.0) < 0.5) continue
                        if (prm.toggle) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                                SpringChip("${prm.label}: ${prm.format(v)}", v > 0.5f, {
                                    haptics.tick()
                                    val nv = if (v > 0.5f) 0.0 else 1.0
                                    ops.live(e.index, prm.id, nv)
                                    ops.setParam(e.index, prm.id, nv)
                                }, Modifier.testTag("fxP:${e.id}:${prm.id}"))
                            }
                        } else {
                            PropSlider(prm.label, v, prm.min, prm.max, prm.default, prm.format, "fx_${e.id}_${prm.id}",
                                onLive = { nv -> ops.live(e.index, prm.id, nv.toDouble()) },
                                lane = false,
                            ) { nv -> ops.setParam(e.index, prm.id, nv.toDouble()) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    SpringChip(stringResource(R.string.audioui_remove), false, { haptics.heavy(); ops.remove(e.index) }, Modifier.testTag("fxDel:${e.id}"), tight = true)
                }
            }
        }
    }
}

/** A short line for a closed card: the effect's main settings (the first two shown parameters). */
internal fun fxSummary(e: AfxSnap): String {
    val def = AudioFxCatalog.of(e.id) ?: return ""
    return def.params.filter { !it.toggle && (it.onlyIf == null || (e.params[it.onlyIf] ?: 0.0) >= 0.5) }.take(2)
        .joinToString(" · ") { p -> "${p.label} ${p.format((e.params[p.id] ?: p.default.toDouble()).toFloat())}" }
}
