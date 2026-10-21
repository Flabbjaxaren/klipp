package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmFx
import se.gendut.klipp.engine.Spektra
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Film on the CPU for an image from a folder (KLIPP_GPU_DIR: src_dec.png and <name>.film.json) → cpu_<name>.png –
 * to compare the GPU path (the node cache's file from the emulator) with the CPU's. Skipped without the folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilmCpuRenderTest {
    companion object {
        @BeforeClass @JvmStatic fun data() { Spektra.open = { File("src/main/assets/spektrafilm/$it").inputStream() } }
    }

    @Test fun renderCases() {
        val dir = System.getenv("KLIPP_GPU_DIR")?.let { File(it) }
        assumeTrue(dir != null && File(dir, "src_dec.png").exists())
        val src = ImageIO.read(File(dir, "src_dec.png"))
        val w = src.width; val h = src.height
        val img = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = src.getRGB(x, y)
            val i = (y * w + x) * 3
            img[i] = ((p shr 16) and 255) / 255f; img[i + 1] = ((p shr 8) and 255) / 255f; img[i + 2] = (p and 255) / 255f
        }
        for (f in dir!!.listFiles { it.name.endsWith(".film.json") }!!) {
            val name = f.name.removeSuffix(".film.json")
            val film = Film.fromJson(JSONObject(f.readText()))!!
            val out = FilmFx.applyEncoded(FilmFx.setup(film), img, w, h, 0)
            val o = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) for (x in 0 until w) {
                val i = (y * w + x) * 3
                fun q(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                o.setRGB(x, y, (q(out[i]) shl 16) or (q(out[i + 1]) shl 8) or q(out[i + 2]))
            }
            ImageIO.write(o, "png", File(dir, "cpu_$name.png"))
            println("cpu $name done")
        }
    }
}
