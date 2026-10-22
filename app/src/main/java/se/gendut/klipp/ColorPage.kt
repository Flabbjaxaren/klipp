package se.gendut.klipp

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Curve
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.HslCurve
import se.gendut.klipp.engine.HslKind
import se.gendut.klipp.engine.Wheel
import se.gendut.klipp.engine.applyTo
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * All clips' grades (per clip id), plus a live grade while dragging a wheel or a curve.
 * Saved with the project (the sidecar file) and used by playback, preview and export.
 */
class GradeState {
    val grades = mutableStateMapOf<Long, ClipGrade>()
    /** Titles that follow a tracked window: the title's clip id → (clip, node). Saved with the grades. */
    val follows = mutableStateMapOf<Long, se.gendut.klipp.engine.Follow>()
    var live by mutableStateOf<Pair<Long, ClipGrade>?>(null)
    private var liveToken = 0
    var clipboard by mutableStateOf<ClipGrade?>(null)
    /** The color page's selected tool (the tab). */
    var editor by mutableStateOf(0)
    /** The clip the grade in [clipboard] was copied from. */
    var clipboardFrom: Long? = null
    /** Increments on every committed change (playback rebuilds). */
    var revision by mutableStateOf(0)
        private set

    fun of(id: Long): ClipGrade = grades[id] ?: ClipGrade()
    /** What should show for a clip right now (live during a drag). */
    fun shown(id: Long): ClipGrade? = live?.takeIf { it.first == id }?.second ?: grades[id]

    /**
     * The undo history (the app's shared one, [EditorState.recordSide]): every committed change becomes a step that
     * is undone in the same order as the timeline's. null = no history (tests, previews).
     */
    var history: ((label: String, undo: () -> Unit, redo: () -> Unit) -> Unit)? = null

    /** A committed change to a clip's grade. [record] = false: a consequence of something else (a split) – no step of its own. */
    fun set(id: Long, g: ClipGrade, record: Boolean = true) = setAll(mapOf(id to g), record)

    /** The clips graded together with a clip (same compound clip, excluding the clip itself). */
    var siblings: ((Long) -> Collection<Long>)? = null

    /** A clip's position and file (timeline ↔ source time; for mask keyframes and tracking in Properties). */
    var clipInfo: ((Long) -> ColorClip?)? = null

    /** Several clips' grades in one undo step (e.g. "apply to all"). A compound clip is graded as one. */
    fun setAll(changes0: Map<Long, ClipGrade>, record: Boolean = true) {
        val changes = LinkedHashMap(changes0)
        // (stabilization, tracking and the layer – key, mask – belong to each clip and are not carried over)
        if (record) siblings?.let { sib -> for ((id, g) in changes0) for (o in sib(id)) if (o !in changes0) changes[o] = g.forOtherClip(of(o).stab, of(o).layer) }
        val before = changes.keys.associateWith { grades[it] }
        for ((id, g) in changes) put(id, g)
        val after = changes.keys.associateWith { grades[it] }
        if (record && before != after) history?.invoke(Str.t(R.string.colorpage_history_color), { before.forEach { (id, g) -> put(id, g) } }, { after.forEach { (id, g) -> put(id, g) } })
    }

    private fun put(id: Long, g: ClipGrade?) {
        // empty = a node that neither grades nor has a window/qualifier (those must stay while being set up)
        if (g == null || (g.isNeutral && g.layer == null && g.nodes.size == 1 && g.nodes.none { it.hasKey })) grades.remove(id) else grades[id] = g
        // a lingering drag state for the clip would otherwise show the old grade for a while (e.g. after "Reset" or undo)
        if (live?.first == id) live = id to (g ?: ClipGrade())
        revision++
    }

    fun setLive(id: Long, g: ClipGrade) { live = id to g; liveToken++ }

    /**
     * The color page is the one shown. It stays composed even beside the others (for swiping between pages), so its
     * tools in the program image – window, dropper, compare, key – apply only while this is true.
     */
    var active by mutableStateOf(true)
    /** "Show key": (clip, node) whose key the program image shows in grayscale. */
    var showKey by mutableStateOf<Pair<Long, Int>?>(null)
    /** The key the program image should show right now (none outside the color page). */
    val shownKey get() = showKey.takeIf { active }
    /**
     * Source time of the still the preview shows right now, per clip (during scrubbing and drags a still is
     * shown – often slightly behind the playhead). The window and point cloud are drawn for the same time as the image.
     */
    var stillTimes by mutableStateOf<Map<Long, Double>>(emptyMap())
    /** The window for (clip, node) can be dragged and pinched directly in the program image. */
    var windowEdit by mutableStateOf<Pair<Long, Int>?>(null)
    /** The dropper: the next tap in the program image gives the position (0..1, y down). */
    var pick by mutableStateOf<((Float, Float) -> Unit)?>(null)
    /** Drags on a tracked window correct the tracking from that frame onward (instead of moving all of it). */
    var correctTrack by mutableStateOf(false)
    /** What the dropper should hit (shown in the image). */
    var pickHint by mutableStateOf(Str.t(R.string.colorpage_pick_hint_color))
    /** The still being compared against (split screen) and where the split line is. */
    var compare by mutableStateOf<Still?>(null)
    var wipe by mutableStateOf(0.5f)
    /** The program image as displayed (PixelCopy) – for scopes during playback. */
    var capture: (suspend () -> Bitmap?)? = null

    /** A node with new values, live (while dragging) and committed respectively. */
    fun liveNode(clip: Long, n: GradeNode) {
        val g = shown(clip) ?: ClipGrade()
        setLive(clip, g.copy(nodes = g.nodes.map { if (it.id == n.id) n else it }))
    }
    fun commitNode(clip: Long, n: GradeNode) {
        set(clip, of(clip).let { cg -> cg.copy(nodes = cg.nodes.map { if (it.id == n.id) n else it }) })
    }
    suspend fun endLive() {
        val t = liveToken
        delay(900)
        if (t == liveToken) live = null
    }

    fun setFollow(title: Long, f: se.gendut.klipp.engine.Follow?) {
        val before = follows[title]
        putFollow(title, f)
        if (before != f) history?.invoke(Str.t(R.string.colorpage_history_follow), { putFollow(title, before) }, { putFollow(title, f) })
    }

    private fun putFollow(title: Long, f: se.gendut.klipp.engine.Follow?) {
        if (f == null) follows.remove(title) else follows[title] = f
        revision++
    }

    fun toJson(): JSONObject = JSONObject().also { o ->
        grades.forEach { (id, g) -> o.put(id.toString(), g.toJson()) }
        // (keys that are not clip ids are skipped by older versions)
        if (follows.isNotEmpty()) o.put("_follow", JSONObject().also { f -> follows.forEach { (t, v) -> f.put(t.toString(), v.encode()) } })
    }
    fun load(o: JSONObject?) {
        grades.clear()
        follows.clear()
        o?.keys()?.forEach { k -> k.toLongOrNull()?.let { grades[it] = ClipGrade.fromJson(o.optJSONObject(k)) } }
        o?.optJSONObject("_follow")?.let { f -> f.keys().forEach { k -> k.toLongOrNull()?.let { t -> se.gendut.klipp.engine.Follow.parse(f.optString(k))?.let { follows[t] = it } } } }
        revision++
    }
}

/** Clip grades for parts of the app that do not get them as a parameter (e.g. the title's "follow object"). */
val LocalGradeState = androidx.compose.runtime.staticCompositionLocalOf<GradeState?> { null }

/** A clip the color page can select (decoupled from the engine). */
data class ColorClip(
    val id: Long, val name: String, val start: Float, val end: Float, val uri: android.net.Uri?, val srcIn: Double, val track: Int, val speed: Double = 1.0,
    /** Freeze frame: the source time shown for the whole clip. */
    val hold: Double? = null,
    /** Reverse. */
    val reverse: Boolean = false,
)

