package com.skorsnap.app.data

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a leg is chosen from each match.
 *
 * The first three differ only in which market they take, and the difference is a
 * trade, not a ranking: a shorter price is likelier to land and pays less. None of
 * them is "value" — a longer price is not better value, only longer.
 *
 * [Strategy.VALUE] is the exception, and it earns the name because it has something
 * the others do not: the bookmaker's actual price, read off the screenshots. Value
 * is price against probability, so it is only nameable once the price is known.
 */
enum class Strategy(val label: String, val note: String) {
    RECOMMENDED(
        "Rekomendasi aplikasi",
        "Market yang direkomendasikan di tiap laga.",
    ),
    SAFEST(
        "Paling aman",
        "Peluang tertinggi di rentang aman tiap laga. Paling sering tembus, bayarannya paling kecil.",
    ),
    HIGHER_PAYING(
        "Bayaran lebih tinggi",
        "Peluang terendah yang masih di rentang aman. Bayarannya naik, tembusnya lebih jarang — " +
            "ini menaikkan bayaran, bukan menaikkan nilai.",
    ),
    /**
     * The one that uses the prices rather than displaying them.
     *
     * The other three rank by probability and leave the user to check whether the
     * book pays enough for it. This one ranks by what the bet actually returns, out
     * of the markets that still clear the safe band — which is what the prices were
     * being collected for in the first place.
     */
    VALUE(
        "Nilai terbaik",
        "Bayaran tertinggi dibanding peluangnya, dari harga yang terbaca di gambar. " +
            "Masih di rentang aman — yang berubah cuma cara memilihnya, dari 'paling " +
            "mungkin tembus' jadi 'paling untung kalau dipasang'.",
    ),
}

/**
 * One line of a slip: a match, the market taken from it, and the price the
 * bookmaker is offering.
 *
 * The price comes from the analysis when a bookmaker screen was among the
 * screenshots, and can still be typed in when it was not. Everything except [edge]
 * works without it; nothing pretends to know it.
 */
data class Leg(
    val matchId: String,
    val home: String,
    val away: String,
    val market: String,
    val group: String,
    val prob: Double,
    val thin: Boolean = false,
    val odds: Double = 0.0,
) {
    val percent: Int get() = (prob * 100).roundToInt()

    /** The price this leg must beat to be worth taking at all. */
    val breakEven: Double get() = if (prob > 1e-9) 1.0 / prob else 0.0

    val priced: Boolean get() = odds > 1.0

    /**
     * Profit per unit staked, at the price entered. Positive means the bookmaker is
     * paying more than the app thinks the outcome is worth.
     */
    val edge: Double get() = if (priced) odds * prob - 1.0 else 0.0

    val edgePercent: Int get() = (edge * 100).roundToInt()
}

/**
 * One slip, with the numbers the app computes itself.
 *
 * The language model reads the screenshots; it does not do this arithmetic. Asked
 * to combine six probabilities a model will usually produce something plausible
 * and wrong, and the whole point of the slip is the one number that has to be
 * exact.
 */
data class Slip(val legs: List<Leg>) {

    val size: Int get() = legs.size

    /** Every leg has to land. Probabilities multiply; they do not average. */
    val combined: Double get() = legs.fold(1.0) { acc, leg -> acc * leg.prob }

    val percent: Int get() = (combined * 100).roundToInt()

    /** Roughly how often this comes in — "1 dari 7" reads better than "14%". */
    val oneInN: Int get() = if (combined > 1e-9) (1.0 / combined).roundToInt() else 0

    /** What a fair bookmaker would pay. */
    val fairOdds: Double get() = if (combined > 1e-9) 1.0 / combined else 0.0

    val priced: Boolean get() = legs.isNotEmpty() && legs.all { it.priced }

    /** The price the bookmaker actually pays for the whole slip: legs multiply. */
    val bookOdds: Double get() = legs.fold(1.0) { acc, leg -> acc * leg.odds }

    /**
     * Return per unit staked.
     *
     * With real prices this is arithmetic on the user's own numbers. Without them it
     * falls back to the measured margin, which is where a parlay's reputation comes
     * from: the margin compounds once per leg whatever the legs are.
     */
    val expectedReturn: Double
        get() = if (priced) bookOdds * combined else 1.0 / Parlay.MARGIN.pow(size)

    val expectedLossPercent: Int get() = ((1.0 - expectedReturn) * 100).roundToInt()

    /** Real odds against the fair price — the only honest reading of "value". */
    val worthTaking: Boolean get() = priced && expectedReturn > 1.0

    /** How many of these legs the app expects to land, on its own numbers. */
    val expectedHits: Double get() = legs.sumOf { it.prob }

    /** Any leg the analysis itself flagged as thin. */
    val weakLegs: List<Leg> get() = legs.filter { it.thin }

