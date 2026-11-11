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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
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
import se.gendut.klipp.engine.HslCurves
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
    /** Titles pinned by their corners (title id → pin), see CornerPin. */
    val pins = androidx.compose.runtime.mutableStateMapOf<Long, se.gendut.klipp.engine.CornerPin>()
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
        if (record) siblings?.let { sib -> for ((id, g) in changes0) for (o in sib(id)) if (o !in changes0) changes[o] = g.forOtherClip(of(o).stab, of(o).layer, of(o).mblur) }
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
    /** The picture compared against when [compare] is [Still.ORIGINAL]: the clip at the playhead without its grade. */
    var compareImage by mutableStateOf<ImageBitmap?>(null)
    /** The scope over the picture: 0 off, 1 waveform, 2 parade, 3 vectorscope. */
    var scopeKind by mutableStateOf(0)
    /** The clips the copied grade has been pasted to (the wheels' button offers pasting once per clip). */
    val pasted = androidx.compose.runtime.mutableStateListOf<Long>()
    /** The program image as displayed (PixelCopy) – for scopes during playback. */
    var capture: (suspend () -> Bitmap?)? = null

    /** A node with new values, live (while dragging) and committed respectively. */
    fun liveNode(clip: Long, n: GradeNode) {
        val g = shown(clip) ?: ClipGrade()
        setLive(clip, g.copy(nodes = g.nodes.map { if (it.id == n.id) n else it }))
    }
    /** Draws the paused player's frame again (set by the program monitor). */
    var redraw: (() -> Unit)? = null
    private val previewNext = java.util.concurrent.atomic.AtomicReference<Pair<Long, GradeNode>?>(null)
    private val previewRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * A node's new values straight into the paused player, before they are committed – for what only the player shows
     * (the film's halation, grain, diffusion). The values are prepared off the main thread, only the latest is used
     * (a drag never queues up); no undo step – the commit follows.
     */
    fun previewNode(clip: Long, n: GradeNode) {
        previewNext.set(clip to n)
        if (!previewRunning.compareAndSet(false, true)) return
        Thread({
            try {
                while (true) {
                    val (c, nn) = previewNext.getAndSet(null) ?: break
                    runCatching { se.gendut.klipp.engine.LiveGrades.put(c, nn) }
                    main.post { redraw?.invoke() }
                }
            } finally {
                previewRunning.set(false)
                // a value that arrived just as the loop ended
                previewNext.get()?.let { (c, nn) -> previewNode(c, nn) }
            }
        }, "grade-preview").apply { isDaemon = true }.start()
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

    fun setPin(title: Long, p: se.gendut.klipp.engine.CornerPin?) {
        val before = pins[title]
        putPin(title, p)
        if (before != p) history?.invoke(Str.t(R.string.colorpage_history_pin), { putPin(title, before) }, { putPin(title, p) })
    }

    /** The corners while they are dragged (no undo step; [commitPin] when the finger lifts). */
    fun livePin(title: Long, p: se.gendut.klipp.engine.CornerPin) = putPin(title, p)

    /** After live drags: [p] becomes one undo step back to [before]. */
    fun commitPin(title: Long, p: se.gendut.klipp.engine.CornerPin?, before: se.gendut.klipp.engine.CornerPin?) {
        putPin(title, p)
        if (before != p) history?.invoke(Str.t(R.string.colorpage_history_pin), { putPin(title, before) }, { putPin(title, p) })
    }

    private fun putPin(title: Long, p: se.gendut.klipp.engine.CornerPin?) {
        if (p == null) pins.remove(title) else pins[title] = p
        revision++
    }

    fun toJson(): JSONObject = JSONObject().also { o ->
        grades.forEach { (id, g) -> o.put(id.toString(), g.toJson()) }
        // (keys that are not clip ids are skipped by older versions)
        if (follows.isNotEmpty()) o.put("_follow", JSONObject().also { f -> follows.forEach { (t, v) -> f.put(t.toString(), v.encode()) } })
        if (pins.isNotEmpty()) o.put("_pin", JSONObject().also { f -> pins.forEach { (t, v) -> f.put(t.toString(), v.toJson()) } })
    }
    fun load(o: JSONObject?) {
        grades.clear()
        follows.clear()
        pins.clear()
        o?.keys()?.forEach { k -> k.toLongOrNull()?.let { grades[it] = ClipGrade.fromJson(o.optJSONObject(k)) } }
        o?.optJSONObject("_follow")?.let { f -> f.keys().forEach { k -> k.toLongOrNull()?.let { t -> se.gendut.klipp.engine.Follow.parse(f.optString(k))?.let { follows[t] = it } } } }
        o?.optJSONObject("_pin")?.let { f -> f.keys().forEach { k -> k.toLongOrNull()?.let { t -> se.gendut.klipp.engine.CornerPin.fromJson(f.optJSONObject(k))?.let { pins[t] = it } } } }
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

/** The clip the color page works on: the selected one, else the topmost under the playhead, else the first. */
internal fun currentColorClip(clips: List<ColorClip>, state: EditorState): ColorClip? =
    clips.firstOrNull { it.id == state.selected }
        ?: clips.filter { state.playhead >= it.start && state.playhead < it.end }.minByOrNull { it.track }
        ?: clips.firstOrNull()

/**
 * The color page: the clips in a strip, the clip's nodes in a row, and under them the selected node's tools – a rail
 * of tools on the left and the tool itself beside it. The picture above follows while dragging. The grid button at
 * the end of the node row opens the gallery, the grade actions for the clip and the timeline's look (the shared look
 * applied after the clips' grades). Compare, scopes and full screen lie over the picture ([ColorViewerTools]).
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
    // derived: the page recomposes only when the clip changes, not every frame during playback
    val current = remember(clips) { derivedStateOf { currentColorClip(clips, state) } }.value
    var nodeId by remember(current?.id) { mutableStateOf(gs.of(current?.id ?: -1).nodes.first().id) }
    // the tool (0 wheels, 1 curves, 2 adjust, 3 LUT, 4 qualifier, 5 window, 6 film) lives in GradeState: it stays
    // when the page is left and when the neighboring pages are composed in during a swipe
    var editor by gs::editor
    // the grid button: the gallery, the clip's grade and the timeline's look instead of the node's tools
    var more by rememberSaveable { mutableStateOf(false) }
    var nodeMenu by remember { mutableStateOf<GradeNode?>(null) }
    var stillMenu by remember { mutableStateOf<Still?>(null) }
    var renaming by remember { mutableStateOf<GradeNode?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { runCatching { StillStore.init(ctx) } }
    // when leaving the color page: no color tools left on top of the program image
    androidx.compose.runtime.DisposableEffect(gs) {
        onDispose { gs.compare = null; gs.compareImage = null; gs.showKey = null; gs.pick = null; gs.windowEdit = null }
    }
    val g = current?.let { gs.shown(it.id) ?: ClipGrade() }
    val node = g?.let { cg -> cg.nodes.firstOrNull { it.id == nodeId } ?: cg.nodes.first() }

    Column(Modifier.fillMaxSize().testTag("colorPage")) {
        ClipStrip(clips, current?.id, gs) { c ->
            haptics.tick()
            state.selected = c.id
            state.playing = false
            if (state.playhead < c.start || state.playhead >= c.end) state.playhead = (c.start + c.end) / 2
        }
        NodeRow(
            current, g, node?.id, more,
            onPick = { haptics.tick(); nodeId = it; more = false },
            onMenu = { haptics.heavy(); nodeMenu = it },
            onAdd = {
                val id = current?.id ?: return@NodeRow
                haptics.confirm()
                val cg = gs.of(id)
                val nid = (cg.nodes.maxOf { it.id }) + 1
                gs.set(id, cg.copy(nodes = cg.nodes + GradeNode(nid, "")))
                nodeId = nid; more = false
            },
            onMore = { haptics.tick(); more = !more },
        )
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(K.Surface).padding(top = 10.dp)) {
            when {
                more -> ColorMore(state, gs, clips, current, look, setNode = { nodeId = it }, onStillMenu = { haptics.heavy(); stillMenu = it }, onDone = { more = false }, timeline = timeline)
                current == null || node == null -> NoClip()
                else -> NodeTools(state, gs, current, node, editor, { editor = it }, onImportLut, setNode = { nodeId = it })
            }
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
                        stillMenu = null; gs.set(cid, st.grade.forOtherClip(gs.of(cid).stab, gs.of(cid).layer, gs.of(cid).mblur)); nodeId = st.grade.nodes.first().id
                    }
                    NodeMenuRow(if (gs.compare?.id == st.id) stringResource(R.string.colorpage_stop_comparing) else stringResource(R.string.colorpage_compare_split)) {
                        stillMenu = null; gs.compare = if (gs.compare?.id == st.id) null else st
                    }
                    NodeMenuRow(stringResource(R.string.colorpage_delete_still)) {
                        stillMenu = null; if (gs.compare?.id == st.id) gs.compare = null; StillStore.delete(st)
                    }
                }
            },
            confirmButton = { FlatTextButton(onClick = { stillMenu = null }) { Text(stringResource(R.string.colorpage_close)) } },
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
                    NodeMenuRow(stringResource(R.string.colorpage_node_rename)) { nodeMenu = null; renaming = n }
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
            confirmButton = { FlatTextButton(onClick = { nodeMenu = null }) { Text(stringResource(R.string.colorpage_close)) } },
            containerColor = K.SurfaceHigh,
        )
    }

    renaming?.let { n ->
        val cid = current?.id
        // the name as shown (a saved "Primär" reads in the app's language); empty = the default "Node"
        var name by remember(n.id) { mutableStateOf(Str.name(n.label)) }
        fun save() {
            renaming = null
            cid?.let { id -> gs.set(id, gs.of(id).let { cg -> cg.copy(nodes = cg.nodes.map { if (it.id == n.id) it.copy(label = name.trim()) else it }) }) }
        }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.colorpage_node_rename)) },
            text = { TextInput(stringResource(R.string.colorpage_node_name), name, focus = true) { name = it.take(40) } },
            confirmButton = { FlatTextButton(onClick = { save() }, Modifier.testTag("nodeRenameSave")) { Text(stringResource(R.string.colorpage_save)) } },
            dismissButton = { FlatTextButton(onClick = { renaming = null }) { Text(stringResource(R.string.colorpage_cancel)) } },
            containerColor = K.SurfaceHigh,
        )
    }
}

@Composable
private fun NoClip() {
    Text(stringResource(R.string.colorpage_no_clip), color = K.OnSurfaceVar, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

/** The clips in timeline order: small pictures, a ring around the one being graded and a dot on those with a grade. */
@Composable
private fun ClipStrip(clips: List<ColorClip>, current: Long?, gs: GradeState, onPick: (ColorClip) -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // the clip being graded stays in view (two clips' room before it)
    val at = clips.indexOfFirst { it.id == current }
    LaunchedEffect(at) { if (at >= 0) scroll.animateScrollTo(with(density) { (70.dp * (at - 2)).roundToPx() }.coerceAtLeast(0)) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).sideScroll(scroll).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((i, c) in clips.withIndex()) key(c.id) {
            ClipThumb(i + 1, c, c.id == current, graded = gs.grades[c.id]?.isNeutral == false) { onPick(c) }
        }
    }
}

@Composable
private fun ClipThumb(n: Int, c: ColorClip, selected: Boolean, graded: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, c.uri, c.srcIn) { value = c.uri?.let { Thumbs.load(ctx, it, c.srcIn + 0.2) } }
    val ring by animateColorAsState(if (selected) K.Primary else K.Primary.copy(alpha = 0f), label = "ct")
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(64.dp, 44.dp).clip(shape).background(K.SurfaceHighest)
            .clickable(role = Role.Tab, onClickLabel = c.name, onClick = onClick).semantics { this.selected = selected }.testTag("colorClip:${c.id}"),
    ) {
        val i = img
        if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // (no picture to show: the clip's number)
        else Text("$n", color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.matchParentSize().border(3.dp, ring, shape))
        if (graded) Box(Modifier.align(Alignment.TopEnd).padding(if (selected) 7.dp else 5.dp).size(7.dp).clip(CircleShape).background(K.OnSurface))
    }
}

