package com.skorlogi.app

import com.skorlogi.app.data.Csv
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.FootballData
import com.skorlogi.app.data.Match
import com.skorlogi.app.engine.LeagueModel
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * Asks whether folding the market price into the prediction makes it better.
 *
 * The backtest already says the bookmaker out-predicts this model. If that is
 * true, then wherever a price exists the honest best estimate is not the model's
 * number but some combination of the two, and refusing to look at the price is
 * leaving accuracy on the table out of pride.
 *
 * This sweeps the blend weight and reports out-of-sample log loss. w=0 is the
 * model alone, w=1 is the market alone with its margin divided out.
 */
class MarketBlendTest {

    private val dataDir = listOf(File("backtest-data"), File("../backtest-data"))
        .firstOrNull { it.isDirectory } ?: File("backtest-data")

    @Test
    fun doesTheMarketMakeUsBetter() {
        Assume.assumeTrue(dataDir.isDirectory)
        val files = dataDir.listFiles { f: File -> f.name.endsWith(".csv") }!!.sortedBy { it.name }

        val byLeague = HashMap<String, MutableList<Match>>()
        val odds = HashMap<String, DoubleArray>()
        for (f in files) {
            val code = f.name.substringBefore('_')
            val body = f.readText()
            byLeague.getOrPut(code) { ArrayList() }.addAll(FootballData.parseMain(code, body))
            for (row in Csv.parse(body)) {
                val date = Dates.parseFeedDate(row["Date"].orEmpty()) ?: continue
                val h = row["B365H"]?.toDoubleOrNull() ?: continue
                val d = row["B365D"]?.toDoubleOrNull() ?: continue
                val a = row["B365A"]?.toDoubleOrNull() ?: continue
                if (minOf(h, d, a) <= 1.0) continue
                odds["$date|${row["HomeTeam"]}|${row["AwayTeam"]}"] = doubleArrayOf(h, d, a)
            }
        }

        val weights = listOf(0.0, 0.2, 0.4, 0.5, 0.6, 0.8, 1.0)
        val ll = DoubleArray(weights.size)
        val hits = IntArray(weights.size)
        var n = 0

        for ((code, raw) in byLeague.toSortedMap()) {
            val all = raw.distinctBy { "${it.dateEpochDay}|${it.home}|${it.away}" }
                .sortedBy { it.dateEpochDay }
            if (all.size < 400) continue
            val evalStart = all[(all.size * 0.62).toInt()].dateEpochDay
            val evalDays = all.filter { it.dateEpochDay >= evalStart }
                .map { it.dateEpochDay }.distinct().sorted()

            var model: LeagueModel? = null
            var lastFit = 0L
            for (day in evalDays) {
                if (model == null || day - lastFit >= 7) {
                    model = LeagueModel.build(code, all.filter { it.dateEpochDay < day }, day)
                    lastFit = day
                }
                val m = model ?: continue
                for (match in all.filter { it.dateEpochDay == day }) {
                    val price = odds["$day|${match.home}|${match.away}"] ?: continue
                    val p = m.predict(Fixture(code, day, "", match.home, match.away)) ?: continue

                    val mine = doubleArrayOf(p.pHome, p.pDraw, p.pAway)
                    // Divide the margin out so the two are on the same footing.
                    val inv = doubleArrayOf(1 / price[0], 1 / price[1], 1 / price[2])
                    val overround = inv.sum()
                    val market = DoubleArray(3) { inv[it] / overround }

                    val actual = when (match.result) {
                        'H' -> 0
                        'D' -> 1
                        else -> 2
                    }
                    n++
                    for ((wi, w) in weights.withIndex()) {
                        val mixed = DoubleArray(3) { (1 - w) * mine[it] + w * market[it] }
                        val sum = mixed.sum()
                        for (i in 0..2) mixed[i] /= sum
                        ll[wi] -= ln(mixed[actual].coerceAtLeast(1e-9))
                        if (mixed.indices.maxByOrNull { mixed[it] } == actual) hits[wi]++
                    }
                }
            }
        }

        println()
        println("=== Mencampur model dengan harga pasar, $n pertandingan ===")
        println("%-28s %10s %10s".format("bobot pasar", "log loss", "akurasi"))
        var best = 0
        for (i in weights.indices) {
            if (ll[i] < ll[best]) best = i
            val label = when (weights[i]) {
                0.0 -> "0%  (model saja)"
                1.0 -> "100% (pasar saja)"
                else -> "${(weights[i] * 100).toInt()}%"
            }
            println("%-28s %10.4f %9.1f%%".format(label, ll[i] / n, 100.0 * hits[i] / n))
        }
        println()
        println("Terbaik: bobot pasar %.0f%%, log loss %.4f".format(weights[best] * 100, ll[best] / n))
        println("Model sendirian: %.4f  |  perbaikan: %.4f"
            .format(ll[0] / n, ll[0] / n - ll[best] / n))
        println()

        assert(n > 1000) { "sampel terlalu kecil: $n" }
    }

