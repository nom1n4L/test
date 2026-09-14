package com.skorlogi.app.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Attack and defence ratings fitted by weighted maximum likelihood.
 *
 *     home rate = exp(intercept + attack[home] - defence[away] + homeAdv)
 *     away rate = exp(intercept + attack[away] - defence[home])
 *
 * The same fit is reused for full-time goals, half-time goals, corners and cards —
 * only the counts fed in change.
 */
class Ratings(
    val teams: List<String>,
    val attack: DoubleArray,
    val defence: DoubleArray,
    val intercept: Double,
    val homeAdv: Double,
    val matchesPerTeam: IntArray,
    /**
     * Negative-binomial dispersion for these counts. Infinity means the counts are
     * no more spread out than Poisson allows, so Poisson is used.
     */
    val dispersion: Double = Double.POSITIVE_INFINITY,
) {
    private val index: Map<String, Int> = teams.withIndex().associate { (i, t) -> t to i }

    fun has(team: String): Boolean = index.containsKey(team)

    fun indexOf(team: String): Int = index[team] ?: -1

    fun matches(team: String): Int = index[team]?.let { matchesPerTeam[it] } ?: 0

    /** Expected counts for a fixture, or null if either side is unknown. */
    fun rates(home: String, away: String): DoubleArray? {
        val h = index[home] ?: return null
        val a = index[away] ?: return null
        val lambda = exp(intercept + attack[h] - defence[a] + homeAdv)
        val mu = exp(intercept + attack[a] - defence[h])
        return doubleArrayOf(lambda.coerceIn(0.02, 12.0), mu.coerceIn(0.02, 12.0))
    }

    /** Overall strength on a readable scale, used for the team tables. */
    fun strength(team: String): Double {
        val i = index[team] ?: return 0.0
        return attack[i] + defence[i]
    }
}

/** One observation for the fitter. */
class Observation(
    val homeIdx: Int,
    val awayIdx: Int,
    val homeCount: Int,
    val awayCount: Int,
    val weight: Double,
)

object RatingFitter {

    /**
     * Strength of the Gaussian prior pulling ratings towards the league average.
     *
     * This is what stops a team with four matches played from being handed a
     * confident rating. The scale is meaningful: the penalty's curvature is 2*L2
     * against roughly one unit of information per match, so this is worth about
     * a match and a half of evidence.
     *
     * A sweep over held-out seasons is nearly flat between 0.5 and 3 — the choice
     * barely moves full-season accuracy. It is set at the calmer end of that range
     * because the difference does show up early in a season, when several teams
     * have only a handful of matches and an unshrunk rating is mostly noise.
     */
    const val DEFAULT_L2 = 1.0

    private const val ITERATIONS = 600
    private const val LR = 0.06

