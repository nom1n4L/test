package com.skorlogi.app

import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.FootballData
import com.skorlogi.app.data.Match
import com.skorlogi.app.engine.LeagueModel
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * Sweeps the two tunable knobs — the time-decay rate and how much weight to give
 * Elo when blending it with the Dixon–Coles probabilities — and reports out-of-sample
 * log loss for each combination. Log loss rather than accuracy, because it rewards
 * being calibrated rather than merely being on the right side of 50%.
 *
 * This is a tuning aid, not a correctness check; it prints a table and asserts only
 * that the shipped default is not obviously wrong.
 */
class TuningTest {

    private val dataDir = listOf(File("backtest-data"), File("../backtest-data"))
        .firstOrNull { it.isDirectory } ?: File("backtest-data")

    @Test
    fun sweepDecayAndBlend() {
        Assume.assumeTrue("backtest-data/ tidak ada — dilewati", dataDir.isDirectory)
        val files = dataDir.listFiles { f: File -> f.name.endsWith(".csv") }!!.sortedBy { it.name }

        val byLeague = HashMap<String, MutableList<Match>>()
        for (f in files) {
            val code = f.name.substringBefore('_')
            byLeague.getOrPut(code) { ArrayList() }.addAll(FootballData.parseMain(code, f.readText()))
        }

        val decays = listOf(0.0008, 0.0015, 0.0025, 0.0035, 0.0055)
        val blends = listOf(0.20, 0.30, 0.40, 0.50, 0.65)

        // decay -> blend -> (logloss sum, correct, n)
        val results = HashMap<Pair<Double, Double>, Triple<Double, Int, Int>>()

        for (decay in decays) {
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
                        model = LeagueModel.build(code, all.filter { it.dateEpochDay < day }, day, decay)
                        lastFit = day
                    }
                    val m = model ?: continue
                    for (match in all.filter { it.dateEpochDay == day }) {
                        val p = m.predict(
                            Fixture(code, day, "", match.home, match.away)
                        ) ?: continue
                        val actual = when (match.result) {
                            'H' -> 0
                            'D' -> 1
                            else -> 2
                        }
                        val dc = doubleArrayOf(p.pHome, p.pDraw, p.pAway)
                        for (w in blends) {
                            val mixed = DoubleArray(3) { i -> (1 - w) * dc[i] + w * p.eloProbs[i] }
                            val norm = mixed.sum()
                            val prob = (mixed[actual] / norm).coerceAtLeast(1e-9)
                            val best = mixed.indices.maxByOrNull { mixed[it] }
                            val key = decay to w
                            val cur = results[key] ?: Triple(0.0, 0, 0)
                            results[key] = Triple(
                                cur.first - ln(prob),
                                cur.second + if (best == actual) 1 else 0,
                                cur.third + 1,
                            )
                        }
                    }
                }
            }
        }

        println()
        println("=== Sweep: log loss out-of-sample (makin kecil makin baik) ===")
        println("%-10s %s".format("decay", blends.joinToString("  ") { "elo=%.2f".format(it) }))
        var best = Double.MAX_VALUE
        var bestKey = 0.0 to 0.0
        for (d in decays) {
            val cells = blends.map { w ->
                val (ll, ok, n) = results[d to w]!!
                val avg = ll / n
                if (avg < best) { best = avg; bestKey = d to w }
                "%.4f/%.1f%%".format(avg, 100.0 * ok / n)
            }
            println("%-10.4f %s".format(d, cells.joinToString("  ")))
        }
        println()
        println("Terbaik: decay=%.4f, bobot Elo=%.2f, log loss=%.4f".format(bestKey.first, bestKey.second, best))
        println("(format sel: log loss / akurasi)")
        println()

        assert(results.isNotEmpty())
    }
}
