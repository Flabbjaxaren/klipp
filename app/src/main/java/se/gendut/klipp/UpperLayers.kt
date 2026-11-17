package se.gendut.klipp

import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import se.gendut.klipp.engine.Layer
import se.gendut.klipp.engine.LayerPlan
import se.gendut.klipp.engine.LayerPlanner
import se.gendut.klipp.engine.Overlay
import se.gendut.klipp.engine.OverlayItem
import se.gendut.klipp.engine.OverlayRenderer
import se.gendut.klipp.engine.ProjectController
import se.gendut.klipp.engine.Seg
import se.gendut.klipp.engine.SegSrc
import se.gendut.klipp.engine.placementOf
import se.gendut.klipp.engine.at
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The program image's upper layers in the preview. Media3's CompositionPlayer can't (yet) show several
 * video sequences, so each upper layer (V2, V3, cross dissolves) is played by its own silent ExoPlayer in a
 * TextureView that the app positions, scales, rotates and fades according to the timeline. Titles and captions
 * are drawn on top. Export does the same thing for real with Media3's compositor.
 */
@Composable
fun UpperLayers(
    controller: ProjectController, state: EditorState, main: Player? = null, modifier: Modifier = Modifier,
    gradeOf: (Long) -> se.gendut.klipp.engine.ClipGrade? = { null },
    look: se.gendut.klipp.engine.Look = se.gendut.klipp.engine.Look.NONE,
    /** Changes when the clips' grades change (pre-rendered chunks may then have become invalid). */
    gradesKey: Any? = null,
    /** Titles and captions too (false: drawn by [ProgramTitles], outside the look drawn over the whole picture). */
    titles: Boolean = true,
) {
    val plan = rememberPreviewPlan(controller, gradeOf, gradesKey) ?: return
    // diagnostics (CutTrace): the main player's reported position at every display frame while playing
    if (TRACE.on && main != null) LaunchedEffect(main) {
        while (true) {
            withFrameNanos { }
            if (state.playing) TRACE.log("mainpos", "pos=${main.currentPosition} playing=${main.isPlaying} state=${main.playbackState}")
        }
    }
    Box(modifier.fillMaxSize().testTag("upperLayers")) {
        // the bottom layer is played by the main player; the others from the bottom up
        val uppers = plan.layers.dropLast(1).reversed()
        // edit points in the main player's layer: the next clip is already decoded in its own player and covers
        // the switch (see Handoff) – directly above the main player, below the other layers
        // clips with motion blur stay in the main player (not yet measured in the edit point layer; diagnostics:
        // CutTrace.stabHandoff). Stabilized clips are covered since the layer's clock was fixed (plan risk 6, measured with
        // a shaking frame-coded clip: the background stands within 2 px through the layer's second, as in the main player)
        val stabilized = plan.layers.lastOrNull()?.segs.orEmpty().mapNotNull { it.clip?.id }.filter { id -> gradeOf(id)?.mblur != null }
        if (TRACE.on) for (l in plan.layers) TRACE.log("plan", "layer=${l.name} " + l.segs.joinToString(" ") { sg -> "%.2f+%.2f:%d%s%s".format(java.util.Locale.ROOT, sg.start, sg.dur, sg.src.item, if (sg.hasFx) "fx" else "", if (sg.ramp != null) "ramp" else "") })
        val oneRenderer = se.gendut.klipp.engine.LiveCuts.oneRenderer
        val handoff = remember(plan, se.gendut.klipp.engine.Proxies.revision, stabilized, se.gendut.klipp.engine.CutTrace.stabHandoff, oneRenderer, se.gendut.klipp.engine.HoldVideos.revision) {
            plan.layers.lastOrNull()?.let { base ->
                Handoff.layer(base, oneRenderer, gapPlayed = { p, n ->
                    se.gendut.klipp.engine.LiveCuts.gapAsVideo(n.start - (p?.end ?: 0.0), p?.let { controller.uriOf(it.src.item) }, controller.uriOf(n.src.item))
                }, stillPlayed = { sg ->
                    (sg.src as? se.gendut.klipp.engine.SegSrc.Still)?.let { st -> se.gendut.klipp.engine.HoldVideos.playable(controller.uriOf(st.item), st.sec, sg.dur) } == true
                }) { s ->
                    val u = controller.uriOf(s.src.item) ?: return@layer false
                    // (diagnostics: CutTrace.stabHandoff lets clips with motion blur in, to measure them first)
                    if (s.clip?.id in stabilized && !se.gendut.klipp.engine.CutTrace.stabHandoff) return@layer false
                    // originals without a proxy: HDR is tone mapped differently in the two players, and a heavy codec (APV,
                    // ProRes …) would be decoded twice at once – 4K APV at 1.9 Gbit/s is ~115 MB per player for its half
                    // second of buffer; with the edit point layer on top the app ran out of memory (OnePlus, 512 MB heap)
                    val original = se.gendut.klipp.engine.Proxies.videoUri(u) == u
                    !(original && (se.gendut.klipp.engine.Perf.cachedHdr(u) == true || se.gendut.klipp.engine.Perf.heavy(se.gendut.klipp.engine.Perf.cachedCodec(u))))
                }
            }
        }
        // (diagnostics mode 1: the main player alone, see CutTrace)
        if (handoff != null && se.gendut.klipp.engine.CutTrace.mode != 1) key("klippbyten") { LayerVideo(handoff, plan, controller, state, main, gradeOf, look, handoff = true) }
        // back-to-back segments (a dissolve's two halves, clips next to each other in an upper track): two players
        // taking turns, see Alternate
        for (layer in uppers) for (part in Alternate.split(layer)) key(part.name) { LayerVideo(part, plan, controller, state, main, gradeOf, look, planName = layer.name) }
        if (titles && plan.overlays.isNotEmpty()) OverlayText(plan, state)
    }
}

/**
 * The same plan as the main player's (CompositionBuilder): same lengths and same layers – layers without a playable
 * file don't count, otherwise the two could disagree on which layer is the bottom (and a layer would be decoded twice).
 * (Reversed clips play from their reversed files once those are ready – then a new plan.) Pre-rendered chunks are
 * swapped in as in the main player (there the layers draw nothing – the file already has it all).
 */
@Composable
fun rememberPreviewPlan(controller: ProjectController, gradeOf: (Long) -> se.gendut.klipp.engine.ClipGrade?, gradesKey: Any?): LayerPlan? {
    val snap = controller.snapshot
    return remember(controller.revision, snap, se.gendut.klipp.engine.Reversals.revision, se.gendut.klipp.engine.RenderCache.revision, se.gendut.klipp.engine.FrameSnap.revision, gradesKey) {
        runCatching {
            se.gendut.klipp.engine.CompositionBuilder.previewPlan(snap, controller::uriOf)?.let { p ->
                val chunks = se.gendut.klipp.engine.RenderCache.chunks(p, gradeOf, controller::uriOf, snap.fps, se.gendut.klipp.engine.VideoPipeline.previewShort)
                se.gendut.klipp.engine.CompositionBuilder.playPlan(snap, controller::uriOf, chunks)
            }
        }.getOrNull()
    }
}

/** Titles and captions alone, over the picture (when the look is drawn over the picture: they are not in it, as in the export). */
@Composable
fun ProgramTitles(controller: ProjectController, state: EditorState, gradeOf: (Long) -> se.gendut.klipp.engine.ClipGrade?, gradesKey: Any?) {
    val plan = rememberPreviewPlan(controller, gradeOf, gradesKey) ?: return
    if (plan.overlays.isNotEmpty()) Box(Modifier.fillMaxSize().testTag("programTitles")) { OverlayText(plan, state) }
}

