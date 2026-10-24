package se.gendut.klipp

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
) {
    val snap = controller.snapshot
    // the same plan as the main player's (CompositionBuilder): same lengths and same layers – layers without a playable
    // file don't count, otherwise the two could disagree on which layer is the bottom (and a layer would be decoded twice)
    // (reversed clips play from their reversed files once those are ready – then a new plan)
    // pre-rendered chunks are swapped in as in the main player (there the layers draw nothing – the file already has it all)
    val plan = remember(controller.revision, snap, se.gendut.klipp.engine.Reversals.revision, se.gendut.klipp.engine.RenderCache.revision, gradesKey) {
        runCatching {
            se.gendut.klipp.engine.CompositionBuilder.previewPlan(snap, controller::uriOf)?.let { p ->
                se.gendut.klipp.engine.RenderCache.substitute(p, se.gendut.klipp.engine.RenderCache.chunks(p, gradeOf, controller::uriOf, snap.fps, se.gendut.klipp.engine.VideoPipeline.previewShort))
            }
        }.getOrNull()
    } ?: return
    Box(modifier.fillMaxSize().testTag("upperLayers")) {
        // the bottom layer is played by the main player; the others from the bottom up
        val uppers = plan.layers.dropLast(1).reversed()
        // edit points in the main player's layer: the next clip is already decoded in its own player and covers
        // the switch (see Handoff) – directly above the main player, below the other layers
        val handoff = remember(plan, se.gendut.klipp.engine.Proxies.revision) {
            plan.layers.lastOrNull()?.let { base ->
                Handoff.layer(base) { s ->
                    val u = controller.uriOf(s.src.item) ?: return@layer false
                    // HDR originals without a proxy are tone mapped differently in the two players – no handoff there
                    !(se.gendut.klipp.engine.Proxies.videoUri(u) == u && se.gendut.klipp.engine.Perf.cachedHdr(u) == true)
                }
            }
        }
        if (handoff != null) key("klippbyten") { LayerVideo(handoff, plan, controller, state, main, gradeOf, look, handoff = true) }
        // back-to-back segments (a dissolve's two halves, clips next to each other in an upper track): two players
        // taking turns, see Alternate
        for (layer in uppers) for (part in Alternate.split(layer)) key(part.name) { LayerVideo(part, plan, controller, state, main, gradeOf, look, planName = layer.name) }
        if (plan.overlays.isNotEmpty()) OverlayText(plan, state)
    }
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
    val player = remember {
        ExoPlayer.Builder(ctx).setLoadControl(se.gendut.klipp.engine.Perf.editingLoadControl()).build().apply {
            volume = 0f // audio comes from the main player
            // the audio track isn't decoded at all in the layer players (saves power, and speed adjustments come for free)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
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
            override fun onRenderedFirstFrame() { drawnIndex = player.currentMediaItemIndex }
            // if it plays, it draws (safety net in case a first-frame event never arrives)
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) drawnIndex = player.currentMediaItemIndex }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // played on into the next segment: its frame follows right away (pre-buffered); a seek waits for its frame
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) drawnIndex = player.currentMediaItemIndex else drawnIndex = -1
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // the image from the proxy when there is one (swapped when a proxy is ready)
    val proxyRev = se.gendut.klipp.engine.Proxies.revision
    LaunchedEffect(segs, proxyRev, preMs) {
        player.setMediaItems(segs.mapIndexed { k, seg ->
            // freeze frame: just the frame (the player stays paused on it for the whole segment, see below)
            val s = seg.src as? SegSrc.Media ?: (seg.src as SegSrc.Still).let { st -> SegSrc.Media(st.item, st.sec, 0.0) }
            MediaItem.Builder().setUri(controller.uriOf(s.item)?.let { se.gendut.klipp.engine.Proxies.videoUri(it) })
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        // start ≥ 0 and end after start (camera files can have a negative start time, see Playback.clipped)
                        .setStartPositionMs(((s.srcIn * 1000).roundToLong() - preMs[k]).coerceAtLeast(0L))
                        .setEndPositionMs(((s.srcIn + (if (s.speed == 0.0) 0.1 else seg.dur * kotlin.math.abs(s.speed))) * 1000).roundToLong().coerceAtLeast((s.srcIn * 1000).roundToLong().coerceAtLeast(0L) + 1))
                        .build()
                ).build()
        })
        drawnIndex = -1
        player.prepare()
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
    val clock = remember { LayerClock() }
    // Follow the main player. Both play at normal speed from the same starting point, so they don't drift apart
    // (no time stretch). The layer only starts once the main player's position actually begins to advance –
    // otherwise it ends up ahead by the main player's startup delay. The drift is measured continuously (diagnostics view)
    // and only a real hiccup (> 200 ms, e.g. a decoder that got stuck) is corrected with a seek.
    // proxyRev: when a proxy is swapped in the segments are reloaded (the player then starts from the beginning) – sync
    // restarts and seeks to the playhead right away, even when it is standing still
    LaunchedEffect(segs, main, proxyRev, preMs) {
        var lastSeek = 0L
        var lastMainMs = -1L
        val stats = SyncStats.forLayer(layer.name)
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
            snapshotFlow { state.playing || state.playhead != ph }.first { it }
            lastMainMs = -1L
        }
        // (marks left by the edit point layer are lifted right away when it stops, not after they lapse)
        try { while (true) {
            val now = withFrameNanos { it } / 1_000_000
            val mainMs = main?.currentPosition
            val advancing = mainMs != null && lastMainMs >= 0 && mainMs != lastMainMs && main.isPlaying
            lastMainMs = mainMs ?: -1L
            val t = if (mainMs != null && state.playing) mainMs / 1000.0 else state.playhead.toDouble()
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
                }
                if (handReadyAt != 0L) {
                    if (handHid.isNotEmpty() && handGradedAt == 0L && filmedOf(handHid) - handFilmBase >= HANDOFF_FILMED) handGradedAt = now
                    val ok = if (handHid.isEmpty()) now - handReadyAt >= HANDOFF_SETTLE_MS else handGradedAt != 0L && now - handGradedAt >= HANDOFF_GRADED_MS
                    // (forced: the layer needed a seek – it goes as soon as the main player's frames are graded)
                    if (ok && (calm || handForced)) handDown = true
                }
                // once stood down it stays down for the rest of the segment: coming back (a late frame after the
                // handover) meant a seek in the paused player – itself a hitch
                if (handDown) i = -1
            }
            // (in a segment's pre-roll the layer plays unseen)
            preRolling = i >= 0 && t < segs[i].start
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
            // the edit point layer covering the whole frame, opaque, while playing: the main player's picture of the same
            // clip isn't seen – its film is skipped until the layer begins to stand down (and not in the last LEAD of the
            // layer's segment), see Occlusion.hideMain
            if (handoff && i >= 0 && !preRolling && handReadyAt == 0L && state.playing && drawnIndex == i && player.isPlaying) {
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
                // prepare the next segment right away (paused on its first frame) – the file is opened and the decoder started
                // in good time, even the first time and with heavy originals (4K, HEVC) that take a while to get going
                val next = segs.indexOfFirst { it.start > t }
                // (also when it is in that segment but not at its start: a layer with a single segment, or one played
                // before, still stood where it stopped – and had to seek from a keyframe as the segment began)
                val sought = next >= 0 && (player.currentMediaItemIndex != next || abs(player.currentPosition - waitMs(next)) > PREPARED_MS)
                if (sought) { player.seekTo(next, waitMs(next)); lastSeek = now }
                if (next >= 0) cue = next
                if (!state.playing && !sought) idle()
                continue
            }
            val seg = segs[i]
            cue = i
            val media = seg.src as? SegSrc.Media
            // freeze frame: stays on the frame (the segment's start) the whole time
            val speed = media?.speed?.toFloat() ?: 1f
            if (abs(player.playbackParameters.speed - speed) > 0.0001f) player.setPlaybackSpeed(speed)
            val target = if (media == null) 0L else ((t - seg.start) * speed * 1000).roundToLong() + preMs[i]
            clock.srcIn = media?.let { it.srcIn - preMs[i] / 1000.0 } ?: (seg.src as SegSrc.Still).sec; clock.posSec = target / 1000.0; clock.item = seg.src.item
            // (once going it keeps going while the main player plays: its reported position often stands still for a few
            // display frames although it plays on – the layer was paused, and often sought, at each of those)
            val run = media != null && state.playing && (main == null || advancing || player.playWhenReady && main.isPlaying)
            val d = LayerSync.decide(
                curIndex = player.currentMediaItemIndex, curMs = player.currentPosition, wantIndex = i, wantMs = target,
                running = run, ready = player.playbackState == Player.STATE_READY, msSinceSeek = now - lastSeek,
            )
            if (d.seekMs != null && handoff && handHid.isNotEmpty() && !handDown) {
                // the edit point layer has the main player's film off: a seek would hide it (until its frame comes) and
                // show the main player's picture without film – it stands down instead, still showing, see above
                if (handReadyAt == 0L) { handReadyAt = now; unhide(); handFilmBase = filmedOf(handHid) }
                handForced = true
            } else d.seekMs?.let { player.seekTo(i, it); lastSeek = now; stats.seeks++ }
            if (player.playWhenReady != run) player.playWhenReady = run
            if (run && player.isPlaying && d.seekMs == null) stats.record(player.currentPosition - target)
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
    val scaleShort = segs.getOrNull(cue)?.let { sg -> controller.uriOf(sg.src.item) }?.let { u ->
        val short = if (se.gendut.klipp.engine.Proxies.videoUri(u) != u) se.gendut.klipp.engine.Proxies.proxyShort(u)
            else se.gendut.klipp.engine.Perf.cachedCodec(u)?.let { k -> if (k.width > 0 && k.height > 0) min(k.width, k.height) else null }
        val pv = se.gendut.klipp.engine.VideoPipeline.previewShort
        if (pv <= 0) null else se.gendut.klipp.engine.CompositionBuilder.previewSize(plan.width, plan.height, pv)
            .let { (pw, ph) -> se.gendut.klipp.engine.CompositionBuilder.previewShortSide(short, pw, ph) }
    }
    LaunchedEffect(gc?.id, structure, look, scaleShort) {
        if (gc != null && grade != null) se.gendut.klipp.engine.LiveGrades.publish(mapOf(gc.id to grade))
        // tracked windows: the frame's source time (see LayerClock)
        // always (live): a window tracked afterwards follows along without the effects being rebuilt
        val src: ((Long) -> Double?)? = if (grade != null) { pts -> clock.source(pts) } else null
        // green screen first (the source's colors), the mask last: the layer below shows through (the player's surface is transparent)
        val layer = grade?.layer?.takeIf { it.hasAlpha }
        val keyFx = gc?.let { cl -> layer?.key?.let { se.gendut.klipp.engine.LayerFxEffect(layer, cl.id, se.gendut.klipp.engine.LayerFxEffect.KEY, se.gendut.klipp.engine.LayerFxEffect.STRAIGHT) } }
        val maskFx = gc?.let { cl -> layer?.let { se.gendut.klipp.engine.LayerFxEffect(it, cl.id, se.gendut.klipp.engine.LayerFxEffect.MASK, se.gendut.klipp.engine.LayerFxEffect.PREMULTIPLIED, src) } }
        val fx = listOfNotNull(keyFx) + gc?.let { grade?.effects(it.id, clock = src) }.orEmpty() + se.gendut.klipp.engine.CompositionBuilder.videoEffects(look) + listOfNotNull(maskFx)
        // (no effects: none – the downscale alone would add a pass the plain picture doesn't need)
        val down = scaleShort?.takeIf { fx.isNotEmpty() }?.let { androidx.media3.effect.Presentation.createForShortSide(it) }
        runCatching { player.setVideoEffects(listOfNotNull(down) + fx) }
        repaint(player)
    }
    LaunchedEffect(grade) {
        if (gc == null || grade == null) return@LaunchedEffect
        se.gendut.klipp.engine.LiveGrades.publish(mapOf(gc.id to grade))
        repaint(player)
    }
    val density = LocalDensity.current
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
                    this.alpha = if (seg == null || drawnIndex != active || preRolling || (handoff && !state.playing && !holding)) 0f else seg.alpha(state.playhead.toDouble())
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
                    // the transition (wipe, push, slide, zoom, iris): same geometry as the export's TransitionFxEffect
                    val g = seg?.geomAt(state.playhead.toDouble())
                    if (g != null && !g.identity) {
                        translationX += g.dx * boxW; translationY += g.dy * boxH
                        scaleX *= g.scale; scaleY *= g.scale
                        val m = g.mask
                        if (m != null) { shape = TransitionMaskShape(m, boxW, boxH); clip = true } else clip = false
                    } else clip = false
                }
        ) {
            // a dedicated non-opaque TextureView: without a frame (yet) the layer below shows, never black
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { c -> android.view.TextureView(c).apply { isOpaque = false; player.setVideoTextureView(this); view = this } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { v -> player.clearVideoTextureView(v) },
            )
        }
    }
}

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
            val item = plan.overlays.lastOrNull { (it.overlay is Overlay.Caption) == captions && t >= it.start && t < it.end } ?: continue
            val half = when (val o = item.overlay) {
                is Overlay.Title -> Overlay.Title(o.t.copy(size = o.t.size / 2, x = o.t.x?.div(2), y = o.t.y?.div(2)))
                is Overlay.Caption -> o
            }
            val img = OverlayBitmaps.get(half, hw, hh)
            drawPlaced(img, placementOf(OverlayItem(item.start, item.end, item.overlay, item.clip, item.fadeIn, item.fadeOut), t, plan.width, plan.height), hw, hh)
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
 * gets going underneath. Preview only – export is not affected.
 */
