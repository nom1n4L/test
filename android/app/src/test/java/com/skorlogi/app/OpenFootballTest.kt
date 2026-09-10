package com.skorlogi.app

import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Http
import com.skorlogi.app.data.Match
import com.skorlogi.app.data.OpenFootball
import com.skorlogi.app.engine.LeagueModel
import com.skorlogi.app.engine.Picks
import org.junit.Assume
import org.junit.Test

/**
 * Runs the whole pipeline on the keyless GitHub source, which is what the app falls
 * back to when the odds archive is unreachable. Exercises the real download, parse,
 * fit and shortlist, so a network that blocks one source still leaves a working app.
 */
class OpenFootballTest {

    @Test
    fun predictsFromKeylessSource() {
        val seasons = OpenFootball.recentSeasons()
        println("Musim yang dicari: $seasons")

        val code = "of:en.1"
        val history = ArrayList<Match>()
        var upcoming = emptyList<com.skorlogi.app.data.Fixture>()

        for (season in seasons) {
            val body = try {
                Http.getText(OpenFootball.url(season, "en.1"))
            } catch (e: Exception) {
                Assume.assumeNoException("jaringan tidak tersedia", e)
                return
            }
            val (played, future) = OpenFootball.parse(code, body)
            history.addAll(played)
            // Only the current season carries fixtures still to be played.
            if (future.size > upcoming.size) upcoming = future
            println("  $season: ${played.size} selesai, ${future.size} belum main")
        }

        assert(history.size > 300) { "riwayat terlalu tipis: ${history.size}" }
        assert(upcoming.isNotEmpty()) { "tidak ada jadwal mendatang" }

        val withHalfTime = history.count { it.hasHalfTime }
        println("Riwayat: ${history.size} laga, ${withHalfTime} punya skor babak 1")
        assert(withHalfTime > history.size / 2) { "skor babak 1 banyak yang hilang" }

        val model = LeagueModel.build(code, history, Dates.today())
        assert(model != null) { "model gagal dibangun dari sumber cadangan" }

        val future = upcoming.filter { it.dateEpochDay >= Dates.today() }
            .sortedBy { it.dateEpochDay }
        println("Jadwal ke depan: ${future.size} laga, sampai ${Dates.format(future.last().dateEpochDay)}")

        val predictions = future.take(60).mapNotNull { model!!.predict(it) }
        println("Berhasil diprediksi: ${predictions.size} dari ${minOf(60, future.size)}")
        assert(predictions.size > future.take(60).size / 2) { "terlalu banyak tim tak dikenal" }

        val shortlist = Picks.best(predictions)
        println()
        println("Pilihan Terbaik dari sumber cadangan: ${shortlist.size}")
        for (p in shortlist.take(6)) {
            println("   %-44s %-28s %3d%%".format(
                "${p.fixture.home} v ${p.fixture.away}", p.selection, p.percent))
        }

        val sample = predictions.first()
        println()
        println("Market tersedia: ${sample.groups.map { it.title }}")
        // No corner or card data comes from this source, so those groups must be absent
        // rather than present and empty.
        assert(sample.groups.none { it.title == "Sepak Pojok" || it.title == "Kartu" }) {
            "market tanpa data ikut muncul"
        }
        assert(sample.groups.any { it.title == "Babak Pertama" }) {
            "skor babak 1 ada di data tapi marketnya hilang"
        }
        println()
        println("Sumber cadangan berfungsi penuh.")
    }
}