@OptIn(UnstableApi::class)
@Composable
private fun LayerVideo(
    layer: Layer, plan: LayerPlan, controller: ProjectController, state: EditorState, main: Player?,
    gradeOf: (Long) -> se.gendut.klipp.engine.ClipGrade?, look: se.gendut.klipp.engine.Look,
    /** The handoff layer: shown only during playback; when paused the player waits ready at the next edit point. */
    handoff: Boolean = false,
    /** The plan's layer this one plays (or part of it, see Alternate). */
    planName: String = layer.name,
) {
    val ctx = LocalContext.current
    // (how early each frame may go out: up to the layer's threshold, but never far earlier than the frame before it)
    val ramp = remember { EarlyRamp(if (handoff) HANDOFF_EARLY_US else LAYER_EARLY_US) }
    val player = remember {
        // a TextureView shows a frame as soon as it arrives (a SurfaceView waits for the frame's time): released up to
        // 50 ms early (Media3's default) the layer's frames were seen about one frame before the main player's at 30 fps
        // (measured with frame-coded clips) – here at most LAYER_EARLY_US before their time
        val renderers = LayerRenderersFactory(ctx, ramp)
        ExoPlayer.Builder(ctx, renderers).setLoadControl(se.gendut.klipp.engine.Perf.editingLoadControl()).build().apply {
            volume = 0f // audio comes from the main player
            // the audio track isn't decoded at all in the layer players (saves power, and speed adjustments come for free)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // which frame the layer's TextureView holds: the video chain's frames by the time they are released at
    // (SurfaceTexture.timestamp), see the edit point layer's start below
    val released = remember(player) { ReleasedFrames(ramp).also { player.setVideoFrameMetadataListener(it) } }
    val clock = remember { LayerClock() }
    // whether the layer's frames go through the timeline grid (LayerGridEffect): then they are shown at slot times
    val gridOn = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // paused: the layer's frame for the playhead's slot (as the main player's, see PausedPick) – chosen in the grid's
    // select stage, the results read by the sync loop below
    val pickResult = remember { java.util.concurrent.atomic.AtomicReference<se.gendut.klipp.engine.PausedPick.Result?>(null) }
    val pick = remember(player) { se.gendut.klipp.engine.PausedPick { r -> pickResult.set(r) }.also { p -> p.trace = { TRACE.log("pick", "layer $it") } } }
    // a pick stands the player before the slot on purpose: a repaint (new effects, a changed grade) picks again instead of
    // stepping a millisecond from there (that showed the first frame after the pick's position – measured on the vivo)
    val pickActive = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val repick = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    suspend fun repaintLayer() { if (pickActive.get()) repick.intValue++ else repaint(player) }
    val segs = remember(layer) { layer.segs.filter { controller.uriOf(it.src.item) != null } }
    // the edit point layer starts each segment a little early, unseen (see HANDOFF_PREROLL_S): source ms before the
    // segment's start, per segment (0 = none)
    val preMs = remember(segs, plan) { segs.indices.map { k -> if (handoff) handoffPreroll(segs, k, plan) else 0L } }
    fun preS(k: Int): Double = if (Preroll.off) 0.0 else (segs[k].src as? SegSrc.Media)?.takeIf { it.speed > 0 }?.let { preMs[k] / 1000.0 / it.speed } ?: 0.0
    // where the player waits for segment k: the pre-roll's start, or the segment's own start without pre-roll
    fun waitMs(k: Int): Long = if (Preroll.off) preMs[k] else 0L
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    // the segment (index in the player) whose frame has actually been drawn on the layer's surface – until then the layer
    // isn't shown at all, so a dissolve never blends in an empty (black) surface while the decoder starts
    var drawnIndex by remember { mutableIntStateOf(-1) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) { videoSize = v }
            // (a layer player that fails shows nothing – in the trail, with the file)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                se.gendut.klipp.CrashReport.note("layer ${layer.name}: ${error.errorCodeName} ${error.message} (${error.cause?.javaClass?.simpleName}: ${error.cause?.message}) item ${player.currentMediaItemIndex} ${player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment}")
            }
            override fun onRenderedFirstFrame() {
                drawnIndex = player.currentMediaItemIndex
                TRACE.log("firstFrame", "layer=${layer.name} idx=${player.currentMediaItemIndex} pos=${player.currentPosition}")
            }
            // if it plays, it draws (safety net in case a first-frame event never arrives)
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) drawnIndex = player.currentMediaItemIndex
                TRACE.log("isPlaying", "layer=${layer.name} $isPlaying idx=${player.currentMediaItemIndex} pos=${player.currentPosition}")
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // played on into the next segment: its frame follows right away (pre-buffered); a seek waits for its frame
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) drawnIndex = player.currentMediaItemIndex else drawnIndex = -1
                TRACE.log("itemTransition", "layer=${layer.name} reason=$reason idx=${player.currentMediaItemIndex} pos=${player.currentPosition}")
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // the image from the proxy when there is one (swapped when a proxy is ready – once playback has stopped: reloading
    // the layer's items while it plays made it seek and wait mid-play)
    var proxyRev by remember { mutableIntStateOf(se.gendut.klipp.engine.Proxies.revision) }
    LaunchedEffect(Unit) {
        snapshotFlow { se.gendut.klipp.engine.Proxies.revision to state.playing }.collect { (r, playing) -> if (!playing) proxyRev = r }
    }
    // each item's start in its file (ms): where its frames' times are counted from (see LayerClock.baseUs)
    val clipStartMs = remember(segs, preMs) { segs.indices.map { k -> clipStartMs(segs[k], preMs[k]) } }
    // an export lets the layer's decoder go; afterwards the player is prepared again (the sync loop seeks as needed)
    val exporting = se.gendut.klipp.engine.BackgroundWork.exporting
    LaunchedEffect(player, exporting) {
        if (exporting) { player.playWhenReady = false; player.stop() }
        else if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            val k = player.currentMediaItemIndex
            player.prepare()
            clock.baseUs = clipStartMs.getOrNull(k)?.let { (it + player.currentPosition) * 1000 } ?: Long.MIN_VALUE
        }
    }
    LaunchedEffect(segs, proxyRev, preMs) {
        player.stop()
        player.setMediaItems(segs.mapIndexed { k, seg ->
            // freeze frame: just the frame (the player stays paused on it for the whole segment, see below)
            val s = seg.src as? SegSrc.Media ?: (seg.src as SegSrc.Still).let { st -> SegSrc.Media(st.item, st.sec, 0.0) }
            MediaItem.Builder().setUri(controller.uriOf(s.item)?.let { se.gendut.klipp.engine.Proxies.videoUri(it) })
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        // start ≥ 0 and end after start (camera files can have a negative start time, see Playback.clipped)
                        .setStartPositionMs(clipStartMs[k])
                        .setEndPositionMs(((s.srcIn + (if (s.speed == 0.0) 0.1 else (seg.dur + (if (handoff) HANDOFF_ITEM_TAIL_S else UPPER_ITEM_TAIL_S)) * kotlin.math.abs(s.speed))) * 1000).roundToLong().coerceAtLeast((s.srcIn * 1000).roundToLong().coerceAtLeast(0L) + 1))
                        .build()
                ).build()
        })
        drawnIndex = -1
        player.prepare()
        clock.baseUs = clipStartMs.getOrNull(0)?.let { it * 1000 } ?: Long.MIN_VALUE
    }
    // the layer's view (its latest frame time, for the playback summary)
    var view by remember { mutableStateOf<android.view.TextureView?>(null) }
    var active by remember { mutableIntStateOf(-1) }
    // the edit point layer stays after a pause until the main player's frame is graded again (see holdSeg)
    var holding by remember { mutableStateOf(false) }
    // playing a segment's pre-roll (unseen, see preMs)
    var preRolling by remember { mutableStateOf(false) }
    // the segment whose effects (grade, LUT) are loaded in the player: the active one, or in a gap the one the player
    // is already waiting on – so the LUT is there from the first frame instead of a few frames in
    var cue by remember { mutableIntStateOf(-1) }
    /**
     * A seek in the layer's player. Its frames' times are counted from where its renderer began (Media3: the first stream
     * after a reset), and a seek to another item doesn't reset it – the times then depended on what had played before
     * (measured: −54.7 s at an edit point), so stabilization, tracked windows, masks and the edit point layer's
     * transitions got the wrong frame's values (plan risk 6). To another item: a fresh start, from a known point.
     */
    fun seekLayer(idx: Int, ms: Long) {
        if (idx != player.currentMediaItemIndex || player.playbackState == Player.STATE_IDLE) {
            player.stop(); player.seekTo(idx, ms); player.prepare()
            clock.baseUs = clipStartMs(segs[idx], preMs[idx]).let { (it + ms) * 1000 }
        } else player.seekTo(idx, ms)
    }
    // Follow the main player. Both play at normal speed from the same starting point, so they don't drift apart
    // (no time stretch). The layer only starts once the main player's position actually begins to advance –
    // otherwise it ends up ahead by the main player's startup delay. The drift is measured continuously (diagnostics view)
    // and only a real hiccup (> 200 ms, e.g. a decoder that got stuck) is corrected with a seek.
    // proxyRev: when a proxy is swapped in the segments are reloaded (the player then starts from the beginning) – sync
    // restarts and seeks to the playhead right away, even when it is standing still
    LaunchedEffect(segs, main, proxyRev, preMs) {
        if (TRACE.on) TRACE.log("segs", "layer=${layer.name} starts=${segs.map { "%.2f".format(java.util.Locale.ROOT, it.start) }} pre=$preMs prerollOff=${Preroll.off}")
        var lastSeek = 0L
        // the paused pick (see the pick above): which segment and slot it is for, its attempts and seeks
        var pickKey = Long.MIN_VALUE; var pickGen = 0; var pickAttempt = 0; var pickSeeks = 0; var pickAt = 0L
        var pickDone = false; var pickSlotUs = 0L; var pickFromStart = false; var seenRepick = repick.intValue
        // the frame the pick found (timeline µs, as LayerClock gives it; MIN_VALUE = none)
        var pickFoundUs = Long.MIN_VALUE
        // started from the frame shown (see below): it stands there, not on the playhead, until it runs
        var fromShownHeld = false
        // … that far behind the playhead (ms): the rate follower begins with it when the layer runs
        var startLateMs = 0.0
        var lastMainMs = -1L
        val stats = SyncStats.forLayer(layer.name)
        // the layer's clock on the main player's while playing (not the edit point layer's: it goes by its own at the cut)
        val rate = LayerSync.Rate()
        // (frames released since this time (ns) and for this segment count for it)
        var rateFromNs = 0L; var rateSeg = -1
        // the edit point layer: the segment it is covering, the main chain's frames of that clip when it began, and
        // when the main player had enough of them (see Occlusion.mainFrames)
        var handSeg = -1; var handFrames = 0L; var handReadyAt = 0L; var handDown = false
        // … the main player's clips whose film it skipped meanwhile (it covered them, see Occlusion.hideMain), their
        // graded frames when it stopped doing that, and when enough of them had come
        val handHid = HashSet<Long>(); var handFilmBase = 0L; var handGradedAt = 0L; var handForced = false
        // paused while the main player's picture could still be one without its film: the layer keeps showing its own
        // frame (segment holdSeg) until the main player has drawn its frame again, graded
        var holdSeg = -1; var holdUntil = 0L; var holdBase = 0L; var holdGradedAt = 0L
        val holdIds = HashSet<Long>()
        fun filmedOf(ids: Set<Long>) = ids.sumOf { se.gendut.klipp.engine.Occlusion.filmedFrames(it) }
        fun unhide() { for (c in handHid) se.gendut.klipp.engine.Occlusion.showMain(c) }
        // the main player's layer (the one the edit point layer covers)
        val mainLayer = plan.layers.lastOrNull()
        // the layers above it (see starting)
        val uppers = if (handoff) plan.layers.dropLast(1) else emptyList()
        var seenSeg = -1
        // when the current pre-roll began (uptime ms; 0 = none)
        var prerollAt = 0L
        // since when playback runs (frame time ms; 0 = paused)
        var playingSince = 0L
        // the layers below this one (their clips are hidden while this one covers the frame, see Occlusion)
        val below = plan.layers.indexOfFirst { it.name == planName }.let { k -> if (k < 0 || handoff) emptyList() else plan.layers.drop(k + 1) }
        // paused and done seeking: sleep until playback starts or the playhead moves (no needless frame loop)
        suspend fun idle() {
            val ph = state.playhead
            val rp = repick.intValue
            snapshotFlow { state.playing || state.playhead != ph || repick.intValue != rp }.first { it }
            lastMainMs = -1L
        }
        // (marks left by the edit point layer are lifted right away when it stops, not after they lapse)
        try { while (true) {
            val now = withFrameNanos { it } / 1_000_000
            val mainMs = main?.currentPosition
            val advancing = mainMs != null && lastMainMs >= 0 && mainMs != lastMainMs && main.isPlaying
            lastMainMs = mainMs ?: -1L
            // (by the playhead until the main player has been told to play: as playback begins it may still stand where its
            // paused pick left it, before the slot – the layer then took itself for outside its segment, measured)
            val t = if (mainMs != null && state.playing && main.playWhenReady) mainMs / 1000.0 else se.gendut.klipp.playheadSec(state.playhead)
            var i = if (handoff && !state.playing) -1 else segs.indices.indexOfFirst { k -> t >= segs[k].start - preS(k) && t < segs[k].end }
            if (handoff && !state.playing && handSeg >= 0 && handHid.isNotEmpty() && !handDown) {
                // paused during the handover: the main player draws its frame again (a seek one millisecond away, as
                // Playback.redraw), now with its film; the layer stays meanwhile
                unhide()
                holdIds.clear(); holdIds += handHid; holdBase = filmedOf(holdIds); holdGradedAt = 0L
                holdSeg = handSeg; holdUntil = now + HANDOFF_HOLD_MS
                main?.let { m -> val pos = m.currentPosition; m.seekTo(if (pos < 1) pos + 1 else pos - 1) }
                handHid.clear(); handSeg = -1
            }
            if (holdSeg >= 0) {
                val graded = filmedOf(holdIds) > holdBase
                if (graded && holdGradedAt == 0L) holdGradedAt = now
                // (only once the main player's frame has its film again – played on, or the playhead moved, or not)
                if (now >= holdUntil || graded && (state.playing || now - holdGradedAt >= HANDOFF_GRADED_MS)) {
                    holdSeg = -1; holding = false
                } else {
                    holding = true; preRolling = false; active = holdSeg
                    if (player.playWhenReady) player.pause()
                    continue
                }
            }
            // the edit point layer stands down once the main player has the new clip going (it computed the same
            // picture in the main chain for a whole second before – twice the GPU work at every cut)
            // (outside its segments – and when paused – it starts over: a segment played again gets the layer again)
            if (handoff && i < 0) { if (handSeg >= 0) unhide(); handSeg = -1 }
            if (handoff && i >= 0) {
                val id = segs[i].clip?.id
                if (i != handSeg) {
                    if (handSeg >= 0) unhide()
                    handSeg = i; handFrames = id?.let { se.gendut.klipp.engine.Occlusion.mainFrames(it) } ?: 0L; handReadyAt = 0L; handDown = false
                    handHid.clear(); handGradedAt = 0L; handForced = false
                    // entered after the edit point (playback started, or a seek, inside the segment): the main player
                    // doesn't switch clips here – nothing to cover, and the layer would only start up on top of it
                    if (t - segs[i].start > HANDOFF_LATE_S) handDown = true
                }
                // … and plays on time: a decoder that has just started can still drop frames as late for a while (they
                // showed as a stutter right after the layer stood down)
                val calm = android.os.SystemClock.uptimeMillis() - androidx.media3.effect.KlippPrecision.lastLateAt >= HANDOFF_CALM_MS
                // the main player's film runs again from here; with frames skipped before, the layer waits until graded
                // frames have come through and the ones without film have been shown under it
                // … and no other layer is just starting (a dissolve's second half begins at the edit point – the main
                // player's film coming back then made three pictures to compute at once while that one got going)
                val starting = uppers.any { l -> l.segs.any { it.start > t - HANDOFF_STARTING_S && it.start <= t + 0.05 } }
                if (handReadyAt == 0L && id != null && se.gendut.klipp.engine.Occlusion.mainFrames(id) - handFrames >= HANDOFF_FRAMES && calm && !starting) {
                    handReadyAt = now; unhide(); handFilmBase = filmedOf(handHid)
                    TRACE.log("handReady", "seg=$i t=$t")
                }
                if (handReadyAt != 0L) {
                    if (handHid.isNotEmpty() && handGradedAt == 0L && filmedOf(handHid) - handFilmBase >= HANDOFF_FILMED) handGradedAt = now
                    val ok = if (handHid.isEmpty()) now - handReadyAt >= HANDOFF_SETTLE_MS else handGradedAt != 0L && now - handGradedAt >= HANDOFF_GRADED_MS
                    // (forced: the layer needed a seek – it goes as soon as the main player's frames are graded)
                    if (ok && (calm || handForced) && !handDown) { handDown = true; TRACE.log("handDown", "seg=$i t=$t forced=$handForced") }
                }
                // once stood down it stays down for the rest of the segment: coming back (a late frame after the
                // handover) meant a seek in the paused player – itself a hitch
                if (handDown) i = -1
            }
            // (in a segment's pre-roll the layer plays unseen)
            preRolling = i >= 0 && t < segs[i].start
            // the edit point layer goes by its own player's clock at the edit point: it plays in step from the pre-roll on,
            // while the main player's reported position stands still exactly while its decoder switches at the cut – going
            // by that, the layer was seen only after the main player had held its last frame (~80 ms) and skipped the next
            // clip's first one (measured with CutTrace, a mark on the layer and frame-coded clips)
            if (handoff && preRolling && state.playing && player.isPlaying && player.currentMediaItemIndex == i) {
                // seen as soon as its view holds the segment's first frame (the item begins preMs before the segment) –
                // the player's reported position comes ~20 ms after the frame; it starts the layer if the frame's
                // time can't be told
                // … the frame that covers the edit point, that is: with the clip's frames off the main player's grid (a
                // pre-rendered piece at the project rate, then 4K 120 fps at 25 %: a frame every 33 ms that began 0–33 ms
                // after the cut) waiting for one that begins at the cut held the main player's last frame for 80–100 ms
                // On the timeline grid the segment's first frame is shown at its slot and every pre-roll frame before it
                // (LayerGrid): seen from that slot on – the time a frame is shown at, mapped back by the clock
                val shownPts = view?.surfaceTexture?.timestamp?.let { released.ptsAt(it) }
                val segUs = Math.round(segs[i].start * 1e6)
                val atCut = if (gridOn.get()) shownPts?.let { clock.timelineUs(it) }?.let { it >= se.gendut.klipp.engine.FrameGrid(controller.snapshot.fps).earliestUs(segUs) - 500 }
                    else shownPts?.let { clock.itemUs(it, clipStartMs[i]) }?.let { EditPointFrame.covers(it, released.stepUs(), preMs[i] * 1000) }
                val shown = shownPts?.let { clock.itemUs(it, clipStartMs[i]) }
                if (atCut == true || player.currentPosition >= preMs[i]) {
                    preRolling = false
                    TRACE.log("handStart", "seg=$i t=$t shown=$shown pos=${player.currentPosition}")
                }
            }
            // the main player fell behind while a pre-roll ran (a heavier picture, a slower phone): it stops right away
            // and isn't used again this session – the layer then starts on the frame itself, as before
            // (not when playback itself just started – the main player's own start can be late)
            if (state.playing) { if (playingSince == 0L) playingSince = now } else playingSince = 0L
            if (preRolling && handoff && now - playingSince >= PREROLL_ARMED_MS) {
                if (prerollAt == 0L) prerollAt = android.os.SystemClock.uptimeMillis()
                else if (androidx.media3.effect.KlippPrecision.lastLateAt >= prerollAt && !Preroll.off) {
                    Preroll.off = true
                    se.gendut.klipp.CrashReport.note("edit point pre-roll off: the video chain was late while it ran")
                }
                if (Preroll.off) { preRolling = false; i = -1 }
            } else prerollAt = 0L
            if (handoff) SyncStats.handoff(i >= 0 && !preRolling)
            // frames reaching the screen while the layer is seen (playback summary)
            if (i >= 0 && state.playing && !preRolling) {
                val ts = view?.surfaceTexture?.timestamp ?: 0L
                if (i != seenSeg) { seenSeg = i; stats.visible(ts, now) } else stats.frame(ts, now)
            } else seenSeg = -1
            active = i
            if (TRACE.on && (state.playing || handoff)) {
                val vis = i >= 0 && drawnIndex == i && !preRolling && (!handoff || state.playing || holding) && !(handoff && se.gendut.klipp.engine.CutTrace.mode == 2)
                TRACE.log("loop", "layer=${layer.name} t=${"%.4f".format(t)} main=$mainMs i=$i drawn=$drawnIndex pre=$preRolling vis=$vis " +
                    "idx=${player.currentMediaItemIndex} pos=${player.currentPosition} pwr=${player.playWhenReady} playing=${player.isPlaying} " +
                    "state=${player.playbackState} tex=${view?.surfaceTexture?.timestamp ?: 0L} ready=$handReadyAt down=$handDown")
            }
            // the edit point layer covering the whole frame, opaque, while playing: the main player's picture of the same
            // clip isn't seen – its film is skipped until the layer begins to stand down (and not in the last LEAD of the
            // layer's segment), see Occlusion.hideMain
            if (handoff && se.gendut.klipp.engine.CutTrace.mode != 2 && i >= 0 && !preRolling && handReadyAt == 0L && state.playing && drawnIndex == i && player.isPlaying) {
                val sg = segs[i]
                val ahead = t + se.gendut.klipp.engine.Occlusion.LEAD_S
                val full = se.gendut.klipp.engine.Occlusion.Box(0.0, 0.0, plan.width.toDouble(), plan.height.toDouble())
                if (ahead < sg.end && coverBox(sg, t, plan, videoSize, gradeOf)?.contains(full) == true && coverBox(sg, ahead, plan, videoSize, gradeOf)?.contains(full) == true) {
                    mainLayer?.at(t)?.clip?.id?.let { c -> se.gendut.klipp.engine.Occlusion.hideMain(c); handHid += c }
                }
            }
            // covering the whole frame, opaque, while playing: the clips below aren't seen – their film is skipped
            // (marked until LEAD before this one ends, so they are graded again before they show)
            if (i >= 0 && below.isNotEmpty() && state.playing && drawnIndex == i && player.isPlaying) {
                val sg = segs[i]
                val ahead = t + se.gendut.klipp.engine.Occlusion.LEAD_S
                val cover = if (ahead < sg.end) coverBox(sg, t, plan, videoSize, gradeOf) else null
                val coverAhead = cover?.let { coverBox(sg, ahead, plan, videoSize, gradeOf) }
                if (cover != null && coverAhead != null) {
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    for (l in below) {
                        val bs = l.at(t) ?: continue
                        val id = bs.clip?.id ?: continue
                        val size = controller.uriOf(bs.src.item)?.let { se.gendut.klipp.engine.Occlusion.Sizes.of(ctx, it) } ?: continue
                        val inside = listOf(t, ahead).all { at -> clipBox(bs, at, plan, size)?.let { b -> (if (at == t) cover else coverAhead).contains(b) } == true }
                        if (inside) se.gendut.klipp.engine.Occlusion.hide(id, nowMs)
                    }
                }
            }
            if (i < 0) {
                if (player.playWhenReady) player.pause()
                rate.reset(); rateFromNs = 0L
                // a pick from an earlier pause in a segment is over: the player now waits on its next segment (below).
                // Left standing, its end – a seek – came as that segment began while playing, over the prepared frame
                if (pickKey != Long.MIN_VALUE) { pick.clear(); pickKey = Long.MIN_VALUE; pickActive.set(false); pickFoundUs = Long.MIN_VALUE }
                // prepare the next segment right away (paused on its first frame) – the file is opened and the decoder started
                // in good time, even the first time and with heavy originals (4K, HEVC) that take a while to get going
                val next = segs.indexOfFirst { it.start > t }
                // (also when it is in that segment but not at its start: a layer with a single segment, or one played
                // before, still stood where it stopped – and had to seek from a keyframe as the segment began)
                val sought = next >= 0 && (player.currentMediaItemIndex != next || abs(player.currentPosition - waitMs(next)) > PREPARED_MS)
                if (sought) { seekLayer(next, waitMs(next)); lastSeek = now; TRACE.log("seek", "layer=${layer.name} wait idx=$next ms=${waitMs(next)} t=$t") }
                if (next >= 0) {
                    cue = next
                    // the clock for the segment waited on (risk 6): its effects are already loaded (cue), and the frame it
                    // waits on – and the first ones after it starts – read their source time from the clock (stabilization,
                    // tracked windows, animated masks). Before, the previous segment's values stayed until it became active
                    val ns = segs[next]
                    clock.srcIn = (ns.src as? SegSrc.Media)?.let { it.srcIn - preMs[next] / 1000.0 } ?: (ns.src as? SegSrc.Still)?.sec ?: 0.0
                    clock.posSec = waitMs(next) / 1000.0; clock.item = ns.src.item
                    clock.seg = (ns.src as? SegSrc.Media)?.let { m -> LayerClock.SegTime(ns.start, m.srcIn, m.speed, ns.end, ns.cut) }
                }
                if (!state.playing && !sought) idle()
                continue
            }
            val seg = segs[i]
            cue = i
            val media = seg.src as? SegSrc.Media
            // freeze frame: stays on the frame (the segment's start) the whole time
            val speed = media?.speed?.toFloat() ?: 1f
            // (… times the rate that keeps it on the main player's clock while playing, see LayerSync.Rate)
            val want = speed * rate.factor.toFloat()
            if (abs(player.playbackParameters.speed - want) > (if (rate.factor == 1.0) 0.0001f else 0.002f * speed)) player.setPlaybackSpeed(want)
            // (the item starts at the segment's source time, less its pre-roll; inside the segment this is Seg.sourceAt – the
            // same mapping as the export – but it must hold before the segment's start too: during the edit point layer's
            // pre-roll t is before it. Taking the segment's start there instead (Seg.sourceAt is null outside) made the
            // target jump by the whole pre-roll – a sync seek with lead just before the cut put the layer two frames ahead,
            // measured with CutTrace and frame-coded clips: at the cut a frame was skipped)
            val target = if (media == null) 0L else LayerSync.targetMs(t, seg, preMs[i])
            clock.srcIn = media?.let { it.srcIn - preMs[i] / 1000.0 } ?: (seg.src as SegSrc.Still).sec; clock.posSec = target / 1000.0; clock.item = seg.src.item
            clock.seg = media?.let { LayerClock.SegTime(seg.start, it.srcIn, it.speed, seg.end, seg.cut) }
            // (once going it keeps going while the main player plays: its reported position often stands still for a few
            // display frames although it plays on – the layer was paused, and often sought, at each of those)
            val run = media != null && state.playing && (main == null || advancing || player.playWhenReady && main.isPlaying)
            // paused on a layer drawn through the grid: the slot's frame as the export has it, not the first frame at the
            // position (Media3 shows that one – the next slot's frame, measured on the vivo). The playhead stays; the layer
            // is sought before the slot and its grid shows only the slot's frame; the sync below stays out of it meanwhile
            val fpsP = controller.snapshot.fps
            if (!state.playing && media != null && gridOn.get() && fpsP > 0.0 && !handoff && t >= seg.start - 1e-6) {
                val g = se.gendut.klipp.engine.FrameGrid(fpsP)
                val k = Math.round(t * fpsP)
                val key = i * 1_000_000_000L + k
                val itemStart = seg.start - preS(i)
                // (a seek to where the player stands is ignored – no flush, no frames: first a millisecond away)
                fun pickSeek(r: se.gendut.klipp.engine.PausedPick.Request, ms: Long) {
                    val same = player.currentMediaItemIndex == i && player.currentPosition == ms
                    pick.request(if (same) r.copy(flushes = 2) else r)
                    if (same) seekLayer(i, ms + 1)
                    seekLayer(i, ms); lastSeek = now
                }
                fun fallback(why: String) {
                    pick.clear(); pickDone = true
                    TRACE.log("pick", "layer=${layer.name} fallback $why")
                    seekLayer(i, target); lastSeek = now
                }
                fun attempt(): Boolean {
                    val backs = se.gendut.klipp.engine.PausedSeek.backs(fpsP)
                    val slotSec = g.timeOf(k) / 1e6
                    // the attempts' starts before the slot, never before the layer's item (its pre-roll included), the
                    // item's start once and within reach
                    val starts = backs.map { slotSec - it }.filter { it > itemStart + 1e-6 } +
                        listOfNotNull(itemStart.takeIf { slotSec - it <= se.gendut.klipp.engine.PausedSeek.MAX_BACK_S })
                    val from = starts.getOrNull(pickAttempt) ?: return false
                    pickFromStart = from <= itemStart + 1e-6
                    pickSeek(se.gendut.klipp.engine.PausedPick.Request(pickGen, pickSlotUs, pickFromStart, stepUs = g.stepUs), LayerSync.targetMs(from, seg, preMs[i]).coerceAtLeast(0)); pickSeeks++
                    return true
                }
                if (repick.intValue != seenRepick) { seenRepick = repick.intValue; pickKey = Long.MIN_VALUE }
                if (key != pickKey) {
                    pickActive.set(true)
                    pickKey = key; pickGen++; pickAttempt = 0; pickSeeks = 0; pickDone = false; pickSlotUs = g.timeOf(k); pickAt = now
                    pickFoundUs = Long.MIN_VALUE
                    pickResult.set(null)
                    if (player.playWhenReady) player.playWhenReady = false
                    if (!attempt()) fallback("no attempt")
                } else if (!pickDone) {
                    val r = pickResult.getAndSet(null)
                    when {
                        r != null && r.gen != pickGen -> {}
                        r is se.gendut.klipp.engine.PausedPick.Result.Found -> { pickDone = true; pickFoundUs = r.frameUs }
                        r is se.gendut.klipp.engine.PausedPick.Result.Exact -> {
                            val ms = LayerSync.frameMs(r.frameUs, seg, preMs[i])
                            if (++pickSeeks > 6) fallback("too many seeks") else {
                                pickSeek(se.gendut.klipp.engine.PausedPick.Request(pickGen, pickSlotUs, pickFromStart, exactUs = r.frameUs, stepUs = g.stepUs), ms.coerceAtLeast(0))
                            }
                        }
                        r is se.gendut.klipp.engine.PausedPick.Result.Earlier -> { pickAttempt++; if (pickSeeks > 6 || !attempt()) fallback("no attempt left") }
                        now - pickAt > 2000 -> fallback("no frame in time")
                    }
                }
                if (pickDone) idle()
                continue
            } else if (pickKey != Long.MIN_VALUE) {
                // left the paused pick (playing, or a still): the layer stood before the slot – back to the playhead.
                // Played from there: to the frame it shows instead. A paused player shows the first frame after a seek at
                // once – from the playhead that is the next slot's (or a later one's), and it stood there while the main
                // player still showed the playhead's slot through its start (~150 ms, measured on the OnePlus). From the
                // frame shown, the layer's clock begins that much behind: the rate follower takes it back (LayerSync.Rate)
                // (only the pick for the playhead's slot: play pressed there. By the playhead – the main player may still
                // stand where its own pick left it, before the slot, as playback begins: measured 2.679 s for 2.80 s)
                val ph = se.gendut.klipp.playheadSec(state.playhead)
                val fromShown = state.playing && pickDone && pickFoundUs != Long.MIN_VALUE && media != null && fpsP > 0.0 &&
                    pickKey == i * 1_000_000_000L + Math.round(ph * fpsP) && LayerSync.startsFromShown(pickFoundUs, pickSlotUs)
                if (TRACE.on) TRACE.log("pick", "layer=${layer.name} leave playing=${state.playing} found=$pickFoundUs slot=$pickSlotUs t=$t fromShown=$fromShown")
                pick.clear(); pickKey = Long.MIN_VALUE; pickActive.set(false)
                if (media != null) {
                    val ms = if (fromShown) LayerSync.frameMs(pickFoundUs, seg, preMs[i]) else target
                    // (the pick may have left it on that very millisecond: a seek there is ignored – no flush, and the grid
                    // holds the frames after the pick's – first a millisecond away)
                    if (player.currentMediaItemIndex == i && player.currentPosition == ms) seekLayer(i, ms + 1)
                    seekLayer(i, ms); lastSeek = now
                }
                fromShownHeld = fromShown
                rate.reset(); rateFromNs = 0L
                if (fromShown) startLateMs = (pickSlotUs - pickFoundUs) / 1000.0
            }
            if (run && fromShownHeld) { rate.start(startLateMs); rateFromNs = System.nanoTime(); rateSeg = i }
            if (run || !state.playing) fromShownHeld = false
            val d = if (fromShownHeld) LayerSync.Decision(null) else LayerSync.decide(
                curIndex = player.currentMediaItemIndex, curMs = player.currentPosition, wantIndex = i, wantMs = target,
                running = run, ready = player.playbackState == Player.STATE_READY, msSinceSeek = now - lastSeek,
            )
            if (d.seekMs != null && handoff && handHid.isNotEmpty() && !handDown) {
                // the edit point layer has the main player's film off: a seek would hide it (until its frame comes) and
                // show the main player's picture without film – it stands down instead, still showing, see above
                if (handReadyAt == 0L) { handReadyAt = now; unhide(); handFilmBase = filmedOf(handHid) }
                handForced = true
            } else d.seekMs?.let { seekLayer(i, it); lastSeek = now; stats.seeks++; TRACE.log("seek", "layer=${layer.name} sync idx=$i ms=$it t=$t cur=${player.currentMediaItemIndex}/${player.currentPosition} state=${player.playbackState} drawn=$drawnIndex err=${player.playerError?.errorCodeName}") }
            if (player.playWhenReady != run) player.playWhenReady = run
            if (run && player.isPlaying && d.seekMs == null) stats.record(player.currentPosition - target)
            if (!handoff && run && player.isPlaying && main?.isPlaying == true && d.seekMs == null && lastSeek != now && fpsP > 0.0) {
                if (rateFromNs == 0L || i != rateSeg) { rateFromNs = System.nanoTime(); rateSeg = i }
                // both players' newest frames since then: how much later the layer shows its slot than the main player
                val m = se.gendut.klipp.engine.MainFrames.released.latest()
                val l = released.latest()
                val lTl = l?.let { clock.timelineUs(it.first) }
                if (m != null && l != null && lTl != null && m.second > rateFromNs && l.second > rateFromNs) {
                    val g = se.gendut.klipp.engine.FrameGrid(fpsP)
                    val slotL = g.timeOf(Math.round(lTl * fpsP / 1e6))
                    val slotM = g.timeOf(Math.round((m.first + se.gendut.klipp.engine.MainFrames.originUs) * fpsP / 1e6))
                    val late = ((l.second - slotL * 1000) - (m.second - slotM * 1000)) / 1e6
                    val f = rate.factor
                    rate.sample(late, 1000.0 / (view?.display?.refreshRate ?: 60f).toDouble())
                    if (TRACE.on) TRACE.log("late", "layer=${layer.name} ms=${"%.1f".format(java.util.Locale.ROOT, late)}")
                    if (TRACE.on && rate.factor != f) TRACE.log("rate", "layer=${layer.name} factor=${"%.4f".format(java.util.Locale.ROOT, rate.factor)} late=${"%.1f".format(java.util.Locale.ROOT, rate.lateMs)} sample=${"%.1f".format(java.util.Locale.ROOT, late)}")
                }
            } else if (!run || lastSeek == now) { rate.reset(); rateFromNs = 0L }
            if (!state.playing && d.seekMs == null && player.playbackState != Player.STATE_BUFFERING) idle()
        } } finally { unhide() }
    }
    val seg = segs.getOrNull(active)
    val c = seg?.clip
    // the clip's grade and the timeline look in the layer's player (the active or prepared segment, see cue)
    val gc = segs.getOrNull(cue)?.clip
    val grade = gc?.let { gradeOf(it.id) }
    // the effects are only replaced when the nodes (or the look) change – the values are read live from LiveGrades
    // (same key as the main player: nodes, tracking and stabilization)
    val structure = grade?.let { g -> se.gendut.klipp.engine.LiveGrades.structureOf(g) }
    // as in the main player: 4K material is brought down to the preview size before the grade and the film (same
    // picture as there; at the original's size the film took several times as long – the edit point layer fell
    // hundreds of milliseconds behind with diffusion and halation on originals)
    // (the source sizes' revision: a size that comes late rebuilds the layer – see SourceSizes)
    val sizesRev = se.gendut.klipp.engine.SourceSizes.revision
    val filmRef = gc?.let { se.gendut.klipp.engine.CompositionBuilder.filmRefOf(controller.uriOf(it.item)) } ?: 0
    val scaleShort = segs.getOrNull(cue)?.let { sg -> controller.uriOf(sg.src.item) }?.let { u ->
        val short = if (se.gendut.klipp.engine.Proxies.videoUri(u) != u) se.gendut.klipp.engine.Proxies.proxyShort(u)
            else se.gendut.klipp.engine.SourceSizes.short(u)
        val pv = se.gendut.klipp.engine.VideoPipeline.previewShort
        if (pv <= 0) null else se.gendut.klipp.engine.CompositionBuilder.previewSize(plan.width, plan.height, pv)
            .let { (pw, ph) -> se.gendut.klipp.engine.CompositionBuilder.previewShortSide(short, pw, ph) }
    }
    // the grid where the layer's frames can fall off it (FrameGrid.onGrid: speed, rate, a start or in point between
    // frames); with effects always (they are drawn anyway). Otherwise the decoder draws straight onto the view, no GL pass
    val projectFps = controller.snapshot.fps
    val offGrid = remember(segs, projectFps) {
        projectFps > 0.0 && segs.any { sg ->
            val m = sg.src as? SegSrc.Media ?: return@any false
            val f = se.gendut.klipp.engine.MediaProbe.frameRate(controller.uriOf(m.item), controller.snapshot.media.firstOrNull { it.id == m.item }?.fps ?: 0.0)
            !se.gendut.klipp.engine.FrameGrid.onGrid(sg.start, m.srcIn, m.speed, f, projectFps)
        }
    }
    val layerName = layer.name
    // segments played from a file stored with headroom (a proxy, see Headroom): the values back – per frame, the player
    // plays all the layer's segments with one effect list
    val headroomSegs = remember(segs, proxyRev) {
        segs.map { sg -> sg.start to se.gendut.klipp.engine.Headroom.isHeadroom(controller.uriOf(sg.src.item)?.let { se.gendut.klipp.engine.Proxies.videoUri(it) }) }
    }
    val headroomRef = remember { java.util.concurrent.atomic.AtomicReference<Pair<List<Pair<Double, Boolean>>, Int>>(emptyList<Pair<Double, Boolean>>() to -1) }
    headroomRef.set(headroomSegs to active)
    val anyHeadroom = headroomSegs.any { it.second }
    LaunchedEffect(gc?.id, structure, look, scaleShort, offGrid, anyHeadroom, filmRef, sizesRev) {
        if (gc != null && grade != null) se.gendut.klipp.engine.LiveGrades.publish(mapOf(gc.id to grade))
        // tracked windows: the frame's source time (see LayerClock)
        // always (live): a window tracked afterwards follows along without the effects being rebuilt
        val src: ((Long) -> Double?)? = if (grade != null) { pts -> clock.source(pts) } else null
        // green screen first (the source's colors), the mask last: the layer below shows through (the player's surface is transparent)
        val layer = grade?.layer?.takeIf { it.hasAlpha }
        val keyFx = gc?.let { cl -> layer?.key?.let { se.gendut.klipp.engine.LayerFxEffect(layer, cl.id, se.gendut.klipp.engine.LayerFxEffect.KEY, se.gendut.klipp.engine.LayerFxEffect.STRAIGHT) } }
        val maskFx = gc?.let { cl -> layer?.let { se.gendut.klipp.engine.LayerFxEffect(it, cl.id, se.gendut.klipp.engine.LayerFxEffect.MASK, se.gendut.klipp.engine.LayerFxEffect.PREMULTIPLIED, src) } }
        val headroomFx = if (!anyHeadroom) null else se.gendut.klipp.engine.Headroom.DynamicDecode { _ ->
            val (list, act) = headroomRef.get()
            // the segment the layer's clock is in (a freeze frame has none: the active one)
            val st = clock.seg?.startSec
            (if (st != null) list.minByOrNull { kotlin.math.abs(it.first - st) } else list.getOrNull(act))?.second ?: false
        }
        val fx = listOfNotNull(headroomFx, keyFx) + gc?.let { grade?.effects(it.id, clock = src, filmRef = filmRef) }.orEmpty() + se.gendut.klipp.engine.CompositionBuilder.videoEffects(look) + listOfNotNull(maskFx)
        // (no effects: none – the downscale alone would add a pass the plain picture doesn't need)
        val down = scaleShort?.takeIf { fx.isNotEmpty() }?.let { androidx.media3.effect.Presentation.createForShortSide(it) }
        // on the timeline's frame grid, as the main player and the export (see LayerGridEffect): a slowed 120 fps clip gave
        // 30 frames a second here – off the grid next to the main player's (after a pre-rendered whip the first frame was
        // nearly the file's last one, a visible pause). The choice first (the effects read the frames' own times), the
        // move to the slot last
        val gridFps = projectFps.takeIf { it > 0.0 && (offGrid || fx.isNotEmpty()) }
        val grid = gridFps?.let { f ->
            se.gendut.klipp.engine.LayerGridEffect(f, clock::timelineUs, { clock.seg?.let { Math.round(it.startSec * 1e6) } },
                { clock.seg?.endSec?.takeIf { it < 1e9 }?.let { Math.round(it * 1e6) } ?: Long.MAX_VALUE }, { clock.seg?.speed ?: 1.0 },
                { line: String -> TRACE.log("grid", "layer=$layerName $line") }, { TRACE.on }, { clock.seg?.cut == true }, pick)
        }
        gridOn.set(grid != null)
        runCatching { player.setVideoEffects(listOfNotNull(grid?.select, down) + fx + listOfNotNull(grid?.present)) }
        repaintLayer()
    }
    LaunchedEffect(grade) {
        if (gc == null || grade == null) return@LaunchedEffect
        se.gendut.klipp.engine.LiveGrades.publish(mapOf(gc.id to grade))
        repaintLayer()
    }
    val density = LocalDensity.current
    val blurShader = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(TRANSITION_BLUR) else null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val s = boxW / plan.width
        // the video fitted to the sequence frame (like Presentation in the export)
        val vw = if (videoSize.width > 0) videoSize.width * videoSize.pixelWidthHeightRatio else plan.width.toFloat()
        val vh = if (videoSize.height > 0) videoSize.height.toFloat() else plan.height.toFloat()
        val f = min(boxW / vw, boxH / vh)
        val w = with(density) { (vw * f).toDp() }
        val h = with(density) { (vh * f).toDp() }
        Box(
            Modifier.align(Alignment.Center).size(w, h)
                .graphicsLayer {
                    // read in the layer's draw phase: follows the keyframes frame by frame without recomposition
                    val c = c?.at(state.playhead.toDouble())
                    this.alpha = if (seg == null || drawnIndex != active || preRolling || (handoff && !state.playing && !holding) ||
                        (handoff && se.gendut.klipp.engine.CutTrace.mode == 2)) 0f else seg.alpha(state.playhead.toDouble())
                    // the blend mode (the program image is then drawn in a TextureView, see ProgramSurface): like the compositor's in the export
                    val bm = seg?.clip?.let { cl -> gradeOf(cl.id)?.layer?.blend }?.let(::composeBlend)
                    blendMode = bm ?: androidx.compose.ui.graphics.BlendMode.SrcOver
                    compositingStrategy = if (bm != null) androidx.compose.ui.graphics.CompositingStrategy.Offscreen else androidx.compose.ui.graphics.CompositingStrategy.Auto
                    if (c != null) {
                        translationX = (((c.x ?: (plan.width / 2.0)) - plan.width / 2.0) * s).toFloat()
                        translationY = (((c.y ?: (plan.height / 2.0)) - plan.height / 2.0) * s).toFloat()
                        val k = (c.scale / 100.0).toFloat()
                        scaleX = k; scaleY = k
                        rotationZ = c.rotation.toFloat()
                    }
                    // a fade's geometry, if any (transitions between two clips come pre-rendered – see RenderCache.playPlan)
                    val g = if (handoff) null else seg?.geomAt(state.playhead.toDouble())
                    if (g != null && !g.identity) {
                        // a mirrored picture is moved in the shader: it has to reach beyond its own edges
                        val shaded = g.mirror && blurShader != null && android.os.Build.VERSION.SDK_INT >= 33
                        translationX += (if (shaded) 0f else g.dx) * boxW; translationY += g.dy * boxH
                        scaleX *= g.scale; scaleY *= g.scale
                        val m = g.mask
                        if (m != null) { shape = TransitionMaskShape(m, boxW, boxH); clip = true } else clip = false
                        if ((shaded || g.blurX > 0f || g.blurZoom > 0f) && blurShader != null && android.os.Build.VERSION.SDK_INT >= 33)
                            renderEffect = transitionBlur(blurShader, size.width, size.height, g.blurX, g.blurZoom, if (shaded) g.dx else 0f, g.mirror, g.soft)
                    } else clip = false
                }
        ) {
            // a dedicated non-opaque TextureView: without a frame (yet) the layer below shows, never black
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { c -> android.view.TextureView(c).apply { isOpaque = false; player.setVideoTextureView(this); view = this } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { v -> player.clearVideoTextureView(v) },
            )
            // diagnostics mode 3 (CutTrace): the edit point layer marks itself, so a screen recording shows when it is seen
            if (handoff && se.gendut.klipp.engine.CutTrace.mode == 3) Box(Modifier.align(Alignment.TopStart).size(androidx.compose.ui.unit.Dp(24f)).then(androidx.compose.ui.Modifier.drawBehind { drawRect(androidx.compose.ui.graphics.Color.Red) }))
        }
    }
}

