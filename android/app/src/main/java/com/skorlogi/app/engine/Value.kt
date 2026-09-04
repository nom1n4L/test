package com.skorlogi.app.engine

import com.skorlogi.app.data.OddsApi
import com.skorlogi.app.data.OddsEvent
import com.skorlogi.app.data.Price
import com.skorlogi.app.data.Selection
import kotlin.math.roundToInt

/**
 * A bet the sharp book prices higher than the book you would actually place it
 * with — the only kind of edge this app can claim honestly.
 */
data class PricedEdge(
    val selection: Selection,
    val best: Price,
    val sharpProb: Double,
) {
    /** Expected return per unit staked, minus the stake. */
    val edge: Double get() = best.price * sharpProb - 1.0
    val edgePercent: Int get() = (edge * 100).roundToInt()
    val sharpPercent: Int get() = (sharpProb * 100).roundToInt()

    /** What the sharp book's own price for this would be, before its margin. */
    val fairOdds: Double get() = if (sharpProb > 1e-9) 1.0 / sharpProb else 0.0
}

/** How much the house takes, one book against the best prices available. */
data class MarginComparison(
    val market: String,
    val point: Double?,
    val singleBook: Double,
    val bestPrices: Double,
) {
    val singlePercent: Int get() = ((singleBook - 1) * 100).roundToInt()
    val bestPercent: Int get() = ((bestPrices - 1) * 100).roundToInt()
    val saved: Int get() = singlePercent - bestPercent
}

/**
 * Reads value out of a set of prices rather than out of a forecast.
 *
 * The app's own model was measured against the market and lost everywhere, so
 * asking it to find mispriced bets would only surface its own errors. What can be
 * done without a forecast is arithmetic on the prices themselves.
 *
 * The reference is Pinnacle: it runs a much thinner margin than the books most
 * people use and moves its line on money rather than on opinion, which makes its
 * price, with the margin divided out, the best public estimate of what is actually
 * going to happen. When a softer book pays more than that estimate says it should,
 * the difference is real and measurable — nothing here is predicted.
 *
 * The margin is removed proportionally, which slightly overstates the chance of
 * longshots and understates favourites. That is why small edges are ignored: below
 * a couple of percent the number is inside the error of the method rather than a
 * finding.
 */
object Value {

    /** Under this an apparent edge is method noise, not an opportunity. */
    const val MIN_EDGE = 0.02

    /**
     * Fair probabilities from the sharp book, per outcome name, for one market
     * and line. Null when that book has not quoted the whole market — a partial
     * quote cannot have its margin removed.
     */
    fun sharpProbabilities(
        event: OddsEvent,
        market: String,
        point: Double?,
        bookmaker: String = OddsApi.SHARP_BOOKMAKER,
    ): Map<String, Double>? {
        val group = event.selections.filter { it.market == market && it.point == point }
        if (group.size < 2) return null
        val quoted = group.mapNotNull { s -> s.priceFrom(bookmaker)?.let { s.name to it } }
        if (quoted.size != group.size) return null

        val inverse = quoted.map { (name, price) -> name to 1.0 / price }
        val overround = inverse.sumOf { it.second }
        if (overround <= 0) return null
        return inverse.associate { (name, inv) -> name to inv / overround }
    }

    /** Every selection the best available price pays more for than the sharp book implies. */
    fun edges(event: OddsEvent, minEdge: Double = MIN_EDGE): List<PricedEdge> {
        val out = ArrayList<PricedEdge>()
        val groups = event.selections.groupBy { it.market to it.point }
        for ((keyPair, group) in groups) {
            val (market, point) = keyPair
            val fair = sharpProbabilities(event, market, point) ?: continue
            for (selection in group) {
                val prob = fair[selection.name] ?: continue
                // Never call the reference book's own price an edge against itself.
                val best = selection.prices
                    .filter { it.bookmakerKey != OddsApi.SHARP_BOOKMAKER }
                    .maxByOrNull { it.price } ?: continue
                val candidate = PricedEdge(selection, best, prob)
                if (candidate.edge >= minEdge) out.add(candidate)
            }
        }
        return out.sortedByDescending { it.edge }
    }

