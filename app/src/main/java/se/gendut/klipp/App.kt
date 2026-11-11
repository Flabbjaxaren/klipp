package se.gendut.klipp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import se.gendut.klipp.engine.playable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.roundToInt

var DEBUG_GESTURES = false
internal val MODES = listOf(R.string.app_mode_edit, R.string.app_mode_color, R.string.app_mode_audio, R.string.app_mode_text)
internal val MODE_ICONS = listOf(Ic.Edit, Ic.Color_, Ic.Audio, Ic.Text)
/** The program monitor is never smaller than this in portrait. */
private val MIN_MONITOR_H = 120.dp

class Toaster {
    var message by mutableStateOf<String?>(null)
    var key by mutableIntStateOf(0)
    fun show(m: String) { message = m; key++ }
}

/** UI state the tests can read. */
class AppUi {
    val page = Animatable(0f)
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun KlippApp(
    state: EditorState = remember { EditorState() },
    ui: AppUi = remember { AppUi() },
    controller: se.gendut.klipp.engine.ProjectController? = null,
    toaster: Toaster = remember { Toaster() },
) {
    val grade = remember { ColorGrade() }
    val mix = remember { AudioMix() }
    val gs = remember { GradeState() }
    /** The look values that last became an undo step (or were loaded). */
    val lookMark = remember { arrayOfNulls<LookValues>(1) }
    /** The master fader and the normalization that last became an undo step (or were loaded). */
    val masterMark = remember { arrayOfNulls<MasterValues>(1) }
    // color changes are undone in the same history as the timeline (undo used to skip past them and undo the
    // latest timeline change – e.g. that the clip was added, so it disappeared)
    androidx.compose.runtime.DisposableEffect(gs, state) {
        gs.history = { label, undo, redo -> state.recordSide(label, undo, redo) }
        // compound clips are graded together (the video clips in the group)
        gs.siblings = { id -> state.clips.firstOrNull { it.id == id }?.let { c -> state.groupMembers(c).filter { it.kind == ClipKind.VIDEO }.map { it.id } }.orEmpty() }
        gs.clipInfo = { id ->
            controller?.snapshot?.sequence?.video?.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == id } }
                ?.let { c -> ColorClip(c.id, c.name, c.start.toFloat(), c.end.toFloat(), controller.uriOf(c.item), c.sourceIn, 0, c.speed, c.hold, c.reverse) }
        }
        onDispose { gs.history = null; gs.siblings = null; gs.clipInfo = null }
    }
    // color tools in the program view only when the color mode is the visible one (the adjacent page stays composed)
    LaunchedEffect(gs) {
        snapshotFlow { ui.page.value.roundToInt() == 1 }.collect { on ->
            gs.active = on
            if (!on) gs.pick = null
        }
    }
    val title = remember { TitleState() }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val page = ui.page
    var tool by remember { mutableStateOf(Tool.SELECT) }
    var fingers by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val playback = remember(controller) { controller?.let { se.gendut.klipp.engine.ProgramPlayback(context) } }
    val sourcePlayback = remember(controller) { controller?.let { se.gendut.klipp.engine.SourcePlayback(context) } }
    androidx.compose.runtime.DisposableEffect(controller) { onDispose { playback?.release(); sourcePlayback?.release() } }
    var mediaOpen by remember { mutableStateOf(false) }
    /** The edit page's add sheet that is open (sound, a voice-over, a title, captions), or null. */
    var addSheet by remember { mutableStateOf<AddAction?>(null) }
    var sourceMedia by remember { mutableStateOf<se.gendut.klipp.engine.MediaInfo?>(null) }
    var exportProgress by remember { mutableStateOf<Int?>(null) }
    var confirmNew by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    /** The picture in full screen (double tap on it); tap there = play/pause, double tap or back = out again. */
    var fullscreen by remember { mutableStateOf(false) }
    // messages from the timeline (locked track …)
    androidx.compose.runtime.DisposableEffect(state, toaster) { state.onNotice = { toaster.show(it) }; onDispose { state.onNotice = null } }
    // the "playhead locked in the center by default" setting
    LaunchedEffect(state) {
        if (AppPrefs.centerDefault(context)) state.centerPlayhead = true
        // snapping is on from the start; the setting can only turn it off
        if (!AppPrefs.magnetDefault(context)) state.magnet = false
    }
    // release the source decoder only when the source is actually closed (not when the phone rotates)
    LaunchedEffect(sourceMedia) { if (sourceMedia == null) sourcePlayback?.close() }
    val picker = rememberVideoPicker { uris -> controller?.import(uris); if (uris.isNotEmpty()) mediaOpen = true }
    fun openImport() { picker.launch(videoRequest()) }
    // an imported LUT goes into the timeline look, or into a node if the import was started from there
    var lutTarget by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val lutPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val target = lutTarget
        lutTarget = null
        if (uri != null) scope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { se.gendut.klipp.engine.LutStore.import(context, uri) } }
                .onSuccess { name -> if (target != null) target(name) else grade.lutName = name; haptics.confirm(); toaster.show(Str.t(R.string.app_lut_imported, name)) }
                .onFailure { toaster.show(Str.t(R.string.app_lut_read_failed, it.message ?: "")) }
        }
    }
    val captionPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller?.importCaptionsFrom(uri)
    }
    LaunchedEffect(controller) { controller?.captionPicker = { captionPicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/*", "application/octet-stream", "*/*")) } }
    // nothing selected: properties close; a clip is selected: the caption is deselected (one selection at a time)
    LaunchedEffect(state.selected) { if (state.selected == null) state.inspecting = false else state.selectedCaption = null }
    LaunchedEffect(state.selectedCaption) { if (state.selectedCaption == null) state.editingCaption = false }
    var exportAsk by remember { mutableStateOf(false) }
    fun export() {
        val c = controller ?: run { toaster.show(Str.t(R.string.app_export_needs_clips)); return }
        if (c.snapshot.sequence?.video?.any { it.clips.isNotEmpty() } != true) { toaster.show(Str.t(R.string.app_timeline_empty)); return }
        state.playing = false
        exportAsk = true
    }
    fun runExport(settings: se.gendut.klipp.engine.ExportSettings, name: String) {
        val c = controller ?: return
        se.gendut.klipp.engine.ExportSettings.save(context, c.currentId, settings)
        // color-graded clips always become SDR (grading is done in SDR)
        val graded = gs.grades.values.any { !it.isNeutral } || !lookOf(grade).isNeutral
        val st = if (graded) settings.copy(hdr = false) else settings
        exportProgress = 0
        scope.launch {
            val r = se.gendut.klipp.engine.Exporter.export(context, c.snapshot, c::uriOf, name, lookOf(grade), mixMaster(mix), HashMap(gs.grades), st) { exportProgress = it }
            exportProgress = null
            r.onSuccess { haptics.confirm(); toaster.show(Str.t(R.string.app_export_saved) + (se.gendut.klipp.engine.Exporter.lastResult?.let { " · $it" } ?: "")) }
                .onFailure {
                    // in the trail (crash reports): what went wrong and where
                    val e = it as? androidx.media3.transformer.ExportException
                    se.gendut.klipp.CrashReport.note("export failed: ${e?.errorCodeName ?: it.javaClass.simpleName} ${it.message} ${it.cause?.let { c -> "(${c.javaClass.simpleName}: ${c.message})" } ?: ""}")
                    toaster.show(Str.t(R.string.app_export_failed, it.message ?: ""))
                }
        }
    }
    if (exportAsk && controller != null) {
        val q = controller.snapshot.sequence
        val graded = gs.grades.values.any { !it.isNeutral } || !lookOf(grade).isNeutral
        val anyHdr = remember(controller.revision) { se.gendut.klipp.engine.CompositionBuilder.anyHdr(context, controller.snapshot) { controller.uriOf(it) } }
        // 10-bit sources (Auto bit depth follows them) – read from the files, off the main thread
        val anyTen by androidx.compose.runtime.produceState<Boolean?>(null, controller.revision) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { se.gendut.klipp.engine.CompositionBuilder.anyTenBit(context, controller.snapshot) { controller.uriOf(it) } }.getOrNull()
            }
        }
        ExportDialog(
            initial = remember { se.gendut.klipp.engine.ExportSettings.load(context, controller.currentId) },
            defaultName = remember { "Klipp_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.ROOT).format(java.util.Date()) },
            seqW = q?.width ?: 1920, seqH = q?.height ?: 1080, projectFps = controller.snapshot.fps, seconds = q?.duration ?: 0.0,
            hdrPossible = anyHdr && se.gendut.klipp.engine.EncoderCaps.hdrSupported,
            // grading is done in SDR (8 bits, gamma) – in an HDR signal it would look wrong
            hdrBlocked = if (graded) stringResource(R.string.app_hdr_blocked) else null,
            anyTenBit = anyTen,
            onDismiss = { exportAsk = false },
        ) { st, name -> exportAsk = false; runExport(st, name) }
    }
    if (controller != null && playback != null) PlaybackSync(controller, playback, state) { toaster.show(it) }
    // voice-over: countdown, recording while the timeline plays muted, then the file is placed on the track
    val voice = remember { VoiceOverUi() }
    val recorder = remember(context) { VoiceOverRecorder(context) }
    androidx.compose.runtime.DisposableEffect(recorder) { onDispose { recorder.stopQuietly() } }
    var voiceStart by remember { mutableStateOf(0.0) }
    fun startVoice() {
        if (controller == null || playback == null || voice.phase != 0) return
        voice.phase = 3 // immediately, so a double tap doesn't start two recordings
        scope.launch {
            state.playing = false
            voiceStart = state.playhead.toDouble()
            for (n in 3 downTo 1) { voice.phase = n; haptics.tick(); kotlinx.coroutines.delay(700) }
            val ok = runCatching { recorder.start() }
            if (ok.isFailure) { voice.phase = 0; toaster.show(Str.t(R.string.app_mic_failed, ok.exceptionOrNull()?.message ?: "")); return@launch }
            voice.phase = -1
            voice.startedAt = System.currentTimeMillis()
            playback.recordingMute = true
            state.playing = true
        }
    }
    fun stopVoice() {
        if (voice.phase != -1) return
        state.playing = false
        playback?.recordingMute = false
        voice.phase = 0
        val f = recorder.stop()
        if (f == null) { toaster.show(Str.t(R.string.app_nothing_recorded)); return }
        controller?.placeRecording(f, voiceStart, voice.track, Str.t(R.string.app_voiceover_name, java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())))
    }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else toaster.show(Str.t(R.string.app_mic_permission))
    }
    // after composition (not as a side effect within it)
    androidx.compose.runtime.SideEffect {
        voice.onStart = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
            else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
        voice.onStop = { stopVoice() }
    }
    // recording stops if playback stops (end of the timeline, pause)
    LaunchedEffect(state.playing) { if (!state.playing && voice.phase == -1) stopVoice() }
    val audioTools = remember(controller) {
        controller?.let { c ->
            AudioTools(
                tracks = { c.snapshot.sequence?.audio?.take(3).orEmpty() },
                toggleKf = { i, at, db -> c.toggleTrackKeyframe(i, at, db) },
                clearKf = { i -> c.clearTrackKeyframes(i) },
                measure = { c.measureLoudness() },
                voiceOver = voice,
                duck = { t, db -> c.autoDuck(t, db) },
                unduck = { t -> c.removeDucking(t) },
                addSound = { file, name, secs -> state.playing = false; c.placeSound(file, state.playhead.toDouble(), secs, name) },
            )
        }
    }
    // color mode: grades to playback (after a short pause, so drags don't rebuild every time)
    if (playback != null) LaunchedEffect(playback) {
        var lastFollows: Map<Long, se.gendut.klipp.engine.Follow>? = null
        var lastPins: Map<Long, se.gendut.klipp.engine.CornerPin>? = null
        androidx.compose.runtime.snapshotFlow { gs.revision }.debounce(200).collect {
            val g = HashMap(gs.grades)
            if (g != playback.grades) {
                playback.grades = g
                playback.refresh(state.playhead.toDouble())?.let { toaster.show(Str.t(R.string.app_playback_rebuild_failed, it)) }
                controller?.requestSave()
            }
            val f = HashMap(gs.follows)
            if (f != lastFollows) { if (lastFollows != null) controller?.requestSave(); lastFollows = f }
            // corner pins are saved with the grades too
            val p = HashMap(gs.pins)
            if (p != lastPins) { if (lastPins != null) controller?.requestSave(); lastPins = p }
        }
    }
    // a transition's motion blur (kept by the app, not the engine): to playback and saved, once the finger rests
    if (playback != null) LaunchedEffect(playback) {
        var last: Map<String, Float>? = null
        androidx.compose.runtime.snapshotFlow { se.gendut.klipp.engine.TransitionLooks.blur.toMap() }.debounce(250).collect { b ->
            if (last != null && b != last) { playback.refresh(state.playhead.toDouble()); controller?.requestSave() }
            last = b
        }
    }
    // node cache: clips with a cached node render in the background a while after changes; a finished file → the player is rebuilt
    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { gs.revision to controller.revision }.debounce(1500).collect {
            runCatching { se.gendut.klipp.engine.NodeCache.sync(context, controller.snapshot, HashMap(gs.grades), controller::uriOf) }
                .onFailure { se.gendut.klipp.CrashReport.note("node cache: ${it.message}") }
        }
    }
    if (playback != null) LaunchedEffect(playback) {
        androidx.compose.runtime.snapshotFlow { listOf(se.gendut.klipp.engine.NodeCache.revision, se.gendut.klipp.engine.FrameHolds.revision, se.gendut.klipp.engine.Reversals.revision, se.gendut.klipp.engine.RenderCache.revision) }.collect {
            playback.refresh(state.playhead.toDouble())
        }
    }
    // pre-rendering: chunks are recomputed a while after each change; pre-rendered transitions (and the whole timeline when
    // requested) render in the background – only while playback is stopped, and a finished chunk is swapped in only when paused.
    // The render runs beside the recomputing: a change lets the chunk under way finish if it is still wanted
    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        var rendering: kotlinx.coroutines.Job? = null
        // (the render belongs to this effect, not to one round of recomputing: closing the project ends it)
        val renderScope = this
        androidx.compose.runtime.snapshotFlow {
            // (not the render's own revision: a finished chunk would then cancel rendering of the next)
            listOf(controller.revision, gs.revision, se.gendut.klipp.engine.RenderCache.pinned.toList(), se.gendut.klipp.engine.TransitionLooks.blur.toMap(), se.gendut.klipp.engine.RenderCache.renderAll,
                se.gendut.klipp.engine.RenderCache.cleared, se.gendut.klipp.engine.RenderCache.halted, se.gendut.klipp.engine.RenderCache.auto,
                se.gendut.klipp.engine.VideoPipeline.previewShort)
        }.debounce(1200).collectLatest {
            val snap = controller.snapshot
            val grades = HashMap(gs.grades)
            val chunks = runCatching {
                se.gendut.klipp.engine.CompositionBuilder.previewPlan(snap, controller::uriOf)
                    ?.let { p -> se.gendut.klipp.engine.RenderCache.chunks(p, { id -> grades[id] }, controller::uriOf, snap.fps, se.gendut.klipp.engine.VideoPipeline.previewShort) }
            }.getOrNull().orEmpty()
            se.gendut.klipp.engine.RenderCache.states = chunks.map { se.gendut.klipp.engine.RenderCache.ChunkState(it.a, it.b, se.gendut.klipp.engine.RenderCache.isReady(it), it.heavy) }
            // the transitions that should be pre-rendered (the clips before and after, by their current ids)
            val pinned = se.gendut.klipp.engine.RenderCache.pinned.toSet()
            val windows = snap.playable().sequence?.video.orEmpty().flatMap { t -> t.transitions }
                .filter { x -> x.from != null && x.to != null && se.gendut.klipp.engine.RenderCache.pairKey(x.from, x.to) in pinned }
                .map { it.start to it.end }
            val all = se.gendut.klipp.engine.RenderCache.renderAll
            val auto = se.gendut.klipp.engine.RenderCache.auto
            val todo = chunks.filter { c ->
                !se.gendut.klipp.engine.RenderCache.isReady(c) && (all || (auto && c.heavy) || windows.any { (a, b) -> c.a < b - 1e-6 && c.b > a + 1e-6 })
            }
            se.gendut.klipp.engine.RenderCache.wanted = if (se.gendut.klipp.engine.RenderCache.halted) emptySet() else todo.map { it.hash }.toSet()
            se.gendut.klipp.engine.RenderCache.generation++
            // the render under way: its chunk finishes (or stops, if no longer wanted), then the new list takes over
            rendering?.join()
            if (todo.isEmpty()) {
                if (all) se.gendut.klipp.engine.RenderCache.renderAll = false
                // finished chunks not yet joined (e.g. after a change in the middle of a rendered stretch)
                val before = se.gendut.klipp.engine.RenderCache.usedFiles(chunks)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { se.gendut.klipp.engine.RenderCache.joinRuns(chunks) }
                if (se.gendut.klipp.engine.RenderCache.usedFiles(chunks) != before) {
                    androidx.compose.runtime.snapshotFlow { state.playing }.first { !it }
                    se.gendut.klipp.engine.RenderCache.bump()
                }
                return@collectLatest
            }
            if (se.gendut.klipp.engine.RenderCache.halted) return@collectLatest
            // not while playing (rendering takes decoders and power from the picture); scrubbing, zooming and editing go on beside it
            rendering = renderScope.launch {
                val r = se.gendut.klipp.engine.RenderCache.render(context, snap, controller::uriOf, grades, todo, canWork = { !state.playing }) {
                    se.gendut.klipp.engine.RenderCache.states = chunks.map { se.gendut.klipp.engine.RenderCache.ChunkState(it.a, it.b, se.gendut.klipp.engine.RenderCache.isReady(it), it.heavy) }
                    // into playback only once it's stopped (a rebuild during playback stutters)
                    scope.launch { androidx.compose.runtime.snapshotFlow { state.playing }.first { !it }; se.gendut.klipp.engine.RenderCache.bump() }
                }
                r.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    se.gendut.klipp.CrashReport.note("pre-render: ${e.message}")
                    se.gendut.klipp.engine.RenderCache.renderAll = false
                    toaster.show(Str.t(R.string.app_render_failed, e.message ?: e.javaClass.simpleName))
                }
                if (r.isSuccess) {
                    // consecutive finished chunks are joined (one item in the player), into playback when paused
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { se.gendut.klipp.engine.RenderCache.joinRuns(chunks) }
                    scope.launch { androidx.compose.runtime.snapshotFlow { state.playing }.first { !it }; se.gendut.klipp.engine.RenderCache.bump() }
                }
                // (a render that left the rest to a newer timeline has not finished it)
                if (r.isSuccess && all && todo.all { se.gendut.klipp.engine.RenderCache.isReady(it) }) {
                    se.gendut.klipp.engine.RenderCache.renderAll = false
                    toaster.show(Str.t(R.string.app_timeline_rendered))
                }
            }
            rendering.join()
        }
    }
    // titles that follow tracked windows: tracking data and timeline to the title layer (playback, export)
    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { listOf(gs.revision, controller.revision, gs.follows.size, gs.pins.size) }.collect {
            se.gendut.klipp.engine.TitleFollow.publish(HashMap(gs.follows), HashMap(gs.grades), controller.snapshot, HashMap(gs.pins))
        }
    }
    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        se.gendut.klipp.engine.LutStore.init(context)
        controller.extraSave = { gradeToJson(grade, mix).put("linkedSelection", state.linkedSelection).put("grades", gs.toJson())
            .put("renderPinned", org.json.JSONArray(se.gendut.klipp.engine.RenderCache.pinned.toList()))
            .put("transitionBlur", se.gendut.klipp.engine.TransitionLooks.toJson()) }
        // the pieces get the clip's grade as part of the split (undone with it) – no separate color step
        controller.onSplit = { old, fresh -> gs.grades[old]?.let { g -> gs.setAll(fresh.associateWith { g }, record = false) } }
        controller.onNewClips = { ids -> ids.filter { gs.grades.containsKey(it) }.takeIf { it.isNotEmpty() }?.let { stale -> gs.setAll(stale.associateWith { se.gendut.klipp.engine.ClipGrade() }, record = false) } }
        controller.onExtraRestore = { o ->
            gs.load(o.optJSONObject("grades"))
            se.gendut.klipp.engine.RenderCache.renderAll = false
            se.gendut.klipp.engine.RenderCache.halted = false
            se.gendut.klipp.engine.RenderCache.pinned.clear()
            o.optJSONArray("renderPinned")?.let { a -> for (i in 0 until a.length()) se.gendut.klipp.engine.RenderCache.pinned += a.optString(i) }
            se.gendut.klipp.engine.TransitionLooks.load(o.optJSONObject("transitionBlur"))
            state.clearSideHistory()
            // playback gets the look, grades and master right away – otherwise it is rebuilt just after start
            playback?.grades = HashMap(gs.grades)
            playback?.look = lookFromJson(o)
            playback?.master = masterFromJson(o)
            // (loading is not a change: the loaded look becomes the baseline for undo)
            scope.launch { gradeFromJson(o, grade, mix); lookMark[0] = LookValues.of(grade); masterMark[0] = MasterValues.of(mix) }
            if (!o.optBoolean("linkedSelection", true)) controller.setLinkedSelection(false)
        }
        playback?.onLutFallback = {
            grade.tetra = false
            lookMark[0] = LookValues.of(grade) // (the app's own switch, no undo step)
            toaster.show(Str.t(R.string.app_lut_fallback))
        }
        state.onTargetTrack = { controller.setTarget(it) }
        state.onAddMarker = { controller.addMarker(it); toaster.show(Str.t(R.string.app_marker_at, formatTc(it))) }
        state.onRemoveMarker = { controller.removeMarker(it); toaster.show(Str.t(R.string.app_marker_removed)) }
        state.onClearMarkers = { controller.clearMarkers(); toaster.show(Str.t(R.string.app_markers_removed)) }
        state.onSpeed = { id, p -> CrashReport.note("speed $p % on clip $id"); controller.setSpeed(id, p) }
        state.onBeatMarkers = { controller.addBeatMarkers(it) }
        state.onSpeedRamp = { id, r -> CrashReport.note("speed ramp ${r.name} on clip $id"); controller.speedRamp(id, r) }
        state.onLinkedSelection = { controller.setLinkedSelection(it) }
        state.onCaptionsVisible = { controller.setCaptionsVisible(it) }
        state.uriOfItem = controller::uriOf
        // audio effect controls are heard directly (playback chains), without a rebuild
        state.onFxLive = { clip, i, param, v -> playback?.fx?.live(clip, i, param, v) }
        // the master (fader and normalization) lives in the app, not the engine: to playback and saved – and an undo step
        // of its own, in the same history as the timeline (it used to be left out: undo took the latest cut instead)
        fun applyMaster() { playback?.master = mixMaster(mix); playback?.refresh(state.playhead.toDouble()); controller.requestSave() }
        fun restoreMaster(v: MasterValues) { masterMark[0] = v; mix.normDb = v.norm; scope.launch { mix.gains[3].snapTo(v.fader); applyMaster() } }
        masterMark[0] = MasterValues.of(mix)
        mix.onCommit = { i ->
            if (i == 3) {
                applyMaster()
                val now = MasterValues.of(mix)
                val before = masterMark[0] ?: now
                if (now != before) {
                    masterMark[0] = now
                    state.recordSide("Master", { restoreMaster(before) }, { restoreMaster(now) })
                }
            } else {
                val t = controller.snapshot.sequence?.audio?.getOrNull(i)
                if (t != null && t.hasAutomation) {
                    // the track has automation: the fader writes a keyframe at the playhead (if it moved)
                    controller.setAudioTrack(i, null, mix.mute[i], mix.solo[i])
                    val at = state.playhead.toDouble()
                    if (kotlin.math.abs(t.volumeAt(at) - mix.db(i)) > 0.05) controller.setTrackKeyframe(i, at, mix.db(i))
                } else controller.setAudioTrack(i, mix.db(i), mix.mute[i], mix.solo[i])
            }
        }
        controller.restore()
    }
    // the mixer shows the engine's track settings (after restore, undo etc.)
    LaunchedEffect(controller?.revision) {
        val q = controller?.snapshot?.sequence ?: return@LaunchedEffect
        q.audio.take(3).forEachIndexed { i, t ->
            mix.mute[i] = t.muted
            mix.solo[i] = t.solo
            // with automation the playhead drives the fader (see AudioPanel)
            if (t.hasAutomation) return@forEachIndexed
            val f = dbToFader(t.volumeDb)
            if (kotlin.math.abs(mix.gains[i].value - f) > 0.002f) mix.gains[i].animateTo(f, Springs.settle())
        }
    }
    // timeline look: a change that has rested a while becomes an undo step (undo restores the values without a new step)
    LaunchedEffect(grade, state) {
        lookMark[0] = LookValues.of(grade)
        androidx.compose.runtime.snapshotFlow { LookValues.of(grade) }
            .debounce(500)
            .collect { now ->
                val before = lookMark[0] ?: now
                if (now == before) return@collect
                lookMark[0] = now
                state.recordSide("Look", { lookMark[0] = before; scope.launch { before.applyTo(grade) } }, { lookMark[0] = now; scope.launch { now.applyTo(grade) } })
            }
    }
    // the color panel drives the real video
    if (playback != null) LaunchedEffect(playback) {
        // while dragging, the program monitor shows the look directly (LivePreview); the player is rebuilt when the control rests
        androidx.compose.runtime.snapshotFlow { lookOf(grade) }
            .debounce(250)
            .collect { look ->
                if (look != playback.look) { playback.look = look; playback.refresh(state.playhead.toDouble()); controller?.requestSave() }
            }
    }
    LaunchedEffect(controller) {
        if (controller != null) state.onCopyToSource = { id ->
            scope.launch {
                controller.copyToSource(id)?.let {
                    state.playing = false
                    playback?.player?.pause()
                    state.selected = null
                    sourceMedia = it
                    page.animateTo(0f, Springs.page())
                    toaster.show(Str.t(R.string.app_copied_to_source))
                }
            }
        }
    }

    fun goTo(i: Int) {
        val target = i.coerceIn(0, MODES.lastIndex)
        if (target != page.targetValue.roundToInt()) haptics.press()
        scope.launch { page.animateTo(target.toFloat(), Springs.page()) }
    }
    // the edit page's row for an empty selection: what is added. Each opens its sheet over the edit page (it used to
    // jump to the sound or text page, each time to a different tab); without a project the text page is the prototype's
    androidx.compose.runtime.DisposableEffect(state, controller) {
        state.onAddAction = { what ->
            when (what) {
                AddAction.MEDIA -> if (controller != null) { if (controller.snapshot.media.isEmpty()) openImport() else mediaOpen = true } else state.addClipAtPlayhead()
                AddAction.SOUND, AddAction.VOICE -> if (audioTools != null) addSheet = what else toaster.show(Str.t(R.string.app_prototype_mode))
                AddAction.TITLE, AddAction.CAPTIONS -> if (state.props != null) addSheet = what
                    else { state.openTab = if (what == AddAction.TITLE) "textTab:titles" else "textTab:captions"; goTo(3) }
            }
        }
        onDispose { state.onAddAction = null }
    }
    // the system back closes what is open, innermost first, and only then leaves the app (it used to leave from
    // everywhere); the projects and settings pages have their own (they are composed later and win while shown)
    val pageNow = page.targetValue.roundToInt()
    androidx.activity.compose.BackHandler(enabled = mediaOpen || sourceMedia != null || state.inspecting || pageNow != 0) {
        when {
            mediaOpen -> mediaOpen = false
            sourceMedia != null -> sourceMedia = null
            state.inspecting -> state.inspecting = false
            else -> goTo(0)
        }
    }
    fun undo() { if (state.undo()) { haptics.confirm(); toaster.show(Str.t(R.string.app_undone)) } }
    fun redo() { if (state.redo()) { haptics.confirm(); toaster.show(Str.t(R.string.app_redone)) } }

    // Playback
    LaunchedEffect(state.playing) {
        if (!state.playing || controller != null) return@LaunchedEffect
        var last = -1L
        while (isActive && state.playing) {
            withFrameNanos { now ->
                if (last > 0) state.playhead += (now - last) / 1_000_000_000f
                last = now
            }
            if (state.playhead >= state.projectEnd) { state.playhead = state.projectEnd; state.playing = false }
        }
    }

    CompositionLocalProvider(LocalGradeState provides gs) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(K.Bg)
            // Global multi-finger gestures: 2-finger tap = undo, 3-finger tap = redo, 3-finger swipe = switch mode
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val t0 = first.uptimeMillis
                    var maxCount = 1
                    var three = false
                    var swiping = false
                    var startX = 0f
                    var base = 0f
                    var twoMoved = false
                    var lifted = false
                    val vt = VelocityTracker()
                    var endTime = t0
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = ev.changes.filter { it.pressed }
                        endTime = ev.changes.maxOf { it.uptimeMillis }
                        if (pressed.size > maxCount) maxCount = pressed.size
                        fingers = pressed.size
                        if (pressed.size >= 3 && !three) {
                            three = true
                            startX = pressed.map { it.position.x }.average().toFloat()
                            base = page.value
                        }
                        if (maxCount == 2 && ev.changes.any { abs(it.position.x - it.previousPosition.x) + abs(it.position.y - it.previousPosition.y) > 0.5f }) {
                            val total = ev.changes.sumOf { (abs(it.position.x - it.previousPosition.x) + abs(it.position.y - it.previousPosition.y)).toDouble() }
                            if (total > 2.0) twoMoved = true
                        }
                        if (three) {
                            if (pressed.size < 3) lifted = true
                            if (pressed.isNotEmpty() && !lifted) {
                                val cx = pressed.map { it.position.x }.average().toFloat()
                                vt.addPosition(ev.changes.first().uptimeMillis, androidx.compose.ui.geometry.Offset(cx, 0f))
                                val dx = cx - startX
                                if (!swiping && abs(dx) > viewConfiguration.touchSlop * 2) swiping = true
                                if (swiping) {
                                    var p = base - dx / size.width
                                    if (p < 0f) p *= 0.3f
                                    if (p > MODES.lastIndex) p = MODES.lastIndex + (p - MODES.lastIndex) * 0.3f
                                    scope.launch { page.snapTo(p) }
                                }
                            }
                            ev.changes.forEach { it.consume() }
                        }
                        if (pressed.isEmpty()) break
                    }
                    fingers = 0
                    val dur = endTime - t0
                    if (DEBUG_GESTURES) println("GEST three=$three swiping=$swiping max=$maxCount v=${vt.calculateVelocity().x} page=${page.value} base=$base dur=$dur")
                    if (three) {
                        if (swiping) {
                            val v = vt.calculateVelocity().x
                            val cur = base.roundToInt()
                            val moved = page.value - base
                            var target = page.value.roundToInt()
                            if (v < -500f || moved > 0.22f) target = cur + 1
                            else if (v > 500f || moved < -0.22f) target = cur - 1
                            goTo(target)
                        } else if (dur < 400) redo()
                    } else if (maxCount == 2 && !twoMoved && dur < 300) {
                        undo()
                    }
                }
            }
    ) {
        val landscape = maxWidth > maxHeight
        // the program monitor's height in portrait (drag the handle under the transport; double tap = 16:9 again)
        val defaultMonitorH = maxWidth * 9f / 16f
        val maxMonitorH = maxHeight * 0.55f
        var monitorH by remember { mutableStateOf(AppPrefs.monitorHeight(context).takeIf { it > 0f }?.dp ?: defaultMonitorH) }
        // the picture's scrub speed while it is dragged (shown as a badge on the picture)
        var monitorTier by remember { mutableIntStateOf(0) }
        /** The clips the color page grades: the video clips of the three lowest tracks, in timeline order. */
        @Composable
        fun colorClips(): List<ColorClip> {
            val q = controller?.snapshot?.sequence
            return remember(q) {
                q?.video?.take(3)?.flatMapIndexed { k, t ->
                    t.clips.filter { it.text == null }.map { c -> ColorClip(c.id, c.name, c.start.toFloat(), c.end.toFloat(), controller.uriOf(c.item), c.sourceIn, 2 - k, c.speed, c.hold, c.reverse) }
                }.orEmpty().sortedBy { it.start }
            }
        }

        @Composable
        fun MonitorArea(mod: Modifier) {
            val grading = gs.active && controller != null && playback != null
            val clips = if (grading) colorClips() else emptyList()
            val clipsNow = rememberUpdatedState(clips)
            // touch on the picture: scrub, frame steps, double tap = full screen (not while the picture's own tools are
            // in use). On the color page the scrub stays inside the clip being graded, and gives way to the dropper, the
            // compare line and the window's handles
            val pictureTouch = Modifier.monitorTouch(
                state,
                enabled = !state.inspecting && sourceMedia == null && (!gs.active || grading && gs.pick == null && gs.compare == null && gs.windowEdit == null),
                haptics = haptics,
                onTier = { monitorTier = it }, onDoubleTap = { state.playing = false; fullscreen = true },
                within = if (grading) ({ currentColorClip(clipsNow.value, state)?.let { it.start..(it.end - 1f / FPS).coerceAtLeast(it.start) } }) else null,
            )
            // the picture a little in from the screen's edges, with square corners (rounded ones looked like a crop)
            Box(mod.padding(start = 8.dp, end = 8.dp, top = 4.dp).clipToBounds().background(Color.Black).then(pictureTouch).testTag("monitorArea"), contentAlignment = Alignment.Center) {
                if (controller != null && playback != null) ProgramMonitor(controller, playback, state, gs, lookOf(grade), Modifier.fillMaxSize(), onFullscreen = { state.playing = false; fullscreen = true }) { if (controller.snapshot.media.isEmpty()) openImport() else mediaOpen = true }
                else Monitor(state, grade, title)
                ScrubSpeedBadge(monitorTier, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                // the color page's tools over the picture: compare, scopes, full screen
                if (grading) ColorViewerTools(state, gs, clips, lookOf(grade), onFullscreen = { state.playing = false; fullscreen = true })
                // the program's audio level (what is heard, after the master)
                else if (controller != null) ProgramMeter(state.playing, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp, top = 10.dp, bottom = 10.dp).width(16.dp).fillMaxHeight())
            }
        }

        /** The top area: the source when one is open, otherwise the program monitor and the transport. */
        @Composable
        fun TopArea(fill: Boolean, monitorMod: Modifier) {
            val src = sourceMedia
            AnimatedContent(
                targetState = src,
                transitionSpec = {
                    (slideInVertically(spring(0.8f, 380f)) { -it / 3 } + fadeIn()) togetherWith (slideOutVertically(spring(1f, 500f)) { -it / 3 } + fadeOut())
                },
                contentKey = { it?.id },
                label = "topArea",
                modifier = if (fill) Modifier.fillMaxHeight() else Modifier,
            ) { media ->
                val srcUri = media?.let { controller?.uriOf(it.id) }
                if (media != null && controller != null && sourcePlayback != null && srcUri != null) {
                    SourcePanel(
                        media, srcUri, sourcePlayback, state,
                        setMarks = { a, b -> controller.setMarks(media.id, a, b) },
                        edit = { ow, done -> controller.editFromSource(media.id, state.playhead.toDouble(), ow, done) },
                        onClose = { sourceMedia = null },
                        fill = fill,
                        text = remember(controller, media.id) {
                            object : SourceText {
                                override val revision get() = controller.transcriptRevision
                                override suspend fun words() = controller.mediaWords(media.id)
                                override fun transcribe(context: android.content.Context, pick: SpeechPick) =
                                    se.gendut.klipp.engine.AutoCaptions.transcriptOf(context, controller, pick.model, pick.language, listOf(media.id))
                            }
                        },
                    )
                } else Column(if (fill) Modifier.fillMaxHeight() else Modifier) {
                    MonitorArea(monitorMod)
                    Transport(state)
                }
            }
        }

        /** The mode pages (edit, color, audio, text) side by side; the page position is read in drawing during a swipe. */
        @Composable
        fun Pages(mod: Modifier) {
            // which modes are visible and which is on top only change when a page is passed – the page position
            // during the swipe is read in graphicsLayer (drawing), so the whole screen isn't recomposed every frame
            val visiblePages by remember { derivedStateOf { MODES.indices.filter { abs(it - page.value) < 1.1f } } }
            val frontPage by remember { derivedStateOf { page.value.roundToInt() } }
            BoxWithConstraints(mod) {
                val pageW = constraints.maxWidth.toFloat()
                for (i in MODES.indices) {
                    // key: each page keeps its state when an adjacent page enters or leaves during a swipe
                    // (without it the color page's state moved to the edit page – the tool jumped to Wheels)
                    if (i in visiblePages) androidx.compose.runtime.key(i) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .zIndex(if (i == frontPage) 1f else 0f)
                                .graphicsLayer {
                                    val d = i - page.value
                                    translationX = d * pageW
                                    val sc = 1f - 0.06f * abs(d).coerceAtMost(1f)
                                    scaleX = sc; scaleY = sc
                                    shape = RoundedCornerShape((28f * abs(d).coerceAtMost(1f)).dp)
                                    clip = abs(d) > 0.001f
                                }
                                .background(K.Bg)
                        ) {
                            // the bar (72 dp and the 12 dp under it) lies over the page's bottom in portrait – not in
                            // landscape, where it is under the picture, nor over the source viewer, which hides it
                            val bar = if (landscape || (i == 0 && sourceMedia != null)) 0.dp else 84.dp
                            CompositionLocalProvider(LocalBarSpace provides bar) {
                                when (i) {
                                    0 -> EditPage(
                                        state, tool, { tool = it }, toaster, tips = controller != null && !landscape, overlays = sourceMedia == null,
                                        onSettings = if (controller != null) ({ showSettings = true }) else null, onAppSettings = { showAppSettings = true },
                                    )
                                    1 -> if (controller != null) {
                                        ColorPage(state, gs, colorClips(), lookOf(grade), onImportLut = { cb -> lutTarget = cb; lutPicker.launch(arrayOf("*/*")) }) {
                                            ColorPanel(grade, { lutTarget = null; lutPicker.launch(arrayOf("*/*")) }, scroll = false)
                                        }
                                    } else ColorPanel(grade, null)
                                    2 -> { controller?.revision; AudioPanel(mix, state, audioTools, dockTabs = true) }
                                    else -> if (state.props != null) TitlesPanel(state, dockTabs = true) { goTo(0) } else TextPanel(title)
                                }
                            }
                        }
                    }
                }
            }
        }

        val topBar: @Composable () -> Unit = {
            TopBar(
                { page.value }, { goTo(it) },
                onProjects = { if (controller != null) showProjects = true else toaster.show(Str.t(R.string.app_prototype_mode)) },
                onExport = ::export,
            )
        }

        if (fullscreen) {
            FullViewer(state, controller, playback, sourcePlayback, sourceMedia, grade, title, gs) { fullscreen = false }
        } else {
        if (landscape) {
            // landscape: picture (and source) on the left, the modes with the timeline on the right
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.weight(0.46f).fillMaxHeight()) {
                    topBar()
                    Column(Modifier.weight(1f)) { TopArea(fill = true, monitorMod = Modifier.fillMaxWidth().weight(1f)) }
                    // room for the floating toolbar under the picture (the timeline keeps the whole right side)
                    if (sourceMedia == null) Spacer(Modifier.height(92.dp))
                }
                Box(Modifier.weight(0.54f).fillMaxHeight()) {
                    Pages(Modifier.fillMaxSize())
                }
            }
        } else {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            topBar()
            TopArea(fill = false, monitorMod = Modifier.fillMaxWidth().height(monitorH.coerceIn(MIN_MONITOR_H, maxMonitorH.coerceAtLeast(MIN_MONITOR_H))))
            if (sourceMedia == null) MonitorHandle(
                onDrag = { dy -> monitorH = (monitorH + with(density) { dy.toDp() }).coerceIn(MIN_MONITOR_H, maxMonitorH.coerceAtLeast(MIN_MONITOR_H)) },
                onEnd = { AppPrefs.setMonitorHeight(context, monitorH.value) },
                onReset = { haptics.tick(); monitorH = defaultMonitorH; AppPrefs.setMonitorHeight(context, 0f) },
            )
            Pages(Modifier.weight(1f).fillMaxWidth())
        }
        }

        // At the bottom: undo/redo and the dock (the actions for what is selected, or the page's tabs), with what is
        // selected named above. Hidden on the edit page while the source is open – the timeline is needed then.
        // In landscape it sits under the picture on the left.
        val bottomBar by remember { derivedStateOf { sourceMedia == null || page.value.roundToInt() != 0 } }
        AnimatedVisibility(
            bottomBar,
            enter = slideInVertically(spring(0.7f, 400f)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(if (landscape) 0.46f else 1f),
        ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 8.dp, end = 8.dp, bottom = 12.dp).fillMaxWidth(),
        ) {
            SelectionChip(state, { page.value })
            Row(verticalAlignment = Alignment.CenterVertically) {
                UndoRedo(state, ::undo, ::redo)
                Spacer(Modifier.width(8.dp))
                Dock(state, { page.value }, audioTabs(mix, audioTools != null), Modifier.weight(1f))
            }
        }
        }

        // Finger indicator for multi-finger gestures
        AnimatedVisibility(
            visible = fingers >= 2,
            enter = scaleIn(spring(0.4f, 500f)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(28.dp)).background(K.SurfaceHighest.copy(alpha = 0.85f)).padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(fingers.coerceAtMost(5)) { Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(K.Primary)) }
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(if (fingers >= 3) R.string.app_gesture_three else R.string.app_gesture_two),
                    color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }

        when (addSheet) {
            AddAction.SOUND -> audioTools?.addSound?.let { add -> SoundFxSheet(onDismiss = { addSheet = null }, speech = true) { file, name, secs -> add(file, name, secs); addSheet = null } }
            AddAction.VOICE -> VoiceSheet(voice) { addSheet = null }
            AddAction.TITLE -> TitleSheet(state) { addSheet = null }
            AddAction.CAPTIONS -> CaptionsSheet(state) { addSheet = null }
            else -> {}
        }

        if (controller != null && sourcePlayback != null) {
            MediaSheet(mediaOpen, remember(controller) { controllerLibrary(controller) }, ::openImport, onOpen = { state.playing = false; playback?.player?.pause(); sourceMedia = it; mediaOpen = false; goTo(0) }, onClose = { mediaOpen = false })
            ExportOverlay(exportProgress)
        }

        } // end of the editor (not full screen)

        if (confirmNew) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmNew = false },
                title = { Text(stringResource(R.string.app_new_project_title)) },
                text = { Text(stringResource(R.string.app_new_project_text)) },
                confirmButton = { FlatTextButton(onClick = { confirmNew = false; controller?.newProject() }) { Text(stringResource(R.string.app_new_project)) } },
                dismissButton = { FlatTextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.app_cancel)) } },
                containerColor = K.SurfaceHigh,
            )
        }

        // the projects page slides in over everything
        if (controller != null) {
            AnimatedVisibility(
                showProjects,
                enter = slideInVertically(spring(0.8f, 380f)) { it / 4 } + fadeIn(),
                exit = slideOutVertically(spring(1f, 500f)) { it / 4 } + fadeOut(),
            ) {
                val backend = remember(controller) { controllerProjects(controller) }
                // the system back closes the page (it left the app)
                androidx.activity.compose.BackHandler { showProjects = false }
                ProjectsScreen(backend, onSettings = { showAppSettings = true }) { showProjects = false }
            }
            if (showSettings) {
                val q = controller.snapshot.sequence
                ProjectSettingsDialog(
                    controller.currentName, q?.width ?: 1920, q?.height ?: 1080, controller.snapshot.fps, q?.sampleRate ?: 48000,
                    onDismiss = { showSettings = false },
                    extra = {
                        PresetRow(stringResource(R.string.app_render_timeline), stringResource(R.string.app_render_timeline_hint), false) {
                            showSettings = false; se.gendut.klipp.engine.RenderCache.halted = false; se.gendut.klipp.engine.RenderCache.renderAll = true
                        }
                        PresetRow(stringResource(if (se.gendut.klipp.engine.RenderCache.auto) R.string.app_render_auto_on else R.string.app_render_auto_off),
                            stringResource(R.string.app_render_auto_hint), se.gendut.klipp.engine.RenderCache.auto) {
                            se.gendut.klipp.engine.RenderCache.setAuto(context, !se.gendut.klipp.engine.RenderCache.auto)
                        }
                        PresetRow(stringResource(R.string.app_delete_renders), stringResource(R.string.app_megabytes, se.gendut.klipp.engine.RenderCache.bytes() / 1e6), false) {
                            showSettings = false; se.gendut.klipp.engine.RenderCache.clear(); toaster.show(Str.t(R.string.app_renders_deleted))
                        }
                        PresetRow(stringResource(R.string.app_app_settings), stringResource(R.string.app_app_settings_hint), false) { showSettings = false; showAppSettings = true }
                    },
                ) { n, w, h, f, sr ->
                    showSettings = false
                    if (n != controller.currentName) controller.renameProject(controller.currentId, n)
                    if (q == null || w != q.width || h != q.height || kotlin.math.abs(f - controller.snapshot.fps) > 0.001 || sr != q.sampleRate) controller.setSequenceSettings(w, h, f, sr)
                }
            }
        }

        // settings slide in over everything (even without an engine)
        AnimatedVisibility(
            showAppSettings,
            enter = slideInVertically(spring(0.8f, 380f)) { it / 4 } + fadeIn(),
            exit = slideOutVertically(spring(1f, 500f)) { it / 4 } + fadeOut(),
        ) {
            androidx.activity.compose.BackHandler { showAppSettings = false }
            SettingsScreen(
                onClose = { showAppSettings = false },
                onCenterDefault = { state.centerPlayhead = it },
                onMagnetDefault = { state.magnet = it },
            )
        }

        ToastHost(toaster, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 70.dp))
    }
    }
}

