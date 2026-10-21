package se.gendut.klipp.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import se.gendut.klipp.R
import se.gendut.klipp.Str

/** Color correction for the whole sequence (values −1..1, 0 = neutral). */
data class Look(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val sat: Float = 0f,
    val lut: CubeLut? = null,
    val lutMix: Float = 1f,
    val tetra: Boolean = true,
) {
    val isNeutral get() = listOf(exposure, contrast, temp, tint, sat).all { kotlin.math.abs(it) < 0.005f } && (lut == null || lutMix < 0.005f)

    companion object { val NONE = Look() }
}

/** Builds a Media3 composition from the engine's timeline: V1 for video, A1–A3 for audio. */
@OptIn(UnstableApi::class)
object CompositionBuilder {
    private fun us(sec: Double) = (sec * 1_000_000).roundToLong()

    /** Extra material after an upper layer's video clip (seconds; see layerSequence). */
    private const val UPPER_TAIL = 0.2

    /** Video effects for a color correction (same for preview and export). */
    fun videoEffects(look: Look): List<androidx.media3.common.Effect> {
        if (look.isNeutral) return emptyList()
        // a single pass (see LookEffect); Media3's own effects only as a fallback if it does not work on the GPU
        if (look.tetra) return listOf(LookEffect(look))
        val out = ArrayList<androidx.media3.common.Effect>()
        val e = Math.pow(2.0, look.exposure.toDouble()).toFloat()
        val r = e * (1f + 0.18f * look.temp)
        val g = e * (1f - 0.12f * look.tint)
        val b = e * (1f - 0.18f * look.temp)
        if (kotlin.math.abs(r - 1f) > 0.002f || kotlin.math.abs(g - 1f) > 0.002f || kotlin.math.abs(b - 1f) > 0.002f) {
            out += androidx.media3.effect.RgbAdjustment.Builder().setRedScale(r).setGreenScale(g).setBlueScale(b).build()
        }
        look.lut?.let { lut ->
            if (look.lutMix > 0.005f) out += if (look.tetra) TetrahedralLutEffect(lut, look.lutMix.coerceIn(0f, 1f)) else lut.toTrilinear()
        }
        if (kotlin.math.abs(look.contrast) > 0.005f) out += androidx.media3.effect.Contrast(look.contrast.coerceIn(-1f, 1f))
        if (kotlin.math.abs(look.sat) > 0.005f) out += androidx.media3.effect.HslAdjustment.Builder().adjustSaturation((look.sat * 100f).coerceIn(-100f, 100f)).build()
        return out
    }

