package se.gendut.klipp

import se.gendut.klipp.engine.playable
import androidx.media3.ui.compose.modifiers.resizeWithContentScale

import androidx.compose.foundation.gestures.detectTapGestures
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.MediaInfo
import se.gendut.klipp.engine.ProgramPlayback
import se.gendut.klipp.engine.ProjectController
import se.gendut.klipp.engine.SourcePlayback
import kotlin.math.abs

// ------------------------------------------------------------------ thumbnails

object Thumbs {
    // at most 160 thumbnails (240 px ≈ 130 kB each) – least recently used are dropped
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 160
    }
    // a dedicated thread and one reused decoder per file: previously a new decoder was started for every
    // thumbnail, many at once, and 4K frames were decoded at full size (33 MB each) – memory ran out
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "thumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        .asCoroutineDispatcher()
    private val retrievers = object : LinkedHashMap<Uri, MediaMetadataRetriever>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, MediaMetadataRetriever>?): Boolean {
            if (size > 2) { runCatching { eldest?.value?.release() }; return true }
            return false
        }
    }

    suspend fun load(context: Context, uri0: Uri, atSec: Double = 1.0): ImageBitmap? {
        // the proxy when there is one (1080p, dense keyframes) – much cheaper than the 4K original
        val uri = se.gendut.klipp.engine.Proxies.videoUri(uri0)
        val key = "$uri0@${"%.1f".format(java.util.Locale.ROOT, atSec)}"
        synchronized(cache) { cache[key] }?.let { return it }
        val img = withContext(worker) {
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            runCatching {
                val r = retrievers.getOrPut(uri) {
                    // if setDataSource fails the retriever is released right away (otherwise it leaks)
                    MediaMetadataRetriever().also { m -> try { m.setDataSource(context, uri) } catch (e: Exception) { m.release(); throw e } }
                }
                val us = (atSec * 1_000_000).toLong()
                // scaled down directly in the decoder (no full-resolution image in the app's memory)
                val f = if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
                else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b -> Bitmap.createScaledBitmap(b, 240, (240f * b.height / b.width).toInt().coerceAtLeast(1), true).also { if (it !== b) b.recycle() } }
                f?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        synchronized(cache) { cache[key] = img }
        return img
    }
}

