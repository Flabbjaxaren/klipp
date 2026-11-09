package se.gendut.klipp.engine

/** When each word of a caption is spoken: from the transcript where the words match, otherwise spread by their length. */
object CaptionWords {
    private val space = Regex("\\s+")
    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * Each word of [text] as (start, end) in timeline seconds. They cover [start]–[end] without gaps, so the marking
     * stays on a word until the next one begins. [words]: the transcript on the timeline.
     */
    fun spans(text: String, start: Double, end: Double, words: List<SpeechLine>): List<Pair<Double, Double>> {
        val tokens = text.trim().split(space).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val heard = words.filter { (it.start + it.end) / 2 in start..end }.sortedBy { it.start }
        val at = arrayOfNulls<Double>(tokens.size + 1)
        if (heard.size == tokens.size) heard.forEachIndexed { i, w -> at[i] = w.start }
        else {
            // edited text: the words still found keep their times (looking a few words ahead), the others are spread between them
            var j = 0
            for ((i, t) in tokens.withIndex()) {
                val k = (j until minOf(j + 4, heard.size)).firstOrNull { norm(heard[it].text) == norm(t) } ?: continue
                at[i] = heard[k].start
                j = k + 1
            }
        }
        at[0] = start
        at[tokens.size] = end
        var a = 0
        while (a < tokens.size) {
            var b = a + 1
            while (at[b] == null) b++
            val t0 = at[a]!!.coerceIn(start, end)
            val t1 = at[b]!!.coerceIn(t0, end)
            val total = (a until b).sumOf { tokens[it].length.coerceAtLeast(1) }.toDouble()
            var used = 0
            for (i in a until b) { at[i] = t0 + (t1 - t0) * used / total; used += tokens[i].length.coerceAtLeast(1) }
            at[b] = t1
            a = b
        }
        return tokens.indices.map { at[it]!! to at[it + 1]!! }
    }

    /** One state of a marked caption: [text] is on screen from [start] to [end] with its word number [word] marked. */
    data class Step(val start: Double, val end: Double, val text: String, val word: Int)

    /** The caption word by word, showing [perPage] words at a time (0 = all of them). */
    fun steps(text: String, start: Double, end: Double, words: List<SpeechLine>, perPage: Int): List<Step> {
        val s = spans(text, start, end, words)
        if (perPage <= 0) return s.mapIndexed { k, (a, b) -> Step(a, b, text, k) }
        val tokens = text.trim().split(space).filter { it.isNotEmpty() }
        return s.mapIndexed { k, (a, b) ->
            val page = k / perPage * perPage
            Step(a, b, tokens.subList(page, minOf(page + perPage, tokens.size)).joinToString(" "), k - page)
        }
    }

    /** What is on screen at [t], null outside the caption. */
    fun stepAt(text: String, start: Double, end: Double, words: List<SpeechLine>, perPage: Int, t: Double): Step? =
        steps(text, start, end, words, perPage).lastOrNull { t >= it.start - 5e-4 && t < it.end - 5e-4 && it.end > it.start }
}
