package com.skorsnap.app.data

/** Which set of markets an analysis was asked for. */
enum class Mode(val label: String, val short: String) {
    MATCH("Analisis Match", "Match"),
    CORNER("Analisis Corner", "Corner"),

    /**
     * One question, one answer: over or under 4.5 corners in the first half.
     *
     * The general modes answer fifty questions at once, which is the right shape
     * when hunting for a bet and the wrong shape when the bet is already decided.
     * This mode returns two numbers that sum to one, and nothing else.
     */
    CORNER_1H("Corner Babak 1 · O/U 4.5", "Corner 1H"),
}

/** One settled market: what was promised, and what happened. */
data class Mark(val group: String, val market: String, val promised: Double, val won: Boolean)

/** Which of the two records is being read: the app's advice, or the user's bet. */
enum class Lens(val label: String, val short: String) {
    PICK("Rekomendasi aplikasi", "Rekomendasi"),
    BACKED("Market yang saya pasang", "Pilihanku"),
}

/** One market the model is willing to name a number for. */
data class MarketOption(
    val name: String,
    val prob: Double,
    val why: String,
    /** Heading this belongs under, so forty markets stay readable. */
    val group: String = "Lainnya",
    /**
     * True when the app worked this number out from the goal expectations rather
     * than the model naming it. The screen says which is which, and a market the
     * model actually judged is preferred when recommending.
     */
    val derived: Boolean = false,
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
    val safe: Boolean get() = inBand(SAFE_LOW)

    fun inBand(floor: Double): Boolean = prob in floor..SAFE_HIGH

    companion object {
        /**
         * The band a recommendation has to sit in.
         *
         * Below the floor the bet is a coin toss dressed up as advice. Above the
         * ceiling it prices under 1.10, which no bookmaker offers and which was
         * never measured for calibration either.
         */
        const val SAFE_LOW = 0.68
        const val SAFE_HIGH = 0.92
    }
}

/**
 * How low a probability the user is willing to be pointed at.
 *
 * The floor was fixed at 68% because an earlier complaint was that
 * recommendations landed on markets nothing called safe. The opposite complaint
 * followed: a 68% floor rules out every market that pays properly, so the app only
 * ever suggested short prices. Both readings are reasonable and they contradict
 * each other, so this is the user's choice rather than a number picked for them.
 *
 * The ceiling does not move. Above 92% the price is under 1.10, which is not worth
 * staking whatever the appetite.
 */
enum class Appetite(val label: String, val floor: Double, val note: String) {
    SAFE(
        "Aman",
        0.68,
        "Cuma market 68% ke atas. Paling sering tembus, bayarannya paling kecil.",
    ),
    BALANCED(
        "Seimbang",
        0.55,
        "Turun sampai 55%. Membuka market seperti Menang & Over 2.5 atau total gol " +
            "2-3 yang bayarannya jauh lebih baik, dengan risiko meleset lebih sering.",
    ),
    BOLD(
        "Berani",
        0.42,
        "Turun sampai 42%. Bayarannya besar dan memang lebih sering meleset — " +
            "hanya masuk akal kalau kamu memasang kecil dan konsisten.",
    ),
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
    /**
     * Two concrete reasons this reading could be wrong, in the model's own words.
     *
     * Asked to argue against itself before committing to a number, a model produces
     * a more careful number — and the user gets to see the argument rather than a
     * bare percentage they have no way to judge.
     */
    val risks: List<String> = emptyList(),
    /**
     * The reasoning chain, in the order it has to happen.
     *
     * The doubts were being written after the numbers and changing nothing — a
     * match reading "cup tie, opening 15 minutes usually cautious" and "away
     * average padded against bottom sides" still came out recommending Over at
     * 72%, and lost. Splitting the read from the adjustment makes a non-adjustment
     * visible on the screen instead of hidden inside one confident percentage.
     */
    /**
     * The closing decision, so the analysis ends with an answer rather than with
     * more considerations.
     *
     * "pasang", "lewatkan", or "butuh data" — and where it is the last, [needMore]
     * names exactly what would settle it, so the user can go and fetch that rather
     * than guess what the model wanted.
     */
    val action: String = "",
    val verdict: String = "",
    val needMore: List<String> = emptyList(),
    val firstRead: String = "",
    val riskSide: String = "",
    val adjustment: String = "",
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
    /** True when the app replaced a recommendation that fell outside the safe band. */
    val pickCorrected: Boolean = false,
    /**
     * How each market turned out, keyed by group and name.
     *
     * One map rather than a flag per role. The recommendation, the market the user
     * backed and every safe option they tick off are all just markets, and the same
     * market cannot land and miss at once — holding a separate verdict for each role
     * invited exactly that contradiction, and made the user mark one result twice.
     *
     * The key carries the group because a name alone is not unique: "Tuan rumah -1"
     * is both an Asian and a European handicap and they settle differently.
     */
    val marketOutcomes: Map<String, Outcome> = emptyMap(),
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