@Composable
fun Thumb(uri: Uri?, modifier: Modifier) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, uri) { value = uri?.let { Thumbs.load(ctx, it) } }
    Box(modifier.background(K.SurfaceHighest)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

fun durText(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

// ------------------------------------------------------------------ program monitor

/**
 * A resized monitor (the handle, rotation) shows the paused frame drawn at the old size and shifted until the player
 * renders again: once the size has settled, the frame is drawn anew.
 */
@Composable
fun Modifier.redrawOnResize(playback: ProgramPlayback, state: EditorState): Modifier {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    LaunchedEffect(size) {
        if (size == androidx.compose.ui.unit.IntSize.Zero) return@LaunchedEffect
        // the surface's new size reaches the player's video pipeline a moment later (its own thread): once soon, and
        // again when it has surely arrived
        kotlinx.coroutines.delay(150)
        if (!state.playing) playback.redraw()
        kotlinx.coroutines.delay(350)
        if (!state.playing) playback.redraw()
    }
    return this.then(Modifier.onSizeChanged { size = it })
}

@OptIn(UnstableApi::class)
@Composable
fun ProgramMonitor(
    controller: ProjectController, playback: ProgramPlayback, state: EditorState, gs: GradeState? = null,
    look: se.gendut.klipp.engine.Look = playback.look,
    /** The monitor's box (the picture is fitted inside it); 16:9 of the width by default. */
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    onImport: () -> Unit,
) {
    // disabled clips aren't shown (gap)
    val q = controller.snapshot.playable().sequence
    val hasClips = q?.video?.any { it.clips.isNotEmpty() } == true || q?.captions?.isNotEmpty() == true
    val aspect = if (q != null && q.width > 0 && q.height > 0) q.width.toFloat() / q.height else 16f / 9f
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (hasClips) {
            // the image in the sequence's format, centered (vertical video gets black bars)
            // clipped to the frame: nothing (scaled/moved layer, still) is drawn outside the program image
            Box(Modifier.fillMaxSize().wrapContentSize().aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 16f / 9f).clipToBounds()) {
                // the composition always outputs the sequence format (each clip is fitted in Playback), the box has the same shape
                ProgramSurface(playback.player, usesBlendModes(), Modifier.fillMaxSize().redrawOnResize(playback, state))
                // no image under the playhead (empty track, gap, everything deleted): black – the player surface could
                // otherwise keep showing its last frame
                val noVideo by remember(q) {
                    derivedStateOf {
                        val t = state.playhead.toDouble()
                        // half a millisecond of margin: the playhead (Float) on an edit point never counts as a gap
                        q.video.filter { it.enabled }.none { tr -> tr.clips.any { it.text == null && t >= it.start - 5e-4 && t < it.end + 5e-4 } }
                    }
                }
                if (noVideo) Box(Modifier.fillMaxSize().background(Color.Black).testTag("noVideo"))
                // V2/V3, cross dissolves, titles and captions on top (see UpperLayers)
                UpperLayers(controller, state, playback.player, gradeOf = { gs?.grades?.get(it) }, look = playback.look, gradesKey = gs?.revision)
                // while dragging: the app's own image with live values (the video updates when the finger is lifted)
                // ... and until the player has shown its first frame (start, reload) or while a new look is being built in:
                // a still instead of black
                LivePreview(controller, state, gs = gs, look = look, force = (!playback.frameShown && !state.playing) || look != playback.look)
                if (state.inspecting) MotionOverlay(state)
                // color page: windows to drag, eyedropper, comparison with a still
                // the window is drawn for the same source time as the visible image (the still while scrubbing, otherwise the playhead)
                if (gs != null) GradeOverlay(gs, srcOf = { id ->
                    gs.stillTimes[id] ?: controller.snapshot.sequence?.video?.firstNotNullOfOrNull { tr -> tr.clips.firstOrNull { it.id == id } }
                        ?.let { c -> c.sourceAt(state.playhead.toDouble()) }
                })
            }
            // the video surface is re-attached when the source panel closes – reload so the image comes back
            LaunchedEffect(Unit) { playback.onSurfaceAttached(state.playhead.toDouble()) }
            // the program image as displayed, for the color page's scopes during playback
            val rootView = androidx.compose.ui.platform.LocalView.current
            if (gs != null) androidx.compose.runtime.DisposableEffect(gs, rootView) {
                gs.capture = { captureProgram(rootView) }
                onDispose { gs.capture = null }
            }
            // long-press "Program" = diagnostics (which path playback takes, the layers, timings)
            var diag by remember { mutableStateOf(false) }
            Column(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                Text(
                    stringResource(R.string.realmode_program) +
                        se.gendut.klipp.engine.Proxies.progress.values.let { v -> if (v.isEmpty()) "" else " · " + stringResource(R.string.realmode_proxy_status, v.firstOrNull { it >= 0 }?.let { "$it %" } ?: stringResource(R.string.realmode_proxy_queued)) + (if (v.size > 1) " (${v.size})" else "") },
                    color = K.OnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { diag = !diag }, onTap = { if (diag) diag = false }) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("programChip")
                )
                if (diag) {
                    var text by remember { mutableStateOf("") }
                    // the passes' timings are only measured while diagnostics are shown (measuring waits for the GPU)
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        se.gendut.klipp.engine.PassTimes.enabled = true
                        onDispose { se.gendut.klipp.engine.PassTimes.enabled = false }
                    }
                    val ctx = LocalContext.current
                    LaunchedEffect(Unit) {
                        while (true) {
                            // the clip under the playhead: file, codec, decoder and proxy (read on a background thread)
                            val clipInfo = run {
                                val t = state.playhead.toDouble()
                                val c = controller.snapshot.playable().sequence?.video?.firstNotNullOfOrNull { tr -> tr.clips.firstOrNull { it.text == null && t >= it.start && t < it.end } }
                                val u = c?.let { controller.uriOf(it.item) }
                                if (c == null || u == null) "" else {
                                    val k = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { se.gendut.klipp.engine.Perf.codec(ctx, u) }
                                    val bits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { se.gendut.klipp.engine.VideoPipeline.bitDepth(ctx, u) }
                                    "clip: ${c.name}\n" + (k?.let { "file: ${it.mime}, ${it.width}×${it.height}, $bits-bit, ${it.bitrate?.let { b -> "${b / 1_000_000} Mbit/s" } ?: "? Mbit/s"}\ndecoder: ${it.decoder ?: "?"}\n" } ?: "file: could not be read\n") +
                                        "proxy for clip: ${se.gendut.klipp.engine.Proxies.statusOf(u)}\n"
                                }
                            }
                            text = playback.diagnostics() + clipInfo + se.gendut.klipp.engine.Proxies.describe() + SyncStats.describe()
                            kotlinx.coroutines.delay(250)
                        }
                    }
                    Text(
                        text, color = Color.White, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 12.sp,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp)
                    )
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(if (controller.snapshot.media.isEmpty()) R.string.realmode_no_media else R.string.realmode_timeline_empty),
                    color = K.OnSurfaceVar, fontSize = 14.sp
                )
                Spacer(Modifier.height(12.dp))
                BigButton(stringResource(if (controller.snapshot.media.isEmpty()) R.string.realmode_import_video else R.string.realmode_open_media), onImport)
            }
        }
    }
}

