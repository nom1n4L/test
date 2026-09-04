package com.skorsnap.app.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads a block of prices copied from a bookmaker, and works out which of the app's
 * markets each one refers to.
 *
 * The first version of this was written against a format nobody uses — one market
 * per line, the app's own wording, the price at the end. Real Melbet text looks
 * like this:
 *
 * ```
 * * M1 2.05
 * * X 3.40
 * * 2X 1.19
 * * (0.5) Over: 1.016 | (0.5) Under: 12.5
 * ```
 *
 * Three things there broke it, and every one of them threw away a price in silence:
 * a bullet in front, shorthand names the app has never heard of, and two markets on
 * one line separated by a pipe. Two lines out of a whole coupon were recognised, and
 * because the market maths needs complete Over/Under and 1X2 sets to remove the
 * bookmaker's fee, nothing downstream ran at all.
 *
 * So: lines are split into segments, bullets and decoration are stripped, bookmaker
 * shorthand is translated before matching, and anything still unrecognised is
 * reported rather than dropped. A price attached to the wrong market is the one
 * failure here that loses money without showing itself.
 */
object Odds {

    /** One line the user gave: a market as they wrote it, and its price. */
    data class Entry(val label: String, val price: Double)

    data class Matched(
        val pairs: Map<String, Double>,
        val unmatched: List<Entry>,
    )

    /**
     * What bookmakers call things, in the app's own words.
     *
     * Melbet writes "M1" where the app writes "Tuan rumah menang", and no amount of
     * word matching bridges that: the two share no letters. The table is the bridge.
     * Keys are matched against the whole cleaned label, lowercased.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "m1" to "Tuan rumah menang",
        "1" to "Tuan rumah menang",
        "w1" to "Tuan rumah menang",
        "home" to "Tuan rumah menang",
        "x" to "Seri",
        "draw" to "Seri",
        "seri" to "Seri",
        "m2" to "Tandang menang",
        "2" to "Tandang menang",
        "w2" to "Tandang menang",
        "away" to "Tandang menang",
        "1x" to "1X (tuan rumah atau seri)",
        "12" to "12 (tidak seri)",
        "x2" to "X2 (seri atau tandang)",
        "2x" to "X2 (seri atau tandang)",
        "gg" to "Kedua tim cetak gol (BTTS) - Ya",
        "btts" to "Kedua tim cetak gol (BTTS) - Ya",
        "btts ya" to "Kedua tim cetak gol (BTTS) - Ya",
        "both teams to score" to "Kedua tim cetak gol (BTTS) - Ya",
        "ng" to "Kedua tim cetak gol (BTTS) - Tidak",
        "btts tidak" to "Kedua tim cetak gol (BTTS) - Tidak",
    )

    /**
     * Pulls name/price pairs out of free text.
     *
     * A line can carry more than one market. Melbet prints an Over/Under pair on one
     * row separated by a pipe, and taking only the last number on such a row loses
     * the Over price and mislabels the Under — which is worse than losing both,
     * because the survivor is wrong rather than missing.
     */
    fun parse(text: String): List<Entry> = text.lines().flatMap { raw ->
        segments(raw).mapNotNull { segment -> entry(segment) }
    }

    /**
     * Splits one line into the markets it mentions.
     *
     * Only where the split produces parts that each carry their own price. A market
     * name can contain a pipe or a slash for other reasons, and splitting those
     * would turn one readable line into two unreadable ones.
     */
    private fun segments(raw: String): List<String> {
        val line = raw.trim()
        if (line.isBlank()) return emptyList()
        val parts = line.split('|', '\t').map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size > 1 && parts.count { PRICE.containsMatchIn(it) } == parts.size) parts
        else listOf(line)
    }

    /** The price at the end of a segment, and whatever came before it as the name. */
    private fun entry(segment: String): Entry? {
        val cleaned = segment.trim().trimEnd(',', ';')
        val found = PRICE.find(cleaned) ?: return null
        val price = found.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        // Below evens is not a price a book offers; above 1000 is not a price at all.
        if (price <= 1.0 || price > 1000) return null

        val label = clean(cleaned.substring(0, found.range.first))
        if (label.isBlank()) return null
        return Entry(label, price)
    }

    /** A trailing number, optionally after a colon or equals. */
    private val PRICE = Regex("""[:=]?\s*(\d+[.,]\d+|\d+)\s*$""")

    /** Strips list bullets and separators that carry no meaning. */
    private fun clean(label: String): String = label
        .trim()
        .trimStart('*', '-', '•', '·', '+', '>')
        .trim()
        .trimEnd(':', '-', '=', '·', '|', '.')
        .trim()

    /**
     * Attaches each price to a market of this match.
     *
     * A market matches when every word of its name appears in what the user wrote,
     * and where several markets qualify the most specific wins — "Babak 1 Over 2.5"
     * beats "Over 2.5" for a line that mentions the first half, while a bare
     * "over 2.5" matches only the plain one.
     *
     * Shorthand is resolved first, and exactly: "1" means the home win, but "1" also
     * appears inside "Babak 1 Over 2.5", so an alias is only used when it is the
     * whole label rather than a word within it.
     */
    fun match(entries: List<Entry>, markets: List<MarketOption>): Matched {
        val pairs = LinkedHashMap<String, Double>()
        val missed = ArrayList<Entry>()

        entries.forEach { entry ->
            val expanded = ALIASES[entry.label.lowercase().trim()] ?: entry.label
            val words = tokens(expanded)
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
     * Words that carry meaning, normalised.
     *
     * Decimal points are kept — 2.5 and 1.5 are different markets — while commas
     * become points so "2,25" and "2.25" are the same number.
     *
     * The app's own names carry a gloss in brackets ("1X (tuan rumah atau seri)")
     * that no bookmaker repeats, so a trailing bracket is dropped. Only a trailing
     * one: Melbet writes the line first, as "(0.5) Over", and cutting at the first
     * bracket there left nothing at all to match on.
     */
    internal fun tokens(name: String): Set<String> = name
        .replace(Regex("""\s*\([^)]*\)\s*$"""), "")
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
}
