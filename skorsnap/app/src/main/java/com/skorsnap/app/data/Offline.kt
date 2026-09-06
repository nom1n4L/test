package com.skorsnap.app.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A full analysis from a bookmaker's coupon alone, with no model call and no cost.
 *
 * Built because the user ran out of API credit and out of money to buy more, and an
 * app that stops working when the credit does is not much of an app. But it is not
 * a consolation prize: the de-margined market is a better forecaster than the model
 * was. That was measured earlier in this project — SofaScore's "AI" prediction sat
 * within four points of the de-vigged market on the same match, and the model's own
 * record over thirteen settled matches was 3 for 13 against a promised 68%.
 *
 * How it works:
 *  1. The 1X2 prices are de-margined. That market is the most heavily traded on the
 *     coupon and so the most trustworthy number on it.
 *  2. Goal expectations are fitted to reproduce exactly those three probabilities —
 *     two free parameters against two independent constraints, so the fit is exact
 *     rather than approximate.
 *  3. Every other market is read off the resulting score matrix. Sixty-odd markets,
 *     all mutually consistent, from three prices.
 *
 * And the part that makes it more than a calculator: where the coupon prices a
 * market that step 3 also derives, the two can disagree. That disagreement is real
 * information — soft books misprice secondary markets against their own main line
 * routinely — and it is the only kind of edge findable without a forecast of one's
 * own. What this cannot do is find value in the 1X2 itself: those prices are the
 * input, so their edge is zero by construction, and the app says so rather than
 * printing a row of zeroes and letting them look like findings.
 */
object Offline {

    /**
     * Names and groups the app knows, with placeholder probabilities.
     *
     * A coupon has to be matched against something before any analysis exists, and
     * the market catalogue is what the grid can produce. The numbers here are
     * discarded; only the names and groups are used.
     */
    private fun catalogue(): List<MarketOption> =
        Grid.matchMarkets(1.35, 1.15, 0.40, 0.28, 0.32)

    data class Result(
        val match: MatchPrediction?,
        /** Why it could not be done, when it could not. */
        val problem: String = "",
        val unmatched: List<String> = emptyList(),
    )

    /**
     * Turns a pasted coupon into an analysis.
     *
     * Requires the three match-result prices. Without them there is nothing to
     * anchor the goal expectations to: totals alone fix how many goals are expected
     * but not who scores them, and guessing the split would put a number on the
     * screen that came from nowhere.
     */
    fun analyse(home: String, away: String, coupon: String, id: String): Result {
        val entries = Odds.parse(coupon)
        if (entries.isEmpty()) {
            return Result(null, "Tidak ada harga yang terbaca dari teks itu.")
        }

        val reference = catalogue()
        val matched = Odds.match(entries, reference)
        val prices = matched.pairs
        val unmatched = matched.unmatched.map { it.label }

        val fair = Devig.fair(prices, reference)
        val result = fair.firstOrNull { it.label == "Hasil Akhir" }
            ?: return Result(
                null,
                "Harga 1, X, dan 2 (menang / seri / kalah) harus lengkap — itu yang " +
                    "dipakai untuk mengunci semua market lain. Yang terbaca: " +
                    if (fair.isEmpty()) "belum ada pasaran lengkap." else
                        fair.joinToString { it.label } + ".",
                unmatched,
            )

        fun p(name: String) = result.probs["Hasil Akhir|$name"] ?: 0.0
        val pH = p("Tuan rumah menang")
        val pD = p("Seri")
        val pA = p("Tandang menang")

        val (xgH, xgA) = Grid.fit(1.35, 1.15, pH, pD, pA)
        val markets = Grid.matchMarkets(xgH, xgA, pH, pD, pA)

        // Every set the coupon priced, so the user can see what the reading rests on.
        val seen = fair.map { f ->
            "${f.label}: margin bandar ${(f.margin * 100).roundToInt()}%"
        }

        // Only markets other than the anchor can carry an edge. The 1X2 is the
        // input; measuring the output against it would report its own rounding as
        // profit.
        val anchors = result.probs.keys
        val crossChecked = prices.keys.count { it !in anchors }

        val base = MatchPrediction(
            id = id,
            home = home.ifBlank { "Tuan rumah" },
            away = away.ifBlank { "Tandang" },
            league = "",
            readable = true,
            problem = "",
            statsSeen = seen,
            statsMissing = emptyList(),
            firstRead = "Dihitung dari harga bandar, tanpa model AI. Setelah margin " +
                "dibuang, pasarnya menilai ${pctOf(pH)} tuan rumah, ${pctOf(pD)} seri, " +
                "${pctOf(pA)} tandang — setara perkiraan gol ${twoDecimals(xgH)} - " +
                "${twoDecimals(xgA)}, dan dari situlah semua market lain diturunkan.",
            risks = listOf(
                "Ini pendapat bandar, bukan pendapat kedua. Tidak ada cedera, cuaca, " +
                    "atau rotasi pemain yang ikut dipertimbangkan di sini — semuanya " +
                    "sudah terkubur di dalam harga, dan tidak bisa dibongkar lagi.",
                "Market yang harganya tidak ada di kupon dihitung dari sebaran Poisson, " +
                    "yang meremehkan laga dengan kartu merah atau tim yang sudah aman " +
                    "di klasemen.",
            ),
            riskSide = "",
            adjustment = if (crossChecked > 0) {
                "$crossChecked market lain ikut dihargai di kupon, jadi bisa " +
                    "dibandingkan dengan angka yang diturunkan dari 1X2. Selisihnya " +
                    "itulah satu-satunya nilai yang bisa ditemukan tanpa prediksi sendiri."
            } else {
                "Cuma 1X2 yang ada harganya, jadi tidak ada pembanding sama sekali. " +
                    "Angka-angka di bawah konsisten dengan harga bandar, tapi tidak ada " +
                    "satu pun yang bisa disebut menguntungkan — untuk itu perlu harga " +
                    "market lain, atau analisis AI."
            },
            probHome = pH,
            probDraw = pD,
            probAway = pA,
            xgHome = xgH,
            xgAway = xgA,
            markets = markets,
            pick = "",
            pickProb = 0.0,
            confidence = when {
                crossChecked >= 6 -> "sedang"
                crossChecked >= 1 -> "rendah"
                else -> "rendah"
            },
            confidenceWhy = "Peluangnya sendiri seakurat pasar taruhan, yang sudah " +
                "lebih baik daripada model mana pun. Yang rendah itu keyakinan bahwa " +
                "ada taruhan menguntungkan di sini, dan itu tergantung berapa banyak " +
                "market lain yang ikut dihargai.",
            action = "",
            verdict = "",
            needMore = if (crossChecked == 0) {
                listOf("Harga market lain dari kupon yang sama: total gol, BTTS, handicap.")
            } else emptyList(),
            mode = Mode.MATCH,
            prices = prices,
            offline = true,
        )

        return Result(finish(base, anchors), unmatched = unmatched)
    }