@Composable
fun RoundIcon(
    icon: ImageVector, desc: String, enabled: Boolean = true, tint: Color = K.OnSurface, modifier: Modifier = Modifier,
    /** The key click on a press; off where the action gives its own (it may end in a refusal instead). */
    haptic: Boolean = true,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, label = "a")
    Box(
        modifier.size(48.dp).pressPop(src, 0.8f).graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, interactionSource = src, indication = null) {
                if (haptic) haptics.press()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp)) }
}

/** Go to a typed time. */
@Composable
internal fun TimecodeDialog(now: Float, onDismiss: () -> Unit, onGo: (Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = parseTimecode(text, now)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_go_to_time)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    text, { text = it }, singleLine = true, placeholder = { Text(formatTc(now), fontFamily = FontFamily.Monospace) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { parsed?.let(onGo) }),
                    modifier = Modifier.focusRequester(focus).testTag("timecodeField"),
                )
                Spacer(Modifier.height(6.dp))
                Text(if (text.isNotBlank() && parsed != null) "→ " + formatTc(parsed) else stringResource(R.string.app_timecode_hint), color = K.OnSurfaceVar, fontSize = 12.sp)
            }
        },
        confirmButton = { FlatTextButton(onClick = { parsed?.let(onGo) }, enabled = parsed != null) { Text(stringResource(R.string.app_go)) } },
        dismissButton = { FlatTextButton(onClick = onDismiss) { Text(stringResource(R.string.app_cancel)) } },
        containerColor = K.SurfaceHigh,
    )
}