/**
 * The color page (Resolve style): pick a clip, build a node chain, drag the Lift/Gamma/Gain/Offset wheels,
 * curves and adjustments – the image above follows while dragging. Scopes on the graded image.
 * "Timeline" is the shared look (exposure, LUT etc.) applied after the clips' grades.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorPage(
    state: EditorState,
    gs: GradeState,
    clips: List<ColorClip>,
    look: se.gendut.klipp.engine.Look = se.gendut.klipp.engine.Look.NONE,
    /** Import a .cube; calls the callback with the LUT's name. */
    onImportLut: (((String) -> Unit) -> Unit)? = null,
    timeline: @Composable () -> Unit,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) } // 0 = clip, 1 = timeline
    // the clip under the playhead (topmost video track), or the selected one
    // derived: the page recomposes only when the clip changes, not every frame during playback
    val current = remember(clips) {
        derivedStateOf {
            clips.firstOrNull { it.id == state.selected }
                ?: clips.filter { state.playhead >= it.start && state.playhead < it.end }.minByOrNull { it.track }
                ?: clips.firstOrNull()
        }
    }.value
    var nodeId by remember(current?.id) { mutableStateOf(gs.of(current?.id ?: -1).nodes.first().id) }
    // the tool (0 wheels, 1 curves, 2 adjust, 3 LUT, 4 qualifier, 5 window, 6 film) lives in GradeState: it stays
    // when the page is left and when the neighboring pages are composed in during a swipe
    var editor by gs::editor
    var scopeKind by remember { mutableStateOf(0) } // 0 off, 1 waveform, 2 parade, 3 vector
    var nodeMenu by remember { mutableStateOf<GradeNode?>(null) }
    var stillMenu by remember { mutableStateOf<Still?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { runCatching { StillStore.init(ctx) } }
    // when leaving the color page: no color tools left on top of the program image
    androidx.compose.runtime.DisposableEffect(gs) {
        onDispose { gs.compare = null; gs.showKey = null; gs.pick = null; gs.windowEdit = null }
    }

    Column(Modifier.fillMaxSize().testTag("colorPage")) {
        // the header stays put – the rest scrolls beneath it
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.colorpage_title), color = K.OnSurface, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            // grade on the original files instead of the proxy (full resolution, no transcoding) – applies app-wide
            val px = se.gendut.klipp.engine.Proxies
            ToggleChip(stringResource(R.string.colorpage_original), !px.enabled, { haptics.tick(); px.switch(!px.enabled) }, Modifier.padding(end = 8.dp).testTag("colorOriginal"))
            ConnectedToggleGroup(listOf(stringResource(R.string.colorpage_tab_clip), stringResource(R.string.colorpage_tab_timeline)), tab, { tab = it }, Modifier.width(176.dp), tag = { if (it == 0) "colorTab:clip" else "colorTab:timeline" })
        }
        // the playhead for the clip being graded – stays put with the header, so you can scrub while grading
        if (tab == 0 && current != null) {
            val tr = gs.shown(current.id)?.nodes?.firstOrNull { it.id == nodeId }?.win?.takeIf { it.on }?.track
            ClipScrubber(state, current, tr?.let { it.t0..it.end }, Modifier.padding(bottom = 6.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("colorScroll")) {
            if (tab == 1) { timeline(); Spacer(Modifier.height(120.dp)); return@Column }
            if (current == null) {
                PanelCard(Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.colorpage_no_clip), color = K.OnSurfaceVar, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                }
                return@Column
            }
            ColorPageBody(state, gs, clips, current, look, onImportLut, nodeId, { nodeId = it }, editor, { editor = it },
                scopeKind, { scopeKind = it }, onNodeMenu = { haptics.heavy(); nodeMenu = it }, onStillMenu = { haptics.heavy(); stillMenu = it })
            Spacer(Modifier.height(120.dp))
        }
    }

    stillMenu?.let { st ->
        val cid = current?.id
        AlertDialog(
            onDismissRequest = { stillMenu = null },
            title = { Text(stringResource(R.string.colorpage_still_title, st.label)) },
            text = {
                Column {
                    if (cid != null) NodeMenuRow(stringResource(R.string.colorpage_apply_still_grade)) {
                        // the still may come from another clip: the color carries over, not stabilization or tracking
                        stillMenu = null; gs.set(cid, st.grade.forOtherClip(gs.of(cid).stab, gs.of(cid).layer)); nodeId = st.grade.nodes.first().id
                    }
                    NodeMenuRow(if (gs.compare?.id == st.id) stringResource(R.string.colorpage_stop_comparing) else stringResource(R.string.colorpage_compare_split)) {
                        stillMenu = null; gs.compare = if (gs.compare?.id == st.id) null else st
                    }
                    NodeMenuRow(stringResource(R.string.colorpage_delete_still)) {
                        stillMenu = null; if (gs.compare?.id == st.id) gs.compare = null; StillStore.delete(st)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { stillMenu = null }) { Text(stringResource(R.string.colorpage_close)) } },
            containerColor = K.SurfaceHigh,
        )
    }

    nodeMenu?.let { n ->
        val cid = current?.id
        AlertDialog(
            onDismissRequest = { nodeMenu = null },
            title = { Text(if (n.label.isNotBlank()) stringResource(R.string.colorpage_node_title_label, n.id, Str.name(n.label)) else stringResource(R.string.colorpage_node_title, n.id)) },
            text = {
                Column {
                    NodeMenuRow(if (n.enabled) stringResource(R.string.colorpage_node_disable) else stringResource(R.string.colorpage_node_enable)) {
                        nodeMenu = null; cid?.let { id -> gs.set(id, gs.of(id).let { cg -> cg.copy(nodes = cg.nodes.map { if (it.id == n.id) it.copy(enabled = !it.enabled) else it }) }) }
                    }
                    NodeMenuRow(if (n.cache) stringResource(R.string.colorpage_node_uncache) else stringResource(R.string.colorpage_node_cache)) {
                        // only one cache point per clip: the latest with cache wins – the others are turned off
                        nodeMenu = null; cid?.let { id -> gs.set(id, gs.of(id).let { cg -> cg.copy(nodes = cg.nodes.map { if (it.id == n.id) it.copy(cache = !n.cache) else it.copy(cache = false) }) }) }
                    }
                    NodeMenuRow(stringResource(R.string.colorpage_node_reset)) {
                        nodeMenu = null; cid?.let { id -> gs.set(id, gs.of(id).let { cg -> cg.copy(nodes = cg.nodes.map { if (it.id == n.id) GradeNode(n.id, n.label) else it }) }) }
                    }
                    if ((cid?.let { gs.of(it).nodes.size } ?: 1) > 1) NodeMenuRow(stringResource(R.string.colorpage_node_delete)) {
                        nodeMenu = null; cid?.let { id -> gs.set(id, gs.of(id).let { cg -> cg.copy(nodes = cg.nodes.filter { it.id != n.id }) }); nodeId = gs.of(id).nodes.first().id }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { nodeMenu = null }) { Text(stringResource(R.string.colorpage_close)) } },
            containerColor = K.SurfaceHigh,
        )
    }
}

/** The clip tab: clip row, node chain, scopes, the node's tools, gallery and grade actions. */
@Composable
private fun ColorPageBody(
    state: EditorState,
    gs: GradeState,
    clips: List<ColorClip>,
    current: ColorClip,
    look: se.gendut.klipp.engine.Look,
    onImportLut: (((String) -> Unit) -> Unit)?,
    nodeId: Int,
    setNode: (Int) -> Unit,
    editor: Int,
    setEditor: (Int) -> Unit,
    scopeKind: Int,
    setScope: (Int) -> Unit,
    onNodeMenu: (GradeNode) -> Unit,
    onStillMenu: (Still) -> Unit,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // the clips in timeline order (like Resolve's clip strip)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((i, c) in clips.withIndex()) key(c.id) {
            ClipChip(i + 1, c, c.id == current.id, graded = gs.grades[c.id]?.isNeutral == false) {
                haptics.tick()
                state.selected = c.id
                state.playing = false
                if (state.playhead < c.start || state.playhead >= c.end) state.playhead = (c.start + c.end) / 2
            }
        }
    }

    val g = gs.shown(current.id) ?: ClipGrade()
    val node = g.nodes.firstOrNull { it.id == nodeId } ?: g.nodes.first()
    fun live(n: GradeNode) = gs.liveNode(current.id, n)
    fun commit(n: GradeNode) {
        gs.commitNode(current.id, n)
        scope.launch { gs.endLive() }
    }
    var wbPicking by remember { mutableStateOf(false) }
    // the window can be dragged in the image when the window tab is open; key/dropper apply only to the selected clip and node
    androidx.compose.runtime.DisposableEffect(current.id, node.id, editor) {
        gs.windowEdit = if (editor == 5) current.id to node.id else null
        if (gs.showKey != null && gs.showKey != current.id to node.id) gs.showKey = null
        if (editor != 4 && editor != 2) gs.pick = null
        if (editor != 2) wbPicking = false
        onDispose { gs.windowEdit = null; gs.pick = null }
    }

    // nodes
    Spacer(Modifier.height(6.dp))
    PanelCard(title = stringResource(R.string.colorpage_nodes), trailing = { ScopesButton(scopeKind, setScope) }) {
        NodeGraph(current, g, node.id, onPick = { haptics.tick(); setNode(it) }, onMenu = onNodeMenu) {
            haptics.confirm()
            val cg = gs.of(current.id)
            val nid = (cg.nodes.maxOf { it.id }) + 1
            gs.set(current.id, cg.copy(nodes = cg.nodes + GradeNode(nid, "")))
            setNode(nid)
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.colorpage_node_hold_hint), color = K.Outline, fontSize = 11.sp, modifier = Modifier.weight(1f))
        }
        if (scopeKind > 0) Scopes(current, state, gs, scopeKind, look)
    }

    // the node's tools
    Spacer(Modifier.height(12.dp))
    val tabs = listOf(
        ToolTab(stringResource(R.string.colorpage_tool_wheels), Ic.Wheels, "editor:Hjul", marked = !(node.lift.isNeutral && node.gamma.isNeutral && node.gain.isNeutral && node.offset.isNeutral)),
        ToolTab(stringResource(R.string.colorpage_tool_curves), Ic.Curves, "editor:Kurvor", marked = node.hasCurves || node.hasHsl),
        ToolTab(stringResource(R.string.colorpage_tool_adjust), Ic.Sliders, "editor:Justera", marked = node.contrast != 1f || node.sat != 1f || node.hue != 0f || node.temp != 0f || node.tint != 0f || node.mix < 1f),
        ToolTab(stringResource(R.string.colorpage_tool_lut), Ic.Cube, "editor:LUT", marked = node.lut != null || node.drt != null),
        ToolTab(stringResource(R.string.colorpage_tool_qualifier), Ic.Dropper, "editor:Kvalificerare", marked = node.qual.on),
        ToolTab(stringResource(R.string.colorpage_tool_window), Ic.Window, "editor:Fönster", marked = node.win.on),
        ToolTab(stringResource(R.string.colorpage_tool_film), Ic.Film, "editor:Film", marked = node.film != null),
    )
    ToolTabs(tabs, editor, setEditor, Modifier.padding(horizontal = 12.dp))
    Spacer(Modifier.height(8.dp))
    PanelCard(title = stringResource(R.string.colorpage_editor_title, stringResource(when (editor) { 0 -> R.string.colorpage_editor_wheels; 1 -> R.string.colorpage_editor_curves; 2 -> R.string.colorpage_editor_adjust; 3 -> R.string.colorpage_editor_lut; 4 -> R.string.colorpage_editor_qualifier; 5 -> R.string.colorpage_editor_window; else -> R.string.colorpage_editor_film }), node.id)) {
        AnimatedContent(editor, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "editor") { e ->
            when (e) {
                0 -> WheelsGrid(node, onLive = ::live, onCommit = ::commit)
                1 -> CurvesEditor(node, onLive = ::live, onCommit = ::commit)
                3 -> NodeLutEditor(node, onLive = ::live, onCommit = ::commit, onImport = onImportLut)
                6 -> FilmEditor(node, onCommit = ::commit)
                4 -> QualifierEditor(current.id, node, gs, onLive = ::live, onCommit = ::commit, onPick = {
                    if (gs.pick != null) { gs.pick = null; return@QualifierEditor }
                    val clip = current
                    val nid = node.id
                    gs.pickHint = Str.t(R.string.colorpage_pick_hint_color)
                    gs.pick = { x, y ->
                        scope.launch {
                            val q = pickColor(ctx, clip, state.playhead, gs.of(clip.id), nid, x, y) ?: return@launch
                            haptics.confirm()
                            gs.of(clip.id).nodes.firstOrNull { it.id == nid }?.let { gs.commitNode(clip.id, it.copy(qual = q)) }
                        }
                    }
                })
                5 -> WindowEditor(current.id, node, gs, onLive = ::live, onCommit = ::commit, colorClip = current, playhead = { state.playhead }, seek = { state.playing = false; state.playhead = it })
                else -> Column {
                    // white balance: tap something that should be white or gray
                    val wbOn = gs.pick != null && wbPicking
                    PillButton(if (wbOn) stringResource(R.string.colorpage_wb_tap) else stringResource(R.string.colorpage_wb_dropper), {
                        if (gs.pick != null) { gs.pick = null; wbPicking = false; return@PillButton }
                        val clip = current
                        val nid = node.id
                        wbPicking = true
                        gs.pickHint = Str.t(R.string.colorpage_pick_hint_white)
                        gs.pick = { x, y ->
                            wbPicking = false
                            scope.launch {
                                val n2 = pickWhite(ctx, clip, state.playhead, gs.of(clip.id), nid, x, y) ?: return@launch
                                haptics.confirm()
                                gs.commitNode(clip.id, n2)
                            }
                        }
                    }, Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("adj:wb"), icon = Ic.Dropper, filled = !wbOn)
                    PropSlider(stringResource(R.string.colorpage_contrast), node.contrast, 0f, 2f, 1f, { "%.2f".format(it) }, "contrast", onLive = { live(node.copy(contrast = it)) }) { commit(node.copy(contrast = it)) }
                    PropSlider(stringResource(R.string.colorpage_pivot), node.pivot, 0f, 1f, 0.435f, { "%.3f".format(it) }, "pivot", onLive = { live(node.copy(pivot = it)) }) { commit(node.copy(pivot = it)) }
                    PropSlider(stringResource(R.string.colorpage_saturation), node.sat, 0f, 2f, 1f, { "%.0f".format(it * 50) }, "sat", onLive = { live(node.copy(sat = it)) }) { commit(node.copy(sat = it)) }
                    PropSlider(stringResource(R.string.colorpage_hue), node.hue, -180f, 180f, 0f, { "%.0f°".format(it) }, "hue", onLive = { live(node.copy(hue = it)) }) { commit(node.copy(hue = it)) }
                    PropSlider(stringResource(R.string.colorpage_temperature), node.temp, -1f, 1f, 0f, { "%+.0f".format(it * 100) }, "temp", onLive = { live(node.copy(temp = it)) }) { commit(node.copy(temp = it)) }
                    PropSlider(stringResource(R.string.colorpage_tint), node.tint, -1f, 1f, 0f, { "%+.0f".format(it * 100) }, "tint", onLive = { live(node.copy(tint = it)) }) { commit(node.copy(tint = it)) }
                    PropSlider(stringResource(R.string.colorpage_node_mix), node.mix, 0f, 1f, 1f, { "%.0f %%".format(it * 100) }, "mix", onLive = { live(node.copy(mix = it)) }) { commit(node.copy(mix = it)) }
                }
            }
        }
    }

    // the gallery: stills to compare against (split screen) and take grades from
    Spacer(Modifier.height(12.dp))
    PanelCard(title = stringResource(R.string.colorpage_gallery), trailing = { Text(stringResource(R.string.colorpage_gallery_hint), color = K.Outline, fontSize = 11.sp) }) {
        StillsRow(gs, onGrab = {
            val clip = current
            scope.launch {
                val img = grabStill(ctx, clip, state.playhead, gs.of(clip.id), look) ?: return@launch
                haptics.confirm()
                runCatching { StillStore.add(img, clip.name, gs.of(clip.id)) }
            }
        }, onMenu = onStillMenu)
    }

    // grade actions for the clip
    Spacer(Modifier.height(12.dp))
    PanelCard(title = stringResource(R.string.colorpage_clip_grade)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            ActionTile(stringResource(R.string.colorpage_copy), Ic.Copy, Modifier.weight(1f).testTag("grade:copy")) { haptics.confirm(); gs.clipboard = gs.of(current.id); gs.clipboardFrom = current.id }
            ActionTile(stringResource(R.string.colorpage_paste), Ic.Paste, Modifier.weight(1f).testTag("grade:paste"), enabled = gs.clipboard != null) {
                gs.clipboard?.let {
                    haptics.confirm()
                    // another clip gets the color – not the copied clip's stabilization and tracking
                    val g = if (gs.clipboardFrom == current.id) it else it.forOtherClip(gs.of(current.id).stab, gs.of(current.id).layer)
                    gs.set(current.id, g); setNode(g.nodes.first().id)
                }
            }
            ActionTile(stringResource(R.string.colorpage_all_clips), Ic.Layers, Modifier.weight(1f).testTag("grade:all")) {
                haptics.confirm()
                val cg = gs.of(current.id)
                gs.setAll(clips.filter { it.id != current.id }.associate { it.id to cg.forOtherClip(gs.of(it.id).stab, gs.of(it.id).layer) })
            }
            ActionTile(stringResource(R.string.colorpage_reset), Ic.Reset, Modifier.weight(1f).testTag("grade:reset"), danger = true) { haptics.heavy(); gs.set(current.id, ClipGrade()); setNode(1) }
        }
    }
    StabilizeCard(gs, current)
}

