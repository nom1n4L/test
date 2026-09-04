package com.skorsnap.app.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads a block of prices pasted or typed from a bookmaker, and works out which
 * of the app's markets each one refers to.
 *
 * Typing prices one field at a time is why the swap feature was useless: with a
 * single price entered there is nothing to swap to. Pasting a whole market list
 * gives the comparison something to compare.
 *
 * Names never match exactly — a book says "Total Over 2.5" where the app says
 * "Over 2.5", and "1X" where the app says "1X (tuan rumah atau seri)" — so the
 * matching is by tokens rather than by string equality, and anything unmatched is
 * reported rather than dropped. A price silently attached to the wrong market is
 * the one failure here that loses money without showing itself.
 */
object Odds {

    /** One line the user gave: a market as they wrote it, and its price. */
    data class Entry(val label: String, val price: Double)

    data class Matched(
        val pairs: Map<String, Double>,
        val unmatched: List<Entry>,
    )

    /**
     * Pulls name/price pairs out of free text.
     *
     * Tolerant on purpose: the text arrives pasted from a betting app, typed by
     * hand, or somewhere between, with commas or dots for decimals and colons,
     * dashes or nothing between name and number.
     */
    fun parse(text: String): List<Entry> = text.lines().mapNotNull { raw ->
        val line = raw.trim().removeSuffix(",").removeSuffix(";")
        if (line.isBlank()) return@mapNotNull null

        // The price is the last number on the line: a market name can contain
        // numbers of its own ("Over 2.5", "Handicap -1"), and the price never
        // comes first.
        val match = Regex("""(\d+[.,]\d+|\d+)\s*$""").find(line) ?: return@mapNotNull null
        val price = match.value.replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
        // Below evens is not a price a book offers; more likely a stray number.
        if (price <= 1.0 || price > 1000) return@mapNotNull null

        val label = line.substring(0, match.range.first)
            .trim().trimEnd(':', '-', '=', '·', '|').trim()
        if (label.isBlank()) return@mapNotNull null
        Entry(label, price)
    }

    /**
     * Attaches each price to a market of this match.
     *
     * A market matches when every word of its name appears in what the user wrote,
     * and where several markets qualify the most specific wins — "Babak 1 Over 2.5"
     * beats "Over 2.5" for a line that mentions the first half, while a bare
     * "over 2.5" matches only the plain one.
     */
    fun match(entries: List<Entry>, markets: List<MarketOption>): Matched {
        val pairs = LinkedHashMap<String, Double>()
        val missed = ArrayList<Entry>()

        entries.forEach { entry ->
            val words = tokens(entry.label)
            val candidates = markets.filter { market ->
                val needed = tokens(market.name)
                needed.isNotEmpty() && words.containsAll(needed)
            }
            val best = candidates.maxByOrNull { tokens(it.name).size }
            if (best == null) missed.add(entry) else pairs["${best.group}|${best.name}"] = entry.price
        }
        return Matched(pairs, missed)
    }

    /**
     * Says, per price, which market it landed on and whether it pays enough.
     *
     * Kept here rather than in the screen that shows it so it can be checked
     * directly: the previous version reported a bare count ("1 harga terpasang"),
     * which is indistinguishable from the feature doing nothing at all, and that is
     * exactly the failure a test on this function catches.
     */
    fun describe(matched: Matched, markets: List<MarketOption>): String = buildString {
        matched.pairs.forEach { (key, price) ->
            val name = key.substringAfter('|')
            val option = markets.firstOrNull { it.name == name } ?: return@forEach
            val edge = ((price * option.prob - 1.0) * 100).roundToInt()
            append(name)
            append(": butuh ${twoDecimals(option.breakEven)}, dibayar ${twoDecimals(price)}")
            // abs on the losing side: the sign is already carried by the word, and
            // "rugi -12%" reads as a profit.
            append(if (edge > 0) " → untung $edge%\n" else " → rugi ${abs(edge)}%\n")
        }
        if (matched.unmatched.isNotEmpty()) {
            append("Tidak dikenali: ")
            append(matched.unmatched.take(5).joinToString { it.label })
            append(". Pakai nama yang mirip nama market di daftar analisis.\n")
        }
    }

    /**
     * Words that carry meaning, normalised.
     *
     * Decimal points are kept — 2.5 and 1.5 are different markets — while commas
     * become points so "2,25" and "2.25" are the same number. Bracketed glosses in
     * the app's own names ("1X (tuan rumah atau seri)") are stripped, since a
     * bookmaker will never repeat them.
     */
    internal fun tokens(name: String): Set<String> = name
        .substringBefore('(')
        .lowercase()
        .replace(',', '.')
        .replace(Regex("""[^a-z0-9. ]"""), " ")
        .split(' ')
        .map { it.trim('.') }
        .filter { it.isNotBlank() && it !in NOISE }
        .toSet()

    /**
     * Words that appear on both sides often enough to match anything.
     *
     * Left in, they let "Total corner" match a plain "Total" line and attach a
     * corner price to a goals market.
     */
    private val NOISE = setOf("dan", "atau", "the", "di", "ke", "market", "pasaran")
}
