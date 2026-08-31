package com.skorlogi.app.data

/** One finished match. Counts that a feed does not provide are stored as -1. */
data class Match(
    val league: String,
    val dateEpochDay: Long,
    val home: String,
    val away: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val htHomeGoals: Int = -1,
    val htAwayGoals: Int = -1,
    val homeCorners: Int = -1,
    val awayCorners: Int = -1,
    val homeCards: Int = -1,
    val awayCards: Int = -1,
    val homeShotsOnTarget: Int = -1,
    val awayShotsOnTarget: Int = -1,
) {
    val hasHalfTime: Boolean get() = htHomeGoals >= 0 && htAwayGoals >= 0
    val hasCorners: Boolean get() = homeCorners >= 0 && awayCorners >= 0
    val hasCards: Boolean get() = homeCards >= 0 && awayCards >= 0

    val result: Char
        get() = when {
            homeGoals > awayGoals -> 'H'
            homeGoals < awayGoals -> 'A'
            else -> 'D'
        }
}

/** An upcoming match, optionally with the bookmaker prices the feed shipped with it. */
data class Fixture(
    val league: String,
    val dateEpochDay: Long,
    val time: String,
    val home: String,
    val away: String,
    val oddsHome: Double = 0.0,
    val oddsDraw: Double = 0.0,
    val oddsAway: Double = 0.0,
) {
    val hasOdds: Boolean get() = oddsHome > 1.0 && oddsDraw > 1.0 && oddsAway > 1.0
    val key: String get() = "$league|$dateEpochDay|$home|$away"
}
