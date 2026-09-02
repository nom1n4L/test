package com.skorsnap.app.data

/** Which set of markets an analysis was asked for. */
enum class Mode(val label: String) {
    MATCH("Analisis Match"),
    CORNER("Analisis Corner"),
}

/** One market the model is willing to name a number for. */
data class MarketOption(
    val name: String,
    val prob: Double,
    val why: String,
    /** Heading this belongs under, so forty markets stay readable. */
    val group: String = "Lainnya",
) {
    val percent: Int get() = Math.round(prob * 100).toInt()

    /**
     * The price this bet has to beat to be worth placing at all.
     *
     * This is the whole of value betting in one number: if the bookmaker pays more
     * than this, the bet is worth taking; if less, it is not, however comfortable
     * the percentage looks.
     */
    val breakEven: Double get() = if (prob > 1e-9) 1.0 / prob else 0.0

    /** High enough to lead with, low enough that a bookmaker will price it. */
    val safe: Boolean get() = prob in 0.68..0.92
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
    val mode: Mode = Mode.MATCH,
    /**
     * The market actually backed, which is not always the one recommended.
     *
     * The app suggests one pick but shows forty markets, and people bet the one
     * they liked rather than the one at the top. Recording the recommendation
     * while they backed something else made the report measure a bet nobody
     * placed — and left the user unable to say whether a match "hit" at all.
     */
    val backed: String = "",
    val model: String = "",
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

    /** What the record is about: the market backed, or the recommendation. */
    val trackedMarket: String get() = backed.ifBlank { pick }

    val trackedProb: Double
        get() = markets.firstOrNull { it.name == trackedMarket }?.prob ?: pickProb

    val trackedGroup: String
        get() = markets.firstOrNull { it.name == trackedMarket }?.group
            ?: if (mode == Mode.CORNER) "Corner" else "Lainnya"

    /** Markets under their headings, in the order the catalogue lists them. */
    fun grouped(): List<Pair<String, List<MarketOption>>> =
        markets.groupBy { it.group }
            .toList()
            .sortedBy { (group, _) -> Markets.order.indexOf(group).takeIf { it >= 0 } ?: 99 }
            .map { (group, list) -> group to list.sortedByDescending { it.prob } }
}

/** One cut of the record — a market family, or a model. */
data class Slice(val name: String, val total: Int, val won: Int, val promised: Double) {
    val actual: Double get() = if (total == 0) 0.0 else won.toDouble() / total
    val gap: Double get() = actual - promised

    /** Enough results to be worth a second look, still not proof. */
    val worthWatching: Boolean get() = total >= 5 && kotlin.math.abs(gap) >= 0.15
}

/** The market catalogue, shared between the prompt and the screen. */
object Markets {

    val order = listOf(
        "Hasil Akhir",
        "Double Chance",
        "Total Gol",
        "Total Babak 1",
        "Total per Tim",
        "Kombinasi Hasil + Total",
        "Handicap Asia",
        "Handicap Eropa",
        "Corner",
        "Corner Babak 1",
        "Corner per Tim",
        "Lainnya",
    )
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
    val promised: Double
        get() = if (total == 0) 0.0 else settled.sumOf { it.trackedProb } / total

    /**
     * The record split by market, and by model.
     *
     * An overall hit rate averages away the thing worth knowing. A model can be
     * honest about corners and badly overconfident about one goal line, and the
     * combined number will look fine while the user keeps losing on that line.
     * Only a split shows it.
     */
    fun byGroup(): List<Slice> = slice { it.trackedGroup }

    fun byModel(): List<Slice> = slice { it.model.ifBlank { "tidak tercatat" } }

    private fun slice(key: (MatchPrediction) -> String): List<Slice> =
        settled.groupBy(key)
            .map { (name, list) ->
                Slice(
                    name = name,
                    total = list.size,
                    won = list.count { it.outcome == Outcome.WON },
                    promised = list.sumOf { it.trackedProb } / list.size,
                )
            }
            .sortedByDescending { it.total }

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