/**
 * The layer players' renderers: frames handed out at most [LAYER_EARLY_US] early (see the player), and a video chain
 * whose output is always SDR – HDR tone mapped with OpenGL, as the export does. Media3 otherwise takes the output's
 * colors from the first file: a layer that began with an HDR original could then not take an SDR file (its proxy, or an
 * SDR clip after it) – "SDR to HDR" isn't a conversion it has, the player failed and the layer stayed empty.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class LayerRenderersFactory(context: android.content.Context, private val ramp: EarlyRamp) : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: android.content.Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: android.os.Handler, eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        out.add(LayerVideoRenderer(
            androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                .setEarlySchedulingThresholdUs(ramp.maxUs),
            ramp,
        ))
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class LayerVideoRenderer(b: androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder, private val ramp: EarlyRamp) : androidx.media3.exoplayer.video.MediaCodecVideoRenderer(b) {
    override fun createPlaybackVideoGraphWrapper(
        context: android.content.Context, videoFrameReleaseControl: androidx.media3.exoplayer.video.VideoFrameReleaseControl,
    ): androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper {
        ramp.control = videoFrameReleaseControl
        return super.createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl).also { it.setRequestOpenGlToneMapping(true) }
    }

    override fun onStarted() { ramp.start(); super.onStarted() }
}

/**
 * How early a layer player hands out each frame. A TextureView shows a frame the moment it arrives, so two frames handed
 * out together show only the second: at a start the player holds its frames until its position moves, and then every
 * frame inside the threshold (up to [maxUs], longer than a frame at 25 or 30 fps) went out at once – V2 skipped its
 * first frame after the paused one in 4 of 6 starts (101 and 102 released 2–3 ms apart; measured with frame-coded
 * clips). Each frame may now go out at most [slackUs] earlier, against its own time, than the frame before it did: the
 * frames after a start come one frame time apart, and within a few frames the threshold is back at [maxUs] (unchanged
 * in steady playback).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class EarlyRamp(val maxUs: Long) {
    @Volatile var control: androidx.media3.exoplayer.video.VideoFrameReleaseControl? = null
    private var lastReleaseNs = 0L

    /** The player starts: the first frame may go out as early as ever. Playback thread. */
    fun start() { lastReleaseNs = 0L; control?.setEarlySchedulingThresholdUs(maxUs) }

    /** A frame goes out now ([nowNs]) to be shown at [releaseNs]: the threshold for the next one. Playback thread. */
    fun released(releaseNs: Long, nowNs: Long) {
        val c = control ?: return
        val gapUs = if (lastReleaseNs > 0) (releaseNs - lastReleaseNs) / 1000 else 0L
        lastReleaseNs = releaseNs
        c.setEarlySchedulingThresholdUs(next((releaseNs - nowNs) / 1000, gapUs, maxUs))
    }

    companion object {
        /** Without a known frame time (the first frame): this much (µs). */
        const val SLACK_US = 8_000L

        /** The slack: 40 % of the frame time (a frame stays on screen at least 60 % of it). */
        fun slackUs(gapUs: Long): Long = if (gapUs in 1..100_000) gapUs * 2 / 5 else SLACK_US

        /** The next frame's threshold: this frame's earliness plus the slack, at most [maxUs]. */
        fun next(earlyUs: Long, gapUs: Long, maxUs: Long): Long = minOf(maxUs, maxOf(0L, earlyUs) + slackUs(gapUs))
    }
}

