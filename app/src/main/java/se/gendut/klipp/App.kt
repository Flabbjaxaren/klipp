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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlin.math.roundToInt

var DEBUG_GESTURES = false
private val MODES = listOf(R.string.app_mode_edit, R.string.app_mode_color, R.string.app_mode_audio, R.string.app_mode_text)
private val MODE_ICONS = listOf(Ic.Edit, Ic.Color_, Ic.Audio, Ic.Text)
/** The program monitor is never smaller than this in portrait. */
private val MIN_MONITOR_H = 120.dp
/** The sound's quick fade (in and out), seconds. */
private const val QUICK_FADE = 0.5f

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
        ExportDialog(
            initial = remember { se.gendut.klipp.engine.ExportSettings.load(context, controller.currentId) },
            defaultName = remember { "Klipp_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.ROOT).format(java.util.Date()) },
            seqW = q?.width ?: 1920, seqH = q?.height ?: 1080, projectFps = controller.snapshot.fps, seconds = q?.duration ?: 0.0,
            hdrPossible = anyHdr && se.gendut.klipp.engine.EncoderCaps.hdrSupported,
            // grading is done in SDR (8 bits, gamma) – in an HDR signal it would look wrong
            hdrBlocked = if (graded) stringResource(R.string.app_hdr_blocked) else null,
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
        androidx.compose.runtime.snapshotFlow { gs.revision }.debounce(200).collect {
            val g = HashMap(gs.grades)
            if (g != playback.grades) {
                playback.grades = g
                playback.refresh(state.playhead.toDouble())?.let { toaster.show(Str.t(R.string.app_playback_rebuild_failed, it)) }
                controller?.requestSave()
            }
            val f = HashMap(gs.follows)
            if (f != lastFollows) { if (lastFollows != null) controller?.requestSave(); lastFollows = f }
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
    // requested) render in the background – only while playback is stopped, and a finished chunk is swapped in only when paused
    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            // (not the render's own revision: a finished chunk would then cancel rendering of the next)
            listOf(controller.revision, gs.revision, se.gendut.klipp.engine.RenderCache.pinned.toList(), se.gendut.klipp.engine.RenderCache.renderAll,
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
            // not while anything is playing, scrubbing or dragging (rendering takes power from the picture and the UI)
            val r = se.gendut.klipp.engine.RenderCache.render(context, snap, controller::uriOf, grades, todo,
                canWork = { !state.playing && !state.scrubbing && state.live == null && gs.live == null }) {
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
            if (r.isSuccess && all) {
                se.gendut.klipp.engine.RenderCache.renderAll = false
                toaster.show(Str.t(R.string.app_timeline_rendered))
            }
        }
    }
    // titles that follow tracked windows: tracking data and timeline to the title layer (playback, export)
    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { Triple(gs.revision, controller.revision, gs.follows.size) }.collect {
            se.gendut.klipp.engine.TitleFollow.publish(HashMap(gs.follows), HashMap(gs.grades), controller.snapshot)
        }
    }
    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        se.gendut.klipp.engine.LutStore.init(context)
        controller.extraSave = { gradeToJson(grade, mix).put("linkedSelection", state.linkedSelection).put("grades", gs.toJson())
            .put("renderPinned", org.json.JSONArray(se.gendut.klipp.engine.RenderCache.pinned.toList())) }
        // the pieces get the clip's grade as part of the split (undone with it) – no separate color step
        controller.onSplit = { old, fresh -> gs.grades[old]?.let { g -> gs.setAll(fresh.associateWith { g }, record = false) } }
        controller.onNewClips = { ids -> ids.filter { gs.grades.containsKey(it) }.takeIf { it.isNotEmpty() }?.let { stale -> gs.setAll(stale.associateWith { se.gendut.klipp.engine.ClipGrade() }, record = false) } }
        controller.onExtraRestore = { o ->
            gs.load(o.optJSONObject("grades"))
            se.gendut.klipp.engine.RenderCache.renderAll = false
            se.gendut.klipp.engine.RenderCache.halted = false
            se.gendut.klipp.engine.RenderCache.pinned.clear()
            o.optJSONArray("renderPinned")?.let { a -> for (i in 0 until a.length()) se.gendut.klipp.engine.RenderCache.pinned += a.optString(i) }
            state.clearSideHistory()
            // playback gets the look, grades and master right away – otherwise it is rebuilt just after start
            playback?.grades = HashMap(gs.grades)
            playback?.look = lookFromJson(o)
            playback?.master = masterFromJson(o)
            // (loading is not a change: the loaded look becomes the baseline for undo)
            scope.launch { gradeFromJson(o, grade, mix); lookMark[0] = LookValues.of(grade) }
            if (!o.optBoolean("linkedSelection", true)) controller.setLinkedSelection(false)
        }
        playback?.onLutFallback = {
            grade.tetra = false
            lookMark[0] = LookValues.of(grade) // (the app's own switch, no undo step)
            toaster.show(Str.t(R.string.app_lut_fallback))
        }
        state.onTargetTrack = { controller.setTarget(it) }
        state.onAddMarker = { controller.addMarker(it); toaster.show(Str.t(R.string.app_marker_at, formatTc(it))) }
        state.onSpeed = { id, p -> CrashReport.note("speed $p % on clip $id"); controller.setSpeed(id, p) }
        state.onSpeedRamp = { id, r -> CrashReport.note("speed ramp ${r.name} on clip $id"); controller.speedRamp(id, r) }
        state.onLinkedSelection = { controller.setLinkedSelection(it) }
        state.onCaptionsVisible = { controller.setCaptionsVisible(it) }
        state.uriOfItem = controller::uriOf
        // audio effect controls are heard directly (playback chains), without a rebuild
        state.onFxLive = { clip, i, param, v -> playback?.fx?.live(clip, i, param, v) }
        mix.onCommit = { i ->
            if (i == 3) { playback?.master = mixMaster(mix); playback?.refresh(state.playhead.toDouble()); controller.requestSave() }
            else {
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
        if (target != page.targetValue.roundToInt()) haptics.tick()
        scope.launch { page.animateTo(target.toFloat(), Springs.page()) }
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

    androidx.compose.runtime.CompositionLocalProvider(LocalGradeState provides gs) {
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
        // touch on the picture: scrub, frame steps, double tap = full screen (not while the picture's own tools are in use)
        val pictureTouch = Modifier.monitorTouch(
            state, enabled = !state.inspecting && !gs.active && sourceMedia == null, haptics = haptics,
            onTier = { monitorTier = it }, onDoubleTap = { state.playing = false; fullscreen = true },
        )

        @Composable
        fun MonitorArea(mod: Modifier) {
            Box(mod.background(Color.Black).then(pictureTouch).testTag("monitorArea"), contentAlignment = Alignment.Center) {
                if (controller != null && playback != null) ProgramMonitor(controller, playback, state, gs, lookOf(grade), Modifier.fillMaxSize(), onFullscreen = { state.playing = false; fullscreen = true }) { if (controller.snapshot.media.isEmpty()) openImport() else mediaOpen = true }
                else Monitor(state, grade, title)
                ScrubSpeedBadge(monitorTier, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                // the program's audio level (what is heard, after the master)
                if (controller != null) ProgramMeter(state.playing, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp, top = 10.dp, bottom = 10.dp).width(16.dp).fillMaxHeight())
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
                            when (i) {
                                0 -> EditPage(state, tool, { tool = it }, toaster, tips = controller != null && !landscape, barSpace = !landscape, overlays = sourceMedia == null)
                                1 -> if (controller != null) {
                                    val q = controller.snapshot.sequence
                                    val clips = remember(q) {
                                        q?.video?.take(3)?.flatMapIndexed { k, t ->
                                            t.clips.filter { it.text == null }.map { c -> ColorClip(c.id, c.name, c.start.toFloat(), c.end.toFloat(), controller.uriOf(c.item), c.sourceIn, 2 - k, c.speed, c.hold, c.reverse) }
                                        }.orEmpty().sortedBy { it.start }
                                    }
                                    ColorPage(state, gs, clips, lookOf(grade), onImportLut = { cb -> lutTarget = cb; lutPicker.launch(arrayOf("*/*")) }) {
                                        ColorPanel(grade, { lutTarget = null; lutPicker.launch(arrayOf("*/*")) }, scroll = false)
                                    }
                                } else ColorPanel(grade, null)
                                2 -> { controller?.revision; AudioPanel(mix, state, audioTools) }
                                else -> if (state.props != null) TitlesPanel(state) { goTo(0) } else TextPanel(title)
                            }
                        }
                    }
                }
            }
        }

        val topBar: @Composable () -> Unit = {
            TopBar(
                state, toaster, ::export,
                title = controller?.currentName?.ifBlank { null } ?: "Klipp",
                onSettings = { if (controller != null) showSettings = true },
                onAppSettings = { showAppSettings = true },
            ) { if (controller != null) showProjects = true else toaster.show(Str.t(R.string.app_prototype_mode)) }
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
                    if (sourceMedia == null) Spacer(Modifier.height(84.dp))
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

        // Floating toolbar: undo/redo, modes and the FAB (hidden while the source is open – the timeline is needed then).
        // In landscape it sits under the picture on the left.
        AnimatedVisibility(
            sourceMedia == null,
            enter = slideInVertically(spring(0.7f, 400f)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(if (landscape) 0.46f else 1f),
        ) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UndoRedo(state, ::undo, ::redo)
            Spacer(Modifier.width(8.dp))
            FloatingModes({ page.value }, Modifier.weight(1f)) { goTo(it) }
            Spacer(Modifier.width(8.dp))
            AddFab {
                haptics.confirm()
                if (page.value.roundToInt() != 0) goTo(0)
                if (controller != null) { if (controller.snapshot.media.isEmpty()) openImport() else mediaOpen = true }
                else state.addClipAtPlayhead()
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
                Modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xCC1D2026)).padding(horizontal = 18.dp, vertical = 12.dp),
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
                confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmNew = false; controller?.newProject() }) { Text(stringResource(R.string.app_new_project)) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.app_cancel)) } },
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
                ProjectsScreen(backend) { showProjects = false }
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
private fun TopBar(state: EditorState, toaster: Toaster, export: () -> Unit, title: String = "Klipp", onSettings: () -> Unit = {}, onAppSettings: () -> Unit = {}, onNewProject: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundIcon(Ic.Media, stringResource(R.string.app_projects)) { onNewProject() }
        // tap the name = project settings
        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSettings() }.padding(horizontal = 4.dp, vertical = 2.dp).testTag("projectTitle")) {
            Text(title, color = K.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(state.subtitle, color = K.OnSurfaceVar, fontSize = 12.sp, maxLines = 1)
        }
        RoundIcon(Ic.Gear, stringResource(R.string.app_settings), modifier = Modifier.size(44.dp).testTag("appSettings"), onClick = onAppSettings)
        Spacer(Modifier.width(4.dp))
        PillButton(stringResource(R.string.app_export)) { export() }
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
fun RoundIcon(icon: ImageVector, desc: String, enabled: Boolean = true, tint: Color = K.OnSurface, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, label = "a")
    Box(
        modifier.size(48.dp).graphicsLayer { scaleX = press.value; scaleY = press.value; this.alpha = alpha }
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.8f); press.animateTo(1f, Springs.pop()) }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    Box(
        Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
            .height(40.dp).clip(RoundedCornerShape(20.dp)).background(K.Primary)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.88f); press.animateTo(1f, Springs.pop()) }
                onClick()
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = K.OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Transport(state: EditorState) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    val playW by animateDpAsState(if (state.playing) 80.dp else 64.dp, spring(0.45f, 500f), label = "pw")
    val playR by animateDpAsState(if (state.playing) 24.dp else 16.dp, spring(0.45f, 500f), label = "pr")
    val playBg = K.Primary
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // tap the timecode = type a time to go to (digits as in Premiere, or +/- to move)
        var tcOpen by remember { mutableStateOf(false) }
        Text(
            formatTc(state.playhead), color = K.Primary, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(108.dp).clip(RoundedCornerShape(8.dp)).clickable { haptics.tick(); state.playing = false; tcOpen = true }.testTag("timecode"),
        )
        if (tcOpen) TimecodeDialog(state.playhead, onDismiss = { tcOpen = false }) { t -> tcOpen = false; haptics.confirm(); state.playhead = snapFrame(t) }
        Spacer(Modifier.weight(1f))
        RoundIcon(Ic.Marker, stringResource(R.string.app_add_marker)) {
            haptics.confirm()
            val cb = state.onAddMarker
            if (cb != null) cb(state.playhead) else state.markers = (state.markers + state.playhead).sorted()
        }
        RoundIcon(Ic.Prev, stringResource(R.string.app_prev_frame)) { state.playing = false; state.playhead = snapFrame((state.playhead - FRAME).coerceAtLeast(0f)); haptics.tick() }
        Box(
            Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
                .size(playW, 48.dp).clip(RoundedCornerShape(playR)).background(playBg)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    haptics.confirm()
                    scope.launch { press.snapTo(0.86f); press.animateTo(1f, Springs.pop()) }
                    if (!state.playing && state.playhead >= state.projectEnd - 0.05f) state.playhead = 0f
                    state.playing = !state.playing
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                state.playing,
                transitionSpec = { (scaleIn(spring(0.4f, 600f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                label = "playicon"
            ) { playing ->
                Icon(if (playing) Ic.Pause else Ic.Play, stringResource(if (playing) R.string.app_pause else R.string.app_play), tint = K.OnPrimary, modifier = Modifier.size(26.dp))
            }
        }
        RoundIcon(Ic.Next, stringResource(R.string.app_next_frame)) { state.playing = false; state.playhead = snapFrame(state.playhead + FRAME); haptics.tick() }
        RoundIcon(Ic.NextEdit, stringResource(R.string.app_next_edit)) {
            state.playing = false
            val next = (state.clips.flatMap { listOf(it.start, it.end) } + state.markers).filter { it > state.playhead + 0.01f }.minOrNull()
            if (next != null) { haptics.tick(); state.playhead = next } else haptics.heavy()
        }
    }
}

/** Go to a typed time. */
@Composable
private fun TimecodeDialog(now: Float, onDismiss: () -> Unit, onGo: (Float) -> Unit) {
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
        confirmButton = { androidx.compose.material3.TextButton(onClick = { parsed?.let(onGo) }, enabled = parsed != null) { Text(stringResource(R.string.app_go)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_cancel)) } },
        containerColor = K.SurfaceHigh,
    )
}