    /** Volume as an audio processor (mono and stereo). null = unchanged. */
    fun gainProcessor(gain: Float): androidx.media3.common.audio.AudioProcessor? {
        if (kotlin.math.abs(gain - 1f) < 0.001f) return null
        return androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(1, 1).scaleBy(gain))
            putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(gain))
        }
    }

    fun dbToGain(db: Double): Float = Math.pow(10.0, db / 20.0).toFloat()

    fun constantSpeed(speed: Float) = object : androidx.media3.common.audio.SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    /**
     * Audio sequence of pieces (one track, or the track's cross-dissolve pieces). Per piece: the clip's level and ramps,
     * the clip's audio effects (the engine's DSP), the track's volume with automation × master, and the level meter.
     */
    private fun audioSequence(
        pieces: List<AudioPiece>, total: Double, uriOf: (Long) -> Uri?, mediaDur: (Long) -> Double?,
        track: TrackSnap, master: Float, meter: Int, fx: FxRegistry?,
    ): EditedMediaItemSequence? {
        val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var t = 0.0
        var any = false
        val trackGain = dbToGain(track.volumeDb) * master
        for (p in pieces.sortedBy { it.start }) {
            val uri = uriOf(p.item) ?: continue
            if (p.start > t + 0.001) b.addGap(us(p.start - t))
            val speed = if (kotlin.math.abs(p.speed - 1.0) > 0.001) constantSpeed(p.speed.toFloat()) else null
            val chain = fx?.create(p.clip, p.afx) ?: 0L
            val procs: List<androidx.media3.common.audio.AudioProcessor> = if (chain != 0L) {
                // effects between the clip's level (before) and the track's volume (after), as in the engine's mixer
                listOf(
                    GainRampProcessor(p, 1f),
                    FxProcessor(chain),
                    GainRampProcessor(p, trackGain, clip = false, track = track.takeIf { it.hasAutomation }, master = master, meter = meter),
                )
            } else if (!p.varying && !track.hasAutomation && meter < 0) {
                listOfNotNull(gainProcessor(trackGain * dbToGain(p.levelDb)))
            } else {
                listOf(GainRampProcessor(p, trackGain, track = track.takeIf { it.hasAutomation }, master = master, meter = meter))
            }
            b.addItem(
                EditedMediaItem.Builder(clipped(uri, p.srcIn, p.dur, p.speed))
                    .setDurationUs(mediaUs(mediaDur(p.item), p.srcIn, p.dur, p.speed))
                    .setRemoveVideo(true)
                    .setEffects(androidx.media3.transformer.Effects(procs, emptyList()))
                    .apply { if (speed != null) setSpeed(speed) }
                    .build()
            )
            t = p.end
            any = true
        }
        if (!any) return null
        if (total > t + 0.001) b.addGap(us(total - t))
        return b.build()
    }

    /**
     * The clip's part of the file. Media3 requires start ≥ 0 and end after start (otherwise IllegalArgumentException
     * without text): camera files (e.g. Samsung's APV) can have a negative start time in the file, so that the clip's
     * start in the source ends up slightly below zero – it is read from the file's start.
     */
    /**
     * The media length passed to Media3 – at least the clip's end in the source. Media3 requires end ≤ length (otherwise
     * IllegalArgumentException without text); the engine's probed length can be a fraction shorter than the clip's end
     * (video and audio tracks of different lengths in the file, rounding to frames).
     */
    private fun mediaUs(mediaDur: Double?, srcIn: Double, dur: Double, speed: Double): Long {
        val start = us(srcIn).coerceAtLeast(0L)
        val end = us(srcIn + dur * kotlin.math.abs(speed)).coerceAtLeast(start + 1)
        return maxOf(mediaDur?.let { us(it) } ?: 0L, end, 1L)
    }

    private fun clipped(uri: Uri, srcIn: Double, dur: Double, speed: Double): MediaItem {
        val start = us(srcIn).coerceAtLeast(0L)
        val end = us(srcIn + dur * kotlin.math.abs(speed)).coerceAtLeast(start + 1)
        return MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(start).setEndPositionUs(end).build())
            .build()
    }

    /**
     * The preview's frame size: the timeline's, downscaled so the short side is at most [short] (even dimensions, same
     * shape). Smaller timelines are kept.
     */
    fun previewSize(w: Int, h: Int, short: Int): Pair<Int, Int> {
        val s = minOf(w, h)
        if (short <= 0 || s <= short) return w to h
        val k = short.toDouble() / s
        fun even(v: Double) = (Math.round(v / 2.0) * 2).toInt().coerceAtLeast(2)
        return even(w * k) to even(h * k)
    }

    /**
     * Playback's downscale before the effects: the preview's short side, if the played image's short side [srcShort] is
     * clearly larger (otherwise null – no extra pass for material that is already small enough).
     */
    fun previewShortSide(srcShort: Int?, planW: Int, planH: Int): Int? {
        if (srcShort == null || srcShort <= 0 || planW <= 0 || planH <= 0) return null
        val target = minOf(planW, planH)
        return if (srcShort > target * 1.1) target else null
    }

    /** An image layer → a video sequence (gaps where the layer is empty; in the compositor they become transparent). */
    private fun layerSequence(
        layer: Layer, plan: LayerPlan, uriOf: (Long) -> Uri?, mediaDur: (Long) -> Double?, multi: Boolean, time: TimeBase,
        grades: Map<Long, ClipGrade> = emptyMap(), liveGrades: Boolean = false,
        /** The node cache (playback): the original file and its length, to find the clip's cache file. */
        nodeCache: Boolean = false, originalUriOf: (Long) -> Uri? = uriOf, originalLength: (Long) -> Double? = mediaDur,
        /** The frame size in playback (the preview resolution, see [previewSize]); 0 = the timeline's. */
        outW: Int = 0, outH: Int = 0,
        /** The project frame rate (freeze frames are shown as images at that rate). */
        fps: Double = 25.0,
    ): EditedMediaItemSequence? {
        val previewDownscale = outW > 0 && outH > 0
        val fw = if (previewDownscale) outW else plan.width
        val fh = if (previewDownscale) outH else plan.height
        val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var t = plan.origin
        var any = false
        // all frames get the sequence size (the compositor, the overlays and the motion compute in it)
        // (the motion computes in timeline pixels but yields a matrix in NDC – independent of the frame size)
        val fit = androidx.media3.effect.Presentation.createForWidthAndHeight(fw, fh, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT)
        // an upper layer is transparent where it has no segment (LayerCompositor), but Media3 fills a clip's last
        // frames with black if the decoder dropped them at the cut (B-frames: the last 2 frames of a transition showed
        // as black) – an upper layer's video clip therefore gets a bit more material after its end, hidden by the compositor
        val upper = multi && layer !== plan.layers.lastOrNull()
        fun tail(i: Int): Double {
            if (!upper) return 0.0
            val next = layer.segs.getOrNull(i + 1)?.start ?: plan.total
            return (next - layer.segs[i].end).coerceIn(0.0, UPPER_TAIL)
        }
        for ((index, seg) in layer.segs.withIndex()) {
            val uri = uriOf(seg.src.item) ?: continue
            // freeze frame: the frame as an image (from the original, see FrameHolds); not ready yet = a gap until it is
            val holdSrc = seg.src as? SegSrc.Still
            val still = holdSrc?.let { st -> FrameHolds.file(originalUriOf(st.item) ?: uri, st.sec) }
            if (holdSrc != null && still == null) continue
            val src = seg.src as? SegSrc.Media ?: SegSrc.Media(seg.src.item, (seg.src as SegSrc.Still).sec, 1.0)
            // speed on the item (Media3's supported path). The old speed effect was driven by the audio –
            // on a video item without audio it caused stutter, lag and crashes when the speed changed
            val speed = if (still == null && kotlin.math.abs(src.speed - 1.0) > 0.001) constantSpeed(src.speed.toFloat()) else null
            val c = seg.clip
            // without the compositor: motion and opacity directly on the clip (opacity = against black)
            // every frame is always fitted to the sequence format (different aspect ratio = black bars, never
            // stretched): the player surface has the sequence's shape and would otherwise stretch a frame of another format
            val single = if (!multi) listOfNotNull(
                fit,
                if (c != null && c.hasMotion(plan.width, plan.height)) MotionMatrix(c, plan.width, plan.height, time) else null,
                when {
                    c == null -> null
                    c.kf.containsKey(KfKeys.OPACITY) -> KeyframedOpacity(c, time)
                    c.hasOpacity -> (c.opacity / 100.0).toFloat().coerceIn(0f, 1f).let { a -> androidx.media3.effect.RgbAdjustment.Builder().setRedScale(a).setGreenScale(a).setBlueScale(a).build() }
                    else -> null
                },
            ) else emptyList()
            // speed: Media3 requires the speed effect to come first
            // the clip's grade (nodes) before the timeline's look, as in Resolve
            // tracked windows: the shader gets the frame's source time (sequence time → the clip's file)
            // node cache: the image up to and including the cache point from the pre-rendered file (full quality), the rest live
            var itemUri = uri
            var itemIn = src.srcIn
            var itemLen = mediaDur(src.item)
            var cachedPoint = -1
            // the cache file: the phone's decoding brought back to the same colors as live (see DecodeCalibration)
            var decodeFix: androidx.media3.common.Effect? = null
            // (not with green screen: the key is computed on the source image, the cache file already has the grade)
            if (still == null && nodeCache && c != null && grades[c.id]?.layer?.key == null) grades[c.id]?.let { g ->
                val orig = originalUriOf(src.item) ?: return@let
                val req = NodeCache.request(c, orig, originalLength(src.item), g) ?: return@let
                val e = NodeCache.lookup(req, src.srcIn, src.srcIn + seg.dur * kotlin.math.abs(src.speed)) ?: return@let
                itemUri = Uri.fromFile(e.file)
                itemIn = src.srcIn - e.start
                itemLen = MediaProbe.cached(itemUri)?.duration ?: (e.end - e.start)
                cachedPoint = req.point
                decodeFix = NodeCache.correction(e)?.let { DecodeCalibration.CorrectionEffect(it) }
            }
            // playback (live): always the clock – a window tracked afterwards then follows without a rebuild
            val clock: ((Long) -> Double?)? = c?.let { cl ->
                val g = grades[cl.id] ?: return@let null
                if (g.needsClock || liveGrades) { pts: Long -> time.seconds(pts)?.let { t -> Reversals.originalTime(cl.item, cl.sourceAt(t)) } } else null
            }
            val grade = c?.let { cl ->
                val g = grades[cl.id] ?: return@let null
                if (cachedPoint >= 0) g.effectsAfter(cachedPoint, if (liveGrades) cl.id else null, clock)
                else g.effects(if (liveGrades) cl.id else null, clock)
            }.orEmpty()
            // playback: 4K material is downscaled to the preview size before the nodes and the film (same
            // image on screen, a fraction of the work). The size of the file being played: the original (the node cache
            // has its size) or the proxy (its short side)
            val scaleDown = if (!previewDownscale) null else {
                val orig = originalUriOf(src.item)
                val short = when {
                    still != null -> FrameHolds.shortSide(still)
                    cachedPoint >= 0 || itemUri == orig -> orig?.let { Perf.cachedCodec(it) }?.let { k -> if (k.width > 0 && k.height > 0) minOf(k.width, k.height) else null }
                    else -> orig?.let { Proxies.proxyShort(it) }
                }
                previewShortSide(short, fw, fh)?.let { androidx.media3.effect.Presentation.createForShortSide(it) }
            }
            // the timeline's look sits on the whole composition (once, after the clips) – not on every clip,
            // so it is not rebuilt at every clip boundary (it flickered, especially around speed-changed clips)
            // (the correction after the downscale: both are linear, so the order does not change the image – but it is
            // computed at preview size instead of 4K)
            // the transition (wipe, push, slide, zoom, iris, dip through a color) last, in the sequence frame
            val transition = if (seg.hasFx) TransitionFxEffect(seg, time) else null
            // green screen before the grade (the source's colors), the mask after, in the clip's own frame (follows its
            // motion); without a layer below the transparent parts become black
            val layer = c?.let { cl -> grades[cl.id]?.layer?.takeIf { it.hasAlpha } }
            val liveId = if (liveGrades) c?.id else null
            val keyFx = layer?.key?.let { LayerFxEffect(layer, liveId, LayerFxEffect.KEY, LayerFxEffect.STRAIGHT) }
            val maskFx = layer?.let { LayerFxEffect(it, liveId, LayerFxEffect.MASK, if (upper) LayerFxEffect.STRAIGHT else LayerFxEffect.FLATTEN, clock) }
            val effects = listOfNotNull(scaleDown, decodeFix, keyFx) + grade + listOfNotNull(maskFx) + (if (multi) listOf(fit) else single) + listOfNotNull(transition)
            val edited = if (still != null) {
                EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(still)).setImageDurationMs(Math.round(seg.dur * 1000).coerceAtLeast(1)).build())
                    .setDurationUs(us(seg.dur).coerceAtLeast(1))
                    .setFrameRate(fps.takeIf { it.isFinite() && it >= 1.0 }?.let { Math.round(it).toInt() } ?: 25)
                    .setEffects(androidx.media3.transformer.Effects(emptyList(), effects))
                    .build()
            } else EditedMediaItem.Builder(clipped(itemUri, itemIn, seg.dur + tail(index), src.speed))
                // CompositionPlayer requires the media's own length (before clipping) on every item
                .setDurationUs(mediaUs(itemLen, itemIn, seg.dur + tail(index), src.speed))
                .setRemoveAudio(true)
                .setEffects(androidx.media3.transformer.Effects(emptyList(), effects))
                .apply { if (speed != null) setSpeed(speed) }
                .build()
            if (seg.start > t + 0.001) b.addGap(us(seg.start - t))
            b.addItem(edited)
            t = seg.end + (if (still == null) tail(index) else 0.0)
            any = true
        }
        if (!any) return null
        if (plan.total > t + 0.001) b.addGap(us(plan.total - t))
        return b.build()
    }

    /** The composition, whether it needs the multi-layer compositor, and the time interpreters (for calibration against the player). */
    class Built(val composition: Composition, val multi: Boolean, val plan: LayerPlan?, val times: List<TimeBase>)

    fun build(
        s: Snapshot, uriOf: (Long) -> Uri?, look: Look = Look.NONE, master: Float = 1f, overlays: Boolean = true, grades: Map<Long, ClipGrade> = emptyMap(),
        fx: FxRegistry? = null, hdrMode: Int = androidx.media3.transformer.Composition.HDR_MODE_KEEP_HDR,
        /** The export format (size, frame rate, audio) – null = the project's. */
        output: ExportSettings? = null,
    ): Composition? = buildFull(s, uriOf, look, master, overlays, grades = grades, fx = fx, hdrMode = hdrMode, output = output)?.composition

    /**
     * Playback's plan before the pre-render substitution (as [buildFull] makes it): same lengths, layers without a playable
     * file removed. Used for the pre-render chunks ([RenderCache.chunks]) in the main player and in the app's upper layers.
     */
    fun previewPlan(s: Snapshot, uriOf: (Long) -> Uri?): LayerPlan? {
        val durs = s.media.associate { it.id to it.duration.takeIf { d -> d > 0 } }
        val plan = LayerPlanner.plan(s, { MediaProbe.length(uriOf(it), durs[it]) }) ?: return null
        return plan.copy(layers = plan.layers.filter { l -> l.segs.any { seg -> uriOf(seg.src.item) != null } })
    }

    /** Is there 10-bit material among the video clips (the original files)? Probes the files – not on the main thread. */
    fun anyTenBit(context: Context, s: Snapshot, uriOf: (Long) -> Uri?): Boolean {
        val q = s.playable().sequence ?: return false
        return q.video.flatMap { it.clips }.filter { it.text == null }.mapNotNull { uriOf(it.item) }.distinct()
            .any { VideoPipeline.bitDepth(context, it) >= 10 }
    }

    /** Is there HDR material among the video clips? [probe] = probe files not yet probed (not on the main thread). */
    fun anyHdr(context: Context, s: Snapshot, probe: Boolean = false, uriOf: (Long) -> Uri?): Boolean {
        val q = s.playable().sequence ?: return false
        return q.video.flatMap { it.clips }.filter { it.text == null }.mapNotNull { uriOf(it.item) }.distinct()
            .any { (if (probe) Perf.isHdr(context, it) else Perf.cachedHdr(it)) == true }
    }

    /**
     * @param overlays draw titles and captions
     * @param forceSingle only the simple path (V1), if the multi-layer compositor does not work on the phone
     */
    fun buildFull(
        s: Snapshot, uriOf: (Long) -> Uri?, look: Look = Look.NONE, master: Float = 1f,
        overlays: Boolean = true, forceSingle: Boolean = false, grades: Map<Long, ClipGrade> = emptyMap(),
        /** Audio effect chains (null = no effects, e.g. in tests without the engine). */
        fx: FxRegistry? = null,
        /** Level meters per track (playback; not export). */
        meters: Boolean = false,
        /** How HDR material is converted to SDR (Composition.HDR_MODE_*), see Perf.hdrMode. */
        hdrMode: Int = androidx.media3.transformer.Composition.HDR_MODE_KEEP_HDR,
        /** The file for the video (proxy when available); audio always comes from [uriOf]. */
        videoUriOf: (Long) -> Uri? = uriOf,
        /** The grades' values are read live from [LiveGrades] (playback) instead of being fixed (export). */
        liveGrades: Boolean = false,
        /** The export format (size, frame rate, audio) – null = the project's. */
        output: ExportSettings? = null,
        /** Read the image from the node cache where available (playback – the export always computes from the originals). */
        nodeCache: Boolean = false,
        /**
         * The preview's short side (playback, see [VideoPipeline.previewShort]): the image is downscaled to it before the
         * effects and the chain computes at that size. 0 = full resolution (the export).
         */
        previewShort: Int = 0,
        /** Only the video in this part of the timeline (pre-render, see [RenderCache]); no audio. */
        range: ClosedFloatingPointRange<Double>? = null,
        /** Transform the plan before the build (playback: pre-rendered parts are substituted, see [RenderCache.substitute]). */
        planTransform: ((LayerPlan) -> LayerPlan)? = null,
    ): Built? = buildPlayable(s.playable(), uriOf, look, master, overlays, forceSingle, grades, fx, meters, hdrMode, videoUriOf, liveGrades, output, nodeCache, previewShort, range, planTransform)

    private fun buildPlayable(
        s: Snapshot, uriOf: (Long) -> Uri?, look: Look, master: Float, overlays: Boolean, forceSingle: Boolean,
        grades: Map<Long, ClipGrade>, fx: FxRegistry?, meters: Boolean, hdrMode: Int, videoUriOf: (Long) -> Uri?,
        liveGrades: Boolean, output: ExportSettings?, nodeCache: Boolean, previewShort: Int,
        range: ClosedFloatingPointRange<Double>? = null, planTransform: ((LayerPlan) -> LayerPlan)? = null,
    ): Built? {
        val q = s.sequence ?: return null
        val total = q.duration
        if (total <= 0.0) return null
        val durs = s.media.associate { it.id to it.duration.takeIf { d -> d > 0 } }
        // the lengths according to Media3 (same file reader as the player, see MediaProbe): the planning uses the limit
        // that applies, every item the played file's own length (video often from the proxy, audio from the original)
        val md: (Long) -> Double? = { MediaProbe.length(uriOf(it), durs[it]) }
        val mdVideo: (Long) -> Double? = { MediaProbe.cached(videoUriOf(it))?.duration ?: durs[it] }
        val mdAudio: (Long) -> Double? = { MediaProbe.cached(uriOf(it))?.duration ?: durs[it] }
        var plan = LayerPlanner.plan(s, md)
        // layers without a playable file do not count
        plan = plan?.copy(layers = plan.layers.filter { l -> l.segs.any { seg -> videoUriOf(seg.src.item) != null } })
        if (plan != null && range != null) plan = LayerPlanner.slice(plan, range.start, range.endInclusive)
        if (plan != null && planTransform != null) plan = planTransform(plan)
        if (plan != null && forceSingle && plan.layers.size > 1) {
            val base = plan.layers.last()
            plan = plan.copy(layers = listOf(base), blackFades = base.segs.mapNotNull { it.ramp }.distinct())
        }
        val multi = plan?.needsCompositor == true
        val seqs = ArrayList<EditedMediaItemSequence>()
        val times = ArrayList<TimeBase>()
        val clipTime = TimeBase(plan?.length ?: total, plan?.origin ?: 0.0).also { times += it } // time for keyframes on the clips (simple path)
        val (pw, ph) = if (plan == null || previewShort <= 0) 0 to 0 else previewSize(plan.width, plan.height, previewShort)
        plan?.layers?.forEach { l -> layerSequence(l, plan, videoUriOf, mdVideo, multi, clipTime, grades, liveGrades, nodeCache, uriOf, md, pw, ph, s.fps)?.let { seqs += it } }
        val hasVideo = seqs.isNotEmpty()
        // export without audio (and pre-render): no audio sequences
        val audio = if (output?.audio == false || range != null) emptyList() else q.audio.take(3)
        val anySolo = audio.any { it.solo }
        for ((ti, t) in audio.withIndex()) {
            if (t.muted || (anySolo && !t.solo)) continue
            // a silent track (without automation that can raise it) need not be played
            if (!t.hasAutomation && dbToGain(t.volumeDb) * master < 0.0005f) continue
            if (master < 0.0005f) continue
            val ap = AudioPlanner.plan(t, md)
            val m = if (meters) ti else -1
            audioSequence(ap.base, total, uriOf, mdAudio, t, master, m, fx)?.let { seqs += it }
            // the cross dissolves' overlapping pieces play in a separate sequence and are mixed in
            if (ap.over.isNotEmpty()) audioSequence(ap.over, total, uriOf, mdAudio, t, master, m, fx)?.let { seqs += it }
        }
        if (seqs.isEmpty()) return null
        val b = Composition.Builder(seqs)
        b.setHdrMode(hdrMode)
        var videoFx: List<androidx.media3.common.Effect> = emptyList()
        if (multi) b.setVideoCompositorSettings(LayerCompositor(plan, { id -> grades[id]?.layer?.blend ?: BlendMode.NORMAL }, pw, ph).also { times += it.time })
        // effects on the whole image: fades to black (simple path), titles and captions
        if (hasVideo && plan != null) {
            val fx = ArrayList<androidx.media3.common.Effect>(videoEffects(look))
            if (!multi && plan.blackFades.isNotEmpty()) fx += BlackFade(plan.blackFades, TimeBase(plan.length, plan.origin).also { times += it }, plan.rendered)
            if (overlays && plan.overlays.isNotEmpty()) {
                if (!multi) fx.add(0, androidx.media3.effect.Presentation.createForWidthAndHeight(plan.width, plan.height, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT))
                // titles and captions as separate overlays (they can show at the same time); captions on top
                val tb = TimeBase(plan.total).also { times += it }
                fx += androidx.media3.effect.OverlayEffect(listOf(
                    TimedOverlay(plan.overlays, false, plan.width, plan.height, tb),
                    TimedOverlay(plan.overlays, true, plan.width, plan.height, tb),
                ))
            }
            // the export's frame rate and size last (titles and captions are already drawn at the project size)
            if (output != null) {
                // always: clips can have a different (or variable) frame rate than the project – the file gets a constant one
                val outFps = output.outputFps(s.fps)
                if (outFps > 0.0) fx += FrameRateEffect(outFps)
                val (ow, oh) = output.outputSize(plan.width, plan.height)
                if (ow != plan.width || oh != plan.height) fx += androidx.media3.effect.Presentation.createForWidthAndHeight(ow, oh, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT)
            }
            if (fx.isNotEmpty()) videoFx = fx
        }
        // the export's audio: sample rate and mono
        val audioFx = ArrayList<androidx.media3.common.audio.AudioProcessor>()
        if (output != null && output.audio) {
            // mono: the mean of all channels (for every channel count – Media3's own matrices exist only for some, and a
            // mean can never clip)
            if (output.mono) audioFx += androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
                for (n in 1..8) putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix(n, 1, FloatArray(n) { 1f / n }))
            }
            if (output.sampleRate > 0) audioFx += androidx.media3.common.audio.SonicAudioProcessor().apply { setOutputSampleRateHz(output.sampleRate) }
        }
        if (videoFx.isNotEmpty() || audioFx.isNotEmpty()) b.setEffects(androidx.media3.transformer.Effects(audioFx, videoFx))
        return Built(b.build(), multi, plan, times)
    }
}

