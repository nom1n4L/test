package com.skorsnap.app.data

import kotlin.math.abs

/**
 * Turns bookmaker prices into probabilities, and folds them into the model's read.
 *
 * This is the part that was missing. Prices were being shown next to the model's
 * numbers and left there, as if the user should be the one to reconcile them. But a
 * bookmaker's price is not decoration: it is a probability with a fee on top, set by
 * people with more information than any screenshot contains, and it is a better
 * predictor than a model reading averages off a stats page. Ignoring it while
 * displaying it was the worst of both.
 *
 * Three steps:
 *  1. Prices only mean something in complete sets. 1.90 on Over 2.5 says nothing on
 *     its own; 1.90 / 1.90 on Over and Under says the book is charging 5.3% and
 *     thinks it is a coin flip. So groups are only used when every side is priced.
 *  2. The fee (the overround) is removed proportionally, leaving probabilities that
 *     sum to what they should.
 *  3. The result is blended with the model's own number rather than replacing it.
 *     Replacing it would make the app a mirror of the bookmaker with no opinion —
 *     every edge exactly zero by construction. Blending keeps the disagreements
 *     that survive contact with the price, which are the only ones worth betting.
 */
object Devig {

    /**
     * How much of the final probability comes from the market.
     *
     * Above a half because the market is the better forecaster: it prices thousands
     * of matches against people trying to beat it, while the model reads a few
     * screenshots. Not so high that the app has no view of its own — at 0.65 a
     * 20-point disagreement still leaves 7 points of it standing, which is enough
     * to point at a market the book has mispriced without inventing one.
     */
    const val MARKET_WEIGHT = 0.65

    /**
     * A gap this wide between model and market is a warning, not an opportunity.
     *
     * A book at 36% and a model at 69% are not both plausible readings of the same
     * match; one of them has misread it, and it is almost never the book. Flagged
     * so a huge "edge" reads as what it usually is — a mistake in the screenshots
     * or in the model's arithmetic.
     */
    const val WIDE_GAP = 0.15

    /** Outside this range the numbers were misread, not merely expensive. */
    private const val MIN_MARGIN = -0.02
    private const val MAX_MARGIN = 0.30

    /**
     * One complete set of mutually exclusive outcomes, and what its probabilities
     * must add up to.
     *
     * Double Chance is the reason this carries a target rather than assuming 1.0:
     * 1X, 12 and X2 each cover two of the three results, so a fair book has them
     * summing to 2.0.
     */
    data class Set(val keys: List<String>, val target: Double, val label: String)

    data class Fair(
        /** Market key to the probability implied by the price, fee removed. */
        val probs: Map<String, Double>,
        /** The bookmaker's fee on this set, as a fraction. */
        val margin: Double,
        val label: String,
    )

    /**
     * The complete sets present in a price list.
     *
     * Built by rule rather than by asking the model, because a mistake here attaches
     * a fair probability to the wrong outcome and every number downstream inherits
     * it silently.
     */
    internal fun sets(prices: Map<String, Double>, markets: List<MarketOption>): List<Set> {
        val byName = markets.associateBy({ it.name }, { "${it.group}|${it.name}" })
        fun keys(vararg names: String): List<String>? {
            val found = names.map { byName[it] ?: return null }
            return if (found.all { it in prices }) found else null
        }

        val out = ArrayList<Set>()

        keys("Tuan rumah menang", "Seri", "Tandang menang")
            ?.let { out.add(Set(it, 1.0, "Hasil Akhir")) }

        keys("1X (tuan rumah atau seri)", "12 (tidak seri)", "X2 (seri atau tandang)")
            ?.let { out.add(Set(it, 2.0, "Double Chance")) }

        keys("Kedua tim cetak gol (BTTS) - Ya", "Kedua tim cetak gol (BTTS) - Tidak")
            ?.let { out.add(Set(it, 1.0, "BTTS")) }

        keys("Minimal satu tim cetak 2+ gol - Ya", "Minimal satu tim cetak 2+ gol - Tidak")
            ?.let { out.add(Set(it, 1.0, "Satu tim 2+ gol")) }

        // Every Over/Under pair on the same line, whatever it counts — goals, first
        // half goals, corners. Paired by the text before "Over", so "Babak 1 Over
        // 2.5" can never be paired with the full-match "Under 2.5".
        markets.filter { " Over " in " ${it.name}" || it.name.startsWith("Over ") }
            .forEach { over ->
                val line = over.name.substringAfter("Over ").trim()
                val prefix = over.name.substringBefore("Over ")
                val under = "${prefix}Under $line"
                keys(over.name, under)?.let { out.add(Set(it, 1.0, "${prefix}$line".trim())) }
            }

        // Asian and European handicaps: the home line and its away mirror.
        markets.filter { it.name.startsWith("Tuan rumah -") }.forEach { home ->
            val line = home.name.removePrefix("Tuan rumah -")
            keys(home.name, "Tandang +$line")
                ?.let { out.add(Set(it, 1.0, "Handicap $line")) }
        }

        return out.distinctBy { it.keys.toSet() }
    }