/**
 * How early (µs) a layer player hands a frame to its TextureView at most (Media3's default: 50 000). The edit point layer
 * starts from its own frame at the cut: 35 ms put it within 2 ms of the main player (measured against the frame line).
 * An upper layer follows the main player's reported position: it was 0–2 frames behind the main picture (V1 against V2
 * read from the same screen frames) – it hands its frames out earlier.
 */
private const val HANDOFF_EARLY_US = 35_000L
private const val LAYER_EARLY_US = 65_000L
/** A layer player waiting for its next segment stands at most this far (ms) into it. */
private const val PREPARED_MS = 20L
/** Main chain frames of the new clip before the edit point layer stands down, and the wait after that (ms). */
private const val HANDOFF_FRAMES = 4L
private const val HANDOFF_SETTLE_MS = 100L
/** A layer whose segment began less than this long ago (s) is still getting going. */
private const val HANDOFF_STARTING_S = 0.3
/**
 * The edit point layer starts playing this long (s) before the edit point, unseen, from the material before the new
 * clip's start – a player started from paused on the frame itself showed its next frames one or two late. Only where
 * no other layer plays then (a dissolve already has two pictures going at that point) and where the file has material.
 */
private const val HANDOFF_PREROLL_S = 0.25
/**
 * The edit point layer's items run this much (s) past their segment: a player that reached an item's end went on to the
 * next one while the layer still showed, and one frame of the next edit point's clip flashed.
 */
