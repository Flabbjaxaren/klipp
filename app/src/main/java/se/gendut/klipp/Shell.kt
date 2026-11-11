package se.gendut.klipp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import se.gendut.klipp.engine.RenderCache
import se.gendut.klipp.engine.SpeedRamp
import se.gendut.klipp.engine.TransitionLooks
import se.gendut.klipp.engine.TransitionType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

// The shell around the pages: the top bar with the workspaces, the transport under the picture, the timeline's own row
// of tools, and at the bottom undo/redo next to the dock – the dock holds what can be done with what is selected (or,
// with nothing selected, what can be added), and on the sound and text pages their tabs.

/** A square-ish button of the shell (44 dp): an icon on a container. */
@Composable
internal fun ShellButton(
    icon: ImageVector, desc: String, radius: Dp, bg: Color, modifier: Modifier = Modifier, tint: Color = K.OnSurface, onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    Box(
        modifier.size(44.dp).pressPop(src, 0.86f).clip(RoundedCornerShape(radius)).background(bg)
            .clickable(interactionSource = src, indication = null) { haptics.press(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** The top bar: the projects, the workspaces and export. */
@Composable
internal fun TopBar(pageOf: () -> Float, onPick: (Int) -> Unit, onProjects: () -> Unit, onExport: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ShellButton(Ic.Folder, stringResource(R.string.app_projects), 22.dp, K.SurfaceHigh, Modifier.testTag("projects:open"), onClick = onProjects)
        Workspaces(pageOf, Modifier.weight(1f), onPick)
        ShellButton(Ic.Export, stringResource(R.string.app_export), 14.dp, K.SurfaceHighest, Modifier.testTag("export"), onClick = onExport)
    }
}

/**
 * The workspaces (edit, color, sound, text) as a connected group: the current one is a pill with its name, the others
 * icons. Dragging sideways along the row steps the workspace: one on each time the finger passes into the next one's
 * place (a quarter of the row each), not following it smoothly; a quick flick that never got that far still steps one.
 */
@Composable
private fun Workspaces(pageOf: () -> Float, modifier: Modifier, onPick: (Int) -> Unit) {
    // read here (not by the caller): during a swipe only this row recomposes, not the whole screen
    val pageNow by rememberUpdatedState(pageOf)
    val current by remember { derivedStateOf { pageNow().roundToInt().coerceIn(0, MODES.lastIndex) } }
    val desc = stringResource(R.string.design_workspaces)
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val itemPx = constraints.maxWidth / MODES.size.toFloat()
        val compact = maxWidth < 280.dp
        val dragging = Modifier.pointerInput(itemPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // tracked from the touch down, so a short flick (mostly within the slop) still has its speed
                val vt = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                val from = pageNow().roundToInt()
                var at = from
                var p = from.toFloat()
                fun follow(c: PointerInputChange, dx: Float) {
                    c.consume()
                    p += dx / itemPx
                    val step = p.roundToInt().coerceIn(0, MODES.lastIndex)
                    if (step != at) { at = step; onPick(step) }
                }
                // past the slop sideways it is a drag (a tap is left to the button's own click); every move counts for the speed
                var dx = 0f
                var dy = 0f
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!c.pressed || c.isConsumed) return@awaitEachGesture
                    vt.addPosition(c.uptimeMillis, c.position)
                    dx += c.positionChange().x
                    dy += c.positionChange().y
                    if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) return@awaitEachGesture
                    if (abs(dx) > viewConfiguration.touchSlop) { follow(c, dx - sign(dx) * viewConfiguration.touchSlop); break }
                }
                if (!horizontalDrag(down.id) { vt.addPosition(it.uptimeMillis, it.position); follow(it, it.positionChange().x) }) return@awaitEachGesture
                val v = vt.calculateVelocity().x / itemPx
                if (at == from && abs(v) > 1.5f) onPick(from + sign(v).toInt())
            }
        }
        Row(
            Modifier.fillMaxSize().then(dragging).semantics { contentDescription = desc }.testTag("modes"),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MODES.forEachIndexed { i, nameId ->
                Workspace(stringResource(nameId), MODE_ICONS[i], i == current, i == 0, i == MODES.lastIndex, compact, "modeTab:$i") { onPick(i) }
            }
        }
    }
}

@Composable
private fun Workspace(name: String, icon: ImageVector, selected: Boolean, first: Boolean, last: Boolean, compact: Boolean, tag: String, onClick: () -> Unit) {
    val spec = spring<Dp>(0.6f, 500f)
    val l by animateDpAsState(if (selected || first) 22.dp else 8.dp, spec, label = "l")
    val r by animateDpAsState(if (selected || last) 22.dp else 8.dp, spec, label = "r")
    val bg by animateColorAsState(if (selected) K.PrimaryContainer else K.SurfaceHigh, label = "bg")
    val fg by animateColorAsState(if (selected) K.PrimarySoft else K.OnSurfaceVar, label = "fg")
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.pressPop(src, 0.9f).height(44.dp).defaultMinSize(minWidth = if (compact) 44.dp else 48.dp)
            .clip(RoundedCornerShape(topStart = l, bottomStart = l, topEnd = r, bottomEnd = r)).background(bg)
            .clickable(interactionSource = src, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(tag)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, name, tint = fg, modifier = Modifier.size(20.dp))
        AnimatedVisibility(
            selected,
            enter = expandHorizontally(spring(0.8f, 500f)) + fadeIn(),
            exit = shrinkHorizontally(spring(1f, 600f)) + fadeOut(),
        ) {
            Text(name, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
        }
    }
}