/** Playback of the whole timeline. Rebuilds the composition only when the timeline actually changed. */
@OptIn(UnstableApi::class)
/**
 * Where the player should seek (ms). If the playhead is on an edit point (or rounds to just before it), sometimes
 * nothing shows while the player is paused – it lands in the first clip's last fraction of a frame, or exactly on
 * the boundary between the items. Then we seek to the incoming clip's start + 1 ms, as an editor expects (the
 * frame after the cut).
 */
object CutSeek {
    fun ms(sec: Double, cuts: DoubleArray, fps: Double, playing: Boolean): Long {
        val ms = Math.round(sec * 1000).coerceAtLeast(0)
        if (playing || cuts.isEmpty()) return ms
        val tol = 0.3 / fps.coerceAtLeast(1.0)
        for (c in cuts) if (kotlin.math.abs(c - sec) <= tol) return kotlin.math.ceil(c * 1000).toLong() + 1
        return ms
    }

    /** Edit points in the video tracks (where a video clip starts). */
    fun cutsOf(s: Snapshot): DoubleArray =
        s.sequence?.video?.flatMap { t -> t.clips.filter { it.text == null && it.start > 0.0 }.map { it.start } }?.distinct()?.sorted()?.toDoubleArray() ?: DoubleArray(0)
}