    /**
     * Fits by Adam on the weighted Poisson log-likelihood. The problem is small —
     * a couple of hundred parameters at most — so this converges in milliseconds.
     */
    fun fit(
        teams: List<String>,
        obs: List<Observation>,
        homeAdvantage: Boolean = true,
        l2: Double = DEFAULT_L2,
    ): Ratings? {
        val n = teams.size
        if (n < 2 || obs.isEmpty()) return null

        val attack = DoubleArray(n)
        val defence = DoubleArray(n)
        var intercept = run {
            val totalW = obs.sumOf { it.weight } * 2.0
            val totalC = obs.sumOf { it.weight * (it.homeCount + it.awayCount) }
            val mean = if (totalW > 0) totalC / totalW else 1.0
            kotlin.math.ln(mean.coerceAtLeast(0.05))
        }
        var homeAdv = if (homeAdvantage) 0.15 else 0.0

        // Adam state.
        val mA = DoubleArray(n); val vA = DoubleArray(n)
        val mD = DoubleArray(n); val vD = DoubleArray(n)
        var mI = 0.0; var vI = 0.0
        var mH = 0.0; var vH = 0.0
        val b1 = 0.9; val b2 = 0.999; val eps = 1e-8

        val gA = DoubleArray(n)
        val gD = DoubleArray(n)

        for (step in 1..ITERATIONS) {
            java.util.Arrays.fill(gA, 0.0)
            java.util.Arrays.fill(gD, 0.0)
            var gI = 0.0
            var gH = 0.0

            for (o in obs) {
                val h = o.homeIdx
                val a = o.awayIdx
                val lambda = exp(intercept + attack[h] - defence[a] + homeAdv).coerceIn(1e-6, 50.0)
                val mu = exp(intercept + attack[a] - defence[h]).coerceIn(1e-6, 50.0)
                val rh = o.weight * (o.homeCount - lambda)
                val ra = o.weight * (o.awayCount - mu)

                gA[h] += rh
                gA[a] += ra
                gD[a] -= rh
                gD[h] -= ra
                gI += rh + ra
                gH += rh
            }

            // Shrink towards zero.
            for (i in 0 until n) {
                gA[i] -= 2.0 * l2 * attack[i]
                gD[i] -= 2.0 * l2 * defence[i]
            }

            val bc1 = 1.0 - Math.pow(b1, step.toDouble())
            val bc2 = 1.0 - Math.pow(b2, step.toDouble())

            for (i in 0 until n) {
                mA[i] = b1 * mA[i] + (1 - b1) * gA[i]
                vA[i] = b2 * vA[i] + (1 - b2) * gA[i] * gA[i]
                attack[i] += LR * (mA[i] / bc1) / (sqrt(vA[i] / bc2) + eps)

                mD[i] = b1 * mD[i] + (1 - b1) * gD[i]
                vD[i] = b2 * vD[i] + (1 - b2) * gD[i] * gD[i]
                defence[i] += LR * (mD[i] / bc1) / (sqrt(vD[i] / bc2) + eps)
            }

            mI = b1 * mI + (1 - b1) * gI
            vI = b2 * vI + (1 - b2) * gI * gI
            intercept += LR * (mI / bc1) / (sqrt(vI / bc2) + eps)

            if (homeAdvantage) {
                mH = b1 * mH + (1 - b1) * gH
                vH = b2 * vH + (1 - b2) * gH * gH
                homeAdv += LR * (mH / bc1) / (sqrt(vH / bc2) + eps)
            }

            // Identifiability: attack and defence are only defined up to a shift, so
            // recentre each and push the difference into the intercept.
            var ma = 0.0; var md = 0.0
            for (i in 0 until n) { ma += attack[i]; md += defence[i] }
            ma /= n; md /= n
            for (i in 0 until n) { attack[i] -= ma; defence[i] -= md }
            intercept += ma - md
        }

        if (!intercept.isFinite() || attack.any { !it.isFinite() }) return null

        val counts = IntArray(n)
        for (o in obs) {
            counts[o.homeIdx]++
            counts[o.awayIdx]++
        }

        // Measure how far the counts actually spread around the fit, so callers can
        // widen the distribution where Poisson would be too sure of itself.
        val observed = IntArray(obs.size * 2)
        val means = DoubleArray(obs.size * 2)
        val weights = DoubleArray(obs.size * 2)
        for ((i, o) in obs.withIndex()) {
            val lambda = exp(intercept + attack[o.homeIdx] - defence[o.awayIdx] + homeAdv)
            val mu = exp(intercept + attack[o.awayIdx] - defence[o.homeIdx])
            observed[i * 2] = o.homeCount; means[i * 2] = lambda; weights[i * 2] = o.weight
            observed[i * 2 + 1] = o.awayCount; means[i * 2 + 1] = mu; weights[i * 2 + 1] = o.weight
        }

        return Ratings(
            teams = teams,
            attack = attack,
            defence = defence,
            intercept = intercept,
            homeAdv = if (homeAdvantage) homeAdv.coerceIn(-0.5, 0.8) else 0.0,
            matchesPerTeam = counts,
            dispersion = NegBin.dispersion(observed, means, weights),
        )
    }

    /**
     * Dixon–Coles low-score correction factor. It fixes the well-known Poisson
     * under-prediction of 0-0 and 1-1 and over-prediction of 1-0 and 0-1.
     */
    fun tau(x: Int, y: Int, lambda: Double, mu: Double, rho: Double): Double = when {
        x == 0 && y == 0 -> 1.0 - lambda * mu * rho
        x == 0 && y == 1 -> 1.0 + lambda * rho
        x == 1 && y == 0 -> 1.0 + mu * rho
        x == 1 && y == 1 -> 1.0 - rho
        else -> 1.0
    }.coerceAtLeast(1e-6)

    /**
     * Grid-searches rho on the same weighted likelihood the ratings were fitted on.
     * A one-dimensional scan is more than enough for a parameter that lives in a
     * narrow range and moves the answer only slightly.
     */
    fun fitRho(ratings: Ratings, obs: List<Observation>, teams: List<String>): Double {
        var best = 0.0
        var bestLL = Double.NEGATIVE_INFINITY
        var rho = -0.25
        while (rho <= 0.15001) {
            var ll = 0.0
            for (o in obs) {
                if (o.homeCount > 1 || o.awayCount > 1) continue // tau is 1 elsewhere
                val lambda = exp(
                    ratings.intercept + ratings.attack[o.homeIdx] -
                        ratings.defence[o.awayIdx] + ratings.homeAdv
                )
                val mu = exp(ratings.intercept + ratings.attack[o.awayIdx] - ratings.defence[o.homeIdx])
                val t = tau(o.homeCount, o.awayCount, lambda, mu, rho)
                ll += o.weight * kotlin.math.ln(t)
            }
            if (ll > bestLL) { bestLL = ll; best = rho }
            rho += 0.005
        }
        return if (abs(best) < 1e-9) 0.0 else best
    }
}
