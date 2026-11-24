package se.gendut.klipp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.McrawStore
import se.gendut.klipp.engine.OpenDrt
import se.gendut.klipp.engine.Placeholder
import se.gendut.klipp.engine.Proxies

/**
 * The picked files with every MotionCam .mcraw developed into a log clip in its place, in the same order – into the
 * app's own storage ([McrawStore]), at the picker's EI and output; the .mcraw stays the source. [onEach] gets the files
 * to import as they become ready (all at once when there is no .mcraw). Every .mcraw first goes in as a [Placeholder]
 * (its middle frame, its length, silent sound) that its develop replaces when it is ready, so the editor is usable from
 * the first second; when placeholders can't be made, the files go in as their develops are done. A file that fails to
 * develop is left out, with the reason shown for a moment. [status] shows the progress (null-free text). Suspends while
 * the develops run.
 */
suspend fun importMcraw(ctx: Context, picked: List<Uri>, onEach: (List<Uri>) -> Unit, status: (String) -> Unit) {
    val names = withContext(Dispatchers.IO) {
        picked.map { u ->
            runCatching {
                ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: u.lastPathSegment.orEmpty()
        }
    }
    if (names.none { isMcraw(it) }) { onEach(picked); return }
    // proxies wait while the develops run (they share the reads, the GPU and the encoder)
    McrawStore.importing = true
    Proxies.hold(true)
    try { importAll(ctx, picked, names, onEach, status) } finally {
        McrawStore.importing = false
        if (McrawStore.drafts == 0) Proxies.hold(false)
        // (a placeholder left by a develop that failed: tried again now)
        withContext(Dispatchers.Main) { McrawStore.kick?.invoke() }
    }
}

private fun isMcraw(name: String) = name.endsWith(".mcraw", ignoreCase = true)

private suspend fun importAll(ctx: Context, picked: List<Uri>, names: List<String>, onEach: (List<Uri>) -> Unit, status: (String) -> Unit) {
    val store = McrawStore
    val quick = McrawFolder.quick(ctx)
    val outKey = McrawFolder.out(ctx)
    val ei = McrawFolder.ei(ctx)
    val outName = OpenDrt.Input.of(outKey).label
    fun progressText(name: String, p: Float, left: Int) =
        Str.t(if (quick) R.string.mcraw_developing_quick else R.string.mcraw_developing_progress, name, outName, (p * 100).toInt(), left)

    val raw = picked.indices.filter { isMcraw(names[it]) }
    status(Str.p(R.plurals.mcraw_preparing, raw.size, raw.size))
    val ph = withContext(Dispatchers.IO) {
        raw.associateWith { i -> Placeholder.make(ctx, picked[i], names[i], quick)?.also { store.notePlaceholder(ctx, it, picked[i], names[i], ei, outKey) } }
    }
    if (ph.values.all { it != null }) {
        // every RAW clip goes on the timeline at once as its placeholder; each develop takes its placeholder's place
        onEach(picked.mapIndexed { i, u -> ph[i] ?: u })
        for ((k, i) in raw.withIndex()) {
            val u = picked[i]; val name = names[i]; val p = ph.getValue(i)!!
            val left = raw.size - k
            status(Str.t(R.string.mcraw_developing, name, outName, left))
            // (the status is Compose state: setting it from the develop's thread is fine)
            val r = withContext(Dispatchers.Default) { store.develop(ctx, u, name, ei, outKey, draft = quick) { pr -> status(progressText(name, pr, left)) } }
            // the placeholder must be in the project before it can be replaced (its import runs on its own)
            var waited = 0
            while (waited < 20_000 && withContext(Dispatchers.Main) { store.has?.invoke(p) } == false) { delay(100); waited += 100 }
            // (not developed: the placeholder stays – it is noted, and developed again once the import is done)
            if (r.uri != null) withContext(Dispatchers.Main) { store.relink?.invoke(p, r.uri) }
            if (r.uri == null) { status(Str.t(R.string.mcraw_not_developed, name, r.summary.lineSequence().firstOrNull().orEmpty())); delay(2500) }
        }
        return
    }
    // (some couldn't be made: the ones that were go, and the files go in as their develops are done)
    withContext(Dispatchers.IO) { ph.values.forEach { it?.path?.let { f -> java.io.File(f).delete() } } }
    // files ready but not handed to [onEach] yet (the ones that need no develop, until the next .mcraw); the first clip
    // goes in at once, the next ones together every ~10 s: each import redraws the timeline and the project, and one at a
    // time kept the screen busy
    val pending = ArrayList<Uri>()
    var flushed = false
    var lastFlush = System.nanoTime()
    fun flush() { if (pending.isNotEmpty()) { onEach(pending.toList()); pending.clear(); flushed = true; lastFlush = System.nanoTime() } }
    for ((i, u) in picked.withIndex()) {
        val name = names[i]
        if (!isMcraw(name)) { pending += u; continue }
        val left = picked.size - i
        status(Str.t(R.string.mcraw_developing, name, outName, left))
        val r = withContext(Dispatchers.Default) { store.develop(ctx, u, name, ei, outKey, draft = quick) { p -> status(progressText(name, p, left)) } }
        if (r.uri != null) {
            pending += r.uri
            if (!flushed || System.nanoTime() - lastFlush > 10_000_000_000L) flush()
            // its proxy right away (playing the 4K 60 fps develop itself freezes the picture); a quick develop is half
            // size, light enough to play itself: its full develop gets the proxy
            if (!quick) Proxies.request(ctx, r.uri)
        } else { status(Str.t(R.string.mcraw_not_developed, name, r.summary.lineSequence().firstOrNull().orEmpty())); delay(2500) }
    }
    flush()
}
