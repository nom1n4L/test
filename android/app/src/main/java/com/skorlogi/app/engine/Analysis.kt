package com.skorlogi.app.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One readable sentence about why the model says what it says.
 *
 * @param heading a few words naming the factor
 * @param body the explanation, in plain language
 * @param weight how much this factor moves the prediction, 0 to 1, for ordering
 *   and for how strongly the row is drawn
 */
data class Insight(val heading: String, val body: String, val weight: Double)

/**
 * Turns the model's internals into an explanation.
 *
 * A page of probabilities tells you what the model concluded but not why, and a
 * conclusion you cannot interrogate is one you cannot sensibly disagree with.
 * Every sentence here is generated from the numbers the prediction was actually
 * built from — the fitted attack and defence factors, the home-ground effect, the
 * form the app has on file — so the explanation cannot drift away from the
 * arithmetic the way a written summary would.
 */
object Analysis {

    fun generate(p: Prediction, model: LeagueModel): List<Insight> {
        val out = ArrayList<Insight>(7)
        val home = p.fixture.home
        val away = p.fixture.away
        val avg = model.averageGoals

        // --- Who is favoured, and by how clear a margin -----------------------
        val best = maxOf(p.pHome, p.pDraw, p.pAway)
        val spread = best - minOf(p.pHome, p.pDraw, p.pAway)
        out.add(
            Insight(
                heading = "Kesimpulan",
                body = when {
                    best == p.pDraw ->
                        "Model tidak menjagokan siapa pun. Seri jadi hasil tunggal paling " +
                            "mungkin (${pct(p.pDraw)}), yang biasanya berarti dua tim yang setara."
                    spread < 0.15 ->
                        "${winner(p)} sedikit diunggulkan (${pct(best)}), tapi tiga hasilnya " +
                            "berdekatan. Ini pertandingan yang model sendiri tidak yakin."
                    best > 0.60 ->
                        "${winner(p)} diunggulkan cukup jelas (${pct(best)}). Selisih sebesar " +
                            "ini jarang muncul, dan biasanya karena beda kualitas yang nyata."
                    else ->
                        "${winner(p)} diunggulkan (${pct(best)}), tapi lawannya tetap punya " +
                            "jalan (${pct(if (best == p.pHome) p.pAway else p.pHome)})."
                },
                weight = spread.coerceIn(0.0, 1.0),
            )
        )

        // --- Attack and defence, the two numbers the whole fit rests on -------
        val ha = model.attackFactor(home)
        val hd = model.defenceFactor(home)
        val aa = model.attackFactor(away)
        val ad = model.defenceFactor(away)

        out.add(
            Insight(
                heading = "Serangan",
                body = "$home mencetak ${factor(ha)} dari tim rata-rata di liga ini, " +
                    "$away ${factor(aa)}. " +
                    if (abs(ha - aa) < 0.12) {
                        "Dua lini serang yang sepadan."
                    } else if (ha > aa) {
                        "Keunggulan ada di $home."
                    } else {
                        "Keunggulan ada di $away."
                    },
                weight = abs(ha - aa).coerceIn(0.0, 1.0),
            )
        )

        out.add(
            Insight(
                heading = "Pertahanan",
                body = "$home kebobolan ${factor(hd)} dari tim rata-rata, $away ${factor(ad)}. " +
                    if (abs(hd - ad) < 0.12) {
                        "Sama-sama rapat, atau sama-sama bocor."
                    } else if (hd < ad) {
                        "$home lebih rapat."
                    } else {
                        "$away lebih rapat."
                    },
                weight = abs(hd - ad).coerceIn(0.0, 1.0),
            )
        )

        // --- Home ground -----------------------------------------------------
        val bonus = model.homeAdvantageGoals
        out.add(
            Insight(
                heading = "Faktor kandang",
                body = "Main di kandang di liga ini bernilai sekitar %.2f gol per laga. "
                    .format(bonus) +
                    if (bonus > 0.25) {
                        "Termasuk besar — sebagian keunggulan $home datang dari sini, bukan " +
                            "murni dari kualitas."
                    } else {
                        "Relatif kecil, jadi keunggulan kandang tidak banyak menolong."
                    },
                weight = (bonus / 0.5).coerceIn(0.0, 1.0),
            )
        )

        // --- Expected goals, said in words ------------------------------------
        val total = p.lambdaHome + p.lambdaAway
        out.add(
            Insight(
                heading = "Perkiraan gol",
                body = "Model memperkirakan %.2f gol untuk $home dan %.2f untuk $away, "
                    .format(p.lambdaHome, p.lambdaAway) +
                    "total %.2f. ".format(total) +
                    when {
                        total > avg * 2 * 1.15 ->
                            "Di atas rata-rata liga (%.2f) — condong ke laga terbuka.".format(avg * 2)
                        total < avg * 2 * 0.85 ->
                            "Di bawah rata-rata liga (%.2f) — condong ke laga tertutup.".format(avg * 2)
                        else -> "Kurang lebih sama dengan rata-rata liga (%.2f).".format(avg * 2)
                    },
                weight = (abs(total - avg * 2) / (avg * 2)).coerceIn(0.0, 1.0),
            )
        )

        // --- Form, but only when it says something --------------------------
        val hf = p.homeForm
        val af = p.awayForm
        if (hf != null && af != null && hf.played >= 5 && af.played >= 5) {
            val hp = hf.points.toDouble() / (hf.played * 3)
            val ap = af.points.toDouble() / (af.played * 3)
            out.add(
                Insight(
                    heading = "Performa terakhir",
                    body = "Dari ${hf.played} laga terakhir, $home mengumpulkan ${hf.points} poin " +
                        "dan $away ${af.points}. " +
                        when {
                            abs(hp - ap) < 0.12 -> "Momentum keduanya mirip."
                            hp > ap -> "$home sedang lebih baik."
                            else -> "$away sedang lebih baik."
                        } +
                        " Perlu dicatat: model sudah memperhitungkan ini lewat pembobotan " +
                        "laga terbaru, jadi form bukan info tambahan di luar angka di atas.",
                    weight = abs(hp - ap).coerceIn(0.0, 1.0),
                )
            )
        }

        // --- Head to head, if there is enough of it ---------------------------
        if (p.h2h.size >= 3) {
            val wins = p.h2h.count { (it.home == home && it.result == 'H') || (it.away == home && it.result == 'A') }
            val draws = p.h2h.count { it.result == 'D' }
            val goals = p.h2h.sumOf { it.homeGoals + it.awayGoals }.toDouble() / p.h2h.size
            out.add(
                Insight(
                    heading = "Rekor pertemuan",
                    body = "Dari ${p.h2h.size} pertemuan terakhir, $home menang $wins, seri $draws, " +
                        "kalah ${p.h2h.size - wins - draws}, dengan rata-rata %.1f gol per laga. "
                            .format(goals) +
                        "Model tidak memakai rekor ini secara khusus — riwayat head-to-head " +
                        "sepak bola terlalu sedikit untuk jadi bukti, dan biasanya cuma " +
                        "mengulang beda kualitas yang sudah terhitung.",
                    weight = 0.2,
                )
            )
        }

        // --- What to actually take from all this -----------------------------
        val pick = Picks.from(p).firstOrNull()
        out.add(
            Insight(
                heading = "Yang paling bisa dipegang",
                body = if (pick == null) {
                    "Tidak ada satu pun market yang lolos ambang di laga ini. " +
                        when (p.confidence) {
                            Confidence.LOW ->
                                "Riwayat salah satu tim terlalu tipis, jadi seluruh halaman ini " +
                                    "sebaiknya dibaca sebagai perkiraan kasar."
                            else ->
                                "Bukan berarti prediksinya salah — cuma tidak ada yang cukup " +
                                    "menonjol untuk disebut pilihan."
                        }
                } else {
                    "\"${pick.selection}\" (${pick.market}) di ${pick.percent}% adalah angka " +
                        "paling kuat yang datang dari market yang lolos uji kejujuran. " +
                        "Sisanya di halaman ini boleh dibaca, tapi jangan dijadikan dasar."
                },
                weight = 1.0,
            )
        )

        return out
    }

    private fun winner(p: Prediction): String = when (maxOf(p.pHome, p.pDraw, p.pAway)) {
        p.pHome -> p.fixture.home
        p.pAway -> p.fixture.away
        else -> "Seri"
    }

    private fun pct(v: Double) = "${(v * 100).roundToInt()}%"

    /** "1,3x lipat" reads better than a bare multiplier for a factor near one. */
    private fun factor(f: Double): String = when {
        f >= 1.08 -> "%.2f kali lebih banyak".format(f)
        f <= 0.92 -> "%.0f%% lebih sedikit".format((1 - f) * 100)
        else -> "kurang lebih sama"
    }
}