    val wantsMore: Boolean
        get() = action.contains("butuh", true) || needMore.isNotEmpty()

    val standDown: Boolean get() = action.equals("lewatkan", true)

    val thin: Boolean get() = confidence.equals("rendah", true) || statsMissing.size > 3

    /** Blank means the user backed the recommendation itself. */
    val backedMarket: String get() = backed.ifBlank { pick }

    fun keyOf(option: MarketOption): String = "${option.group}|${option.name}"

    /** The same key for a market known only by name, as pick and backed are. */
    fun keyOf(name: String): String =
        markets.firstOrNull { it.name == name }?.let { keyOf(it) } ?: "|$name"

    fun outcomeOf(option: MarketOption): Outcome =
        marketOutcomes[keyOf(option)] ?: Outcome.PENDING

    val pickOutcome: Outcome get() = marketOutcomes[keyOf(pick)] ?: Outcome.PENDING

    val backedOutcome: Outcome get() = marketOutcomes[keyOf(backedMarket)] ?: Outcome.PENDING

    fun outcomeFor(lens: Lens): Outcome =
        if (lens == Lens.PICK) pickOutcome else backedOutcome

    /**
     * Every market whose result is known, as evidence about the model's numbers.
     *
     * Ticking off the safe shortlist turns one match into several observations
     * instead of one, which is the only honest way to build a calibration record
     * quickly — each is a separate promise the model made and either kept or did not.
     */
    fun marks(): List<Mark> = markets.mapNotNull { option ->
        when (marketOutcomes[keyOf(option)]) {
            Outcome.WON -> Mark(option.group, option.name, option.prob, true)
            Outcome.LOST -> Mark(option.group, option.name, option.prob, false)
            else -> null
        }
    }

    fun marketFor(lens: Lens): String =
        if (lens == Lens.PICK) pick else backedMarket

    fun probFor(lens: Lens): Double =
        markets.firstOrNull { it.name == marketFor(lens) }?.prob ?: pickProb

    fun groupFor(lens: Lens): String =
        markets.firstOrNull { it.name == marketFor(lens) }?.group
            ?: if (mode == Mode.CORNER) "Corner" else "Lainnya"

    fun settledFor(lens: Lens): Boolean = outcomeFor(lens) != Outcome.PENDING

    /** True once either record has been filled in. */
    val settled: Boolean get() = settledFor(Lens.PICK) || settledFor(Lens.BACKED)

    /** The user backed something the app did not recommend. */
    val divergent: Boolean get() = backed.isNotBlank() && backed != pick

    /** In-band markets, strongest first — the shortlist worth leading with. */
    fun safePicks(floor: Double = MarketOption.SAFE_LOW): List<MarketOption> =
        markets.filter { it.inBand(floor) }.sortedByDescending { it.prob }

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
/**
 * The app's recommendation against the user's own choice, head to head.
 *
 * Only matches where BOTH records are filled in count. Comparing 21 of the app's
 * picks against 14 of the user's would be comparing two different sets of matches
 * and calling the difference skill.
 */
data class Comparison(val all: List<MatchPrediction>) {

    val both: List<MatchPrediction> =
        all.filter { it.settledFor(Lens.PICK) && it.settledFor(Lens.BACKED) }

    /** Matches where the two records could differ at all. */
    val contested: List<MatchPrediction> = both.filter { it.divergent }

    val pickWon: Int get() = contested.count { it.pickOutcome == Outcome.WON }
    val backedWon: Int get() = contested.count { it.backedOutcome == Outcome.WON }
    val n: Int get() = contested.size

