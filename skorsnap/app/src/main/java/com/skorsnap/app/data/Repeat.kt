package com.skorsnap.app.data

/**
 * Notices when a fixture has been analysed before, and refuses to repeat a mistake
 * that is already on the record for that exact match.
 *
 * The failure this exists for: a match was analysed, Under 3.5 was recommended at
 * 85%, the match finished 3-2 and that market lost. It was analysed again hours
 * later and Under 3.5 came back at 81% — the same losing bet on the same finished
 * match. Nothing in the app noticed, because the record fed to the model is
 * averages by market group ("Total Gol is nine points overconfident"), which says
 * nothing about *this fixture* and cannot: the model never sees the team names until
 * after it has answered.
 *
 * So the check runs afterwards, where the names are known, and it is arithmetic
 * rather than persuasion. A market that is already settled for this fixture has a
 * fact attached to it, and a fact outranks a fresh estimate of the same thing.
 */
object Repeat {

    /**
     * Words that decorate a club's name without identifying it.
     *
     * "Unión Santa Fe" and "CA Unión de Santa Fe" are one club; matching on the raw
     * strings finds nothing. Matching after these are removed finds the three words
     * that actually name it.
     */
    private val NOISE = setOf(
        "fc", "ca", "ac", "sc", "cf", "cd", "afc", "cfc", "if", "sk", "fk", "bk",
        "de", "do", "da", "del", "la", "le", "el", "los", "las", "the", "und",
        "club", "atletico", "atlético", "deportivo", "deportes", "sporting",
        "real", "united", "city", "town", "ii", "b", "u21", "u23", "reserves",
    )

    /** Letters only, accents folded, decoration dropped. */
    internal fun tokens(name: String): Set<String> = name
        .lowercase()
        .replace(Regex("[áàâä]"), "a").replace(Regex("[éèêë]"), "e")
        .replace(Regex("[íìîï]"), "i").replace(Regex("[óòôöõ]"), "o")
        .replace(Regex("[úùûü]"), "u").replace("ñ", "n").replace("ç", "c")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(' ')
        .map { it.trim() }
        .filter { it.length >= 2 && it !in NOISE }
        .toSet()

    /**
     * Whether two names denote the same club.
     *
     * Deliberately strict. Attaching one fixture's history to a different fixture
     * would suppress a market for no reason and quietly hand the user a worse bet,
     * so a shared word is not enough: the smaller name has to be almost entirely
     * contained in the larger, and at least one shared word has to be long enough to
     * be distinctive — "Santa" alone matches half of South America.
     */
    internal fun sameClub(a: String, b: String): Boolean {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val shared = x intersect y
        if (shared.none { it.length >= 4 }) return false
        return shared.size >= minOf(x.size, y.size)
    }

    fun sameFixture(a: MatchPrediction, b: MatchPrediction): Boolean =
        sameClub(a.home, b.home) && sameClub(a.away, b.away)

    /** Earlier analyses of this same fixture, newest last. */
    fun priors(match: MatchPrediction, history: List<MatchPrediction>): List<MatchPrediction> =
        history.filter { it.id != match.id && sameFixture(match, it) }

    /**
     * Every market already settled for this fixture, from any earlier analysis.
     *
     * Keyed by market name rather than by group and name: the same bet analysed
     * twice can land in different groups if the second reading shuffled things, and
     * "Under 3.5 lost" is true of the bet however it was filed.
     */
    fun settledMarkets(priors: List<MatchPrediction>): Map<String, Outcome> {
        val out = HashMap<String, Outcome>()
        priors.forEach { prior ->
            prior.markets.forEach { option ->
                val verdict = prior.marketOutcomes[prior.keyOf(option)]
                if (verdict == Outcome.WON || verdict == Outcome.LOST) out[option.name] = verdict
            }
        }
        return out
    }

    /** The final score already recorded for this fixture, if any. */
    fun knownScore(priors: List<MatchPrediction>): String =
        priors.lastOrNull { it.resultScore.isNotBlank() }?.resultScore.orEmpty()