@Composable
private fun EditPage(
    state: EditorState, tool: Tool, setTool: (Tool) -> Unit, toaster: Toaster, tips: Boolean = false, barSpace: Boolean = true,
    /** The clip/transition/cut bars and the render chip over the timeline; off while the source monitor is open (the timeline is short then and they covered its ruler). */
    overlays: Boolean = true,
) {
    // the selection bars sit above the floating toolbar (portrait); in landscape the toolbar is elsewhere
    val barPad = if (barSpace) 92.dp else 12.dp
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize()) {
        // Tools: connected buttons (icon + name) where the selected one morphs into a pill. On a narrow screen (under
        // 400 dp) everything is a little tighter, so the four toggles still fit next to the tools
        val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 400
        val gap = if (compact) 0.dp else 4.dp
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tools = listOf(
                Tool.SELECT to (stringResource(R.string.app_tool_move) to Ic.Select), Tool.RIPPLE to (stringResource(R.string.app_tool_ripple) to Ic.Ripple),
                Tool.TRIM to (stringResource(R.string.app_tool_trim) to Ic.Trim), Tool.RAZOR to (stringResource(R.string.app_tool_razor) to Ic.Razor),
            )
            tools.forEachIndexed { i, (t, li) ->
                ToolButton(li.first, li.second, tool == t, first = i == 0, last = i == tools.lastIndex, compact = compact) {
                    haptics.tick(); setTool(t)
                }
            }
            Spacer(Modifier.weight(1f))
            ToggleIcon(Ic.Link, stringResource(R.string.app_linked_selection), stringResource(R.string.app_short_link), state.linkedSelection, compact) {
                val on = !state.linkedSelection
                state.linkedSelection = on
                state.onLinkedSelection?.invoke(on)
                haptics.tick()
                toaster.show(Str.t(if (on) R.string.app_linked_on else R.string.app_linked_off))
            }
            Spacer(Modifier.width(gap))
            ToggleIcon(Ic.Center, stringResource(R.string.app_locked_playhead), stringResource(R.string.app_short_center), state.centerPlayhead, compact) {
                state.centerPlayhead = !state.centerPlayhead
                haptics.tick()
                toaster.show(Str.t(if (state.centerPlayhead) R.string.app_playhead_locked else R.string.app_playhead_free))
            }
            Spacer(Modifier.width(gap))
            ToggleIcon(Ic.Magnet, stringResource(R.string.app_magnetic_snapping), stringResource(R.string.app_short_snap), state.magnet, compact) {
                state.magnet = !state.magnet
                haptics.tick()
                toaster.show(Str.t(if (state.magnet) R.string.app_snapping_on else R.string.app_snapping_off))
            }
            Spacer(Modifier.width(gap))
            ToggleIcon(Ic.VolLine, stringResource(R.string.app_volume_lines), stringResource(R.string.app_short_volume), state.volLines, compact) {
                state.volLines = !state.volLines
                haptics.tick()
                toaster.show(Str.t(if (state.volLines) R.string.app_volume_lines_on else R.string.app_volume_lines_off))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
            Timeline(
                state, tool,
                Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            )
            if (overlays) {
                ContextBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = barPad))
                TransitionBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = barPad))
                CutBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = barPad, start = 12.dp, end = 12.dp))
                RenderChip(Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 10.dp))
            }
            MultiBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = barPad))
            // first-run tips about the gestures, only when nothing is selected (the selection bars use the same place)
            val nothingSelected = state.selected == null && !state.groupSelected && state.selectedTransition == null && state.selectedCut == null && state.selectedCaption == null
            TipCard(tips && nothingSelected && state.clips.isNotEmpty(), Modifier.align(Alignment.BottomCenter).padding(bottom = barPad, start = 12.dp, end = 12.dp))
            CaptionBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = barPad))
            // Properties slide up over the timeline; the picture above stays visible
            androidx.compose.animation.AnimatedVisibility(
                visible = state.inspecting && state.selected != null,
                enter = slideInVertically(spring(0.75f, 420f)) { it } + fadeIn(),
                exit = slideOutVertically(spring(1f, 500f)) { it } + fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().shadow(12.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
                    PropertiesPanel(state) { state.inspecting = false }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = state.editingCaption && state.selectedCaption != null,
                enter = slideInVertically(spring(0.75f, 420f)) { it } + fadeIn(),
                exit = slideOutVertically(spring(1f, 500f)) { it } + fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().shadow(12.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
                    CaptionInspector(state)
                }
            }
        }
    }
}

