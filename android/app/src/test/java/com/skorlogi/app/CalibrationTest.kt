package com.skorlogi.app

import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.FootballData
import com.skorlogi.app.data.Match
import com.skorlogi.app.engine.LeagueModel
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Measures calibration per market, out of sample.
 *
 * Overall accuracy is the wrong question for a picks list. What matters there is:
 * when the model claims 70% on this kind of bet, how often is it actually right?
 * That is what these buckets answer, and the numbers feed the reliability figures
 * the app shows next to each pick.
 */
class CalibrationTest {

    private val dataDir = listOf(File("backtest-data"), File("../backtest-data"))
        .firstOrNull { it.isDirectory } ?: File("backtest-data")

    private class Bucket {
        var n = 0
        var hits = 0
        var predicted = 0.0
        fun add(p: Double, hit: Boolean) { n++; predicted += p; if (hit) hits++ }
        val actual get() = if (n == 0) 0.0 else hits.toDouble() / n
        val mean get() = if (n == 0) 0.0 else predicted / n
    }

    private class MarketStats(val name: String) {
        // 50-60, 60-70, 70-80, 80-90, 90-100
        val buckets = Array(5) { Bucket() }
        fun add(p: Double, hit: Boolean) {
            if (p < 0.5) return
            val i = ((p - 0.5) / 0.1).toInt().coerceIn(0, 4)
            buckets[i].add(p, hit)
        }
        fun report() {
            println("  $name")
            val labels = listOf("50-60%", "60-70%", "70-80%", "80-90%", "90%+")
            for (i in 0..4) {
                val b = buckets[i]
                if (b.n < 20) continue
                println("    %-8s model %5.1f%%  nyata %5.1f%%  (n=%d)"
                    .format(labels[i], b.mean * 100, b.actual * 100, b.n))
            }
        }
    }

    @Test
    fun calibrationByMarket() {
        Assume.assumeTrue(dataDir.isDirectory)
        val files = dataDir.listFiles { f: File -> f.name.endsWith(".csv") }!!.sortedBy { it.name }
        val byLeague = HashMap<String, MutableList<Match>>()
        for (f in files) {
            val code = f.name.substringBefore('_')
            byLeague.getOrPut(code) { ArrayList() }.addAll(FootballData.parseMain(code, f.readText()))
        }

        val fav = MarketStats("Hasil akhir — unggulan")
        val dc = MarketStats("Double chance")
        val o15 = MarketStats("Over 1.5 gol")
        val o25 = MarketStats("Over/Under 2.5 gol")
        val btts = MarketStats("Kedua tim cetak gol")
        val ht05 = MarketStats("Babak 1 ada gol")
        val corners = MarketStats("Total corner over/under 9.5")

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

                    // Whichever side the model actually leans towards.
                    val probs = listOf(p.pHome to 'H', p.pDraw to 'D', p.pAway to 'A')
                    val top = probs.maxByOrNull { it.first }!!
                    fav.add(top.first, match.result == top.second)

                    p.groups.first { it.title.startsWith("Double Chance") }.lines
                        .filter { it.label.contains('(') }
                        .maxByOrNull { it.prob }?.let { line ->
                            val hit = when {
                                line.label.contains("(1X)") -> match.result != 'A'
                                line.label.contains("(12)") -> match.result != 'D'
                                else -> match.result != 'H'
                            }
                            dc.add(line.prob, hit)
                        }

                    val totals = p.groups.first { it.title == "Total Gol" }
                    fun side(label: String, over: Double, hit: Boolean, stats: MarketStats) {
                        if (over >= 0.5) stats.add(over, hit) else stats.add(1 - over, !hit)
                    }
                    side("1.5", totals.lines.first { it.label == "Over 1.5" }.prob, total > 1, o15)
                    side("2.5", totals.lines.first { it.label == "Over 2.5" }.prob, total > 2, o25)

                    val bt = p.groups.first { it.title.startsWith("Kedua Tim") }
                        .lines.first { it.label == "Ya" }.prob
                    val btHit = match.homeGoals > 0 && match.awayGoals > 0
                    if (bt >= 0.5) btts.add(bt, btHit) else btts.add(1 - bt, !btHit)

                    if (match.hasHalfTime) {
                        p.groups.firstOrNull { it.title == "Babak Pertama" }
                            ?.lines?.firstOrNull { it.label == "Babak 1 over 0.5" }
                            ?.let {
                                val hit = match.htHomeGoals + match.htAwayGoals > 0
                                if (it.prob >= 0.5) ht05.add(it.prob, hit) else ht05.add(1 - it.prob, !hit)
                            }
                    }

                    if (match.hasCorners) {
                        p.groups.firstOrNull { it.title == "Sepak Pojok" }
                            ?.lines?.firstOrNull { it.label == "Total corner over 9.5" }
                            ?.let {
                                val hit = match.homeCorners + match.awayCorners > 9
                                if (it.prob >= 0.5) corners.add(it.prob, hit) else corners.add(1 - it.prob, !hit)
                            }
                    }
                }
            }
        }

        println()
        println("=== Kalibrasi per market (out-of-sample) ===")
        println("Dibaca: kalau model bilang X%, kenyataannya terjadi Y%.")
        println()
        listOf(ht05, o15, corners, o25, btts, dc, fav).forEach { it.report() }
        println()
    }
}
