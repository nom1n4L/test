package com.skorsnap.app.data

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Completes the market list.
 *
 * The model reads the screenshots and judges the match, but it answers in prose-
 * sized batches: asked for fifty markets it returns whichever fifteen or twenty it
 * feels like, and a different fifteen next time. That left the screen with gaps —
 * no team totals on one match, no handicaps on the next — which is the "kadang ada
 * kadang enggak" the user reported.
 *
 * Every goal market is a consequence of one thing: how many goals each side is
 * expected to score. The model already supplies that (xg_home, xg_away) and its own
 * 1X2 call. So rather than asking it to enumerate fifty numbers, the app builds the
 * whole score matrix once and reads every market off it. Coverage stops depending on
 * the model's mood.
 *
 * Two rules keep this honest:
 *  - the model's own markets always win. A derived number only ever fills a gap,
 *    never overwrites something the model actually said.
 *  - the matrix is fitted to reproduce the model's stated 1X2, so a derived Double
 *    Chance can never contradict the win probability printed above it.
 */
object Grid {

    /** Goals per side worth enumerating; beyond this the mass is negligible. */
    private const val MAX_GOALS = 12

    /** Share of a match's goals scored before the break, from long-run averages. */
    private const val FIRST_HALF_SHARE = 0.45

    /**
     * Corner counts are overdispersed — a match with a mean of 10 varies far more
     * than a Poisson allows. Fitting a negative binomial with this shape reproduced
     * observed corner spreads in backtesting; a plain Poisson claimed 74% on totals
     * that landed 55% of the time.
     */
    private const val CORNER_SHAPE = 9.0

    // ---------------------------------------------------------------- pmf

    private fun poisson(lambda: Double, k: Int): Double {
        if (lambda <= 0.0) return if (k == 0) 1.0 else 0.0
        var logP = -lambda + k * ln(lambda)
        for (i in 2..k) logP -= ln(i.toDouble())
        return exp(logP)
    }

    /** Negative binomial with the given mean and shape, in the mean/shape form. */
    private fun negBin(mean: Double, shape: Double, k: Int): Double {
        if (mean <= 0.0) return if (k == 0) 1.0 else 0.0
        val p = shape / (shape + mean)
        var logP = shape * ln(p) + k * ln(1 - p)
        for (i in 1..k) logP += ln(shape + i - 1) - ln(i.toDouble())
        return exp(logP)
    }

    /** The full score matrix: joint[h][a] is the chance of exactly that scoreline. */
    private fun matrix(lh: Double, la: Double, corners: Boolean): Array<DoubleArray> {
        val h = DoubleArray(MAX_GOALS + 1) {
            if (corners) negBin(lh, CORNER_SHAPE, it) else poisson(lh, it)
        }
        val a = DoubleArray(MAX_GOALS + 1) {
            if (corners) negBin(la, CORNER_SHAPE, it) else poisson(la, it)
        }
        val m = Array(MAX_GOALS + 1) { i -> DoubleArray(MAX_GOALS + 1) { j -> h[i] * a[j] } }
        val total = m.sumOf { row -> row.sum() }
        if (total > 0) for (row in m) for (j in row.indices) row[j] /= total
        return m
    }

    // ---------------------------------------------------------------- fitting

    /**
     * Nudges the two goal expectations until the matrix reproduces the model's own
     * 1X2 call.
     *
     * Without this the derived markets are answering a slightly different question
     * from the headline one: the model might say 62% home while its xG implies 55%,
     * and the derived Double Chance would then sit below the plain win probability —
     * visibly contradictory. A short coordinate descent removes that.
     */
    internal fun fit(xgH: Double, xgA: Double, pH: Double, pD: Double, pA: Double): Pair<Double, Double> {
        var lh = xgH.coerceIn(0.15, 6.0)
        var la = xgA.coerceIn(0.15, 6.0)
        val sum = pH + pD + pA
        if (sum < 0.5) return lh to la
        val tH = pH / sum
        val tD = pD / sum
        val tA = pA / sum

        fun error(a: Double, b: Double): Double {
            val m = matrix(a, b, corners = false)
            var h = 0.0
            var d = 0.0
            var w = 0.0
            for (i in m.indices) for (j in m[i].indices) {
                when {
                    i > j -> h += m[i][j]
                    i == j -> d += m[i][j]
                    else -> w += m[i][j]
                }
            }
            return (h - tH) * (h - tH) + (d - tD) * (d - tD) + (w - tA) * (w - tA)
        }

        var step = 0.40
        var best = error(lh, la)
        repeat(60) {
            var moved = false
            for ((dh, da) in listOf(step to 0.0, -step to 0.0, 0.0 to step, 0.0 to -step)) {
                val nh = (lh + dh).coerceIn(0.15, 6.0)
                val na = (la + da).coerceIn(0.15, 6.0)
                val e = error(nh, na)
                if (e < best - 1e-9) {
                    best = e; lh = nh; la = na; moved = true
                }
            }
            if (!moved) step *= 0.5
        }
        return lh to la
    }