/** Scope names (string resources, resolved at use) and icons. */
private val SCOPES = listOf(R.string.colorpage_scope_none to Ic.EyeOff, R.string.colorpage_scope_waveform to Ic.Waveform, R.string.colorpage_scope_parade to Ic.Parade, R.string.colorpage_scope_vectorscope to Ic.Vectorscope)

/**
 * Scopes as a button with an icon: a tap drops down the choices. With a scope on, the button is tonal and shows
 * which one.
 */
@Composable
private fun ScopesButton(kind: Int, onKind: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val r by animateDpAsState(if (pressed) 8.dp else 18.dp, spring(0.55f, 700f), label = "sb")
    val on = kind > 0
    val bg by animateColorAsState(if (on || open) K.Tonal else K.SurfaceHigh, label = "sbb")
    val fg = if (on || open) K.PrimarySoft else K.OnSurface
    Box {
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(r)).background(bg)
                .clickable(interactionSource = src, indication = null, role = androidx.compose.ui.semantics.Role.Button) { haptics.tick(); open = true }
                .padding(start = 12.dp, end = 10.dp).testTag("scopes"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(if (on) SCOPES[kind].second else Ic.Waveform, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (on) stringResource(SCOPES[kind].first) else stringResource(R.string.colorpage_scopes), color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(2.dp))
            val turn by animateFloatAsState(if (open) 180f else 0f, spring(0.6f, 500f), label = "sbt")
            androidx.compose.material3.Icon(Ic.ChevronDown, null, tint = fg, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp), containerColor = K.SurfaceHighest,
            offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
        ) {
            for ((i, sc) in SCOPES.withIndex()) {
                val sel = i == kind
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(sc.first), color = if (sel) K.Primary else K.OnSurface, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium) },
                    leadingIcon = { androidx.compose.material3.Icon(sc.second, null, tint = if (sel) K.Primary else K.OnSurfaceVar, modifier = Modifier.size(20.dp)) },
                    trailingIcon = if (sel) ({ androidx.compose.material3.Icon(Ic.Check, null, tint = K.Primary, modifier = Modifier.size(18.dp)) }) else null,
                    onClick = { haptics.tick(); onKind(i); open = false },
                    modifier = Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) K.Tonal else Color.Transparent).testTag("scope:$i"),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeMenuRow(label: String, onClick: () -> Unit) {
    Text(label, color = K.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipChip(n: Int, c: ColorClip, selected: Boolean, graded: Boolean, onClick: () -> Unit) {
    // selected: rounder corners, a primary-colored ring around the image and the name in primary color
    val r by animateDpAsState(if (selected) 22.dp else 14.dp, spring(0.5f, 500f), label = "cc")
    val ring by animateColorAsState(if (selected) K.Primary else Color.Transparent, label = "ccb")
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, c.uri, c.srcIn) { value = c.uri?.let { Thumbs.load(ctx, it, c.srcIn + 0.2) } }
    Column(
        Modifier.width(124.dp).clip(RoundedCornerShape(r + 3.dp)).combinedClickable(onClick = onClick).testTag("colorClip:${c.id}"),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).border(3.dp, ring, RoundedCornerShape(r + 3.dp)).padding(5.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(r - 2.dp)).background(K.SurfaceHighest)) {
                img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Text("%02d".format(n), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 1.dp))
                if (graded) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(12.dp).clip(CircleShape).background(Color(0x99000000)).padding(2.dp).clip(CircleShape).background(K.Primary))
            }
        }
        Text(c.name, color = if (selected) K.Primary else K.OnSurfaceVar, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).padding(top = 2.dp, bottom = 4.dp))
    }
}