/**
 * The clip's nodes in a row, joined by short wires: the number and the name, the selected one round and in the accent's
 * container color. Long press = menu. The outlined plus adds a node at the end; the grid button at the far right
 * stays put and opens the gallery, the clip's grade and the timeline's look.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    clip: ColorClip?, g: ClipGrade?, selected: Int?, more: Boolean,
    onPick: (Int) -> Unit, onMenu: (GradeNode) -> Unit, onAdd: () -> Unit, onMore: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).sideScroll(), verticalAlignment = Alignment.CenterVertically) {
            if (clip != null && g != null) {
                val cacheStatus = se.gendut.klipp.engine.NodeCache.status(clip.id)
                for ((i, n) in g.nodes.withIndex()) key(n.id) {
                    if (i > 0) Wire()
                    NodeChip(n, n.id == selected && !more, { onPick(n.id) }, { onMenu(n) },
                        cacheText = if (n.cache) cacheStatus ?: stringResource(R.string.colorpage_cache) else null)
                }
                Wire()
                val src = remember { MutableInteractionSource() }
                Box(
                    Modifier.pressPop(src, 0.88f).size(44.dp).clip(RoundedCornerShape(14.dp)).border(1.5.dp, K.Outline, RoundedCornerShape(14.dp))
                        .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onAdd).testTag("addNode"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Ic.Plus, stringResource(R.string.colorpage_new_node), tint = K.OnSurface, modifier = Modifier.size(18.dp)) }
            }
        }
        Spacer(Modifier.width(6.dp))
        val src = remember { MutableInteractionSource() }
        val bg by animateColorAsState(if (more) K.PrimaryContainer else K.PrimaryContainer.copy(alpha = 0f), label = "gm")
        Box(
            Modifier.pressPop(src, 0.88f).size(44.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onMore).testTag("colorMore"),
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.Grid, stringResource(R.string.design_color_more), tint = if (more) K.PrimarySoft else K.OnSurface, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun Wire() = Box(Modifier.width(8.dp).height(2.dp).background(K.OutlineVar))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeChip(n: GradeNode, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit, cacheText: String? = null) {
    val src = remember { MutableInteractionSource() }
    // selected: rounder and in the container color – shape and color show the selection
    val r by animateDpAsState(if (selected) 22.dp else 14.dp, spring(0.55f, 600f), label = "nr")
    val pad by animateDpAsState(if (selected) 16.dp else 12.dp, spring(0.55f, 600f), label = "np")
    val bg by animateColorAsState(if (selected) K.PrimaryContainer else K.SurfaceHigh, label = "nb")
    val fg = if (selected) K.PrimarySoft else K.OnSurface
    val weight = if (selected) FontWeight.Bold else FontWeight.SemiBold
    Row(
        Modifier.pressPop(src, 0.92f).graphicsLayer { alpha = if (n.enabled) 1f else 0.45f }
            .height(44.dp).clip(RoundedCornerShape(r)).background(bg)
            .combinedClickable(interactionSource = src, indication = null, onLongClick = onLong, onClick = onClick)
            .padding(horizontal = pad)
            .testTag("node:${n.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${n.id}", color = if (selected) fg else K.OnSurfaceVar, fontSize = 13.sp, fontWeight = weight, style = TextStyle(fontFeatureSettings = "tnum"))
        Text(n.label.ifBlank { Str.t(if (n.isNeutral) R.string.colorpage_node_neutral else R.string.colorpage_node_default_label) }.let(Str::name),
            color = fg, fontSize = 13.sp, fontWeight = weight, maxLines = 1)
        // the node cache: status (cached / caching 40 % / queued)
        if (cacheText != null) Text(cacheText, color = K.Teal, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.testTag("nodeCache:${n.id}"))
    }
}

/** The selected node's tools: the rail of tools on the left, the tool itself beside it (scrolls when it is tall). */
@Composable
private fun NodeTools(
    state: EditorState,
    gs: GradeState,
    current: ColorClip,
    node: GradeNode,
    editor: Int,
    setEditor: (Int) -> Unit,
    onImportLut: (((String) -> Unit) -> Unit)?,
    setNode: (Int) -> Unit,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
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
        if (editor != 4 && editor != 2 && editor != 1 && editor != 0) gs.pick = null
        if (editor != 2 && editor != 0) wbPicking = false
        onDispose { gs.windowEdit = null; gs.pick = null }
    }
    // white balance: tap something that should be white or gray
    val wbOn = gs.pick != null && wbPicking
    fun pickWb() {
        if (gs.pick != null) { gs.pick = null; wbPicking = false; return }
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
    }
    // a grade copied from another clip is offered once: the button pastes it here, then copies again
    val canPaste = gs.clipboard != null && gs.clipboardFrom != current.id && current.id !in gs.pasted
    fun copyOrPaste() {
        haptics.confirm()
        val cb = gs.clipboard
        if (canPaste && cb != null) {
            // another clip gets the color – not the copied clip's stabilization and tracking
            val g = cb.forOtherClip(gs.of(current.id).stab, gs.of(current.id).layer, gs.of(current.id).mblur)
            gs.set(current.id, g); gs.pasted.add(current.id); setNode(g.nodes.first().id)
        } else {
            gs.clipboard = gs.of(current.id); gs.clipboardFrom = current.id; gs.pasted.clear()
        }
    }

    val tabs = listOf(
        ToolTab(stringResource(R.string.colorpage_tool_wheels), Ic.Wheels, "editor:Hjul", marked = node.hasLog || !(node.lift.isNeutral && node.gamma.isNeutral && node.gain.isNeutral && node.offset.isNeutral)),
        ToolTab(stringResource(R.string.colorpage_tool_curves), Ic.Curves, "editor:Kurvor", marked = node.hasCurves || node.hasHsl),
        ToolTab(stringResource(R.string.colorpage_tool_adjust), Ic.Sliders, "editor:Justera", marked = node.contrast != 1f || node.sat != 1f || node.density != 0f || node.hue != 0f || node.temp != 0f || node.tint != 0f || node.mix < 1f),
        ToolTab(stringResource(R.string.colorpage_tool_lut), Ic.Cube, "editor:LUT", marked = node.lut != null || node.drt != null),
        ToolTab(stringResource(R.string.colorpage_tool_qualifier), Ic.Dropper, "editor:Kvalificerare", marked = node.qual.on),
        ToolTab(stringResource(R.string.colorpage_tool_window), Ic.Window, "editor:Fönster", marked = node.win.on),
        ToolTab(stringResource(R.string.colorpage_tool_film), Ic.Film, "editor:Film", marked = node.film != null),
    )
    Row(Modifier.fillMaxSize()) {
        ToolRail(tabs, editor, setEditor, dense = true)
        // each tool starts at its top; the column lies right of undo and redo, so it only needs air at its end
        val scroll = key(editor) { rememberScrollState() }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).testTag("colorScroll").padding(bottom = 16.dp)) {
            AnimatedContent(editor, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "editor") { e ->
                when (e) {
                    0 -> Flush { WheelsPanel(node, onLive = ::live, onCommit = ::commit, wbOn = wbOn, onWb = ::pickWb, canPaste = canPaste, onCopyPaste = ::copyOrPaste) }
                    1 -> CurvesEditor(node, onLive = ::live, onCommit = ::commit, picking = gs.pick != null, onPick = { got ->
                        if (got == null) { gs.pick = null; return@CurvesEditor }
                        val clip = current
                        val nid = node.id
                        gs.pickHint = Str.t(R.string.colorpage_pick_hint_curve)
                        gs.pick = { x, y ->
                            scope.launch {
                                val rgb = pickHsl(ctx, clip, state.playhead, gs.of(clip.id), nid, x, y) ?: return@launch
                                haptics.confirm()
                                got(rgb)
                            }
                        }
                    })
                    3 -> NodeLutEditor(node, onLive = ::live, onCommit = ::commit, onImport = onImportLut)
                    6 -> FilmEditor(node, onCommit = ::commit, onLive = { gs.previewNode(current.id, it) })
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
                    5 -> Column {
                        // the window follows over time: the playhead for the clip, with the tracked stretch marked
                        ClipScrubber(state, current, node.win.takeIf { it.on }?.track?.let { it.t0..it.end }, Modifier.padding(bottom = 6.dp))
                        WindowEditor(current.id, node, gs, onLive = ::live, onCommit = ::commit, colorClip = current, playhead = { state.playhead }, seek = { state.playing = false; state.playhead = it })
                    }
                    else -> Flush {
                        PillButton(if (wbOn) stringResource(R.string.colorpage_wb_tap) else stringResource(R.string.colorpage_wb_dropper), ::pickWb,
                            Modifier.padding(vertical = 6.dp).testTag("adj:wb"), icon = Ic.Dropper, filled = false, on = wbOn)
                        SectionLabel(stringResource(R.string.colorpage_group_balance))
                        PropSlider(stringResource(R.string.colorpage_temperature), node.temp, -1f, 1f, 0f, { "%+.0f".format(it * 100) }, "temp", onLive = { live(node.copy(temp = it)) }) { commit(node.copy(temp = it)) }
                        PropSlider(stringResource(R.string.colorpage_tint), node.tint, -1f, 1f, 0f, { "%+.0f".format(it * 100) }, "tint", onLive = { live(node.copy(tint = it)) }) { commit(node.copy(tint = it)) }
                        SectionLabel(stringResource(R.string.colorpage_group_tone))
                        PropSlider(stringResource(R.string.colorpage_contrast), node.contrast, 0f, 2f, 1f, { "%.2f".format(it) }, "contrast", onLive = { live(node.copy(contrast = it)) }) { commit(node.copy(contrast = it)) }
                        PropSlider(stringResource(R.string.colorpage_pivot), node.pivot, 0f, 1f, 0.435f, { "%.3f".format(it) }, "pivot", onLive = { live(node.copy(pivot = it)) }) { commit(node.copy(pivot = it)) }
                        SectionLabel(stringResource(R.string.colorpage_group_color))
                        PropSlider(stringResource(R.string.colorpage_saturation), node.sat, 0f, 2f, 1f, { "%.0f".format(it * 50) }, "sat", onLive = { live(node.copy(sat = it)) }) { commit(node.copy(sat = it)) }
                        PropSlider(stringResource(R.string.colorpage_hue), node.hue, -180f, 180f, 0f, { "%.0f°".format(it) }, "hue", onLive = { live(node.copy(hue = it)) }) { commit(node.copy(hue = it)) }
                        PropSlider(stringResource(R.string.colorpage_film_density), node.density * 100f, 0f, 100f, 0f, { "%.0f".format(it) }, "density", onLive = { live(node.copy(density = it / 100f)) }) { commit(node.copy(density = it / 100f)) }
                        SectionLabel(stringResource(R.string.colorpage_group_finish))
                        PropSlider(stringResource(R.string.colorpage_noise_reduction), node.denoise * 100f, 0f, 100f, 0f, { "%.0f".format(it) }, "denoise", onLive = { live(node.copy(denoise = it / 100f)) }) { commit(node.copy(denoise = it / 100f)) }
                        PropSlider(stringResource(R.string.colorpage_node_mix), node.mix, 0f, 1f, 1f, { "%.0f %%".format(it * 100) }, "mix", onLive = { live(node.copy(mix = it)) }) { commit(node.copy(mix = it)) }
                    }
                }
            }
        }
    }
}