    /** Legs the bookmaker is underpaying for, once prices are in. */
    val badlyPriced: List<Leg> get() = legs.filter { it.priced && it.edge <= 0 }

    fun withOdds(matchId: String, market: String, odds: Double) = copy(
        legs = legs.map {
            if (it.matchId == matchId && it.market == market) it.copy(odds = odds) else it
        }
    )
}

/**
 * A slip the user actually placed, kept so the parlays can be judged as parlays.
 *
 * The per-market record already knows whether each leg landed, but it cannot say
 * that four slips out of five died on a single leg, nor what the stakes returned.
 * A parlay is the thing that is actually bet, so it is the thing that has to be
 * measured.
 */
data class SavedSlip(
    val id: String,
    val placedAt: Long,
    val strategy: String,
    val legs: List<Leg>,
    val stake: Double = 0.0,
    val outcome: Outcome = Outcome.PENDING,
) {
    val size: Int get() = legs.size
    val combined: Double get() = legs.fold(1.0) { acc, l -> acc * l.prob }
    val percent: Int get() = (combined * 100).roundToInt()
    val bookOdds: Double get() = legs.fold(1.0) { acc, l -> acc * l.odds }
    val priced: Boolean get() = legs.isNotEmpty() && legs.all { it.priced }
    val settled: Boolean get() = outcome != Outcome.PENDING
    val title: String get() = legs.joinToString(" + ") { it.market }
    val returned: Double
        get() = if (outcome == Outcome.WON && priced) stake * bookOdds else 0.0
}

/**
 * How the placed slips have actually done.
 *
 * Two numbers matter and they answer different questions: the hit rate says
 * whether the probabilities are honest, the money says whether the prices were.
 * A slip can be perfectly calibrated and still lose money at bad prices.
 */
data class SlipReport(val all: List<SavedSlip>) {

    val settled: List<SavedSlip> = all.filter { it.settled }
    val total: Int get() = settled.size
    val won: Int get() = settled.count { it.outcome == Outcome.WON }
    val actual: Double get() = if (total == 0) 0.0 else won.toDouble() / total
    val promised: Double get() = if (total == 0) 0.0 else settled.sumOf { it.combined } / total

    val staked: Double get() = settled.filter { it.stake > 0 }.sumOf { it.stake }
    val returned: Double get() = settled.filter { it.stake > 0 }.sumOf { it.returned }
    val profit: Double get() = returned - staked
    val hasMoney: Boolean get() = staked > 0

    /** Where the losses actually come from: how many legs is too many. */
    fun byLegCount(): List<Slice> =
        settled.groupBy { it.size }
            .toList()
            .sortedBy { it.first }
            .map { (n, list) ->
                Slice(
                    name = "$n leg",
                    total = list.size,
                    won = list.count { it.outcome == Outcome.WON },
                    promised = list.sumOf { it.combined } / list.size,
                )
            }

    /**
     * The single most useful thing a parlay record can tell you, phrased so it
     * cannot be read as a promise.
     */
    val verdict: String
        get() = when {
            total == 0 -> "Belum ada parlay yang ditandai hasilnya."
            total < 10 ->
                "Baru $total parlay selesai ($won tembus). Terlalu sedikit untuk " +
                    "menyimpulkan apa pun — parlay jarang tembus, jadi butuh lebih " +
                    "banyak data daripada taruhan tunggal."
            hasMoney && profit < 0 ->
                "Dari $total parlay: $won tembus. Uangnya minus " +
                    "Rp ${money(-profit)} dari Rp ${money(staked)} dipasang."
            hasMoney ->
                "Dari $total parlay: $won tembus, untung Rp ${money(profit)} " +
                    "dari Rp ${money(staked)} dipasang."
            else -> "Dari $total parlay: $won tembus (${(actual * 100).roundToInt()}%), " +
                "dijanjikan ${(promised * 100).roundToInt()}%."
        }

    private fun money(v: Double) = String.format("%,.0f", v).replace(',', '.')
}

/**
 * The one-line summary under a market when swapping legs.
 *
 * Lives here rather than in the composable because the version that lived there
 * crashed the app: it interpolated the percentage into the string and then called
 * format() on the result, so format() met a bare "%" and threw. A pure function can
 * be tested; a string built inside a Compose lambda cannot.
 */
fun priceLabel(option: MarketOption, odds: Double): String {
    val head = "${option.percent}% · minimal ${twoDecimals(option.breakEven)}"
    if (odds <= 1.0) return head
    val edge = ((odds * option.prob - 1.0) * 100).roundToInt()
    val sign = if (edge > 0) "+" else ""
    return "$head · $sign$edge%"
}

internal fun twoDecimals(v: Double): String = String.format("%.2f", v)

object Parlay {