private const val HANDOFF_ITEM_TAIL_S = 0.5

private val TRACE = se.gendut.klipp.engine.CutTrace

/**
 * An upper layer's items run this much (s) past their segment, hidden (the layer isn't shown after the segment's end),
 * as the export's upper layers do (UPPER_TAIL): an item ending exactly at the segment's end lost its last frame – paused
 * there, the layer showed the frame before (measured on the vivo with frame-coded clips).
 */
private const val UPPER_ITEM_TAIL_S = 0.2

/** Turned off for the session when the main player was late during a pre-roll (see HANDOFF_PREROLL_S). */
internal object Preroll { @Volatile var off = false }
/** Playback has run this long (ms) before late frames during a pre-roll count against it. */
private const val PREROLL_ARMED_MS = 1000L

/** Source milliseconds of pre-roll for the edit point layer's segment [k] (see [HANDOFF_PREROLL_S]). */
private fun handoffPreroll(segs: List<Seg>, k: Int, plan: LayerPlan): Long {
    val sg = segs[k]
    val m = sg.src as? SegSrc.Media ?: return 0L
    if (m.speed <= 0.0) return 0L
    val busy = plan.layers.dropLast(1).any { l -> l.segs.any { it.end > sg.start - HANDOFF_PREROLL_S && it.start < sg.start + 0.05 } }
    if (busy) return 0L
    // not into the previous segment of this player (it has to stop and seek between them), not before the timeline's start
    val prevGap = segs.getOrNull(k - 1)?.let { sg.start - it.end - 0.05 } ?: Double.MAX_VALUE
    val sec = minOf(HANDOFF_PREROLL_S, prevGap, sg.start).coerceAtLeast(0.0)
    return minOf((sec * m.speed * 1000).toLong(), (m.srcIn * 1000).toLong()).coerceAtLeast(0L)
}

