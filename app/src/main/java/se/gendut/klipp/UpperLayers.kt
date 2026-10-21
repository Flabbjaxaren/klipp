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
        for (layer in uppers) key(layer.name) { LayerVideo(layer, plan, controller, state, main, gradeOf, look) }
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
    LaunchedEffect(segs, proxyRev) {
        player.setMediaItems(segs.map { seg ->
            // freeze frame: just the frame (the player stays paused on it for the whole segment, see below)
            val s = seg.src as? SegSrc.Media ?: (seg.src as SegSrc.Still).let { st -> SegSrc.Media(st.item, st.sec, 0.0) }
            MediaItem.Builder().setUri(controller.uriOf(s.item)?.let { se.gendut.klipp.engine.Proxies.videoUri(it) })
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        // start ≥ 0 and end after start (camera files can have a negative start time, see Playback.clipped)
                        .setStartPositionMs((s.srcIn * 1000).roundToLong().coerceAtLeast(0L))
                        .setEndPositionMs(((s.srcIn + (if (s.speed == 0.0) 0.1 else seg.dur * kotlin.math.abs(s.speed))) * 1000).roundToLong().coerceAtLeast((s.srcIn * 1000).roundToLong().coerceAtLeast(0L) + 1))
                        .build()
                ).build()
        })
        drawnIndex = -1
        player.prepare()
    }
    var active by remember { mutableIntStateOf(-1) }
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
    LaunchedEffect(segs, main, proxyRev) {
        var lastSeek = 0L
        var lastMainMs = -1L
        val stats = SyncStats.forLayer(layer.name)
        // paused and done seeking: sleep until playback starts or the playhead moves (no needless frame loop)
        suspend fun idle() {
            val ph = state.playhead
            snapshotFlow { state.playing || state.playhead != ph }.first { it }
            lastMainMs = -1L
        }
        while (true) {
            val now = withFrameNanos { it } / 1_000_000
            val mainMs = main?.currentPosition
            val advancing = mainMs != null && lastMainMs >= 0 && mainMs != lastMainMs && main.isPlaying
            lastMainMs = mainMs ?: -1L
            val t = if (mainMs != null && state.playing) mainMs / 1000.0 else state.playhead.toDouble()
            val i = if (handoff && !state.playing) -1 else segs.indexOfFirst { t >= it.start && t < it.end }
            active = i
            if (i < 0) {
                if (player.playWhenReady) player.pause()
                // prepare the next segment right away (paused on its first frame) – the file is opened and the decoder started
                // in good time, even the first time and with heavy originals (4K, HEVC) that take a while to get going
                val next = segs.indexOfFirst { it.start > t }
                val sought = next >= 0 && player.currentMediaItemIndex != next
                if (sought) { player.seekTo(next, 0); lastSeek = now }
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
            val target = if (media == null) 0L else ((t - seg.start) * speed * 1000).roundToLong()
            clock.srcIn = media?.srcIn ?: (seg.src as SegSrc.Still).sec; clock.posSec = target / 1000.0; clock.item = seg.src.item
            val run = media != null && state.playing && (main == null || advancing)
            val d = LayerSync.decide(
                curIndex = player.currentMediaItemIndex, curMs = player.currentPosition, wantIndex = i, wantMs = target,
                running = run, ready = player.playbackState == Player.STATE_READY, msSinceSeek = now - lastSeek,
            )
            d.seekMs?.let { player.seekTo(i, it); lastSeek = now; stats.seeks++ }
            if (player.playWhenReady != run) player.playWhenReady = run
            if (run && player.isPlaying && d.seekMs == null) stats.record(player.currentPosition - target)
            if (!state.playing && d.seekMs == null && player.playbackState != Player.STATE_BUFFERING) idle()
        }
    }
    val seg = segs.getOrNull(active)
    val c = seg?.clip
    // the clip's grade and the timeline look in the layer's player (the active or prepared segment, see cue)
    val gc = segs.getOrNull(cue)?.clip
    val grade = gc?.let { gradeOf(it.id) }
    // the effects are only replaced when the nodes (or the look) change – the values are read live from LiveGrades
    // (same key as the main player: nodes, tracking and stabilization)
    val structure = grade?.let { g -> se.gendut.klipp.engine.LiveGrades.structureOf(g) }
    LaunchedEffect(gc?.id, structure, look) {
        if (gc != null && grade != null) se.gendut.klipp.engine.LiveGrades.publish(mapOf(gc.id to grade))
        // tracked windows: the frame's source time (see LayerClock)
        // always (live): a window tracked afterwards follows along without the effects being rebuilt
        val src: ((Long) -> Double?)? = if (grade != null) { pts -> clock.source(pts) } else null
        // green screen first (the source's colors), the mask last: the layer below shows through (the player's surface is transparent)
        val layer = grade?.layer?.takeIf { it.hasAlpha }
        val keyFx = gc?.let { cl -> layer?.key?.let { se.gendut.klipp.engine.LayerFxEffect(layer, cl.id, se.gendut.klipp.engine.LayerFxEffect.KEY, se.gendut.klipp.engine.LayerFxEffect.STRAIGHT) } }
        val maskFx = gc?.let { cl -> layer?.let { se.gendut.klipp.engine.LayerFxEffect(it, cl.id, se.gendut.klipp.engine.LayerFxEffect.MASK, se.gendut.klipp.engine.LayerFxEffect.PREMULTIPLIED, src) } }
        runCatching { player.setVideoEffects(listOfNotNull(keyFx) + gc?.let { grade?.effects(it.id, src) }.orEmpty() + se.gendut.klipp.engine.CompositionBuilder.videoEffects(look) + listOfNotNull(maskFx)) }
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
                    this.alpha = if (seg == null || drawnIndex != active || (handoff && !state.playing)) 0f else seg.alpha(state.playhead.toDouble())
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
                factory = { c -> android.view.TextureView(c).apply { isOpaque = false; player.setVideoTextureView(this) } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { v -> player.clearVideoTextureView(v) },
            )
        }
    }
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
        fun record(d: Long) {
            drift = d; samples++; sumAbs += abs(d)
            if (abs(d) > abs(maxDrift)) maxDrift = d
        }
    }
    private val layers = java.util.concurrent.ConcurrentHashMap<String, Layer>()
    fun forLayer(name: String): Layer = layers.getOrPut(name) { Layer(name) }
    fun reset() = layers.clear()
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