/** Under the picture: frame steps on the left, play between the previous and next edit in the middle, the time on the right. */
@Composable
internal fun Transport(state: EditorState) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val playR by animateDpAsState(if (state.playing) 24.dp else 18.dp, spring(0.45f, 500f), label = "pr")
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Ic.Prev, stringResource(R.string.app_prev_frame), tint = K.OnSurfaceVar, modifier = Modifier.size(44.dp)) {
                state.playing = false; state.playhead = snapFrame((state.playhead - FRAME).coerceAtLeast(0f))
            }
            RoundIcon(Ic.Next, stringResource(R.string.app_next_frame), tint = K.OnSurfaceVar, modifier = Modifier.size(44.dp)) {
                state.playing = false; state.playhead = snapFrame(state.playhead + FRAME)
            }
        }
        // the edits: where clips start and end, and the markers
        fun edits() = state.clips.flatMap { listOf(it.start, it.end) } + state.markers
        RoundIcon(Ic.PrevEdit, stringResource(R.string.design_prev_edit), modifier = Modifier.size(44.dp), haptic = false) {
            state.playing = false
            val prev = edits().filter { it < state.playhead - 0.01f }.maxOrNull() ?: 0f
            if (state.playhead > 0.01f) { haptics.press(); state.playhead = prev } else haptics.heavy()
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.pressPop(src, 0.86f)
                .size(64.dp, 48.dp).clip(RoundedCornerShape(playR)).background(K.Primary)
                .clickable(interactionSource = src, indication = null) {
                    haptics.press()
                    if (!state.playing && state.playhead >= state.projectEnd - 0.05f) state.playhead = 0f
                    state.playing = !state.playing
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                state.playing,
                transitionSpec = { (scaleIn(spring(0.4f, 600f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                label = "playicon",
            ) { playing ->
                Icon(if (playing) Ic.Pause else Ic.Play, stringResource(if (playing) R.string.app_pause else R.string.app_play), tint = K.OnPrimary, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        RoundIcon(Ic.NextEdit, stringResource(R.string.app_next_edit), modifier = Modifier.size(44.dp), haptic = false) {
            state.playing = false
            val next = edits().filter { it > state.playhead + 0.01f }.minOrNull()
            if (next != null) { haptics.press(); state.playhead = next } else haptics.heavy()
        }
        // tap the time = type a time to go to (digits, or +/- to move)
        var tcOpen by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { haptics.press(); state.playing = false; tcOpen = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp).testTag("timecode"),
                horizontalAlignment = Alignment.End,
            ) {
                // (on a narrow phone the numbers shrink to the room they have – never cut)
                val tabular = LocalTextStyle.current.merge(TextStyle(fontFeatureSettings = "tnum"))
                BasicText(
                    formatTc(state.playhead), color = { K.OnSurface }, maxLines = 1,
                    style = tabular.merge(TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)),
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
                )
                BasicText(
                    stringResource(R.string.design_of_total, formatTc(state.projectEnd)), color = { K.OnSurfaceVar }, maxLines = 1,
                    style = tabular.merge(TextStyle(fontSize = 11.sp)),
                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
                )
            }
        }
        // (a typed time past the end goes to the end)
        if (tcOpen) TimecodeDialog(state.playhead, onDismiss = { tcOpen = false }) { t ->
            tcOpen = false; haptics.confirm(); state.playhead = snapFrame(t.coerceIn(0f, state.projectEnd))
        }
    }
}

/** A toggle in the timeline's row of tools: the accent's container when on. */
@Composable
private fun HeaderToggle(icon: ImageVector, desc: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val bg by animateColorAsState(if (on) K.PrimaryContainer else K.SurfaceHigh, label = "tb")
    val rot = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch) {
                haptics.press()
                scope.launch { rot.snapTo(-25f); rot.animateTo(0f, Springs.pop()) }
                onClick()
            }
            .semantics { selected = on }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (on) K.PrimarySoft else K.OnSurfaceVar, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rot.value }) }
}

/**
 * The edit page: the timeline on its own surface with a row of tools on top (the tool, snapping, linked selection, a
 * marker, and the rest in a menu). The actions for what is selected are in the dock at the bottom of the screen.
 */
