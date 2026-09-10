package com.skorsnap.app.data

/**
 * The facts of a finished match, as read off a result screenshot.
 *
 * Only facts. The model reads digits and nothing else — it is not asked which
 * markets landed, and that division is the whole point. A model deciding that
 * "1X & Over 2.5" won is a judgement call nobody can audit; a model reading "2-1"
 * is a transcription anyone can check against the picture, and the settlement is
 * then arithmetic that cannot be wrong in a way that hides.
 */
data class MatchResult(
    val homeGoals: Int,
    val awayGoals: Int,
    /** Half-time, when the screenshot shows it. Null when it does not. */
    val htHome: Int? = null,
    val htAway: Int? = null,
    val homeCorners: Int? = null,
    val awayCorners: Int? = null,
    /** What the reader could not make out, in its own words. */
    val problem: String = "",
) {
    val goals: Int get() = homeGoals + awayGoals
    val htGoals: Int? get() = htHome?.let { h -> htAway?.let { h + it } }
    val corners: Int? get() = homeCorners?.let { h -> awayCorners?.let { h + it } }
    val score: String get() = "$homeGoals-$awayGoals"
    val bothScored: Boolean get() = homeGoals >= 1 && awayGoals >= 1
    val margin: Int get() = homeGoals - awayGoals

    val summary: String
        get() = buildString {
            append("Skor akhir $homeGoals-$awayGoals")
            htGoals?.let { append(", babak 1 $htHome-$htAway") }
            corners?.let { append(", corner $homeCorners-$awayCorners") }
            append(".")
        }
}

/**
 * Works out which markets landed, from the score alone.
 *
 * Every rule here is a rule of the bet, not a guess. Where a market cannot be
 * settled from what the screenshot showed — first-half lines with no half-time
 * score, corner lines with no corner count — it is left pending rather than
 * assumed, because a market wrongly recorded as lost poisons the calibration
 * record permanently and silently, which is worse than one left blank.
 *
 * Asian handicaps that push or half-push are also left pending. The app has no
 * concept of a returned stake, and calling a returned stake a win or a loss would
 * put a false result into the very record that is supposed to keep it honest.
 */
object Settle {

    /**
     * The verdict for one market, or null when this result cannot settle it.
     */
    fun outcome(option: MarketOption, r: MatchResult): Outcome? {
        // European handicaps are a three-way market — home, draw-after-handicap,
        // away — and this app only lists two of the three. Settling them as a
        // two-way bet would disagree with the probability that was published for
        // them, and a disagreement like that lands in the calibration record as a
        // fact. Left unsettled until the market list itself is fixed.
        if (option.group == "Handicap Eropa") return null
        val name = option.name
        won(name, r)?.let { return if (it) Outcome.WON else Outcome.LOST }
        return null
    }