/**
 * The node chain (like Resolve's node tree, in a row): each node shows its image – the clip graded up to and including
 * the node – with input and output, the number and icons for the tools used in it. "+" adds a node at the end.
 * Long press = menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeGraph(clip: ColorClip, g: ClipGrade, selected: Int, onPick: (Int) -> Unit, onMenu: (GradeNode) -> Unit, onAdd: () -> Unit) {
    val cacheStatus = se.gendut.klipp.engine.NodeCache.status(clip.id)
    val ctx = LocalContext.current
    // the clip's image, small (software bitmap so the grade can be computed on it)
    val base by produceState<Bitmap?>(null, clip.uri, clip.srcIn) {
        val img = clip.uri?.let { Thumbs.load(ctx, it, clip.srcIn + 0.2) }
        if (img == null) { value = withContext(Dispatchers.Default) { swatch() }; return@produceState }
        value = withContext(Dispatchers.Default) {
            val b = img.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            Bitmap.createScaledBitmap(b, 160, (160f * b.height / b.width).roundToInt().coerceAtLeast(1), true)
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NodePort(K.Teal)
        for ((i, n) in g.nodes.withIndex()) key(n.id) {
            Wire()
            NodeCard(n, ClipGrade(g.nodes.take(i + 1)), base, n.id == selected, { onPick(n.id) }, { onMenu(n) },
                cacheText = if (n.cache) cacheStatus ?: stringResource(R.string.colorpage_cache) else null)
        }
        Wire()
        NodePort(K.Teal)
        Spacer(Modifier.width(14.dp))
        val src = remember { MutableInteractionSource() }
        val pressed by src.collectIsPressedAsState()
        val r by animateDpAsState(if (pressed) 12.dp else 24.dp, spring(0.55f, 700f), label = "add")
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(r)).background(K.Tonal)
                .combinedClickable(interactionSource = src, indication = null, onClick = onAdd).testTag("addNode"),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Icon(Ic.Plus, stringResource(R.string.colorpage_new_node), tint = K.PrimarySoft, modifier = Modifier.size(22.dp)) }
    }
}

/** Without a clip image: a grayscale ramp with a color band, so the node's grading still shows on the card. */
private fun swatch(): Bitmap {
    val w = 160; val h = 90
    val px = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        if (y < h / 2) {
            val v = x * 255 / (w - 1)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        } else android.graphics.Color.HSVToColor(floatArrayOf(x * 360f / w, 0.55f, 0.75f))
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

@Composable
private fun NodePort(c: Color) = Box(Modifier.size(12.dp).clip(CircleShape).background(c))

@Composable
private fun Wire() = Box(Modifier.width(14.dp).height(2.dp).background(K.OutlineVar))

/** The tools used in the node (the icons on the node card). */
private fun GradeNode.toolIcons() = buildList {
    if (!(lift.isNeutral && gamma.isNeutral && gain.isNeutral && offset.isNeutral)) add(Ic.Wheels)
    if (hasCurves) add(Ic.Curves)
    if (contrast != 1f || sat != 1f || hue != 0f || temp != 0f || tint != 0f) add(Ic.Sliders)
    if (lut != null) add(Ic.Cube)
    if (qual.on) add(Ic.Dropper)
    if (win.on) add(Ic.Window)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeCard(n: GradeNode, upTo: ClipGrade, base: Bitmap?, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit, cacheText: String? = null) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    // selected: rounder, filled with the primary container color and a ring – shape and color show the selection
    val r by animateDpAsState(if (selected) 24.dp else 16.dp, spring(0.55f, 600f), label = "nr")
    val bg by animateColorAsState(if (selected) K.PrimaryContainer else K.SurfaceHigh, label = "nb")
    val ring by animateColorAsState(if (selected) K.Primary else Color.Transparent, label = "nring")
    val fg = if (selected) K.PrimarySoft else K.OnSurface
    // the node's image: the clip graded up to and including the node (recomputed shortly after dragging stops)
    val img by produceState<ImageBitmap?>(null, base, upTo) {
        val b = base ?: return@produceState
        delay(120)
        value = withContext(Dispatchers.Default) { upTo.applyTo(b).asImageBitmap() }
    }
    Column(
        Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value; alpha = if (n.enabled) 1f else 0.45f }
            .width(128.dp).clip(RoundedCornerShape(r)).background(bg).border(2.dp, ring, RoundedCornerShape(r))
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onLongClick = onLong) {
                scope.launch { press.snapTo(0.9f); press.animateTo(1f, Springs.pop()) }
                onClick()
            }
            .padding(6.dp)
            .testTag("node:${n.id}"),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(r - 6.dp)).background(K.SurfaceHighest)) {
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text("%02d".format(n.id), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 6.dp))
            if (!n.enabled) Text(stringResource(R.string.colorpage_node_off_badge), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).clip(CircleShape).background(Color(0xCC93000A)).padding(horizontal = 8.dp, vertical = 2.dp))
            // the node cache: status (cached / caching 40 % / queued)
            if (cacheText != null) Text(cacheText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(CircleShape).background(Color(0xCC1B5E20)).padding(horizontal = 6.dp, vertical = 1.dp).testTag("nodeCache:${n.id}"))
        }
        Row(Modifier.fillMaxWidth().height(26.dp).padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (n.isNeutral && n.enabled) stringResource(R.string.colorpage_node_neutral) else n.label.ifBlank { Str.t(R.string.colorpage_node_default_label) }.let(Str::name), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            for (ic in n.toolIcons().take(4)) androidx.compose.material3.Icon(ic, null, tint = if (selected) K.Primary else K.OnSurfaceVar, modifier = Modifier.padding(start = 2.dp).size(14.dp))
        }
    }
}

