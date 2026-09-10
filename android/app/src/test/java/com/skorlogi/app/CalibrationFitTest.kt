package com.skorlogi.app

import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.FootballData
import com.skorlogi.app.data.Match
import com.skorlogi.app.engine.LeagueModel
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/**
 * Fits Platt scaling per market family on out-of-sample predictions.
 *
 * The raw model is confident in places it has not earned — corner lines above 70%
 * came in barely better than a coin toss. Rather than pretend otherwise, this
 * measures the relationship between what the model claims and what happens, as
 *
 *     calibrated_logit = a * raw_logit + b
 *
 * and prints the coefficients that get baked into Calibration.kt. An `a` near 1
 * means the family was honest to begin with; a small `a` means its confidence has
 * to be pulled back towards 50%.
 */
class CalibrationFitTest {

    private val dataDir = listOf(File("backtest-data"), File("../backtest-data"))
        .firstOrNull { it.isDirectory } ?: File("backtest-data")

    private class Samples(val name: String) {
        val logits = ArrayList<Double>()
        val outcomes = ArrayList<Boolean>()

        fun add(p: Double, hit: Boolean) {
            val q = p.coerceIn(1e-4, 1 - 1e-4)
            logits.add(ln(q / (1 - q)))
            outcomes.add(hit)
        }

        /** Gradient descent on log loss; two parameters, so this converges quickly. */
        fun fit(): Pair<Double, Double> {
            var a = 1.0
            var b = 0.0
            val n = logits.size
            if (n < 200) return 1.0 to 0.0
            repeat(4000) {
                var ga = 0.0
                var gb = 0.0
                for (i in 0 until n) {
                    val z = a * logits[i] + b
                    val p = 1.0 / (1.0 + exp(-z))
                    val err = p - (if (outcomes[i]) 1.0 else 0.0)
                    ga += err * logits[i]
                    gb += err
                }
                a -= 0.05 * ga / n
                b -= 0.05 * gb / n
            }
            return a to b
        }

        fun logLoss(a: Double, b: Double): Double {
            var ll = 0.0
            for (i in logits.indices) {
                val p = 1.0 / (1.0 + exp(-(a * logits[i] + b)))
                ll -= if (outcomes[i]) ln(p.coerceAtLeast(1e-9)) else ln((1 - p).coerceAtLeast(1e-9))
            }
            return ll / logits.size
        }
    }

    @Test
    fun fitPlattScaling() {
        Assume.assumeTrue(dataDir.isDirectory)
        val files = dataDir.listFiles { f: File -> f.name.endsWith(".csv") }!!.sortedBy { it.name }
        val byLeague = HashMap<String, MutableList<Match>>()
        for (f in files) {
            val code = f.name.substringBefore('_')
            byLeague.getOrPut(code) { ArrayList() }.addAll(FootballData.parseMain(code, f.readText()))
        }

        val result = Samples("RESULT (1X2 & double chance)")
        val totals = Samples("TOTALS (over/under gol)")
        val btts = Samples("BTTS")
        val half = Samples("HALF (babak 1)")
        val corners = Samples("CORNERS")
        val cards = Samples("CARDS")

        for ((code, raw) in byLeague.toSortedMap()) {
            val all = raw.distinctBy { "${it.dateEpochDay}|${it.home}|${it.away}" }
                .sortedBy { it.dateEpochDay }
            if (all.size < 400) continue
            val evalStart = all[(all.size * 0.62).toInt()].dateEpochDay
            val evalDays = all.filter { it.dateEpochDay >= evalStart }
                .map { it.dateEpochDay }.distinct().sorted()

            var model: LeagueModel? = null
            var lastFit = 0L
            for (day in evalDays) {
                if (model == null || day - lastFit >= 7) {
                    model = LeagueModel.build(code, all.filter { it.dateEpochDay < day }, day)
                    lastFit = day
                }
                val m = model ?: continue
                for (match in all.filter { it.dateEpochDay == day }) {
                    val p = m.predict(Fixture(code, day, "", match.home, match.away)) ?: continue
                    val total = match.homeGoals + match.awayGoals

                    result.add(p.pHome, match.result == 'H')
                    result.add(p.pDraw, match.result == 'D')
                    result.add(p.pAway, match.result == 'A')

                    val tg = p.groups.first { it.title == "Total Gol" }
                    for ((label, line) in listOf(1.5, 2.5, 3.5).map { l ->
                        "$l" to tg.lines.first { it.label == "Over $l" }
                    }) {
                        totals.add(line.prob, total > label.toDouble())
                    }

                    btts.add(
                        p.groups.first { it.title.startsWith("Kedua Tim") }.lines.first { it.label == "Ya" }.prob,
                        match.homeGoals > 0 && match.awayGoals > 0,
                    )

                    if (match.hasHalfTime) {
                        val ht = match.htHomeGoals + match.htAwayGoals
                        p.groups.firstOrNull { it.title == "Babak Pertama" }?.let { g ->
                            g.lines.firstOrNull { it.label == "Babak 1 over 0.5" }?.let { half.add(it.prob, ht > 0) }
                            g.lines.firstOrNull { it.label == "Babak 1 over 1.5" }?.let { half.add(it.prob, ht > 1) }
                        }
                    }

                    if (match.hasCorners) {
                        val c = match.homeCorners + match.awayCorners
                        p.groups.firstOrNull { it.title == "Sepak Pojok" }?.let { g ->
                            for (l in listOf(8.5, 9.5, 10.5)) {
                                g.lines.firstOrNull { it.label == "Total corner over $l" }
                                    ?.let { corners.add(it.prob, c > l) }
                            }
                        }
                    }

                    if (match.hasCards) {
                        val c = match.homeCards + match.awayCards
                        p.groups.firstOrNull { it.title == "Kartu" }?.let { g ->
                            for (l in listOf(3.5, 4.5)) {
                                g.lines.firstOrNull { it.label == "Total poin kartu over $l" }
                                    ?.let { cards.add(it.prob, c > l) }
                            }
                        }
                    }
                }
            }
        }

        println()
        println("=== Platt scaling per keluarga market ===")
        println("%-30s %8s %8s %10s %10s %8s".format("keluarga", "a", "b", "logloss0", "logloss1", "n"))
        for (s in listOf(result, totals, btts, half, corners, cards)) {
            val (a, b) = s.fit()
            println("%-30s %8.4f %8.4f %10.4f %10.4f %8d"
                .format(s.name, a, b, s.logLoss(1.0, 0.0), s.logLoss(a, b), s.logits.size))
        }
        println()
        println("a < 1 berarti model terlalu percaya diri dan harus ditarik ke 50%.")
        println()
    }
}
