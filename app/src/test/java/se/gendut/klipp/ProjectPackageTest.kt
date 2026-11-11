package se.gendut.klipp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ProjectPackage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The project package (.klipp): what goes in comes out, media are unpacked safely and relinked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectPackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val side = JSONObject()
        .put("media", JSONArray().put(JSONObject().put("item", 3).put("uri", "content://picker/3")).put(JSONObject().put("item", 5).put("uri", "content://picker/5")))
        .put("meta", JSONObject().put("name", "Sommar"))
        .put("extra", JSONObject().put("grades", JSONObject().put("k", 1)))

    @Test fun roundTripWithMedia() {
        val video = ByteArray(300_000) { (it * 7).toByte() }
        val out = ByteArrayOutputStream()
        var last = 0L
        val w = ProjectPackage.write(out, "PROJECT".toByteArray(), side, byteArrayOf(1, 2, 3), listOf(
            ProjectPackage.Media(3, "28.mp4") { ByteArrayInputStream(video) },
            ProjectPackage.Media(5, "gone.mp4") { null },
        ), "0.18.14") { last = it }
        assertEquals(listOf(3L), w.packed)
        assertEquals(listOf(5L), w.unreadable)
        assertTrue("progress reached the media", last >= video.size)

        val dir = tmp.newFolder("media")
        val u = ProjectPackage.read(ByteArrayInputStream(out.toByteArray()), dir)
        assertArrayEquals("PROJECT".toByteArray(), u.project)
        assertArrayEquals(byteArrayOf(1, 2, 3), u.thumb)
        assertEquals("Sommar", u.side.getJSONObject("meta").getString("name"))
        assertEquals(1, u.side.getJSONObject("extra").getJSONObject("grades").getInt("k"))
        assertEquals(setOf(3L), u.media.keys)
        assertArrayEquals(video, u.media.getValue(3).readBytes())
        assertEquals(dir, u.media.getValue(3).parentFile)

        // the unpacked file replaces item 3's link; item 5 keeps its old link
        val r = ProjectPackage.relinked(u.side, mapOf(3L to "file:///x/3_28.mp4"))
        val links = (0 until r.getJSONArray("media").length()).associate { i -> r.getJSONArray("media").getJSONObject(i).let { it.getLong("item") to it.getString("uri") } }
        assertEquals(mapOf(3L to "file:///x/3_28.mp4", 5L to "content://picker/5"), links)
    }

    @Test fun projectOnlyIsSmall() {
        val out = ByteArrayOutputStream()
        ProjectPackage.write(out, ByteArray(1000), side, null, emptyList())
        assertTrue(out.size() < 4000)
        val u = ProjectPackage.read(ByteArrayInputStream(out.toByteArray()), tmp.newFolder("m"))
        assertTrue(u.media.isEmpty())
        assertEquals(null, u.thumb)
    }

    @Test fun pathsStayInTheMediaFolder() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun e(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            e("project.fcproj", byteArrayOf(9))
            e("side.json", "{}".toByteArray())
            e("media/../../evil.sh", "x".toByteArray())
        }
        val dir = tmp.newFolder("safe")
        ProjectPackage.read(ByteArrayInputStream(out.toByteArray()), dir)
        assertFalse(java.io.File(tmp.root, "evil.sh").exists())
        assertTrue(dir.listFiles()!!.all { it.parentFile == dir })
    }

    @Test(expected = ProjectPackage.NotAPackage::class)
    fun notAPackage() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("hello.txt")); z.write(1); z.closeEntry() }
        ProjectPackage.read(ByteArrayInputStream(out.toByteArray()), tmp.newFolder("n"))
    }

    @Test fun fileName() {
        assertEquals("Sommar_ 2026.klipp", ProjectPackageName.of("Sommar/ 2026"))
        assertEquals("Klipp.klipp", ProjectPackageName.of("  "))
    }

    private fun pkg(media: List<ProjectPackage.Media>): ByteArray =
        ByteArrayOutputStream().also { ProjectPackage.write(it, "PROJECT".toByteArray(), side, null, media, "0.18.52") }.toByteArray()

    @Test fun aWholePackageChecksOut() {
        val bytes = pkg(listOf(ProjectPackage.Media(3, "a.mp4") { ByteArrayInputStream(ByteArray(200_000) { it.toByte() }) }))
        val v = ProjectPackage.verify(ByteArrayInputStream(bytes))
        assertTrue(v.problem, v.ok)
        assertTrue(v.complete)
        assertEquals(ProjectPackage.FORMAT, v.format)
    }

    @Test fun aCutOffPackageDoesNotCheckOut() {
        val bytes = pkg(listOf(ProjectPackage.Media(3, "a.mp4") { ByteArrayInputStream(ByteArray(200_000) { it.toByte() }) }))
        for (keep in listOf(bytes.size / 2, bytes.size - 30, 100)) {
            assertFalse("cut at $keep", ProjectPackage.verify(ByteArrayInputStream(bytes.copyOf(keep))).ok)
        }
        assertFalse(ProjectPackage.verify(ByteArrayInputStream("not a zip".toByteArray())).ok)
    }

    @Test fun missingMediaMakeThePackageIncomplete() {
        val bytes = pkg(listOf(
            ProjectPackage.Media(3, "a.mp4") { ByteArrayInputStream(ByteArray(1000)) },
            ProjectPackage.Media(5, "gone.mp4") { null },
        ))
        val v = ProjectPackage.verify(ByteArrayInputStream(bytes))
        assertTrue("whole as a file", v.ok)
        assertFalse("but not complete", v.complete)
        assertEquals("the import says so", 1, ProjectPackage.read(ByteArrayInputStream(bytes), tmp.newFolder("mm")).missing)
    }

    @Test fun aPackageFromANewerKlippIsRefused() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, b) in listOf("project.fcproj" to "P", "side.json" to "{}", "klipp.json" to "{\"format\":99,\"media\":[]}")) {
                z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry()
            }
        }
        val v = ProjectPackage.verify(ByteArrayInputStream(out.toByteArray()))
        assertFalse(v.ok)
        assertEquals(99, v.format)
    }

    @Test fun anOlderPackageWithoutSizesStillChecksOut() {
        // format 1 from before 0.18.53: no "size" or "complete" in the manifest
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, b) in listOf("project.fcproj" to "P", "side.json" to "{}", "media/3_a.mp4" to "VIDEO",
                "klipp.json" to "{\"format\":1,\"media\":[{\"item\":3,\"name\":\"a.mp4\",\"file\":\"media/3_a.mp4\"}]}")) {
                z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry()
            }
        }
        val v = ProjectPackage.verify(ByteArrayInputStream(out.toByteArray()))
        assertTrue(v.problem, v.ok)
        assertTrue(v.complete)
    }
}
