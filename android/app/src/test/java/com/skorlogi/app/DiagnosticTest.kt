package com.skorlogi.app

import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.FootballData
import com.skorlogi.app.engine.Observation
import com.skorlogi.app.engine.RatingFitter
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.exp

/** Checks that a fitted model reproduces the average it was fitted on. */
class DiagnosticTest {

    private val dataDir = listOf(File("backtest-data"), File("../backtest-data"))
        .firstOrNull { it.isDirectory } ?: File("backtest-data")

    @Test
    fun fitReproducesLeagueAverages() {
        Assume.assumeTrue(dataDir.isDirectory)
        val code = System.getProperty("diag.league") ?: "E0"
        val files = dataDir.listFiles { f: File -> f.name.startsWith(code + "_") }!!
        val matches = files.flatMap { FootballData.parseMain(code, it.readText()) }
            .distinctBy { "${it.dateEpochDay}|${it.home}|${it.away}" }
        val today = matches.maxOf { it.dateEpochDay } + 1
        val teams = matches.flatMap { listOf(it.home, it.away) }.distinct().sorted()
        val idx = teams.withIndex().associate { (i, t) -> t to i }
        val decay = 0.0025

        fun obs(h: (com.skorlogi.app.data.Match) -> Int, a: (com.skorlogi.app.data.Match) -> Int) =
            matches.map {
                Observation(idx[it.home]!!, idx[it.away]!!, h(it), a(it), exp(-decay * (today - it.dateEpochDay)))
            }

        data class Case(val name: String, val obs: List<Observation>)
        val cases = listOf(
            Case("Gol penuh", obs({ it.homeGoals }, { it.awayGoals })),
            Case("Gol babak 1", obs({ it.htHomeGoals }, { it.htAwayGoals })),
            Case("Gol babak 2", obs({ it.homeGoals - it.htHomeGoals }, { it.awayGoals - it.htAwayGoals })),
            Case("Corner", obs({ it.homeCorners }, { it.awayCorners })),
            Case("Kartu", obs({ it.homeCards }, { it.awayCards })),
        )

        println()
        println("%-14s %10s %10s %8s".format("model", "rata2 asli", "rata2 fit", "selisih"))
        for (c in cases) {
            val valid = c.obs.filter { it.homeCount >= 0 && it.awayCount >= 0 }
            val wSum = valid.sumOf { it.weight }
            val empirical = valid.sumOf { it.weight * (it.homeCount + it.awayCount) } / wSum

            val r = RatingFitter.fit(teams, valid)!!
            // Average total the fitted model implies over exactly the same fixtures.
            var fitted = 0.0
            for (o in valid) {
                val lam = exp(r.intercept + r.attack[o.homeIdx] - r.defence[o.awayIdx] + r.homeAdv)
                val mu = exp(r.intercept + r.attack[o.awayIdx] - r.defence[o.homeIdx])
                fitted += o.weight * (lam + mu)
            }
            fitted /= wSum
            println("%-14s %10.3f %10.3f %+8.3f".format(c.name, empirical, fitted, fitted - empirical))
        }
        // Optional single-fixture readout, for when a specific prediction looks off:
        //   ./gradlew testDebugUnitTest --tests '*DiagnosticTest*' \
        //     -Ddiag.league=EC -Ddiag.home="Boston Utd" -Ddiag.away=Hornchurch
        val model = com.skorlogi.app.engine.LeagueModel.build(code, matches, today)
        val pairHome = System.getProperty("diag.home")
        val pairAway = System.getProperty("diag.away")
        if (model != null && pairHome != null && pairAway != null && model.knows(pairHome) && model.knows(pairAway)) {
            val p = model.predict(
                com.skorlogi.app.data.Fixture(code, today, "", pairHome, pairAway)
            )!!
            val ht = p.groups.first { it.title == "Babak Pertama" }.note
            val sh = p.groups.first { it.title == "Babak Kedua" }.note
            println()
            println("$pairHome vs $pairAway")
            println("  FT  : %.2f + %.2f = %.2f".format(p.lambdaHome, p.lambdaAway, p.lambdaHome + p.lambdaAway))
            println("  $ht")
            println("  $sh")
        }
        println()
    }
}
