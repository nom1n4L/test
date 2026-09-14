package com.skorlogi.app.engine

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Parlay arithmetic, done honestly.
 *
 * Combining picks is the most requested thing an app like this can do and the
 * least kind, so the numbers here are the real ones rather than the flattering
 * ones. Two facts drive everything on this screen.
 *
 * The first is that probabilities multiply. Four legs at 80% is not 80%, it is
 * 41%. People reach for parlays because the payout looks large and rarely notice
 * that the chance shrank faster than the payout grew.
 *
 * The second is sharper. A bookmaker prices each leg with a margin — measured at
 * 6.03% across 7,314 matches of Bet365 1X2 prices in this app's own data — and
 * the margins multiply too. Work the algebra through and the expected return of
 * an n-leg parlay is 1 / 1.0603^n, whatever the legs are. It does not depend on
 * how good the picks are. Better picks raise the chance of winning and lower the
 * payout by exactly the same factor, and the margin is what is left. That is why
 * there is no such thing as a safe parlay, only a slower one.
 */
data class ParlayOption(
    val legs: List<Pick>,
    val combinedProb: Double,
    /**
     * The actual prices a bookmaker is offering for these legs, where the odds
     * feed covers them. When present the payout below stops being an estimate
     * built on an average margin and becomes the real number.
     */
    val quotedOdds: List<Double> = emptyList(),
) {
    val size: Int get() = legs.size

    /** What a fair bookmaker would pay. */
    val fairOdds: Double get() = if (combinedProb > 1e-9) 1.0 / combinedProb else 0.0

    /** What a real one pays, after the margin measured from actual prices. */
    val realisticOdds: Double get() = fairOdds / Parlay.MARGIN.pow(size)

    /** Expected return per 1 staked. Below 1 means a loss on average. */
    val expectedReturn: Double get() = 1.0 / Parlay.MARGIN.pow(size)

    val expectedLossPercent: Int get() = ((1.0 - expectedReturn) * 100).roundToInt()

    val percent: Int get() = (combinedProb * 100).roundToInt()

    /** True when every leg has a real price, so nothing here is assumed. */
    val fullyPriced: Boolean get() = quotedOdds.size == size && size > 0

    /** What this slip actually pays at the best prices found. */
    val quotedPayout: Double? get() =
        if (fullyPriced) quotedOdds.fold(1.0) { acc, o -> acc * o } else null

    /**
     * Expected return using the real prices rather than the average margin. This
     * is the honest number when the feed covers every leg.
     */
    val quotedExpectedReturn: Double? get() = quotedPayout?.let { combinedProb * it }

    /** Roughly how often this comes in — "1 dari 7" reads better than "14%". */
    val oneInN: Int get() = if (combinedProb > 1e-9) (1.0 / combinedProb).roundToInt() else 0
}

object Parlay {

    /**
     * Bookmaker overround per leg, measured rather than assumed: the mean of
     * 1/H + 1/D + 1/A over 7,314 Bet365 1X2 prices in the archive.
     */
    const val MARGIN = 1.0603

    /** Legs from the same match move together, so a parlay may hold only one. */
    fun combine(legs: List<Pick>): ParlayOption {
        val unique = legs.distinctBy { it.fixture.key }
        var p = 1.0
        for (leg in unique) p *= leg.prob
        return ParlayOption(unique, p)
    }

    /**
     * Suggested combinations, taking the strongest picks one match at a time.
     * Offered because people will build these anyway; the numbers beside them are
     * the argument.
     */
    fun suggestions(picks: List<Pick>, sizes: List<Int> = listOf(2, 3, 4, 5, 6)): List<ParlayOption> {
        val pool = picks.distinctBy { it.fixture.key }.sortedByDescending { it.prob }
        return sizes.filter { it <= pool.size }.map { n -> combine(pool.take(n)) }
    }

    /** The margin table, as plain numbers for the screen to render. */
    fun marginTable(maxLegs: Int = 6): List<Pair<Int, Int>> =
        (1..maxLegs).map { n -> n to ((1.0 - 1.0 / MARGIN.pow(n)) * 100).roundToInt() }
}
