package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Text read aloud by the phone's own voice, to a sound file (kept per text and language). */
object SpeechFile {
    private const val TIMEOUT_MS = 60_000L

    class Result(val file: File, val seconds: Double)

    /** The text as speech; null = the phone has no voice for the language. */
    suspend fun make(context: Context, text: String, locale: Locale = Locale.getDefault()): Result? {
        val dir = File(context.filesDir, "speech").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest("$locale|$text".toByteArray()).joinToString("") { "%02x".format(it) }
        val out = File(dir, "$name.wav")
        if (out.length() > 0) return Result(out, seconds(out))
        val ready = CompletableDeferred<Int>()
        val tts = TextToSpeech(context.applicationContext) { ready.complete(it) }
        try {
            if (withTimeout(TIMEOUT_MS) { ready.await() } != TextToSpeech.SUCCESS) return null
            if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return null
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.complete(true) }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { done.complete(false) }
                override fun onError(id: String?, code: Int) { done.complete(false) }
            })
            val tmp = File(dir, "$name.tmp")
            if (tts.synthesizeToFile(text, null, tmp, name) != TextToSpeech.SUCCESS) return null
            if (!withTimeout(TIMEOUT_MS) { done.await() } || tmp.length() == 0L || !tmp.renameTo(out)) { tmp.delete(); return null }
            return Result(out, seconds(out))
        } finally { tts.shutdown() }
    }

    private fun seconds(f: File): Double {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.path)
            ex.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) / 1e6
        } finally { ex.release() }
    }
}
