package com.skorsnap.app.data

/** One market the model is willing to name a number for. */
data class MarketOption(
    val name: String,
    val prob: Double,
    val why: String,
) {
    val percent: Int get() = Math.round(prob * 100).toInt()

    /** The price this bet has to beat to be worth placing at all. */
    val breakEven: Double get() = if (prob > 1e-9) 1.0 / prob else 0.0
}

/** How a followed pick actually turned out. */
enum class Outcome { PENDING, WON, LOST }

/** What came back from reading one match's screenshots. */
data class MatchPrediction(
    val id: String,
    val home: String,
    val away: String,
    val league: String,
    val readable: Boolean,
    val problem: String,
    val statsSeen: List<String>,
    val statsMissing: List<String>,
    val probHome: Double,
    val probDraw: Double,
    val probAway: Double,
    val xgHome: Double,
    val xgAway: Double,
    val markets: List<MarketOption>,
    val pick: String,
    val pickProb: Double,
    val confidence: String,
    val confidenceWhy: String,
    val outcome: Outcome = Outcome.PENDING,
    val raw: String = "",
) {
    val title: String get() = if (home.isBlank()) "Pertandingan" else "$home vs $away"

    val pickPercent: Int get() = Math.round(pickProb * 100).toInt()

    val pickBreakEven: Double get() = if (pickProb > 1e-9) 1.0 / pickProb else 0.0

    /** The result the model leans towards, distinct from how the bet turned out. */
    val predictedResult: String
        get() = when (maxOf(probHome, probDraw, probAway)) {
            probHome -> "$home menang"
            probAway -> "$away menang"
            else -> "Seri"
        }

    val thin: Boolean get() = confidence.equals("rendah", true) || statsMissing.size > 3

    val settled: Boolean get() = outcome != Outcome.PENDING
}

/**
 * The record of how the picks have actually done.
 *
 * The number that matters is not the hit rate on its own but the gap between it
 * and what the app promised: a model claiming 78% and landing 78% can be trusted
 * at its word, and one claiming 78% and landing 55% cannot, however pleasant the
 * recent run has been.
 */
data class Report(val settled: List<MatchPrediction>) {

    val total: Int get() = settled.size
    val won: Int get() = settled.count { it.outcome == Outcome.WON }

    val actual: Double get() = if (total == 0) 0.0 else won.toDouble() / total

    /** What the app said would happen, averaged over the same picks. */
    val promised: Double get() = if (total == 0) 0.0 else settled.sumOf { it.pickProb } / total

    val gap: Double get() = actual - promised

    /**
     * Wilson interval on the true hit rate. Quoted because a run of eleven from
     * twelve is compatible with almost any competence, and the width says so more
     * honestly than the point estimate does.
     */
    val low: Double get() = bounds().first
    val high: Double get() = bounds().second

    private fun bounds(): Pair<Double, Double> {
        if (total == 0) return 0.0 to 1.0
        val z = 1.96
        val n = total.toDouble()
        val p = actual
        val den = 1 + z * z / n
        val centre = (p + z * z / (2 * n)) / den
        val half = z * kotlin.math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den
        return (centre - half).coerceAtLeast(0.0) to (centre + half).coerceAtMost(1.0)
    }

    /** Plus or minus, in percentage points, at the current sample size. */
    val precision: Int
        get() = if (total == 0) 50 else Math.round((high - low) / 2 * 100).toInt()

    /** Below this the record is a story, not a measurement. */
    val meaningful: Boolean get() = total >= 50

    val verdict: String
        get() = when {
            total == 0 -> "Belum ada hasil yang dicatat."
            total < 15 ->
                "Baru $total hasil. Terlalu sedikit untuk menyimpulkan apa pun — " +
                    "beruntung dan pintar terlihat sama di jumlah segini."
            !meaningful ->
                "$total hasil, ketelitiannya masih ±$precision poin. " +
                    "Di sekitar 50 hasil angkanya baru mulai berarti."
            kotlin.math.abs(gap) < 0.05 ->
                "Akurasi nyatamu kurang lebih sama dengan yang dijanjikan model. " +
                    "Artinya angka persennya bisa dipercaya apa adanya."
            gap > 0 ->
                "Hasilnya di atas yang dijanjikan model. Menyenangkan, tapi jangan " +
                    "buru-buru menaikkan taruhan — selisih sebesar ini masih bisa keberuntungan."
            else ->
                "Hasilnya di bawah yang dijanjikan model. Kalau bertahan sampai 100 hasil, " +
                    "berarti angkanya memang terlalu percaya diri untuk jenis laga yang kamu pilih."
        }
}