class ProgramPlayback(private val context: Context) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var renderedFirstFrame = false
    /** The player has shown a frame since the composition was built (otherwise the app shows a still instead of black). */
    var frameShown by androidx.compose.runtime.mutableStateOf(false)
        private set
    /** Counted up for every frame the player shows after a seek/reload. */
    var framesShown by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    /** If the phone never reports the first frame: assume it shows a while after the player became ready. */
    private val assumeShown = Runnable { if (player.playbackState == androidx.media3.common.Player.STATE_READY) { frameShown = true; framesShown++ } }

    // must be created before the player below
    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            lastKey = null // force a reload on the next play
            // Media3 1.11's CompositionPlayer never clears an error: it stays idle forever, and
            // prepare() or a new composition does not help (only one frame showed, then it stood still).
            // The next reload therefore switches to a new player.
            dead = true
            val pos = positionSec()
            val cause = error.cause ?: error
            lastError = error.errorCodeName + ": " + (error.cause?.message ?: error.message) + where(cause)
            se.gendut.klipp.CrashReport.note("player error: $lastError\n" + cause.stackTrace.take(12).joinToString("\n") { "  at $it" })
            // the fallbacks only for errors they can fix (a missing file or a read error must not turn off HDR or the LUT)
            val code = error.errorCode
            val decoderIssue = code == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                code == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
                code == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                code == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                code == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
            val frameIssue = code == androidx.media3.common.PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
            // an internal player (the previous composition's) did not release its decoder/surface in time – common with
            // heavy files (4K 10-bit, APV, the node cache) or a busy decoder. No error in the new one: reload
            // silently, once (the player otherwise stayed in the error state, and every tap on play reloaded and stopped).
            // A failed runtime check (an internal state race in CompositionPlayer's renderers or audio graph) gets the
            // same single retry with a fresh player.
            if ((code == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT ||
                    code == androidx.media3.common.PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) && !timeoutRetried) {
                timeoutRetried = true
                val wasPlaying = player.playWhenReady
                main.post { reload(pos); if (wasPlaying) player.play() }
                return
            }
            val toneMapping = hdrMode == androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC ||
                hdrMode == androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
            if (toneMapping && !hdrCodecFailed && (decoderIssue || frameIssue)) {
                // the chosen tone mapping did not work on the phone → the other one (OpenGL ↔ the decoder)
                hdrCodecFailed = true
                main.post { reload(pos) }
                return
            }
            if (!look.isNeutral && look.tetra && frameIssue) {
                // the tetrahedral shader did not work on this GPU → Media3's trilinear LUT
                look = look.copy(tetra = false)
                onLutFallback()
                main.post { reload(pos) }
                return
            }
            onError(Str.t(R.string.playback_error, error.cause?.message ?: error.errorCodeName))
        }

        override fun onRenderedFirstFrame() {
            timeoutRetried = false
            renderedFirstFrame = true
            frameShown = true
            framesShown++
            main.removeCallbacks(assumeShown)
            firstFrameAt = android.os.SystemClock.elapsedRealtime()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                main.removeCallbacks(assumeShown)
                main.postDelayed(assumeShown, 1500)
            }
        }
    }

    /** Muted while a voice-over is recorded (the program audio must not get into the microphone) – takes precedence over [liveGain]. */
    var recordingMute = false
        set(v) { field = v; applyVolume() }
    /** The volume while dragging an audio clip's level (preview listening). Before [player]: it is created with the volume. */
    var liveGain = 1f
        set(v) { field = v; applyVolume() }
    private fun applyVolume() { player.volume = if (recordingMute) 0f else liveGain }

    /**
     * The preview plays only the bottom image layer (upper layers, titles and captions are drawn by the app
     * on top, see UpperLayers) – one player with Media3's simple video graph.
     */
    var player: CompositionPlayer by androidx.compose.runtime.mutableStateOf(newPlayer())
        private set
    /** The player got an error and cannot play any more (see onPlayerError) – replaced at the next reload. */
    private var dead = false

    /** A new player instead of one that got an error; the program view and the layers follow along (Compose state). */
    private fun replacePlayer() {
        val old = player
        val scrubbing = runCatching { old.isScrubbingModeEnabled }.getOrDefault(false)
        old.removeListener(listener)
        player = newPlayer()
        if (scrubbing) runCatching { player.isScrubbingModeEnabled = true }
        dead = false
        se.gendut.klipp.CrashReport.note("playback: new player after error ($lastError)")
        // the old one is released after the surface has moved to the new one
        main.post { runCatching { old.release() } }
    }
    /** A timeout or runtime-check error has just been reloaded (once per shown frame – not in a loop). */
    private var timeoutRetried = false
    var onError: (String) -> Unit = {}
    var hasContent = false
        private set
    private var lastKey: String? = null
    private var lastSnapshot: Snapshot? = null
    private var lastUriOf: ((Long) -> Uri?)? = null
    private var times: List<TimeBase> = emptyList()
    private var firstFrameAt = 0L
    private var preparedAt = 0L
    private var lastError: String? = null
    /** Where the error occurred: the first line in Media3 and the first in the app (for the error message). */
    private fun where(t: Throwable): String {
        val st = t.stackTrace
        val lib = st.firstOrNull { it.className.startsWith("androidx.media3") }
        val app = st.firstOrNull { it.className.startsWith("se.gendut") }
        return listOfNotNull(lib, app).joinToString(prefix = " – ", separator = " ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            .takeIf { lib != null || app != null } ?: ""
    }

    /** Why the latest build gave nothing to play (shown instead of a silent error). */
    private var buildProblem: String? = null

    /** Diagnostics for the program view (long-press "Program"). */
    fun diagnostics(): String = buildString {
        append("path: bottom layer in the player; upper layers, titles and captions drawn by the app\n")
        append("player: ").append(when (player.playbackState) { 1 -> "idle"; 2 -> "buffering"; 3 -> "ready"; else -> "ended" })
            .append(if (player.isPlaying) ", playing" else "").append('\n')
        append("hdr: ").append(when (hdrMode) { 1 -> "tone-mapped in the decoder"; 2 -> "tone-mapped with OpenGL"; else -> "no (SDR)" }).append('\n')
        append(VideoPipeline.describe())
        append(NodeCache.describe().trimEnd()).append(", the player reads ${cacheUsage.count { it == ';' }} clips from the cache\n")
        append(DecodeCalibration.describe())
        append(Perf.describe(context))
        append("first frame: ").append(if (renderedFirstFrame) "${firstFrameAt - preparedAt} ms" else "none yet").append('\n')
        append("position: ${player.currentPosition} ms\n")
        // the frames' time base (tracked windows, keyframes): interpreted time vs the player's position – should be close
        times.firstOrNull()?.last?.let { (pts, sec, h) ->
            append("time base: frame %.3f s, player %.3f s (stamp %d)\n".format(sec, h / 1e6, pts))
        }
        lastError?.let { append("error: ").append(it).append('\n') }
        buildProblem?.let { append("build: ").append(it).append('\n') }
    }
    var look: Look by androidx.compose.runtime.mutableStateOf(Look.NONE)
    var master: Float = 1f
    /** The clips' grades (the color mode). */
    var grades: Map<Long, ClipGrade> = emptyMap()
    private var shownGrades: Map<Long, ClipGrade>? = null
    var onLutFallback: () -> Unit = {}
    /** The audio effect chains for the current composition (live controls go here). */
    val fx = FxRegistry()
    /** The chosen HDR tone mapping did not work on the phone – the other one is used (see VideoPipeline.toneMapMode). */
    private var hdrCodecFailed = false
    private var hdrMode = androidx.media3.transformer.Composition.HDR_MODE_KEEP_HDR
    /** The clips that read the node cache in the latest composition. */
    private var cacheUsage = ""

    private fun newPlayer(): CompositionPlayer =
        CompositionPlayer.Builder(context)
            // short buffer: fast start after seek and rebuild (see Perf)
            .setLoadControl(Perf.editingLoadControl())
            // the program view: 10-bit surface (RGBA_1010102) when the setting is on; the chain computes in floating point (VideoPipeline)
            .setGlObjectsProvider(TenBitGlObjectsProvider(VideoPipeline.displayTenBit))
            .build().also { it.addListener(listener); it.volume = if (recordingMute) 0f else liveGain }

    /** The player's position, so the time interpretation can be calibrated should Media3 change time base. */
    fun hint(sec: Double) {
        val us = (sec * 1_000_000).roundToLong()
        for (t in times) t.hintUs = us
    }

    /** Key for what affects playback (clips, positions, sources, properties) – not in/out in the source. */
    private fun keyOf(s: Snapshot, uriOf: (Long) -> Uri?): String {
        val q = s.sequence ?: return ""
        val sb = StringBuilder()
        sb.append(look).append(master).append(hdrCodecFailed).append(LiveGrades.structure(grades).hashCode()).append('#').append(q.width).append('x').append(q.height).append(q.captions).append('#')
        for (t in q.video.take(3) + q.audio.take(3)) {
            sb.append(t.id).append(t.enabled).append(t.muted).append(t.solo).append(t.volumeDb).append(t.transitions).append(t.volKf).append(t.autoMode).append('|')
            // the audio effects' values change live in the chains (no rebuild) – only which effects exist counts
            for (c in t.clips) sb.append(c.copy(afx = c.afx.map { it.copy(params = emptyMap()) })).append(':').append(uriOf(c.item)?.let { Proxies.videoUri(it) }).append(';')
        }
        return sb.toString()
    }

    /** The pre-render chunks at the latest build (for the render bar and the rendering). */
    @Volatile var renderChunks: List<RenderCache.Chunk> = emptyList()
        private set

    fun update(s: Snapshot, uriOf: (Long) -> Uri?, positionSec: Double, force: Boolean = false): String? {
        lastSnapshot = s
        cuts = CutSeek.cutsOf(s)
        cutFps = s.fps
        lastUriOf = uriOf
        // proxy files are already SDR – HDR only if some video clip plays from an HDR original file (already probed)
        // the freeze frames (images from the originals) are extracted in the background; playback is rebuilt when they are ready
        FrameHolds.ensure(context, FrameHolds.holdsOf(s, uriOf))
        // reversed clips: the files that run backwards, likewise in the background
        Reversals.ensure(context, s, uriOf)
        hdrMode = VideoPipeline.toneMapMode(CompositionBuilder.anyHdr(context, s) { id -> uriOf(id)?.takeUnless { Proxies.enabled && Proxies.hasProxy(it) } }, hdrCodecFailed)
        // which clips read the node cache (and which file): if it changes the player is rebuilt
        cacheUsage = NodeCache.usage(s, grades, uriOf)
        // pre-rendered chunks: played from their files (if which exist changes, the player is rebuilt)
        val chunks = runCatching { CompositionBuilder.previewPlan(s, uriOf)?.let { RenderCache.chunks(it, { id -> grades[id] }, uriOf, s.fps, VideoPipeline.previewShort) } }
            .getOrNull().orEmpty()
        renderChunks = chunks
        val key = "#render" + RenderCache.usedFiles(chunks) + keyOf(s, uriOf) + "#hdr" + hdrMode + "#cache" + cacheUsage + "#preview" + VideoPipeline.previewShort + "#dc" + DecodeCalibration.stamp + "#holds" + FrameHolds.revision + "#rev" + Reversals.revision
        // the grades' values go directly to the shader passes; only which nodes exist requires a rebuild
        LiveGrades.publish(grades)
        if (!force && key == lastKey && hasContent) {
            // same structure: the chains get the engine's current parameter values (undo, released controls …)
            fx.sync(s.sequence?.audio?.flatMap { it.clips }.orEmpty())
            // changed grade: a new frame is enough (if it is already playing the change shows directly). A seek to
            // exactly the same position is ignored – hence 1 ms forward, the same frame
            if (grades !== shownGrades && !player.playWhenReady) nudge()
            shownGrades = grades
            return null
        }
        shownGrades = grades
        // Preview: Media3's CompositionPlayer does not support multiple video layers yet (only export does),
        // so the player shows the bottom layer and the app puts upper layers, titles and captions on top.
        // the old chains are released when the new composition has taken over (the audio thread can run them a while
        // longer); if the rebuild fails they are kept
        val oldChains = fx.takeAllByClip()
        val freeOld = { val l = oldChains.values.flatten(); main.postDelayed({ fx.free(l) }, 3000) }
        val keepOld = { val l = fx.swapBack(oldChains); main.postDelayed({ fx.free(l) }, 3000) }
        // video from the proxy when available (light 1080p), audio from the original
        val videoUri: (Long) -> Uri? = { id -> uriOf(id)?.let { Proxies.videoUri(it) } }
        se.gendut.klipp.CrashReport.note("playback building: ${s.sequence?.video?.sumOf { it.clips.size } ?: 0} video clips, hdr=$hdrMode, proxy=${Proxies.enabled}, speed=${s.sequence?.video?.flatMap { it.clips }?.map { it.speed }?.filter { kotlin.math.abs(it - 1.0) > 0.001 }?.distinct()}")
        val built = runCatching { CompositionBuilder.buildFull(s, uriOf, look, master, overlays = false, forceSingle = true, grades = grades, fx = fx, meters = true, hdrMode = hdrMode, videoUriOf = videoUri, liveGrades = true, nodeCache = true, previewShort = VideoPipeline.previewShort,
            planTransform = { p -> RenderCache.substitute(p, chunks) }) }.getOrElse {
            keepOld()
            // never an empty error: without text it looked like everything went fine (and then "nothing to play")
            buildProblem = "${it.javaClass.simpleName}: ${it.message ?: Str.t(R.string.playback_no_message)}${where(it)}"
            se.gendut.klipp.CrashReport.note("playback could not be built: $buildProblem\n${it.stackTraceToString().take(1500)}")
            return buildProblem
        }
        if (built == null) {
            freeOld()
            val clips = s.sequence?.video?.flatMap { it.clips }?.filter { it.text == null }.orEmpty()
            val missing = clips.count { uriOf(it.item) == null }
            buildProblem = if (clips.isEmpty()) null else Str.p(R.plurals.playback_clips_missing_file, clips.size, clips.size, missing)
            se.gendut.klipp.CrashReport.note("playback empty: ${buildProblem ?: "no clips"}")
            hasContent = false
            lastKey = key
            player.stop()
            return null
        }
        var handed = false
        return try {
            val wasPlaying = player.playWhenReady
            if (dead) replacePlayer()
            times = built.times
            preparedAt = android.os.SystemClock.elapsedRealtime()
            renderedFirstFrame = false
            frameShown = false
            hint(positionSec)
            player.setComposition(built.composition, CutSeek.ms(positionSec, cuts, cutFps, wasPlaying))
            // scrubbing mode only applies to the players that existed when it was turned on – the new ones (new build) get it too
            if (player.isScrubbingModeEnabled) { player.isScrubbingModeEnabled = false; player.isScrubbingModeEnabled = true }
            handed = true
            player.prepare()
            player.playWhenReady = wasPlaying
            hasContent = true
            buildProblem = null
            lastKey = key
            freeOld()
            null
        } catch (e: Exception) {
            // if the player got the new chains they are in use – then the old ones can be released
            if (handed) freeOld() else keepOld()
            lastKey = null
            buildProblem = "${e.javaClass.simpleName}: ${e.message ?: Str.t(R.string.playback_no_message)}${where(e)}"
            se.gendut.klipp.CrashReport.note("playback could not be started: $buildProblem\n${e.stackTraceToString().take(1500)}")
            buildProblem
        }
    }

    /** Rebuilds only if something affecting playback changed (look, grades, master). */
    fun refresh(positionSec: Double): String? {
        val s = lastSnapshot ?: return null
        val u = lastUriOf ?: return null
        return update(s, u, positionSec)
    }

    private var attached = false
    /** The video surface has been attached (the program view is shown). The first time no reload is needed. */
    fun onSurfaceAttached(positionSec: Double) {
        if (!attached) { attached = true; if (hasContent) return }
        reload(positionSec)
    }

    /** Reloads with the latest timeline (e.g. when the video surface is attached again). */
    fun reload(positionSec: Double): String? {
        val s = lastSnapshot ?: return null
        val u = lastUriOf ?: return null
        return update(s, u, positionSec, force = true)
    }

    /** Start playback robustly: reloads after errors, wakes the player from the idle and ended states. */
    /**
     * A new frame at the same place (changed grade while the player stands still): a seek one millisecond forward and
     * the next time back – a seek to exactly the same position is ignored, and always forward would drift away from
     * the playhead after many changes.
     */
    private var nudgeUp = true
    private fun nudge() {
        val pos = player.currentPosition
        player.seekTo(if (nudgeUp || pos < 1) pos + 1 else pos - 1)
        nudgeUp = !nudgeUp
    }

    fun start(positionSec: Double): String? {
        if (lastKey == null || player.playbackState == androidx.media3.common.Player.STATE_IDLE || !hasContent) {
            // a failed build is retried once before it becomes an error (an empty timeline is not)
            var err = reload(positionSec)
            if (err != null) err = reload(positionSec)
            if (err != null) return Str.t(R.string.playback_could_not_start, err)
        }
        if (!hasContent) return buildProblem?.let { Str.t(R.string.playback_could_not_start, it) } ?: Str.t(R.string.playback_nothing_to_play)
        val ended = player.playbackState == androidx.media3.common.Player.STATE_ENDED
        if (ended || kotlin.math.abs(positionSec - positionSec()) > 0.05) seek(positionSec)
        player.play()
        return null
    }

    private var cuts = DoubleArray(0)
    private var cutFps = 25.0
    private fun seekMs(sec: Double) = CutSeek.ms(sec, cuts, cutFps, player.playWhenReady)

    fun seek(sec: Double) {
        if (hasContent) { hint(sec); player.seekTo(seekMs(sec)) }
    }

    private fun positionSec(): Double = player.currentPosition / 1000.0
    val positionSec: Double get() = positionSec()

    fun release() {
        main.removeCallbacks(assumeShown)
        // the player (and its audio thread) first – then the chains it used
        player.release()
        fx.free(fx.takeAll())
    }
}

