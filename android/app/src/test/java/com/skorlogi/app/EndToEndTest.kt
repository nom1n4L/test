package com.skorlogi.app

import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.FootballData
import com.skorlogi.app.data.Http
import com.skorlogi.app.data.Leagues
import com.skorlogi.app.data.Match
import com.skorlogi.app.engine.LeagueModel
import org.junit.Assume
import org.junit.Test

/**
 * Runs the real pipeline against the live feeds: download, parse, fit, predict.
 * Prints one full prediction so the output can be eyeballed for sanity.
 *
 * Skipped when the network is unavailable, so it never breaks an offline build.
 */
class EndToEndTest {

    @Test
    fun predictsRealUpcomingFixture() {
        val fixturesBody = try {
            Http.getText(FootballData.FIXTURES_URL)
        } catch (e: Exception) {
            Assume.assumeNoException("jaringan tidak tersedia", e)
            return
        }

        val seasons = Leagues.recentSeasons()
        println("Kode musim yang dipakai: $seasons")

        val fixtures = FootballData.parseFixtures(fixturesBody)
            .filter { it.dateEpochDay >= Dates.today() - 1 }
            .sortedBy { it.dateEpochDay }
        println("Jadwal mendatang: ${fixtures.size} laga di ${fixtures.map { it.league }.distinct().size} liga")
        assert(fixtures.isNotEmpty()) { "tidak ada jadwal terbaca" }

        val league = fixtures.groupBy { it.league }.maxByOrNull { it.value.size }!!.key
        val history = ArrayList<Match>()
        for (season in seasons) {
            runCatching { Http.getText(FootballData.mainUrl(league, season)) }
                .onSuccess { history.addAll(FootballData.parseMain(league, it)) }
        }
        println("Riwayat ${Leagues.label(league)}: ${history.size} pertandingan")
        assert(history.size > 200) { "riwayat terlalu sedikit: ${history.size}" }

        val model = LeagueModel.build(league, history, Dates.today())
        assert(model != null) { "model gagal dibangun" }

        val fixture = fixtures.first { it.league == league && model!!.knows(it.home) && model.knows(it.away) }
        val p = model!!.predict(fixture)
        assert(p != null) { "prediksi gagal" }
        p!!

        println()
        println("=".repeat(64))
        println("${fixture.home} vs ${fixture.away}")
        println("${Leagues.label(league)} · ${Dates.formatWithDay(fixture.dateEpochDay)} ${fixture.time}")
        println("Keyakinan: ${p.confidence.label}")
        println("Perkiraan gol: %.2f - %.2f".format(p.lambdaHome, p.lambdaAway))
        println("=".repeat(64))
        for (g in p.groups) {
            println()
            println("[${g.title}]" + (g.note?.let { "  ($it)" } ?: ""))
            for (line in g.lines.take(6)) {
                println("   %-46s %5.1f%%  (odds adil %.2f)".format(line.label, line.prob * 100, line.fairOdds))
            }
            if (g.lines.size > 6) println("   … ${g.lines.size - 6} baris lagi")
        }
        if (p.values.isNotEmpty()) {
            println()
            println("[Value vs odds bandar]")
            p.values.forEach {
                println("   %-30s model %5.1f%%  odds %.2f  edge %+d%%"
                    .format(it.label, it.modelProb * 100, it.odds, it.edgePercent))
            }
        }
        println()

        // Sanity: the probabilities must actually be probabilities.
        val total = p.pHome + p.pDraw + p.pAway
        assert(total > 0.999 && total < 1.001) { "1X2 tidak berjumlah 1: $total" }
        for (g in p.groups) {
            for (line in g.lines) {
                assert(line.prob in 0.0..1.0) { "peluang di luar rentang: ${g.title} / ${line.label} = ${line.prob}" }
            }
        }
        val totalGoals = p.groups.first { it.title == "Total Gol" }
        val o25 = totalGoals.lines.first { it.label == "Over 2.5" }.prob
        val u25 = totalGoals.lines.first { it.label == "Under 2.5" }.prob
        assert(kotlin.math.abs(o25 + u25 - 1.0) < 1e-6) { "over/under tidak saling melengkapi" }
        println("Semua pemeriksaan konsistensi lolos.")
    }
}