/** Lift, Gamma, Gain, Offset – two by two. */
@Composable
private fun WheelsGrid(n: GradeNode, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit) {
    Column(Modifier.padding(horizontal = 6.dp)) {
        Row {
            ColorWheel("Lift", n.lift, Modifier.weight(1f), { onLive(n.copy(lift = it)) }) { onCommit(n.copy(lift = it)) }
            ColorWheel("Gamma", n.gamma, Modifier.weight(1f), { onLive(n.copy(gamma = it)) }) { onCommit(n.copy(gamma = it)) }
        }
        Row {
            ColorWheel("Gain", n.gain, Modifier.weight(1f), { onLive(n.copy(gain = it)) }) { onCommit(n.copy(gain = it)) }
            ColorWheel("Offset", n.offset, Modifier.weight(1f), { onLive(n.copy(offset = it)) }) { onCommit(n.copy(offset = it)) }
        }
    }
}

/**
 * A color wheel: drag the puck toward a color (snaps to center with a click), drag the strip below the wheel for
 * luminance (the master control). Double-tap the wheel to reset the color, the strip to reset the master.
 */
@Composable
fun ColorWheel(label: String, w: Wheel, modifier: Modifier, onLive: (Wheel) -> Unit, onCommit: (Wheel) -> Unit) {
    val haptics = rememberHaptics()
    var cur by remember { mutableStateOf(w) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(w) { if (!dragging) cur = w }
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)
    val lastTap = remember { longArrayOf(0L, 0L) }
    Column(modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        // R G B in their colors, dimmed when zero
        val rgb = cur.rgb()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, col) in listOf(Color(0xFFFF8A80), Color(0xFF8BE0A4), Color(0xFF8AB4FF)).withIndex()) {
                Text("%+.2f".format(rgb[i]), color = if (abs(rgb[i]) < 0.005f) K.Outline else col, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp).testTag("wheel:$label")) {
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = size.width / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val now = System.currentTimeMillis()
                    if (now - lastTap[0] < 320) {
                        down.consume()
                        haptics.confirm(); lastTap[0] = 0
                        if (cur.x != 0f || cur.y != 0f) { cur = cur.copy(x = 0f, y = 0f); commit(cur) }
                        return@awaitEachGesture
                    }
                    // on the puck: take the gesture immediately; otherwise only on horizontal movement – vertical scrolls the page
                    val puck = Offset(c.x + cur.x * r * 0.92f, c.y - cur.y * r * 0.92f)
                    if ((down.position - puck).getDistance() < 28.dp.toPx()) down.consume()
                    else when (claimHorizontal(down)) {
                        null -> { lastTap[0] = now; return@awaitEachGesture }
                        false -> return@awaitEachGesture
                        true -> {}
                    }
                    val start = cur
                    dragging = true
                    var snapped = cur.x == 0f && cur.y == 0f
                    // relative drag (like Resolve): the puck moves with the finger, with fine sensitivity
                    var px = cur.x; var py = cur.y
                    var last = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val d = ch.position - last
                        last = ch.position
                        px += d.x / r * 0.6f
                        py -= d.y / r * 0.6f
                        val m = hypot(px, py)
                        if (m > 1f) { px /= m; py /= m }
                        val near = hypot(px, py) < 0.035f
                        if (near && !snapped) haptics.tick()
                        snapped = near
                        cur = if (near) cur.copy(x = 0f, y = 0f) else cur.copy(x = px, y = py)
                        live(cur)
                        ch.consume()
                    }
                    dragging = false
                    // a tap without change: no undo step, but counts as a tap (double-tap)
                    if (cur != start) commit(cur) else lastTap[0] = now
                }
            }) {
                val r = size.minDimension / 2f
                val c = center
                // the color circle: same angle → color as the grade math (Wheel.rgb)
                val cols = (0..12).map { i ->
                    val phi = i * 30.0
                    val th = (-phi * PI / 180).toFloat()
                    val h = Wheel.hueColor(th)
                    Color(h[0], h[1], h[2])
                }
                drawCircle(Brush.sweepGradient(cols, c), r, c)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF8A8A8A), Color(0x008A8A8A)), c, r), r, c)
                drawCircle(Color(0x33000000), r * 0.98f, c, style = Stroke(r * 0.04f))
                drawLine(Color(0x55FFFFFF), Offset(c.x - r * 0.12f, c.y), Offset(c.x + r * 0.12f, c.y), 1.dp.toPx())
                drawLine(Color(0x55FFFFFF), Offset(c.x, c.y - r * 0.12f), Offset(c.x, c.y + r * 0.12f), 1.dp.toPx())
                val p = Offset(c.x + cur.x * r * 0.92f, c.y - cur.y * r * 0.92f)
                drawCircle(Color.Black.copy(alpha = 0.5f), (if (dragging) 11f else 9f) * density, p)
                drawCircle(Color.White, (if (dragging) 9f else 7f) * density, p, style = Stroke(2.5f * density))
            }
        }
        // the master control (luminance) as a strip dragged horizontally
        MasterStrip(cur.master, label, onLive = { m -> cur = cur.copy(master = m); live(cur) }, onCommit = { m -> cur = cur.copy(master = m); commit(cur) }, lastTap = lastTap)
    }
}

@Composable
private fun MasterStrip(value: Float, label: String, onLive: (Float) -> Unit, onCommit: (Float) -> Unit, lastTap: LongArray) {
    val haptics = rememberHaptics()
    val v by rememberUpdatedState(value)
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)
    Box(
        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh).testTag("master:$label")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val now = System.currentTimeMillis()
                    if (now - lastTap[1] < 320) { down.consume(); haptics.confirm(); if (v != 0f) commit(0f); lastTap[1] = 0; return@awaitEachGesture }
                    // only horizontal movement takes the gesture – vertical scrolls the page
                    when (claimHorizontal(down)) {
                        null -> { lastTap[1] = now; return@awaitEachGesture }
                        false -> return@awaitEachGesture
                        true -> {}
                    }
                    var m = v
                    var last = down.position.x
                    var zero = m == 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.position.x - last
                        last = ch.position.x
                        m = (m + dx / size.width * 1.2f).coerceIn(-1f, 1f)
                        val near = abs(m) < 0.02f
                        if (near && !zero) haptics.tick()
                        zero = near
                        live(if (near) 0f else m)
                        ch.consume()
                    }
                    commit(if (abs(m) < 0.02f) 0f else m)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // "wheel edge": ticks that slide with the value
            val off = (value * size.width * 2f) % 12.dp.toPx()
            var x = -12.dp.toPx() + off
            while (x < size.width) {
                drawLine(K.OutlineVar, Offset(x, size.height * 0.3f), Offset(x, size.height * 0.7f), 1.dp.toPx())
                x += 6.dp.toPx()
            }
            drawRect(K.Primary, Offset(size.width / 2f - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
        Text("%+.2f".format(value), color = K.OnSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xCC1D2026)).padding(horizontal = 6.dp))
    }
}

/**
 * Wait until the finger has moved past slop (like PropSlider): true = horizontal, the gesture is ours; false = vertical
 * or already taken by the panel's scrolling; null = released without movement (a tap).
 */
private suspend fun AwaitPointerEventScope.claimHorizontal(down: PointerInputChange): Boolean? {
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return false
        if (!c.pressed) return null
        if (c.isConsumed) return false
        val dx = abs(c.position.x - down.position.x)
        val dy = abs(c.position.y - down.position.y)
        if (dx > viewConfiguration.touchSlop / 2 && dx > dy) { c.consume(); return true }
        if (dy > viewConfiguration.touchSlop) return false
    }
}