    data class Outcome2(val match: MatchPrediction, val note: String)

    /**
     * What two readings of the same unplayed fixture agree on.
     *
     * A second reading is not a contradiction of the first, and treating it as one
     * is a mistake the app was inviting: two analyses came back recommending Under
     * 2.5 and 1X, and the user asked which was correct. Neither and both — 1-0
     * settles them both, they are different bets from the same reading, and the
     * recommendation moved only because two near-tied markets swapped places.
     *
     * What a repeat reading actually provides is a second opinion on the same
     * evidence. Markets both readings put in the safe band survived being read
     * twice; a market only one reading liked did not. The agreement is ranked by
     * the LOWER of the two probabilities, because if two readings of the same match
     * disagree about a number, the pessimistic one is the one that has not yet been
     * contradicted.
     */
    fun consensus(
        match: MatchPrediction,
        priors: List<MatchPrediction>,
        floor: Double,
    ): List<Pair<String, Double>> {
        val prior = priors.lastOrNull() ?: return emptyList()
        val mine = match.markets.filter { it.inBand(floor) }.associate { it.name to it.prob }
        val theirs = prior.markets.associate { it.name to it.prob }
        return mine.mapNotNull { (name, p) ->
            val other = theirs[name] ?: return@mapNotNull null
            if (other < floor) null else name to minOf(p, other)
        }.sortedByDescending { it.second }
    }

    /**
     * Whether two recommendations cannot both win.
     *
     * Under 2.5 and 1X can both land on 1-0, so two readings picking those is not a
     * disagreement at all. Over 2.5 and Under 2.5 cannot, and neither can Tuan rumah
     * menang and X2 — that is the app contradicting itself about the same match, and
     * it is the one case here worth alarming about.
     */
    internal fun contradicts(a: String, b: String): Boolean {
        if (a == b) return false
        val ou = Regex("""^(.*)(Over|Under) ([\d.]+)$""")
        val x = ou.find(a)
        val y = ou.find(b)
        if (x != null && y != null) {
            // Same counter, same line, opposite side.
            return x.groupValues[1] == y.groupValues[1] &&
                x.groupValues[3] == y.groupValues[3] &&
                x.groupValues[2] != y.groupValues[2]
        }
        val opposites = setOf(
            setOf("Tuan rumah menang", "X2 (seri atau tandang)"),
            setOf("Tandang menang", "1X (tuan rumah atau seri)"),
            setOf("Seri", "12 (tidak seri)"),
            setOf("Kedua tim cetak gol (BTTS) - Ya", "Kedua tim cetak gol (BTTS) - Tidak"),
        )
        return setOf(a, b) in opposites
    }

    /** How the two readings compare, in words, for a fixture not yet played. */
    private fun secondOpinion(
        match: MatchPrediction,
        priors: List<MatchPrediction>,
        floor: Double,
    ): String {
        val prior = priors.lastOrNull() ?: return ""
        val agreed = consensus(match, priors, floor)
        return buildString {
            append("\n\nBacaan sebelumnya merekomendasikan \"${prior.pick}\" (${prior.pickPercent}%), ")
            append("yang sekarang \"${match.pick}\" (${match.pickPercent}%). ")
            if (prior.pick == match.pick) {
                append("Sama — dua bacaan terpisah sampai ke market yang sama, dan itu " +
                    "tanda paling kuat yang bisa diberikan aplikasi ini.")
            } else if (contradicts(prior.pick, match.pick)) {
                append("BERTENTANGAN — dua market ini tidak mungkin sama-sama tembus. " +
                    "Berarti bacaan aplikasinya atas laga ini memang belum stabil, " +
                    "bukan sekadar dua sudut pandang. Jangan pasang salah satunya " +
                    "sebelum kamu punya data lebih baik.")
            } else {
                append("Beda, tapi TIDAK bertentangan: dua market ini bisa sama-sama " +
                    "tembus di satu skor yang sama. Bukan berarti salah satu salah.")
            }
            if (agreed.isEmpty()) {
                append("\n\nTidak ada satu pun market yang masuk rentang aman di KEDUA " +
                    "bacaan. Itu sendiri sebuah jawaban: bacaannya belum stabil, jadi " +
                    "jangan dipasang besar.")
            } else {
                append("\n\n${agreed.size} market masuk rentang aman di kedua bacaan — ")
                append("ini yang paling layak dipercaya, karena bertahan dibaca dua kali:\n")
                agreed.take(5).forEach { (name, p) ->
                    append("• $name (paling rendah dari dua bacaan: ${(p * 100).toInt()}%)\n")
                }
            }
        }
    }