@Composable
internal fun EditPage(
    state: EditorState, tool: Tool, setTool: (Tool) -> Unit, toaster: Toaster, tips: Boolean = false,
    /** The transition and edit-point cards and the render chip over the timeline; off while the source monitor is open (the timeline is short then and they covered its ruler). */
    overlays: Boolean = true,
    /** The project's settings (null without a project) and the app's, in the menu. */
    onSettings: (() -> Unit)? = null,
    onAppSettings: () -> Unit = {},
) {
    // the cards sit above the dock (portrait); in landscape the dock is elsewhere
    val bar = LocalBarSpace.current
    val barPad = if (bar > 0.dp) bar + 8.dp else 12.dp
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(K.Surface)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tools = listOf(
                Tool.SELECT to (stringResource(R.string.app_tool_move) to Ic.Select), Tool.RIPPLE to (stringResource(R.string.app_tool_ripple) to Ic.Ripple),
                Tool.TRIM to (stringResource(R.string.app_tool_trim) to Ic.Trim), Tool.RAZOR to (stringResource(R.string.app_tool_razor) to Ic.Razor),
            )
            // the tool: one chip with a menu
            var toolsOpen by remember { mutableStateOf(false) }
            Box {
                val (label, icon) = tools.first { it.first == tool }.second
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh)
                        .clickable { haptics.press(); toolsOpen = true }.padding(start = 12.dp, end = 8.dp).testTag("tool:menu"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = K.OnSurface, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Ic.ChevronDown, stringResource(R.string.design_tool), tint = K.OnSurfaceVar, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                }
                DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }, shape = RoundedCornerShape(20.dp), containerColor = K.SurfaceHighest) {
                    for ((t, li) in tools) DropdownMenuItem(
                        text = { Text(li.first, color = if (t == tool) K.PrimarySoft else K.OnSurface, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(li.second, null, tint = if (t == tool) K.PrimarySoft else K.OnSurface, modifier = Modifier.size(20.dp)) },
                        onClick = { toolsOpen = false; haptics.press(); setTool(t) },
                        modifier = Modifier.testTag("tool:${t.name}"),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            HeaderToggle(Ic.Magnet, stringResource(R.string.app_magnetic_snapping), state.magnet, "mode:magnet") {
                state.magnet = !state.magnet
                toaster.show(Str.t(if (state.magnet) R.string.app_snapping_on else R.string.app_snapping_off))
            }
            HeaderToggle(Ic.Link, stringResource(R.string.app_linked_selection), state.linkedSelection, "mode:link") {
                val on = !state.linkedSelection
                state.linkedSelection = on
                state.onLinkedSelection?.invoke(on)
                toaster.show(Str.t(if (on) R.string.app_linked_on else R.string.app_linked_off))
            }
            // the marker button: lit while the playhead stands on a marker, and then it takes that marker away
            val onMarker by remember(state) { derivedStateOf { state.markers.firstOrNull { abs(it - state.playhead) < FRAME / 2f } } }
            val markerBg by animateColorAsState(if (onMarker != null) K.PrimaryContainer else K.SurfaceHigh, label = "mk")
            ShellButton(
                Ic.Marker, stringResource(if (onMarker != null) R.string.app_remove_marker else R.string.app_add_marker), 14.dp, markerBg,
                Modifier.testTag("marker:add"), tint = if (onMarker != null) K.PrimarySoft else K.OnSurfaceVar,
            ) {
                val at = onMarker
                if (at != null) {
                    val cb = state.onRemoveMarker
                    if (cb != null) cb(at) else state.markers = state.markers - at
                } else {
                    val cb = state.onAddMarker
                    if (cb != null) cb(state.playhead) else state.markers = (state.markers + state.playhead).sorted()
                }
            }
            var modesOpen by remember { mutableStateOf(false) }
            Box {
                ShellButton(Ic.More, stringResource(R.string.design_timeline_modes), 14.dp, K.SurfaceHigh, Modifier.testTag("modes:menu"), tint = K.OnSurfaceVar) { modesOpen = true }
                DropdownMenu(expanded = modesOpen, onDismissRequest = { modesOpen = false }, shape = RoundedCornerShape(20.dp), containerColor = K.SurfaceHighest) {
                    @Composable fun mode(icon: ImageVector, label: String, on: Boolean, tag: String, flip: () -> Unit) =
                        DropdownMenuItem(
                            text = { Text(label, color = K.OnSurface, fontWeight = FontWeight.Medium) },
                            leadingIcon = { Icon(icon, null, tint = if (on) K.PrimarySoft else K.OnSurfaceVar, modifier = Modifier.size(20.dp)) },
                            trailingIcon = { if (on) Icon(Ic.Check, null, tint = K.Primary, modifier = Modifier.size(18.dp)) },
                            onClick = { haptics.press(); flip() },
                            modifier = Modifier.testTag(tag),
                        )
                    mode(Ic.Center, stringResource(R.string.app_locked_playhead), state.centerPlayhead, "mode:center") { state.centerPlayhead = !state.centerPlayhead }
                    mode(Ic.VolLine, stringResource(R.string.app_volume_lines), state.volLines, "mode:vol") { state.volLines = !state.volLines }
                    val anyMarkers = state.markers.isNotEmpty()
                    if (anyMarkers || onSettings != null) HorizontalDivider(color = K.OutlineVar, modifier = Modifier.padding(vertical = 4.dp))
                    if (anyMarkers) DropdownMenuItem(
                        text = { Text(stringResource(R.string.app_remove_all_markers), color = K.OnSurface, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Ic.Marker, null, tint = K.OnSurfaceVar, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            modesOpen = false; haptics.press()
                            val cb = state.onClearMarkers
                            if (cb != null) cb() else state.markers = emptyList()
                        },
                        modifier = Modifier.testTag("markers:clear"),
                    )
                    if (onSettings != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.design_project_settings), color = K.OnSurface, fontWeight = FontWeight.Medium) },
                            leadingIcon = { Icon(Ic.Sliders, null, tint = K.OnSurfaceVar, modifier = Modifier.size(20.dp)) },
                            onClick = { modesOpen = false; haptics.press(); onSettings() },
                            modifier = Modifier.testTag("projectTitle"),
                        )
                    }
                }
            }
            ShellButton(Ic.Gear, stringResource(R.string.app_app_settings), 14.dp, K.SurfaceHigh, Modifier.testTag("appSettings"), tint = K.OnSurfaceVar) { onAppSettings() }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Timeline(state, tool, Modifier.fillMaxSize())
            if (overlays) {
                // (the chip first: a card that reaches up to it lies over it, not under)
                RenderChip(Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 10.dp))
                TransitionCard(state, Modifier.align(Alignment.BottomCenter).padding(bottom = barPad, start = 12.dp, end = 12.dp))
                CutBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = barPad, start = 12.dp, end = 12.dp))
            }
            // first-run tips about the gestures, only when nothing is selected
            val nothingSelected = state.selected == null && !state.groupSelected && state.selectedTransition == null && state.selectedCut == null && state.selectedCaption == null
            TipCard(tips && nothingSelected && state.clips.isNotEmpty(), Modifier.align(Alignment.BottomCenter).padding(bottom = barPad, start = 12.dp, end = 12.dp))
            // Properties slide up over the timeline; the picture above stays visible
            androidx.compose.animation.AnimatedVisibility(
                visible = state.inspecting && state.selected != null,
                enter = slideInVertically(spring(0.75f, 420f)) { it } + fadeIn(),
                exit = slideOutVertically(spring(1f, 500f)) { it } + fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                // (the sheet takes the touches on its empty parts too: the timeline lies under it)
                Box(Modifier.fillMaxSize().background(K.Surface).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).pointerInput(Unit) {}) {
                    PropertiesPanel(state) { state.inspecting = false }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = state.editingCaption && state.selectedCaption != null,
                enter = slideInVertically(spring(0.75f, 420f)) { it } + fadeIn(),
                exit = slideOutVertically(spring(1f, 500f)) { it } + fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().background(K.Surface).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).pointerInput(Unit) {}) {
                    CaptionInspector(state)
                }
            }
        }
    }
}