    /** True/false when the rule applies, null when the facts are missing. */
    internal fun won(name: String, r: MatchResult): Boolean? {
        val h = r.homeGoals
        val a = r.awayGoals
        val total = r.goals

        // --- result and double chance
        when (name) {
            "Tuan rumah menang" -> return h > a
            "Seri" -> return h == a
            "Tandang menang" -> return h < a
            "1X (tuan rumah atau seri)" -> return h >= a
            "12 (tidak seri)" -> return h != a
            "X2 (seri atau tandang)" -> return h <= a
            "Kedua tim cetak gol (BTTS) - Ya" -> return r.bothScored
            "Kedua tim cetak gol (BTTS) - Tidak" -> return !r.bothScored
            "Minimal satu tim cetak 2+ gol - Ya" -> return h >= 2 || a >= 2
            "Minimal satu tim cetak 2+ gol - Tidak" -> return h < 2 && a < 2
            "Tuan rumah corner terbanyak" -> return r.homeCorners?.let { hc ->
                r.awayCorners?.let { hc > it }
            }
            "Corner sama banyak" -> return r.homeCorners?.let { hc ->
                r.awayCorners?.let { hc == it }
            }
            "Tandang corner terbanyak" -> return r.homeCorners?.let { hc ->
                r.awayCorners?.let { hc < it }
            }
        }

        // --- combinations, before the plain totals they contain
        if ("&" in name) return combination(name, r)

        // --- multigoal bands
        MULTI.find(name)?.let { m ->
            val lo = m.groupValues[1].toInt()
            val hi = m.groupValues[2].toInt()
            return total in lo..hi
        }

        // --- corner totals and per-team corner lines
        CORNER_TOTAL.find(name)?.let { m ->
            val line = m.groupValues[2].toDouble()
            val count = r.corners ?: return null
            return if (m.groupValues[1] == "Over") count > line else count < line
        }
        CORNER_TEAM.find(name)?.let { m ->
            val count = if (m.groupValues[1] == "tuan rumah") r.homeCorners else r.awayCorners
            return count?.let { it > m.groupValues[2].toDouble() }
        }
        CORNER_1H.find(name)?.let {
            // First-half corners are never on a full-time result screen, and
            // guessing them from the total would invent a number.
            return null
        }

        // --- first half totals
        FIRST_HALF.find(name)?.let { m ->
            val ht = r.htGoals ?: return null
            val line = m.groupValues[2].toDouble()
            return if (m.groupValues[1] == "Over") ht > line else ht < line
        }

        // --- team totals
        TEAM_TOTAL.find(name)?.let { m ->
            val scored = if (m.groupValues[1] == "Tuan rumah") h else a
            return scored > m.groupValues[2].toDouble()
        }

        // --- plain match totals
        TOTAL.find(name)?.let { m ->
            val line = m.groupValues[2].toDouble()
            return if (m.groupValues[1] == "Over") total > line else total < line
        }

        // --- handicaps
        HANDICAP.find(name)?.let { m ->
            val line = m.groupValues[2].toDouble()
            // The line is added, not subtracted: "Tuan rumah -1" means the home
            // side starts a goal down, so a one-goal win lands exactly on zero and
            // the stake comes back. Subtracting turned that push into a two-goal
            // win — a market recorded as won that was never won at all.
            val adjusted = if (m.groupValues[1] == "Tuan rumah") r.margin + line
            else -r.margin + line
            return when {
                // A whole or half-whole line that lands exactly on zero is a push,
                // or a half-win on the quarter lines. Neither is a win or a loss,
                // and recording it as either would put a false result into the
                // record the calibration depends on.
                kotlin.math.abs(adjusted) < 1e-9 -> null
                isQuarter(line) && kotlin.math.abs(adjusted) <= 0.25 + 1e-9 -> null
                else -> adjusted > 0
            }
        }

        return null
    }

    private fun isQuarter(line: Double): Boolean {
        val frac = kotlin.math.abs(line % 1.0)
        return kotlin.math.abs(frac - 0.25) < 1e-9 || kotlin.math.abs(frac - 0.75) < 1e-9
    }

    /** "1X & Over 2.5", "Tuan rumah menang & BTTS Ya" — both halves must land. */
    private fun combination(name: String, r: MatchResult): Boolean? {
        val parts = name.split(" & ")
        if (parts.size != 2) return null
        val left = side(parts[0], r) ?: return null
        val right = when {
            parts[1] == "BTTS Ya" -> r.bothScored
            else -> won(parts[1], r) ?: return null
        }
        return left && right
    }

    private fun side(text: String, r: MatchResult): Boolean? = when (text) {
        "1X" -> r.homeGoals >= r.awayGoals
        "X2" -> r.homeGoals <= r.awayGoals
        "12" -> r.homeGoals != r.awayGoals
        else -> won(text, r)
    }

    private val TOTAL = Regex("""^(Over|Under) ([\d.]+)$""")
    private val FIRST_HALF = Regex("""^Babak 1 (Over|Under) ([\d.]+)$""")
    private val TEAM_TOTAL = Regex("""^(Tuan rumah|Tandang) Over ([\d.]+)$""")
    private val MULTI = Regex("""^Total gol (\d+)-(\d+)$""")
    private val HANDICAP = Regex("""^(Tuan rumah|Tandang) ([-+][\d.]+)$""")
    private val CORNER_TOTAL = Regex("""^Total corner (Over|Under) ([\d.]+)$""")
    private val CORNER_TEAM = Regex("""^Corner (tuan rumah|tandang) Over ([\d.]+)$""")
    private val CORNER_1H = Regex("""^Corner babak 1 (Over|Under) ([\d.]+)$""")

    /**
     * Settles a whole analysis.
     *
     * Verdicts the user recorded by hand are kept. They watched the match; the
     * screenshot reader did not, and where the two disagree the person wins.
     */
    fun apply(match: MatchPrediction, r: MatchResult): MatchPrediction {
        val settled = HashMap(match.marketOutcomes)
        match.markets.forEach { option ->
            val key = match.keyOf(option)
            if (settled[key] != null && settled[key] != Outcome.PENDING) return@forEach
            outcome(option, r)?.let { settled[key] = it }
        }
        return match.copy(marketOutcomes = settled, result = r.summary, resultScore = r.score)
    }

    /** How many markets this result could and could not decide. */
    fun coverage(match: MatchPrediction, r: MatchResult): Pair<Int, Int> {
        val decided = match.markets.count { outcome(it, r) != null }
        return decided to (match.markets.size - decided)
    }
}