/** The tools drawn for this page lie flush with the rail; the shared editors keep their own margins. */
@Composable
private fun Flush(content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalSliderInset provides 0.dp) {
        Column(Modifier.padding(start = 8.dp, end = 12.dp), content = content)
    }
}

/**
 * What the grid button opens instead of the node's tools: the gallery (stills to compare against and take grades
 * from), the grade actions for the clip, and the timeline's look.
 */
@Composable
private fun ColorMore(
    state: EditorState,
    gs: GradeState,
    clips: List<ColorClip>,
    current: ColorClip?,
    look: se.gendut.klipp.engine.Look,
    setNode: (Int) -> Unit,
    onStillMenu: (Still) -> Unit,
    onDone: () -> Unit,
    timeline: @Composable () -> Unit,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val names = listOf(stringResource(R.string.colorpage_gallery), stringResource(R.string.colorpage_clip_grade), stringResource(R.string.colorpage_tab_timeline))
    val tags = listOf("lower:gallery", "lower:grade", "colorTab:timeline")
    Column(Modifier.fillMaxSize()) {
        SheetHeader(names[tab], onDone = onDone)
        ConnectedToggleGroup(names, tab, { tab = it }, Modifier.padding(horizontal = 12.dp).fillMaxWidth(), height = 44.dp, tag = { tags[it] })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("colorScroll").padding(top = 8.dp, bottom = LocalBarSpace.current + 26.dp)) {
            when (tab) {
                0 -> if (current == null) NoClip() else {
                    StillsRow(gs, onGrab = {
                        val clip = current
                        scope.launch {
                            val img = grabStill(ctx, clip, state.playhead, gs.of(clip.id), look) ?: return@launch
                            haptics.confirm()
                            runCatching { StillStore.add(img, clip.name, gs.of(clip.id)) }
                        }
                    }, onMenu = onStillMenu)
                    Text(stringResource(R.string.colorpage_gallery_hint), color = K.Outline, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                1 -> if (current == null) NoClip() else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    ActionTile(stringResource(R.string.colorpage_copy), Ic.Copy, Modifier.weight(1f).testTag("grade:copy")) {
                        haptics.confirm(); gs.clipboard = gs.of(current.id); gs.clipboardFrom = current.id; gs.pasted.clear()
                    }
                    ActionTile(stringResource(R.string.colorpage_paste), Ic.Paste, Modifier.weight(1f).testTag("grade:paste"), enabled = gs.clipboard != null) {
                        gs.clipboard?.let {
                            haptics.confirm()
                            // another clip gets the color – not the copied clip's stabilization and tracking
                            val g = if (gs.clipboardFrom == current.id) it else it.forOtherClip(gs.of(current.id).stab, gs.of(current.id).layer, gs.of(current.id).mblur)
                            gs.set(current.id, g); setNode(g.nodes.first().id)
                        }
                    }
                    ActionTile(stringResource(R.string.colorpage_all_clips), Ic.Layers, Modifier.weight(1f).testTag("grade:all")) {
                        haptics.confirm()
                        val cg = gs.of(current.id)
                        gs.setAll(clips.filter { it.id != current.id }.associate { it.id to cg.forOtherClip(gs.of(it.id).stab, gs.of(it.id).layer, gs.of(it.id).mblur) })
                    }
                    ActionTile(stringResource(R.string.colorpage_reset), Ic.Reset, Modifier.weight(1f).testTag("grade:reset"), danger = true) { haptics.heavy(); gs.set(current.id, ClipGrade()); setNode(1) }
                    // (stabilization and motion blur are on the clip: Picture in the edit page)
                }
                else -> {
                    // grade on the original files instead of the proxy (full resolution, no transcoding) – applies app-wide
                    val px = se.gendut.klipp.engine.Proxies
                    ToggleChip(stringResource(R.string.colorpage_original), !px.enabled, { haptics.tick(); px.switch(!px.enabled) }, Modifier.padding(start = 16.dp, bottom = 4.dp).testTag("colorOriginal"))
                    timeline()
                }
            }
        }
    }
}