@Composable
private fun ContextBar(state: EditorState, modifier: Modifier) {
    val haptics = rememberHaptics()
    var speedMode by remember { mutableStateOf(false) }
    var freezeMode by remember { mutableStateOf(false) }
    var volumeMode by remember { mutableStateOf(false) }
    var labelMode by remember { mutableStateOf(false) }
    LaunchedEffect(state.selected) { speedMode = false; freezeMode = false; volumeMode = false; labelMode = false }
    // the actions follow what is selected: video, sound or a title
    val sel = state.clips.firstOrNull { it.id == state.selected }
    val isSound = sel?.kind == ClipKind.AUDIO || sel?.kind == ClipKind.MUSIC
    val isTitle = sel?.kind == ClipKind.TITLE
    AnimatedVisibility(
        visible = state.selected != null,
        enter = slideInVertically(spring(0.6f, 500f)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        AnimatedContent(listOf(speedMode, freezeMode, volumeMode, labelMode), transitionSpec = { (fadeIn() + scaleIn(spring(0.6f, 500f), initialScale = 0.9f)) togetherWith fadeOut() }, label = "ctx") { (speed, freeze, volume, label) ->
            Row(
                Modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(K.SurfaceHighest)
                    // narrow screen: the row scrolls sideways instead of buttons ending up off-screen
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (speed) {
                    val cur = state.clips.firstOrNull { it.id == state.selected }?.speed ?: 1f
                    RoundIcon(Ic.Back, stringResource(R.string.app_back)) { speedMode = false }
                    // ramp: the speed changes smoothly along the clip
                    state.onSpeedRamp?.let { ramp ->
                        var open by remember { mutableStateOf(false) }
                        Box {
                            Box(
                                Modifier.padding(horizontal = 2.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(K.Tonal)
                                    .clickable { haptics.tick(); open = true }.padding(horizontal = 10.dp).testTag("speed:ramp"),
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(R.string.app_ramp), color = K.PrimarySoft, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp), containerColor = K.SurfaceHighest) {
                                for (r in se.gendut.klipp.engine.SpeedRamp.entries) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(r.label, color = K.OnSurface, fontWeight = FontWeight.Medium) },
                                        onClick = { open = false; state.selected?.let { haptics.confirm(); ramp(it, r); speedMode = false } },
                                        modifier = Modifier.testTag("ramp:${r.name}"),
                                    )
                                }
                            }
                        }
                    }
                    for (p in listOf(25, 50, 100, 150, 200, 400)) {
                        val on = kotlin.math.abs(cur * 100 - p) < 0.5f
                        Box(
                            Modifier.padding(horizontal = 2.dp).height(40.dp).clip(RoundedCornerShape(if (on) 20.dp else 10.dp))
                                .background(if (on) K.Primary else K.SurfaceHigh)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    state.selected?.let { haptics.confirm(); state.setSpeed(it, p) }
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("$p%", color = if (on) K.OnPrimary else K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                } else if (volume) {
                    // the clip's level in a few steps (fine control in Properties)
                    RoundIcon(Ic.Back, stringResource(R.string.app_back)) { volumeMode = false }
                    val cur = state.selected?.let { id -> state.props?.props(id)?.levelDb } ?: 0f
                    for (db in listOf(-12, -6, -3, 0, 3, 6)) {
                        val on = abs(cur - db) < 0.05f
                        Box(
                            Modifier.padding(horizontal = 2.dp).height(40.dp).clip(RoundedCornerShape(if (on) 20.dp else 10.dp))
                                .background(if (on) K.Primary else K.SurfaceHigh)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    state.selected?.let { haptics.confirm(); state.props?.setProps(it, levelDb = db.toFloat()) }
                                }
                                .padding(horizontal = 10.dp).testTag("level:$db"),
                            contentAlignment = Alignment.Center
                        ) { Text(if (db > 0) "+$db dB" else "$db dB", color = if (on) K.OnPrimary else K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                } else if (label) {
                    LabelPicker(sel?.label, onBack = { labelMode = false }) { l -> state.selected?.let { haptics.confirm(); state.setLabel(listOf(it), l); labelMode = false } }
                } else if (freeze) {
                    // freeze frame at the playhead: choose how long the frame is held
                    RoundIcon(Ic.Back, stringResource(R.string.app_back)) { freezeMode = false }
                    Text(stringResource(R.string.app_freeze), color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                    for (sec in listOf(1f, 2f, 3f, 5f)) {
                        Box(
                            Modifier.padding(horizontal = 2.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(K.SurfaceHigh)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    state.selected?.let { haptics.confirm(); state.freezeFrame(it, state.playhead, sec); freezeMode = false }
                                }
                                .padding(horizontal = 12.dp).testTag("freeze:${sec.toInt()}"),
                            contentAlignment = Alignment.Center
                        ) { Text(stringResource(R.string.app_seconds, sec.toInt()), color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                } else {
                    if (!isTitle) state.onCopyToSource?.let { copy ->
                        ContextAction(Ic.Copy, stringResource(R.string.app_copy)) { state.selected?.let { haptics.confirm(); copy(it) } }
                    }
                    // a title: its text first (the title editor is in Properties)
                    if (isTitle && state.props != null) ContextAction(Ic.Text, stringResource(R.string.app_edit_text), tag = "ctx:editText") { haptics.tick(); state.inspecting = true }
                    ContextAction(Ic.Split, stringResource(R.string.app_split)) {
                        state.selected?.let { haptics.confirm(); state.split(it, state.playhead) }
                    }
                    // sound: level steps and a quick fade in + out
                    if (isSound && state.props != null) {
                        ContextAction(Ic.Speaker, stringResource(R.string.app_volume), tag = "ctx:volume") { haptics.tick(); volumeMode = true }
                        val p = state.selected?.let { state.props?.props(it) }
                        val faded = (p?.fadeIn ?: 0f) > 0f || (p?.fadeOut ?: 0f) > 0f
                        ContextAction(Ic.Fade, stringResource(R.string.app_fade), selected = faded, tag = "ctx:fade") {
                            val id = state.selected ?: return@ContextAction
                            haptics.confirm()
                            val len = if (faded) null else QUICK_FADE.coerceAtMost((sel?.length ?: 1f) / 3f)
                            state.props?.setTransition(id, true, len)
                            state.props?.setTransition(id, false, len)
                        }
                    }
                    if (state.props != null && !isTitle) ContextAction(Ic.Sliders, stringResource(R.string.app_properties)) { haptics.tick(); state.inspecting = true }
                    if (!isTitle) ContextAction(Ic.Speed, stringResource(R.string.app_speed)) { haptics.tick(); speedMode = true }
                    if (state.clips.firstOrNull { it.id == state.selected }?.kind == ClipKind.VIDEO) {
                        ContextAction(Ic.Freeze, stringResource(R.string.app_freeze)) { haptics.tick(); freezeMode = true }
                    }
                    state.clips.firstOrNull { it.id == state.selected }?.takeIf { (it.kind == ClipKind.VIDEO || it.kind == ClipKind.AUDIO) && !it.hold }?.let { c ->
                        ContextAction(Ic.Reverse, stringResource(if (c.reverse) R.string.app_forward else R.string.app_reverse)) { haptics.confirm(); state.toggleReverse(c.id) }
                    }
                    ContextAction(Ic.Tag, stringResource(R.string.app_label), selected = sel?.label != null, tag = "ctx:label") { haptics.tick(); labelMode = true }
                    val off = state.clips.firstOrNull { it.id == state.selected }?.enabled == false
                    ContextAction(Ic.Power, stringResource(if (off) R.string.app_enable else R.string.app_disable)) { state.selected?.let { haptics.confirm(); state.toggleEnabled(listOf(it)) } }
                    ContextAction(Ic.Delete, stringResource(R.string.app_delete)) { state.selected?.let { haptics.heavy(); state.requestRemove(it, false) } }
                    ContextAction(Ic.Ripple, stringResource(R.string.app_ripple_delete)) { state.selected?.let { haptics.heavy(); state.requestRemove(it, true) } }
                }
            }
        }
    }
}

/** Several clips selected with the lasso: delete them or deselect. Drag one of them to move them all. */
@Composable
private fun MultiBar(state: EditorState, modifier: Modifier) {
    val haptics = rememberHaptics()
    var labelMode by remember { mutableStateOf(false) }
    LaunchedEffect(state.groupSelected) { labelMode = false }
    AnimatedVisibility(
        visible = state.groupSelected,
        enter = slideInVertically(spring(0.6f, 500f)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.testTag("multiBar"),
    ) {
        // more actions than fit on a narrow phone: the bar scrolls sideways (deselect stays reachable)
        Row(
            Modifier.padding(horizontal = 8.dp).shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(K.SurfaceHighest)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val nClips = state.multi.size
            val nCaps = state.multiCaptions.size
            if (labelMode) {
                val labels = state.multi.map { id -> state.clips.firstOrNull { it.id == id }?.label }.toSet()
                LabelPicker(labels.singleOrNull(), onBack = { labelMode = false }) { l -> haptics.confirm(); state.setLabel(state.multi, l); labelMode = false }
                return@Row
            }
            val clipsLabel = if (nClips > 0) pluralStringResource(R.plurals.app_n_clips, nClips, nClips) else null
            val capsLabel = if (nCaps > 0) pluralStringResource(R.plurals.app_n_captions, nCaps, nCaps) else null
            Text(
                listOfNotNull(clipsLabel, capsLabel).joinToString(" · "),
                color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp),
            )
            if (nClips > 0) ContextAction(Ic.Tag, stringResource(R.string.app_label), tag = "multi:label") { haptics.tick(); labelMode = true }
            if (nClips > 0) ContextAction(Ic.Power, stringResource(R.string.app_toggle_enabled)) { haptics.confirm(); state.toggleEnabled(state.multi) }
            // compound clip: selected, moved and graded together
            if (state.props != null && nClips > 0) {
                val groups = state.multi.mapNotNull { id -> state.clips.firstOrNull { it.id == id }?.group }.toSet()
                val whole = groups.size == 1 && state.clips.all { it.group != groups.first() || it.id in state.multi } && state.multi.all { id -> state.clips.firstOrNull { it.id == id }?.group == groups.first() }
                if (whole) ContextAction(Ic.Ungroup, stringResource(R.string.app_ungroup)) { haptics.confirm(); state.setGrouped(state.multi, false) }
                else ContextAction(Ic.Group, stringResource(R.string.app_group)) { haptics.confirm(); state.setGrouped(state.multi, true) }
            }
            // separate sound (a recorder, another camera): lined up by the audio
            if (state.props != null && nClips > 1) ContextAction(Ic.SyncAudio, stringResource(R.string.app_sync_audio), tag = "syncAudio") { haptics.confirm(); state.syncByAudio(state.multi) }
            ContextAction(Ic.Delete, stringResource(R.string.app_delete)) { haptics.heavy(); state.removeGroup(false) }
            if (nClips > 0) ContextAction(Ic.Ripple, stringResource(R.string.app_ripple_delete)) { haptics.heavy(); state.removeGroup(true) }
            ContextAction(Ic.Close, stringResource(R.string.app_deselect)) { haptics.tick(); state.clearSelection() }
        }
    }
}

/**
 * Pre-render chip at the timeline: "Render" when there are heavy parts that aren't rendered (then everything plays from
 * finished files, without lag); during rendering, the progress – tap to cancel.
 */
@Composable
private fun RenderChip(modifier: Modifier) {
    val haptics = rememberHaptics()
    val rc = se.gendut.klipp.engine.RenderCache
    val p = rc.progress
    val todo = rc.states.any { it.heavy && !it.ready }
    AnimatedVisibility(p != null || todo, modifier = modifier, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
        Row(
            Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (p != null) K.SurfaceHighest else K.Primary)
                .clickable {
                    haptics.confirm()
                    if (p != null || rc.renderAll) { rc.renderAll = false; rc.halted = true } else { rc.halted = false; rc.renderAll = true }
                }
                .padding(horizontal = 10.dp).testTag("renderChip"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Bolt, null, tint = if (p != null) K.Primary else K.OnPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (p != null) stringResource(R.string.app_rendering_progress, (p * 100).toInt()) else if (rc.renderAll) stringResource(R.string.app_waiting) else stringResource(R.string.app_render),
                color = if (p != null) K.OnSurface else K.OnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
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
            Modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(K.SurfaceHighest)
                .padding(horizontal = 6.dp, vertical = 6.dp).testTag("cutbar"),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Fade, null, tint = K.PrimarySoft, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
                val types = if (audio) listOf(se.gendut.klipp.engine.TransitionType.CROSS) else se.gendut.klipp.engine.TransitionType.entries.toList()
                for (ty in types) {
                    Box(
                        Modifier.padding(horizontal = 2.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(K.Tonal)
                            .clickable { add(ty.effect) }.padding(horizontal = 12.dp).testTag("cut:${ty.effect}"),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (audio) stringResource(R.string.app_crossfade) else ty.label, color = K.PrimarySoft, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_length), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp))
                for (sec in listOf(0.5f, 1f, 2f)) {
                    val on = abs(len - sec) < 0.03f
                    Box(
                        Modifier.padding(horizontal = 2.dp).height(36.dp).clip(RoundedCornerShape(if (on) 18.dp else 10.dp))
                            .background(if (on) K.Primary else K.SurfaceHigh)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { haptics.tick(); len = sec }
                            .padding(horizontal = 10.dp).testTag("cutlen:$sec"),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (sec < 1f) stringResource(R.string.app_half_second) else stringResource(R.string.app_seconds, sec.toInt()), color = if (on) K.OnPrimary else K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.weight(1f))
                RoundIcon(Ic.Close, stringResource(R.string.app_deselect)) { haptics.tick(); state.selectedCut = null }
            }
        }
    }
}

/** Selected transition: choose a length or remove it. The edges can also be dragged directly in the timeline. */
@Composable
private fun TransitionBar(state: EditorState, modifier: Modifier) {
    val haptics = rememberHaptics()
    val tr = state.selectedTransition?.let { id -> state.transitions.firstOrNull { it.id == id } }
    AnimatedVisibility(
        visible = tr != null && state.props != null,
        enter = slideInVertically(spring(0.6f, 500f)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val cur = tr?.let { it.end - it.start } ?: 0f
        Row(
            Modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(K.SurfaceHighest)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Ic.Fade, null, tint = K.PrimarySoft, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
            // the type (engine effect): a menu; fades in/out to black or white, between two clips all types
            val type = se.gendut.klipp.engine.TransitionType.of(tr?.effect ?: "cross_dissolve")
            val choices = if (tr?.anchor == 0) se.gendut.klipp.engine.TransitionType.entries.toList() else se.gendut.klipp.engine.TransitionType.FADES
            var open by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier.padding(horizontal = 2.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(K.Tonal)
                        .clickable { haptics.tick(); open = true }.padding(horizontal = 10.dp).testTag("transition:type"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (tr?.anchor) {
                            -1 -> stringResource(if (type == se.gendut.klipp.engine.TransitionType.DIP_WHITE) R.string.app_fade_in_from_white else R.string.app_fade_in)
                            1 -> stringResource(if (type == se.gendut.klipp.engine.TransitionType.DIP_WHITE) R.string.app_fade_out_to_white else R.string.app_fade_out)
                            else -> type.label
                        },
                        color = K.PrimarySoft, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp), containerColor = K.SurfaceHighest) {
                    for (ty in choices) {
                        val label = when {
                            tr?.anchor == 0 -> ty.label
                            ty == se.gendut.klipp.engine.TransitionType.DIP_WHITE -> stringResource(R.string.app_to_white)
                            else -> stringResource(R.string.app_to_black)
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label, color = if (ty == type) K.Primary else K.OnSurface, fontWeight = FontWeight.Medium) },
                            onClick = {
                                open = false
                                val id = state.selectedTransition ?: return@DropdownMenuItem
                                haptics.confirm(); state.props?.setTransitionType(id, ty.effect, tr?.reverse ?: false)
                            },
                            modifier = Modifier.testTag("transition:${ty.effect}"),
                        )
                    }
                }
            }
            if (type.directional && tr?.anchor == 0) {
                RoundIcon(Ic.Reverse, stringResource(R.string.app_reverse_direction)) {
                    val id = state.selectedTransition ?: return@RoundIcon
                    haptics.tick(); state.props?.setTransitionType(id, type.effect, !(tr.reverse))
                }
            }
            for (sec in listOf(0.5f, 1f, 2f)) {
                val on = abs(cur - sec) < 0.03f
                Box(
                    Modifier.padding(horizontal = 2.dp).height(40.dp).clip(RoundedCornerShape(if (on) 20.dp else 10.dp))
                        .background(if (on) K.Primary else K.SurfaceHigh)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            val id = state.selectedTransition ?: return@clickable
                            haptics.confirm(); state.props?.resizeTransition(id, sec)
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text(if (sec < 1f) stringResource(R.string.app_half_second) else stringResource(R.string.app_seconds, sec.toInt()), color = if (on) K.OnPrimary else K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            // pre-render: the transition plays from a finished file (smooth, no lag); re-rendered in the background after each change
            val from = tr?.from; val to = tr?.to
            if (from != null && to != null && tr.anchor == 0) {
                val pinned = se.gendut.klipp.engine.RenderCache.isPinned(from, to)
                val ready = pinned && se.gendut.klipp.engine.RenderCache.states.filter { it.a < tr.end - 1e-3 && it.b > tr.start + 1e-3 }.let { l -> l.isNotEmpty() && l.all { it.ready } }
                ContextAction(Ic.Bolt, when { !pinned -> stringResource(R.string.app_prerender); ready -> stringResource(R.string.app_rendered); else -> stringResource(R.string.app_rendering) }, selected = pinned, tag = "transition:render") {
                    haptics.confirm(); se.gendut.klipp.engine.RenderCache.setPinned(from, to, !pinned); state.props?.requestSave()
                }
            }
            ContextAction(Ic.Delete, stringResource(R.string.app_remove)) {
                val id = state.selectedTransition ?: return@ContextAction
                haptics.heavy(); state.selectedTransition = null; state.props?.removeTransition(id)
            }
        }
    }
}

/** The color labels in a row (plus none): the current one ringed. */
@Composable
private fun LabelPicker(current: ClipLabel?, onBack: () -> Unit, onPick: (ClipLabel?) -> Unit) {
    RoundIcon(Ic.Back, stringResource(R.string.app_back)) { onBack() }
    val none = stringResource(R.string.app_label_none)
    Box(
        Modifier.padding(horizontal = 4.dp).size(34.dp).clip(CircleShape)
            .border(if (current == null) 2.5.dp else 1.5.dp, if (current == null) K.OnSurface else K.Outline, CircleShape)
            .clickable(onClickLabel = none) { onPick(null) }.testTag("label:none"),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(18.dp, 2.dp).graphicsLayer { rotationZ = -45f }.background(K.Outline)) }
    for (l in ClipLabel.entries) {
        val name = stringResource(l.title)
        Box(
            Modifier.padding(horizontal = 4.dp).size(34.dp)
                .then(if (l == current) Modifier.border(2.5.dp, K.OnSurface, CircleShape).padding(5.dp) else Modifier.padding(3.dp))
                .clip(CircleShape).background(l.color)
                .clickable(onClickLabel = name) { onPick(l) }.testTag("label:${l.name}")
        )
    }
}

@Composable
private fun ContextAction(icon: ImageVector, label: String, selected: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    Column(
        Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
            .clip(RoundedCornerShape(18.dp))
            .then(if (selected) Modifier.background(K.Primary) else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.85f); press.animateTo(1f, Springs.pop()) }
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (selected) K.OnPrimary else K.OnSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (selected) K.OnPrimary else K.OnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, style = IconLabel)
    }
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, selected: Boolean, first: Boolean, last: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val spec = spring<androidx.compose.ui.unit.Dp>(0.5f, 500f)
    val outer = 22.dp
    val inner = 8.dp
    val l by animateDpAsState(if (selected || first) outer else inner, spec, label = "l")
    val r by animateDpAsState(if (selected || last) outer else inner, spec, label = "r")
    val bg by animateColorAsState(if (selected) K.Primary else K.SurfaceHigh, label = "bg")
    val fg by animateColorAsState(if (selected) K.OnPrimary else K.OnSurface, label = "fg")
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    Column(
        Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
            .height(48.dp)
            .clip(RoundedCornerShape(topStart = l, bottomStart = l, topEnd = r, bottomEnd = r))
            .background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.9f); press.animateTo(1f, Springs.pop()) }
                onClick()
            }
            .padding(horizontal = if (compact) 6.dp else 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, style = IconLabel)
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, desc: String, label: String, on: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) K.Tonal else K.SurfaceHigh, label = "tb")
    val r by animateDpAsState(if (on) 22.dp else 12.dp, spring(0.45f, 500f), label = "tr")
    val rot = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.size(if (compact) 41.dp else 44.dp, 48.dp).clip(RoundedCornerShape(r)).background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { rot.snapTo(-25f); rot.animateTo(0f, Springs.pop()) }
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, desc, tint = if (on) K.PrimarySoft else K.OnSurfaceVar, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rot.value })
        Text(label, color = if (on) K.PrimarySoft else K.OnSurfaceVar, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, style = IconLabel)
    }
}

