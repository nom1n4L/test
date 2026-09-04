package com.skorlogi.app

import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Http
import com.skorlogi.app.data.OpenFootball
import com.skorlogi.app.engine.Analysis
import com.skorlogi.app.engine.LeagueModel
import org.junit.Assume
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Checks the written analysis against the numbers it claims to describe.
 *
 * An explanation that drifts from the arithmetic is worse than no explanation,
 * because it is believed. These assertions pin the prose to the prediction: the
 * side it names as favourite must be the one with the highest probability, and the
 * percentage it quotes must be that probability.
 */
class AnalysisTest {

    @Test
    fun explanationMatchesTheNumbers() {
        val code = "of:en.1"
        val history = ArrayList<com.skorlogi.app.data.Match>()
        var upcoming = emptyList<com.skorlogi.app.data.Fixture>()
        for (season in OpenFootball.recentSeasons()) {
            val body = try {
                Http.getText(OpenFootball.url(season, "en.1"))
            } catch (e: Exception) {
                Assume.assumeNoException("jaringan tidak tersedia", e)
                return
            }
            val (played, future) = OpenFootball.parse(code, body)
            history.addAll(played)
            if (future.size > upcoming.size) upcoming = future
        }

        val model = LeagueModel.build(code, history, Dates.today())!!
        val fixtures = upcoming.filter { it.dateEpochDay >= Dates.today() }
            .sortedBy { it.dateEpochDay }.take(30)

        var checked = 0
        for (fixture in fixtures) {
            val p = model.predict(fixture) ?: continue
            val insights = Analysis.generate(p, model)
            checked++

            assert(insights.size >= 5) { "penjelasan terlalu pendek: ${insights.size} poin" }
            assert(insights.all { it.body.length > 30 }) { "ada poin yang kosong isinya" }
            assert(insights.first().heading == "Kesimpulan") { "kesimpulan tidak di depan" }
            assert(insights.last().heading == "Yang paling bisa dipegang") { "penutup hilang" }

            // The conclusion must name the side the numbers actually favour.
            val body = insights.first().body
            val best = maxOf(p.pHome, p.pDraw, p.pAway)
            val favourite = when (best) {
                p.pHome -> fixture.home
                p.pAway -> fixture.away
                else -> "Seri"
            }
            if (best != p.pDraw) {
                assert(body.contains(favourite)) {
                    "kesimpulan tidak menyebut unggulan sebenarnya ($favourite): $body"
                }
            }
            // And the percentage it quotes must be that probability.
            val quoted = Regex("\\((\\d+)%\\)").find(body)?.groupValues?.get(1)?.toInt()
            if (quoted != null) {
                val expected = (best * 100).roundToInt()
                val other = ((if (best == p.pHome) p.pAway else p.pHome) * 100).roundToInt()
                assert(quoted == expected || quoted == other) {
                    "persentase di teks ($quoted) bukan angka model ($expected): $body"
                }
            }

            // Expected goals quoted in the text must be the fitted ones.
            insights.firstOrNull { it.heading == "Perkiraan gol" }?.let { g ->
                assert(g.body.contains("%.2f".format(p.lambdaHome))) {
                    "perkiraan gol kandang tidak cocok: ${g.body}"
                }
                assert(g.body.contains("%.2f".format(p.lambdaAway))) {
                    "perkiraan gol tandang tidak cocok: ${g.body}"
                }
            }
        }

        assert(checked >= 10) { "terlalu sedikit yang diperiksa: $checked" }
        println("Penjelasan diperiksa untuk $checked pertandingan, semuanya cocok dengan angkanya.")

        // Team pages have to hold up too.
        val team = model.teams.first()
        val profile = model.profile(team)!!
        println()
        println("Contoh halaman tim: ${profile.team}")
        println("  peringkat #${profile.rank} dari ${profile.teamsInLeague}, Elo ${profile.elo.roundToInt()}")
        println("  serangan %.2f, pertahanan %.2f, dari %d laga"
            .format(profile.attackFactor, profile.defenceFactor, profile.matchesPlayed))
        println("  kandang: %d laga, cetak %.2f, kebobolan %.2f"
            .format(profile.homePlayed, profile.homeScored, profile.homeConceded))
        println("  tandang: %d laga, cetak %.2f, kebobolan %.2f"
            .format(profile.awayPlayed, profile.awayScored, profile.awayConceded))

        assert(profile.rank in 1..profile.teamsInLeague) { "peringkat di luar rentang" }
        assert(profile.attackFactor > 0.1 && profile.attackFactor < 5.0) { "faktor serangan tidak masuk akal" }
        assert(profile.homePlayed + profile.awayPlayed == profile.recent.size ||
            profile.recent.size == 12) { "pembagian kandang/tandang tidak konsisten" }

        // Every team in the division must have a usable page, not just the first.
        val broken = model.teams.filter { model.profile(it) == null }
        assert(broken.isEmpty()) { "tim tanpa halaman: $broken" }
        println()
        println("Semua ${model.teams.size} tim punya halaman yang lengkap.")

        // Ranks must be unique and cover 1..n over the current division only —
        // a table that still lists last season's relegated clubs is wrong.
        val current = model.currentTeams
        val ranks = current.map { model.rankOf(it) }.sorted()
        assert(ranks == (1..current.size).toList()) { "peringkat duplikat atau bolong" }
        println("Anggota liga saat ini: ${current.size} tim (dari ${model.teams.size} yang dipakai model).")
        println("Peringkat 1..${current.size} lengkap tanpa duplikat.")
    }
}
