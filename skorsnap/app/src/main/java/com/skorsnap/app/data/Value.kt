package com.skorsnap.app.data

import kotlin.math.roundToInt

/**
 * Chooses the market to back, once the bookmaker's prices are known.
 *
 * Without prices there is only one thing to rank by — the probability — so the app
 * recommended whatever was likeliest and left the user to notice that the book paid
 * too little for it. That is backwards. A bet is worth placing when the price is
 * higher than the probability requires, and nothing else; an 80% market at 1.20 is a
 * slow loss, and a 69% market at 1.60 is a bet.
 *
 * So when prices are in, the recommendation is the highest expected return among the
 * markets that still clear the user's probability floor — not the highest
 * probability. The floor stays because expected return alone points at longshots
 * whose variance the user has not asked for.
 */
object Value {

    /**
     * A price this far above the model's own reading is not an opportunity.
     *
     * When the book pays far more than a market's probability should allow, the
     * usual explanation is that the app misread the match — a wrong average, a
     * transposed digit, a team name mixed up. Backing it is betting on the
     * screenshot being right and the market being wrong. Excluded from the
     * recommendation, and reported instead.
     */
    const val TOO_GOOD = 0.35

    data class Priced(
        val option: MarketOption,
        val price: Double,
        /** Expected return per unit staked: price × probability − 1. */
        val edge: Double,
    ) {
        val edgePercent: Int get() = (edge * 100).roundToInt()
    }

    /** Every in-band market the bookmaker priced, best value first. */
    fun ranked(match: MatchPrediction, floor: Double): List<Priced> =
        match.markets.mapNotNull { option ->
            if (!option.inBand(floor)) return@mapNotNull null
            val price = match.priceOf(option) ?: return@mapNotNull null
            val edge = price * option.prob - 1.0
            if (edge > TOO_GOOD) return@mapNotNull null
            Priced(option, price, edge)
        }.sortedByDescending { it.edge }

    /**
     * The market to recommend, or null when no priced market clears the floor.
     *
     * Null rather than a fallback so the caller keeps whatever the model chose: an
     * unpriced match is the situation the app was already built for, and quietly
     * substituting a different rule there would change answers for matches this
     * feature never touched.
     */
    fun best(match: MatchPrediction, floor: Double): Priced? =
        ranked(match, floor).firstOrNull { it.edge > 0.0 }

    /**
     * Applies the choice to the analysis.
     *
     * Only when the chosen market actually beats what the model picked at the price
     * on offer. If the model's own pick is already the best-priced thing on the
     * board there is nothing to change, and changing it anyway would make the
     * recommendation look unstable for no gain.
     */
    fun apply(match: MatchPrediction, floor: Double): MatchPrediction {
        val best = best(match, floor) ?: return match
        if (best.option.name == match.pick) return match

        val current = match.markets.firstOrNull { it.name == match.pick }
        val currentEdge = current?.let { option ->
            match.priceOf(option)?.let { it * option.prob - 1.0 }
        }
        // An unpriced recommendation loses to a priced one: the whole point is to
        // recommend something whose payout is known to cover it.
        if (currentEdge != null && currentEdge >= best.edge) return match

        return match.copy(
            pick = best.option.name,
            pickProb = best.option.prob,
            pickCorrected = true,
            valuePick = true,
            valueWas = match.pick,
            valueEdge = best.edge,
        )
    }
}
