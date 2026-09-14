package com.skorlogi.app.engine

import com.skorlogi.app.data.Match
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * A conventional football Elo: margin-of-victory scaled K, and home advantage
 * expressed in rating points. It is not what drives the markets — the Dixon–Coles
 * fit does — but it is an independent read on strength, so a disagreement between
 * the two is a useful warning that a prediction rests on thin evidence.
 */
object Elo {

    private const val START = 1500.0
    private const val K = 20.0
    private const val HOME_BONUS = 65.0

    fun rate(matches: List<Match>): Map<String, Double> {
        val r = HashMap<String, Double>()
        for (m in matches.sortedBy { it.dateEpochDay }) {
            val rh = r.getOrPut(m.home) { START }
            val ra = r.getOrPut(m.away) { START }

            val expectedHome = 1.0 / (1.0 + 10.0.pow((ra - (rh + HOME_BONUS)) / 400.0))
            val actualHome = when {
                m.homeGoals > m.awayGoals -> 1.0
                m.homeGoals < m.awayGoals -> 0.0
                else -> 0.5
            }

            val diff = abs(m.homeGoals - m.awayGoals)
            val multiplier = if (diff <= 1) 1.0 else 1.0 + ln(diff.toDouble())
            val delta = K * multiplier * (actualHome - expectedHome)

            r[m.home] = rh + delta
            r[m.away] = ra - delta
        }
        return r
    }

    /** Home win / draw / away win from two ratings. */
    fun probabilities(homeRating: Double, awayRating: Double): DoubleArray {
        val d = (homeRating + HOME_BONUS) - awayRating
        val pHomeVsAway = 1.0 / (1.0 + 10.0.pow(-d / 400.0))
        // Draws peak when the sides are level and fade as the gap widens.
        val pDraw = (0.30 * kotlin.math.exp(-(d * d) / (2 * 260.0 * 260.0))).coerceIn(0.10, 0.32)
        val rest = 1.0 - pDraw
        val pHome = pHomeVsAway * rest
        val pAway = rest - pHome
        return doubleArrayOf(pHome, pDraw, pAway)
    }
}