/** The edit point layer only takes over when the playhead reaches its segment's start, not this much (s) later. */
private const val HANDOFF_LATE_S = 0.15
/**
 * After the main player's film runs again (see Occlusion.hideMain): graded frames through its film before the edit point
 * layer stands down, and the wait after that (ms) – the frames without film still queued for display are shown under
 * the layer meanwhile (from the film pass to the screen took at most 65 ms on the OnePlus 12, 39 ms on average).
 */
private const val HANDOFF_FILMED = 2L
private const val HANDOFF_GRADED_MS = 150L
/** Paused during the handover: the longest the layer waits for the main player's graded frame (ms). */
private const val HANDOFF_HOLD_MS = 1000L
/** No late frame in the video chain for this long (ms) before the edit point layer stands down. */
private const val HANDOFF_CALM_MS = 250L

/** Segment [sg]'s box at [t] if it is an opaque rectangle there (see Occlusion.opaque); null otherwise. */
private fun coverBox(sg: Seg, t: Double, plan: LayerPlan, video: VideoSize, gradeOf: (Long) -> se.gendut.klipp.engine.ClipGrade?): se.gendut.klipp.engine.Occlusion.Box? {
    // (a size not reported yet: the layer is drawn in the frame's shape, as LayerVideo's box does)
    val vw = if (video.width > 0) video.width * video.pixelWidthHeightRatio.toDouble() else plan.width.toDouble()
    val vh = if (video.height > 0) video.height.toDouble() else plan.height.toDouble()
    val c = sg.clip?.at(t)
    val lfx = sg.clip?.let { gradeOf(it.id)?.layer }
    if (!se.gendut.klipp.engine.Occlusion.opaque(c?.rotation ?: 0.0, sg.alpha(t),
            plainBlend = lfx == null || lfx.blend == se.gendut.klipp.engine.BlendMode.NORMAL, noAlphaFx = lfx?.hasAlpha != true,
            geomIdentity = sg.geomAt(t)?.identity ?: true)) return null
    return se.gendut.klipp.engine.Occlusion.box(plan.width.toDouble(), plan.height.toDouble(), vw, vh,
        (c?.scale ?: 100.0) / 100.0, (c?.x ?: plan.width / 2.0) - plan.width / 2.0, (c?.y ?: plan.height / 2.0) - plan.height / 2.0, 0.0)
}

/** Where segment [bs] (a video of display [size]) is drawn at [t] – its bounding box; null during a transition's geometry. */
private fun clipBox(bs: Seg, t: Double, plan: LayerPlan, size: Pair<Int, Int>): se.gendut.klipp.engine.Occlusion.Box? {
    if (bs.geomAt(t)?.identity == false) return null
    val c = bs.clip?.at(t)
    return se.gendut.klipp.engine.Occlusion.box(plan.width.toDouble(), plan.height.toDouble(), size.first.toDouble(), size.second.toDouble(),
        (c?.scale ?: 100.0) / 100.0, (c?.x ?: plan.width / 2.0) - plan.width / 2.0, (c?.y ?: plan.height / 2.0) - plan.height / 2.0, c?.rotation ?: 0.0)
}

/** A clip's blend mode as Compose's (null = normal). */
private fun composeBlend(b: se.gendut.klipp.engine.BlendMode): androidx.compose.ui.graphics.BlendMode? = when (b) {
    se.gendut.klipp.engine.BlendMode.NORMAL -> null
    se.gendut.klipp.engine.BlendMode.MULTIPLY -> androidx.compose.ui.graphics.BlendMode.Multiply
    se.gendut.klipp.engine.BlendMode.SCREEN -> androidx.compose.ui.graphics.BlendMode.Screen
    se.gendut.klipp.engine.BlendMode.OVERLAY -> androidx.compose.ui.graphics.BlendMode.Overlay
    se.gendut.klipp.engine.BlendMode.SOFT_LIGHT -> androidx.compose.ui.graphics.BlendMode.Softlight
    se.gendut.klipp.engine.BlendMode.ADD -> androidx.compose.ui.graphics.BlendMode.Plus
    se.gendut.klipp.engine.BlendMode.DARKEN -> androidx.compose.ui.graphics.BlendMode.Darken
    se.gendut.klipp.engine.BlendMode.LIGHTEN -> androidx.compose.ui.graphics.BlendMode.Lighten
    se.gendut.klipp.engine.BlendMode.DIFFERENCE -> androidx.compose.ui.graphics.BlendMode.Difference
}

/** Titles and captions drawn by the app, in step with the playhead. */
@Composable
private fun OverlayText(plan: LayerPlan, state: EditorState) {
    val hw = plan.width / 2
    val hh = plan.height / 2
    Canvas(Modifier.fillMaxSize()) {
        // read in the draw phase: during playback only the drawing is redone, nothing is recomposed every frame
        val t = state.playhead.toDouble()
        // titles that follow a tracked object are redrawn when the tracking or the link changes
        se.gendut.klipp.engine.TitleFollow.revision
        for (captions in OVERLAY_KINDS) {
            // the playhead is a Float: a frame's time can come out a hair before the title's start – the same margin as the clips.
            // Titles: every one that shows (two during a dissolve), in start order; captions: one
            val shown = plan.overlays.filter { (it.overlay is Overlay.Caption) == captions && it.shows(t, 5e-4) }
            for (item in if (captions) listOfNotNull(shown.lastOrNull()) else shown) {
                val half = when (val o = item.overlay) {
                    is Overlay.Title -> Overlay.Title(o.t.copy(size = o.t.size / 2, x = o.t.x?.div(2), y = o.t.y?.div(2)))
                    is Overlay.Caption -> o
                }
                val img = OverlayBitmaps.get(half, hw, hh)
                // pinned: drawn at the preview's half size, as the other titles (the export draws at full size)
                val pin = se.gendut.klipp.engine.pinArtOf(item.copy(overlay = half), t, hw, hh)
                if (pin != null) drawPinned(pin.first, pin.second, hw, placementOf(item, t, plan.width, plan.height).alpha)
                else drawPlaced(img, placementOf(item, t, plan.width, plan.height), hw, hh)
            }
        }
    }
}

private val OVERLAY_KINDS = listOf(false, true)

/**
 * Titles and captions at half resolution, per content and size – kept between edits (previously all were
 * redrawn after every change in the timeline, even when the title wasn't touched).
 */
private object OverlayBitmaps {
    private val cache = object : LinkedHashMap<Triple<Overlay, Int, Int>, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Overlay, Int, Int>, ImageBitmap>?) = size > 12
    }
    fun get(o: Overlay, w: Int, h: Int): ImageBitmap {
        val k = Triple(o, w, h)
        return cache[k] ?: OverlayRenderer.draw(o, w, h).asImageBitmap().also { cache[k] = it }
    }
}

/**
 * Hiccup-free edit points: Media3's CompositionPlayer can't pre-warm the next clip's decoder (ExoPlayer can,
 * but not the composition player), so at an edit point a new decoder starts while the image freezes or goes
 * black for a moment – worst the first time and with heavy originals. As in other editors (A/B roll, a pre-warmed
 * second decoder), the start of the next clip is therefore already decoded and paused on its first frame in a
 * separate player, which takes over the image for the first [SEC] seconds after the switch while the main player
 * gets going underneath. Preview only – export is not affected. Since the main player keeps one video renderer across
 * the items (LiveCuts) a cut from clip to clip is on time there: the layer is left for a clip after an empty stretch or a
 * freeze frame (see [Handoff.layer]).
 */
