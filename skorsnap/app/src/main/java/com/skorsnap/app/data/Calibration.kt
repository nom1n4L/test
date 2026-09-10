package com.skorsnap.app.data

import kotlin.math.roundToInt

/**
 * Checks whether the app's percentages mean what they say, and corrects them when
 * they do not.
 *
 * A market labelled 80% is supposed to lose one time in five. That is not a fault,
 * and no amount of work will change it — the label is a frequency, not a promise.
 * But it is only honest if 80% markets actually land 80% of the time, and until now
 * nothing in this app ever checked. A run of "paling aman" picks losing is either
 * ordinary variance or systematic overconfidence, and those two look identical
 * until someone counts.
 *
 * So the settled record is bucketed by the probability that was published, and each
 * bucket compares what was promised against what happened. A bucket that promised
 * 84% and delivered 61% is not bad luck at any decent sample size; it is a number
 * that needs to come down, and [adjust] brings it down.
 *
 * Two things keep this from doing harm:
 *  - It corrects the *bias of a band*, not the market itself. One market losing
 *    twice says nothing; forty markets in the 80s landing 60% of the time says the
 *    app is overconfident in the 80s, which is a fact about the app.
 *  - It fades in with evidence. At five results nothing moves. The correction only
 *    reaches full strength once a band has enough history to outvote the noise.
 */
object Calibration {

    /** Bands are ten points wide: narrower than this and every band is noise. */
    private val EDGES = listOf(0.0, 0.5, 0.6, 0.7, 0.8, 0.9, 1.01)

    /**
     * How much evidence a band needs before its correction counts for half.
     *
     * At n = 25 the measured bias is applied at half strength, at 75 three quarters.
     * Chosen so a bad week cannot rewrite the app's numbers, and a bad season can.
     */
    private const val HALF_WEIGHT_AT = 25.0

    /**
     * The most a probability may be moved, in points.
     *
     * A cap because the record can be thin and lopsided in ways the weighting does
     * not fully absorb — a band holding nothing but one user's favourite market on
     * one league. Beyond twelve points the app would be inventing a different model
     * rather than correcting this one.
     */
    private const val MAX_SHIFT = 0.12

    data class Band(
        val low: Double,
        val high: Double,
        val marks: List<Mark>,
    ) {
        val total: Int get() = marks.size
        val won: Int get() = marks.count { it.won }

        /** What the app said, averaged across the band. */
        val promised: Double get() = if (total == 0) 0.0 else marks.sumOf { it.promised } / total

        /**
         * What happened, with Laplace smoothing.
         *
         * (k+2)/(n+4) rather than k/n: at four results the raw rate can only be
         * 0%, 25%, 50%, 75% or 100%, and every one of those is a wild claim.
         */
        val actual: Double get() = (won + 2.0) / (total + 4.0)

        /** Negative means the app was overconfident in this band. */
        val bias: Double get() = actual - promised

        val weight: Double get() = total / (total + HALF_WEIGHT_AT)

        /** The correction this band contributes, already weighted and capped. */
        val shift: Double get() = (bias * weight).coerceIn(-MAX_SHIFT, MAX_SHIFT)

        val label: String get() = "${(low * 100).roundToInt()}–${(high * 100).roundToInt()}%"

        /** Enough to say something, still not proof. */
        val worthReporting: Boolean get() = total >= 8
    }

    /** The record split by the probability that was published. */
    fun bands(marks: List<Mark>): List<Band> =
        EDGES.zipWithNext().map { (low, high) ->
            Band(low, high, marks.filter { it.promised >= low && it.promised < high })
        }.filter { it.total > 0 }

    /** The band a probability falls in, or null when the record has nothing there. */
    private fun bandFor(prob: Double, marks: List<Mark>): Band? =
        bands(marks).firstOrNull { prob >= it.low && prob < it.high }