/** The source monitor: an ordinary ExoPlayer for one clip at a time. */
class SourcePlayback(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).setLoadControl(Perf.boundedLoadControl()).build().apply { setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT) }
    var currentItem: Long? = null
        private set

    fun open(item: Long, uri: Uri, startSec: Double) {
        if (currentItem != item || player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
            player.setMediaItem(MediaItem.fromUri(uri), (startSec * 1000).roundToLong())
            player.prepare()
            currentItem = item
        } else seek(startSec)
    }

    /** Releases the decoder so the timeline's player gets it (phones have few hardware decoders). */
    fun close() {
        player.stop()
        player.clearMediaItems()
        currentItem = null
    }

    val positionSec: Double get() = player.currentPosition / 1000.0
    fun seek(sec: Double) = player.seekTo((sec * 1000).roundToLong().coerceAtLeast(0))
    fun release() = player.release()
}

/** Export to MP4 (H.264/AAC via the phone's hardware encoder) and saving to Movies/Klipp. */
@OptIn(UnstableApi::class)
object Exporter {
    /** The latest export's size and bitrate (as the file actually turned out), for the message afterwards. */
    @Volatile var lastResult: String? = null
        private set

    suspend fun export(
        context: Context,
        s: Snapshot,
        uriOf: (Long) -> Uri?,
        name: String,
        look: Look = Look.NONE,
        master: Float = 1f,
        grades: Map<Long, ClipGrade> = emptyMap(),
        settings: ExportSettings = ExportSettings(),
        onProgress: (Int) -> Unit,
    ): Result<Uri> {
        lastResult = null
        // separate effect chains for the export (released when it is done)
        val fx = FxRegistry()
        try {
            return exportWith(context, s, uriOf, name, look, master, grades, fx, settings, onProgress)
        } finally {
            fx.free(fx.takeAll())
        }
    }