/**
 * The height at the bottom of a page that the floating bar (undo/redo and the dock) lies over; 0 where the bar is
 * elsewhere (landscape) or the page is shown on its own. What a page must keep clear of the bar ends this far up.
 */
val LocalBarSpace = compositionLocalOf { 0.dp }

/**
 * Undo and redo at the bottom (within the thumb's reach), next to the dock. The pill is narrow (72 dp) so the dock has
 * the width for six actions on a narrow phone; each half is pressed over the pill's whole height.
 */
@Composable
internal fun UndoRedo(state: EditorState, undo: () -> Unit, redo: () -> Unit) {
    Row(
        Modifier.height(72.dp).shadow(10.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(K.SurfaceHighest).testTag("undoRedo"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(Ic.Undo, stringResource(R.string.app_undo), enabled = state.undoCount > 0, modifier = Modifier.size(36.dp, 72.dp).testTag("undo"), haptic = false, onClick = undo)
        RoundIcon(Ic.Redo, stringResource(R.string.app_redo), enabled = state.redoCount > 0, modifier = Modifier.size(36.dp, 72.dp).testTag("redo"), haptic = false, onClick = redo)
    }
}

/**
 * One thing the dock offers. [primary] = an "on" state (the tab that is open, a label that is set): the accent's
 * container behind the icon. Nothing is marked only for being the main action. [strong] = the long press's
 * heavier feel (delete); every other press gives the key click. [desc] = the whole name, where the label is a short
 * form of it (for a screen reader).
 */
internal class DockItem(
    val icon: ImageVector, val label: String, val tag: String? = null, val primary: Boolean = false,
    val tint: Color? = null, val marked: Boolean = false, val strong: Boolean = false, val desc: String? = null, val onClick: () -> Unit,
)

@Composable
internal fun DockAction(item: DockItem, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (item.primary) K.PrimaryContainer else K.PrimaryContainer.copy(alpha = 0f), label = "db")
    val fg = item.tint ?: if (item.primary) K.PrimarySoft else K.OnSurface
    Column(
        modifier.height(56.dp).pressPop(src, 0.88f)
            .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier)
            .semantics { selected = item.primary; item.desc?.let { contentDescription = it } }
            .clickable(interactionSource = src, indication = null) { if (item.strong) haptics.heavy() else haptics.press(); item.onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // the "on" state is a pill around the icon only (as in a navigation bar): it stays clear of the dock's edges
        Box(
            Modifier.padding(horizontal = 3.dp).widthIn(max = 52.dp).fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Box {
                Icon(item.icon, null, tint = fg, modifier = Modifier.size(22.dp))
                if (item.marked) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(7.dp).clip(CircleShape).background(K.Primary))
            }
        }
        Spacer(Modifier.height(3.dp))
        // a long name shrinks to its place on a narrow phone instead of running into the next one
        BasicText(
            item.label, color = { item.tint ?: K.OnSurface }, maxLines = 1,
            style = IconLabel.merge(TextStyle(fontSize = 11.sp, fontWeight = if (item.primary) FontWeight.Bold else FontWeight.SemiBold, textAlign = TextAlign.Center)),
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
            modifier = Modifier.padding(horizontal = 1.dp),
        )
    }
}

/**
 * A row of the dock: at most six actions, which share the width – the dock never scrolls. (Six get less air around
 * them, so each stays wide enough to press on a narrow phone.)
 */
@Composable
internal fun DockItems(items: List<DockItem>, modifier: Modifier = Modifier) {
    val tight = items.size > 5
    Row(
        modifier.fillMaxSize().padding(horizontal = if (tight) 6.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tight) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) DockAction(item, Modifier.weight(1f))
    }
}

/**
 * A row of the dock with other things than actions (the steps of a value, the labels): Back stays where it is and the
 * rest scrolls sideways beside it, fading at the edge it continues past.
 */
@Composable
private fun DockScroll(onBack: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxSize().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Ic.Back, stringResource(R.string.app_back), modifier = Modifier.size(44.dp, 56.dp)) { onBack() }
        Row(
            Modifier.weight(1f).fillMaxHeight().sideScroll(scroll).padding(start = 2.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, content = content,
        )
    }
}

/** A step of a value in the dock (a speed, a level, a length). [tight] = the steps share a row's width: less air beside the text. */
@Composable
private fun DockStep(label: String, on: Boolean, tag: String? = null, modifier: Modifier = Modifier, tight: Boolean = false, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        modifier.padding(horizontal = 2.dp).height(44.dp).clip(RoundedCornerShape(if (on) 22.dp else 12.dp))
            .background(if (on) K.PrimaryContainer else K.SurfaceHigh)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { haptics.press(); onClick() }
            .padding(horizontal = if (tight) 4.dp else 12.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) K.PrimarySoft else K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}

private enum class DockKind { NONE, ADD, CLIP, MULTI, CAPTION, TRANSITION, AUDIO, TEXT }

/**
 * The dock: on the edit page the actions for what is selected (a clip, several, a caption, a transition) or, with
 * nothing selected, the things to add; on the sound and text pages their tabs. Hidden on the color page (its tools are
 * in the page) and while a sheet is open over the timeline.
 */