    /**
     * The corrected probability.
     *
     * Unchanged when the band has too little history, which is the common case early
     * on and is the right answer then: an uncorrected number is honest about being
     * uncorrected, where a number nudged by three results is not.
     */
    fun adjust(prob: Double, marks: List<Mark>): Double {
        val band = bandFor(prob, marks) ?: return prob
        if (band.total < MIN_FOR_CORRECTION) return prob
        return (prob + band.shift).coerceIn(0.02, 0.97)
    }

    /** Below this a band is a story about a few matches. */
    const val MIN_FOR_CORRECTION = 12

    /** True when anything at all would move, so the screen can stay quiet if not. */
    fun active(marks: List<Mark>): Boolean =
        bands(marks).any { it.total >= MIN_FOR_CORRECTION && it.shift.let { s -> s != 0.0 } }

    /**
     * Applies the correction across a whole analysis.
     *
     * The published number is kept on the option so the screen can show the move.
     * A probability that changed for reasons the user cannot see is worse than no
     * probability at all — that rule has cost this project two bugs already.
     */
    fun applyTo(match: MatchPrediction, marks: List<Mark>): MatchPrediction {
        if (!active(marks)) return match
        val moved = match.markets.map { option ->
            val corrected = adjust(option.prob, marks)
            if (kotlin.math.abs(corrected - option.prob) < 1e-9) option
            else option.copy(prob = corrected, rawProb = option.rawProb ?: option.prob)
        }
        return match.copy(
            markets = moved.sortedByDescending { it.prob },
            pickProb = moved.firstOrNull { it.name == match.pick }?.prob ?: match.pickProb,
            calibrated = true,
        )
    }

    /**
     * What the record says, in plain terms, for the screen to print.
     *
     * Written to answer the question the user actually asked — why did something
     * labelled safe lose — rather than to report a statistic.
     */
    fun verdict(marks: List<Mark>): String {
        val bands = bands(marks).filter { it.worthReporting }
        if (marks.size < 8) {
            return "Baru ${marks.size} market yang ditandai hasilnya. Terlalu sedikit " +
                "untuk tahu apakah angka persennya jujur — tandai tembus/meleset di " +
                "tiap laga dan halaman ini akan mulai bisa menjawab."
        }
        if (bands.isEmpty()) {
            return "Hasilnya belum menumpuk di satu rentang peluang mana pun. " +
                "Perlu lebih banyak market yang ditandai di rentang yang sama."
        }
        val worst = bands.minByOrNull { it.bias } ?: return ""
        return when {
            worst.bias < -0.10 && worst.total >= MIN_FOR_CORRECTION ->
                "Di rentang ${worst.label} aplikasi menjanjikan " +
                    "${(worst.promised * 100).roundToInt()}% tapi yang tembus " +
                    "${(worst.actual * 100).roundToInt()}% dari ${worst.total} taruhan. " +
                    "Itu terlalu percaya diri, bukan sial. Angka di rentang itu sekarang " +
                    "diturunkan ${(-worst.shift * 100).roundToInt()} poin otomatis."
            worst.bias < -0.10 ->
                "Di rentang ${worst.label} baru ${worst.total} taruhan, dan hasilnya di " +
                    "bawah janji. Belum cukup untuk mengoreksi — perlu " +
                    "${MIN_FOR_CORRECTION - worst.total} lagi di rentang itu."
            else ->
                "Sejauh ini angka persennya kurang lebih jujur: yang dijanjikan dan yang " +
                    "tembus tidak beda jauh di tiap rentang. Yang meleset di label aman " +
                    "itu memang jatah melesetnya."
        }
    }

    /**
     * How many of ten bets like this are expected to lose, spelled out.
     *
     * "82%" and "sekitar 2 dari 10 akan meleset" are the same fact, and people act
     * on them differently. The second is the one that stops a losing safe bet from
     * feeling like a broken app.
     */
    fun outOfTen(prob: Double): String {
        val misses = ((1 - prob) * 10).roundToInt()
        return when {
            misses <= 0 -> "hampir selalu tembus"
            misses == 1 -> "sekitar 1 dari 10 meleset"
            else -> "sekitar $misses dari 10 meleset"
        }
    }
}
