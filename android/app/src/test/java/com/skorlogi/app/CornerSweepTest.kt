package com.skorlogi.app

import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.FootballData
import com.skorlogi.app.data.Match
import com.skorlogi.app.engine.LeagueModel
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Finds the shrinkage at which corner predictions stop over-claiming. */
class CornerSweepTest {

    private val dataDir = listOf(File("backtest-data"), File("../backtest-data"))
        .firstOrNull { it.isDirectory } ?: File("backtest-data")

    @Test
    fun sweepCornerShrinkage() {
        Assume.assumeTrue(dataDir.isDirectory)
        val files = dataDir.listFiles { f: File -> f.name.endsWith(".csv") }!!.sortedBy { it.name }
        val byLeague = HashMap<String, MutableList<Match>>()
        for (f in files) {
            val code = f.name.substringBefore('_')
            byLeague.getOrPut(code) { ArrayList() }.addAll(FootballData.parseMain(code, f.readText()))
        }

        println()
        println("%-8s %-26s %-26s %s".format("L2", "saat model 60-70%", "saat model 70%+", "akurasi keseluruhan"))
        for (l2 in listOf(1.0, 4.0, 8.0, 12.0, 20.0, 40.0)) {
            var midN = 0; var midHit = 0; var midP = 0.0
            var hiN = 0; var hiHit = 0; var hiP = 0.0
            var n = 0; var ok = 0
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
                            LeagueModel.DEFAULT_DECAY, com.skorlogi.app.engine.RatingFitter.DEFAULT_L2, l2,
                        )
                        lastFit = day
                    }
                    val m = model ?: continue
                    for (match in all.filter { it.dateEpochDay == day && it.hasCorners }) {
                        val p = m.predict(Fixture(code, day, "", match.home, match.away)) ?: continue
                        val line = p.groups.firstOrNull { it.title == "Sepak Pojok" }
                            ?.lines?.firstOrNull { it.label == "Total corner over 9.5" } ?: continue
                        val overHit = match.homeCorners + match.awayCorners > 9
                        val prob = if (line.prob >= 0.5) line.prob else 1 - line.prob
                        val hit = if (line.prob >= 0.5) overHit else !overHit
                        n++; if (hit) ok++
                        when {
                            prob >= 0.7 -> { hiN++; hiP += prob; if (hit) hiHit++ }
                            prob >= 0.6 -> { midN++; midP += prob; if (hit) midHit++ }
                        }
                    }
                }
            }
            fun fmt(nn: Int, hh: Int, pp: Double) =
                if (nn < 20) "-" else "klaim %.0f%% nyata %.0f%% (n=%d)".format(100 * pp / nn, 100.0 * hh / nn, nn)
            println("%-8.1f %-26s %-26s %.1f%%"
                .format(l2, fmt(midN, midHit, midP), fmt(hiN, hiHit, hiP), 100.0 * ok / n))
        }
        println()
    }
}