    /**
     * Removes the bookmaker's fee from each complete set.
     *
     * Proportionally: every price is scaled by the same factor. More elaborate
     * methods exist and matter at long odds, where the favourite carries less of
     * the fee than the longshot, but they need assumptions this app cannot check
     * from a screenshot. A set whose fee is negative or enormous is dropped — that
     * is a misread price, and a misread price silently poisons everything after it.
     */
    fun fair(prices: Map<String, Double>, markets: List<MarketOption>): List<Fair> =
        sets(prices, markets).mapNotNull { set ->
            val raw = set.keys.associateWith { key ->
                val price = prices[key] ?: return@mapNotNull null
                if (price <= 1.0) return@mapNotNull null
                1.0 / price
            }
            val sum = raw.values.sum()
            if (sum <= 0.0) return@mapNotNull null
            val margin = (sum - set.target) / set.target
            if (margin < MIN_MARGIN || margin > MAX_MARGIN) return@mapNotNull null
            Fair(raw.mapValues { (_, p) -> p * set.target / sum }, margin, set.label)
        }

    /**
     * The blended probabilities, keyed by market.
     *
     * A market can appear in more than one set — Over 2.5 sits in its own pair and
     * nowhere else, but a handicap line can be reached two ways — so where a market
     * gets several fair readings they are averaged before blending.
     */
    fun marketProbs(prices: Map<String, Double>, markets: List<MarketOption>): Map<String, Double> {
        val collected = HashMap<String, MutableList<Double>>()
        fair(prices, markets).forEach { f ->
            f.probs.forEach { (key, p) -> collected.getOrPut(key) { ArrayList() }.add(p) }
        }
        return collected.mapValues { (_, list) -> list.average() }
    }

    /**
     * Folds the market into a finished analysis.
     *
     * The model's number is kept on the option as [MarketOption.modelProb] so the
     * screen can show the move rather than quietly presenting a different figure
     * than the one the reasoning above it argued for.
     *
     * The 1X2 is updated too when the book priced it, and the goal expectations are
     * refitted to match. Without that, a Double Chance the app derived from the
     * model's old 1X2 would sit on the same screen contradicting the blended win
     * probability printed above it.
     */
    fun blend(match: MatchPrediction, weight: Double = MARKET_WEIGHT): MatchPrediction {
        if (match.prices.isEmpty()) return match
        val fairProbs = marketProbs(match.prices, match.markets)
        if (fairProbs.isEmpty()) return match

        val moved = match.markets.map { option ->
            val market = fairProbs["${option.group}|${option.name}"] ?: return@map option
            option.copy(
                prob = weight * market + (1 - weight) * option.prob,
                modelProb = option.prob,
                marketProb = market,
            )
        }

        fun probOf(name: String, fallback: Double) =
            moved.firstOrNull { it.name == name }?.prob ?: fallback

        val pH = probOf("Tuan rumah menang", match.probHome)
        val pD = probOf("Seri", match.probDraw)
        val pA = probOf("Tandang menang", match.probAway)
        val total = pH + pD + pA
        val (h, d, a) =
            if (total > 0.0) Triple(pH / total, pD / total, pA / total)
            else Triple(match.probHome, match.probDraw, match.probAway)

        val (xgH, xgA) = Grid.fit(match.xgHome, match.xgAway, h, d, a)

        return match.copy(
            markets = moved.sortedByDescending { it.prob },
            probHome = h,
            probDraw = d,
            probAway = a,
            xgHome = xgH,
            xgAway = xgA,
            marketBlended = true,
        )
    }

    /** Markets where the model and the book disagree far more than either can justify. */
    fun disagreements(match: MatchPrediction): List<MarketOption> =
        match.markets.filter { option ->
            val model = option.modelProb
            val market = option.marketProb
            model != null && market != null && abs(model - market) >= WIDE_GAP
        }.sortedByDescending { abs((it.modelProb ?: 0.0) - (it.marketProb ?: 0.0)) }
}
