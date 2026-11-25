package se.gendut.klipp.engine

/**
 * ONNX Runtime's environment with its telemetry off. The Android package can send usage events (1DS) to its maker;
 * Klipp sends nothing anywhere, so they are turned off on every use – and the package's start-up provider that would
 * prepare them is removed in the manifest.
 */
internal object Ort {
    val env: ai.onnxruntime.OrtEnvironment by lazy {
        ai.onnxruntime.OrtEnvironment.getEnvironment().also { e -> runCatching { e.setTelemetry(false) } }
    }
}