    /**
     * Plain-language reading, refusing to call a winner on a handful of matches.
     *
     * At n = 5 a two-result lead happens by chance often enough to be worthless as
     * evidence, and this screen exists to stop the user acting on that kind of run.
     */
    val verdict: String
        get() = when {
            n == 0 ->
                "Belum ada laga yang dua-duanya tercatat sekaligus berbeda pilihan. " +
                    "Isi kedua hasil pada laga yang kamu pasang di luar rekomendasi, " +
                    "baru perbandingan ini ada isinya."
            n < 10 ->
                "Baru $n laga yang bisa dibandingkan ($pickWon-$backedWon). " +
                    "Terlalu sedikit untuk menyimpulkan siapa yang lebih baik — " +
                    "selisih sekecil ini sering muncul karena kebetulan saja."
            pickWon > backedWon ->
                "Dari $n laga, rekomendasi aplikasi menang $pickWon, pilihanmu $backedWon. " +
                    "Condong ke rekomendasi, tapi belum telak."
            backedWon > pickWon ->
                "Dari $n laga, pilihanmu menang $backedWon, rekomendasi aplikasi $pickWon. " +
                    "Naluri pasarmu sejauh ini lebih baik daripada rekomendasinya."
            else ->
                "Dari $n laga, keduanya sama-sama $pickWon. Belum ada bedanya."
        }
}

object Markets {

    val order = listOf(
        "Hasil Akhir",
        "Double Chance",
        "Total Gol",
        "Total Babak 1",
        "Total per Tim",
        "Multigol",
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
/**
 * The record seen through one lens.
 *
 * Built from every match rather than a pre-filtered list, so the screen cannot
 * hand it a stale snapshot: it decides for itself what counts as settled.
 */
data class Report(val all: List<MatchPrediction>, val lens: Lens = Lens.BACKED) {

    val settled: List<MatchPrediction> = all.filter { it.settledFor(lens) }

    val total: Int get() = settled.size
    val won: Int get() = settled.count { it.outcomeFor(lens) == Outcome.WON }

    val actual: Double get() = if (total == 0) 0.0 else won.toDouble() / total

    /** What the app said would happen, averaged over the same picks. */
    val promised: Double
        get() = if (total == 0) 0.0 else settled.sumOf { it.probFor(lens) } / total

    /**
     * The record split by market, and by model.
     *
     * An overall hit rate averages away the thing worth knowing. A model can be
     * honest about corners and badly overconfident about one goal line, and the
     * combined number will look fine while the user keeps losing on that line.
     * Only a split shows it.
     */
    fun byGroup(): List<Slice> = slice { it.groupFor(lens) }

    fun byModel(): List<Slice> = slice { it.model.ifBlank { "tidak tercatat" } }

    /**
     * The record split by the exact market, not just its heading.
     *
     * "Total Gol" lumps Over 1.5 together with Under 3.5, and those are different
     * bets that can run in opposite directions. The heading is where a problem
     * shows up; the exact line is where it can be acted on.
     */
    fun byMarket(): List<Slice> = slice { it.marketFor(lens) }.filter { it.total >= 2 }

    /**
     * Every settled market, not just the one filling a role on each match.
     *
     * The safe shortlist can be ticked off in full, and those verdicts are the
     * calibration record: each is a percentage the model published and then either
     * kept or did not. Independent of the lens, since a market is a market.
     */
    fun allMarks(): List<Mark> = all.flatMap { it.marks() }

    fun byMarkedMarket(): List<Slice> =
        allMarks().groupBy { it.market }
            .map { (name, list) ->
                Slice(
                    name = name,
                    total = list.size,
                    won = list.count { it.won },
                    promised = list.sumOf { it.promised } / list.size,
                )
            }
            .filter { it.total >= 2 }
            .sortedByDescending { it.total }

    private fun slice(key: (MatchPrediction) -> String): List<Slice> =
        settled.groupBy(key)
            .map { (name, list) ->
                Slice(
                    name = name,
                    total = list.size,
                    won = list.count { it.outcomeFor(lens) == Outcome.WON },
                    promised = list.sumOf { it.probFor(lens) } / list.size,
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