    /**
     * Picks the bet and writes the closing line.
     *
     * The floor is deliberately not applied here: this runs before the app knows the
     * user's appetite, and [Value.apply] is called again with the real floor when the
     * analysis is saved. What is decided here is only the wording.
     */
    private fun finish(match: MatchPrediction, anchors: Set<String>): MatchPrediction {
        val edges = match.markets
            .filter { "${it.group}|${it.name}" in match.prices }
            .filter { "${it.group}|${it.name}" !in anchors }
            .mapNotNull { option ->
                val price = match.priceOf(option) ?: return@mapNotNull null
                val edge = price * option.prob - 1.0
                if (edge <= 0.0 || edge > Value.TOO_GOOD) null else option to edge
            }
            .sortedByDescending { it.second }

        val top = edges.firstOrNull()
        val fallback = match.markets.filter { it.inBand(MarketOption.SAFE_LOW) }
            .maxByOrNull { it.prob }

        return if (top != null) {
            match.copy(
                pick = top.first.name,
                pickProb = top.first.prob,
                action = "pasang",
                valuePick = true,
                valueEdge = top.second,
                verdict = "${top.first.name}. Bandar membayar " +
                    "${twoDecimals(match.priceOf(top.first) ?: 0.0)} untuk sesuatu yang " +
                    "harga 1X2-nya sendiri bilang ${pctOf(top.first.prob)} — impasnya " +
                    "${twoDecimals(top.first.breakEven)}. Untung " +
                    "${(top.second * 100).roundToInt()}% per taruhan, dan itu datang dari " +
                    "ketidakkonsistenan bandar sendiri, bukan dari tebakan.",
            )
        } else {
            match.copy(
                pick = fallback?.name.orEmpty(),
                pickProb = fallback?.prob ?: 0.0,
                action = "lewatkan",
                verdict = "Tidak ada taruhan yang menguntungkan di kupon ini. Semua harga " +
                    "yang ada konsisten dengan 1X2-nya, jadi yang tersisa cuma margin " +
                    "bandar — dan itu selalu merugikan pemasang. Angka-angka di bawah " +
                    "tetap benar dan bisa dipakai untuk membandingkan kupon lain.",
            )
        }
    }

    private fun pctOf(p: Double) = "${(p * 100).roundToInt()}%"

    /** How far the derived total sits from a priced one, when both exist. */
    fun totalDisagreement(match: MatchPrediction): Pair<MarketOption, Double>? =
        match.markets
            .filter { it.group == "Total Gol" && "${it.group}|${it.name}" in match.prices }
            .mapNotNull { option ->
                val price = match.priceOf(option) ?: return@mapNotNull null
                val implied = 1.0 / price
                option to abs(option.prob - implied)
            }
            .maxByOrNull { it.second }
}
