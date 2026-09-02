package com.skorsnap.app.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Feeds the record back into the next analysis.
 *
 * Nothing here trains anything. The model is Google's and it is the same model on
 * every call — what changes is what it is told. So the app hands it its own track
 * record: where it promised 74% and delivered 50%, where it was honest, and how
 * many matches each judgement rests on. A model that is told it has been
 * overconfident on one goal line can allow for that; a model told nothing repeats
 * the same number forever, which is the "salahnya keulang terus" the user reported.
 *
 * This is a real mechanism with a real limit, and both belong on the screen. At
 * twenty results a per-market rate is barely distinguishable from noise, so the
 * brief reports sample sizes and asks for a nudge rather than an override. Nothing
 * is silently rewritten behind the user's back: the same text the model receives is
 * shown in the report.
 */
object Coach {

    /**
     * Below this a group's rate says nothing worth acting on. Four is already
     * generous — at n=4 a 50% run has a 95% interval spanning roughly 15% to 85% —
     * but waiting for statistical comfort would mean never feeding anything back,
     * so the brief carries the count and lets the model weigh it.
     */
    const val MIN_SAMPLE = 4

    /** Gaps smaller than this are not worth mentioning; they are sampling noise. */
    private const val NOTABLE_GAP = 0.08

    /** One settled bet: what was promised, what happened, under which heading. */
    private data class Shot(val group: String, val market: String, val promised: Double, val won: Boolean)

    private fun shots(history: List<MatchPrediction>): List<Shot> =
        history.flatMap { m ->
            buildList {
                if (m.settledFor(Lens.PICK)) {
                    add(Shot(m.groupFor(Lens.PICK), m.marketFor(Lens.PICK),
                        m.probFor(Lens.PICK), m.pickOutcome == Outcome.WON))
                }
                // Only when it is a different bet, or the same result would be
                // counted twice and every rate would look twice as certain as it is.
                if (m.divergent && m.settledFor(Lens.BACKED)) {
                    add(Shot(m.groupFor(Lens.BACKED), m.marketFor(Lens.BACKED),
                        m.probFor(Lens.BACKED), m.backedOutcome == Outcome.WON))
                }
            }
        }

    /**
     * The text handed to the model, or empty when there is nothing honest to say.
     */
    fun brief(history: List<MatchPrediction>): String {
        val all = shots(history)
        if (all.size < MIN_SAMPLE) return ""

        val overallPromised = all.sumOf { it.promised } / all.size
        val overallActual = all.count { it.won }.toDouble() / all.size

        val lines = ArrayList<String>()
        all.groupBy { it.group }
            .filterValues { it.size >= MIN_SAMPLE }
            .toList()
            .sortedByDescending { it.second.size }
            .forEach { (group, list) ->
                val promised = list.sumOf { it.promised } / list.size
                val actual = list.count { it.won }.toDouble() / list.size
                val gap = actual - promised
                val verdict = when {
                    abs(gap) < NOTABLE_GAP -> "sudah pas, pertahankan"
                    gap < 0 -> "TERLALU PERCAYA DIRI — turunkan peluangmu di kelompok ini " +
                        "sekitar ${(abs(gap) * 100).roundToInt()} poin"
                    else -> "terlalu hati-hati — boleh sedikit lebih berani"
                }
                lines.add(
                    "- ${group}: dijanjikan ${pct(promised)}, tembus ${pct(actual)} " +
                        "dari ${list.size} taruhan → $verdict"
                )
            }

        return buildString {
            append("CATATAN HASIL NYATA DARI APLIKASI INI (${all.size} taruhan sudah selesai).\n")
            append("Keseluruhan: dijanjikan ${pct(overallPromised)}, tembus ${pct(overallActual)}.\n")
            if (lines.isNotEmpty()) {
                append("Per kelompok market (hanya yang minimal $MIN_SAMPLE data):\n")
                append(lines.joinToString("\n"))
                append("\n")
            }
            append(
                if (all.size < 30) {
                    "Jumlah data ini masih sedikit, jadi pakai sebagai penyesuaian kecil, " +
                        "bukan aturan mati. Jangan mengubah pembacaanmu atas statistik di " +
                        "gambar hanya supaya cocok dengan catatan ini."
                } else {
                    "Sesuaikan peluangmu memakai catatan ini, terutama pada kelompok yang " +
                        "ditandai terlalu percaya diri."
                }
            )
        }
    }

    /**
     * The same brief, phrased for the user rather than the model.
     *
     * Shown in the report so the feedback loop is inspectable: if the app is telling
     * the model to shade a market down, the user should be able to see that and
     * disagree with it.
     */
    fun summary(history: List<MatchPrediction>): String {
        val text = brief(history)
        if (text.isBlank()) {
            val n = shots(history).size
            return "Belum cukup hasil untuk diumpankan balik ke model — baru $n taruhan " +
                "selesai, minimal $MIN_SAMPLE. Tandai hasil tiap laga dan catatan ini " +
                "akan mulai terisi."
        }
        return text
    }

    private fun pct(v: Double) = "${(v * 100).roundToInt()}%"
}
