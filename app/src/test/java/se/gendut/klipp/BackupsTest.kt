package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Backups

/** Backups: the fingerprint follows the content (not the save time), and what is recorded comes back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aSaveWithoutAnEditIsNoChange() {
        val f = tmp.newFile("p.fcproj").apply { writeText("PROJECT") }
        val side = JSONObject().put("media", org.json.JSONArray()).put("meta", JSONObject().put("modified", 1L))
        val a = Backups.fingerprint(f, side)
        assertEquals("only the save time differs", a, Backups.fingerprint(f, JSONObject(side.toString()).put("meta", JSONObject().put("modified", 2L))))
        f.writeText("PROJECT2")
        assertNotEquals("an edit", a, Backups.fingerprint(f, side))
        assertNotEquals("a grade", a, Backups.fingerprint(tmp.newFile("q.fcproj").apply { writeText("PROJECT") }, JSONObject(side.toString()).put("extra", JSONObject().put("g", 1))))
    }

    @Test fun theLastBackupIsRemembered() {
        val c = RuntimeEnvironment.getApplication()
        assertEquals(Backups.Status.NEVER, Backups.status(Backups.last(c, "p9"), "x"))
        Backups.record(c, "p9", Backups.Last(123L, "Sommar 2026-11-11 1420.klipp", "fp", complete = false))
        val l = Backups.last(c, "p9")!!
        assertEquals(123L, l.time)
        assertEquals(false, l.complete)
        assertEquals(Backups.Status.CURRENT, Backups.status(l, "fp"))
        assertEquals(Backups.Status.CHANGED, Backups.status(l, "other"))
        Backups.forget(c, "p9")
        assertEquals(null, Backups.last(c, "p9"))
    }
}
