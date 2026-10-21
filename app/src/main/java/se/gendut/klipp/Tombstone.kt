package se.gendut.klipp

/**
 * Android's native crash report (the tombstone protobuf from ApplicationExitInfo) as readable text: the signal, the
 * cause and the crashing thread's backtrace first, then the other threads that were inside the app's own libraries.
 * Read as plain text the protobuf was mostly unreadable bytes and the crashing thread often fell outside the report.
 */
object Tombstone {
    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (p < end) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if (x < 0x80) break
                s += 7
            }
            return r
        }
        /** (field number, wire type). */
        fun tag(): Pair<Int, Int> = varint().let { (it ushr 3).toInt() to (it and 7).toInt() }
        fun bytes(): Reader { val n = varint().toInt(); val r = Reader(b, p, (p + n).coerceAtMost(end)); p = r.end; return r }
        fun string(): String { val r = bytes(); return String(b, r.p, r.end - r.p, Charsets.UTF_8) }
        fun skip(wire: Int) {
            when (wire) { 0 -> varint(); 1 -> p += 8; 2 -> bytes(); 5 -> p += 4; else -> p = end }
        }
    }

    private class Frame(var relPc: Long = 0, var function: String = "", var offset: Long = 0, var file: String = "")
    private class Thread(var id: Long = 0, var name: String = "", val frames: MutableList<Frame> = ArrayList())

    /** Readable text, or null when [b] is not a tombstone. */
    fun format(b: ByteArray): String? = runCatching {
        val r = Reader(b)
        var tid = 0L
        var signal = ""; var code = ""; var fault: Long? = null
        val causes = ArrayList<String>()
        var abort = ""
        val threads = ArrayList<Thread>()
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 6 && w == 0 -> tid = r.varint()
                f == 10 && w == 2 -> {
                    val s = r.bytes()
                    while (s.more()) {
                        val (g, v) = s.tag()
                        when {
                            g == 2 && v == 2 -> signal = s.string()
                            g == 4 && v == 2 -> code = s.string()
                            g == 9 && v == 0 -> fault = s.varint()
                            else -> s.skip(v)
                        }
                    }
                }
                f == 14 && w == 2 -> abort = r.string()
                f == 15 && w == 2 -> { val c = r.bytes(); while (c.more()) { val (g, v) = c.tag(); if (g == 1 && v == 2) causes += c.string() else c.skip(v) } }
                f == 16 && w == 2 -> {
                    val entry = r.bytes()
                    while (entry.more()) {
                        val (g, v) = entry.tag()
                        if (g == 2 && v == 2) threads += thread(entry.bytes()) else entry.skip(v)
                    }
                }
                else -> r.skip(w)
            }
        }
        if (threads.isEmpty() && signal.isEmpty()) return@runCatching null
        buildString {
            append("Signal: ").append(signal).append(" (").append(code).append(')')
            fault?.let { append(", fault address 0x").append(java.lang.Long.toHexString(it)) }
            append('\n')
            causes.forEach { append("Cause: ").append(it).append('\n') }
            if (abort.isNotBlank()) append("Abort: ").append(abort).append('\n')
            val crashed = threads.firstOrNull { it.id == tid }
            fun dump(t: Thread, max: Int) {
                append("\nThread \"").append(t.name).append("\" (").append(t.id).append(")\n")
                for ((i, fr) in t.frames.take(max).withIndex()) {
                    append("  #").append(i.toString().padStart(2, '0')).append(" pc ").append(java.lang.Long.toHexString(fr.relPc)).append("  ")
                        .append(fr.file.substringAfterLast('/')).append("  ").append(fr.function.ifEmpty { "?" })
                    if (fr.offset > 0) append('+').append(fr.offset)
                    append('\n')
                }
            }
            if (crashed != null) { append("\nCrashing thread:"); dump(crashed, 40) }
            // other threads that were in the app's own native code (speech recognition, the engine)
            val own = threads.filter { t -> t !== crashed && t.frames.any { it.file.contains("/data/app/") } }
            if (own.isNotEmpty()) { append("\nThreads in the app's own libraries:"); own.take(6).forEach { dump(it, 14) } }
        }
    }.getOrNull()

    private fun thread(r: Reader): Thread {
        val t = Thread()
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 0 -> t.id = r.varint()
                f == 2 && w == 2 -> t.name = r.string()
                f == 4 && w == 2 -> {
                    val s = r.bytes(); val fr = Frame()
                    while (s.more()) {
                        val (g, v) = s.tag()
                        when {
                            g == 1 && v == 0 -> fr.relPc = s.varint()
                            g == 4 && v == 2 -> fr.function = s.string()
                            g == 5 && v == 0 -> fr.offset = s.varint()
                            g == 6 && v == 2 -> fr.file = s.string()
                            else -> s.skip(v)
                        }
                    }
                    t.frames += fr
                }
                else -> r.skip(w)
            }
        }
        return t
    }
}