    private suspend fun exportWith(
        context: Context, s: Snapshot, uriOf: (Long) -> Uri?, name: String, look: Look, master: Float,
        grades: Map<Long, ClipGrade>, fx: FxRegistry, settings: ExportSettings, onProgress: (Int) -> Unit,
    ): Result<Uri> {
        // the freeze frames must exist (otherwise they became gaps in the file)
        runCatching { FrameHolds.await(context, FrameHolds.holdsOf(s, uriOf)) }.onFailure { return Result.failure(it) }
        // the reversed clips' files likewise (otherwise they played forwards in the file)
        runCatching { Reversals.await(context, s, uriOf) }.onFailure { return Result.failure(it) }
        val anyHdr = withContext(Dispatchers.IO) { CompositionBuilder.anyHdr(context, s, probe = true, uriOf = uriOf) }
        // HDR is kept only when possible: HDR sources, an encoder for HDR (HEVC, 10-bit) – otherwise tone-mapped to SDR
        val keepHdr = settings.hdr && anyHdr && EncoderCaps.hdrSupported
        if (keepHdr) {
            val st = settings.copy(codec = MimeTypes.VIDEO_H265)
            return exportOnce(context, s, uriOf, name, look, master, grades, fx, androidx.media3.transformer.Composition.HDR_MODE_KEEP_HDR, st, false, onProgress)
        }
        // SDR: 10-bit when selected, or (Auto) when some video clip is 10-bit – in HEVC/AV1 Main10
        val anyTen = withContext(Dispatchers.IO) { CompositionBuilder.anyTenBit(context, s, uriOf) }
        val wantTen = settings.bitDepth == 10 || (settings.bitDepth == 0 && anyTen)
        val tenCodec = if (wantTen) VideoPipeline.tenBitCodec(settings.codec) else null
        val tenBit = tenCodec != null
        if (wantTen && !tenBit) se.gendut.klipp.CrashReport.note("export: 10-bit requested but no encoder supports Main10 – 8-bit")
        val st = settings.copy(hdr = false, codec = tenCodec ?: settings.codec)
        var r = exportOnce(context, s, uriOf, name, look, master, grades, fx, VideoPipeline.toneMapMode(anyHdr, false), st, tenBit, onProgress)
        // the HDR tone mapping did not work on the phone: the other path
        if (r.isFailure && anyHdr && r.exceptionOrNull() !is kotlinx.coroutines.CancellationException) {
            fx.free(fx.takeAll())
            r = exportOnce(context, s, uriOf, name, look, master, grades, fx, VideoPipeline.toneMapMode(true, true), st, tenBit, onProgress)
        }
        // 10-bit failed (the encoder's input): once more in 8-bit with the chosen format
        if (r.isFailure && tenBit && r.exceptionOrNull() !is kotlinx.coroutines.CancellationException) {
            se.gendut.klipp.CrashReport.note("10-bit export failed (${r.exceptionOrNull()?.message}) – 8-bit")
            fx.free(fx.takeAll())
            r = exportOnce(context, s, uriOf, name, look, master, grades, fx, VideoPipeline.toneMapMode(anyHdr, false), settings.copy(hdr = false), false, onProgress)
        }
        return r
    }

