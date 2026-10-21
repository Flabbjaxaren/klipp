package se.gendut.klipp

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Records voice-over from the microphone to an AAC file in the app's files (the file is kept so the project
 * can be opened again). 48 kHz mono, 128 kbit/s.
 */
class VoiceOverRecorder(private val context: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null
        private set

    fun start(): File {
        stopQuietly()
        val dir = File(context.filesDir, "voiceover").apply { mkdirs() }
        val f = File(dir, "voiceover_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + ".m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            // microphone busy, no permission …: leave nothing behind after the attempt
            r.release()
            f.delete()
            throw e
        }
        rec = r
        file = f
        return f
    }

    /** Stops; returns the file if anything was recorded. */
    fun stop(): File? {
        val r = rec ?: return null
        rec = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = file
        return if (ok && f != null && f.length() > 0) f else { f?.delete(); null }
    }

    fun stopQuietly() { runCatching { rec?.stop() }; rec?.release(); rec = null }
}
