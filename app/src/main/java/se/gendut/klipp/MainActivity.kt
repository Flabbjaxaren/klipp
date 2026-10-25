package se.gendut.klipp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import se.gendut.klipp.engine.ProjectController

class MainActivity : ComponentActivity() {
    /** So we can save right away when the app goes to the background (the controller is created in setContent). */
    private var controller: ProjectController? = null

    override fun onDestroy() {
        if (McpBridge.activity === this) McpBridge.activity = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // save changes now – the process may be killed in the background before a delayed save has run
        controller?.saveNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        se.gendut.klipp.engine.Perf.init(applicationContext)
        BuildConfigShim.version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        CrashReport.install(applicationContext)
        CrashReport.note("start ${BuildConfigShim.version}")
        // video pipeline settings (float, 10-bit surface) before the player
        se.gendut.klipp.engine.VideoPipeline.init(applicationContext)
        se.gendut.klipp.engine.Proxies.init(applicationContext)
        se.gendut.klipp.engine.NodeCache.init(applicationContext)
        Str.init(applicationContext)
        se.gendut.klipp.engine.FrameHolds.init(applicationContext)
        se.gendut.klipp.engine.Reversals.init(applicationContext)
        se.gendut.klipp.engine.RenderCache.init(applicationContext)
        se.gendut.klipp.engine.Fonts.init(applicationContext)
        se.gendut.klipp.engine.WindowTracker.density = AppPrefs.trackDensity(applicationContext)
        se.gendut.klipp.engine.Spektra.init(applicationContext)
        // theme and haptics from settings (before anything is drawn)
        AppPrefs.apply(applicationContext)
        // the phone's encoders (export options) are read in the background – the export dialog then opens instantly
        Thread({ runCatching { se.gendut.klipp.engine.EncoderCaps.codecs; se.gendut.klipp.engine.EncoderCaps.hdrSupported } }, "encoders").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        val crash = CrashReport.takePending(applicationContext)
        McpBridge.activity = this
        McpBridge.start()
        enableEdgeToEdge()
        setContent {
            KlippTheme {
                val toaster = remember { Toaster() }
                val scope = rememberCoroutineScope()
                val state = remember {
                    EditorState(emptyList()).apply {
                        playhead = 0f
                        markers = emptyList()
                        subtitle = Str.t(R.string.mainactivity_no_media_yet)
                    }
                }
                val controller = remember {
                    runCatching { ProjectController(applicationContext, state, scope) { toaster.show(it) } }.getOrNull()
                }
                LaunchedEffect(controller) {
                    if (controller == null) toaster.show(Str.t(R.string.mainactivity_engine_failed))
                }
                val ui = remember { AppUi() }
                DisposableEffect(controller) {
                    this@MainActivity.controller = controller
                    McpBridge.attach(state, ui, controller)
                    onDispose {
                        if (this@MainActivity.controller === controller) this@MainActivity.controller = null
                        controller?.close()
                    }
                }
                if (controller != null) KlippApp(state, ui, controller = controller, toaster = toaster)
                else KlippApp(ui = ui, toaster = toaster)
                var report by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(crash) }
                report?.let { r ->
                    CrashDialog(r, onCopy = {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText(Str.t(R.string.mainactivity_crash_clip_label), r))
                        toaster.show(Str.t(R.string.mainactivity_crash_copied))
                        report = null
                    }, onDismiss = { report = null })
                }
            }
        }
    }
}