/**
 * Pre-render chip at the timeline: "Render" when there are heavy parts that aren't rendered (then everything plays from
 * finished files, without lag); during rendering, the progress – tap to cancel.
 */
@Composable
internal fun RenderChip(modifier: Modifier) {
    val haptics = rememberHaptics()
    val rc = se.gendut.klipp.engine.RenderCache
    val p = rc.progress
    val todo = rc.states.any { it.heavy && !it.ready }
    AnimatedVisibility(p != null || todo, modifier = modifier, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
        Row(
            Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(K.SurfaceHighest)
                .clickable {
                    haptics.confirm()
                    if (p != null || rc.renderAll) { rc.renderAll = false; rc.halted = true } else { rc.halted = false; rc.renderAll = true }
                }
                .padding(horizontal = 10.dp).testTag("renderChip"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Bolt, null, tint = if (p != null) K.Primary else K.OnSurface, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (p != null) stringResource(R.string.app_rendering_progress, (p * 100).toInt()) else if (rc.renderAll) stringResource(R.string.app_waiting) else stringResource(R.string.app_render),
                color = K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The color labels in a row (plus none): the current one ringed, and kept in view where the row scrolls. Each is
 * pressed over 44 dp, though the dot is smaller.
 */
@Composable
internal fun LabelPicker(current: ClipLabel?, onPick: (ClipLabel?) -> Unit) {
    val none = stringResource(R.string.app_label_none)
    Box(
        Modifier.inViewWhen(current == null).size(44.dp).clip(CircleShape)
            .clickable(onClickLabel = none) { onPick(null) }.testTag("label:none"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).border(if (current == null) 2.5.dp else 1.5.dp, if (current == null) K.OnSurface else K.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(18.dp, 2.dp).graphicsLayer { rotationZ = -45f }.background(K.Outline)) }
    }
    for (l in ClipLabel.entries) {
        val name = stringResource(l.title)
        Box(
            Modifier.inViewWhen(l == current).size(44.dp).clip(CircleShape)
                .clickable(onClickLabel = name) { onPick(l) }.testTag("label:${l.name}"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(34.dp)
                    .then(if (l == current) Modifier.border(2.5.dp, K.OnSurface, CircleShape).padding(5.dp) else Modifier.padding(3.dp))
                    .clip(CircleShape).background(l.color)
            )
        }
    }
}

/** The grip under the transport: drag to make the picture taller or shorter; double tap = back to 16:9. */
@Composable
private fun MonitorHandle(onDrag: (Float) -> Unit, onEnd: () -> Unit, onReset: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(14.dp)
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = onEnd) { ch, dy -> ch.consume(); onDrag(dy) } }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
            .testTag("monitorHandle"),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(K.OutlineVar)) }
}