internal object Handoff {
    const val SEC = 1.0

    /** The layer with segments [edit, edit + SEC] from the main layer [base]; null if there are no edit points. */
    fun layer(base: Layer, usable: (Seg) -> Boolean): Layer? {
        val out = ArrayList<Seg>()
        for (s in base.segs) {
            // the start of the timeline (no edit point), fades (the opacity follows the ramp in the main player) and
            // transparent clips (would be doubled on top of the main player's image) are skipped
            if (s.start <= 1e-6 || s.ramp != null || s.src !is SegSrc.Media || s.dur <= 1e-6) continue
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
    // one millisecond forward or back (alternating): a new frame at the same spot without drifting over time
    if (!player.playWhenReady && player.playbackState != Player.STATE_IDLE) {
        val pos = player.currentPosition
        player.seekTo(if (pos % 2L == 0L) pos + 1 else pos - 1)
    }
}

/**
 * The layer player's frame time → source time (seconds in the clip's file), for tracked windows. The player's
 * timestamps count either in the file's time or from the segment's start (the clipping); the interpretation closest
 * to the player's last known position is chosen.
 */
private class LayerClock {
    @Volatile var srcIn = 0.0
    @Volatile var posSec = 0.0
    /** The file being played (a reversed file: the original's time is computed, see Reversals.originalTime). */
    @Volatile var item = 0L

    fun source(pts: Long): Double {
        val p = pts / 1e6
        val now = srcIn + posSec
        return se.gendut.klipp.engine.Reversals.originalTime(item, if (abs(p - now) <= abs(srcIn + p - now)) p else srcIn + p)
    }
}


/**
 * The transition's mask in the layer's box (the video's box, centered in the sequence frame [frameW]×[frameH]): the
 * visible area, in sequence coordinates like [se.gendut.klipp.engine.SegFx.visibleAt].
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