@Composable
internal fun Dock(state: EditorState, pageOf: () -> Float, audioTabs: List<ToolTab>, modifier: Modifier = Modifier) {
    val pageNow by rememberUpdatedState(pageOf)
    val page by remember { derivedStateOf { pageNow().roundToInt() } }
    val kind = when {
        page == 1 -> DockKind.NONE
        page == 2 -> DockKind.AUDIO
        page == 3 -> if (state.props != null) DockKind.TEXT else DockKind.NONE
        state.inspecting && state.selected != null -> DockKind.NONE
        state.editingCaption && state.selectedCaption != null -> DockKind.NONE
        state.selected != null -> DockKind.CLIP
        state.groupSelected -> DockKind.MULTI
        state.selectedCaption != null && state.props != null && state.captions.any { it.id == state.selectedCaption } -> DockKind.CAPTION
        state.selectedTransition != null && state.props != null && state.transitions.any { it.id == state.selectedTransition } -> DockKind.TRANSITION
        state.onAddAction != null -> DockKind.ADD
        else -> DockKind.NONE
    }
    AnimatedContent(
        kind, modifier,
        transitionSpec = { (fadeIn() + scaleIn(spring(0.6f, 500f), initialScale = 0.94f)) togetherWith fadeOut() },
        label = "dock",
    ) { k ->
        if (k == DockKind.NONE) Spacer(Modifier.fillMaxWidth().height(72.dp))
        else Box(
            Modifier.fillMaxWidth().height(72.dp).shadow(10.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(K.SurfaceHighest).testTag("dock"),
            contentAlignment = Alignment.Center,
        ) {
            when (k) {
                DockKind.ADD -> AddDock(state)
                DockKind.CLIP -> ClipDock(state)
                DockKind.MULTI -> MultiDock(state)
                DockKind.CAPTION -> CaptionDock(state)
                DockKind.TRANSITION -> TransitionDock(state)
                DockKind.AUDIO -> DockItems(audioTabs.mapIndexed { i, t ->
                    DockItem(t.icon, t.label, t.tag, primary = i == state.audioTab.coerceIn(0, audioTabs.lastIndex), marked = t.marked) { state.audioTab = i }
                })
                DockKind.TEXT -> {
                    val tabs = listOf(
                        Ic.Text to stringResource(R.string.titleui_tab_titles), Ic.Subs to stringResource(R.string.titleui_captions),
                        Ic.Transcript to stringResource(R.string.transcript_title),
                    )
                    DockItems(tabs.mapIndexed { i, (icon, label) -> DockItem(icon, label, "textTab:$i", primary = i == state.textTab) { state.textTab = i } })
                }
                DockKind.NONE -> {}
            }
        }
    }
}

/**
 * Nothing selected: add media, sound, a title, captions or a voice-over. Each opens its own sheet over the edit page
 * (the timeline stays where it is), and what is made lands on the timeline at the playhead.
 */
@Composable
private fun AddDock(state: EditorState) {
    val add = state.onAddAction ?: return
    DockItems(
        listOf(
            DockItem(Ic.Media, stringResource(R.string.design_add_media), "add:media") { add(AddAction.MEDIA) },
            DockItem(Ic.Audio, stringResource(R.string.design_add_sound), "add:sound") { add(AddAction.SOUND) },
            DockItem(Ic.Text, stringResource(R.string.design_add_title), "add:title") { add(AddAction.TITLE) },
            DockItem(Ic.Subs, stringResource(R.string.design_add_captions), "add:captions") { add(AddAction.CAPTIONS) },
            DockItem(Ic.Mic, stringResource(R.string.design_add_voice), "add:voice") { add(AddAction.VOICE) },
        ),
        Modifier.testTag("addBar"),
    )
}

/** A selected clip: the actions follow what it is (video, sound or a title); some open a row of steps or the rest behind More. */
@Composable
private fun ClipDock(state: EditorState) {
    val haptics = rememberHaptics()
    var speedMode by remember { mutableStateOf(false) }
    var freezeMode by remember { mutableStateOf(false) }
    var volumeMode by remember { mutableStateOf(false) }
    var labelMode by remember { mutableStateOf(false) }
    var moreMode by remember { mutableStateOf(false) }
    LaunchedEffect(state.selected) { speedMode = false; freezeMode = false; volumeMode = false; labelMode = false; moreMode = false }
    val sel = state.clips.firstOrNull { it.id == state.selected }
    val isSound = sel?.kind == ClipKind.AUDIO || sel?.kind == ClipKind.MUSIC
    val isTitle = sel?.kind == ClipKind.TITLE
    val back = stringResource(R.string.app_back)
    // the label and on/off: behind More, or in the row itself where there is room (a title)
    val labelItem = DockItem(Ic.Tag, stringResource(R.string.app_label), "ctx:label", primary = sel?.label != null) { moreMode = false; labelMode = true }
    val powerItem = DockItem(Ic.Power, stringResource(if (sel?.enabled == false) R.string.app_enable else R.string.app_disable), "ctx:power") {
        state.selected?.let { state.toggleEnabled(listOf(it)) }
    }
    AnimatedContent(
        listOf(speedMode, freezeMode, volumeMode, labelMode, moreMode),
        transitionSpec = { (fadeIn() + scaleIn(spring(0.6f, 500f), initialScale = 0.94f)) togetherWith fadeOut() }, label = "ctx",
    ) { (speed, freeze, volume, label, more) ->
        if (speed) DockScroll(onBack = { speedMode = false }) {
            val cur = sel?.speed ?: 1f
            for (p in listOf(25, 50, 100, 150, 200, 400)) {
                // (the speed the clip has is never out of sight)
                val on = abs(cur * 100 - p) < 0.5f
                DockStep("$p%", on, modifier = Modifier.inViewWhen(on)) { state.selected?.let { state.setSpeed(it, p) } }
            }
            // ramp: the speed changes smoothly along the clip
            state.onSpeedRamp?.let { ramp ->
                var open by remember { mutableStateOf(false) }
                Box {
                    DockStep(stringResource(R.string.app_ramp), false, "speed:ramp") { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp), containerColor = K.SurfaceHighest) {
                        for (r in SpeedRamp.entries) {
                            DropdownMenuItem(
                                text = { Text(r.label, color = K.OnSurface, fontWeight = FontWeight.Medium) },
                                onClick = { open = false; state.selected?.let { haptics.confirm(); ramp(it, r); speedMode = false } },
                                modifier = Modifier.testTag("ramp:${r.name}"),
                            )
                        }
                    }
                }
            }
        } else if (volume) Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // the clip's level, set freely: heard while dragging, saved on release (keyframes are in the clip's sheet).
            // The slider lies in a well of the page's surface: its track has the dock's own color
            RoundIcon(Ic.Back, back, modifier = Modifier.size(44.dp, 56.dp)) { volumeMode = false }
            val p = state.selected?.let { id -> state.props?.props(id) }
            if (p != null) Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(24.dp)).background(K.Surface).padding(top = 2.dp).testTag("dockVolume")) {
                val scope = rememberCoroutineScope()
                CompositionLocalProvider(LocalSliderInset provides 14.dp) {
                    PropSlider(
                        stringResource(R.string.app_volume), p.levelDb, -40f, 15f, 0f, { "%+.1f dB".format(it).replace('-', '−') }, "level",
                        onLive = { v -> state.setLive(p.copy(levelDb = v)) },
                    ) { state.props?.setProps(p.id, levelDb = it); scope.launch { state.endLive() } }
                }
            }
        } else if (label) DockScroll(onBack = { labelMode = false }) {
            LabelPicker(sel?.label) { l -> state.selected?.let { haptics.confirm(); state.setLabel(listOf(it), l); labelMode = false } }
        } else if (freeze) Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // freeze frame at the playhead: choose how long the frame is held (the four lengths share the row)
            RoundIcon(Ic.Back, back, modifier = Modifier.size(44.dp, 56.dp)) { freezeMode = false }
            Text(stringResource(R.string.app_freeze), color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
            for (sec in listOf(1f, 2f, 3f, 5f)) {
                DockStep(stringResource(R.string.app_seconds, sec.toInt()), false, "freeze:${sec.toInt()}", Modifier.weight(1f), tight = true) {
                    state.selected?.let { state.freezeFrame(it, state.playhead, sec); freezeMode = false }
                }
            }
        } else if (more) {
            // the rest of the clip's actions, behind More
            val items = buildList {
                add(DockItem(Ic.Back, back) { moreMode = false })
                // (a video clip has Copy in its row)
                if (isSound) state.onCopyToSource?.let { copy ->
                    add(DockItem(Ic.Copy, stringResource(R.string.app_copy)) { state.selected?.let { copy(it) } })
                }
                if (sel?.kind == ClipKind.VIDEO) add(DockItem(Ic.Freeze, stringResource(R.string.app_freeze)) { moreMode = false; freezeMode = true })
                sel?.takeIf { (it.kind == ClipKind.VIDEO || it.kind == ClipKind.AUDIO) && !it.hold }?.let { c ->
                    add(DockItem(Ic.Reverse, stringResource(if (c.reverse) R.string.app_forward else R.string.app_reverse)) { state.toggleReverse(c.id) })
                }
                if (!isTitle) state.onBeatMarkers?.let { beats -> add(DockItem(Ic.Bolt, stringResource(R.string.beats_btn), "ctx:beats") { state.selected?.let { beats(it) } }) }
                add(labelItem)
                add(powerItem)
            }
            DockItems(items)
        } else {
            // one row per kind of clip, in the order the actions are used: delete first, then the picture, copy and the
            // rest; each group opens its sheet
            fun open(group: Int) { state.inspectGroup = group; state.inspecting = true }
            val items = buildList {
                add(DockItem(Ic.Delete, stringResource(R.string.app_delete), "ctx:delete", tint = K.Error, strong = true) { state.selected?.let { state.requestRemove(it, false) } })
                val split = DockItem(Ic.Split, stringResource(R.string.app_split), "ctx:split") { state.selected?.let { state.split(it, state.playhead) } }
                when {
                    isTitle -> {
                        // a title has few actions: they are all in its row, nothing behind More
                        if (state.props != null) add(DockItem(Ic.Text, stringResource(R.string.design_edit_short), "ctx:editText", desc = stringResource(R.string.app_edit_text)) { open(0) })
                        add(split)
                        add(labelItem)
                        add(powerItem)
                    }
                    isSound -> {
                        add(split)
                        if (state.props != null) {
                            add(DockItem(Ic.Speaker, stringResource(R.string.app_volume), "ctx:volume") { volumeMode = true })
                            add(DockItem(Ic.Audio, stringResource(R.string.design_sound_fx), "ctx:afx") { state.openTab = "propTab:afx"; open(0) })
                        }
                        add(DockItem(Ic.Speed, stringResource(R.string.app_speed), "ctx:speed") { speedMode = true })
                    }
                    else -> {
                        // the picture: position, crop, blending, keying, mask, stabilization, motion blur
                        if (state.props != null) add(DockItem(Ic.Crop, stringResource(R.string.design_picture), "ctx:picture") { open(GROUP_PICTURE) })
                        state.onCopyToSource?.let { copy -> add(DockItem(Ic.Copy, stringResource(R.string.app_copy), "ctx:copy") { state.selected?.let { copy(it) } }) }
                        add(split)
                        add(DockItem(Ic.Speed, stringResource(R.string.app_speed), "ctx:speed") { speedMode = true })
                    }
                }
                if (!isTitle) add(DockItem(Ic.More, stringResource(R.string.design_more), "ctx:more") { moreMode = true })
            }
            DockItems(items)
        }
    }
}

