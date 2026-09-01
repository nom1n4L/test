package com.skorlogi.app

import com.skorlogi.app.data.Fixture
import com.skorlogi.app.engine.Confidence
import com.skorlogi.app.engine.Parlay
import com.skorlogi.app.engine.Pick
import com.skorlogi.app.engine.PickKind
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pins the parlay arithmetic, including the claim the screen leads with: that the
 * expected return of an n-leg parlay is 1 / margin^n regardless of the legs. If
 * that stopped being true the screen would be making a false argument.
 */
class ParlayTest {

    private fun pick(id: Int, prob: Double) = Pick(
        fixture = Fixture("L", 20000L + id, "12:00", "Home$id", "Away$id"),
        market = "Total Gol",
        selection = "Over 1.5",
        kind = PickKind.OVER_15,
        prob = prob,
        reliability = "",
        confidence = Confidence.HIGH,
    )

    @Test
    fun probabilitiesMultiply() {
        val legs = listOf(pick(1, 0.80), pick(2, 0.80), pick(3, 0.80), pick(4, 0.80))
        val option = Parlay.combine(legs)
        assert(abs(option.combinedProb - 0.8.pow(4)) < 1e-9) {
            "gabungan salah: ${option.combinedProb}"
        }
        // The number the screen quotes as the intuition trap.
        assert(option.percent == 41) { "empat leg 80% harusnya 41%, dapat ${option.percent}%" }
        println("4 leg @80%% = %.1f%% (1 dari %d)".format(option.combinedProb * 100, option.oneInN))
    }

    @Test
    fun expectedReturnDependsOnlyOnLegCount() {
        // Wildly different legs, same number of them.
        val safe = listOf(pick(1, 0.90), pick(2, 0.88), pick(3, 0.91))
        val risky = listOf(pick(4, 0.55), pick(5, 0.60), pick(6, 0.52))
        val a = Parlay.combine(safe)
        val b = Parlay.combine(risky)

        assert(abs(a.expectedReturn - b.expectedReturn) < 1e-12) {
            "harapan berbeda antara pilihan aman dan berisiko: ${a.expectedReturn} vs ${b.expectedReturn}"
        }
        val expected = 1.0 / Parlay.MARGIN.pow(3)
        assert(abs(a.expectedReturn - expected) < 1e-12) { "rumus harapan meleset" }

        println("3 leg aman  : tembus %.1f%%, harapan %.1f%%".format(a.combinedProb * 100, a.expectedReturn * 100))
        println("3 leg berisiko: tembus %.1f%%, harapan %.1f%%".format(b.combinedProb * 100, b.expectedReturn * 100))
        println("=> peluangnya jauh berbeda, harapannya identik.")
    }

    @Test
    fun lossGrowsWithEveryLeg() {
        val table = Parlay.marginTable(6)
        println()
        println("leg  rugi rata-rata")
        table.forEach { (n, loss) -> println("%3d  %d%%".format(n, loss)) }
        for (i in 1 until table.size) {
            assert(table[i].second > table[i - 1].second) {
                "menambah leg harusnya selalu memperburuk: ${table[i]} vs ${table[i - 1]}"
            }
        }
        assert(table.first().second in 4..7) { "margin satu leg di luar dugaan: ${table.first()}" }
    }

    @Test
    fun onlyOneLegPerMatch() {
        val same = Fixture("L", 20000L, "12:00", "A", "B")
        val legs = listOf(
            Pick(same, "Hasil Akhir", "A menang", PickKind.HOME, 0.7, "", Confidence.HIGH),
            Pick(same, "Total Gol", "Over 1.5", PickKind.OVER_15, 0.8, "", Confidence.HIGH),
            pick(9, 0.75),
        )
        val option = Parlay.combine(legs)
        assert(option.size == 2) { "dua taruhan dari satu laga tidak digabung jadi satu: ${option.size}" }
        println()
        println("Dua leg dari pertandingan yang sama dilebur jadi satu — hasilnya ${option.size} leg.")
    }

    @Test
    fun suggestionsGetWorseAsTheyGetLonger() {
        val pool = (1..6).map { pick(it, 0.85) }
        val options = Parlay.suggestions(pool)
        assert(options.isNotEmpty())
        for (i in 1 until options.size) {
            assert(options[i].combinedProb < options[i - 1].combinedProb) {
                "parlay lebih panjang harusnya lebih kecil peluangnya"
            }
            assert(options[i].expectedReturn < options[i - 1].expectedReturn) {
                "parlay lebih panjang harusnya lebih buruk harapannya"
            }
        }
        println()
        options.forEach {
            println("%d leg: tembus %2d%%, harapan %.0f%%".format(it.size, it.percent, it.expectedReturn * 100))
        }
    }
}
