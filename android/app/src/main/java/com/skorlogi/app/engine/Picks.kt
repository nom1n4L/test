package com.skorlogi.app.engine

import com.skorlogi.app.data.Fixture

/**
 * Builds the daily shortlist.
 *
 * The ranking deliberately ignores most of the markets the app can produce. Which
 * ones survive is decided by measured calibration rather than by how confident the
 * model sounds: double chance, the match-result favourite, over 1.5 goals and
 * first-half goals all came back out of sample saying what they meant. Corners and
 * cards did not — at a stated 70%+ they landed near a coin toss — so they are not
 * offered as picks at all, however tempting their numbers look on the match page.
 *
 * Everything here is still a probability, not a tip. A 78% pick loses roughly one
 * time in five, and that is the honest expectation, not a failure of the model.
 *
 * The list is also bounded at both ends. Anything under 68% is not confident
 * enough to lead with, and anything over 92% is not a bet: it prices near 1.05,
 * which no bookmaker offers, and it sits past the range where the calibration was
 * measured. A shortlist full of near-certainties nobody can back is a shortlist
 * that wastes the reader's attention.
 */
/**
 * The machine-readable half of a pick. Stored alongside the label so a followed
 * pick can be settled against the real result later without parsing display text.
 */
enum class PickKind {
    HOME, DRAW, AWAY, DC_1X, DC_12, DC_X2, OVER_15, UNDER_35, HT_OVER_05;

    /** Did this pick come in? Null when the match lacks the data to tell. */
    fun settle(homeGoals: Int, awayGoals: Int, htHome: Int, htAway: Int): Boolean? {
        val total = homeGoals + awayGoals
        return when (this) {
            HOME -> homeGoals > awayGoals
            DRAW -> homeGoals == awayGoals
            AWAY -> homeGoals < awayGoals
            DC_1X -> homeGoals >= awayGoals
            DC_12 -> homeGoals != awayGoals
            DC_X2 -> homeGoals <= awayGoals
            OVER_15 -> total > 1
            UNDER_35 -> total < 4
            HT_OVER_05 -> if (htHome < 0 || htAway < 0) null else htHome + htAway > 0
        }
    }
}

data class Pick(
    val fixture: Fixture,
    val market: String,
    val selection: String,
    val kind: PickKind,
    val prob: Double,
    val reliability: String,
    val confidence: Confidence,
) {
    val percent: Int get() = Math.round(prob * 100).toInt()
    val fairOdds: Double get() = if (prob > 1e-6) 1.0 / prob else 0.0
}

object Picks {

    /** Below this the pick is not worth listing as a "best" anything. */
    private const val MIN_PROB = 0.68

    /**
     * And above this it is not worth listing either, for two reasons.
     *
     * A 98% call prices at 1.02, which no bookmaker offers and no margin survives,
     * so it is not a bet anyone can place. It is also past where calibration was
     * measured — the archive leagues never produced claims that extreme, so there
     * is no evidence the number means what it says up there. Both arguments point
     * the same way: leave it off the list.
     */
    private const val MAX_PROB = 0.92

    /**
     * What the measurements actually say, per market and per confidence band.
     *
     * Quoting a figure measured at 75% next to a 95% claim is the kind of thing
     * that reads as evidence and is not, so each band carries its own number and
     * the unmeasured ones say so.
     */
    private fun reliability(family: String, prob: Double): String {
        val band = when {
            prob < 0.70 -> 0
            prob < 0.80 -> 1
            prob < 0.90 -> 2
            else -> 3
        }
        return when (family) {
            "DC" -> when (band) {
                1 -> "Diuji: klaim ~75%, nyatanya 74%"
                2 -> "Diuji: klaim ~84%, nyatanya 84%"
                else -> "Diuji: klaim ~93%, nyatanya 95%"
            }
            "RESULT" -> when (band) {
                1 -> "Diuji: klaim ~75%, nyatanya 77%"
                2 -> "Diuji: klaim ~84%, nyatanya 90%"
                else -> "Di atas 90% belum pernah teruji — perlakukan dengan hati-hati"
            }
            "TOTAL" -> when (band) {
                1 -> "Diuji: klaim ~75%, nyatanya 78%"
                2 -> "Diuji: klaim ~84%, nyatanya 84%"
                else -> "Diuji: klaim ~92%, nyatanya 92%"
            }
            else -> when (band) {
                1 -> "Diuji: klaim ~75%, nyatanya 75%"
                2 -> "Diuji: klaim ~83%, nyatanya 79% — sedikit kelewat pede"
                else -> "Di atas 90% belum pernah teruji — perlakukan dengan hati-hati"
            }
        }
    }

    /** Candidate picks from one match, best first. */
    fun from(p: Prediction): List<Pick> {
        val out = ArrayList<Pick>(4)
        val fx = p.fixture

        // Thin history makes every number below unreliable regardless of market.
        if (p.confidence == Confidence.LOW) return emptyList()

        p.groups.firstOrNull { it.title.startsWith("Double Chance") }
            ?.lines?.filter { it.label.contains('(') }
            ?.maxByOrNull { it.prob }
            ?.let { line ->
                val kind = when {
                    line.label.contains("(1X)") -> PickKind.DC_1X
                    line.label.contains("(12)") -> PickKind.DC_12
                    else -> PickKind.DC_X2
                }
                if (line.prob in MIN_PROB..MAX_PROB) {
                    out.add(
                        Pick(fx, "Double Chance", line.label, kind, line.prob,
                            reliability("DC", line.prob), p.confidence)
                    )
                }
            }

        val favourite = listOf(
            Triple(p.pHome, "${fx.home} menang", PickKind.HOME),
            Triple(p.pDraw, "Seri", PickKind.DRAW),
            Triple(p.pAway, "${fx.away} menang", PickKind.AWAY),
        ).maxByOrNull { it.first }!!
        if (favourite.first in MIN_PROB..MAX_PROB) {
            out.add(
                Pick(fx, "Hasil Akhir", favourite.second, favourite.third, favourite.first,
                    reliability("RESULT", favourite.first), p.confidence)
            )
        }

        p.groups.firstOrNull { it.title == "Total Gol" }?.lines?.let { lines ->
            for ((label, kind) in listOf("Over 1.5" to PickKind.OVER_15, "Under 3.5" to PickKind.UNDER_35)) {
                lines.firstOrNull { it.label == label }?.let {
                    if (it.prob in MIN_PROB..MAX_PROB) {
                        out.add(
                            Pick(fx, "Total Gol", it.label, kind, it.prob,
                                reliability("TOTAL", it.prob), p.confidence)
                        )
                    }
                }
            }
        }

        p.groups.firstOrNull { it.title == "Babak Pertama" }
            ?.lines?.firstOrNull { it.label == "Babak 1 over 0.5" }
            ?.let {
                if (it.prob in MIN_PROB..MAX_PROB) {
                    out.add(
                        Pick(fx, "Babak 1", "Ada gol di babak 1", PickKind.HT_OVER_05, it.prob,
                            reliability("HALF", it.prob), p.confidence)
                    )
                }
            }

        return out.sortedByDescending { it.prob }
    }

    /**
     * The shortlist across every match, strongest first, with at most one pick per
     * match so the list does not fill up with four angles on the same game.
     */
    fun best(predictions: List<Prediction>, limit: Int = 25): List<Pick> =
        predictions
            .mapNotNull { from(it).firstOrNull() }
            .sortedWith(
                compareByDescending<Pick> { it.confidence == Confidence.HIGH }
                    .thenByDescending { it.prob }
            )
            .take(limit)
}