/**
 * Several clips selected with the lasso: delete them (leaving the gap, or closing it with Ripple), group them, label
 * them, turn them off and on, or line them up by their sound. Drag one of them to move them all.
 */
@Composable
private fun MultiDock(state: EditorState) {
    val haptics = rememberHaptics()
    var labelMode by remember { mutableStateOf(false) }
    val nClips = state.multi.size
    if (labelMode) {
        DockScroll(onBack = { labelMode = false }) {
            val labels = state.multi.map { id -> state.clips.firstOrNull { it.id == id }?.label }.toSet()
            LabelPicker(labels.singleOrNull()) { l -> haptics.confirm(); state.setLabel(state.multi, l); labelMode = false }
        }
        return
    }
    val items = buildList {
        // (delete first, as in a clip's row; six share the dock, so two have short names – the whole ones are read out)
        add(DockItem(Ic.Delete, stringResource(R.string.app_delete), "multi:delete", tint = K.Error, strong = true) { state.removeGroup(false) })
        if (nClips > 0) add(DockItem(Ic.Ripple, stringResource(R.string.design_ripple_short), "multi:ripple", strong = true, desc = stringResource(R.string.app_ripple_delete)) { state.removeGroup(true) })
        // compound clip: selected, moved and graded together
        if (state.props != null && nClips > 0) {
            val groups = state.multi.mapNotNull { id -> state.clips.firstOrNull { it.id == id }?.group }.toSet()
            val whole = groups.size == 1 && state.clips.all { it.group != groups.first() || it.id in state.multi } && state.multi.all { id -> state.clips.firstOrNull { it.id == id }?.group == groups.first() }
            if (whole) add(DockItem(Ic.Ungroup, stringResource(R.string.app_ungroup)) { state.setGrouped(state.multi, false) })
            else add(DockItem(Ic.Group, stringResource(R.string.app_group)) { state.setGrouped(state.multi, true) })
        }
        if (nClips > 0) add(DockItem(Ic.Tag, stringResource(R.string.app_label), "multi:label") { labelMode = true })
        if (nClips > 0) add(DockItem(Ic.Power, stringResource(R.string.app_toggle_enabled), "multi:power") { state.toggleEnabled(state.multi) })
        // separate sound (a recorder, another camera): lined up by the audio
        if (state.props != null && nClips > 1) add(DockItem(Ic.SyncAudio, stringResource(R.string.design_sync_short), "syncAudio", desc = stringResource(R.string.app_sync_audio)) { state.syncByAudio(state.multi) })
    }
    DockItems(items, Modifier.testTag("multiBar"))
}