@Composable
private fun ToastHost(toaster: Toaster, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(toaster.key) {
        if (toaster.key == 0) return@LaunchedEffect
        visible = true
        delay(1600)
        visible = false
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(spring(0.5f, 500f)) { -it * 2 } + fadeIn(),
        exit = slideOutVertically { -it * 2 } + fadeOut(),
        modifier = modifier
    ) {
        Text(
            toaster.message ?: "",
            color = K.OnMark,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(K.Mark).padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

/** The master fader (0..1, where it is going) and the normalization (dB) as values (for the undo history). */
data class MasterValues(val fader: Float, val norm: Float) {
    companion object {
        fun of(m: AudioMix) = MasterValues(m.gains[3].targetValue, m.normDb)
    }
}

/** The timeline look as values (for the undo history). */
data class LookValues(val exposure: Float, val contrast: Float, val temp: Float, val tint: Float, val sat: Float, val lut: String?, val lutMix: Float, val tetra: Boolean) {
    suspend fun applyTo(g: ColorGrade) {
        g.exposure.snapTo(exposure); g.contrast.snapTo(contrast); g.temp.snapTo(temp); g.tint.snapTo(tint); g.sat.snapTo(sat)
        g.lutName = lut; g.lutMix.snapTo(lutMix); g.tetra = tetra
    }

    companion object {
        fun of(g: ColorGrade) = LookValues(g.exposure.value, g.contrast.value, g.temp.value, g.tint.value, g.sat.value, g.lutName, g.lutMix.value, g.tetra)
    }
}

fun lookOf(g: ColorGrade) = se.gendut.klipp.engine.Look(
    g.exposure.value, g.contrast.value, g.temp.value, g.tint.value, g.sat.value,
    lut = g.lutName?.let { se.gendut.klipp.engine.LutStore.get(it) }, lutMix = g.lutMix.value, tetra = g.tetra,
)

fun gradeToJson(g: ColorGrade, m: AudioMix): org.json.JSONObject = org.json.JSONObject()
    .put("exposure", g.exposure.value.toDouble()).put("contrast", g.contrast.value.toDouble())
    .put("temp", g.temp.value.toDouble()).put("tint", g.tint.value.toDouble()).put("sat", g.sat.value.toDouble())
    .put("lut", g.lutName ?: org.json.JSONObject.NULL).put("lutMix", g.lutMix.value.toDouble()).put("tetra", g.tetra)
    .put("master", m.gains[3].targetValue.toDouble()).put("norm", m.normDb.toDouble())

suspend fun gradeFromJson(o: org.json.JSONObject, g: ColorGrade, m: AudioMix) {
    g.exposure.snapTo(o.optDouble("exposure", 0.0).toFloat()); g.contrast.snapTo(o.optDouble("contrast", 0.0).toFloat())
    g.temp.snapTo(o.optDouble("temp", 0.0).toFloat()); g.tint.snapTo(o.optDouble("tint", 0.0).toFloat())
    g.sat.snapTo(o.optDouble("sat", 0.0).toFloat())
    g.lutName = if (o.isNull("lut")) null else o.optString("lut").takeIf { se.gendut.klipp.engine.LutStore.get(it) != null }
    g.lutMix.snapTo(o.optDouble("lutMix", 1.0).toFloat()); g.tetra = o.optBoolean("tetra", true)
    m.gains[3].snapTo(o.optDouble("master", 0.75).toFloat())
    m.normDb = o.optDouble("norm", 0.0).toFloat().let { if (it.isNaN()) 0f else it }
}

/** Master: the fader's gain (where it is going – it springs after the finger) × the normalization (dB, from the loudness measurement). */
fun mixMaster(m: AudioMix): Float = masterOf(m.gains[3].targetValue) * Math.pow(10.0, m.normDb / 20.0).toFloat()
private fun masterOf(v: Float): Float = if (v < 0.001f) 0f else (v / 0.75f).let { it * it }.coerceIn(0f, 4f)

/** The same look and master that [gradeFromJson] + [lookOf] give – directly, so the first playback is built with them. */
fun lookFromJson(o: org.json.JSONObject): se.gendut.klipp.engine.Look {
    val lutName = if (o.isNull("lut")) null else o.optString("lut").takeIf { se.gendut.klipp.engine.LutStore.get(it) != null }
    return se.gendut.klipp.engine.Look(
        o.optDouble("exposure", 0.0).toFloat(), o.optDouble("contrast", 0.0).toFloat(), o.optDouble("temp", 0.0).toFloat(),
        o.optDouble("tint", 0.0).toFloat(), o.optDouble("sat", 0.0).toFloat(),
        lut = lutName?.let { se.gendut.klipp.engine.LutStore.get(it) }, lutMix = o.optDouble("lutMix", 1.0).toFloat(), tetra = o.optBoolean("tetra", true),
    )
}
fun masterFromJson(o: org.json.JSONObject): Float =
    masterOf(o.optDouble("master", 0.75).toFloat()) * Math.pow(10.0, o.optDouble("norm", 0.0).toFloat().let { if (it.isNaN()) 0f else it } / 20.0).toFloat()

/**
 * Full screen: just the video – the source if it's open, otherwise the timeline. Tap = play/pause, double tap or back =
 * [onExit].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FullViewer(
    state: EditorState,
    controller: se.gendut.klipp.engine.ProjectController?,
    playback: se.gendut.klipp.engine.ProgramPlayback?,
    source: se.gendut.klipp.engine.SourcePlayback?,
    sourceMedia: se.gendut.klipp.engine.MediaInfo?,
    grade: ColorGrade,
    title: TitleState,
    gs: GradeState? = null,
    onExit: () -> Unit,
) {
    androidx.activity.compose.BackHandler { onExit() }
    val haptics = rememberHaptics()
    var overlay by remember { mutableIntStateOf(1) }
    var srcPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(overlay) { delay(2200); overlay = 0 }
    val overlayA by animateFloatAsState(if (overlay > 0) 1f else 0f, spring(1f, 300f), label = "ov")
    val isSource = sourceMedia != null && source != null
    val playing = if (isSource) srcPlaying else state.playing
    val q = controller?.snapshot?.sequence
    val aspect = when {
        isSource && sourceMedia.width > 0 -> sourceMedia.width.toFloat() / sourceMedia.height
        q != null && q.width > 0 -> q.width.toFloat() / q.height
        else -> 16f / 9f
    }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val activity = view.context as? android.app.Activity
        val window = activity?.window
        val ctl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        // the screen turns with the picture: landscape for a landscape project, portrait for a portrait one (either way up)
        val before = activity?.requestedOrientation
        val wasLandscape = view.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        fullscreenOrientation(aspect)?.let { activity?.requestedOrientation = it }
        onDispose {
            ctl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (activity != null && before != null && activity.requestedOrientation != before) {
                // back the way the editor was: the sensor alone wouldn't turn a phone lying flat back. A moment later the
                // earlier setting (usually: follow the sensor) applies again.
                activity.requestedOrientation = if (wasLandscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                view.postDelayed({ activity.requestedOrientation = before }, 1500)
            }
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("fullViewer")
            .pointerInput(isSource) {
                detectTapGestures(
                    onDoubleTap = { haptics.confirm(); if (isSource) source.player.pause() else state.playing = false; onExit() },
                    onTap = {
                        haptics.confirm()
                        if (isSource) {
                            srcPlaying = !srcPlaying
                            if (srcPlaying) source.player.play() else source.player.pause()
                        } else {
                            if (!state.playing && state.playhead >= state.projectEnd - 0.05f) state.playhead = 0f
                            state.playing = !state.playing
                        }
                        overlay++
                    },
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // fitted inside the screen (the whole picture, bars where the shapes differ), clipped to the frame like the monitor:
        // a scaled or moved layer must not show outside the program image
        Box(Modifier.fillMaxSize().wrapContentSize().aspectRatio(aspect).clipToBounds()) {
            when {
                isSource -> FitPlayerSurface(source.player, Modifier.fillMaxSize())
                controller != null && playback != null -> {
                    ProgramSurface(playback.player, usesBlendModes(), Modifier.fillMaxSize().redrawOnResize(playback, state))
                    UpperLayers(controller, state, playback.player, gradeOf = { gs?.grades?.get(it) }, look = playback.look, gradesKey = gs?.revision)
                    LaunchedEffect(Unit) { playback.onSurfaceAttached(state.playhead.toDouble()) }
                }
                else -> Monitor(state, grade, title, Modifier.fillMaxSize())
            }
        }
        if (overlayA > 0.01f) {
            Box(
                Modifier.size(88.dp).graphicsLayer { alpha = overlayA; scaleX = 0.8f + 0.2f * overlayA; scaleY = 0.8f + 0.2f * overlayA }
                    .clip(RoundedCornerShape(44.dp)).background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) { Icon(if (playing) Ic.Pause else Ic.Play, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
            Text(
                if (isSource) sourceMedia.name else formatTc(state.playhead),
                color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).graphicsLayer { alpha = overlayA }
                    .clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/** The screen orientation for full screen: follows the picture's shape; a square picture keeps the current one (null). */
fun fullscreenOrientation(aspect: Float): Int? = when {
    aspect > 1.01f -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    aspect < 0.99f -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    else -> null
}

/** The projects page's connection to the engine. */
private fun controllerProjects(c: se.gendut.klipp.engine.ProjectController) = object : ProjectsBackend {
    override val revision: Int get() = c.projectsRevision
    override fun list() = c.listProjects().map {
        ProjectCard(it.id, it.name, it.modified, it.duration, it.width, it.height, it.fps, it.clips, it.thumb, it.id == c.currentId)
    }
    override fun open(id: String) { c.openProject(id) }
    override fun create(spec: NewProjectSpec) { c.newProject(spec) }
    override fun rename(id: String, name: String) { c.renameProject(id, name) }
    override fun duplicate(id: String) { c.duplicateProject(id) }
    override fun delete(id: String) { c.deleteProject(id) }
    override fun export(id: String, withMedia: Boolean, to: android.net.Uri) { c.exportProject(id, withMedia, to) }
    override fun import(from: android.net.Uri) { c.importProject(from) }
    override val progress: Float? get() = c.packProgress
    override fun versions(id: String) = c.versions(id).map { it.stamp to it.clips }
    override fun restoreVersion(id: String, stamp: Long) { c.restoreVersion(id, stamp) }
}
