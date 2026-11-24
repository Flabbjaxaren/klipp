package se.gendut.klipp

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.McrawStore
import se.gendut.klipp.engine.OpenDrt

/**
 * The RAW settings of a MotionCam clip, on the Color page (above the nodes, as the CRAW development). The clip on the
 * timeline is a develop of its .mcraw (see [McrawStore]); the EI it was developed at and its output (log curve and
 * gamut) can be changed at any time: the .mcraw is developed again in the background and the clip switches to the new
 * develop when it is done (same length, the grade stays), with its OpenDRT and film nodes reading the new log.
 */
@Composable
internal fun McrawRawButton(gs: GradeState, clips: List<ColorClip>, current: ColorClip) {
    val ctx = LocalContext.current
    val info by produceState<McrawStore.Info?>(null, current.uri, McrawStore.revision) {
        value = withContext(Dispatchers.IO) { McrawStore.infoOf(ctx, current.uri) }
    }
    val i = info ?: return
    var open by remember(i) { mutableStateOf(false) }
    var msg by remember(current.uri) { mutableStateOf<String?>(null) }
    val working = McrawStore.working
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(stringResource(R.string.mcraw_settings), { open = true }, Modifier.testTag("raw:open"), filled = false, enabled = working == null)
        (working ?: msg)?.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f).padding(start = 10.dp)) }
    }
    if (!open) return
    // 0 = Auto
    var ei by remember(i) { mutableIntStateOf(if (i.auto) 0 else i.ei) }
    var out by remember(i) { mutableStateOf(i.out) }
    val changed = (if (ei == 0) !i.auto else ei != i.ei || i.auto) || out != i.out
    val autoWas = stringResource(R.string.mcraw_auto_was, i.ei)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.mcraw_settings)) },
        text = {
            Column {
                Text(stringResource(R.string.mcraw_settings_info, i.name, if (i.auto) autoWas else "${i.ei}", OpenDrt.Input.of(i.out).label),
                    color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                Text(stringResource(R.string.mcraw_develop_at_ei), color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in McrawFolder.EIS) ToggleChip(if (v == 0 && i.auto) autoWas else McrawFolder.eiLabel(v), ei == v, { ei = v }, Modifier.testTag("raw:ei$v"))
                }
                DropField(stringResource(R.string.mcraw_output), McrawFolder.OUTS.map { Opt(it.key, it.label, key = it.key) }, out, "raw:out") { out = it }
            }
        },
        confirmButton = {
            PillButton(stringResource(R.string.mcraw_develop), {
                open = false
                val old = current.uri ?: return@PillButton
                val ids = clips.filter { it.uri == old }.map { it.id }.ifEmpty { listOf(current.id) }
                val newEi = ei; val newOut = out
                msg = null
                McrawStore.redevelop(ctx, old, i, newEi, newOut) { uri, summary ->
                    if (uri == null) { msg = Str.t(R.string.mcraw_not_developed, i.name, summary.lineSequence().firstOrNull().orEmpty()); return@redevelop }
                    msg = Str.t(R.string.mcraw_developed, McrawFolder.eiLabel(newEi), OpenDrt.Input.of(newOut).label)
                    // the clips' OpenDRT and film nodes read the develop's new log (no undo step: the relink has none)
                    if (newOut != i.out) gs.setAll(ids.associateWith { followDevelop(gs.of(it), i.out, newOut) }, record = false)
                }
            }, Modifier.testTag("raw:develop"), enabled = changed && working == null)
        },
        dismissButton = { PillButton(stringResource(android.R.string.cancel), { open = false }, filled = false) },
    )
}

/** A clip's grade with the OpenDRT and film nodes that read the develop's log [from] reading [to] instead. */
internal fun followDevelop(g: ClipGrade, from: String, to: String): ClipGrade = g.copy(nodes = g.nodes.map { n ->
    var m = n
    n.drt?.takeIf { it.input == from }?.let { m = m.copy(drt = it.copy(input = to)) }
    n.film?.takeIf { it.input == from }?.let { m = m.copy(film = it.copy(input = to)) }
    m
})