/** A selected transition: remove it, pre-render it (the picture's), or done. Its type, length and blur are in the card above. */
@Composable
private fun TransitionDock(state: EditorState) {
    val tr = state.selectedTransition?.let { id -> state.transitions.firstOrNull { it.id == id } }
    val items = buildList {
        add(DockItem(Ic.Delete, stringResource(R.string.app_remove), "transition:remove", tint = K.Error, strong = true) {
            val id = state.selectedTransition ?: return@DockItem
            state.selectedTransition = null; state.props?.removeTransition(id)
        })
        // pre-render: the transition plays from a finished file (smooth, no lag); re-rendered in the background after each change
        val from = tr?.from; val to = tr?.to
        if (tr != null && from != null && to != null && tr.anchor == 0 && tr.track < 3) {
            val pinned = RenderCache.isPinned(from, to)
            val ready = pinned && RenderCache.states.filter { it.a < tr.end - 1e-3 && it.b > tr.start + 1e-3 }.let { l -> l.isNotEmpty() && l.all { it.ready } }
            val label = when { !pinned -> stringResource(R.string.app_prerender); ready -> stringResource(R.string.app_rendered); else -> stringResource(R.string.app_rendering) }
            add(DockItem(Ic.Bolt, label, "transition:render", primary = pinned) { RenderCache.setPinned(from, to, !pinned); state.props?.requestSave() })
        }
        add(DockItem(Ic.Check, stringResource(R.string.properties_done), "transition:done") { state.selectedTransition = null })
    }
    DockItems(items)
}

