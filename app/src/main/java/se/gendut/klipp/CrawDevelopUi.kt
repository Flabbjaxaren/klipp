package se.gendut.klipp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.gendut.klipp.engine.CrawSettings
import se.gendut.klipp.engine.SequenceSnap

@Composable
internal fun CrawDevelopButton(settings: CrawSettings, busy: Boolean, develop: (CrawSettings) -> Unit) {
    var open by remember(settings) { mutableStateOf(false) }
    PillButton(
        stringResource(R.string.craw_develop_settings), { open = true },
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp), filled = false, enabled = !busy,
    )
    if (open) CrawDevelopDialog(settings, importing = false, onClose = { open = false }, develop)
}

@Composable
internal fun CrawDevelopDialog(settings: CrawSettings, importing: Boolean, onClose: () -> Unit, develop: (CrawSettings) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.craw_develop_settings)) },
        text = {
            Column {
                DropField(stringResource(R.string.craw_develop_log), CrawSettings.LOGS.map { Opt(it, it.label) },
                    draft.log, "crawLog") { draft = draft.copy(log = it) }
                val auto = stringResource(R.string.craw_develop_auto)
                DropField(stringResource(R.string.craw_develop_ei),
                    CrawSettings.EXPOSURE_INDICES.map { Opt(it, if (it == 0) auto else it.toString()) },
                    draft.ei, "crawEi") { draft = draft.copy(ei = it) }
            }
        },
        confirmButton = {
            PillButton(stringResource(if (importing) R.string.craw_develop_import else R.string.craw_develop_again), {
                onClose()
                develop(draft)
            }, enabled = importing || draft != settings)
        },
        dismissButton = {
            PillButton(stringResource(android.R.string.cancel), onClose, filled = false)
        },
    )
}

internal fun updateCrawGrades(gs: GradeState, sequence: SequenceSnap?, item: Long, before: CrawSettings, after: CrawSettings) {
    if (before.log == after.log) return
    val changes = sequence?.video.orEmpty().flatMap { it.clips }.filter { it.item == item }.associate { clip ->
        val grade = gs.of(clip.id)
        clip.id to grade.copy(nodes = grade.nodes.map { node ->
            val transform = node.drt
            if (transform?.input == before.log.key) node.copy(drt = transform.copy(input = after.log.key)) else node
        })
    }
    gs.setAll(changes, record = false)
}