/** The curves: the custom Y/R/G/B curves or one of the six HSL curves (a row of choices on top). */
@Composable
fun CurvesEditor(n: GradeNode, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit) {
    var mode by remember { mutableStateOf(-1) }
    val labels = listOf(R.string.colorpage_curves_custom) + HslKind.entries.map { hslLabel(it) }
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, l) in labels.withIndex()) {
                val k = i - 1
                val on = mode == k
                val used = if (k < 0) n.hasCurves else !n.hsl[HslKind.entries[k]].isNeutral
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) K.Primary else K.SurfaceHigh)
                        .clickable { mode = k }.padding(horizontal = 12.dp, vertical = 7.dp).testTag("curveMode:$k"),
                ) {
                    Text(stringResource(l) + if (used && !on) " •" else "", color = if (on) K.OnPrimary else K.OnSurface, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (mode < 0) CustomCurves(n, onLive, onCommit) else HslCurveEditor(n, HslKind.entries[mode], onLive, onCommit)
    }
}

private fun hslLabel(k: HslKind) = when (k) {
    HslKind.HUE_HUE -> R.string.colorpage_curves_hue_hue; HslKind.HUE_SAT -> R.string.colorpage_curves_hue_sat
    HslKind.HUE_LUM -> R.string.colorpage_curves_hue_lum; HslKind.LUM_SAT -> R.string.colorpage_curves_lum_sat
    HslKind.SAT_SAT -> R.string.colorpage_curves_sat_sat; HslKind.SAT_LUM -> R.string.colorpage_curves_sat_lum
}

/** The six primaries and secondaries on the hue axis (0 red, 1/6 yellow, 1/3 green, 1/2 cyan, 2/3 blue, 5/6 magenta). */
private val HUE_SWATCHES = listOf(Color(0xFFFF4040), Color(0xFFFFE040), Color(0xFF40E060), Color(0xFF40E0E0), Color(0xFF4070FF), Color(0xFFE040E0))

/** The color at a hue (0..1) on the axis, drawn behind the hue curves. */
private fun hueColor(h: Float): Color {
    val c = android.graphics.Color.HSVToColor(floatArrayOf(((h % 1f + 1f) % 1f) * 360f, 0.75f, 0.85f))
    return Color(c)
}

/**
 * One HSL curve. The middle line is no change; up = more (hue turned, saturation, luminance). Tap = a point on the
 * curve, drag = move, drag out of the box = delete. The hue axis wraps (a point near red affects both ends); the
 * luma and saturation axes have fixed end points that move only up and down. Under the hue curves: the six colors –
 * a tap adds a point there plus neutral points at the neighbouring colors, to change just that color.
 */
@Composable
fun HslCurveEditor(n: GradeNode, kind: HslKind, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit) {
    val haptics = rememberHaptics()
    fun defaults(): List<Pair<Float, Float>> = if (kind.periodic) emptyList() else listOf(0f to 0.5f, 1f to 0.5f)
    fun pointsOf(node: GradeNode) = node.hsl[kind].points.ifEmpty { defaults() }
    var pts by remember(kind) { mutableStateOf(pointsOf(n)) }
    var dragging by remember { mutableStateOf(false) }
    var active by remember(kind) { mutableStateOf<Int?>(null) }
    val src = pointsOf(n)
    LaunchedEffect(src) { if (!dragging) pts = src }
    val node by rememberUpdatedState(n)
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)
    fun with(node: GradeNode, p: List<Pair<Float, Float>>) = node.copy(hsl = node.hsl.with(kind, HslCurve(p)))
    fun heightAt(p: List<Pair<Float, Float>>, x: Float): Float {
        if (p.isEmpty()) return 0.5f
        var a = p.sortedBy { it.first }
        if (kind.periodic) a = a.map { (px, py) -> (px - 1f) to py } + a + a.map { (px, py) -> (px + 1f) to py }
        return HslCurve.at(a, x)
    }
    Column(Modifier.padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // the value under the finger (or the last point touched), in the panel's terms
            val sel = active?.let { pts.getOrNull(it) }
            val readout = sel?.let { (x, y) ->
                val input = if (kind.periodic) "%.0f°".format(x * 360f) else "%.2f".format(x)
                val out = when (kind) {
                    HslKind.HUE_HUE -> stringResource(R.string.colorpage_hsl_rotate, "%+.1f".format((y - 0.5f) * 360f))
                    HslKind.HUE_SAT, HslKind.LUM_SAT, HslKind.SAT_SAT -> stringResource(R.string.colorpage_hsl_sat, "%.2f".format(2f * y))
                    else -> stringResource(R.string.colorpage_hsl_lum, "%.2f".format(2f * y))
                }
                stringResource(R.string.colorpage_hsl_input, input) + " · " + out
            } ?: ""
            Text(readout, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.weight(1f).testTag("hslReadout"))
            PillButton(stringResource(R.string.colorpage_reset), { haptics.confirm(); pts = defaults(); active = null; commit(node.copy(hsl = node.hsl.with(kind, HslCurve()))) }, icon = Ic.Reset, filled = false)
        }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1.6f).padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF15171C)).testTag("hslCurve")
                .pointerInput(kind) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun toCurve(o: Offset) = (o.x / size.width).coerceIn(0f, 1f) to (1f - o.y / size.height).coerceIn(0f, 1f)
                        var list = pts.sortedBy { it.first }.toMutableList()
                        fun dist(i: Int) = hypot(list[i].first * size.width - down.position.x, (1 - list[i].second) * size.height - down.position.y)
                        val hit = list.indices.minByOrNull { dist(it) }?.takeIf { dist(it) < 24.dp.toPx() }
                        val onLine = abs((1 - heightAt(list, down.position.x / size.width)) * size.height - down.position.y) < 24.dp.toPx()
                        var tap = false
                        if (hit != null || onLine) down.consume()
                        else when (claimHorizontal(down)) {
                            null -> tap = true
                            false -> return@awaitEachGesture
                            true -> {}
                        }
                        var idx = hit ?: run {
                            // a new point on the curve where tapped (the curve doesn't jump)
                            val x = (down.position.x / size.width).coerceIn(0f, 1f)
                            val same = list.indexOfFirst { abs(it.first - x) < 1e-3f }
                            if (same >= 0) same else {
                                val i = list.indexOfFirst { it.first > x }.let { if (it < 0) list.size else it }
                                list.add(i, x to heightAt(list, x))
                                haptics.tick()
                                i
                            }
                        }
                        // the luma/saturation axes: the end points stay at 0 and 1
                        val end = !kind.periodic && (idx == 0 || idx == list.lastIndex)
                        var removed = false
                        pts = list.toList(); active = idx
                        if (tap) { commit(with(node, pts)); return@awaitEachGesture }
                        dragging = true
                        live(with(node, pts))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val (x, y) = toCurve(c.position)
                            val out = c.position.y < -40.dp.toPx() || c.position.y > size.height + 40.dp.toPx() || c.position.x < -40.dp.toPx() || c.position.x > size.width + 40.dp.toPx()
                            list = pts.toMutableList()
                            if (out && !end) {
                                if (!removed) { haptics.heavy(); removed = true }
                                if (idx in list.indices) list.removeAt(idx)
                                pts = list.toList(); active = null; live(with(node, pts))
                                idx = -1
                                c.consume(); continue
                            }
                            if (idx < 0) continue
                            val lo = if (idx > 0) list[idx - 1].first + 0.005f else 0f
                            val hi = if (idx < list.lastIndex) list[idx + 1].first - 0.005f else 1f
                            list[idx] = (if (end) list[idx].first else x.coerceIn(lo, hi)) to y
                            pts = list.toList()
                            live(with(node, pts))
                            c.consume()
                        }
                        dragging = false
                        commit(with(node, pts))
                    }
                },
        ) {
            val w = size.width; val h = size.height
            // the axis: the hue circle, gray from black to white, or gray to full color
            val stops = (0..24).map { i ->
                val f = i / 24f
                when {
                    kind.periodic -> hueColor(f)
                    kind == HslKind.LUM_SAT -> Color(f, f, f)
                    else -> androidx.compose.ui.graphics.lerp(Color(0xFF8A8A8A), Color(0xFFE05A3A), f)
                }
            }
            drawRect(Brush.horizontalGradient(stops.map { it.copy(alpha = 0.22f) }), Offset.Zero, size)
            drawRect(Brush.horizontalGradient(stops), Offset(0f, h - 6.dp.toPx()), androidx.compose.ui.geometry.Size(w, 6.dp.toPx()))
            for (i in 1..5) drawLine(Color(0x1FFFFFFF), Offset(w * i / 6f, 0f), Offset(w * i / 6f, h), 1f)
            drawLine(Color(0x55FFFFFF), Offset(0f, h / 2f), Offset(w, h / 2f), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val t = HslCurve(pts).table(kind.periodic, 256)
            val path = Path()
            for (i in 0 until 256) {
                val x = i / 255f * w; val y = (1 - t[i]) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color(0xFFF2F2F2), style = Stroke(2.5f * density))
            for ((i, p) in pts.withIndex()) {
                val o = Offset(p.first * w, (1 - p.second) * h)
                drawCircle(Color(0xFF15171C), 7.5f * density, o)
                drawCircle(if (i == active) K.Primary else Color(0xFFF2F2F2), 6f * density, o)
            }
        }
        if (kind.periodic) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((i, col) in HUE_SWATCHES.withIndex()) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(col).testTag("hslSwatch:$i")
                        .clickable {
                            // the color plus neutral points at its neighbours (an existing point there is kept)
                            haptics.tick()
                            val cx = i / 6f
                            var list = pts.toMutableList()
                            fun has(x: Float) = list.any { abs(((it.first - x + 1.5f) % 1f) - 0.5f) < 0.02f }
                            val center = heightAt(list, cx)
                            for ((x, y) in listOf((cx - 1f / 6f + 1f) % 1f to 0.5f, cx to center, (cx + 1f / 6f) % 1f to 0.5f)) if (!has(x)) list.add(x to y)
                            list = list.sortedBy { it.first }.toMutableList()
                            pts = list.toList(); active = list.indexOfFirst { abs(it.first - cx) < 1e-4f }
                            commit(with(node, pts))
                        },
                )
            }
        }
    }
}