/** Scope names (string resources, resolved at use) and icons. */
private val SCOPES = listOf(R.string.colorpage_scope_none to Ic.EyeOff, R.string.colorpage_scope_waveform to Ic.Waveform, R.string.colorpage_scope_parade to Ic.Parade, R.string.colorpage_scope_vectorscope to Ic.Vectorscope)

/**
 * What the color page lays over the picture: Compare and Scope at the bottom left, full screen at the bottom right,
 * and the chosen scope over the picture's top left. Compare splits the picture against the clip without its grade
 * (drag the line); a still from the gallery is compared the same way from there.
 */
@Composable
fun ColorViewerTools(
    state: EditorState,
    gs: GradeState,
    clips: List<ColorClip>,
    look: se.gendut.klipp.engine.Look,
    modifier: Modifier = Modifier,
    onFullscreen: (() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    val ctx = LocalContext.current
    val clip = remember(clips) { derivedStateOf { currentColorClip(clips, state) } }.value
    val comparing = gs.compare != null
    if (gs.compare?.id == Still.ORIGINAL && clip != null) {
        // the clip without its grade at the playhead: while stopped, in fifths of a second (follows a scrub)
        val tick = remember(clip) { derivedStateOf { (clip.srcAt(state.playhead) * 5).roundToInt() } }.value
        LaunchedEffect(clip, tick, state.playing) {
            if (state.playing) return@LaunchedEffect
            delay(60)
            val bare = gs.of(clip.id).copy(nodes = listOf(GradeNode(1)))
            grabStill(ctx, clip, state.playhead, bare, se.gendut.klipp.engine.Look.NONE)?.let { gs.compareImage = it.asImageBitmap() }
        }
    }
    Box(modifier.fillMaxSize()) {
        if (gs.scopeKind > 0 && clip != null) Scopes(clip, state, gs, gs.scopeKind, look, Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth(0.48f).fillMaxHeight(0.62f))
        // (the dropper has the whole picture while it picks)
        if (gs.pick == null) {
            Row(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ViewerChip(stringResource(R.string.design_color_compare), Ic.Compare, comparing, "colorCompare") {
                    haptics.tick()
                    if (comparing) { gs.compare = null; gs.compareImage = null }
                    else if (clip != null) { gs.compareImage = null; gs.wipe = 0.5f; gs.compare = Still(Still.ORIGINAL, clip.name, 0L, ClipGrade()) }
                }
                var open by remember { mutableStateOf(false) }
                Box {
                    ViewerChip(stringResource(R.string.design_color_scope), Ic.Scope, gs.scopeKind > 0 || open, "scopes") { haptics.tick(); open = true }
                    androidx.compose.material3.DropdownMenu(
                        expanded = open, onDismissRequest = { open = false },
                        shape = RoundedCornerShape(20.dp), containerColor = K.SurfaceHighest,
                        offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
                    ) {
                        for ((i, sc) in SCOPES.withIndex()) {
                            val sel = i == gs.scopeKind
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(sc.first), color = if (sel) K.PrimarySoft else K.OnSurface, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium) },
                                leadingIcon = { Icon(sc.second, null, tint = if (sel) K.PrimarySoft else K.OnSurfaceVar, modifier = Modifier.size(20.dp)) },
                                trailingIcon = if (sel) ({ Icon(Ic.Check, null, tint = K.Primary, modifier = Modifier.size(18.dp)) }) else null,
                                onClick = { haptics.tick(); gs.scopeKind = i; open = false },
                                modifier = Modifier.testTag("scope:$i"),
                            )
                        }
                    }
                }
            }
            if (onFullscreen != null) {
                val src = remember { MutableInteractionSource() }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp).pressPop(src, 0.88f).size(44.dp)
                        .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onFullscreen).testTag("colorFullscreen"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x990F1012)), contentAlignment = Alignment.Center) {
                        Icon(Ic.Fullscreen, stringResource(R.string.design_color_fullscreen), tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** A pill over the picture: dark and see-through, in the accent's container color while its tool is on. */
@Composable
private fun ViewerChip(label: String, icon: ImageVector, on: Boolean, tag: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (on) K.PrimaryContainer else Color(0xB20F1012), label = "vc")
    val fg = if (on) K.PrimarySoft else Color.White
    Box(
        Modifier.pressPop(src, 0.9f).height(44.dp).clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeMenuRow(label: String, onClick: () -> Unit) {
    Text(label, color = K.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp))
}

/** One of the wheel's ranges: its name, the wheel's value and how the node gets a new one. */
private class WheelRange(val key: String, val label: String, val wheel: Wheel, val with: (Wheel) -> GradeNode)

/**
 * The wheels: one large wheel for the range chosen beside it (shadows, midtones, highlights, offset – each button
 * shows its own wheel small), the luminance of that range as a slider under, then white balance and copying the
 * grade. Primaries are lift, gamma and gain; Log is the log wheels with their two range limits.
 */
@Composable
private fun WheelsPanel(
    n: GradeNode, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit,
    wbOn: Boolean, onWb: () -> Unit, canPaste: Boolean, onCopyPaste: () -> Unit,
) {
    val haptics = rememberHaptics()
    var log by rememberSaveable { mutableStateOf(false) }
    var at by rememberSaveable { mutableStateOf(1) }
    val shadow = stringResource(R.string.colorpage_wheel_shadow)
    val mid = stringResource(R.string.colorpage_wheel_midtone)
    val high = stringResource(R.string.colorpage_wheel_highlight)
    // primaries go by the grading names (the same in every language); the log wheels by the tonal ranges they work on
    val ranges = if (!log) listOf(
        WheelRange("Lift", "Lift", n.lift) { n.copy(lift = it) },
        WheelRange("Gamma", "Gamma", n.gamma) { n.copy(gamma = it) },
        WheelRange("Gain", "Gain", n.gain) { n.copy(gain = it) },
        WheelRange("Offset", "Offset", n.offset) { n.copy(offset = it) },
    ) else listOf(
        WheelRange("LogShadow", shadow, n.logShadow) { n.copy(logShadow = it) },
        WheelRange("LogMid", mid, n.logMid) { n.copy(logMid = it) },
        WheelRange("LogHi", high, n.logHi) { n.copy(logHi = it) },
        WheelRange("Offset", "Offset", n.offset) { n.copy(offset = it) },
    )
    val cur = ranges[at.coerceIn(0, ranges.lastIndex)]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            WheelMode(stringResource(R.string.colorpage_wheels_primaries), !log, first = true, tag = "wheels:0") { haptics.tick(); log = false }
            Spacer(Modifier.width(3.dp))
            WheelMode(stringResource(R.string.colorpage_wheels_log), log, first = false, tag = "wheels:1") { haptics.tick(); log = true }
            Spacer(Modifier.weight(1f))
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.pressPop(src, 0.88f).size(44.dp).clip(RoundedCornerShape(14.dp))
                    .clickable(interactionSource = src, indication = null, role = Role.Button) {
                        // all of this mode's wheels in one step
                        val reset = if (!log) n.copy(lift = Wheel(), gamma = Wheel(), gain = Wheel(), offset = Wheel())
                        else n.copy(logShadow = Wheel(), logMid = Wheel(), logHi = Wheel(), offset = Wheel(), logLow = 0.333f, logHigh = 0.55f)
                        if (reset != n) { haptics.heavy(); onCommit(reset) }
                    }.testTag("wheels:reset"),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Reset, stringResource(R.string.design_color_reset_wheels), tint = K.OnSurface, modifier = Modifier.size(20.dp)) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(176.dp)) {
            // on a narrow phone the wheel gives way, so the ranges' names fit beside it
            val wheel = (maxWidth - 114.dp).coerceIn(140.dp, 176.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                key(cur.key) {
                    ColorWheel(cur.key, cur.wheel, Modifier.size(wheel), { onLive(cur.with(it)) }) { onCommit(cur.with(it)) }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((i, r) in ranges.withIndex()) {
                        RangeButton(r.label, r.wheel, r === cur, Modifier.weight(1f).testTag("range:${r.key}")) { haptics.tick(); at = i }
                    }
                }
            }
        }
        PropSlider(stringResource(R.string.design_color_luma, cur.label.lowercase()), cur.wheel.master, -1f, 1f, 0f, { "%+.2f".format(it) }, "master", lane = false,
            onLive = { onLive(cur.with(cur.wheel.copy(master = it))) }) { onCommit(cur.with(cur.wheel.copy(master = it))) }
        if (log) {
            PropSlider(stringResource(R.string.colorpage_log_low), n.logLow * 100f, 5f, n.logHigh * 100f - 5f, 33.3f, { "%.0f".format(it) }, "logLow", lane = false,
                onLive = { onLive(n.copy(logLow = it / 100f)) }) { onCommit(n.copy(logLow = it / 100f)) }
            PropSlider(stringResource(R.string.colorpage_log_high), n.logHigh * 100f, n.logLow * 100f + 5f, 95f, 55f, { "%.0f".format(it) }, "logHigh", lane = false,
                onLive = { onLive(n.copy(logHigh = it / 100f)) }) { onCommit(n.copy(logHigh = it / 100f)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlatButton(if (wbOn) stringResource(R.string.design_color_cancel_pick) else stringResource(R.string.colorpage_group_balance), Ic.Dropper, wbOn, Modifier.weight(1f).testTag("wheels:wb"), onWb)
            FlatButton(stringResource(if (canPaste) R.string.design_color_paste_grade else R.string.design_color_copy_grade), if (canPaste) Ic.Paste else Ic.Copy, false, Modifier.weight(1f).testTag("wheels:copy"), onCopyPaste)
        }
    }
}

/** Primaries or Log: the chosen one a light pill, the other gray with its small corners toward it. */
@Composable
private fun WheelMode(label: String, on: Boolean, first: Boolean, tag: String, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) K.OnSurface else K.SurfaceHigh, label = "wm")
    val inner by animateDpAsState(if (on) 22.dp else 8.dp, spring(0.55f, 700f), label = "wmr")
    val shape = if (first) RoundedCornerShape(22.dp, inner, inner, 22.dp) else RoundedCornerShape(inner, 22.dp, 22.dp, inner)
    Box(
        Modifier.height(44.dp).clip(shape).background(bg)
            .clickable(role = Role.Tab, onClick = onClick).semantics { selected = on }
            .padding(horizontal = if (on) 18.dp else 16.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) K.Bg else K.OnSurface, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1) }
}

