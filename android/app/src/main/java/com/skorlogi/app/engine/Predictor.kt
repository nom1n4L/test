package com.skorlogi.app.engine

import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.Match
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

data class FormEntry(val match: Match, val forTeam: String) {
    val isHome: Boolean get() = match.home == forTeam
    val opponent: String get() = if (isHome) match.away else match.home
    val scored: Int get() = if (isHome) match.homeGoals else match.awayGoals
    val conceded: Int get() = if (isHome) match.awayGoals else match.homeGoals
    val outcome: Char
        get() = when {
            scored > conceded -> 'M'   // Menang
            scored < conceded -> 'K'   // Kalah
            else -> 'S'                // Seri
        }
}

data class TeamForm(
    val team: String,
    val entries: List<FormEntry>,
    val played: Int,
    val avgScored: Double,
    val avgConceded: Double,
    val avgCorners: Double,
    val avgCards: Double,
    val bttsRate: Double,
    val over25Rate: Double,
    val eloRating: Double,
) {
    val formString: String get() = entries.take(6).joinToString("") { it.outcome.toString() }
    val points: Int
        get() = entries.fold(0) { acc, e ->
            acc + when (e.outcome) {
                'M' -> 3
                'S' -> 1
                else -> 0
            }
        }
}

data class ValueBet(val label: String, val modelProb: Double, val odds: Double) {
    /** Expected return per unit staked, minus the stake. */
    val edge: Double get() = modelProb * odds - 1.0
    val edgePercent: Int get() = (edge * 100).roundToInt()
}

enum class Confidence(val label: String, val note: String) {
    HIGH("Tinggi", "Kedua tim punya banyak riwayat dan model sejalan."),
    MEDIUM("Sedang", "Data cukup, tapi ada ketidakpastian."),
    LOW("Rendah", "Riwayat tipis atau dua model tidak sejalan — anggap kasar saja."),
}

data class Prediction(
    val fixture: Fixture,
    val lambdaHome: Double,
    val lambdaAway: Double,
    val groups: List<MarketGroup>,
    val homeForm: TeamForm?,
    val awayForm: TeamForm?,
    val h2h: List<Match>,
    val values: List<ValueBet>,
    val confidence: Confidence,
    val eloProbs: DoubleArray,
    val pHome: Double,
    val pDraw: Double,
    val pAway: Double,
) {
    val topScore: Triple<Int, Int, Double>?
        get() = groups.firstOrNull { it.title.startsWith("Skor") }
            ?.lines?.firstOrNull()
            ?.let { line ->
                val parts = line.label.split(" - ")
                if (parts.size == 2) {
                    Triple(parts[0].toInt(), parts[1].toInt(), line.prob)
                } else {
                    null
                }
            }

    val pick: String
        get() = when (maxOf(pHome, pDraw, pAway)) {
            pHome -> fixture.home
            pAway -> fixture.away
            else -> "Seri"
        }
}

/**
 * Everything fitted for one league, ready to answer questions about any fixture in it.
 * Fitting is done once per league and reused across all of that league's fixtures.
 */
