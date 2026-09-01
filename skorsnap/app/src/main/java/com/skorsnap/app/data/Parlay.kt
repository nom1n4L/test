package com.skorsnap.app.data

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One slip, with the numbers the app computes itself.
 *
 * The language model reads the screenshots; it does not do this arithmetic. Asked
 * to combine six probabilities a model will usually produce something plausible
 * and wrong, and the whole point of the slip is the one number that has to be
 * exact.
 */
data class Slip(val legs: List<MatchPrediction>) {

    val size: Int get() = legs.size

    /** Every leg has to land. Probabilities multiply; they do not average. */
    val combined: Double get() = legs.fold(1.0) { acc, m -> acc * m.pickProb }

    val percent: Int get() = (combined * 100).roundToInt()

    /** Roughly how often this comes in — "1 dari 7" reads better than "14%". */
    val oneInN: Int get() = if (combined > 1e-9) (1.0 / combined).roundToInt() else 0

    /** What a fair bookmaker would pay. */
    val fairOdds: Double get() = if (combined > 1e-9) 1.0 / combined else 0.0

    /** What a real one pays, after a margin on every leg. */
    val realisticOdds: Double get() = fairOdds / Parlay.MARGIN.pow(size)

    /** Expected return per unit staked. Below 1 is a loss on average. */
    val expectedReturn: Double get() = 1.0 / Parlay.MARGIN.pow(size)

    val expectedLossPercent: Int get() = ((1.0 - expectedReturn) * 100).roundToInt()

    /** How many of these legs the app expects to land, on its own numbers. */
    val expectedHits: Double get() = legs.sumOf { it.pickProb }

    /** Any leg the analysis itself flagged as thin. */
    val weakLegs: List<MatchPrediction> get() = legs.filter { it.thin }
}

object Parlay {

    /**
     * Bookmaker margin per leg. Measured rather than assumed: the mean overround
     * across 7,314 Bet365 1X2 prices in football-data.co.uk's archive.
     */
    const val MARGIN = 1.0603

    fun of(legs: List<MatchPrediction>) = Slip(legs.distinctBy { it.id })

    /** Expected loss by leg count, for the table the parlay screen leads with. */
    fun marginTable(maxLegs: Int = 8): List<Pair<Int, Int>> =
        (1..maxLegs).map { n -> n to ((1.0 - 1.0 / MARGIN.pow(n)) * 100).roundToInt() }
}