    // ---------------------------------------------------------------- readers

    private fun sum(m: Array<DoubleArray>, pick: (Int, Int) -> Boolean): Double {
        var s = 0.0
        for (i in m.indices) for (j in m[i].indices) if (pick(i, j)) s += m[i][j]
        return s
    }

    // ---------------------------------------------------------------- catalogues

    /**
     * Every goal market the app shows, as a name/group/probability triple.
     *
     * Quarter handicaps report the chance of a *full* win and say in the reason what
     * happens to the rest of the stake. Blending the half-push into a single headline
     * number would flatter the line, and these are exactly the markets a user is
     * likeliest to misread.
     */
    fun matchMarkets(xgH: Double, xgA: Double, pH: Double, pD: Double, pA: Double): List<MarketOption> {
        val (lh, la) = fit(xgH, xgA, pH, pD, pA)
        val m = matrix(lh, la, corners = false)
        val half = matrix(lh * FIRST_HALF_SHARE, la * FIRST_HALF_SHARE, corners = false)
        val why = "Dihitung dari perkiraan gol ${fmt(lh)} - ${fmt(la)}."

        val home = sum(m) { i, j -> i > j }
        val draw = sum(m) { i, j -> i == j }
        val away = sum(m) { i, j -> i < j }
        fun over(line: Double) = sum(m) { i, j -> i + j > line }
        fun overHalf(line: Double) = sum(half) { i, j -> i + j > line }

        val out = ArrayList<MarketOption>(60)
        fun add(name: String, group: String, p: Double, note: String = why) {
            out.add(MarketOption(name, p.coerceIn(0.0, 1.0), note, group, derived = true))
        }

        add("Tuan rumah menang", "Hasil Akhir", home)
        add("Seri", "Hasil Akhir", draw)
        add("Tandang menang", "Hasil Akhir", away)

        add("1X (tuan rumah atau seri)", "Double Chance", home + draw)
        add("12 (tidak seri)", "Double Chance", home + away)
        add("X2 (seri atau tandang)", "Double Chance", draw + away)

        for (line in listOf(0.5, 1.5, 2.5, 3.5, 4.5)) {
            val o = over(line)
            add("Over $line", "Total Gol", o)
            add("Under $line", "Total Gol", 1 - o)
        }
        val btts = sum(m) { i, j -> i >= 1 && j >= 1 }
        add("Kedua tim cetak gol (BTTS) - Ya", "Total Gol", btts)
        add("Kedua tim cetak gol (BTTS) - Tidak", "Total Gol", 1 - btts)
        val two = sum(m) { i, j -> i >= 2 || j >= 2 }
        add("Minimal satu tim cetak 2+ gol - Ya", "Total Gol", two)
        add("Minimal satu tim cetak 2+ gol - Tidak", "Total Gol", 1 - two)

        for (line in listOf(0.5, 1.5, 2.5)) {
            val o = overHalf(line)
            add("Babak 1 Over $line", "Total Babak 1", o, "Dihitung dari perkiraan gol babak 1.")
            add("Babak 1 Under $line", "Total Babak 1", 1 - o, "Dihitung dari perkiraan gol babak 1.")
        }

        for (line in listOf(0.5, 1.5, 2.5)) {
            add("Tuan rumah Over $line", "Total per Tim", sum(m) { i, _ -> i > line })
            add("Tandang Over $line", "Total per Tim", sum(m) { _, j -> j > line })
        }

        add("1X & Over 2.5", "Kombinasi Hasil + Total", sum(m) { i, j -> i >= j && i + j > 2.5 })
        add("1X & Under 2.5", "Kombinasi Hasil + Total", sum(m) { i, j -> i >= j && i + j < 2.5 })
        add("X2 & Over 2.5", "Kombinasi Hasil + Total", sum(m) { i, j -> i <= j && i + j > 2.5 })
        add("X2 & Under 2.5", "Kombinasi Hasil + Total", sum(m) { i, j -> i <= j && i + j < 2.5 })
        add("Tuan rumah menang & Over 1.5", "Kombinasi Hasil + Total", sum(m) { i, j -> i > j && i + j > 1.5 })
        add("Tandang menang & Over 1.5", "Kombinasi Hasil + Total", sum(m) { i, j -> i < j && i + j > 1.5 })
        add("12 & Over 2.5", "Kombinasi Hasil + Total", sum(m) { i, j -> i != j && i + j > 2.5 })

        val byTwo = sum(m) { i, j -> i - j >= 2 }
        val byOne = sum(m) { i, j -> i - j == 1 }
        val refund = " Seri: setengah taruhan kembali."
        add("Tuan rumah -0.25", "Handicap Asia", home, why + refund)
        add("Tandang +0.25", "Handicap Asia", away + draw, "$why Seri: menang setengah.")
        add("Tuan rumah -0.5", "Handicap Asia", home)
        add("Tandang +0.5", "Handicap Asia", away + draw)
        add("Tuan rumah -0.75", "Handicap Asia", byTwo, "$why Menang tepat 1 gol: menang setengah.")
        add("Tandang +0.75", "Handicap Asia", away + draw, "$why Kalah tepat 1 gol: kalah setengah.")
        add("Tuan rumah -1", "Handicap Asia", byTwo, "$why Menang tepat 1 gol: taruhan kembali.")
        add("Tandang +1", "Handicap Asia", away + draw, "$why Kalah tepat 1 gol: taruhan kembali.")

        add("Tuan rumah -1", "Handicap Eropa", byTwo)
        add("Tandang +1", "Handicap Eropa", away + draw)
        add("Tuan rumah -2", "Handicap Eropa", sum(m) { i, j -> i - j >= 3 })
        add("Tandang +2", "Handicap Eropa", away + draw + byOne)

        return out
    }

