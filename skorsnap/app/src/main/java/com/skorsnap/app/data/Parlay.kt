package com.skorsnap.app.data

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a leg is chosen from each match.
 *
 * The three differ only in which market they take, and the difference is a trade,
 * not a ranking: a shorter price is likelier to land and pays less. Naming them
 * plainly is the point — "value" as a label for the longer prices would be a lie,
 * because whether a price is value depends on what the bookmaker pays for it, and
 * that is not knowable from the analysis alone.
 */
enum class Strategy(val label: String, val note: String) {
    RECOMMENDED(
        "Rekomendasi aplikasi",
        "Market yang direkomendasikan di tiap laga.",
    ),
    SAFEST(
        "Paling aman",
        "Peluang tertinggi di rentang aman tiap laga. Paling sering tembus, bayarannya paling kecil.",
    ),
    HIGHER_PAYING(
        "Bayaran lebih tinggi",
        "Peluang terendah yang masih di rentang aman. Bayarannya naik, tembusnya lebih jarang — " +
            "ini menaikkan bayaran, bukan menaikkan nilai.",
    ),
}

/**
 * One line of a slip: a match, the market taken from it, and optionally the price
 * the bookmaker is actually offering.
 *
 * The price is entered by hand and starts empty. Everything except [edge] works
 * without it; nothing pretends to know it.
 */
data class Leg(
    val matchId: String,
    val home: String,
    val away: String,
    val market: String,
    val group: String,
    val prob: Double,
    val thin: Boolean = false,
    val odds: Double = 0.0,
) {
    val percent: Int get() = (prob * 100).roundToInt()

    /** The price this leg must beat to be worth taking at all. */
    val breakEven: Double get() = if (prob > 1e-9) 1.0 / prob else 0.0

    val priced: Boolean get() = odds > 1.0

    /**
     * Profit per unit staked, at the price entered. Positive means the bookmaker is
     * paying more than the app thinks the outcome is worth.
     */
    val edge: Double get() = if (priced) odds * prob - 1.0 else 0.0

    val edgePercent: Int get() = (edge * 100).roundToInt()
}

/**
 * One slip, with the numbers the app computes itself.
 *
 * The language model reads the screenshots; it does not do this arithmetic. Asked
 * to combine six probabilities a model will usually produce something plausible
 * and wrong, and the whole point of the slip is the one number that has to be
 * exact.
 */
data class Slip(val legs: List<Leg>) {

    val size: Int get() = legs.size

    /** Every leg has to land. Probabilities multiply; they do not average. */
    val combined: Double get() = legs.fold(1.0) { acc, leg -> acc * leg.prob }

    val percent: Int get() = (combined * 100).roundToInt()

    /** Roughly how often this comes in — "1 dari 7" reads better than "14%". */
    val oneInN: Int get() = if (combined > 1e-9) (1.0 / combined).roundToInt() else 0

    /** What a fair bookmaker would pay. */
    val fairOdds: Double get() = if (combined > 1e-9) 1.0 / combined else 0.0

    val priced: Boolean get() = legs.isNotEmpty() && legs.all { it.priced }

    /** The price the bookmaker actually pays for the whole slip: legs multiply. */
    val bookOdds: Double get() = legs.fold(1.0) { acc, leg -> acc * leg.odds }

    /**
     * Return per unit staked.
     *
     * With real prices this is arithmetic on the user's own numbers. Without them it
     * falls back to the measured margin, which is where a parlay's reputation comes
     * from: the margin compounds once per leg whatever the legs are.
     */
    val expectedReturn: Double
        get() = if (priced) bookOdds * combined else 1.0 / Parlay.MARGIN.pow(size)

    val expectedLossPercent: Int get() = ((1.0 - expectedReturn) * 100).roundToInt()

    /** Real odds against the fair price — the only honest reading of "value". */
    val worthTaking: Boolean get() = priced && expectedReturn > 1.0

    /** How many of these legs the app expects to land, on its own numbers. */
    val expectedHits: Double get() = legs.sumOf { it.prob }

    /** Any leg the analysis itself flagged as thin. */
    val weakLegs: List<Leg> get() = legs.filter { it.thin }

    /** Legs the bookmaker is underpaying for, once prices are in. */
    val badlyPriced: List<Leg> get() = legs.filter { it.priced && it.edge <= 0 }

    fun withOdds(matchId: String, market: String, odds: Double) = copy(
        legs = legs.map {
            if (it.matchId == matchId && it.market == market) it.copy(odds = odds) else it
        }
    )
}

object Parlay {

    /**
     * Bookmaker margin per leg. Measured rather than assumed: the mean overround
     * across 7,314 Bet365 1X2 prices in football-data.co.uk's archive.
     */
    const val MARGIN = 1.0603

    /**
     * Picks one market per match under the chosen strategy.
     *
     * A match with nothing in the safe band contributes no leg rather than its best
     * bad option: padding a slip with a coin-flip to reach six legs is how a parlay
     * that looked reasonable becomes one that cannot win.
     */
    fun build(matches: List<MatchPrediction>, strategy: Strategy): Slip = Slip(
        matches.distinctBy { it.id }.mapNotNull { m ->
            val safe = m.safePicks()
            val option = when (strategy) {
                Strategy.RECOMMENDED -> m.markets.firstOrNull { it.name == m.pick }
                Strategy.SAFEST -> safe.firstOrNull()
                Strategy.HIGHER_PAYING -> safe.lastOrNull()
            } ?: return@mapNotNull null
            Leg(
                matchId = m.id,
                home = m.home,
                away = m.away,
                market = option.name,
                group = option.group,
                prob = option.prob,
                thin = m.thin,
            )
        }
    )

    /** Matches that contribute nothing under this strategy, and why. */
    fun skipped(matches: List<MatchPrediction>, strategy: Strategy): List<MatchPrediction> {
        val kept = build(matches, strategy).legs.map { it.matchId }.toSet()
        return matches.distinctBy { it.id }.filterNot { it.id in kept }
    }

    /** Expected loss by leg count, for the table the parlay screen leads with. */
    fun marginTable(maxLegs: Int = 8): List<Pair<Int, Int>> =
        (1..maxLegs).map { n -> n to ((1.0 - 1.0 / MARGIN.pow(n)) * 100).roundToInt() }
}