    /**
     * The question that decides what this app is for: is there any market where the
     * model is competitive with the price? 1X2 is the sharpest line a bookmaker
     * publishes; over/under gets less attention. If an edge exists anywhere it is
     * likelier to be in the second.
     */
    @Test
    fun whereIsTheModelCompetitive() {
        Assume.assumeTrue(dataDir.isDirectory)
        val files = dataDir.listFiles { f: File -> f.name.endsWith(".csv") }!!.sortedBy { it.name }

        val byLeague = HashMap<String, MutableList<Match>>()
        val rows = HashMap<String, Map<String, String>>()
        for (f in files) {
            val code = f.name.substringBefore('_')
            val body = f.readText()
            byLeague.getOrPut(code) { ArrayList() }.addAll(FootballData.parseMain(code, body))
            for (row in Csv.parse(body)) {
                val date = Dates.parseFeedDate(row["Date"].orEmpty()) ?: continue
                rows["$date|${row["HomeTeam"]}|${row["AwayTeam"]}"] = row
            }
        }

        fun d(row: Map<String, String>, k: String) = row[k]?.toDoubleOrNull()

        var n1x2 = 0; var llModel1 = 0.0; var llOpen1 = 0.0; var llClose1 = 0.0
        var nOu = 0; var llModelOu = 0.0; var llMarketOu = 0.0
        var hitModelOu = 0; var hitMarketOu = 0

        for ((code, raw) in byLeague.toSortedMap()) {
            val all = raw.distinctBy { "${it.dateEpochDay}|${it.home}|${it.away}" }
                .sortedBy { it.dateEpochDay }
            if (all.size < 400) continue
            val evalStart = all[(all.size * 0.62).toInt()].dateEpochDay
            val evalDays = all.filter { it.dateEpochDay >= evalStart }
                .map { it.dateEpochDay }.distinct().sorted()

            var model: LeagueModel? = null
            var lastFit = 0L
            for (day in evalDays) {
                if (model == null || day - lastFit >= 7) {
                    model = LeagueModel.build(code, all.filter { it.dateEpochDay < day }, day)
                    lastFit = day
                }
                val m = model ?: continue
                for (match in all.filter { it.dateEpochDay == day }) {
                    val row = rows["$day|${match.home}|${match.away}"] ?: continue
                    val p = m.predict(Fixture(code, day, "", match.home, match.away)) ?: continue
                    val actual = when (match.result) { 'H' -> 0; 'D' -> 1; else -> 2 }

                    fun norm(a: Double, b: Double, c: Double): DoubleArray {
                        val inv = doubleArrayOf(1 / a, 1 / b, 1 / c)
                        val s = inv.sum()
                        return DoubleArray(3) { inv[it] / s }
                    }

                    val oh = d(row, "B365H"); val od = d(row, "B365D"); val oa = d(row, "B365A")
                    val ch = d(row, "B365CH"); val cd = d(row, "B365CD"); val ca = d(row, "B365CA")
                    if (oh != null && od != null && oa != null && ch != null && cd != null && ca != null &&
                        minOf(oh, od, oa, ch, cd, ca) > 1.0
                    ) {
                        n1x2++
                        llModel1 -= ln(doubleArrayOf(p.pHome, p.pDraw, p.pAway)[actual].coerceAtLeast(1e-9))
                        llOpen1 -= ln(norm(oh, od, oa)[actual].coerceAtLeast(1e-9))
                        llClose1 -= ln(norm(ch, cd, ca)[actual].coerceAtLeast(1e-9))
                    }

                    val over = d(row, "B365>2.5"); val under = d(row, "B365<2.5")
                    if (over != null && under != null && minOf(over, under) > 1.0) {
                        val hit = match.homeGoals + match.awayGoals > 2
                        val mine = p.groups.first { it.title == "Total Gol" }
                            .lines.first { it.label == "Over 2.5" }.prob
                        val implied = (1 / over) / (1 / over + 1 / under)
                        nOu++
                        llModelOu -= ln((if (hit) mine else 1 - mine).coerceAtLeast(1e-9))
                        llMarketOu -= ln((if (hit) implied else 1 - implied).coerceAtLeast(1e-9))
                        if ((mine >= 0.5) == hit) hitModelOu++
                        if ((implied >= 0.5) == hit) hitMarketOu++
                    }
                }
            }
        }

        println()
        println("=== 1X2, $n1x2 pertandingan (log loss, makin kecil makin baik) ===")
        println("  Model Skorlogi     %.4f".format(llModel1 / n1x2))
        println("  Odds pembukaan     %.4f".format(llOpen1 / n1x2))
        println("  Odds penutupan     %.4f".format(llClose1 / n1x2))
        println()
        println("=== Over/Under 2.5, $nOu pertandingan ===")
        println("  Model Skorlogi     %.4f   akurasi %.1f%%".format(llModelOu / nOu, 100.0 * hitModelOu / nOu))
        println("  Odds bandar        %.4f   akurasi %.1f%%".format(llMarketOu / nOu, 100.0 * hitMarketOu / nOu))
        println()
        val gap1 = llModel1 / n1x2 - llOpen1 / n1x2
        val gapOu = llModelOu / nOu - llMarketOu / nOu
        println("Selisih model vs pasar — 1X2: %+.4f | O/U 2.5: %+.4f".format(gap1, gapOu))
        println("(positif = model lebih buruk)")
        println()
    }
}