/** Curves: Y affects all channels, R/G/B each separately. Tap = new point, drag = move, drag out of the box = delete. */
@Composable
private fun CustomCurves(n: GradeNode, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit) {
    val haptics = rememberHaptics()
    var ch by remember { mutableStateOf(0) }
    val curveOf: (GradeNode, Int) -> Curve = { node, i -> when (i) { 1 -> node.curveR; 2 -> node.curveG; 3 -> node.curveB; else -> node.curveY } }
    fun with(node: GradeNode, i: Int, c: Curve) = when (i) { 1 -> node.copy(curveR = c); 2 -> node.copy(curveG = c); 3 -> node.copy(curveB = c); else -> node.copy(curveY = c) }
    // a single state the gesture (never restarted) reads and writes; follows the node/channel when not dragging
    var pts by remember { mutableStateOf(curveOf(n, ch).points) }
    var dragging by remember { mutableStateOf(false) }
    val src = curveOf(n, ch).points
    LaunchedEffect(src) { if (!dragging) pts = src }
    val node by rememberUpdatedState(n)
    val channel by rememberUpdatedState(ch)
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)
    val colors = listOf(Color(0xFFE2E2E9), Color(0xFFFF6B6B), Color(0xFF6BDB8B), Color(0xFF6BA7FF))
    Column(Modifier.padding(horizontal = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ConnectedToggleGroup(listOf("Y", "R", "G", "B"), ch, { i -> ch = i; pts = curveOf(node, i).points }, Modifier.width(200.dp), tag = { "curveCh:$it" })
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.colorpage_reset), { haptics.confirm(); pts = Curve.IDENTITY; commit(with(node, channel, Curve())) }, icon = Ic.Reset, filled = false)
        }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF15171C)).testTag("curve")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun toCurve(o: Offset) = (o.x / size.width).coerceIn(0f, 1f) to (1f - o.y / size.height).coerceIn(0f, 1f)
                        var list = pts.toMutableList()
                        val hit = list.indices.minByOrNull { i -> hypot(list[i].first * size.width - down.position.x, (1 - list[i].second) * size.height - down.position.y) }
                            ?.takeIf { i -> hypot(list[i].first * size.width - down.position.x, (1 - list[i].second) * size.height - down.position.y) < 24.dp.toPx() }
                        // on a point or the curve: take the gesture immediately; otherwise only a tap or horizontal – vertical scrolls the page
                        val t = Curve(list).table()
                        val onLine = abs((1 - t[(down.position.x / size.width * 255).roundToInt().coerceIn(0, 255)]) * size.height - down.position.y) < 24.dp.toPx()
                        var tap = false
                        if (hit != null || onLine) down.consume()
                        else when (claimHorizontal(down)) {
                            null -> tap = true
                            false -> return@awaitEachGesture
                            true -> {}
                        }
                        var idx = hit ?: run {
                            // new point on the curve where tapped
                            val (x, y) = toCurve(down.position)
                            // if a point already exists at the same x (e.g. the end point at 0), take it – two points at the same x
                            // gave an ambiguous curve, and the drag then moved the wrong point
                            val same = list.indexOfFirst { kotlin.math.abs(it.first - x) < 1e-4f }
                            if (same >= 0) same else {
                                val i = list.indexOfFirst { it.first > x }.let { if (it < 0) list.size else it }
                                list.add(i, x to y)
                                haptics.tick()
                                i
                            }
                        }
                        val end = idx == 0 || idx == list.lastIndex
                        var removed = false
                        pts = list.toList()
                        if (tap) { commit(with(node, channel, Curve(pts))); return@awaitEachGesture }
                        dragging = true
                        live(with(node, channel, Curve(pts)))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val (x, y) = toCurve(c.position)
                            val out = c.position.y < -40.dp.toPx() || c.position.y > size.height + 40.dp.toPx() || c.position.x < -40.dp.toPx() || c.position.x > size.width + 40.dp.toPx()
                            list = pts.toMutableList()
                            if (out && !end) {
                                if (!removed) { haptics.heavy(); removed = true }
                                if (idx in list.indices) list.removeAt(idx)
                                pts = list.toList(); live(with(node, channel, Curve(pts)))
                                idx = -1
                                c.consume(); continue
                            }
                            if (idx < 0) continue
                            val lo = if (idx > 0) list[idx - 1].first + 0.01f else 0f
                            val hi = if (idx < list.lastIndex) list[idx + 1].first - 0.01f else 1f
                            list[idx] = (if (end) list[idx].first else x.coerceIn(lo, hi)) to y
                            pts = list.toList()
                            live(with(node, channel, Curve(pts)))
                            c.consume()
                        }
                        dragging = false
                        commit(with(node, channel, Curve(pts)))
                    }
                },
        ) {
            for (i in 1..3) {
                val x = size.width * i / 4f; val y = size.height * i / 4f
                drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
                drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
            }
            drawLine(Color(0x33FFFFFF), Offset(0f, size.height), Offset(size.width, 0f), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val t = Curve(pts).table()
            val path = Path()
            for (i in 0 until 256) {
                val x = i / 255f * size.width; val y = (1 - t[i]) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[ch], style = Stroke(2.5f * density))
            for (p in pts) drawCircle(colors[ch], 6f * density, Offset(p.first * size.width, (1 - p.second) * size.height))
        }
    }
}

/**
 * Scopes (waveform, RGB parade, vectorscope). When stopped: the clip's frame at the playhead, graded with the clip's
 * grade and the timeline look on the CPU. During playback: the program image as actually displayed (the GPU's
 * grade, PixelCopy) four times per second – falls back to stills if that fails.
 */
