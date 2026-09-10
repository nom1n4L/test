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

    /**
     * "No minimum". Every real price is above this, so it filters nothing.
     *
     * A price floor is a separate wish from a probability floor and has to stay
     * separate: the user wants a payout worth collecting, but not at the cost of
     * being talked into a market they already decided was too risky. So this only
     * ever narrows the safe band — it can remove candidates, never add one from
     * below the floor, and when nothing inside the band pays enough the app says so
     * instead of quietly reaching down for something that does.
     */
    const val NO_MINIMUM = 1.0

    data class Priced(
        val option: MarketOption,
        val price: Double,
        /** Expected return per unit staked: price × probability − 1. */
        val edge: Double,
    ) {
        val edgePercent: Int get() = (edge * 100).roundToInt()
    }

    /** Every in-band market the bookmaker priced, best value first. */
    fun ranked(
        match: MatchPrediction,
        floor: Double,
        minOdds: Double = NO_MINIMUM,
    ): List<Priced> =
        match.markets.mapNotNull { option ->
            if (!option.inBand(floor)) return@mapNotNull null
            val price = match.priceOf(option) ?: return@mapNotNull null
            if (price < minOdds - 1e-9) return@mapNotNull null
            val edge = price * option.prob - 1.0
            if (edge > TOO_GOOD) return@mapNotNull null
            Priced(option, price, edge)
        }.sortedByDescending { it.edge }

    /** The best price anywhere inside the band, ignoring the minimum. */
    fun bestPriceInBand(match: MatchPrediction, floor: Double): Priced? =
        ranked(match, floor).maxByOrNull { it.price }

    /**
     * What the price floor did to this match, in words.
     *
     * Blank when it changed nothing. The interesting case is when it changed
     * everything — no safe market pays enough — because that is a real answer about
     * the match rather than a malfunction, and the app has to say it out loud or the
     * user will read the unchanged recommendation as the minimum being ignored.
     */
    fun shortfall(match: MatchPrediction, floor: Double, minOdds: Double): String {
        if (minOdds <= NO_MINIMUM) return ""
        if (ranked(match, floor).isEmpty()) return ""
        if (ranked(match, floor, minOdds).isNotEmpty()) return ""
        val best = bestPriceInBand(match, floor)
        return buildString {
            append("Minimum bayaran ${Odds.oddsLabel(minOdds)} TIDAK terpenuhi di laga ini. ")
            if (best != null) {
                append("Yang paling besar di rentang aman cuma ")
                append("${Odds.oddsLabel(best.price)} (${best.option.name}, ${best.option.percent}%). ")
            }
            append("Aplikasi tidak menurunkan batas amanmu cuma demi bayaran — ")
            append("laga yang bandar hargai murah memang murah, dan memaksakannya ")
            append("berarti pasang market yang kamu sendiri sudah bilang terlalu berisiko. ")
            append("Lewati laga ini, atau turunkan minimumnya kalau memang mau.")
        }
    }

    /**
     * The market to recommend, or null when no priced market clears the floor.
     *
     * Null rather than a fallback so the caller keeps whatever the model chose: an
     * unpriced match is the situation the app was already built for, and quietly
     * substituting a different rule there would change answers for matches this
     * feature never touched.
     */
    fun best(
        match: MatchPrediction,
        floor: Double,
        minOdds: Double = NO_MINIMUM,
    ): Priced? = ranked(match, floor, minOdds).firstOrNull { it.edge > 0.0 }
        // Nothing in the band pays the minimum. Fall back to the band alone rather
        // than to nothing: the recommendation the user had before they ever set a
        // minimum is still the best safe bet available, and withholding it would
        // punish them for asking a question.
        ?: if (minOdds > NO_MINIMUM) ranked(match, floor).firstOrNull { it.edge > 0.0 } else null

    /**
     * Applies the choice to the analysis.
     *
     * Only when the chosen market actually beats what the model picked at the price
     * on offer. If the model's own pick is already the best-priced thing on the
     * board there is nothing to change, and changing it anyway would make the
     * recommendation look unstable for no gain.
     */
    fun apply(
        match: MatchPrediction,
        floor: Double,
        minOdds: Double = NO_MINIMUM,
    ): MatchPrediction {
        // Idempotent, for the same reason as the blend: prices arrive late and more
        // than once. Without restoring the model's own pick first, a second pass
        // would record the previous value pick as "what the model recommended", and
        // the screen would claim a swap that never happened.
        val start =
            if (!match.valuePick || match.valueWas.isBlank()) match
            else match.copy(
                oddsNote = "",
                pick = match.valueWas,
                pickProb = match.markets.firstOrNull { it.name == match.valueWas }?.prob
                    ?: match.pickProb,
                valuePick = false,
                valueWas = "",
                valueEdge = 0.0,
            )

        val note = shortfall(start, floor, minOdds)
        val best = best(start, floor, minOdds) ?: return start.copy(oddsNote = note)
        if (best.option.name == start.pick) return start.copy(oddsNote = note)

        val current = start.markets.firstOrNull { it.name == start.pick }
        val currentEdge = current?.let { option ->
            start.priceOf(option)?.let { it * option.prob - 1.0 }
        }
        // An unpriced recommendation loses to a priced one: the whole point is to
        // recommend something whose payout is known to cover it.
        // A recommendation that clears the price floor beats one that does not, even
        // on edge. That is the whole point of setting a floor: the user is telling
        // the app that a 1.15 payout is not a bet they want, and answering "but its
        // expected return is better" is answering a question they did not ask.
        val currentPrice = current?.let { start.priceOf(it) }
        val currentClears = currentPrice != null && currentPrice >= minOdds - 1e-9
        val bestClears = best.price >= minOdds - 1e-9
        if (currentEdge != null && currentEdge >= best.edge && currentClears == bestClears) {
            return start.copy(oddsNote = note)
        }
        if (currentClears && !bestClears) return start.copy(oddsNote = note)

        return start.copy(
            pick = best.option.name,
            pickProb = best.option.prob,
            pickCorrected = true,
            valuePick = true,
            valueWas = start.pick,
            valueEdge = best.edge,
            oddsNote = note,
        )
    }
}