    /**
     * Bookmaker margin per leg. Measured rather than assumed: the mean overround
     * across 7,314 Bet365 1X2 prices in football-data.co.uk's archive.
     */
    const val MARGIN = 1.0603

    /**
     * Picks one market per match under the chosen strategy.
     *
     * A match with nothing in the safe band contributes no leg rather than its best
     * bad option: padding a slip with a coin-flip to reach six legs is how a parlay
     * that looked reasonable becomes one that cannot win.
     */
    fun build(
        matches: List<MatchPrediction>,
        strategy: Strategy,
        chosen: Map<String, String> = emptyMap(),
        floor: Double = MarketOption.SAFE_LOW,
    ): Slip = Slip(
        matches.distinctBy { it.id }.mapNotNull { m ->
            val safe = m.safePicks()
            val override = chosen[m.id]?.let { name -> m.markets.firstOrNull { it.name == name } }
            val option = override ?: when (strategy) {
                Strategy.RECOMMENDED -> m.markets.firstOrNull { it.name == m.pick }
                Strategy.SAFEST -> safe.firstOrNull()
                Strategy.HIGHER_PAYING -> safe.lastOrNull()
                // Falls back to the recommendation when this match had no price
                // screen: a leg dropped for want of odds would silently shrink the
                // slip, which reads as the app losing a match the user selected.
                Strategy.VALUE ->
                    Value.best(m, floor)?.option ?: m.markets.firstOrNull { it.name == m.pick }
            } ?: return@mapNotNull null
            Leg(
                matchId = m.id,
                home = m.home,
                away = m.away,
                market = option.name,
                group = option.group,
                prob = option.prob,
                thin = m.thin,
                // The price the analysis already read off the screenshots, so the
                // slip arrives priced instead of waiting to be typed into.
                odds = m.priceOf(option) ?: 0.0,
            )
        }
    )

    /** Matches that contribute nothing under this strategy, and why. */
    fun skipped(
        matches: List<MatchPrediction>,
        strategy: Strategy,
        chosen: Map<String, String> = emptyMap(),
    ): List<MatchPrediction> {
        val kept = build(matches, strategy, chosen).legs.map { it.matchId }.toSet()
        return matches.distinctBy { it.id }.filterNot { it.id in kept }
    }

    /**
     * The best-priced market in a match, among those with a price entered.
     *
     * This is the answer to "so swap it for something better": better means the
     * bookmaker pays furthest above the break-even price, and that can only be known
     * for markets whose price the user has actually looked up.
     */
    /**
     * Swaps a leg whose price does not clear its break-even for one that does.
     *
     * Entering 1.27 against a market that needs 1.30 is a losing bet however good
     * the reading behind it, and leaving it in the slip while merely saying so is
     * half an answer. This picks the priced market with the most room above its own
     * break-even, so the decision is made on the gap between the app's number and
     * the bookmaker's rather than on either alone.
     *
     * Returns null when nothing should change: either the current leg already
     * clears, or no alternative with a price does. It never swaps into a market
     * outside the user's appetite band — a generous price on a coin flip is still a
     * coin flip.
     */
    fun swapIfUnderpriced(
        match: MatchPrediction,
        current: MarketOption?,
        odds: Map<String, Double>,
        floor: Double = MarketOption.SAFE_LOW,
    ): MarketOption? {
        val price = current?.let { odds["${match.id}|${it.name}"] } ?: 0.0
        if (current != null && price > 1.0 && price * current.prob - 1.0 > 0) return null

        val better = match.markets
            .filter { it.inBand(floor) && it.name != current?.name }
            .mapNotNull { option ->
                val quoted = odds["${match.id}|${option.name}"] ?: return@mapNotNull null
                if (quoted <= 1.0) return@mapNotNull null
                val edge = quoted * option.prob - 1.0
                if (edge <= 0) null else option to edge
            }
            .maxByOrNull { it.second }
            ?.first
        return better
    }

    fun bestPriced(match: MatchPrediction, odds: Map<String, Double>): Leg? =
        match.markets
            .mapNotNull { option ->
                val price = odds["${match.id}|${option.name}"] ?: return@mapNotNull null
                if (price <= 1.0) return@mapNotNull null
                Leg(
                    matchId = match.id, home = match.home, away = match.away,
                    market = option.name, group = option.group,
                    prob = option.prob, thin = match.thin, odds = price,
                )
            }
            .filter { it.edge > 0 }
            .maxByOrNull { it.edge }

    /** Expected loss by leg count, for the table the parlay screen leads with. */
    fun marginTable(maxLegs: Int = 8): List<Pair<Int, Int>> =
        (1..maxLegs).map { n -> n to ((1.0 - 1.0 / MARGIN.pow(n)) * 100).roundToInt() }
}