@Composable
private fun Scopes(c: ColorClip, state: EditorState, gs: GradeState, kind: Int, look: se.gendut.klipp.engine.Look) {
    val ctx = LocalContext.current
    val grade = gs.shown(c.id)
    // fifths of a second in the source – changes rarely, so the scope doesn't recompose every frame
    val tick = remember(c) { derivedStateOf { (c.srcAt(state.playhead) * 5).roundToInt() } }.value
    var live by remember { mutableStateOf(false) }
    val still by produceState<ImageBitmap?>(null, c.uri, tick, grade, kind, look, state.playing) {
        if (state.playing) return@produceState
        val uri = c.uri ?: return@produceState
        delay(60)
        val sec = c.srcAt(state.playhead)
        val frame = Frames.load(ctx, uri, sec, exact = false) ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val small = Bitmap.createScaledBitmap(frame.asAndroidBitmap(), 256, (256f * frame.height / frame.width).roundToInt().coerceAtLeast(1), true)
            val graded = grade?.at(sec)?.applyTo(small) ?: small
            ScopeMath.render(look.applyTo(graded), kind).asImageBitmap()
        }
        live = false
    }
    var playingImg by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(state.playing, kind) {
        if (!state.playing) { playingImg = null; return@LaunchedEffect }
        while (state.playing) {
            val shot = gs.capture?.invoke()
            if (shot != null) {
                playingImg = withContext(Dispatchers.Default) { ScopeMath.render(shot, kind).asImageBitmap() }
                live = true
            } else {
                // no video surface to copy: still at the playhead
                val uri = c.uri
                val s2 = c.srcAt(state.playhead)
                val frame = uri?.let { Frames.load(ctx, it, s2, exact = false) }
                if (frame != null) playingImg = withContext(Dispatchers.Default) {
                    val small = Bitmap.createScaledBitmap(frame.asAndroidBitmap(), 256, (256f * frame.height / frame.width).roundToInt().coerceAtLeast(1), true)
                    ScopeMath.render(look.applyTo(grade?.at(s2)?.applyTo(small) ?: small), kind).asImageBitmap()
                }
                live = false
            }
            delay(250)
        }
    }
    val img = if (state.playing) playingImg ?: still else still
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(150.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0C0F)).testTag("scope")) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = if (kind == 3) ContentScale.Fit else ContentScale.FillBounds) }
        Canvas(Modifier.fillMaxSize()) {
            if (kind == 3) {
                val r = min(size.width, size.height) / 2f * 0.95f
                drawCircle(Color(0x44FFFFFF), r, center, style = Stroke(1f))
                drawCircle(Color(0x22FFFFFF), r * 0.75f, center, style = Stroke(1f))
                // the skin tone line (approx. 123°)
                val a = Math.toRadians(123.0)
                drawLine(Color(0x55FFB77C), center, Offset(center.x + (cos(a) * r).toFloat(), center.y - (sin(a) * r).toFloat()), 1f)
            } else {
                for (i in 1..3) drawLine(Color(0x22FFFFFF), Offset(0f, size.height * i / 4f), Offset(size.width, size.height * i / 4f), 1f)
            }
        }
        if (state.playing && live) Text(stringResource(R.string.colorpage_live), color = K.Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** Source time (s) for the playhead in a clip on the color page. */
internal fun ColorClip.srcAt(playhead: Float): Double {
    hold?.let { return it }
    val rel = (playhead.coerceIn(start, end) - start).toDouble() * abs(speed)
    return if (reverse) srcIn + (end - start) * abs(speed) - rel else srcIn + rel
}

/**
 * The dropper: the color (average of 5×5 pixels) in the node's input – after the nodes before it – around position [x],[y].
 * Returns a qualifier around that color, or null if the image can't be read.
 */
suspend fun pickColor(ctx: android.content.Context, c: ColorClip, playhead: Float, g: ClipGrade, nodeId: Int, x: Float, y: Float): se.gendut.klipp.engine.Qualifier? =
    pickInput(ctx, c, playhead, g, nodeId, x, y)?.let { se.gendut.klipp.engine.Qualifier.around(it[0], it[1], it[2]) }

/** Dropper white balance: the node's temperature and tint so the color where tapped becomes gray. */
suspend fun pickWhite(ctx: android.content.Context, c: ColorClip, playhead: Float, g: ClipGrade, nodeId: Int, x: Float, y: Float): GradeNode? {
    val n = g.nodes.firstOrNull { it.id == nodeId } ?: return null
    val rgb = pickInput(ctx, c, playhead, g, nodeId, x, y) ?: return null
    val (t, u) = withContext(Dispatchers.Default) { GradeNode.whiteBalance(n, rgb) }
    return n.copy(temp = t, tint = u)
}

/** The average color (5×5 pixels) in the node's input – after the input and the nodes before it – around position [x],[y]. */
suspend fun pickInput(ctx: android.content.Context, c: ColorClip, playhead: Float, g: ClipGrade, nodeId: Int, x: Float, y: Float): FloatArray? {
    val uri = c.uri ?: return null
    val sec = c.srcAt(playhead)
    val frame = Frames.load(ctx, uri, sec, exact = true) ?: return null
    return withContext(Dispatchers.Default) {
        val b = frame.asAndroidBitmap()
        val before = g.at(sec).chain.takeWhile { it.id != nodeId }.filter { !it.isNeutral }.map { it.params() }
        val aspect = b.width.toFloat() / b.height
        // the program image is stabilized (moved, rotated, zoomed): the tap is mapped back to the source image point
        var sx = x * b.width
        var sy = y * b.height
        g.stab?.let { st ->
            val inv = st.transform(sec, b.width.toFloat(), b.height.toFloat()).inverse()
            val q = inv.map(sx - b.width / 2f, sy - b.height / 2f)
            sx = q[0] + b.width / 2f; sy = q[1] + b.height / 2f
        }
        val cx = sx.roundToInt().coerceIn(0, b.width - 1)
        val cy = sy.roundToInt().coerceIn(0, b.height - 1)
        val acc = FloatArray(3)
        var n = 0
        val rgb = FloatArray(3)
        for (dy in -2..2) for (dx in -2..2) {
            val px = (cx + dx).coerceIn(0, b.width - 1); val py = (cy + dy).coerceIn(0, b.height - 1)
            val p = b.getPixel(px, py)
            rgb[0] = ((p shr 16) and 255) / 255f; rgb[1] = ((p shr 8) and 255) / 255f; rgb[2] = (p and 255) / 255f
            for (pp in before) GradeNode.apply(pp, rgb, px.toFloat() / b.width, py.toFloat() / b.height, aspect)
            acc[0] += rgb[0]; acc[1] += rgb[1]; acc[2] += rgb[2]; n++
        }
        floatArrayOf(acc[0] / n, acc[1] / n, acc[2] / n)
    }
}

/** Still for the gallery: the clip's frame at the playhead with grade and look (like the program image). */
suspend fun grabStill(ctx: android.content.Context, c: ColorClip, playhead: Float, g: ClipGrade, look: se.gendut.klipp.engine.Look): Bitmap? {
    val uri = c.uri ?: return null
    val sec = c.srcAt(playhead)
    val frame = Frames.load(ctx, uri, sec, exact = true) ?: return null
    return withContext(Dispatchers.Default) {
        val b = frame.asAndroidBitmap()
        val w = min(960, b.width)
        val small = Bitmap.createScaledBitmap(b, w, (w.toFloat() * b.height / b.width).roundToInt().coerceAtLeast(1), true)
        look.applyTo(g.at(sec).applyTo(small))
    }
}

/** Scope computation on a small bitmap (pure logic, testable). */
object ScopeMath {
    fun render(src: Bitmap, kind: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        return when (kind) {
            3 -> vector(px)
            else -> wave(px, w, h, parade = kind == 2)
        }
    }

    private fun wave(px: IntArray, w: Int, h: Int, parade: Boolean): Bitmap {
        val ow = if (parade) w * 3 else w
        val oh = 128
        val acc = Array(3) { FloatArray(ow * oh) }
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            if (parade) {
                acc[0][(127 - r / 2) * ow + x] += 1f
                acc[1][(127 - g / 2) * ow + x + w] += 1f
                acc[2][(127 - b / 2) * ow + x + 2 * w] += 1f
            } else {
                val l = ((0.2126f * r + 0.7152f * g + 0.0722f * b) / 2).toInt().coerceIn(0, 127)
                acc[0][(127 - l) * ow + x] += 1f
            }
        }
        val out = IntArray(ow * oh)
        val k = 255f / (h * 0.06f).coerceAtLeast(1f)
        for (i in out.indices) {
            if (parade) {
                val r = min(255f, acc[0][i] * k).toInt(); val g = min(255f, acc[1][i] * k).toInt(); val b = min(255f, acc[2][i] * k).toInt()
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                val v = min(255f, acc[0][i] * k).toInt()
                out[i] = (0xFF shl 24) or ((v * 0.75f).toInt() shl 16) or (v shl 8) or ((v * 0.8f).toInt())
            }
        }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }

    private fun vector(px: IntArray): Bitmap {
        val n = 128
        val acc = FloatArray(n * n)
        for (c in px) {
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val cb = (b - y) / 1.8556f
            val cr = (r - y) / 1.5748f
            val x = (n / 2 + cb * n * 0.95f).toInt().coerceIn(0, n - 1)
            val yy = (n / 2 - cr * n * 0.95f).toInt().coerceIn(0, n - 1)
            acc[yy * n + x] += 1f
        }
        val k = 255f / (px.size * 0.004f).coerceAtLeast(1f)
        val out = IntArray(n * n) { i ->
            val v = min(255f, acc[i] * k).toInt()
            (0xFF shl 24) or ((v * 0.8f).toInt() shl 16) or (v shl 8) or ((v * 0.85f).toInt())
        }
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
    }

    /** The fraction of the image with luminance in each tenth (for tests). */
    fun lumaHistogram(src: Bitmap): FloatArray {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hist = FloatArray(10)
        for (c in px) {
            val l = (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
            hist[(l * 10).toInt().coerceIn(0, 9)] += 1f / px.size
        }
        return hist
    }
}