    /**
     * What sticking to one bookmaker costs against shopping every price. This is
     * the guaranteed part: no forecast, no judgement, just a bigger number for the
     * identical bet.
     */
    fun marginComparison(event: OddsEvent, bookmaker: String): List<MarginComparison> {
        val out = ArrayList<MarginComparison>()
        val groups = event.selections.groupBy { it.market to it.point }
        for ((keyPair, group) in groups) {
            val (market, point) = keyPair
            if (group.size < 2) continue

            val single = group.mapNotNull { it.priceFrom(bookmaker) }
            if (single.size != group.size) continue
            val best = group.mapNotNull { it.best?.price }
            if (best.size != group.size) continue

            out.add(
                MarginComparison(
                    market = market,
                    point = point,
                    singleBook = single.sumOf { 1.0 / it },
                    bestPrices = best.sumOf { 1.0 / it },
                )
            )
        }
        return out
    }

    /**
     * The best available price for one of the app's own picks, when the feed
     * quotes that market. Only the kinds that map cleanly onto a quoted selection
     * are covered — double chance and the half-time lines are not in the market
     * list the app asks for, so those return null rather than a guess.
     */
    fun priceFor(event: OddsEvent, kind: PickKind, home: String, away: String): Price? {
        fun team(name: String) = event.selections
            .firstOrNull { it.market == "h2h" && similar(it.name, name) }?.best

        fun total(point: Double, side: String) = event.selections
            .firstOrNull { it.market == "totals" && it.point == point && it.name.equals(side, true) }
            ?.best

        return when (kind) {
            PickKind.HOME -> team(home)
            PickKind.AWAY -> team(away)
            PickKind.DRAW -> event.selections
                .firstOrNull { it.market == "h2h" && it.name.equals("Draw", true) }?.best
            PickKind.OVER_15 -> total(1.5, "Over")
            PickKind.UNDER_35 -> total(3.5, "Under")
            PickKind.DC_1X, PickKind.DC_12, PickKind.DC_X2, PickKind.HT_OVER_05 -> null
        }
    }

    /**
     * Joins an odds feed event to a fixture from the match data.
     *
     * The two sources spell teams differently — "Arsenal FC" against "Arsenal",
     * "Bologna FC 1909" against "Bologna" — so names are stripped of the decoration
     * clubs carry before being compared.
     */
    fun matches(event: OddsEvent, home: String, away: String, day: Long): Boolean {
        if (kotlin.math.abs(event.commenceEpochDay - day) > 1) return false
        return similar(event.home, home) && similar(event.away, away)
    }

    /**
     * Corporate decoration only.
     *
     * The tempting additions — city, united, town, rovers, wanderers — are exactly
     * what separates one English club from another, and stripping them collapsed
     * Manchester United onto Manchester City and Sheffield United onto Sheffield
     * Wednesday. Attaching one club's odds to another's fixture is the worst thing
     * this file could do, so the list stays narrow.
     */
    private val NOISE = Regex(
        "\\b(fc|afc|cf|sc|ac|as|ss|ssc|bc|sv|sk|sc|vfb|vfl|tsg|fsv|bsc|cd|ud|rcd|" +
            "club|calcio|football|futbol|\\d{4})\\b"
    )

    /** True when two spellings are plausibly the same club. */
    fun similar(a: String, b: String): Boolean {
        val x = normalise(a)
        val y = normalise(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        // One being a prefix of the other covers "Wolverhampton" vs "Wolverhampton
        // Wanderers"; requiring length keeps "Man" from matching everything.
        if (x.length >= 4 && y.length >= 4 && (x.startsWith(y) || y.startsWith(x))) return true
        return x.length >= 5 && y.length >= 5 && (x.contains(y) || y.contains(x))
    }

    private fun normalise(name: String): String =
        name.lowercase()
            .replace('&', ' ')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .let { NOISE.replace(it, " ") }
            .replace(Regex("\\s+"), "")
}
