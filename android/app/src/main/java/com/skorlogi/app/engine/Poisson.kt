package com.skorlogi.app.engine

import kotlin.math.exp
import kotlin.math.ln

/** Poisson helpers and the joint score grid every market is read off. */
object Poisson {

    /** pmf for k events at rate lambda, computed in log space to stay stable. */
    fun pmf(k: Int, lambda: Double): Double {
        if (lambda <= 0.0) return if (k == 0) 1.0 else 0.0
        var logP = -lambda + k * ln(lambda)
        for (i in 2..k) logP -= ln(i.toDouble())
        return exp(logP)
    }

    /** The full pmf vector for 0..maxK, with the tail mass folded into the last cell. */
    fun vector(lambda: Double, maxK: Int): DoubleArray {
        val v = DoubleArray(maxK + 1)
        var sum = 0.0
        for (k in 0 until maxK) {
            v[k] = pmf(k, lambda)
            sum += v[k]
        }
        v[maxK] = (1.0 - sum).coerceAtLeast(0.0)
        return v
    }
}

/**
 * A joint distribution over (home count, away count). Every market in the app is a
 * sum over cells of one of these, which keeps all the numbers mutually consistent.
 */
class Grid(
    homeVec: DoubleArray,
    awayVec: DoubleArray,
    correction: ((Int, Int) -> Double)? = null,
    outcomeTilt: DoubleArray? = null,
) {

    val size: Int = homeVec.size

    val p: Array<DoubleArray>

    /** Marginals read back off the joint, so they stay right after a tilt. */
    val home: DoubleArray
    val away: DoubleArray

    init {
        val grid = Array(size) { i ->
            DoubleArray(size) { j ->
                var v = homeVec[i] * awayVec[j]
                if (correction != null) v *= correction(i, j)
                if (outcomeTilt != null) {
                    v *= when {
                        i > j -> outcomeTilt[0]
                        i == j -> outcomeTilt[1]
                        else -> outcomeTilt[2]
                    }
                }
                v
            }
        }
        var total = 0.0
        for (i in 0 until size) for (j in 0 until size) total += grid[i][j]
        if (total > 0.0) {
            for (i in 0 until size) for (j in 0 until size) grid[i][j] /= total
        }
        p = grid

        home = DoubleArray(size)
        away = DoubleArray(size)
        for (i in 0 until size) for (j in 0 until size) {
            home[i] += p[i][j]
            away[j] += p[i][j]
        }
    }

    inline fun sumWhere(predicate: (Int, Int) -> Boolean): Double {
        var s = 0.0
        for (i in 0 until size) for (j in 0 until size) if (predicate(i, j)) s += p[i][j]
        return s
    }

    val pHome: Double get() = sumWhere { i, j -> i > j }
    val pDraw: Double get() = sumWhere { i, j -> i == j }
    val pAway: Double get() = sumWhere { i, j -> i < j }

    val expectedHome: Double get() = (0 until size).sumOf { i -> i * home[i] }
    val expectedAway: Double get() = (0 until size).sumOf { j -> j * away[j] }
    val expectedTotal: Double get() = expectedHome + expectedAway

    fun over(line: Double): Double = sumWhere { i, j -> i + j > line }

    fun under(line: Double): Double = 1.0 - over(line)

    fun homeOver(line: Double): Double = (0 until size).sumOf { i -> if (i > line) home[i] else 0.0 }

    fun awayOver(line: Double): Double = (0 until size).sumOf { j -> if (j > line) away[j] else 0.0 }

    /** Top scorelines by probability. */
    fun topScores(n: Int): List<Triple<Int, Int, Double>> {
        val all = ArrayList<Triple<Int, Int, Double>>(size * size)
        for (i in 0 until size) for (j in 0 until size) all.add(Triple(i, j, p[i][j]))
        return all.sortedByDescending { it.third }.take(n)
    }

    /** Convolves two independent grids — used to join first and second half. */
    fun convolve(other: Grid, maxK: Int): Grid {
        val h = DoubleArray(maxK + 1)
        val a = DoubleArray(maxK + 1)
        for (i in 0 until size) for (k in 0 until other.size) {
            val idx = minOf(i + k, maxK)
            h[idx] += home[i] * other.home[k]
            a[idx] += away[i] * other.away[k]
        }
        return Grid(h, a)
    }
}
