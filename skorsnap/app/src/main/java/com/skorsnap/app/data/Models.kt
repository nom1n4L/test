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
    val raw: String = "",
) {
    val title: String get() = if (home.isBlank()) "Pertandingan" else "$home vs $away"

    val pickPercent: Int get() = Math.round(pickProb * 100).toInt()

    val pickBreakEven: Double get() = if (pickProb > 1e-9) 1.0 / pickProb else 0.0

    val outcome: String
        get() = when (maxOf(probHome, probDraw, probAway)) {
            probHome -> "$home menang"
            probAway -> "$away menang"
            else -> "Seri"
        }

    val thin: Boolean get() = confidence.equals("rendah", true) || statsMissing.size > 3
}
