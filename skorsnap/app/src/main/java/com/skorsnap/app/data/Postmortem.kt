package com.skorsnap.app.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the app got wrong, and what the mistake was made of.
 *
 * Written after the result is known, from the analysis and the score alone — no
 * model call, no cost. That matters twice over: it works with no credit, and it
 * cannot flatter itself. A model asked why its own prediction failed will write a
 * fluent paragraph either way; arithmetic on what it published against what
 * happened cannot.
 *
 * The point is not the paragraph. The point is that the lesson goes into the brief
 * fed to every later analysis, and into the calibration record — so a habit that
 * keeps costing money gets named rather than repeated.
 */
object Postmortem {

    /**
     * A miss this far above the published probability is the interesting kind.
     *
     * A 55% market losing tells you nothing. An 88% market losing is either the
     * one time in eight, or a number that was never really 88%, and the second
     * only shows up when the same thing keeps happening.
     */
    private const val CONFIDENT = 0.80

    data class Verdict(
        val result: MatchResult,
        val hit: List<MarketOption>,
        val missed: List<MarketOption>,
        /** Markets the app was confident about and still lost. */
        val painful: List<MarketOption>,
        /** How the goal count compared with what was expected. */
        val goalGap: Double,
        val pickWon: Boolean?,
        val backedWon: Boolean?,
    ) {
        val decided: Int get() = hit.size + missed.size
        val rate: Double get() = if (decided == 0) 0.0 else hit.size.toDouble() / decided
        /** What the app said would happen, over the markets this result settled. */
        val promised: Double
            get() = if (decided == 0) 0.0 else (hit + missed).sumOf { it.prob } / decided
    }

    fun judge(match: MatchPrediction, r: MatchResult): Verdict {
        val decided = match.markets.mapNotNull { option ->
            Settle.outcome(option, r)?.let { option to it }
        }
        val hit = decided.filter { it.second == Outcome.WON }.map { it.first }
        val missed = decided.filter { it.second == Outcome.LOST }.map { it.first }
        return Verdict(
            result = r,
            hit = hit,
            missed = missed,
            painful = missed.filter { it.prob >= CONFIDENT }.sortedByDescending { it.prob },
            goalGap = r.goals - (match.xgHome + match.xgAway),
            pickWon = match.markets.firstOrNull { it.name == match.pick }
                ?.let { Settle.outcome(it, r) }?.let { it == Outcome.WON },
            backedWon = match.markets.firstOrNull { it.name == match.backedMarket }
                ?.let { Settle.outcome(it, r) }?.let { it == Outcome.WON },
        )
    }

    /**
     * The written lesson.
     *
     * Structured as: what happened, where the reading went wrong, and the one thing
     * that would have to change. Not an apology — a diagnosis with a direction, or
     * an explicit statement that this one was ordinary variance, which is the more
     * common and more useful answer.
     */
    fun write(match: MatchPrediction, r: MatchResult, history: List<MatchPrediction>): String {
        val v = judge(match, r)
        return buildString {
            append(r.summary)
            append(" Dari ${v.decided} market yang bisa dinilai, ${v.hit.size} tembus")
            append(" (${(v.rate * 100).roundToInt()}%), padahal rata-rata dijanjikan ")
            append("${(v.promised * 100).roundToInt()}%.\n\n")

            // Where the reading itself was wrong, which is upstream of every market.
            val expected = match.xgHome + match.xgAway
            when {
                abs(v.goalGap) < 0.8 ->
                    append("Perkiraan golnya kena: diperkirakan sekitar " +
                        "${twoDecimals(expected)} gol, jadinya ${r.goals}.\n")
                v.goalGap > 0 ->
                    append("Laganya jauh lebih terbuka daripada bacaan awal: " +
                        "diperkirakan sekitar ${twoDecimals(expected)} gol, jadinya " +
                        "${r.goals}. Semua market Under di laga ini rontok dari satu " +
                        "sebab yang sama, bukan dari ${v.missed.size} kesalahan terpisah.\n")
                else ->
                    append("Laganya jauh lebih tertutup daripada bacaan awal: " +
                        "diperkirakan sekitar ${twoDecimals(expected)} gol, jadinya " +
                        "${r.goals}. Yang rontok di sini kebanyakan market Over, dan " +
                        "sebabnya satu: perkiraan golnya kelewat tinggi.\n")
            }

            if (v.painful.isNotEmpty()) {
                append("\nYang paling mahal — dibilang hampir pasti, tetap meleset:\n")
                v.painful.take(4).forEach {
                    append("• ${it.name} (${it.percent}%)\n")
                }
                append(
                    "Satu laga tidak membuktikan angkanya salah — market " +
                        "${v.painful.first().percent}% memang meleset kadang-kadang. " +
                        "Yang membuktikan itu pengulangan, dan itu yang dilacak di Rapor.\n"
                )
            }

            // The part that is actually actionable: is this a pattern yet?
            val settled = history.filter { it.settled && it.id != match.id }
            val marks = Report(settled + match).allMarks()
            if (marks.size >= 8) {
                append("\n")
                append(Calibration.verdict(marks))
            }

            val bias = Coach.sideBias(marks)
            if (bias.isNotBlank()) {
                append("\n\n")
                append(bias)
            }
        }
    }

    /**
     * The one-line takeaway, for the row in the history list.
     */
    fun headline(match: MatchPrediction, r: MatchResult): String {
        val v = judge(match, r)
        return when {
            v.decided == 0 -> "Skor ${r.score} — belum ada market yang bisa dinilai."
            v.pickWon == true -> "Skor ${r.score} — rekomendasi tembus, ${v.hit.size}/${v.decided} market benar."
            v.pickWon == false -> "Skor ${r.score} — rekomendasi meleset, ${v.hit.size}/${v.decided} market benar."
            else -> "Skor ${r.score} — ${v.hit.size}/${v.decided} market benar."
        }
    }
}