/** What is selected, above the dock: its name and length, and a way to let go of it. */
@Composable
internal fun SelectionChip(state: EditorState, pageOf: () -> Float, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val pageNow by rememberUpdatedState(pageOf)
    val page by remember { derivedStateOf { pageNow().roundToInt() } }
    val sheet = state.inspecting && state.selected != null || state.editingCaption && state.selectedCaption != null
    val sel = state.clips.firstOrNull { it.id == state.selected }
    val cap = state.selectedCaption?.let { id -> state.captions.firstOrNull { it.id == id } }
    val text = when {
        page != 0 || sheet -> null
        sel != null -> stringResource(R.string.design_selection, sel.name, sel.length)
        state.groupSelected -> {
            val nClips = state.multi.size
            val nCaps = state.multiCaptions.size
            listOfNotNull(
                if (nClips > 0) pluralStringResource(R.plurals.app_n_clips, nClips, nClips) else null,
                if (nCaps > 0) pluralStringResource(R.plurals.app_n_captions, nCaps, nCaps) else null,
            ).joinToString(" · ")
        }
        cap != null && state.props != null -> cap.text.replace('\n', ' ')
        else -> null
    }
    // (the last text stays while the chip fades out)
    val last = remember { arrayOf("") }
    if (text != null) last[0] = text
    AnimatedVisibility(
        text != null, modifier,
        enter = fadeIn() + scaleIn(spring(0.6f, 500f), initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f),
    ) {
        // the chip is 32 dp high; its cross is pressed over 44 (the area reaches a little past the chip's end and edges)
        Box(Modifier.padding(bottom = 2.dp).height(44.dp)) {
            Row(
                Modifier.align(Alignment.CenterStart).padding(end = 6.dp).height(32.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(K.SurfaceHighest)
                    .padding(start = 12.dp, end = 32.dp).testTag("selectionChip"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(last[0], color = K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
            Box(
                Modifier.align(Alignment.CenterEnd).size(44.dp).clip(CircleShape).clickable { haptics.tick(); state.clearSelection() }.testTag("selection:clear"),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Close, stringResource(R.string.app_deselect), tint = K.OnSurfaceVar, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** A choice among a few (a transition's type): the accent's container when chosen. */
@Composable
private fun ChoiceTile(label: String, on: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) K.PrimaryContainer else K.SurfaceHighest, label = "ct")
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(if (on) 22.dp else 14.dp)).background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 14.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) K.PrimarySoft else K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
}

/**
 * Selected edit point (tap the boundary between two clips): choose a transition and its length – it is inserted centered
 * on the edit point and becomes selected (type, direction and length can then be changed there). Audio: a crossfade.
 */
@Composable
internal fun CutBar(state: EditorState, modifier: Modifier) {
    val haptics = rememberHaptics()
    val cut = state.selectedCut
    // the edit point still exists (the clips still meet)
    val valid = cut != null && state.cutPairs().any { (l, r) -> l.id == cut.left && r.id == cut.right }
    val audio = cut != null && state.clips.firstOrNull { it.id == cut.right }?.kind?.track == TrackKind.AUDIO
    var len by remember { mutableStateOf(1f) }
    AnimatedVisibility(
        visible = valid && state.props != null,
        enter = slideInVertically(spring(0.6f, 500f)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        fun add(effect: String) {
            val c = state.selectedCut ?: return
            haptics.confirm()
            state.props?.addCutTransition(c.right, effect, len) { id -> state.selectedCut = null; state.selectedTransition = id }
        }
        Column(
            Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(K.SurfaceHigh)
                .padding(vertical = 8.dp).testTag("cutbar"),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.design_transition), color = K.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                RoundIcon(Ic.Close, stringResource(R.string.app_deselect), tint = K.OnSurfaceVar, modifier = Modifier.size(44.dp)) { state.selectedCut = null }
            }
            Row(
                Modifier.sideScroll().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val types = if (audio) listOf(TransitionType.CROSS) else TransitionType.entries.toList()
                for (ty in types) ChoiceTile(if (audio) stringResource(R.string.app_crossfade) else ty.label, false, "cut:${ty.effect}") { add(ty.effect) }
            }
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_length), color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
                for (sec in listOf(0.5f, 1f, 2f)) {
                    DockStep(if (sec < 1f) stringResource(R.string.app_half_second) else stringResource(R.string.app_seconds, sec.toInt()), abs(len - sec) < 0.03f, "cutlen:$sec") { haptics.tick(); len = sec }
                }
            }
        }
    }
}

/**
 * Selected transition: its type, length, motion blur and direction, in a card over the timeline (the edges can also be
 * dragged directly in the timeline). Removing it is in the dock. Between two sounds it is a crossfade, with only a length.
 */
@Composable
internal fun TransitionCard(state: EditorState, modifier: Modifier) {
    val haptics = rememberHaptics()
    val now = state.selectedTransition?.let { id -> state.transitions.firstOrNull { it.id == id } }
    // (the last one stays while the card slides out)
    val kept = remember { arrayOfNulls<UiTransition>(1) }
    if (now != null) kept[0] = now
    AnimatedVisibility(
        visible = now != null && state.props != null,
        enter = slideInVertically(spring(0.6f, 500f)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val tr = kept[0] ?: return@AnimatedVisibility
        val type = TransitionType.of(tr.effect)
        val between = tr.anchor == 0
        val audio = tr.track >= 3
        Column(
            Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(K.SurfaceHigh)
                .padding(vertical = 8.dp).testTag("transitionCard"),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 12.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (audio && between) R.string.app_crossfade else R.string.design_transition), color = K.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    // between which clips, or which fade it is
                    val a = state.clips.firstOrNull { it.id == tr.from }?.name
                    val b = state.clips.firstOrNull { it.id == tr.to }?.name
                    val sub = when {
                        tr.anchor == -1 -> stringResource(if (type == TransitionType.DIP_WHITE) R.string.app_fade_in_from_white else R.string.app_fade_in)
                        tr.anchor == 1 -> stringResource(if (type == TransitionType.DIP_WHITE) R.string.app_fade_out_to_white else R.string.app_fade_out)
                        a != null && b != null -> "$a → $b"
                        audio -> null
                        else -> type.label
                    }
                    if (sub != null) Text(sub, color = K.OnSurfaceVar, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (type.directional && between && !audio) {
                    val bg by animateColorAsState(if (tr.reverse) K.PrimaryContainer else K.SurfaceHighest, label = "rv")
                    val fg = if (tr.reverse) K.PrimarySoft else K.OnSurface
                    Row(
                        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(bg)
                            .clickable(role = Role.Switch) { haptics.tick(); state.props?.setTransitionType(tr.id, type.effect, !tr.reverse) }
                            .semantics { selected = tr.reverse }
                            .padding(start = 10.dp, end = 14.dp).testTag("transition:reverse"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Ic.Reverse, null, tint = fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.app_reverse_direction), color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            // the type: between two clips all of them, a fade in or out to black or white (sound has no types)
            if (!audio) Row(
                Modifier.sideScroll().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                for (ty in if (between) TransitionType.entries.toList() else TransitionType.FADES) {
                    val label = when {
                        between -> ty.label
                        ty == TransitionType.DIP_WHITE -> stringResource(R.string.app_to_white)
                        else -> stringResource(R.string.app_to_black)
                    }
                    ChoiceTile(label, ty == type, "transition:${ty.effect}", Modifier.inViewWhen(ty == type)) { haptics.confirm(); state.props?.setTransitionType(tr.id, ty.effect, tr.reverse) }
                }
            }
            PropSlider(
                stringResource(R.string.app_length), tr.end - tr.start, 0.2f, 3f, 1f, { Str.t(R.string.design_seconds_decimal, it) }, tag = "transition:length",
            ) { sec -> haptics.confirm(); state.props?.resizeTransition(tr.id, sec) }
            // how much the picture smears while it moves (the default is 100 %)
            val from = tr.from; val to = tr.to
            if (type.blurs && from != null && to != null && between && !audio) {
                PropSlider(
                    stringResource(R.string.design_transition_blur), TransitionLooks.blurOf(from, to) * 100f, 0f, TransitionLooks.MAX_BLUR * 100f, 100f,
                    { "%.0f %%".format(it) }, tag = "transition:blur", onLive = { TransitionLooks.setBlur(from, to, it / 100f) },
                ) { TransitionLooks.setBlur(from, to, it / 100f) }
            }
        }
    }
}
