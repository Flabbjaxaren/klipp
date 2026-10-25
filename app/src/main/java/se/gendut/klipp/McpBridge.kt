package se.gendut.klipp

import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import se.gendut.klipp.engine.ProjectController
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A small MCP server inside the app, so an agent (Claude Code on the computer) can see and drive Klipp over adb:
 *
 *     adb forward tcp:8765 localabstract:klipp-mcp
 *     claude mcp add --transport http klipp http://127.0.0.1:8765/mcp
 *
 * It listens on an abstract local socket (never the network) and only answers adb – the shell user, who can do
 * all of this on the phone anyway; other apps on the phone are turned away. Streamable HTTP with plain JSON
 * answers (no event stream): initialize, tools/list, tools/call.
 */
object McpBridge {
    const val SOCKET = "klipp-mcp"
    private const val SHELL_UID = 2000

    @Volatile var activity: ComponentActivity? = null
    @Volatile private var state: EditorState? = null
    @Volatile private var ui: AppUi? = null
    @Volatile private var controller: ProjectController? = null
    private var started = false

    fun attach(state: EditorState, ui: AppUi, controller: ProjectController?) {
        this.state = state; this.ui = ui; this.controller = controller
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        Thread({
            val server = runCatching { LocalServerSocket(SOCKET) }.getOrElse { CrashReport.note("mcp: no socket: ${it.message}"); return@Thread }
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: continue
                val uid = runCatching { s.peerCredentials.uid }.getOrDefault(-1)
                if (uid != SHELL_UID && uid != Process.myUid()) { runCatching { s.close() }; continue }
                Thread({ serve(s) }, "mcp-conn").apply { isDaemon = true }.start()
            }
        }, "mcp").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ HTTP (just enough of it)

