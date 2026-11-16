package se.gendut.klipp.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The CPU features the speech library is built for (see src/main/cpp/CMakeLists.txt: armv8.2-a+dotprod+fp16, which
 * also brings the v8.1 atomics). A phone without them (ARMv8.0 cores: Cortex-A53/A57/A72/A73) would stop with SIGILL –
 * the process killed, nothing to catch – on the first transcription, so the library is not loaded there and speech is
 * reported as unavailable instead.
 *
 * Read from the kernel's hardware capabilities (AT_HWCAP in /proc/self/auxv), /proc/cpuinfo's Features if that can't
 * be read. Pure apart from the two files; the parsers are tested.
 */
object CpuFeatures {
    private const val AT_HWCAP = 16L
    // arm64 HWCAP bits (linux/arch/arm64/include/uapi/asm/hwcap.h)
    private const val HWCAP_ATOMICS = 1L shl 8
    private const val HWCAP_FPHP = 1L shl 9
    private const val HWCAP_ASIMDHP = 1L shl 10
    private const val HWCAP_ASIMDDP = 1L shl 20
    private const val SPEECH_BITS = HWCAP_ATOMICS or HWCAP_FPHP or HWCAP_ASIMDHP or HWCAP_ASIMDDP
    private val SPEECH_FEATURES = listOf("atomics", "fphp", "asimdhp", "asimddp")

    /** AT_HWCAP from the auxiliary vector's bytes (64-bit key/value pairs, little-endian), null if it isn't there. */
    fun hwcapOf(auxv: ByteArray): Long? {
        val b = ByteBuffer.wrap(auxv).order(ByteOrder.LITTLE_ENDIAN)
        while (b.remaining() >= 16) {
            val key = b.long
            val value = b.long
            if (key == 0L) return null
            if (key == AT_HWCAP) return value
        }
        return null
    }

    fun speechOk(hwcap: Long): Boolean = hwcap and SPEECH_BITS == SPEECH_BITS

    /** From /proc/cpuinfo's text: every core's Features line has them (null = no Features line). */
    fun speechOkFromCpuinfo(text: String): Boolean? {
        val lines = text.lineSequence().filter { it.trimStart().startsWith("Features") }.toList()
        if (lines.isEmpty()) return null
        return lines.all { line -> line.substringAfter(':').trim().split(Regex("\\s+")).toSet().containsAll(SPEECH_FEATURES) }
    }

    /**
     * Whether this phone runs the speech library. Neither file readable: true (as before this check – the library is
     * built for every Snapdragon 8 since the 855, and the kernel always gives an app its own auxv).
     */
    val speech: Boolean by lazy {
        runCatching { hwcapOf(File("/proc/self/auxv").readBytes())?.let(::speechOk) }.getOrNull()
            ?: runCatching { speechOkFromCpuinfo(File("/proc/cpuinfo").readText()) }.getOrNull()
            ?: true
    }
}