/** Whether the edit point layer's frame covers the edit point (pure logic, testable). */
internal object EditPointFrame {
    /**
     * A frame beginning at [shownUs] (item time) and lasting [stepUs] is on screen at the edit point [cutUs] once it begins
     * at it or ends after it (half a millisecond of rounding either way). Without a known step: from the cut on.
     */
    fun covers(shownUs: Long, stepUs: Long?, cutUs: Long): Boolean =
        shownUs >= cutUs - 1000 || (stepUs != null && shownUs + stepUs > cutUs + 500)
}

internal object Handoff {
    const val SEC = 1.0

    /**
     * The layer with segments [edit, edit + SEC] from the main layer [base]; null if there are no edit points. With
     * [oneRenderer] (the main player's one video renderer across the items, see LiveCuts) a cut straight from a clip or a
     * pre-rendered piece is on time in the main player – the layer, a second 4K decoder and grade, only cost the vivo
     * frames there (82–166 ms holds); it covers only a clip after a freeze frame or an empty stretch that the image renderer
     * plays (the video renderer starts again after them) – not one played as video ([gapPlayed]).
     */
    fun layer(
        base: Layer, oneRenderer: Boolean = false,
        /** With [oneRenderer]: whether the empty stretch between [prev] (null: the timeline's start) and [next] is played as video (LiveCuts.gapAsVideo) – then the cut after it is on time too. */
        gapPlayed: (prev: Seg?, next: Seg) -> Boolean = { _, _ -> false },
        /** With [oneRenderer]: whether a freeze frame is played as video (HoldVideos) – then the cut after it is on time too. */
        stillPlayed: (Seg) -> Boolean = { false },
        usable: (Seg) -> Boolean,
    ): Layer? {
        val out = ArrayList<Seg>()
        for ((i, s) in base.segs.withIndex()) {
            val prev = base.segs.getOrNull(i - 1)
            if (oneRenderer && prev != null && (prev.src is SegSrc.Media || (prev.src is SegSrc.Still && stillPlayed(prev))) &&
                kotlin.math.abs(prev.end - s.start) < 1e-3) continue
            if (oneRenderer && s.start > (prev?.end ?: 0.0) + 1e-3 && gapPlayed(prev, s)) continue
            // the start of the timeline (no edit point), fades (the opacity or tint follows the fade in the main player) and
            // transparent clips (would be doubled on top of the main player's image) are skipped. Transitions between two
            // clips never reach here: playback shows them pre-rendered, as a file of their own (see RenderCache.playPlan)
            if (s.start <= 1e-6 || s.ramp != null || s.hasFx || s.src !is SegSrc.Media || s.dur <= 1e-6) continue
            // a pre-rendered piece: the layer's hand-back waits on the main player's frames of a clip, so it covered the
            // whole piece and the main picture showed through stale when it ended (a whip at 25 %: the frame before the
            // whip for 150 ms after it)
            if (se.gendut.klipp.engine.RenderCache.isChunk(s.src.item)) continue
            val c = s.clip
            if (c != null && ((c.at(s.start).opacity) < 99.95 || (c.at(minOf(s.end, s.start + SEC)).opacity) < 99.95)) continue
            if (!usable(s)) continue
            out += Seg(s.start, minOf(SEC, s.dur), s.src, s.clip, null)
        }
        return if (out.isEmpty()) null else Layer("klippbyten", out)
    }
}

/**
 * A layer whose segments follow each other closely is played by two players taking turns (every other segment): the
 * idle one waits paused on its next segment's first frame. With one player the switch happened while playing – the
 * player was still in the previous segment when the timeline reached the next, so it sought there and decoded from
 * the nearest keyframe; a dissolve lost about half its frames at the edit point. Preview only.
 */
internal object Alternate {
    /** Segments closer than this (s) get the other player. */
    const val GAP = 1.0

    fun split(layer: Layer): List<Layer> {
        val s = layer.segs
        if ((1 until s.size).none { s[it].start - s[it - 1].end < GAP }) return listOf(layer)
        return listOf(layer.copy(segs = s.filterIndexed { i, _ -> i % 2 == 0 }), Layer(layer.name + " B", s.filterIndexed { i, _ -> i % 2 == 1 }))
    }
}

/** How a layer's player is kept in step with the timeline (pure logic, testable). No time stretch. */
object LayerSync {
    data class Decision(val seekMs: Long?)

    /**
     * Where in the layer's item (ms) timeline time [t] is, for segment [seg] whose item begins [preMs] before it (the
     * pre-roll): also before the segment's start (then less than [preMs]). Inside the segment it equals
     * (Seg.sourceAt(t) − srcIn) · 1000 + preMs.
     */
    fun targetMs(t: Double, seg: Seg, preMs: Long): Long {
        val m = seg.src as? SegSrc.Media ?: return 0L
        return ((t - seg.start) * m.speed * 1000).roundToLong() + preMs
    }

    /**
     * Just before the frame at timeline time [frameUs] (as LayerClock gives it: with the read-ahead) in segment [seg]'s
     * item (ms; the item begins [preMs] before the segment): a seek there shows that frame first.
     */
    fun frameMs(frameUs: Long, seg: Seg, preMs: Long): Long {
        val sp = (seg.src as? SegSrc.Media)?.speed?.coerceAtLeast(1e-6) ?: return 0L
        return (Math.floor(((frameUs / 1e6 - se.gendut.klipp.engine.FrameGrid.LEAD_US / 1e6 / sp) - seg.start) * sp * 1000).toLong() + preMs - 1).coerceAtLeast(0)
    }

    /**
     * Playback after a paused pick starts the layer from the frame shown ([frameUs]) for slot [slotUs] – unless it lies
     * further from the slot than half a seek's threshold (a long-held frame: the layer would start that far behind; it
     * starts from the playhead then).
     */
    fun startsFromShown(frameUs: Long, slotUs: Long): Boolean = abs(slotUs - frameUs) < SEEK_MS * 1000L / 2

    /** Above this much during playback it counts as a hiccup and is corrected with a seek (ms). */
    const val SEEK_MS = 200
    /** A seek during playback takes roughly this long – seek this far ahead (ms). */
    const val SEEK_LEAD_MS = 120

    /**
     * @param running both the main player and the layer are playing
     */
    fun decide(curIndex: Int, curMs: Long, wantIndex: Int, wantMs: Long, running: Boolean, ready: Boolean, msSinceSeek: Long): Decision {
        if (curIndex != wantIndex) return Decision(if (!running || msSinceSeek > 250) wantMs + if (running) SEEK_LEAD_MS else 0 else null)
        val drift = curMs - wantMs // + = the layer is ahead
        // still (or about to start): exact frame
        if (!running) return Decision(if (abs(drift) > 15 && (ready || msSinceSeek > 300)) wantMs else null)
        if (!ready) return Decision(null)
        if (abs(drift) > SEEK_MS && msSinceSeek > 1000) return Decision(wantMs + SEEK_LEAD_MS)
        return Decision(null)
    }

    /**
     * The layer's frames shown on the same screen refresh as the main player's for the same slot. The main player's clock
     * runs ahead of the wall clock for its first 300–400 ms (measured on the OnePlus: 20–40 ms gained, then steady), the
     * layer's (no audio) doesn't: it began in step and stayed that far behind – its frames shown 22–33 ms after the main
     * player's for the same slot, all along (below a seek's threshold). Measured where it shows: each player's newest
     * frame, the time it is shown at less its timeline time (constant while a player keeps its pace – frames held or
     * dropped, or a stall at a cut, don't change it). The players' reported positions don't tell it: the main player's
     * lies 13 ms before to 4 ms after its pictures, differently from run to run. The layer plays a little faster or slower
     * (it has no sound) until the median of the last [WINDOW] samples is within half a screen refresh – shown times are
     * whole refreshes, it can't be told closer.
     */
    class Rate {
        private val d = DoubleArray(WINDOW)
        private var n = 0
        private var k = 0
        /** The factor on the segment's speed. */
        var factor = 1.0
            private set
        /** The latest median (ms, + = the layer's frames shown late), for diagnostics. */
        var lateMs = 0.0
            private set

        fun reset() { n = 0; k = 0; factor = 1.0 }

        /** Starts [lateMs] behind (known before any frame shows it: started from a frame before the playhead). */
        fun start(lateMs: Double) { reset(); factor = (1.0 + lateMs / TAU_MS).coerceIn(1.0 - MAX, 1.0 + MAX) }

        /** One sample: how much later (ms) the layer shows a slot than the main player does; [refreshMs] = the screen's. */
        fun sample(late: Double, refreshMs: Double): Double {
            d[k] = late; k = (k + 1) % WINDOW; if (n < WINDOW) n++
            if (n < MIN_SAMPLES) return factor
            val med = d.copyOf(n).sorted()[n / 2]
            lateMs = med
            factor = if (abs(med) <= refreshMs / 2) 1.0 else (1.0 + med / TAU_MS).coerceIn(1.0 - MAX, 1.0 + MAX)
            return factor
        }

        companion object {
            /** Samples (one per display frame) in the median … */
            const val WINDOW = 15
            /** … from this many on. */
            const val MIN_SAMPLES = 5
            /** A difference is taken back over about this long (ms) … */
            const val TAU_MS = 300.0
            /** … at most this much faster or slower (the main player's clock runs about this much fast through its start). */
            const val MAX = 0.10
        }
    }
}

/** Measured drift per layer during playback (shown in the diagnostics view). */
object SyncStats {
    class Layer(val name: String) {
        @Volatile var drift = 0L
        @Volatile var maxDrift = 0L
        @Volatile var samples = 0L
        @Volatile var sumAbs = 0L
        @Volatile var seeks = 0
        /**
         * New frames on the layer's view while it was seen during this run, and the longest wait for one (ms). (Not the
         * decoder's counts: with effects, frames the chain didn't take in time never show up as dropped there.)
         */
        @Volatile var frames = 0L
        @Volatile var maxGap = 0L
        private var lastTs = 0L
        private var lastAt = 0L
        /** The layer becomes visible (a segment starts): waits are counted from here. */
        fun visible(ts: Long, now: Long) { lastTs = ts; lastAt = now }
        /** Each display frame while visible: [ts] = the view's latest frame time. */
        fun frame(ts: Long, now: Long) {
            if (ts == lastTs) return
            frames++; if (lastAt > 0) maxGap = maxOf(maxGap, now - lastAt)
            lastTs = ts; lastAt = now
        }
        fun record(d: Long) {
            drift = d; samples++; sumAbs += abs(d)
            if (abs(d) > abs(maxDrift)) maxDrift = d
        }
    }
    private val layers = java.util.concurrent.ConcurrentHashMap<String, Layer>()
    fun forLayer(name: String): Layer = layers.getOrPut(name) { Layer(name) }
    fun reset() = layers.clear()

    /** When the edit point layer covered the picture during this run (uptime ms; end 0 = still covering). */
    private val covered = java.util.concurrent.ConcurrentLinkedDeque<LongArray>()
    fun handoff(on: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        val last = covered.peekLast()
        if (on && (last == null || last[1] != 0L)) covered.addLast(longArrayOf(now, 0L))
        else if (!on && last != null && last[1] == 0L) last[1] = now
        while (covered.size > 64) covered.pollFirst()
    }

    private var markShown = 0L
    private var markLate = 0L
    private var markAt = 0L
    private var markSkipped = 0L
    private var markUptime = 0L

    /** Playback starts: the counters now (see [summary]). Main thread. */
    fun start() {
        for (l in layers.values) { l.frames = 0; l.maxGap = 0 }
        markShown = androidx.media3.effect.KlippPrecision.framesShown; markLate = androidx.media3.effect.KlippPrecision.framesLate
        markAt = System.nanoTime()
        markSkipped = se.gendut.klipp.engine.Occlusion.skipped
        markUptime = android.os.SystemClock.uptimeMillis()
        covered.clear()
    }