@Composable
fun BigButton(label: String, onClick: () -> Unit, color: Color = K.Primary, textColor: Color = K.OnPrimary, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    Box(
        modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
            .height(52.dp).clip(RoundedCornerShape(26.dp)).background(color)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.9f); press.animateTo(1f, Springs.pop()) }
                onClick()
            }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

/** Syncs playback with the timeline: playhead, play/pause and reloading after edits. */
@OptIn(FlowPreview::class)
@Composable
fun PlaybackSync(controller: ProjectController, playback: ProgramPlayback, state: EditorState, onError: (String) -> Unit) {
    // always the latest callback (not the one from the first composition)
    val onErrorNow by androidx.compose.runtime.rememberUpdatedState(onError)
    LaunchedEffect(playback) {
        playback.onError = { onErrorNow(it); state.playing = false }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(controller.revision) {
        playback.update(controller.snapshot, controller::uriOf, state.playhead.toDouble())?.let { onError(Str.t(R.string.realmode_playback_error, it)) }
        // the scrub frames are built in the background for the clips on the timeline (once per file)
        controller.snapshot.sequence?.video?.flatMap { it.clips }?.filter { it.text == null }?.mapNotNull { controller.uriOf(it.item) }?.distinct()
            ?.forEach { se.gendut.klipp.engine.ScrubFrames.ensure(ctx, it) }
    }
    // Media3's scrubbing mode while the finger drags: the decoders keep running and stale seeks are skipped
    // (audio is off meanwhile). It is only turned off when playback starts – not when the finger is lifted, since
    // the player would otherwise reselect tracks just as the frame is about to show.
    LaunchedEffect(playback) {
        snapshotFlow { state.scrubbing }.collect { on -> if (on && !state.playing) runCatching { playback.player.isScrubbingModeEnabled = true } }
    }
    // a proxy became ready (or was turned on/off): switch files while playback is stopped
    LaunchedEffect(se.gendut.klipp.engine.Proxies.revision, se.gendut.klipp.engine.Perf.hdrRevision, se.gendut.klipp.engine.VideoPipeline.previewShort, state.playing) {
        if (!state.playing) playback.refresh(state.playhead.toDouble())
    }
    LaunchedEffect(state.playing) {
        // the display refresh rate follows the project frame rate during playback (smooth playback, see DisplayRate)
        (ctx as? android.app.Activity)?.let { se.gendut.klipp.engine.DisplayRate.apply(it, if (state.playing) controller.snapshot.fps else null) }
        // scrub frames aren't built during playback (the decoder is needed for the picture)
        se.gendut.klipp.engine.ScrubFrames.paused = state.playing
        if (state.playing) {
            runCatching { if (playback.player.isScrubbingModeEnabled) playback.player.isScrubbingModeEnabled = false }
            val err = playback.start(state.playhead.toDouble())
            if (err != null) { onError(err); state.playing = false; return@LaunchedEffect }
            var lastPos = -1.0
            var stuckFrames = 0
            var idleFrames = 0
            while (state.playing) {
                withFrameNanos { }
                val p = playback.player
                val pos = playback.positionSec
                playback.hint(pos)
                if (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED) state.playhead = pos.toFloat()
                when {
                    p.playbackState == Player.STATE_ENDED -> state.playing = false
                    !p.playWhenReady -> state.playing = false // the player paused itself (e.g. headphones unplugged)
                    // after a reload the player is idle for a moment before it starts buffering – that is not a
                    // stop (a real error stops via onError). Stopping here used to leave just one frame.
                    p.playbackState == Player.STATE_IDLE -> if (++idleFrames > 120) state.playing = false
                }
                if (p.playbackState != Player.STATE_IDLE) idleFrames = 0
                // if the player stands still for over 3 s although it should be playing: reload once
                if (p.playbackState == Player.STATE_READY && pos == lastPos) {
                    if (++stuckFrames == 180) { playback.reload(pos); p.play() }
                } else stuckFrames = 0
                lastPos = pos
            }
            playback.player.pause()
        } else {
            playback.player.pause()
        }
    }
    // scrubbing: the app shows stills from the clips (LivePreview) while the finger moves; the player seeks when
    // the finger rests for a moment and the still is only dropped once the player actually shows the frame (never black)
    LaunchedEffect(Unit) {
        var settle: kotlinx.coroutines.Job? = null
        var last = state.playhead
        snapshotFlow { state.playhead }.collect { ph ->
            if (state.playing || kotlin.math.abs(ph - last) < 0.0005f) { last = ph; return@collect }
            last = ph
            state.scrubbing = true
            settle?.cancel()
            settle = launch {
                kotlinx.coroutines.delay(70)
                val before = playback.framesShown
                playback.seek(ph.toDouble())
                kotlinx.coroutines.withTimeoutOrNull(900) { snapshotFlow { playback.framesShown }.first { it != before } }
                // and the player is ready (not in the middle of new buffering) – then a few more frames, so the surface
                // surely shows the frame before the still is removed (otherwise it could flash black)
                kotlinx.coroutines.withTimeoutOrNull(600) {
                    while (playback.player.playbackState != androidx.media3.common.Player.STATE_READY) kotlinx.coroutines.delay(16)
                }
                kotlinx.coroutines.delay(100)
                state.scrubbing = false
            }
        }
    }
    // the volume slider is audible while dragging (lowering right away; raising up to the player's max volume)
    LaunchedEffect(playback) {
        snapshotFlow { state.live }.collect { live ->
            val committed = live?.takeIf { it.isAudio }?.let { controller.props(it.id)?.levelDb }
            playback.liveGain = if (live != null && committed != null)
                se.gendut.klipp.engine.CompositionBuilder.dbToGain((live.levelDb - committed).toDouble()).coerceIn(0f, 1f) else 1f
        }
    }
}

// ------------------------------------------------------------------ media panel

@Composable
fun rememberVideoPicker(onPicked: (List<Uri>) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { onPicked(it) }

fun videoRequest() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)

// ------------------------------------------------------------------ source monitor

/**
 * The Source monitor takes the Program monitor's place at the top; the timeline stays visible below so you
 * see where the media lands and can move the playhead there before inserting.
 */
@OptIn(UnstableApi::class)
@Composable
fun SourcePanel(
    m: MediaInfo,
    uri: Uri,
    source: SourcePlayback,
    state: EditorState,
    setMarks: suspend (Double?, Double?) -> Unit,
    edit: (overwrite: Boolean, onDone: () -> Unit) -> Unit,
    onClose: () -> Unit,
    /** Fill the given height (landscape column): the picture takes what the controls leave. */
    fill: Boolean = false,
    /** The clip's transcript: the Text view picks sentences as in/out (null = no text view). */
    text: SourceText? = null,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    // the same clip already loaded (e.g. after the phone was rotated) → continue where we were
    var pos by remember(m) { mutableStateOf(if (source.currentItem == m.id) source.positionSec else (m.markIn ?: 0.0)) }
    var markIn by remember(m) { mutableStateOf(m.markIn) }
    var markOut by remember(m) { mutableStateOf(m.markOut) }
    var playing by remember(m) { mutableStateOf(false) }
    val frame = 1.0 / (if (m.fps > 1) m.fps else 25.0)
    // the Text view instead of the picture, and its marked sentences
    var textMode by remember(m) { mutableStateOf(false) }
    var sentencesMarked by remember(m) { mutableStateOf<IntRange?>(null) }

    LaunchedEffect(m) { state.playing = false; source.open(m.id, uri, pos) }
    LaunchedEffect(playing) {
        if (playing) {
            source.player.play()
            while (playing) {
                withFrameNanos { }
                pos = source.positionSec
                if (source.player.playbackState == Player.STATE_ENDED) playing = false
            }
        } else source.player.pause()
    }
    fun seek(t: Double) { pos = t.coerceIn(0.0, m.duration); source.seek(pos) }
    fun pushMarks() { scope.launch { setMarks(markIn, markOut) } }
    fun doEdit(overwrite: Boolean) {
        playing = false
        haptics.confirm()
        scope.launch {
            setMarks(markIn, markOut)
            // picking by text: stay in the source for the next sentences (the timeline playhead moved past the new clip)
            edit(overwrite) { if (textMode) sentencesMarked = null else onClose() }
        }
    }

    Column(Modifier.fillMaxWidth().then(if (fill) Modifier.fillMaxHeight() else Modifier).background(K.Bg)) {
        // Header
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Ic.Back, stringResource(R.string.realmode_close_source)) { playing = false; onClose() }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.realmode_source_title, m.name), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${m.width}×${m.height} · ${"%.2f".format(m.fps)} fps · ${durText(m.duration)}", color = K.OnSurfaceVar, fontSize = 11.sp, maxLines = 1)
            }
            Text(
                formatTc(pos.toFloat()), color = K.Primary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 4.dp)
            )
            if (text != null) {
                val bg by androidx.compose.animation.animateColorAsState(if (textMode) K.Primary else Color.Transparent, label = "tx")
                Box(
                    Modifier.padding(end = 6.dp).size(40.dp).clip(RoundedCornerShape(20.dp)).background(bg)
                        .clickable(onClickLabel = stringResource(if (textMode) R.string.source_show_picture else R.string.source_show_text)) { haptics.tick(); textMode = !textMode }
                        .testTag("source:text"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Ic.Transcript, stringResource(R.string.source_text), tint = if (textMode) K.OnPrimary else K.OnSurface, modifier = Modifier.size(20.dp)) }
            }
        }
        // Image (or the clip as text)
        val area = Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f) else if (textMode) Modifier.height(300.dp) else Modifier.aspectRatio(16f / 9f))
        Box(area.background(Color.Black)) {
            FitPlayerSurface(source.player, Modifier.fillMaxSize())
            if (textMode && text != null) SourceTextView(
                m.id, m.duration, frame, text, pos, Modifier.fillMaxSize(),
                selection = sentencesMarked, onSelect = { sentencesMarked = it },
                onMarks = { a, b -> markIn = a; markOut = b; pushMarks() },
                onSeek = { playing = false; seek(it) },
            )
        }
        // Scrub bar with in/out
        ScrubBar(
            duration = m.duration, pos = pos, markIn = markIn, markOut = markOut,
            onScrub = { playing = false; seek(it) },
            onMoveIn = { markIn = it; sentencesMarked = null }, onMoveOut = { markOut = it; sentencesMarked = null },
            onMarksDone = { pushMarks() },
        )
        val len = ((markOut ?: m.duration) - (markIn ?: 0.0)).coerceAtLeast(0.0) + if (markOut != null) frame else 0.0
        // Transport + insert/overwrite in one row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIcon(Ic.MarkIn, stringResource(R.string.realmode_mark_in)) { markIn = pos; if (markOut != null && markOut!! < pos) markOut = null; haptics.confirm(); pushMarks() }
            RoundIcon(Ic.Prev, stringResource(R.string.realmode_previous_frame)) { playing = false; seek(pos - frame); haptics.tick() }
            val bg = K.Primary
            Box(
                Modifier.size(56.dp, 44.dp).clip(RoundedCornerShape(if (playing) 22.dp else 14.dp)).background(bg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { haptics.confirm(); playing = !playing },
                contentAlignment = Alignment.Center
            ) { Icon(if (playing) Ic.Pause else Ic.Play, stringResource(if (playing) R.string.realmode_pause else R.string.realmode_play), tint = K.OnPrimary, modifier = Modifier.size(24.dp)) }
            RoundIcon(Ic.Next, stringResource(R.string.realmode_next_frame)) { playing = false; seek(pos + frame); haptics.tick() }
            RoundIcon(Ic.MarkOut, stringResource(R.string.realmode_mark_out)) { markOut = pos; if (markIn != null && markIn!! > pos) markIn = null; haptics.confirm(); pushMarks() }
            Spacer(Modifier.weight(1f))
            Text(formatTc(len.toFloat()).substring(3), color = K.OnSurfaceVar, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            EditButton(stringResource(R.string.realmode_insert), Ic.Split, K.Primary, K.OnPrimary, RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp), Modifier.weight(1f)) { doEdit(false) }
            EditButton(stringResource(R.string.realmode_overwrite), Ic.Edit, K.PrimaryContainer, K.PrimarySoft, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp), Modifier.weight(1f)) { doEdit(true) }
        }
    }
}

