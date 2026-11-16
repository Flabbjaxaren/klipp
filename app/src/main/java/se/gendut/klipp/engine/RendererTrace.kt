package se.gendut.klipp.engine

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime

/**
 * Diagnostics: the renderer events of one sequence's player in the cut trace ("seq" lines) – when its video renderer is
 * turned off and on, when the decoder is made or reused, the frames it drops, its states and item transitions. The
 * time in each line is the event's own (the callbacks arrive later, on the main thread).
 */
@OptIn(UnstableApi::class)
class RendererTrace(private val index: Int) : AnalyticsListener {
    private fun log(t: EventTime, what: String) =
        CutTrace.log("seq", "$index late=${android.os.SystemClock.elapsedRealtime() - t.realtimeMs} pos=${t.currentPlaybackPositionMs} win=${t.windowIndex} evpos=${t.eventPlaybackPositionMs} $what")

    override fun onVideoEnabled(eventTime: EventTime, decoderCounters: DecoderCounters) = log(eventTime, "video enabled")
    override fun onVideoDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) =
        log(eventTime, "video disabled rendered=${decoderCounters.renderedOutputBufferCount} dropped=${decoderCounters.droppedBufferCount} skipped=${decoderCounters.skippedOutputBufferCount}")
    override fun onVideoDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
        log(eventTime, "video decoder init $decoderName took=$initializationDurationMs")
    override fun onVideoDecoderReleased(eventTime: EventTime, decoderName: String) = log(eventTime, "video decoder released")
    override fun onVideoInputFormatChanged(eventTime: EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) =
        log(eventTime, "video format ${format.width}x${format.height} ${format.sampleMimeType} reuse=${decoderReuseEvaluation?.result} discard=${decoderReuseEvaluation?.discardReasons}")
    override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) = log(eventTime, "video dropped=$droppedFrames in=$elapsedMs")
    override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) = log(eventTime, "first frame rendered at=$renderTimeMs")
    override fun onAudioEnabled(eventTime: EventTime, decoderCounters: DecoderCounters) = log(eventTime, "audio enabled")
    override fun onAudioDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) = log(eventTime, "audio disabled")
    override fun onPlaybackStateChanged(eventTime: EventTime, state: Int) = log(eventTime, "state=$state")
    override fun onIsPlayingChanged(eventTime: EventTime, isPlaying: Boolean) = log(eventTime, "playing=$isPlaying")
    override fun onPositionDiscontinuity(eventTime: EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        log(eventTime, "discontinuity reason=$reason from=${oldPosition.mediaItemIndex}/${oldPosition.positionMs} to=${newPosition.mediaItemIndex}/${newPosition.positionMs}")
    override fun onPlayerError(eventTime: EventTime, error: androidx.media3.common.PlaybackException) = log(eventTime, "error ${error.errorCodeName} ${error.message}")
    override fun onVideoCodecError(eventTime: EventTime, videoCodecError: Exception) = log(eventTime, "video codec error $videoCodecError")
}