    /**
     * Playback stopped: what happened since [start] – the video chains' frames out and too late (the main player and
     * the layers' effect chains), and per layer the frames it showed and its longest wait for one (at 25 fps on a
     * 60–120 Hz screen up to about 55 ms is the normal rhythm). Null for very short runs.
     */
    fun summary(): String? {
        val sec = (System.nanoTime() - markAt) / 1e9
        if (markAt == 0L || sec < 1.5) return null
        val shown = androidx.media3.effect.KlippPrecision.framesShown - markShown
        val late = androidx.media3.effect.KlippPrecision.framesLate - markLate
        val per = layers.entries.sortedBy { it.key }.mapNotNull { (k, l) ->
            if (l.frames == 0L) null else "$k ${l.frames} frames, longest wait ${l.maxGap} ms"
        }
        val skipped = se.gendut.klipp.engine.Occlusion.skipped - markSkipped
        // late frames the edit point layer didn't cover: these were seen as a stutter
        val spans = covered.toList()
        val seen = androidx.media3.effect.KlippPrecision.lateTimes.count { at -> at >= markUptime && spans.none { at >= it[0] && (it[1] == 0L || at <= it[1]) } }
        return "played %.1f s: chain %.1f fps, %d late (%d not covered)".format(sec, shown / sec, late, seen) + (if (skipped > 0) ", film skipped under a layer ×$skipped" else "") + if (per.isEmpty()) "" else "; " + per.joinToString("; ")
    }
    fun describe(): String = buildString {
        for (l in layers.values) {
            val avg = if (l.samples > 0) l.sumAbs / l.samples else 0
            append("${l.name}: drift ${l.drift} ms (avg ±$avg, max ${l.maxDrift}), corrections ${l.seeks}\n")
        }
    }
}

/**
 * If the layer's player is standing still: decode the frame again so changed effect values show. A seek to exactly
 * the same position is ignored, hence 1 ms ahead (same frame); a short pause so an effect change has arrived.
 */
private suspend fun repaint(player: ExoPlayer) {
    kotlinx.coroutines.delay(100)
    if (!player.playWhenReady && player.playbackState != Player.STATE_IDLE) {
        val pos = player.currentPosition
        player.seekTo(se.gendut.klipp.engine.Repaint.next(player, pos))
    }
}


/**
 * The layer player's frame time → source time (seconds in the clip's file), for tracked windows. The player's
 * timestamps count either in the file's time or from the segment's start (the clipping); the interpretation closest
 * to the player's last known position is chosen.
 */
/** Where a layer's item for segment [s] begins in its file (ms): the segment's source time less its pre-roll [preMs]. */
private fun clipStartMs(s: Seg, preMs: Long): Long {
    val m = s.src as? SegSrc.Media ?: (s.src as SegSrc.Still).let { st -> SegSrc.Media(st.item, st.sec, 0.0) }
    return ((m.srcIn * 1000).roundToLong() - preMs).coerceAtLeast(0L)
}

/** The last frames a layer player released: their presentation time (µs, the item's) by release time (ns). */
internal class ReleasedFrames(private val ramp: EarlyRamp? = null, private val kind: String = "released") : androidx.media3.exoplayer.video.VideoFrameMetadataListener {
    private val pts = LongArray(16)
    private val at = LongArray(16)
    @Volatile private var n = 0

    @Synchronized override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: androidx.media3.common.Format, mediaFormat: android.media.MediaFormat?) {
        if (releaseTimeNs == androidx.media3.common.C.TIME_UNSET) return
        val k = n % 16; pts[k] = presentationTimeUs; at[k] = releaseTimeNs; n++
        ramp?.released(releaseTimeNs, System.nanoTime())
        if (TRACE.on) TRACE.log(kind, "pts=$presentationTimeUs at=$releaseTimeNs")
    }

    /** The newest frame released: its presentation time (µs) and the time it is to be shown at (ns); null = none yet. */
    @Synchronized fun latest(): Pair<Long, Long>? = if (n == 0) null else ((n - 1) % 16).let { pts[it] to at[it] }

    /** The time between the last two frames released (µs; null = not known yet). */
    @Synchronized fun stepUs(): Long? {
        if (n < 2) return null
        val d = pts[(n - 1) % 16] - pts[(n - 2) % 16]
        return d.takeIf { it in 1..200_000 }
    }

    /** The presentation time of the frame released for [releaseNs] (a SurfaceTexture's timestamp); null = not among the last ones. */
    @Synchronized fun ptsAt(releaseNs: Long): Long? {
        if (releaseNs == 0L) return null
        // newest first: two frames released for the same refresh – the view holds the later one
        for (k in 1..minOf(n, 16)) { val j = (n - k) % 16; if (at[j] == releaseNs) return pts[j] }
        return null
    }
}

private class LayerClock {
    /** The segment being played (or waited on): where it begins on the timeline, its source time there and its speed. */
    class SegTime(val startSec: Double, val srcSec: Double, val speed: Double, val endSec: Double = Double.MAX_VALUE, val cut: Boolean = false)
    /** One reference, so the chain's thread reads one segment's values together (null: a still, or nothing yet). */
    @Volatile var seg: SegTime? = null

    /** A frame's time on the timeline (µs), from its time in the file; null when no media segment is set. */
    fun timelineUs(pts: Long): Long? = seg?.takeIf { it.speed > 0.0 }?.let { g ->
        // (in the main player's and the export's time base: they read a clip 1 ms of its file early, so each of its frames
        // comes 1 ms / speed after its own timeline time – without it, a frame on a slot's edge fell on another slot here
        // than there: 120 fps at 25 % on 25p, the edit point layer skipped every fifth source frame the export shows)
        Math.round((g.startSec + (fileSec(pts) - g.srcSec) / g.speed) * 1e6 + se.gendut.klipp.engine.FrameGrid.LEAD_US / g.speed)
    }

    @Volatile var srcIn = 0.0
    @Volatile var posSec = 0.0
    /** The file being played (a reversed file: the original's time is computed, see Reversals.originalTime). */
    @Volatile var item = 0L
    /**
     * Where (µs, in the file) the player's renderer began: a frame's time + this = its time in the file (set at each fresh
     * start, see seekLayer; MIN_VALUE = not known).
     */
    @Volatile var baseUs = Long.MIN_VALUE

    /**
     * A frame's time in the file (s). From [baseUs]; if that doesn't fit the position (more than [SLACK_S] away – e.g. the
     * player went on into the next item by itself), the timestamp read as the item's own time or the file's, whichever
     * lies nearer the position.
     */
    fun fileSec(pts: Long): Double {
        val now = srcIn + posSec
        val b = baseUs
        if (b != Long.MIN_VALUE) { val f = (pts + b) / 1e6; if (abs(f - now) <= SLACK_S) return f }
        val p = pts / 1e6
        return if (abs(p - now) <= abs(srcIn + p - now)) p else srcIn + p
    }

    /** A frame's time in the current item (µs from [clipStartMs]). */
    fun itemUs(pts: Long, clipStartMs: Long): Long = Math.round(fileSec(pts) * 1e6) - clipStartMs * 1000

    fun source(pts: Long): Double = fileSec(pts).also { if (TRACE.on) TRACE.log("clock", "pts=$pts file=${"%.4f".format(java.util.Locale.ROOT, it)} base=$baseUs pos=$posSec") }
        .let { se.gendut.klipp.engine.Reversals.originalTime(item, it) }

    companion object { const val SLACK_S = 1.5 }
}


/**
 * The transition's mask in the layer's box (the video's box, centered in the sequence frame [frameW]×[frameH]): the
 * visible area, in sequence coordinates (left of / right of a line, inside / outside a circle).
 */
private class TransitionMaskShape(private val m: se.gendut.klipp.engine.Mask, private val frameW: Float, private val frameH: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        // the sequence frame in the box's coordinates (the box can be narrower/shorter than the frame)
        val ox = (size.width - frameW) / 2f; val oy = (size.height - frameH) / 2f
        val all = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
        val p = androidx.compose.ui.graphics.Path()
        when (m.kind) {
            se.gendut.klipp.engine.Mask.LEFT_OF -> p.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, (ox + m.v * frameW).coerceIn(0f, size.width), size.height))
            se.gendut.klipp.engine.Mask.RIGHT_OF -> p.addRect(androidx.compose.ui.geometry.Rect((ox + m.v * frameW).coerceIn(0f, size.width), 0f, size.width, size.height))
            se.gendut.klipp.engine.Mask.CIRCLE_IN, se.gendut.klipp.engine.Mask.CIRCLE_OUT -> {
                val r = m.v * kotlin.math.hypot(frameW / 2f, frameH / 2f)
                val circle = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(ox + frameW / 2f, oy + frameH / 2f), r)) }
                if (m.kind == se.gendut.klipp.engine.Mask.CIRCLE_IN) p.addPath(circle)
                else p.op(androidx.compose.ui.graphics.Path().apply { addRect(all) }, circle, androidx.compose.ui.graphics.PathOperation.Difference)
            }
            else -> p.addRect(all)
        }
        return androidx.compose.ui.graphics.Outline.Generic(p)
    }
}

/** The transition's motion blur on the player's picture, the same samples as the export's TransitionFxEffect (Android 13+). */
private const val TRANSITION_BLUR = """
uniform shader content;
uniform float2 uSize;
uniform float uBlurX;
uniform float uBlurZoom;
uniform float uDx;
uniform float uMirror;
uniform float uSoft;
float2 tap(float2 p) {
  if (uMirror > 0.5) { float2 n = p / uSize; return (abs(mod(n + 1.0, 2.0) - 1.0)) * (uSize - float2(1.0)) + float2(0.5); }
  return clamp(p, float2(0.5), uSize - float2(0.5));
}
half4 main(float2 p0) {
  float2 p = p0 - float2(uDx * uSize.x, 0.0);
  half4 sum = half4(0.0);
  // each pixel's taps start a little apart (as in the export): a soft streak instead of 32 faint copies
  float j = fract(52.9829189 * fract(dot(p0, float2(0.06711056, 0.00583715))));
  for (int i = 0; i < 32; i++) {
    float f = (float(i) + j) / 32.0 - 0.5;
    sum += content.eval(tap(p + float2(uBlurX * uSize.x * f, 0.0) + (p - uSize * 0.5) * uBlurZoom * f));
  }
  sum /= 32.0;
  if (uSoft > 0.0 && uDx != 0.0) {
    float x = p.x / uSize.x;
    sum *= smoothstep(-uSoft * 0.5, uSoft * 0.5, uDx < 0.0 ? 1.0 - x : x);
  }
  return sum;
}
"""

@androidx.annotation.RequiresApi(33)
private fun transitionBlur(shader: android.graphics.RuntimeShader, w: Float, h: Float, blurX: Float, blurZoom: Float, dx: Float, mirror: Boolean, soft: Float): androidx.compose.ui.graphics.RenderEffect {
    shader.setFloatUniform("uSize", w, h)
    shader.setFloatUniform("uBlurX", blurX)
    shader.setFloatUniform("uBlurZoom", blurZoom)
    shader.setFloatUniform("uDx", dx)
    shader.setFloatUniform("uMirror", if (mirror) 1f else 0f)
    shader.setFloatUniform("uSoft", soft)
    return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}