@Composable
private fun EditButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, fg: Color, shape: RoundedCornerShape, modifier: Modifier, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    Row(
        modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
            .height(48.dp).clip(shape).background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.92f); press.animateTo(1f, Springs.pop()) }
                onClick()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Scrub bar. Drag anywhere to scrub. The in and out points have large tabs below the track that can be
 * grabbed (32 dp each way). Dragging the finger down away from the bar halves the speed step by step
 * (½, ¼, ⅛ …, see [ScrubTiers]) – for precise points.
 */
@Composable
fun ScrubBar(
    duration: Double,
    pos: Double,
    markIn: Double?,
    markOut: Double?,
    onScrub: (Double) -> Unit,
    onMoveIn: (Double) -> Unit,
    onMoveOut: (Double) -> Unit,
    onMarksDone: () -> Unit,
) {
    val haptics = rememberHaptics()
    // The gesture must NOT restart when the points change mid-drag (the bug in 0.4) – read current values here.
    val curIn by androidx.compose.runtime.rememberUpdatedState(markIn)
    val curOut by androidx.compose.runtime.rememberUpdatedState(markOut)
    val curPos by androidx.compose.runtime.rememberUpdatedState(pos)
    val curDur by androidx.compose.runtime.rememberUpdatedState(duration)
    val scrub by androidx.compose.runtime.rememberUpdatedState(onScrub)
    val moveIn by androidx.compose.runtime.rememberUpdatedState(onMoveIn)
    val moveOut by androidx.compose.runtime.rememberUpdatedState(onMoveOut)
    val done by androidx.compose.runtime.rememberUpdatedState(onMarksDone)
    var dragging by remember { mutableStateOf(0) } // 0 = scrub, 1 = in, 2 = out
    var tier by remember { mutableStateOf(0) }
    val grow by animateFloatAsState(if (dragging != 0) 1f else 0f, spring(0.4f, 600f), label = "g")
    Box(
        Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 20.dp)
            .testTag("scrub")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val dur = curDur
                    fun xOf(t: Double) = (t / dur * w).toFloat()
                    fun t(x: Float) = (x / w * dur).coerceIn(0.0, dur)
                    val grab = 32.dp.toPx()
                    val dIn = curIn?.let { abs(down.position.x - xOf(it)) } ?: Float.MAX_VALUE
                    val dOut = curOut?.let { abs(down.position.x - xOf(it)) } ?: Float.MAX_VALUE
                    dragging = when {
                        dIn <= grab && dIn <= dOut -> 1
                        dOut <= grab -> 2
                        else -> 0
                    }
                    val anchorT = when (dragging) { 1 -> curIn!!; 2 -> curOut!!; else -> t(down.position.x) }
                    // finer the further down the finger goes (re-anchored at each step so nothing jumps)
                    val tiers = ScrubTiers(32.dp.toPx(), 48.dp.toPx())
                    tiers.start(down.position.x, down.position.y, anchorT)
                    if (dragging == 0) scrub(anchorT) else haptics.tick()
                    var snapped = false
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        var tt = tiers.at(c.position.x, c.position.y, dur / w).coerceIn(0.0, dur)
                        if (tiers.changed) { tier = tiers.tier; haptics.tick() }
                        if (dragging != 0 && abs(xOf(tt) - xOf(curPos)) < 10.dp.toPx()) {
                            if (!snapped) haptics.tick(); snapped = true; tt = curPos
                        } else snapped = false
                        when (dragging) {
                            0 -> scrub(tt)
                            1 -> moveIn(curOut?.let { minOf(tt, it) } ?: tt)
                            else -> moveOut(curIn?.let { maxOf(tt, it) } ?: tt)
                        }
                        c.consume()
                    }
                    if (dragging != 0) { haptics.confirm(); done() }
                    dragging = 0
                    tier = 0
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = 14.dp.toPx()
            val top = 22.dp.toPx() - h / 2
            val w = size.width
            fun x(t: Double) = (t / duration * w).toFloat()
            drawRoundRect(K.SurfaceHighest, Offset(0f, top), Size(w, h), CornerRadius(h / 2))
            val a = markIn ?: 0.0
            val b = markOut ?: duration
            if (markIn != null || markOut != null) {
                drawRoundRect(K.Tonal, Offset(x(a), top), Size((x(b) - x(a)).coerceAtLeast(2f), h), CornerRadius(h / 2))
            }
            // in/out: a bar through the track + a large tab below
            fun handle(t: Double, active: Boolean, left: Boolean) {
                val cx = x(t)
                val g = if (active) grow else 0f
                val bw = (4f + 2f * g).dp.toPx()
                drawRoundRect(K.Primary, Offset(cx - bw / 2, top - 6.dp.toPx()), Size(bw, h + 12.dp.toPx()), CornerRadius(bw / 2))
                val tw = (26f + 6f * g).dp.toPx()
                val th = (30f + 4f * g).dp.toPx()
                val tx = if (left) cx - tw + bw / 2 else cx - bw / 2
                drawRoundRect(
                    if (active) K.PrimarySoft else K.Primary,
                    Offset(tx, top + h + 6.dp.toPx()), Size(tw, th),
                    CornerRadius(10.dp.toPx())
                )
                // grip lines
                val gx = tx + tw / 2
                for (i in -1..1) drawLine(K.OnPrimary, Offset(gx + i * 4.dp.toPx(), top + h + 14.dp.toPx()), Offset(gx + i * 4.dp.toPx(), top + h + 28.dp.toPx()), 1.5.dp.toPx())
            }
            markIn?.let { handle(it, dragging == 1, left = true) }
            markOut?.let { handle(it, dragging == 2, left = false) }
            val px = x(pos)
            drawRoundRect(K.OnSurface, Offset(px - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), top + h + 4.dp.toPx()), CornerRadius(2.dp.toPx()))
            drawCircle(K.OnSurface, 6.dp.toPx(), Offset(px, 4.dp.toPx()))
        }
        ScrubSpeedBadge(tier, Modifier.align(Alignment.TopCenter))
    }
}

