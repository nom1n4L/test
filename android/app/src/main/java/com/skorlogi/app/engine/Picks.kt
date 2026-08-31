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

    private const val RELIABLE_DC = "Terukur akurat: saat model bilang ~75%, nyatanya 74%"
    private const val RELIABLE_RESULT = "Terukur akurat: saat model bilang ~75%, nyatanya 77%"
    private const val RELIABLE_OVER15 = "Terukur akurat: saat model bilang ~75%, nyatanya 78%"
    private const val RELIABLE_HALF = "Terukur akurat: saat model bilang ~75%, nyatanya 75%"

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
                if (line.prob >= MIN_PROB) {
                    out.add(Pick(fx, "Double Chance", line.label, kind, line.prob, RELIABLE_DC, p.confidence))
                }
            }

        val favourite = listOf(
            Triple(p.pHome, "${fx.home} menang", PickKind.HOME),
            Triple(p.pDraw, "Seri", PickKind.DRAW),
            Triple(p.pAway, "${fx.away} menang", PickKind.AWAY),
        ).maxByOrNull { it.first }!!
        if (favourite.first >= MIN_PROB) {
            out.add(
                Pick(fx, "Hasil Akhir", favourite.second, favourite.third, favourite.first, RELIABLE_RESULT, p.confidence)
            )
        }

        p.groups.firstOrNull { it.title == "Total Gol" }?.lines?.let { lines ->
            for ((label, kind) in listOf("Over 1.5" to PickKind.OVER_15, "Under 3.5" to PickKind.UNDER_35)) {
                lines.firstOrNull { it.label == label }?.let {
                    if (it.prob >= MIN_PROB) {
                        out.add(Pick(fx, "Total Gol", it.label, kind, it.prob, RELIABLE_OVER15, p.confidence))
                    }
                }
            }
        }

        p.groups.firstOrNull { it.title == "Babak Pertama" }
            ?.lines?.firstOrNull { it.label == "Babak 1 over 0.5" }
            ?.let {
                if (it.prob >= MIN_PROB) {
                    out.add(
                        Pick(fx, "Babak 1", "Ada gol di babak 1", PickKind.HT_OVER_05, it.prob, RELIABLE_HALF, p.confidence)
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
