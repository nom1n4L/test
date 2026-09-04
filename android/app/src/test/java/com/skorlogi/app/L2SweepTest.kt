package com.skorlogi.app

import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.FootballData
import com.skorlogi.app.data.Match
import com.skorlogi.app.engine.LeagueModel
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.ln

/** Sweeps the shrinkage strength against held-out seasons. */
class L2SweepTest {

    private val dataDir = listOf(File("backtest-data"), File("../backtest-data"))
        .firstOrNull { it.isDirectory } ?: File("backtest-data")

    @Test
    fun sweepShrinkage() {
        Assume.assumeTrue(dataDir.isDirectory)
        val files = dataDir.listFiles { f: File -> f.name.endsWith(".csv") }!!.sortedBy { it.name }
        val byLeague = HashMap<String, MutableList<Match>>()
        for (f in files) {
            val code = f.name.substringBefore('_')
            byLeague.getOrPut(code) { ArrayList() }.addAll(FootballData.parseMain(code, f.readText()))
        }

        println()
        println("%-8s %10s %10s %10s %10s".format("L2", "logloss", "akurasi", "O/U 2.5", "BTTS"))
        for (l2 in listOf(0.035, 0.5, 1.5, 3.0, 5.0, 8.0)) {
            var ll = 0.0; var ok = 0; var n = 0; var ou = 0; var btts = 0
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
                        model = LeagueModel.build(
                            code, all.filter { it.dateEpochDay < day }, day,
                            LeagueModel.DEFAULT_DECAY, l2,
                        )
                        lastFit = day
                    }
                    val m = model ?: continue
                    for (match in all.filter { it.dateEpochDay == day }) {
                        val p = m.predict(Fixture(code, day, "", match.home, match.away)) ?: continue
                        val probs = doubleArrayOf(p.pHome, p.pDraw, p.pAway)
                        val actual = when (match.result) { 'H' -> 0; 'D' -> 1; else -> 2 }
                        n++
                        ll -= ln(probs[actual].coerceAtLeast(1e-9))
                        if (probs.indices.maxByOrNull { probs[it] } == actual) ok++
                        val over = p.groups.first { it.title == "Total Gol" }
                            .lines.first { it.label == "Over 2.5" }.prob
                        if ((over >= 0.5) == (match.homeGoals + match.awayGoals > 2)) ou++
                        val bt = p.groups.first { it.title.startsWith("Kedua Tim") }
                            .lines.first { it.label == "Ya" }.prob
                        if ((bt >= 0.5) == (match.homeGoals > 0 && match.awayGoals > 0)) btts++
                    }
                }
            }
            println("%-8.3f %10.4f %9.1f%% %9.1f%% %9.1f%%"
                .format(l2, ll / n, 100.0 * ok / n, 100.0 * ou / n, 100.0 * btts / n))
        }
        println()
    }
}
