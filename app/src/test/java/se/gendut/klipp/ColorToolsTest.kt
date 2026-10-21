package se.gendut.klipp

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.GradeShaders
import se.gendut.klipp.engine.LutStore
import se.gendut.klipp.engine.Qualifier
import se.gendut.klipp.engine.TetraShaders
import se.gendut.klipp.engine.Wheel
import se.gendut.klipp.engine.Window
import java.io.File

/** The 0.14 color round: LUT in node, qualifier, window, key view, gallery – and that they don't interfere with each other. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ColorToolsTest {
    private fun px(n: GradeNode, r: Float, g: Float, b: Float, x: Float = 0.5f, y: Float = 0.5f): FloatArray {
        val c = floatArrayOf(r, g, b); GradeNode.apply(n.params(), c, x, y); return c
    }

    private val brighten = GradeNode(1, gain = Wheel(master = 0.5f))

    @Test fun nodeLutChangesOnlyThatClipAndMixWorks() {
        val with = GradeNode(1, lut = LutStore.TEAL_ORANGE)
        assertFalse("a node with only a LUT is not neutral", with.isNeutral)
        val a = px(with, 0.8f, 0.6f, 0.5f)
        assertTrue("the LUT changes the color", kotlin.math.abs(a[0] - 0.8f) + kotlin.math.abs(a[2] - 0.5f) > 0.01f)
        val off = px(with.copy(lutMix = 0f), 0.8f, 0.6f, 0.5f)
        assertEquals(0.8f, off[0], 1e-4f); assertEquals(0.5f, off[2], 1e-4f)
        assertTrue("strength 0 = neutral", with.copy(lutMix = 0f).isNeutral)
        // the LUT comes last in the node: gain first, then the LUT (= the same as two nodes in a row)
        val one = px(brighten.copy(lut = LutStore.TEAL_ORANGE), 0.4f, 0.4f, 0.4f)
        val two = floatArrayOf(0.4f, 0.4f, 0.4f).also { GradeNode.apply(brighten.params(), it); GradeNode.apply(GradeNode(2, lut = LutStore.TEAL_ORANGE).params(), it) }
        for (i in 0..2) assertEquals(two[i], one[i], 1e-4f)
        // a removed/unknown LUT does not crash and does not affect the image
        val gone = px(GradeNode(1, lut = "finns inte"), 0.3f, 0.5f, 0.7f)
        assertEquals(0.3f, gone[0], 1e-4f)
    }

    @Test fun qualifierSelectsColor() {
        val red = Qualifier.around(0.9f, 0.1f, 0.1f)
        assertTrue(red.on)
        val n = brighten.copy(qual = red)
        val r = px(n, 0.6f, 0.1f, 0.1f)
        val b = px(n, 0.1f, 0.1f, 0.6f)
        assertTrue("red is affected", r[0] > 0.7f)
        assertEquals("blue is left alone", 0.6f, b[2], 1e-3f)
        val inv = brighten.copy(qual = red.copy(invert = true))
        assertEquals("inverted: red is left alone", 0.6f, px(inv, 0.6f, 0.1f, 0.1f)[0], 1e-3f)
        assertTrue("inverted: blue is affected", px(inv, 0.1f, 0.1f, 0.6f)[2] > 0.7f)
        // hue wraps around 360°: a qualifier at 355° also takes 5°
        val wrap = brighten.copy(qual = Qualifier(true, 355f, 30f, 0f, 1f, 0f, 1f, 0.02f))
        val (h5, _, _) = Qualifier.hsl(0.6f, 0.08f, 0.05f)
        assertTrue(h5 * 360f < 10f)
        assertTrue(px(wrap, 0.6f, 0.08f, 0.05f)[0] > 0.7f)
    }

    @Test fun windowLimitsToRegion() {
        val n = brighten.copy(win = Window(1, 0.5f, 0.5f, 0.2f, 0.2f, 0.1f))
        assertTrue("center", px(n, 0.4f, 0.4f, 0.4f, 0.5f, 0.5f)[0] > 0.5f)
        assertEquals("corner", 0.4f, px(n, 0.4f, 0.4f, 0.4f, 0.05f, 0.05f)[0], 1e-4f)
        val vig = brighten.copy(win = Window(1, 0.5f, 0.5f, 0.2f, 0.2f, 0.1f, invert = true))
        assertEquals("vignette: center untouched", 0.4f, px(vig, 0.4f, 0.4f, 0.4f, 0.5f, 0.5f)[0], 1e-4f)
        val rect = brighten.copy(win = Window(2, 0.5f, 0.5f, 0.3f, 0.1f, 0f))
        assertTrue("rectangle: inside the width", px(rect, 0.4f, 0.4f, 0.4f, 0.75f, 0.5f)[0] > 0.5f)
        assertEquals("rectangle: outside the height", 0.4f, px(rect, 0.4f, 0.4f, 0.4f, 0.5f, 0.7f)[0], 1e-4f)
        // qualifier and window together = both must apply
        val both = n.copy(qual = Qualifier.around(0.9f, 0.1f, 0.1f))
        assertTrue(px(both, 0.6f, 0.1f, 0.1f, 0.5f, 0.5f)[0] > 0.7f)
        assertEquals(0.6f, px(both, 0.6f, 0.1f, 0.1f, 0.05f, 0.05f)[0], 1e-4f)
        assertEquals(0.6f, px(both, 0.1f, 0.1f, 0.6f, 0.5f, 0.5f)[2], 1e-4f)
    }

    @Test fun bitmapPositionsAndKeyView() {
        val bmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(100, 100, 100)) }
        // window in the top-left corner
        val g = ClipGrade(listOf(brighten.copy(win = Window(2, 0.2f, 0.25f, 0.15f, 0.2f, 0f))))
        val out = g.applyTo(bmp)
        assertTrue("top left brighter", (out.getPixel(8, 5) and 255) > 120)
        assertEquals("bottom right untouched", 100, out.getPixel(35, 17) and 255)
        val key = g.applyTo(bmp, keyOf = 1)
        assertEquals("key white inside the window", 255, key.getPixel(8, 5) and 255)
        assertEquals("key black outside", 0, key.getPixel(35, 17) and 255)
        // a node with only a window (no color change) is neutral but still shows its key
        val onlyWin = ClipGrade(listOf(GradeNode(1, win = Window(1))))
        assertTrue(onlyWin.isNeutral)
        assertEquals(255, onlyWin.applyTo(bmp, keyOf = 1).getPixel(20, 10) and 255)
        assertEquals("untouched without key view", 100, onlyWin.applyTo(bmp).getPixel(20, 10) and 255)
    }

    @Test fun jsonRoundTripKeepsEverything() {
        val n = GradeNode(3, "himmel", lift = Wheel(0.1f, 0f, 0f), lut = LutStore.BLEACH, lutMix = 0.4f,
            qual = Qualifier(true, 210f, 40f, 0.2f, 0.9f, 0.3f, 1f, 0.05f, true), win = Window(2, 0.3f, 0.2f, 0.4f, 0.1f, 0.5f, true))
        val back = ClipGrade.fromJson(ClipGrade(listOf(n)).toJson())
        assertEquals(n, back.nodes[0])
        // older projects (before 0.14) without the new fields read as before
        val old = GradeNode.fromJson(JSONObject().put("id", 1).put("sat", 1.5))
        assertEquals(null, old.lut); assertFalse(old.qual.on); assertFalse(old.win.on); assertEquals(1.5f, old.sat, 1e-6f)
        // a grade with a node LUT gives a different key for playback (rebuilt)
        assertNotEquals(ClipGrade(listOf(GradeNode(1))).hashCode(), ClipGrade(listOf(GradeNode(1, lut = LutStore.BLEACH))).hashCode())
    }

    @Test fun galleryStoresAndDeletes() {
        val dir = File(System.getProperty("java.io.tmpdir"), "klipp-stills-" + System.nanoTime())
        StillStore.initAt(dir)
        val bmp = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val g = ClipGrade(listOf(brighten))
        val s = kotlinx.coroutines.runBlocking { StillStore.add(bmp, "intervju", g) }
        assertEquals(1, StillStore.stills.size)
        StillStore.initAt(dir) // reload from disk
        assertEquals(1, StillStore.stills.size)
        assertEquals(g, StillStore.stills[0].grade)
        assertTrue(StillStore.image(StillStore.stills[0]) != null)
        StillStore.delete(s)
        assertEquals(0, StillStore.stills.size)
        dir.deleteRecursively()
    }

    /** All GLSL shaders compile with glslc (translated to 310 es) – if the tool is on the machine. */
    @Test fun shadersCompile() {
        // glslc from the NDK (the Linux build server or a local Android SDK)
        val home = System.getProperty("user.home")
        val glslc = (listOf(File("/root/android-sdk/ndk/30.0.16248370/shader-tools/linux-x86_64/glslc")) +
            (File("$home/Library/Android/sdk/ndk").listFiles().orEmpty().sortedDescending().flatMap { nd ->
                listOf(File(nd, "shader-tools/darwin-x86_64/glslc"), File(nd, "shader-tools/darwin-arm64/glslc"), File(nd, "shader-tools/linux-x86_64/glslc"))
            })).firstOrNull { it.canExecute() }
        assumeTrue(glslc != null)
        glslc!!
        val all = listOf("grade" to es2to310(GradeShaders.FRAGMENT), "tetra" to es2to310(TetraShaders.FRAGMENT),
            "look" to es2to310(se.gendut.klipp.engine.LookShaders.FRAGMENT), "agsl-node" to agslToGlsl(se.gendut.klipp.engine.AgslShaders.NODE), "agsl-look" to agslToGlsl(se.gendut.klipp.engine.AgslShaders.LOOK))
        for ((name, src) in all) {
            val f = File.createTempFile(name, ".frag")
            f.writeText(src)
            val p = ProcessBuilder(glslc.path, "-fshader-stage=frag", f.path, "-o", f.path + ".spv").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("$name: $out", 0, p.waitFor())
        }
    }

    /** AGSL (the preview's GPU color) translated to GLSL 310 es for a syntax and type check with glslc. */
    private fun agslToGlsl(a: String): String {
        val un = ArrayList<String>(); val samp = ArrayList<String>(); val lines = ArrayList<String>()
        var src = a.replace("half4", "vec4").replace("half3", "vec3").replace("float2", "vec2").replace("float3x3", "mat3").replace("float3", "vec3").replace("float4", "vec4")
        for (l in src.lines()) {
            val m = Regex("""uniform (\w+) (\w+);""").matchEntire(l.trim())
            if (m != null) { if (m.groupValues[1] == "shader") samp += m.groupValues[2] else un += "  ${m.groupValues[1]} ${m.groupValues[2]};"; continue }
            lines += l
        }
        src = lines.joinToString("\n")
        for (sn in samp) src = src.replace(Regex("""\b$sn\.eval\(([^;]*?)\)(\.\w+)?(?=[;,)\s+*-])""")) { r -> "texture($sn, (${r.groupValues[1]}) / 256.0)${r.groupValues[2]}" }
        src = src.replace("vec4 main(vec2 pos)", "vec4 shade(vec2 pos)")
        val header = "#version 310 es\nprecision highp float;\n" + samp.mapIndexed { i, sn -> "layout(binding=${i + 1}) uniform sampler2D $sn;" }.joinToString("\n") +
            "\nlayout(std140, binding=0) uniform U {\n" + un.joinToString("\n") + "\n};\nlayout(location=0) out vec4 fragOut;\n"
        return header + src + "\nvoid main() { fragOut = shade(gl_FragCoord.xy); }\n"
    }

    private fun es2to310(s0: String): String {
        val un = ArrayList<String>(); val samp = ArrayList<String>(); val lines = ArrayList<String>()
        for (l in s0.replace("#version 100", "#version 310 es").lines()) {
            val m = Regex("""uniform (\w+) (\w+);""").matchEntire(l.trim())
            if (m != null) { if (m.groupValues[1].startsWith("sampler")) samp += l.trim() else un += "  ${m.groupValues[1]} ${m.groupValues[2]};"; continue }
            lines += l
        }
        var s = lines.joinToString("\n")
        val sd = samp.mapIndexed { i, x -> "layout(binding=${i + 1}) $x" }.joinToString("\n")
        val block = if (un.isNotEmpty()) "layout(std140, binding=0) uniform U {\n" + un.joinToString("\n") + "\n};\n" else ""
        s = s.replaceFirst("precision highp float;", "precision highp float;\n$sd\n${block}layout(location=0) out vec4 fragOut;")
        return s.replace("varying ", "layout(location=0) in ").replace("texture2D", "texture").replace("gl_FragColor", "fragOut")
    }
}
