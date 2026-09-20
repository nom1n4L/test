package com.skorsnap.app.data

/**
 * The strictest way the app knows how to choose, for when losing matters more than
 * winning big.
 *
 * Raising the probability floor on its own does not make anything more accurate. It
 * asks the same model the same question and reads a bigger number off the same
 * answer — and a model that is overconfident at 70% is overconfident at 85% too, by
 * about the same margin. The floor changes which market gets named; it does not
 * change how often the naming is right.
 *
 * What does change that is corroboration. Every check below is a different witness,
 * and a market is only offered when they all agree:
 *
 *  - the model's own reading is high;
 *  - the bookmaker's own price, with its fee stripped out, is high too;
 *  - the market was one the model actually reasoned about, not one the app filled
 *    in from goal expectations;
 *  - the reading it came from was not thin;
 *  - the grid it sits in is internally consistent;
 *  - the app's own record does not show this family of markets running hot.
 *
 * Any one of those can be wrong. Being wrong in the same direction at the same time
 * is much rarer, and that is the whole of the idea.
 *
 * The other half is the part that makes it honest: when nothing passes, this
 * recommends nothing. A mode that always finds something is not a safe mode, it is
 * the same mode with a stricter vocabulary — and the matches where every witness
 * disagrees are exactly the matches worth skipping.
 */
object Lockdown {

    /** The model's own reading has to be at least this. */
    const val MODEL_FLOOR = 0.80

    /**
     * And the bookmaker has to be saying something similar.
     *
     * Deliberately lower than [MODEL_FLOOR]. A bookmaker's de-vigged price is the
     * sharpest number either side has, but it is shaded by what the public is
     * backing, so demanding the two agree exactly would reject almost everything.
     * What this rules out is the case worth ruling out: the app reading 85% on a
     * market the book prices at 55%. That gap is not an edge. It is the app being
     * wrong and not knowing it.
     */
    const val MARKET_FLOOR = 0.72

    /**
     * How far the book may sit below the model before the disagreement itself is
     * disqualifying, even when both clear their floors.
     */
    const val MAX_DISAGREEMENT = 0.12

    /** Below this, the record says this family of markets is not to be trusted. */
    const val WORST_TOLERATED_BIAS = -0.06

    /**
     * Whether a set of goal lines contradicts itself.
     *
     * Over 1.5 cannot be less likely than Over 2.5 — every match with three goals
     * also has two. When the grid says otherwise, the arithmetic behind it is wrong
     * somewhere, and the fact that one particular market in that grid reads 88% is
     * not evidence of anything. This catches misread screenshots and confused
     * readings that no single number would reveal.
     *
     * Applied per family, so goals, first-half goals, corners and team totals are
     * each checked against themselves rather than against each other.
     */
    fun coherent(markets: List<MarketOption>): Boolean = incoherences(markets).isEmpty()

    /** The contradictions found, in words, for the screen to show. */
    fun incoherences(markets: List<MarketOption>): List<String> {
        val out = ArrayList<String>()
        markets.mapNotNull { option ->
            LINE.find(option.name)?.let { m ->
                val prefix = m.groupValues[1].trim()
                val side = m.groupValues[2]
                val line = m.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null
                Quad(option.group, prefix, side, line to option)
            }
        }
            .groupBy { Triple(it.group, it.prefix, it.side) }
            .forEach { (key, rows) ->
                val sorted = rows.map { it.line }.sortedBy { it.first }
                sorted.zipWithNext { (lowLine, low), (highLine, high) ->
                    // A higher Over line can only be less likely; a higher Under
                    // line can only be more likely. A small wobble is rounding, not
                    // a contradiction, so only a real crossing counts.
                    val broken = if (key.third == "Over") high.prob > low.prob + 0.02
                    else high.prob < low.prob - 0.02
                    if (broken) {
                        out.add(
                            "${low.name} ${low.percent}% vs ${high.name} ${high.percent}% " +
                                "— tidak mungkin dua-duanya benar"
                        )
                    }
                }
            }
        return out
    }

    private data class Quad(
        val group: String,
        val prefix: String,
        val side: String,
        val line: Pair<Double, MarketOption>,
    )

    private val LINE = Regex("""^(.*?)\b(Over|Under)\s+([\d.]+)$""")

