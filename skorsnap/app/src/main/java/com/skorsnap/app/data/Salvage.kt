package com.skorsnap.app.data

/**
 * Rescues an answer that was cut off mid-sentence.
 *
 * When Gemini hits its output ceiling it stops wherever it happens to be, and the
 * half-written JSON that comes back parses as nothing at all. The app then threw
 * "Jawaban model terpotong sebelum selesai" and discarded the lot — including, on a
 * long analysis, forty markets that had already been written correctly.
 *
 * That is the wrong trade. The response schema fixes the order fields are generated
 * in, and the expensive thinking — team readings, goal expectations, the market grid
 * — comes before the short tail. A reply cut off in the middle of the market list
 * still contains almost all of the analysis; only the last, partly-typed item is
 * unusable.
 *
 * So the text is trimmed back to the last point where it was definitely complete and
 * the open brackets are closed. Nothing is invented: every value kept is a value the
 * model finished writing. What cannot be rescued is refused rather than guessed at,
 * and the caller decides whether what survived is enough to show.
 */
object Salvage {

    /**
     * The truncated text, cut back to its last complete value and closed.
     *
     * Returns null when there is no such point — a reply that died before finishing
     * even one field has nothing in it worth keeping.
     */
    fun repair(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        val text = raw.substring(start)

        val open = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        // The last index at which everything before it was a finished value, paired
        // with the brackets that were open there. A comma outside a string is
        // exactly that point: whatever preceded it was complete enough for the
        // model to move on from.
        var cut = -1
        var cutDepth = ""

        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> open.addLast('}')
                '[' -> open.addLast(']')
                '}', ']' -> {
                    if (open.isEmpty()) return null
                    open.removeLast()
                    // A closed object or array is also a finished value.
                    if (open.isNotEmpty()) {
                        cut = i + 1
                        cutDepth = open.reversed().joinToString("")
                    }
                }
                ',' -> if (open.isNotEmpty()) {
                    cut = i
                    cutDepth = open.reversed().joinToString("")
                }
            }
        }

        // Already whole: nothing to repair, and re-closing it would corrupt it.
        if (!inString && open.isEmpty()) return text
        if (cut <= 0) return null
        return text.substring(0, cut) + cutDepth
    }

    /**
     * How many markets survived, without committing to parsing the whole thing.
     *
     * Used to decide whether a rescued answer is worth showing. A handful of markets
     * is not an analysis; it is a fragment that would look like one, which is worse
     * than an honest failure.
     */
    fun marketCount(json: String): Int {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return 0
        return o.optJSONArray("markets")?.length() ?: 0
    }

    /** Below this a rescued answer is refused: too little to recommend anything from. */
    const val ENOUGH = 8
}
