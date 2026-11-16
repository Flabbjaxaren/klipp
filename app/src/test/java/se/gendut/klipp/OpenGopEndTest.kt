package se.gendut.klipp

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A clip ending just before an open GOP's keyframe (HEVC's CRA, x265's default) keeps its last frames: the pictures
 * shown before the keyframe are decoded after it (Klipp's change in the vendored SampleQueue, see its KLIPP.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenGopEndTest {
    private fun framesRead(order: List<Int>, keyframes: Set<Int>, reorder: Int, endUs: Long): List<Int> {
        val q = SampleQueue.createWithoutDrm(DefaultAllocator(true, 1024))
        q.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setMaxNumReorderSamples(reorder).build())
        q.setReadEndTimeUs(endUs)
        for (n in order) {
            q.sampleData(ParsableByteArray(ByteArray(4)), 4)
            q.sampleMetadata(Math.round(n * 1e6 / 30), if (n in keyframes) C.BUFFER_FLAG_KEY_FRAME else 0, 4, 0, null)
        }
        val out = ArrayList<Int>()
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        while (true) {
            buffer.clear()
            val r = q.read(holder, buffer, 0, false)
            if (r == C.RESULT_NOTHING_READ || buffer.isEndOfStream) break
            if (r == C.RESULT_BUFFER_READ) out += Math.round(buffer.timeUs * 30 / 1e6).toInt()
        }
        return out
    }

    // decoding order of x265's pyramid with an open GOP every 30 frames: … 57 56 55 | 60(key) 59 58 | 63 62 61 …
    // (the queue starts at a keyframe: 51 is one here)
    private val order = listOf(51, 53, 52, 54, 57, 56, 55, 60, 59, 58, 63, 62, 61, 66, 65, 64)

    @Test fun theFramesBeforeAnOpenGopsKeyframeAreRead() {
        // the clip ends at 1.972 s: frames up to 59 (1.967 s) are the clip's
        val read = framesRead(order, setOf(51, 60), reorder = 2, endUs = 1_972_000L)
        assertEquals(true, 58 in read && 59 in read)
        assertEquals(listOf(51, 52, 53, 54, 55, 56, 57, 58, 59), read.filter { it < 60 }.sorted())
    }

    @Test fun aClosedGopStillEndsAtTheKeyframe() {
        // no reordering: the keyframe after the end ends the read at once, as before
        val read = framesRead(listOf(55, 56, 57, 58, 59, 60, 61, 62), setOf(55, 60), reorder = 0, endUs = 1_972_000L)
        assertEquals(listOf(55, 56, 57, 58, 59), read)
    }
}