    /** Why one market did not make it through, or null when it did. */
    fun rejection(
        option: MarketOption,
        match: MatchPrediction,
        marks: List<Mark>,
    ): String? {
        if (option.prob < MODEL_FLOOR) {
            return "peluang model ${option.percent}%, di bawah $MODEL_PERCENT%"
        }
        if (option.prob > MarketOption.SAFE_HIGH) {
            return "peluang ${option.percent}% terlalu tinggi untuk dipercaya apa adanya"
        }
        if (option.derived) return "market ini dihitung aplikasi, bukan dibaca AI dari data"

        val book = option.marketProb
        if (book != null) {
            if (book < MARKET_FLOOR) {
                return "bandar cuma menghargai ${(book * 100).toInt()}% — dia tidak sependapat"
            }
            if (option.prob - book > MAX_DISAGREEMENT) {
                return "AI ${option.percent}% vs bandar ${(book * 100).toInt()}% — " +
                    "selisihnya terlalu jauh untuk disebut aman"
            }
        }

        val bias = Calibration.bands(marks)
            .firstOrNull { it.total >= Calibration.MIN_FOR_CORRECTION && it.holds(option.prob) }
        if (bias != null && bias.bias < WORST_TOLERATED_BIAS) {
            return "rekormu sendiri: di rentang ${bias.label} aplikasi ini kelebihan " +
                "percaya diri ${(-bias.bias * 100).toInt()} poin"
        }

        if (match.outcomeOf(option) == Outcome.LOST) return "sudah meleset di laga ini"
        return null
    }

    private const val MODEL_PERCENT = 80

    data class Verdict(
        /** Markets that passed every check, most corroborated first. */
        val passed: List<MarketOption>,
        /** Why the grid as a whole was refused, or blank when it was not. */
        val blocked: String,
        /** The near misses, with the reason each one failed. */
        val rejected: List<Pair<MarketOption, String>>,
    ) {
        val hasPick: Boolean get() = blocked.isBlank() && passed.isNotEmpty()
    }

    /**
     * The safest markets in this match, or an explanation of why there are none.
     *
     * Ranked by the more pessimistic of the two readings rather than the model's
     * own. Where the app and the bookmaker disagree about a number, the lower one
     * is the one that has not yet been contradicted by anybody.
     */
    fun judge(match: MatchPrediction, history: List<MatchPrediction> = emptyList()): Verdict {
        val marks = Report(history).allMarks()

        if (match.thin) {
            return Verdict(
                emptyList(),
                "Bacaan laga ini tipis — AI sendiri bilang keyakinannya rendah atau " +
                    "kebanyakan statistik yang dia butuhkan tidak ada di gambar. Mode " +
                    "paling aman tidak memilih apa pun dari bacaan seperti ini. " +
                    "Tambahkan screenshot statistik yang kurang, lalu analisis lagi.",
                emptyList(),
            )
        }

        val clashes = incoherences(match.markets)
        if (clashes.isNotEmpty()) {
            return Verdict(
                emptyList(),
                "Angka-angka di laga ini saling bertabrakan, jadi tidak ada satu pun " +
                    "yang bisa dipercaya sebagai yang paling aman:\n" +
                    clashes.take(3).joinToString("\n") { "• $it" } +
                    "\n\nBiasanya ini tanda ada angka yang salah terbaca dari gambar. " +
                    "Analisis ulang dengan screenshot yang lebih jelas.",
                emptyList(),
            )
        }

        val passed = ArrayList<MarketOption>()
        val rejected = ArrayList<Pair<MarketOption, String>>()
        match.markets.forEach { option ->
            val why = rejection(option, match, marks)
            if (why == null) passed.add(option) else rejected.add(option to why)
        }

        return Verdict(
            passed = passed.sortedByDescending { floorProb(it) },
            blocked = "",
            rejected = rejected
                .filter { it.first.prob >= 0.70 }
                .sortedByDescending { it.first.prob }
                .take(6),
        )
    }

    /** The lower of what the app thinks and what the bookmaker thinks. */
    fun floorProb(option: MarketOption): Double =
        option.marketProb?.let { minOf(option.prob, it) } ?: option.prob

    /**
     * Applies the verdict to the analysis.
     *
     * When nothing passes, the recommendation is cleared rather than replaced. An
     * empty answer is the correct one here and the screen is built to show it —
     * quietly falling back to the next-best market would be the ordinary mode
     * wearing this one's name, which is the specific thing this must never do.
     */
    fun apply(match: MatchPrediction, history: List<MatchPrediction> = emptyList()): MatchPrediction {
        val verdict = judge(match, history)
        val best = verdict.passed.firstOrNull()
        return match.copy(
            lockdown = true,
            lockdownNote = verdict.blocked.ifBlank {
                if (best == null) {
                    "Tidak ada satu pun market di laga ini yang lolos semua pemeriksaan " +
                        "mode paling aman. Itu jawabannya: lewati laga ini. " +
                        "Analisis biasa tetap ada di bawah kalau kamu mau melihatnya."
                } else ""
            },
            lockdownRejects = verdict.rejected.map { (option, why) ->
                "${option.name} (${option.percent}%) — $why"
            },
            pick = best?.name ?: match.pick,
            pickProb = best?.prob ?: match.pickProb,
            pickCorrected = best != null && best.name != match.pick,
        )
    }
}