/** A range beside the wheel: its wheel small (the dot is where its puck is) and its name. */
@Composable
private fun RangeButton(label: String, w: Wheel, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val r by animateDpAsState(if (on) 28.dp else 12.dp, spring(0.55f, 600f), label = "rr")
    val bg by animateColorAsState(if (on) K.PrimaryContainer else K.SurfaceHigh, label = "rb")
    val fg = if (on) K.PrimarySoft else K.OnSurface
    val ring = if (on) K.Primary else K.Outline
    Row(
        modifier.fillMaxWidth().pressPop(src, 0.94f).clip(RoundedCornerShape(r)).background(bg)
            .clickable(interactionSource = src, indication = null, role = Role.Tab, onClick = onClick).semantics { selected = on }
            .padding(horizontal = if (on) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(30.dp)) {
            drawCircle(ring, size.minDimension / 2f - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
            val t = 9.dp.toPx()
            drawCircle(fg, 3.dp.toPx(), Offset(center.x + w.x * t, center.y - w.y * t))
        }
        // a long name shrinks to the button's width instead of being cut
        BasicText(
            label, color = { fg }, maxLines = 1,
            style = TextStyle(fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold),
            autoSize = TextAutoSize.StepBased(8.sp, 11.sp, 0.5.sp), modifier = Modifier.weight(1f),
        )
    }
}

/** A wide, flat button under a tool: an icon and a name; in the accent's container color while [on]. */
@Composable
private fun FlatButton(label: String, icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (on) K.PrimaryContainer else K.SurfaceHigh, label = "fb")
    val fg = if (on) K.PrimarySoft else K.OnSurface
    Row(
        modifier.pressPop(src, 0.94f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        BasicText(
            label, color = { fg }, maxLines = 1,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            autoSize = TextAutoSize.StepBased(9.sp, 13.sp, 0.5.sp), modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * A color wheel: a ring of the colors around a dark disc, and a puck with a line from the center. Drag the puck toward
 * a color (no snapping – a click when passing the center); double-tap the wheel to reset the color. The luminance of
 * the range is the slider under the wheel.
 */
@Composable
fun ColorWheel(label: String, w: Wheel, modifier: Modifier, onLive: (Wheel) -> Unit, onCommit: (Wheel) -> Unit) {
    val haptics = rememberHaptics()
    var cur by remember { mutableStateOf(w) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(w) { if (!dragging) cur = w }
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)
    val lastTap = remember { longArrayOf(0L) }
    val disc = K.SurfaceHigh
    val cross = K.OutlineVar
    val ink = K.OnSurface
    // the color ring: same angle → color as the grade math (Wheel.rgb), muted to sit in the dark interface
    val ringColors = remember {
        (0..12).map { i ->
            val h = Wheel.hueColor((-i * 30.0 * PI / 180).toFloat())
            Color(0.31f + 0.54f * h[0], 0.31f + 0.54f * h[1], 0.31f + 0.54f * h[2])
        }
    }
    Canvas(modifier.testTag("wheel:$label").pointerInput(Unit) {
        // how far the puck goes from the center (it stays inside the ring)
        fun travel() = size.width / 2f - 22.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            val r = size.width / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val now = System.currentTimeMillis()
            if (now - lastTap[0] < 320) {
                down.consume()
                haptics.confirm(); lastTap[0] = 0
                // (the range's luminance stays: it has its own slider)
                if (cur.x != 0f || cur.y != 0f) { cur = w.copy(x = 0f, y = 0f); commit(cur) }
                return@awaitEachGesture
            }
            // on the puck: take the gesture immediately; otherwise only on horizontal movement – vertical scrolls the page
            val puck = Offset(c.x + cur.x * travel(), c.y - cur.y * travel())
            if ((down.position - puck).getDistance() < 28.dp.toPx()) down.consume()
            else when (claimHorizontal(down)) {
                null -> { lastTap[0] = now; return@awaitEachGesture }
                false -> return@awaitEachGesture
                true -> {}
            }
            val start = cur
            dragging = true
            var snapped = cur.x == 0f && cur.y == 0f
            // relative drag: the puck moves with the finger, with fine sensitivity
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
                // a click when the puck passes the center (within the click zone once, again after leaving it)
                val near = hypot(px, py) < 0.035f
                if (near && !snapped) haptics.tick()
                snapped = near
                cur = cur.copy(x = px, y = py)
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
        drawCircle(Brush.sweepGradient(ringColors, c), r, c)
        drawCircle(disc, r - 10.dp.toPx(), c)
        val arm = r - 20.dp.toPx()
        drawLine(cross, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), 2.dp.toPx())
        drawLine(cross, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), 2.dp.toPx())
        val t = r - 22.dp.toPx()
        val p = Offset(c.x + cur.x * t, c.y - cur.y * t)
        drawLine(ink, c, p, 2.dp.toPx())
        drawCircle(Color.Black.copy(alpha = 0.3f), 12.dp.toPx(), Offset(p.x, p.y + 2.dp.toPx()))
        drawCircle(ink, (if (dragging) 12f else 10f) * density, p)
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
fun CurvesEditor(n: GradeNode, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit, picking: Boolean = false, onPick: ((PickedRgb?) -> Unit)? = null) {
    val haptics = rememberHaptics()
    var mode by remember { mutableStateOf(-1) }
    val labels = listOf(R.string.colorpage_curves_custom) + HslKind.entries.map { hslLabel(it) }
    Column {
        Row(Modifier.fillMaxWidth().sideScroll().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, l) in labels.withIndex()) {
                val k = i - 1
                val on = mode == k
                val used = if (k < 0) n.hasCurves else !n.hsl[HslKind.entries[k]].isNeutral
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(if (on) 22.dp else 12.dp)).background(if (on) K.PrimaryContainer else K.SurfaceHigh)
                        .clickable(role = Role.Tab) { if (!on) haptics.tick(); mode = k }.semantics { selected = on }
                        .padding(horizontal = 14.dp).testTag("curveMode:$k"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(l) + if (used && !on) " •" else "", color = if (on) K.PrimarySoft else K.OnSurface, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (mode < 0) CustomCurves(n, onLive, onCommit) else HslCurveEditor(n, HslKind.entries[mode], onLive, onCommit, picking, onPick)
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

/** Receives the color the dropper picked (as the node's HSL curves see it). */
typealias PickedRgb = (FloatArray) -> Unit

/**
 * One HSL curve. The middle line is no change; up = more (hue turned, saturation, luminance). Tap = a point on the
 * curve, drag = move, drag out of the box or double tap = delete. The hue axis wraps (a point near red affects both
 * ends); the luma and saturation axes have fixed end points that move only up and down. Under the hue curves: the six
 * colors – a tap adds a point there plus neutral points at the neighbouring colors, to change just that color. The
 * dropper ([onPick]: starts a pick with the receiver, or cancels with null) adds a point at the color tapped in the
 * image with a point on each side, all on the curve as it is.
 */
@Composable
fun HslCurveEditor(
    n: GradeNode, kind: HslKind, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit,
    picking: Boolean = false, onPick: ((PickedRgb?) -> Unit)? = null,
) {
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
    // a distance along the axis (the hue axis wraps)
    fun gap(a: Float, b: Float) = if (kind.periodic) abs(((a - b + 1.5f) % 1f) - 0.5f) else abs(a - b)
    /** The dropper's color at [x] on the axis: a point there and one on each side, on the curve as it is (nothing moves yet). */
    fun addPicked(x: Float) {
        val side = if (kind.periodic) 1f / 12f else 0.1f
        val xs = listOf(x - side, x, x + side).map { if (kind.periodic) (it % 1f + 1f) % 1f else it }
            .filter { kind.periodic || it in 0.01f..0.99f }
        val old = pts
        val add = xs.filter { c -> old.none { gap(it.first, c) < 0.02f } }.map { it to heightAt(old, it) }
        val list = (old + add).sortedBy { it.first }
        pts = list
        active = list.indices.minByOrNull { gap(list[it].first, x) }
        haptics.tick()
        commit(with(node, list))
    }
    // the dropper is for this curve only: leaving it (another curve, another tool) ends the pick
    val pickFn by rememberUpdatedState(onPick)
    val pickingNow by rememberUpdatedState(picking)
    // a color read after leaving the curve (the reading takes a moment) must not land on another curve
    val alive = remember(kind) { booleanArrayOf(true) }
    androidx.compose.runtime.DisposableEffect(kind) { onDispose { alive[0] = false; if (pickingNow) pickFn?.invoke(null) } }
    // the last tap on a point (time, index): a second tap on the same point soon after deletes it
    val lastTap = remember(kind) { LongArray(2) { -1L } }
    Column(Modifier.padding(horizontal = 14.dp)) {
        run {
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (onPick != null) {
                    // (while it waits for a tap its long text takes the room Reset leaves)
                    PillButton(stringResource(if (picking) R.string.colortools_tap_image else R.string.colorpage_hsl_pick), {
                        haptics.tick()
                        onPick(if (picking) null else { rgb -> if (alive[0]) addPicked(HslCurves.axis(kind, rgb)) })
                    }, Modifier.weight(1f, fill = false).testTag("hslPick"), icon = Ic.Dropper, filled = false, on = picking)
                } else Spacer(Modifier.width(1.dp))
                PillButton(stringResource(R.string.colorpage_reset), { haptics.confirm(); pts = defaults(); active = null; commit(node.copy(hsl = node.hsl.with(kind, HslCurve()))) },
                    Modifier.padding(start = 6.dp), icon = Ic.Reset, filled = false)
            }
            // a line of its own (beside the buttons it broke into four on a phone), there also when empty: nothing moves
            Text(readout, color = K.OnSurfaceVar, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("hslReadout"))
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
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            // a tap on a point doesn't nudge it: it moves once the finger has really moved
                            if (!moved && (c.position - down.position).getDistance() < viewConfiguration.touchSlop) { c.consume(); continue }
                            moved = true
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
                        // double tap on a point: delete it (not the luma/saturation axes' end points)
                        if (!moved && hit != null) {
                            val now = down.uptimeMillis
                            if (lastTap[1] == hit.toLong() && now - lastTap[0] < viewConfiguration.doubleTapTimeoutMillis && !end) {
                                lastTap[0] = -1L; lastTap[1] = -1L
                                haptics.heavy()
                                pts = pts.filterIndexed { i, _ -> i != hit }; active = null
                                commit(with(node, pts))
                                return@awaitEachGesture
                            }
                            lastTap[0] = now; lastTap[1] = hit.toLong()
                        } else { lastTap[0] = -1L; lastTap[1] = -1L }
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
    // the last tap on a point (time, index): a second tap on the same point soon after deletes it
    // (per channel: a tap on Y and then on the same point in R must not count as a double tap)
    val lastTap = remember(ch) { LongArray(2) { -1L } }
    Column(Modifier.padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // (the channels give way to Reset on a narrow phone)
            ConnectedToggleGroup(listOf("Y", "R", "G", "B"), ch, { i -> ch = i; pts = curveOf(node, i).points }, Modifier.weight(1f, fill = false).widthIn(max = 200.dp), tag = { "curveCh:$it" })
            PillButton(stringResource(R.string.colorpage_reset), { haptics.confirm(); pts = Curve.IDENTITY; commit(with(node, channel, Curve())) }, Modifier.padding(start = 6.dp), icon = Ic.Reset, filled = false)
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
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            // a tap on a point doesn't nudge it: it moves once the finger has really moved
                            if (!moved && (c.position - down.position).getDistance() < viewConfiguration.touchSlop) { c.consume(); continue }
                            moved = true
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
                        // double tap on a point: delete it (not the end points)
                        if (!moved && hit != null) {
                            val now = down.uptimeMillis
                            if (lastTap[1] == hit.toLong() && now - lastTap[0] < viewConfiguration.doubleTapTimeoutMillis && !end) {
                                lastTap[0] = -1L; lastTap[1] = -1L
                                haptics.heavy()
                                pts = pts.filterIndexed { i, _ -> i != hit }
                                commit(with(node, channel, Curve(pts)))
                                return@awaitEachGesture
                            }
                            lastTap[0] = now; lastTap[1] = hit.toLong()
                        } else { lastTap[0] = -1L; lastTap[1] = -1L }
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
private fun Scopes(c: ColorClip, state: EditorState, gs: GradeState, kind: Int, look: se.gendut.klipp.engine.Look, modifier: Modifier = Modifier) {
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
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE60B0C0F)).testTag("scope")) {
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

/**
 * The dropper for the HSL curves: the color where tapped as the node's curves measure it – its input, then the node's
 * own wheels, adjustments and custom curves (the HSL curves come after those, before the LUT).
 */
suspend fun pickHsl(ctx: android.content.Context, c: ColorClip, playhead: Float, g: ClipGrade, nodeId: Int, x: Float, y: Float): FloatArray? {
    val n = g.nodes.firstOrNull { it.id == nodeId } ?: return null
    val rgb = pickInput(ctx, c, playhead, g, nodeId, x, y) ?: return null
    val before = n.copy(enabled = true, hsl = HslCurves(), lut = null, drt = null, film = null, mix = 1f, blur = 0f, pixelate = 0f,
        qual = n.qual.copy(on = false), win = n.win.copy(shape = 0))
    if (!before.isNeutral) withContext(Dispatchers.Default) { GradeNode.apply(before.params(), rgb, x, y) }
    return rgb
}

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
