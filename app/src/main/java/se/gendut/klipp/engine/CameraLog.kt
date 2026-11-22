package se.gendut.klipp.engine

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * The log curve a camera says it recorded in – read from the metadata it leaves in the file ([QtBoxes]), because the
 * container's colour tags often say BT.709 for log footage (a Panasonic S5II's ProRes: BT.709 tags, V-Log in its
 * com.panasonic.SemiPro.metadata.xml). Used only as a suggestion: a new OpenDRT or film node starts from it, and the
 * node's panel offers it when another input is chosen (ColorTools) – nothing is graded by it on its own.
 *
 * Known: Panasonic's XML (<CaptureGamma>V-Log</CaptureGamma>, <CaptureGamut>V-Gamut</CaptureGamut>) and Sony's
 * NonRealTimeMeta (CaptureGammaEquation s-log3 / s-log3-cine with CaptureColorPrimaries S-Gamut3 / S-Gamut3.Cine).
 */
object CameraLog {
    private val byUri = ConcurrentHashMap<String, OpenDrt.Input>()

    /** The detected input for a file (the original or its intermediate), null = nothing known. */
    fun of(uri: Uri?): OpenDrt.Input? = uri?.let { byUri[it.toString()] }

    fun remember(uri: Uri, input: OpenDrt.Input?) {
        if (input != null) byUri[uri.toString()] = input
    }

    /** Copies what is known about [from] to [to] (an intermediate file stands in for its original). */
    fun alias(from: Uri, to: Uri) { byUri[from.toString()]?.let { byUri[to.toString()] = it } }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>\\s*([^<]+?)\\s*</$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)

    private fun item(xml: String, name: String): String? =
        Regex("<Item\\s+name=\"$name\"\\s+value=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)

    private fun norm(s: String?) = s?.lowercase()?.replace(Regex("[^a-z0-9]"), "")

    /** The log input the camera metadata in [texts] names, or null. */
    fun detect(texts: List<String>): OpenDrt.Input? {
        for (t in texts) {
            if ('<' !in t) continue
            val gamma = norm(tag(t, "CaptureGamma") ?: item(t, "CaptureGammaEquation"))
            val gamut = norm(tag(t, "CaptureGamut") ?: item(t, "CaptureColorPrimaries"))
            when {
                gamma == null -> continue
                // V-Log is only ever recorded with V-Gamut
                gamma == "vlog" && (gamut == null || gamut == "vgamut") -> return OpenDrt.Input.VLOG
                gamma == "slog3cine" -> return OpenDrt.Input.SLOG3
                gamma == "slog3" -> return when (gamut) {
                    "sgamut3cine" -> OpenDrt.Input.SLOG3
                    "sgamut3" -> OpenDrt.Input.SLOG3SG
                    // (S-Log3 without a stated gamut: the Cine variant is what Sony's cameras default to)
                    else -> OpenDrt.Input.SLOG3
                }
            }
        }
        return null
    }

    internal fun clearForTest() = byUri.clear()
}