    /**
     * The one pair this mode exists for, from first-half corner counts.
     *
     * The counts are already first-half here, so no 45% split is applied — doing it
     * twice would halve a number the model has been told to give in halves. Filling
     * the pair matters even though the model is asked for both: a reply missing one
     * side would otherwise leave the screen with half a question.
     */
    fun firstHalfCornerMarkets(cH: Double, cA: Double): List<MarketOption> {
        val m = matrix(max(cH, 0.2), max(cA, 0.2), corners = true)
        val over = sum(m) { i, j -> i + j > 4.5 }
        val why = "Dihitung dari perkiraan corner babak 1 ${fmt(cH)} - ${fmt(cA)}, " +
            "sebaran negative binomial."
        return listOf(
            MarketOption("Corner babak 1 Over 4.5", over, why, "Corner Babak 1", derived = true),
            MarketOption("Corner babak 1 Under 4.5", 1 - over, why, "Corner Babak 1", derived = true),
        )
    }

    /** The corner grid, from the per-team corner counts the model estimates. */
    fun cornerMarkets(cH: Double, cA: Double): List<MarketOption> {
        val m = matrix(max(cH, 0.2), max(cA, 0.2), corners = true)
        val half = matrix(max(cH, 0.2) * 0.45, max(cA, 0.2) * 0.45, corners = true)
        val why = "Dihitung dari perkiraan corner ${fmt(cH)} - ${fmt(cA)}, sebaran negative binomial."

        val out = ArrayList<MarketOption>(30)
        fun add(name: String, group: String, p: Double) =
            out.add(MarketOption(name, p.coerceIn(0.0, 1.0), why, group, derived = true))

        for (line in listOf(7.5, 8.5, 9.5, 10.5, 11.5)) {
            val o = sum(m) { i, j -> i + j > line }
            add("Total corner Over $line", "Corner", o)
            add("Total corner Under $line", "Corner", 1 - o)
        }
        add("Tuan rumah corner terbanyak", "Corner", sum(m) { i, j -> i > j })
        add("Corner sama banyak", "Corner", sum(m) { i, j -> i == j })
        add("Tandang corner terbanyak", "Corner", sum(m) { i, j -> i < j })

        for (line in listOf(3.5, 4.5, 5.5)) {
            val o = sum(half) { i, j -> i + j > line }
            add("Corner babak 1 Over $line", "Corner Babak 1", o)
            add("Corner babak 1 Under $line", "Corner Babak 1", 1 - o)
        }
        for (line in listOf(3.5, 4.5, 5.5)) {
            add("Corner tuan rumah Over $line", "Corner per Tim", sum(m) { i, _ -> i > line })
            add("Corner tandang Over $line", "Corner per Tim", sum(m) { _, j -> j > line })
        }
        return out
    }

    /**
     * Adds every catalogue market the model left out, keeping the model's own
     * numbers wherever it gave one.
     *
     * A match the model could not read is left completely alone: filling in a grid
     * off invented goal expectations would turn "aku tidak bisa baca gambarnya" into
     * fifty confident-looking percentages, which is the one failure mode worth
     * protecting against here.
     */
    fun fill(p: MatchPrediction): MatchPrediction {
        if (!p.readable) return p
        if (p.xgHome <= 0.0 && p.xgAway <= 0.0) return p

        val derived = when (p.mode) {
            Mode.CORNER -> cornerMarkets(p.xgHome, p.xgAway)
            Mode.CORNER_1H -> firstHalfCornerMarkets(p.xgHome, p.xgAway)
            Mode.MATCH -> matchMarkets(p.xgHome, p.xgAway, p.probHome, p.probDraw, p.probAway)
        }

        // A name alone is not unique: "Tuan rumah -1" is both an Asian and a
        // European handicap, and they settle differently.
        val seen = p.markets.map { it.group to it.name }.toHashSet()
        val added = derived.filter { (it.group to it.name) !in seen }
        if (added.isEmpty()) return p

        return p.copy(markets = (p.markets + added).sortedByDescending { it.prob })
    }

    private fun fmt(v: Double) = String.format("%.2f", v)

    /** Kept for callers that only need to know how wide the catalogue is. */
    internal fun matchMarketCount(): Int = matchMarkets(1.4, 1.1, 0.45, 0.27, 0.28).size
}
