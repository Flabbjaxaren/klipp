package se.gendut.klipp.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Backups to a folder the user picked once (Storage Access Framework). Each backup is a NEW file in it (the project's
 * name and the time): nothing is renamed or replaced, so an interrupted backup never touches an earlier one – document
 * providers differ in what they can rename or swap atomically, and none of that is assumed.
 *
 * What was backed up when is kept here (not in the project), with a fingerprint of the project's content, so the
 * projects page can say whether the project has changed since.
 */
object Backups {
    private fun prefs(c: Context) = c.getSharedPreferences("backups", Context.MODE_PRIVATE)

    /** The chosen folder (a tree URI), or null. */
    fun folder(c: Context): Uri? = prefs(c).getString("folder", null)?.let(Uri::parse)

    /** Remembers [tree] and keeps the permission to it across restarts, where the provider allows that. */
    fun setFolder(c: Context, tree: Uri) {
        runCatching { c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs(c).edit().putString("folder", tree.toString()).apply()
    }

    /** Forgets the folder (its permission is gone): the next backup asks for one again. */
    fun clearFolder(c: Context) {
        folder(c)?.let { t -> runCatching { c.contentResolver.releasePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs(c).edit().remove("folder").apply()
    }

    /** The folder's name as the provider shows it ("Klipp", "Download" …). */
    fun folderName(c: Context, tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        c.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: tree.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "…"

    /** A new, empty file [name] in the folder. Throws (SecurityException …) when the folder can't be reached any more. */
    fun createIn(c: Context, tree: Uri, name: String): Uri {
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(c.contentResolver, dir, "application/octet-stream", name)
            ?: throw java.io.IOException("the folder refused a new file")
    }

    /** The last backup of a project: when, its file's name, the project's fingerprint then, and whether all media were in it. */
    data class Last(val time: Long, val file: String, val fingerprint: String, val complete: Boolean)

    fun last(c: Context, id: String): Last? = prefs(c).getString("p:$id", null)?.let { s ->
        runCatching { JSONObject(s).let { Last(it.getLong("time"), it.optString("file"), it.optString("fp"), it.optBoolean("complete", true)) } }.getOrNull()
    }

    fun record(c: Context, id: String, last: Last) {
        prefs(c).edit().putString("p:$id", JSONObject().put("time", last.time).put("file", last.file).put("fp", last.fingerprint).put("complete", last.complete).toString()).apply()
    }

    fun forget(c: Context, id: String) { prefs(c).edit().remove("p:$id").apply() }

    /**
     * The project's content as a fingerprint: the engine's project file and the side file without its "meta" (the
     * name and the time of the last save change on every save, even without an edit).
     */
    fun fingerprint(project: File, side: JSONObject?): String {
        val md = MessageDigest.getInstance("SHA-256")
        if (project.exists()) project.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        side?.let { s -> md.update(JSONObject(s.toString()).apply { remove("meta") }.toString().toByteArray(Charsets.UTF_8)) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /** What to say about a project's backups. */
    enum class Status { NEVER, CURRENT, CHANGED }

    fun status(last: Last?, fingerprintNow: String): Status = when {
        last == null -> Status.NEVER
        last.fingerprint == fingerprintNow -> Status.CURRENT
        else -> Status.CHANGED
    }
}
