package com.skorlogi.app.engine

import kotlin.math.exp
import kotlin.math.ln

/**
 * Pulls model probabilities into line with what actually happened.
 *
 * A model can rank matches well and still be wrong about how sure it is. Measured
 * against held-out seasons, this one is honest about match results but overclaims
 * elsewhere — most severely on both-teams-to-score, where a stated 72% came in
 * around 64%. Acting on the stated number is the whole point of a picks list, so
 * the numbers have to mean what they say.
 *
 * Each family is corrected by Platt scaling on the log odds,
 *
 *     calibrated = sigmoid(a * logit(raw) + b)
 *
 * with `a` and `b` fitted out of sample by `CalibrationFitTest`. An `a` near 1
 * means the family needed no help; a small `a` pulls confidence back towards 50%.
 *
 * Only one side of a complementary pair is ever calibrated — the other is derived
 * from it — so over and under still sum to one after correction.
 */
object Calibration {

    /** Fitted on 8,127 out-of-sample predictions. Effectively no correction. */
    private val RESULT = doubleArrayOf(1.0371, 0.0239)

    /** Over/under goal lines, 8,127 samples. */
    private val TOTALS = doubleArrayOf(0.9187, 0.0779)

    /** Both teams to score, 2,709 samples. The largest correction of the set. */
    private val BTTS = doubleArrayOf(0.4667, 0.1678)

    /** First-half goal lines, 5,418 samples. */
    private val HALF = doubleArrayOf(0.9392, 0.0799)

    /** Corner lines, 6,876 samples. */
    private val CORNERS = doubleArrayOf(0.8291, 0.0634)

    /** Card lines, 5,418 samples. */
    private val CARDS = doubleArrayOf(0.8232, -0.1062)

    private fun apply(c: DoubleArray, p: Double): Double {
        val q = p.coerceIn(1e-6, 1 - 1e-6)
        val z = c[0] * ln(q / (1 - q)) + c[1]
        return (1.0 / (1.0 + exp(-z))).coerceIn(1e-6, 1 - 1e-6)
    }

    fun result(p: Double): Double = apply(RESULT, p)
    fun totals(p: Double): Double = apply(TOTALS, p)
    fun btts(p: Double): Double = apply(BTTS, p)
    fun half(p: Double): Double = apply(HALF, p)
    fun corners(p: Double): Double = apply(CORNERS, p)
    fun cards(p: Double): Double = apply(CARDS, p)

    /** Calibrates a set of mutually exclusive outcomes and renormalises to one. */
    fun exclusive(c: (Double) -> Double, probs: DoubleArray): DoubleArray {
        val out = DoubleArray(probs.size) { c(probs[it]) }
        val sum = out.sum()
        if (sum > 0) for (i in out.indices) out[i] /= sum
        return out
    }

    /**
     * How much a family's stated confidence can be trusted, for display. This is
     * the fitted slope: 1.0 means the stated probability is the real one.
     */
    fun trustOf(family: Family): Double = when (family) {
        Family.RESULT -> RESULT[0]
        Family.TOTALS -> TOTALS[0]
        Family.BTTS -> BTTS[0]
        Family.HALF -> HALF[0]
        Family.CORNERS -> CORNERS[0]
        Family.CARDS -> CARDS[0]
    }

    enum class Family { RESULT, TOTALS, BTTS, HALF, CORNERS, CARDS }
}
