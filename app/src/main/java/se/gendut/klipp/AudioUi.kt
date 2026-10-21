package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.AudioFxCatalog

/**
 * Audio effects on the selected audio clip (Properties): add noise reduction, voice enhancement, EQ, compressor
 * and limiter. The controls are audible while dragging (the playback effect chain changes directly); the value is
 * written to the engine when the finger lifts (one undo step).
 */
@Composable
fun AudioFxSection(state: EditorState, p: ClipProps, backend: PropsBackend) {
    val haptics = rememberHaptics()
    SectionLabel(stringResource(R.string.audioui_effects))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (fx in AudioFxCatalog.all.filter { it.addable }) {
            val has = p.afx.any { it.id == fx.id }
            SpringChip(if (has) "${fx.label} ✓" else "+ ${fx.label}", has, {
                if (!has) { haptics.confirm(); backend.addAudioFx(p.id, fx.id) }
            }, Modifier.testTag("addFx:${fx.id}"))
        }
    }
    if (p.afx.isEmpty()) {
        Text(stringResource(R.string.audioui_effects_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    }
    for (e in p.afx) key(e.index, e.id) {
        val def = AudioFxCatalog.of(e.id)
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Surface)
                .padding(vertical = 6.dp).testTag("fx:${e.id}")
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AudioFxCatalog.label(e.id), color = if (e.on) K.OnSurface else K.OnSurfaceVar, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SpringChip(stringResource(if (e.on) R.string.audioui_on else R.string.audioui_off), e.on, { haptics.tick(); backend.toggleAudioFx(p.id, e.index) }, Modifier.testTag("fxOn:${e.id}"), selectedColor = K.Primary, selectedText = K.OnPrimary)
                SpringChip(stringResource(R.string.audioui_remove), false, { haptics.heavy(); backend.removeAudioFx(p.id, e.index) }, Modifier.testTag("fxDel:${e.id}"))
            }
            if (def == null) {
                Text(stringResource(R.string.audioui_not_controlled), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
            } else {
                def.hint.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
                val live: (String, Double) -> Unit = { k, nv -> state.onFxLive?.invoke(p.id, e.index, k, nv) }
                val commit: (Map<String, Double>) -> Unit = { m -> backend.setAudioFxParams(p.id, e.index, m) }
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
                                state.onFxLive?.invoke(p.id, e.index, prm.id, nv)
                                backend.setAudioFxParam(p.id, e.index, prm.id, nv)
                            }, Modifier.testTag("fxP:${e.id}:${prm.id}"))
                        }
                    } else {
                        PropSlider(prm.label, v, prm.min, prm.max, prm.default, prm.format, "fx_${e.id}_${prm.id}",
                            onLive = { nv -> state.onFxLive?.invoke(p.id, e.index, prm.id, nv.toDouble()) },
                            lane = false,
                        ) { nv -> backend.setAudioFxParam(p.id, e.index, prm.id, nv.toDouble()) }
                    }
                }
            }
        }
    }
}