    private suspend fun exportOnce(
        context: Context, s: Snapshot, uriOf: (Long) -> Uri?, name: String, look: Look, master: Float,
        grades: Map<Long, ClipGrade>, fx: FxRegistry, hdrMode: Int, settings: ExportSettings, tenBit: Boolean, onProgress: (Int) -> Unit,
    ): Result<Uri> {
        val comp = CompositionBuilder.build(s, uriOf, look, master, grades = grades, fx = fx, hdrMode = hdrMode, output = settings) ?: return Result.failure(IllegalStateException(Str.t(R.string.playback_timeline_empty)))
        // Auto: a fixed bitrate for the file's format (not Media3's guess from the source frame rate)
        val q = s.sequence
        val st = if (settings.bitrateMbps > 0f || q == null) settings else settings.copy(bitrateMbps = settings.videoMbps(q.width, q.height, s.fps).toFloat())
        val out = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        // the temporary file is deleted whatever happens (error, cancel, failed first HDR attempt, done)
        try {
            return exportTo(context, comp, out, name, st, tenBit, onProgress)
        } finally {
            out.delete()
        }
    }

    /** The encoders' settings from [st]: bitrate, CBR/VBR, keyframes, profile, audio bitrate. */
    private fun encoderFactory(context: Context, st: ExportSettings, tenBit: Boolean = false): androidx.media3.transformer.DefaultEncoderFactory {
        val v = androidx.media3.transformer.VideoEncoderSettings.Builder()
        if (st.bitrateMbps > 0f) v.setBitrate((st.bitrateMbps * 1_000_000).toInt())
        v.setBitrateMode(if (st.cbr) android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (st.keyframeSec > 0f) v.setiFrameIntervalSeconds(st.keyframeSec)
        // 10-bit in SDR: Main10 with the encoder's highest level (Media3 uses the profile only with a supported level)
        val ten = if (tenBit) VideoPipeline.tenBitProfile(st.codec) else null
        if (ten != null) {
            v.setEncodingProfileLevel(ten.first, ten.second)
        } else if (st.highProfile && st.codec == MimeTypes.VIDEO_H264 && !st.hdr && avcHighSupported) {
            // H.264 High only if some encoder has it (otherwise the encoder chooses itself)
            v.setEncodingProfileLevel(android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, androidx.media3.transformer.VideoEncoderSettings.NO_VALUE)
        }
        return androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
            // if the phone cannot do exactly what was chosen (size, bitrate, profile) the closest possible is chosen
            .setEnableFallback(true)
            .setRequestedVideoEncoderSettings(v.build())
            .setRequestedAudioEncoderSettings(androidx.media3.transformer.AudioEncoderSettings.Builder().setBitrate(st.audioKbps * 1000).build())
            .build()
    }

    private val avcHighSupported: Boolean by lazy {
        runCatching {
            androidx.media3.transformer.EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H264).any { info ->
                info.getCapabilitiesForType(MimeTypes.VIDEO_H264).profileLevels.any { it.profile == android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
            }
        }.getOrDefault(false)
    }

    private suspend fun exportTo(context: Context, comp: Composition, out: File, name: String, st: ExportSettings, tenBit: Boolean, onProgress: (Int) -> Unit): Result<Uri> {
        val result = transform(context, comp, out, st, tenBit, onProgress = onProgress)
        result.exceptionOrNull()?.let { return Result.failure(it) }
        return withContext(Dispatchers.IO) { runCatching { saveToMovies(context, out, name) } }
    }

    /**
     * The composition [comp] → the file [out] with Transformer (the export and the pre-render). [note] = write the result
     * to the crash report (the export).
     */
    internal suspend fun transform(context: Context, comp: Composition, out: File, st: ExportSettings, tenBit: Boolean, note: Boolean = true, onProgress: (Int) -> Unit): Result<Unit> {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                // Snapdragon: the encoding threads on the big cores while the job runs (see Perf.Boost)
                val boost = Perf.Boost(context)
                val transformer = Transformer.Builder(context)
                    .setAssetLoaderFactory(Perf.assetLoaderFactory(context))
                    .setVideoMimeType(st.codec)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory(context, st, tenBit))
                    // floating point in the whole chain; with 10-bit an RGBA_1010102 surface into the encoder
                    .setVideoFrameProcessorFactory(VideoPipeline.processorFactory(tenBit))
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            boost.close()
                            // what the file actually became (the encoder's choice when it cannot do exactly what was requested)
                            val r = exportResult
                            if (note) lastResult = "${r.width}×${r.height}" + (if (r.averageVideoBitrate > 0) " · ${"%.0f".format(r.averageVideoBitrate / 1e6)} Mbit/s" else "")
                            if (note) se.gendut.klipp.CrashReport.note("export done: ${r.width}×${r.height}, requested ${"%.1f".format(st.bitrateMbps)} Mbit/s, got ${"%.1f".format(r.averageVideoBitrate / 1e6)} Mbit/s, ${r.videoEncoderName} ${st.codec.substringAfter('/')}${if (tenBit) " 10-bit" else ""}")
                            if (cont.isActive) cont.resume(Result.success(Unit))
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            boost.close()
                            if (cont.isActive) cont.resume(Result.failure(exportException))
                        }
                    })
                    .build()
                transformer.start(comp, out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel(); boost.close() }
                // the progress
                val holder = ProgressHolder()
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                        handler.postDelayed(this, 200)
                    }
                }
                handler.post(poll)
            }
        }
    }

    private fun saveToMovies(context: Context, file: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Klipp")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error(Str.t(R.string.playback_create_file_failed))
        try {
            (resolver.openOutputStream(uri) ?: error(Str.t(R.string.playback_write_file_failed))).use { o -> file.inputStream().use { it.copyTo(o) } }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            // no half-written (pending) file left in Movies
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } finally {
            file.delete()
        }
        return uri
    }
}

