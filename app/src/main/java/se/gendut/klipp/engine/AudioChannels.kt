package se.gendut.klipp.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi

/**
 * Which sound an audio clip plays. The engine places a file with several audio streams (a camera's four mono
 * microphone tracks) or a multichannel stream as one clip per stream or channel: the clip carries the stream
 * (ClipSnap.audioStream, 0 = the first) and the stream's channels it plays (ClipSnap.sourceChannels; empty = the first
 * two – a mono stream its one). Media3 plays only one audio track of a file and all its channels, so per piece:
 *  - a ProRes intermediate holds every stream side by side in one track ([ProRes.audioSlots]): the clip's channels
 *    are picked from its stream's slot;
 *  - another file's stream k ≥ 1 plays from a stand-in of just that stream ([AudioStreams]);
 *  - the channels are picked with Media3's ChannelMixingAudioProcessor ([processor]); one channel is mono, which the
 *    existing stereo upmix puts on both sides.
 * A file with one mono or stereo stream (and a clip of all of it) takes today's path: nothing is picked.
 */
@OptIn(UnstableApi::class)
object AudioChannels {
    /** What a piece plays: [uri], and of its audio track the channels [picks] (empty = all of it, as it is). */
    data class Pick(val uri: Uri, val picks: List<Int>)

    /** The largest input channel count a picker is prepared for (the intermediate keeps at most 64). */
    const val MAX_INPUT = 64

    /**
     * The channels of a track to take for a stream that starts at channel [first] with [count] channels: the clip's
     * [sourceChannels] (within the stream; at most two – left and right), or empty = the stream's first two (one if
     * mono). Channels beyond the stream are read as its last.
     */
    fun picks(first: Int, count: Int, sourceChannels: List<Int>): List<Int> {
        val n = count.coerceAtLeast(1)
        val own = if (sourceChannels.isEmpty()) (0 until minOf(2, n)).toList() else sourceChannels.take(2).map { it.coerceIn(0, n - 1) }
        return own.map { first + it }
    }

    /** Nothing to pick: stream 0 of a file whose first stream has at most two channels, and the clip plays all of it. */
    fun isDefault(stream: Int, sourceChannels: List<Int>, streamChannels: Int?): Boolean =
        stream == 0 && (streamChannels == null || streamChannels <= 2) &&
            (sourceChannels.isEmpty() || (streamChannels != null && sourceChannels == (0 until streamChannels).toList()))

    /**
     * The mixing matrix for an input of [inputs] channels: one output per pick (a channel past the input's last reads
     * the last – a file that turns out to have fewer channels plays what it has instead of failing).
     */
    fun matrix(inputs: Int, picks: List<Int>): ChannelMixingMatrix {
        val out = picks.size.coerceIn(1, 2)
        val m = FloatArray(inputs * out)
        for (o in 0 until out) {
            val inCh = (picks.getOrNull(o) ?: picks.first()).coerceIn(0, inputs - 1)
            m[inCh * out + o] = 1f
        }
        return ChannelMixingMatrix(inputs, out, m)
    }

    /** Media3's channel mixer for [picks], ready for any input channel count up to [MAX_INPUT]. */
    fun processor(picks: List<Int>): ChannelMixingAudioProcessor = ChannelMixingAudioProcessor().apply {
        for (n in 1..MAX_INPUT) putChannelMixingMatrix(matrix(n, picks))
    }

    /**
     * What a piece of stream [stream], channels [sourceChannels] plays: [play] is the file the piece's item decodes
     * (uriOf), [layout] the file whose streams the clip counts (the same, or – for a reversed clip or one with rendered
     * effects – the file those were made from: they keep its first audio track's channels). null = nothing to play yet
     * (a stand-in being made) or at all (a stream the intermediate didn't keep).
     */
    fun resolve(play: Uri, layout: Uri, stream: Int, sourceChannels: List<Int>): Pick? {
        // a ProRes intermediate: every stream in one track
        ProRes.audioSlots(layout)?.let { slots ->
            if (slots.isEmpty()) return if (stream == 0) Pick(play, if (sourceChannels.isEmpty()) emptyList() else picks(0, 2, sourceChannels)) else null
            val slot = slots.getOrNull(stream) ?: return null
            return Pick(play, picks(slot.first, slot.count, sourceChannels))
        }
        val counts = AudioStreams.cached(layout)
        if (stream == 0 || play != layout) {
            // the first stream (also what a reversed or rendered file holds – see the limits in AudioStreams)
            val n = counts?.firstOrNull()
            if (isDefault(0, sourceChannels, n)) return Pick(play, emptyList())
            return Pick(play, picks(0, n ?: (sourceChannels.maxOrNull()?.plus(1) ?: 2), sourceChannels))
        }
        // stream k ≥ 1 of an ordinary file: its stand-in, which holds just that stream
        val n = counts?.getOrNull(stream) ?: return null
        val file = AudioStreams.standIn(layout, stream) ?: return null
        return Pick(file, if (n <= 2 && (sourceChannels.isEmpty() || sourceChannels == (0 until n).toList())) emptyList() else picks(0, n, sourceChannels))
    }
}