    private fun serve(s: LocalSocket) = runCatching {
        s.use {
            val input = BufferedInputStream(s.inputStream)
            val out = s.outputStream
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) continue
                val method = line.substringBefore(' ')
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(input) ?: return@use
                    if (h.isEmpty()) break
                    headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len).also { b -> var r = 0; while (r < len) { val n = input.read(b, r, len - r); if (n < 0) break; r += n } }
                when (method) {
                    "POST" -> {
                        val text = String(body, Charsets.UTF_8).trim()
                        val reply: String? = if (text.startsWith("[")) {
                            val arr = JSONArray(text)
                            val outs = JSONArray()
                            for (i in 0 until arr.length()) handle(arr.getJSONObject(i))?.let { outs.put(it) }
                            if (outs.length() == 0) null else outs.toString()
                        } else handle(JSONObject(text))?.toString()
                        if (reply == null) respond(out, 202, "Accepted", null) else respond(out, 200, "OK", reply)
                    }
                    "DELETE" -> respond(out, 200, "OK", null)
                    else -> respond(out, 405, "Method Not Allowed", null)
                }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.UTF_8.name())
            if (c == '\n'.code) return b.toString(Charsets.UTF_8.name()).trimEnd('\r')
            b.write(c)
        }
    }

    private fun respond(out: OutputStream, code: Int, reason: String, json: String?) {
        val body = json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            if (json != null) append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\nConnection: keep-alive\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
    }

    // ------------------------------------------------------------------ JSON-RPC / MCP

    private fun handle(msg: JSONObject): JSONObject? {
        if (!msg.has("id")) return null // a notification
        val id = msg.get("id")
        val params = msg.optJSONObject("params") ?: JSONObject()
        fun ok(r: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r)
        return when (msg.optString("method")) {
            "initialize" -> ok(JSONObject()
                .put("protocolVersion", params.optString("protocolVersion", "2025-06-18"))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "klipp").put("version", BuildConfigShim.version))
                .put("instructions", "Klipp, the video editor on the phone. Read with app_state/timeline/ui_nodes/screenshot; edit through the " +
                    "engine (engine_commands lists them, engine_exec runs one – one undo step each); touch with tap/drag (window pixels)."))
            "ping" -> ok(JSONObject())
            "tools/list" -> ok(JSONObject().put("tools", JSONArray(TOOLS.map { it.spec() })))
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val tool = TOOLS.firstOrNull { it.name == name }
                val content = runCatching { tool?.run?.invoke(args) ?: error("no tool $name") }
                ok(content.fold(
                    { JSONObject().put("content", it) },
                    { e -> JSONObject().put("isError", true).put("content", JSONArray().put(text("${e.javaClass.simpleName}: ${e.message}"))) },
                ))
            }
            else -> JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32601).put("message", "unknown method ${msg.optString("method")}"))
        }
    }

    private class Tool(val name: String, val description: String, val props: JSONObject = JSONObject(), val required: List<String> = emptyList(),
                       val run: (JSONObject) -> JSONArray) {
        fun spec(): JSONObject = JSONObject().put("name", name).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
    }

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)
    private fun text(s: String) = JSONObject().put("type", "text").put("text", s)
    private fun texts(s: String) = JSONArray().put(text(s))
    private fun ctl() = controller ?: error("the engine isn't running (prototype mode)")
    private fun st() = state ?: error("the app isn't showing yet")

    /** [block] on the main thread, waited for (up to 10 s). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { result = runCatching(block); done.countDown() }
        if (!done.await(10, TimeUnit.SECONDS)) error("the app's main thread didn't answer in 10 s")
        return result!!.getOrThrow()
    }

    private val MODE_NAMES = listOf("edit", "color", "audio", "text")

    private val TOOLS: List<Tool> by lazy { listOf(
        Tool("app_state", "Version, mode (edit/color/audio/text), playhead and playing, the selection, whether Properties is open.") {
            val s = st()
            val o = onMain {
                JSONObject().put("version", BuildConfigShim.version)
                    .put("mode", MODE_NAMES.getOrNull(ui?.page?.value?.let { kotlin.math.round(it).toInt() } ?: 0))
                    .put("playhead", s.playhead.toDouble()).put("playing", s.playing)
                    .put("selected", s.selected).put("multi", JSONArray(s.multi.toList())).put("inspecting", s.inspecting)
                    .put("projectEnd", s.projectEnd.toDouble())
            }
            texts(o.toString(2))
        },
        Tool("timeline", "The engine's project state as JSON: the active sequence (tracks, clips, transitions, captions, markers; " +
            "times in seconds). media=true adds the media pool.", JSONObject().put("media", prop("boolean", "include the media pool"))) { a ->
            val o = JSONObject(runBlocking { ctl().engine.snapshotJson() })
            if (!a.optBoolean("media")) { o.remove("media"); o.remove("bins") }
            texts(o.toString())
        },
        Tool("engine_commands", "The engine's commands: id, label and parameters. filter = part of the id or label.",
            JSONObject().put("filter", prop("string", "only commands whose id or label contains this"))) { a ->
            val r = runBlocking { ctl().engine.exec("command.list") }
            val list = r.optJSONArray("commands") ?: r.optJSONArray("value") ?: JSONArray()
            val f = a.optString("filter").lowercase()
            val out = StringBuilder()
            for (i in 0 until list.length()) {
                val c = list.optJSONObject(i) ?: continue
                val id = c.optString("id"); val label = c.optString("label")
                if (f.isNotEmpty() && f !in id.lowercase() && f !in label.lowercase()) continue
                out.append(id).append(" – ").append(label).append("  ").append(c.optString("params")).append('\n')
            }
            texts(out.toString().ifEmpty { if (f.isEmpty()) r.toString() else "no command matches \"$f\"" })
        },
        Tool("engine_exec", "Run an engine command (as the app's own edits do: one undo step, the timeline follows, the project is saved).",
            JSONObject().put("command", prop("string", "command id, e.g. timeline.split")).put("params", prop("object", "its parameters")),
            listOf("command")) { a ->
            val r = runBlocking(Dispatchers.Main) { ctl().execExternal(a.getString("command"), a.optJSONObject("params") ?: JSONObject()) }
            texts(r.toString())
        },
        Tool("screenshot", "The app's window as a JPEG (the video surfaces included). scale: 0.1–1 (default 0.5).",
            JSONObject().put("scale", prop("number", "size relative to the screen"))) { a -> JSONArray().put(shot(a.optDouble("scale", 0.5))) },
        Tool("ui_nodes", "What is on screen: elements with a test tag, text or description and their bounds in window pixels (for tap/drag). " +
                "The topmost window (dialog, sheet) comes first; a full-screen page can cover elements still listed after it.",
            JSONObject().put("filter", prop("string", "only elements whose tag/text/description contains this"))) { a ->
            texts(nodes(a.optString("filter")).joinToString("\n") { n -> "${n.label}  [${n.l},${n.t},${n.r},${n.b}]" }.ifEmpty { "nothing matches" })
        },
        Tool("tap", "Tap at window pixel (x, y), or on an element by test tag or text (node).",
            JSONObject().put("x", prop("number", "")).put("y", prop("number", "")).put("node", prop("string", "test tag or text"))) { a ->
            val (x, y) = point(a, "x", "y", "node")
            gesture(listOf(x to y, x to y), 60, 0, false)
            texts("tapped ($x, $y)")
        },
        Tool("drag", "A finger from (x1, y1) to (x2, y2) over duration_ms, held hold_ms before lifting. shot_while_held=true returns a " +
            "screenshot taken while the finger is still down (e.g. a magnifier). press_ms: rest before moving (a long press).",
            JSONObject().put("x1", prop("number", "")).put("y1", prop("number", "")).put("x2", prop("number", "")).put("y2", prop("number", ""))
                .put("from_node", prop("string", "start on this element instead of (x1, y1)"))
                .put("duration_ms", prop("number", "default 400")).put("press_ms", prop("number", "default 0"))
                .put("hold_ms", prop("number", "default 0")).put("shot_while_held", prop("boolean", "")).put("scale", prop("number", "screenshot scale")),
            listOf("x2", "y2")) { a ->
            val (x1, y1) = point(a, "x1", "y1", "from_node")
            val x2 = a.getDouble("x2").toFloat(); val y2 = a.getDouble("y2").toFloat()
            val dur = a.optLong("duration_ms", 400).coerceIn(16, 10_000)
            val steps = (dur / 16).toInt().coerceAtLeast(1)
            val path = ArrayList<Pair<Float, Float>>()
            path += x1 to y1
            val press = a.optLong("press_ms", 0)
            if (press > 0) repeat((press / 16).toInt()) { path += x1 to y1 }
            for (i in 1..steps) path += (x1 + (x2 - x1) * i / steps) to (y1 + (y2 - y1) * i / steps)
            val shot = gesture(path, 16, a.optLong("hold_ms", 0), a.optBoolean("shot_while_held"), a.optDouble("scale", 0.5))
            JSONArray().put(text("dragged ($x1, $y1) → ($x2, $y2)")).also { if (shot != null) it.put(shot) }
        },
        Tool("pinch", "Two fingers around (x, y), from span1 to span2 pixels apart (horizontally) over duration_ms: pinch zoom.",
            JSONObject().put("x", prop("number", "")).put("y", prop("number", "")).put("span1", prop("number", ""))
                .put("span2", prop("number", "")).put("duration_ms", prop("number", "default 500")),
            listOf("x", "y", "span1", "span2")) { a ->
            pinch(a.getDouble("x").toFloat(), a.getDouble("y").toFloat(), a.getDouble("span1").toFloat(), a.getDouble("span2").toFloat(),
                a.optLong("duration_ms", 500).coerceIn(50, 5000))
            texts("pinched")
        },
        Tool("set_playhead", "Stop and move the playhead to a time (seconds).", JSONObject().put("seconds", prop("number", "")), listOf("seconds")) { a ->
            val t = a.getDouble("seconds").toFloat()
            onMain { st().playing = false; st().playhead = t }
            texts("playhead at $t s")
        },
        Tool("set_mode", "Switch mode: edit, color, audio or text.", JSONObject().put("mode", prop("string", "edit | color | audio | text")), listOf("mode")) { a ->
            val i = MODE_NAMES.indexOf(a.getString("mode").lowercase()).also { require(it >= 0) { "mode is one of $MODE_NAMES" } }
            val page = ui?.page ?: error("no app")
            runBlocking(Dispatchers.Main) { page.snapTo(i.toFloat()) }
            texts("mode ${MODE_NAMES[i]}")
        },
        Tool("select", "Select a clip by id (null clears the selection); inspect=true opens its Properties.",
            JSONObject().put("clip", prop("number", "clip id")).put("inspect", prop("boolean", ""))) { a ->
            val id = if (a.isNull("clip")) null else a.optLong("clip")
            onMain { st().selected = id; if (id == null) st().multi = emptySet(); st().inspecting = id != null && a.optBoolean("inspect") }
            texts("selected $id")
        },
        Tool("logs", "The app's recent event trail (the same as in crash reports).") { texts(CrashReport.trail().joinToString("\n")) },
    ) }

    // ------------------------------------------------------------------ screen

    private fun window() = (activity ?: error("the app isn't open")).window

    /** The app's windows, bottom first: the activity, then dialogs, sheets and popups over it. */
    private fun roots(): List<View> = onMain {
        val main = window().decorView
        val all = if (android.os.Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews() else listOf(main)
        listOf(main) + all.filter { it !== main && it.isShown && it.width > 0 }
    }

    private fun View.screenPos(): IntArray = IntArray(2).also { getLocationOnScreen(it) }

    private fun shot(scale: Double): JSONObject {
        val w = window()
        val view = onMain { w.decorView }
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var res = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(w, bmp, { r -> res = r; done.countDown() }, Handler(Looper.getMainLooper()))
        if (!done.await(5, TimeUnit.SECONDS) || res != PixelCopy.SUCCESS) error("the screenshot failed ($res)")
        // dialogs and sheets are windows of their own: drawn over it where they are (they hold no video surfaces)
        onMain {
            val base = view.screenPos()
            val canvas = android.graphics.Canvas(bmp)
            for (r in roots().drop(1)) {
                val p = r.screenPos()
                canvas.save(); canvas.translate((p[0] - base[0]).toFloat(), (p[1] - base[1]).toFloat()); r.draw(canvas); canvas.restore()
            }
        }
        val k = scale.coerceIn(0.1, 1.0)
        val small = if (k < 0.999) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    private class Node(val label: String, val l: Int, val t: Int, val r: Int, val b: Int)

    /** The Compose semantics on screen (unmerged, so every test tag shows). */
    private fun nodes(filter: String): List<Node> = onMain {
        val base = window().decorView.screenPos()
        val out = ArrayList<Node>()
        var dx = 0; var dy = 0
        fun walk(n: SemanticsNode) {
            val c = n.config
            val parts = listOfNotNull(
                c.getOrNull(SemanticsProperties.TestTag)?.let { "#$it" },
                c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }?.let { "\"$it\"" },
                c.getOrNull(SemanticsProperties.EditableText)?.text?.let { "edit:\"$it\"" },
                c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.let { "desc:$it" },
            )
            if (parts.isNotEmpty()) {
                val label = parts.joinToString(" ")
                val bounds = n.boundsInWindow
                if ((filter.isEmpty() || label.contains(filter, ignoreCase = true)) && bounds.width > 0f && bounds.height > 0f)
                    out += Node(label, bounds.left.toInt() + dx, bounds.top.toInt() + dy, bounds.right.toInt() + dx, bounds.bottom.toInt() + dy)
            }
            n.children.forEach(::walk)
        }
        // the topmost window first (a dialog's buttons before what lies under it)
        for (w in roots().reversed()) {
            val p = w.screenPos(); dx = p[0] - base[0]; dy = p[1] - base[1]
            val composes = ArrayList<RootForTest>()
            fun find(v: View) { if (v is RootForTest) composes += v; if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i)) }
            find(w)
            for (r in composes) walk(r.semanticsOwner.unmergedRootSemanticsNode)
        }
        out.take(500)
    }

    /** (x, y) from the arguments, or the middle of the element named by [nodeKey] (exact tag first, then text). */
    private fun point(a: JSONObject, xKey: String, yKey: String, nodeKey: String): Pair<Float, Float> {
        val name = a.optString(nodeKey)
        if (name.isEmpty()) return a.getDouble(xKey).toFloat() to a.getDouble(yKey).toFloat()
        val all = nodes(name)
        val n = all.firstOrNull { it.label.split(' ').first() == "#$name" } ?: all.firstOrNull() ?: error("no element \"$name\" on screen")
        return (n.l + n.r) / 2f to (n.t + n.b) / 2f
    }

    private fun pinch(cx: Float, cy: Float, span1: Float, span2: Float, durMs: Long) {
        val view = roots().last()
        val off = onMain { val b = window().decorView.screenPos(); val p = view.screenPos(); (p[0] - b[0]).toFloat() to (p[1] - b[1]).toFloat() }
        val down = SystemClock.uptimeMillis()
        val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun send(action: Int, span: Float, count: Int) = onMain {
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                x = cx + (if (i == 0) -span / 2 else span / 2) - off.first; y = cy - off.second; pressure = 1f; size = 1f } }
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props.copyOf(count), coords, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            view.dispatchTouchEvent(e).also { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, span1, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), span1, 2)
        val steps = (durMs / 16).toInt().coerceAtLeast(1)
        for (i in 1..steps) { Thread.sleep(16); send(MotionEvent.ACTION_MOVE, span1 + (span2 - span1) * i / steps, 2) }
        Thread.sleep(50)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), span2, 2)
        send(MotionEvent.ACTION_UP, span2, 1)
    }

    /** One finger along [path] (a point every [stepMs]), held [holdMs] at the end; a screenshot before lifting if asked. */
    private fun gesture(path: List<Pair<Float, Float>>, stepMs: Long, holdMs: Long, shotHeld: Boolean, scale: Double = 0.5): JSONObject? {
        // the topmost window gets the finger (a dialog over the app catches touches outside itself too)
        val view = roots().last()
        val off = onMain { val b = window().decorView.screenPos(); val p = view.screenPos(); (p[0] - b[0]).toFloat() to (p[1] - b[1]).toFloat() }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) = onMain {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x - off.first, y - off.second, 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(e).also { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, path.first().first, path.first().second)
        for (p in path.drop(1)) { Thread.sleep(stepMs); send(MotionEvent.ACTION_MOVE, p.first, p.second) }
        if (holdMs > 0) Thread.sleep(holdMs)
        val shot = if (shotHeld) { Thread.sleep(80); shot(scale) } else null
        send(MotionEvent.ACTION_UP, path.last().first, path.last().second)
        return shot
    }
}