    /**
     * Applies what is already known about this fixture.
     *
     * Two things happen, and only the second changes a number:
     *  - the note says the fixture is a repeat, and gives the score if it is known,
     *    because analysing a finished match is nearly always a mistake and the app
     *    should say so rather than answer as if the match were still to come;
     *  - a recommendation that has already lost on this fixture is replaced. Not
     *    because the probability was wrong — it may have been perfectly fair — but
     *    because the outcome is no longer uncertain, and recommending it again is
     *    recommending a bet that cannot win.
     */
    fun apply(
        match: MatchPrediction,
        history: List<MatchPrediction>,
        floor: Double = MarketOption.SAFE_LOW,
    ): Outcome2 {
        val priors = priors(match, history)
        if (priors.isEmpty()) return Outcome2(match, "")

        val settled = settledMarkets(priors)
        val score = knownScore(priors)
        val lost = settled.filterValues { it == Outcome.LOST }.keys
        val won = settled.filterValues { it == Outcome.WON }.keys

        val head = buildString {
            append("Laga ini sudah pernah dianalisis ${priors.size}× sebelumnya")
            if (score.isNotBlank()) append(", dan hasilnya sudah tercatat: $score")
            append(".")
            if (settled.isNotEmpty()) {
                append(" ${settled.size} market di laga ini sudah ketahuan hasilnya — ")
                append("${won.size} tembus, ${lost.size} meleset.")
            }
        }

        if (match.pick !in lost) {
            // Two readings of a match that has not been played are a second opinion,
            // not a contradiction. Where the result IS known, no opinion is needed.
            val tail = if (score.isBlank()) secondOpinion(match, priors, floor) else
                " Kalau kamu cuma mau melihat ulang analisisnya, tidak apa-apa — tapi " +
                    "angka-angka di bawah ini menghitung peluang untuk sesuatu yang sudah terjadi."
            return Outcome2(match.copy(repeatNote = head + tail), head)
        }

        // The recommendation is a market that already lost here. Replace it with the
        // best in-band market that is not already settled as a loss, preferring one
        // that is known to have won — a fact beats an estimate.
        val replacement = match.markets
            .filter { it.inBand(floor) && it.name !in lost }
            .sortedWith(compareByDescending<MarketOption> { it.name in won }.thenByDescending { it.prob })
            .firstOrNull()

        val note = buildString {
            append(head)
            append("\n\n")
            append("\"${match.pick}\" SUDAH MELESET di laga ini")
            if (score.isBlank()) append("") else append(" (skor $score)")
            append(", jadi tidak direkomendasikan lagi — hasilnya bukan tebakan lagi, ")
            append("sudah pasti kalah.")
            if (replacement != null) {
                append(" Diganti ke \"${replacement.name}\" (${replacement.percent}%)")
                append(if (replacement.name in won) ", yang di laga ini justru tembus." else ".")
            } else {
                append(" Tidak ada market lain di rentang aman yang belum kalah di laga ini.")
            }
        }

        val fixed = if (replacement == null) match else match.copy(
            pick = replacement.name,
            pickProb = replacement.prob,
            pickCorrected = true,
        )
        return Outcome2(fixed.copy(repeatNote = note), note)
    }
}
