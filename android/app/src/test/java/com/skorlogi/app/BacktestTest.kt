package com.skorlogi.app

import com.skorlogi.app.data.FootballData
import com.skorlogi.app.data.Match
import com.skorlogi.app.engine.LeagueModel
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Walk-forward backtest of the prediction engine.
 *
 * For each match in the evaluation window the model is refitted using only matches
 * that had already been played at that point, so nothing leaks backwards from the
 * result being predicted. The refit happens once a week rather than once a match,
 * which is how the app itself behaves between data refreshes.
 *
 * Skipped unless `backtest-data/` holds the season CSVs, so a normal build does not
 * depend on the network.
 */
class BacktestTest {

    // Unit tests run with the module directory as the working directory, so the
    // data can sit either beside the module or at the project root.
    private val dataDir = listOf(File("backtest-data"), File("../backtest-data"))
        .firstOrNull { it.isDirectory } ?: File("backtest-data")

    private class Tally(val name: String) {
        var n = 0
        var correct = 0
        var logLoss = 0.0
        var brier = 0.0

        fun add(probs: DoubleArray, actual: Int) {
            n++
            if (probs.indices.maxByOrNull { probs[it] } == actual) correct++
            logLoss -= ln(probs[actual].coerceAtLeast(1e-9))
            for (i in probs.indices) {
                val y = if (i == actual) 1.0 else 0.0
                brier += (probs[i] - y) * (probs[i] - y)
            }
        }

        val accuracy get() = if (n == 0) 0.0 else correct.toDouble() / n
        override fun toString() =
            "%-22s n=%4d  akurasi=%5.1f%%  logloss=%.4f  brier=%.4f"
                .format(name, n, accuracy * 100, if (n == 0) 0.0 else logLoss / n, if (n == 0) 0.0 else brier / n)
    }

    private class BinaryTally(val name: String) {
        var n = 0
        var correct = 0
        fun add(pYes: Double, yes: Boolean) {
            n++
            if ((pYes >= 0.5) == yes) correct++
        }
        val accuracy get() = if (n == 0) 0.0 else correct.toDouble() / n
        override fun toString() = "%-22s n=%4d  akurasi=%5.1f%%".format(name, n, accuracy * 100)
    }

    @Test
    fun walkForwardAccuracy() {
        Assume.assumeTrue("backtest-data/ tidak ada — dilewati", dataDir.isDirectory)
        val files = dataDir.listFiles { f: File -> f.name.endsWith(".csv") }?.sortedBy { it.name }
        Assume.assumeTrue(!files.isNullOrEmpty())

        val byLeague = HashMap<String, MutableList<Match>>()
        val odds = HashMap<String, DoubleArray>()
        for (f in files!!) {
            val code = f.name.substringBefore('_')
            val body = f.readText()
            byLeague.getOrPut(code) { ArrayList() }.addAll(FootballData.parseMain(code, body))
            for (row in com.skorlogi.app.data.Csv.parse(body)) {
                val date = com.skorlogi.app.data.Dates.parseFeedDate(row["Date"].orEmpty()) ?: continue
                val h = row["B365H"]?.toDoubleOrNull() ?: continue
                val d = row["B365D"]?.toDoubleOrNull() ?: continue
                val a = row["B365A"]?.toDoubleOrNull() ?: continue
                if (h <= 1.0 || d <= 1.0 || a <= 1.0) continue
                odds["$date|${row["HomeTeam"]}|${row["AwayTeam"]}"] = doubleArrayOf(h, d, a)
            }
        }

        val model1x2 = Tally("Dixon-Coles 1X2")
        val bookie1x2 = Tally("Bandar (odds B365)")
        val homeAlways = Tally("Selalu tebak kandang")
        val ou25 = BinaryTally("Over/Under 2.5")
        val bttsTally = BinaryTally("BTTS")
        val htTally = BinaryTally("Babak 1 over 0.5")

        var evaluated = 0

        for ((code, raw) in byLeague.toSortedMap()) {
            val all = raw.distinctBy { "${it.dateEpochDay}|${it.home}|${it.away}" }
                .sortedBy { it.dateEpochDay }
            if (all.size < 400) continue

            // Burn in on everything before the last season, evaluate on the rest.
            val evalStart = all[(all.size * 0.62).toInt()].dateEpochDay
            val evalDays = all.filter { it.dateEpochDay >= evalStart }
                .map { it.dateEpochDay }.distinct().sorted()

            var model: LeagueModel? = null
            var lastFit = 0L

            for (day in evalDays) {
                // Refit at most weekly, using only matches already played.
                if (model == null || day - lastFit >= 7) {
                    val history = all.filter { it.dateEpochDay < day }
                    model = LeagueModel.build(code, history, day)
                    lastFit = day
                }
                val m = model ?: continue

                for (match in all.filter { it.dateEpochDay == day }) {
                    val fixture = com.skorlogi.app.data.Fixture(
                        league = code,
                        dateEpochDay = day,
                        time = "",
                        home = match.home,
                        away = match.away,
                    )
                    val p = m.predict(fixture) ?: continue
                    evaluated++

                    val actual = when (match.result) {
                        'H' -> 0
                        'D' -> 1
                        else -> 2
                    }
                    model1x2.add(doubleArrayOf(p.pHome, p.pDraw, p.pAway), actual)
                    homeAlways.add(doubleArrayOf(0.46, 0.26, 0.28), actual)

                    // The bookmaker's own view of the same match, with its margin
                    // divided out so the comparison is like for like.
                    odds["$day|${match.home}|${match.away}"]?.let { o ->
                        val inv = doubleArrayOf(1 / o[0], 1 / o[1], 1 / o[2])
                        val overround = inv.sum()
                        bookie1x2.add(doubleArrayOf(inv[0] / overround, inv[1] / overround, inv[2] / overround), actual)
                    }

                    val over = p.groups.first { it.title == "Total Gol" }
                        .lines.first { it.label == "Over 2.5" }.prob
                    ou25.add(over, match.homeGoals + match.awayGoals > 2)

                    val btts = p.groups.first { it.title.startsWith("Kedua Tim") }
                        .lines.first { it.label == "Ya" }.prob
                    bttsTally.add(btts, match.homeGoals > 0 && match.awayGoals > 0)

                    if (match.hasHalfTime) {
                        p.groups.firstOrNull { it.title == "Babak Pertama" }
                            ?.lines?.firstOrNull { it.label == "Babak 1 over 0.5" }
                            ?.let { htTally.add(it.prob, match.htHomeGoals + match.htAwayGoals > 0) }
                    }
                }
            }
        }

        println()
        println("=== Backtest walk-forward, ${byLeague.keys.sorted()} ===")
        println("Pertandingan diuji: $evaluated")
        println(model1x2)
        println(bookie1x2)
        println(homeAlways)
        println(ou25)
        println(bttsTally)
        println(htTally)
        println()

        // Guard rails: the engine must beat the naive baseline and stay calibrated.
        assert(evaluated > 500) { "terlalu sedikit sampel: $evaluated" }
        assert(model1x2.accuracy > homeAlways.accuracy) {
            "model (${model1x2.accuracy}) tidak mengungguli tebakan kandang (${homeAlways.accuracy})"
        }
        assert(model1x2.logLoss / model1x2.n < 1.06) {
            "log loss terlalu tinggi: ${model1x2.logLoss / model1x2.n}"
        }
    }
}