/** Undo and redo at the bottom (within the thumb's reach), next to the modes. */
@Composable
private fun UndoRedo(state: EditorState, undo: () -> Unit, redo: () -> Unit) {
    Row(
        Modifier.height(64.dp).shadow(10.dp, RoundedCornerShape(32.dp)).clip(RoundedCornerShape(32.dp)).background(K.SurfaceHighest).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(Ic.Undo, stringResource(R.string.app_undo), enabled = state.undoCount > 0, modifier = Modifier.size(44.dp), onClick = undo)
        RoundIcon(Ic.Redo, stringResource(R.string.app_redo), enabled = state.redoCount > 0, modifier = Modifier.size(44.dp), onClick = redo)
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
private fun FloatingModes(pageOf: () -> Float, modifier: Modifier, onPick: (Int) -> Unit) {
    // read here (not by the caller): during a swipe only the mode row recomposes, not the whole screen
    val page = pageOf()
    BoxWithConstraints(
        modifier.height(64.dp).shadow(10.dp, RoundedCornerShape(32.dp)).clip(RoundedCornerShape(32.dp)).background(K.SurfaceHighest).padding(6.dp)
    ) {
        val itemW = maxWidth / MODES.size
        val p = page.coerceIn(0f, MODES.lastIndex.toFloat())
        // The indicator stretches between modes and contracts when it lands
        val frac = abs(page - page.roundToInt())
        val stretch = 1f + frac * 0.6f
        Box(
            Modifier
                .offset { IntOffset(((itemW * p).toPx()).roundToInt(), 0) }
                .width(itemW).height(52.dp)
                .graphicsLayer { scaleX = stretch; scaleY = 1f - frac * 0.12f }
                .clip(RoundedCornerShape(26.dp)).background(K.Primary)
        )
        Row(Modifier.fillMaxSize()) {
            MODES.forEachIndexed { i, nameId ->
                val name = stringResource(nameId)
                val active = abs(p - i) < 0.5f
                val fg by animateColorAsState(if (active) K.OnPrimary else K.OnSurface, label = "m$i")
                Column(
                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(26.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(MODE_ICONS[i], name, tint = fg, modifier = Modifier.size(20.dp))
                    Text(name, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = IconLabel)
                }
            }
        }
    }
}

@Composable
private fun AddFab(onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    val rot = remember { Animatable(0f) }
    Box(
        Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
            .size(60.dp).shadow(10.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(K.Primary)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.82f); press.animateTo(1f, Springs.pop()) }
                scope.launch { rot.animateTo(rot.value + 90f, Springs.pop()) }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Ic.Plus, stringResource(R.string.app_add_clip), tint = K.OnPrimary, modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = rot.value })
    }
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
            color = K.OnPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(K.Primary).padding(horizontal = 18.dp, vertical = 10.dp)
        )
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
    .put("master", m.gains[3].value.toDouble()).put("norm", m.normDb.toDouble())

suspend fun gradeFromJson(o: org.json.JSONObject, g: ColorGrade, m: AudioMix) {
    g.exposure.snapTo(o.optDouble("exposure", 0.0).toFloat()); g.contrast.snapTo(o.optDouble("contrast", 0.0).toFloat())
    g.temp.snapTo(o.optDouble("temp", 0.0).toFloat()); g.tint.snapTo(o.optDouble("tint", 0.0).toFloat())
    g.sat.snapTo(o.optDouble("sat", 0.0).toFloat())
    g.lutName = if (o.isNull("lut")) null else o.optString("lut").takeIf { se.gendut.klipp.engine.LutStore.get(it) != null }
    g.lutMix.snapTo(o.optDouble("lutMix", 1.0).toFloat()); g.tetra = o.optBoolean("tetra", true)
    m.gains[3].snapTo(o.optDouble("master", 0.75).toFloat())
    m.normDb = o.optDouble("norm", 0.0).toFloat().let { if (it.isNaN()) 0f else it }
}

/** Master: the fader's gain × the normalization (dB, from the loudness measurement). */
fun mixMaster(m: AudioMix): Float = masterOf(m.gains[3].value) * Math.pow(10.0, m.normDb / 20.0).toFloat()
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