/**
 * The timeline's look on the CPU (same order and math as the Media3 effects in [CompositionBuilder.videoEffects]),
 * so the preview can show the change while the slider is dragged.
 */
fun Look.applyTo(src: android.graphics.Bitmap): android.graphics.Bitmap {
    if (isNeutral) return src
    val w = src.width; val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val e = Math.pow(2.0, exposure.toDouble()).toFloat()
    val rs = e * (1f + 0.18f * temp); val gsc = e * (1f - 0.12f * tint); val bs = e * (1f - 0.18f * temp)
    val c = contrast.coerceIn(-1f, 1f)
    val cf = (1f + c) / (1.0001f - c)
    val doC = kotlin.math.abs(contrast) > 0.005f
    val dS = (sat * 100f).coerceIn(-100f, 100f) / 100f
    val doS = kotlin.math.abs(sat) > 0.005f
    val l = lut?.takeIf { lutMix > 0.005f }
    val mix = lutMix.coerceIn(0f, 1f)
    for (i in px.indices) {
        val p = px[i]
        var r = ((p shr 16) and 255) / 255f * rs
        var g = ((p shr 8) and 255) / 255f * gsc
        var b = (p and 255) / 255f * bs
        r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
        if (l != null) {
            val o = l.applyTetrahedral(r, g, b)
            r += (o[0] - r) * mix; g += (o[1] - g) * mix; b += (o[2] - b) * mix
        }
        if (doC) {
            r = (cf * (r - 0.5f) + 0.5f).coerceIn(0f, 1f); g = (cf * (g - 0.5f) + 0.5f).coerceIn(0f, 1f); b = (cf * (b - 0.5f) + 0.5f).coerceIn(0f, 1f)
        }
        if (doS) {
            // HSL: the saturation is shifted by dS (like Media3's HslAdjustment)
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val li = (mx + mn) / 2f
            val d = mx - mn
            if (d > 1e-5f) {
                val s0 = if (li > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
                val s1 = (s0 + dS).coerceIn(0f, 1f)
                val k = s1 / s0
                // same hue and lightness, scaled saturation: the distance to gray is scaled
                r = (li + (r - li) * k).coerceIn(0f, 1f); g = (li + (g - li) * k).coerceIn(0f, 1f); b = (li + (b - li) * k).coerceIn(0f, 1f)
            }
        }
        px[i] = (p and -0x1000000) or ((r * 255f + 0.5f).toInt() shl 16) or ((g * 255f + 0.5f).toInt() shl 8) or (b * 255f + 0.5f).toInt()
    }
    return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
}