// ------------------------------------------------------------------ export

@Composable
fun ExportOverlay(progress: Int?, modifier: Modifier = Modifier) {
    AnimatedVisibility(progress != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }, contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(32.dp).clip(RoundedCornerShape(28.dp)).background(K.Surface).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.realmode_exporting), color = K.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(16.dp))
                val p by animateFloatAsState((progress ?: 0) / 100f, spring(1f, 200f), label = "ep")
                WavyProgress(p, Modifier.width(240.dp).height(20.dp))
                Spacer(Modifier.height(10.dp))
                Text("${progress ?: 0} %", color = K.OnSurfaceVar, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun WavyProgress(p: Float, modifier: Modifier) {
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { withFrameNanos { phase = (it / 1e9f * 6f) % (2 * Math.PI.toFloat()) } } }
    Canvas(modifier) {
        val mid = size.height / 2
        val end = size.width * p
        val path = androidx.compose.ui.graphics.Path()
        var x = 0f
        path.moveTo(0f, mid)
        while (x <= end) {
            path.lineTo(x, mid + kotlin.math.sin(x / 10.dp.toPx() + phase) * 4.dp.toPx())
            x += 2f
        }
        drawPath(path, K.Primary, style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        if (end < size.width - 8.dp.toPx())
            drawLine(K.Tonal, Offset(end + 8.dp.toPx(), mid), Offset(size.width, mid), 5.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
    }
}


/**
 * The player surface with the image's own aspect ratio (black bars if needed) – never stretched to the box.
 * The size comes from the player (after rotation), so vertical phone video stays vertical.
 */
/** Some clip has a blend mode (the program image is then drawn in a TextureView, see [ProgramSurface]). */
@Composable
fun usesBlendModes(): Boolean = LocalGradeState.current?.grades?.values?.any { it.layer?.blend?.let { b -> b != se.gendut.klipp.engine.BlendMode.NORMAL } == true } == true

/**
 * The program image. Usually a SurfaceView (fastest, HDR); with [texture] a TextureView – it is then drawn in the same
 * image as the upper layers, so they can blend with it (blend modes: multiply, screen …). Layers over a SurfaceView
 * can only be placed on top.
 */
@OptIn(UnstableApi::class)
@Composable
fun ProgramSurface(player: Player, texture: Boolean, modifier: Modifier = Modifier) {
    val comp = player as? androidx.media3.transformer.CompositionPlayer
    if (!texture || comp == null) { PlayerSurface(player, modifier); return }
    val current = androidx.compose.runtime.rememberUpdatedState(comp)
    var surface by remember { mutableStateOf<Pair<android.view.Surface, androidx.media3.common.util.Size>?>(null) }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c ->
            android.view.TextureView(c).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        surface = android.view.Surface(st) to androidx.media3.common.util.Size(w, h)
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        surface = surface?.first?.let { it to androidx.media3.common.util.Size(w, h) }
                    }
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                        // the player releases the surface before it is destroyed
                        surface?.first?.let { s -> runCatching { current.value.clearVideoSurface(s) }; s.release() }
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
            }
        },
        modifier = modifier,
    )
    DisposableEffect(comp, surface) {
        val s = surface
        if (s != null) comp.setVideoSurface(s.first, s.second)
        onDispose { if (s != null && surface?.first !== s.first) runCatching { comp.clearVideoSurface(s.first) } }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun FitPlayerSurface(player: Player, modifier: Modifier = Modifier) {
    val ps = androidx.media3.ui.compose.state.rememberPresentationState(player)
    Box(modifier, contentAlignment = Alignment.Center) {
        PlayerSurface(player, Modifier.resizeWithContentScale(androidx.compose.ui.layout.ContentScale.Fit, ps.videoSizeDp))
    }
}