class LeagueModel(
    val league: String,
    private val matches: List<Match>,
    private val ftRatings: Ratings,
    private val ftRho: Double,
    private val htRatings: Ratings?,
    private val shRatings: Ratings?,
    private val cornerRatings: Ratings?,
    private val cardRatings: Ratings?,
    private val elo: Map<String, Double>,
) {
    val teams: List<String> = ftRatings.teams.sorted()

    fun knows(team: String): Boolean = ftRatings.has(team)

    fun eloOf(team: String): Double = elo[team] ?: 1500.0

    fun strengthTable(): List<Triple<String, Double, Double>> =
        ftRatings.teams.map { t ->
            Triple(t, eloOf(t), ftRatings.attack[ftRatings.indexOf(t)])
        }.sortedByDescending { it.second }

    private fun grid(
        r: Ratings?,
        home: String,
        away: String,
        maxK: Int,
        rho: Double = 0.0,
        overdispersed: Boolean = false,
    ): Grid? {
        val rates = r?.rates(home, away) ?: return null
        val disp = if (overdispersed) r.dispersion else Double.POSITIVE_INFINITY
        val hv = NegBin.vector(rates[0], disp, maxK)
        val av = NegBin.vector(rates[1], disp, maxK)
        return if (rho != 0.0) {
            Grid(hv, av, correction = { i, j -> RatingFitter.tau(i, j, rates[0], rates[1], rho) })
        } else {
            Grid(hv, av)
        }
    }

    fun predict(fixture: Fixture): Prediction? {
        val home = fixture.home
        val away = fixture.away
        val base = grid(ftRatings, home, away, MAX_GOALS, ftRho) ?: return null
        val eloProbs = Elo.probabilities(eloOf(home), eloOf(away))

        // Fold Elo in by tilting the score grid until its match-result probabilities
        // hit the blended target. Doing it this way — rather than blending the 1X2
        // numbers on their own — keeps every other market consistent with the
        // headline, since they are all still read off a single distribution.
        val baseOutcome = doubleArrayOf(base.pHome, base.pDraw, base.pAway)
        val tilt = DoubleArray(3) { i ->
            val target = (1 - ELO_BLEND) * baseOutcome[i] + ELO_BLEND * eloProbs[i]
            if (baseOutcome[i] > 1e-6) target / baseOutcome[i] else 1.0
        }
        val rates0 = ftRatings.rates(home, away)!!
        val ft = Grid(
            homeVec = Poisson.vector(rates0[0], MAX_GOALS),
            awayVec = Poisson.vector(rates0[1], MAX_GOALS),
            correction = { i, j -> RatingFitter.tau(i, j, rates0[0], rates0[1], ftRho) },
            outcomeTilt = tilt,
        )

        val groups = ArrayList<MarketGroup>()
        groups.add(Markets.matchResult(ft, home, away))
        groups.add(Markets.correctScore(ft))
        groups.add(Markets.totalGoals(ft))
        groups.add(Markets.btts(ft))
        groups.add(Markets.doubleChance(ft, home, away))
        groups.add(Markets.teamTotals(ft, home, away))
        groups.add(Markets.handicap(ft, home, away))
        groups.add(Markets.winningMargin(ft, home, away))

        val ht = grid(htRatings, home, away, MAX_HALF_GOALS)
        val sh = grid(shRatings, home, away, MAX_HALF_GOALS)
        if (ht != null) groups.add(Markets.halfTime(ht, home, away))
        if (sh != null) groups.add(Markets.secondHalf(sh, home, away))
        if (ht != null && sh != null) {
            groups.add(Markets.halfTimeFullTime(ht, sh, home, away))
            groups.add(Markets.highestScoringHalf(ht, sh))
        }

        grid(cornerRatings, home, away, MAX_CORNERS, overdispersed = true)?.let {
            groups.add(Markets.corners(it, home, away))
        }
        grid(cardRatings, home, away, MAX_CARDS, overdispersed = true)?.let {
            groups.add(Markets.cards(it, home, away))
        }

        val rates = rates0

        val values = ArrayList<ValueBet>()
        if (fixture.hasOdds) {
            values.add(ValueBet("$home menang", ft.pHome, fixture.oddsHome))
            values.add(ValueBet("Seri", ft.pDraw, fixture.oddsDraw))
            values.add(ValueBet("$away menang", ft.pAway, fixture.oddsAway))
        }

        // Agreement between the two independent views of the same match.
        val disagreement = abs(ft.pHome - eloProbs[0]) + abs(ft.pAway - eloProbs[2])
        val sample = minOf(ftRatings.matches(home), ftRatings.matches(away))
        val confidence = when {
            sample >= 25 && disagreement < 0.16 -> Confidence.HIGH
            sample >= 12 && disagreement < 0.30 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return Prediction(
            fixture = fixture,
            lambdaHome = rates[0],
            lambdaAway = rates[1],
            groups = groups,
            homeForm = formOf(home),
            awayForm = formOf(away),
            h2h = matches.filter {
                (it.home == home && it.away == away) || (it.home == away && it.away == home)
            }.sortedByDescending { it.dateEpochDay }.take(8),
            values = values.sortedByDescending { it.edge },
            confidence = confidence,
            eloProbs = eloProbs,
            pHome = ft.pHome,
            pDraw = ft.pDraw,
            pAway = ft.pAway,
        )
    }

    fun formOf(team: String, limit: Int = 10): TeamForm? {
        val entries = matches.asSequence()
            .filter { it.home == team || it.away == team }
            .sortedByDescending { it.dateEpochDay }
            .take(limit)
            .map { FormEntry(it, team) }
            .toList()
        if (entries.isEmpty()) return null

        val withCorners = entries.filter { it.match.hasCorners }
        val withCards = entries.filter { it.match.hasCards }

        return TeamForm(
            team = team,
            entries = entries,
            played = entries.size,
            avgScored = entries.map { it.scored }.average(),
            avgConceded = entries.map { it.conceded }.average(),
            avgCorners = if (withCorners.isEmpty()) {
                -1.0
            } else {
                withCorners.map { if (it.isHome) it.match.homeCorners else it.match.awayCorners }.average()
            },
            avgCards = if (withCards.isEmpty()) {
                -1.0
            } else {
                withCards.map { if (it.isHome) it.match.homeCards else it.match.awayCards }.average()
            },
            bttsRate = entries.count { it.scored > 0 && it.conceded > 0 }.toDouble() / entries.size,
            over25Rate = entries.count { it.scored + it.conceded > 2 }.toDouble() / entries.size,
            eloRating = eloOf(team),
        )
    }

    companion object {
        const val MAX_GOALS = 10
        const val MAX_HALF_GOALS = 6
        const val MAX_CORNERS = 24
        const val MAX_CARDS = 14

        /** Matches older than this are dropped before fitting. */
        const val HISTORY_DAYS = 1100

        /**
         * How much of the match-result probability comes from Elo rather than the
         * goal-based fit. Chosen by sweeping the value against held-out seasons:
         * the curve is very flat, and this sits near the best of both log loss and
         * raw accuracy.
         */
        const val ELO_BLEND = 0.20

        /** Per-day weight decay; see [build]. */
        const val DEFAULT_DECAY = 0.0025

        /**
         * Shrinkage for the corner and card models, kept separate from the goal
         * models and far stronger.
         *
         * Corner counts are noisier than goals and depend much less on which teams
         * are playing, so an unshrunk fit produces confident-looking numbers that
         * do not survive contact with reality — measured calibration had it
         * claiming 80% on outcomes that happened barely half the time.
         */
        const val SECONDARY_L2 = 12.0

        /**
         * Fits every sub-model for a league.
         *
         * @param decay per-day exponential weight decay. The default halves a
         *   match's influence after roughly nine months. A sweep over held-out
         *   seasons put the optimum here, though the curve is shallow enough that
         *   anything from four months to a year performs about the same.
         */
        fun build(
            league: String,
            all: List<Match>,
            today: Long,
            decay: Double = DEFAULT_DECAY,
            l2: Double = RatingFitter.DEFAULT_L2,
            secondaryL2: Double = SECONDARY_L2,
        ): LeagueModel? {
            val matches = all.filter { today - it.dateEpochDay in 0..HISTORY_DAYS }
                .sortedBy { it.dateEpochDay }
            if (matches.size < 30) return null

            // Only teams currently active — a side that stopped appearing a year ago
            // has been relegated or has left the division.
            val recentCutoff = today - 400
            val active = matches.filter { it.dateEpochDay >= recentCutoff }
                .flatMap { listOf(it.home, it.away) }
                .groupingBy { it }.eachCount()
                .filter { it.value >= 4 }
                .keys

            val teams = matches.flatMap { listOf(it.home, it.away) }
                .distinct()
                .filter { it in active }
                .sorted()
            if (teams.size < 4) return null

            val idx = teams.withIndex().associate { (i, t) -> t to i }
            val usable = matches.filter { idx.containsKey(it.home) && idx.containsKey(it.away) }
            if (usable.size < 30) return null

            fun weightOf(m: Match) = exp(-decay * (today - m.dateEpochDay))

            fun observations(
                filter: (Match) -> Boolean,
                homeCount: (Match) -> Int,
                awayCount: (Match) -> Int,
            ): List<Observation> = usable.filter(filter).map {
                Observation(idx[it.home]!!, idx[it.away]!!, homeCount(it), awayCount(it), weightOf(it))
            }

            val ftObs = observations({ true }, { it.homeGoals }, { it.awayGoals })
            val ftRatings = RatingFitter.fit(teams, ftObs, l2 = l2) ?: return null
            val rho = RatingFitter.fitRho(ftRatings, ftObs, teams)

            val htObs = observations({ it.hasHalfTime }, { it.htHomeGoals }, { it.htAwayGoals })
            val htRatings = if (htObs.size >= 30) RatingFitter.fit(teams, htObs, l2 = l2) else null

            val shObs = observations(
                { it.hasHalfTime },
                { it.homeGoals - it.htHomeGoals },
                { it.awayGoals - it.htAwayGoals },
            ).filter { it.homeCount >= 0 && it.awayCount >= 0 }
            val shRatings = if (shObs.size >= 30) RatingFitter.fit(teams, shObs, l2 = l2) else null

            val cornerObs = observations({ it.hasCorners }, { it.homeCorners }, { it.awayCorners })
            val cornerRatings = if (cornerObs.size >= 30) RatingFitter.fit(teams, cornerObs, l2 = secondaryL2) else null

            val cardObs = observations({ it.hasCards }, { it.homeCards }, { it.awayCards })
            val cardRatings = if (cardObs.size >= 30) RatingFitter.fit(teams, cardObs, l2 = secondaryL2) else null

            return LeagueModel(
                league = league,
                matches = usable,
                ftRatings = ftRatings,
                ftRho = rho,
                htRatings = htRatings,
                shRatings = shRatings,
                cornerRatings = cornerRatings,
                cardRatings = cardRatings,
                elo = Elo.rate(usable),
            )
        }
    }
}
