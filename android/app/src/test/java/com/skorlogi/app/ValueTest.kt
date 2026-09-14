package com.skorlogi.app

import com.skorlogi.app.data.OddsEvent
import com.skorlogi.app.data.Price
import com.skorlogi.app.data.Selection
import com.skorlogi.app.engine.Value
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the price arithmetic the odds screen argues from. Unlike the prediction
 * model, none of this is estimated — it is all derivable from the quoted numbers,
 * so it can be checked exactly.
 */
class ValueTest {

    private fun event(vararg selections: Selection) = OddsEvent(
        id = "e1",
        sportKey = "soccer_epl",
        commenceEpochDay = 20000L,
        commenceTime = "20:00",
        home = "Arsenal",
        away = "Chelsea",
        selections = selections.toList(),
    )

    private fun sel(name: String, vararg prices: Pair<String, Double>) = Selection(
        market = "h2h",
        name = name,
        point = null,
        prices = prices.map { (k, p) -> Price(k.replaceFirstChar { c -> c.uppercase() }, k, p) },
    )

    @Test
    fun sharpMarginIsRemovedToExactlyOne() {
        val e = event(
            sel("Arsenal", "pinnacle" to 2.10),
            sel("Draw", "pinnacle" to 3.40),
            sel("Chelsea", "pinnacle" to 3.50),
        )
        val fair = Value.sharpProbabilities(e, "h2h", null)!!
        val total = fair.values.sum()
        assert(abs(total - 1.0) < 1e-9) { "peluang wajar tidak berjumlah 1: $total" }
        println("Pinnacle 2.10 / 3.40 / 3.50 →")
        fair.forEach { (k, v) -> println("   %-10s %.1f%%".format(k, v * 100)) }
        val overround = (1 / 2.10 + 1 / 3.40 + 1 / 3.50)
        println("   margin mentahnya %.2f%%".format((overround - 1) * 100))
    }

    @Test
    fun aSoftBookPayingMoreThanFairIsFlagged() {
        // Pinnacle implies Arsenal about 46%; a fair price is roughly 2.16.
        val e = event(
            sel("Arsenal", "pinnacle" to 2.10, "onexbet" to 2.45),
            sel("Draw", "pinnacle" to 3.40, "onexbet" to 3.30),
            sel("Chelsea", "pinnacle" to 3.50, "onexbet" to 3.20),
        )
        val edges = Value.edges(e)
        assert(edges.isNotEmpty()) { "selisih nyata tidak terdeteksi" }
        val top = edges.first()
        assert(top.selection.name == "Arsenal") { "menandai pilihan yang salah: ${top.selection.name}" }
        assert(top.best.bookmakerKey == "onexbet") { "bandar yang ditandai salah" }
        println()
        println("Ditandai: ${top.selection.name} di ${top.best.bookmaker} @ ${top.best.price}")
        println("   Pinnacle bilang %d%%, harga wajarnya %.2f, jadi unggul %+d%%"
            .format(top.sharpPercent, top.fairOdds, top.edgePercent))
    }

    @Test
    fun aSoftBookPayingLessIsNotFlagged() {
        val e = event(
            sel("Arsenal", "pinnacle" to 2.10, "onexbet" to 1.95),
            sel("Draw", "pinnacle" to 3.40, "onexbet" to 3.20),
            sel("Chelsea", "pinnacle" to 3.50, "onexbet" to 3.30),
        )
        assert(Value.edges(e).isEmpty()) { "harga yang lebih buruk malah dianggap unggul" }
        println()
        println("Semua harga di bawah wajar → tidak ada yang ditandai. Benar.")
    }

    @Test
    fun shoppingEveryPriceCutsTheMargin() {
        val e = event(
            sel("Arsenal", "onexbet" to 2.10, "melbet" to 2.30, "marathonbet" to 2.20),
            sel("Draw", "onexbet" to 3.30, "melbet" to 3.25, "marathonbet" to 3.45),
            sel("Chelsea", "onexbet" to 3.40, "melbet" to 3.20, "marathonbet" to 3.55),
        )
        val single = Value.marginComparison(e, "onexbet").first()
        println()
        println("Bertahan di satu bandar : margin %d%%".format(single.singlePercent))
        println("Kejar harga terbaik     : margin %d%%".format(single.bestPercent))
        println("Hemat                   : %d poin persen, tanpa menebak apa pun".format(single.saved))
        assert(single.bestPrices < single.singleBook) { "kejar harga malah memperburuk margin" }
        assert(single.saved > 0)
    }

    @Test
    fun bestPriceIsTheHighestOnOffer() {
        val s = sel("Arsenal", "onexbet" to 2.10, "melbet" to 2.30, "pinnacle" to 2.18)
        assert(s.best!!.price == 2.30) { "harga terbaik salah: ${s.best}" }
        assert(s.best!!.bookmakerKey == "melbet")
    }

    @Test
    fun clubNamesMatchAcrossSources() {
        val same = listOf(
            "Arsenal FC" to "Arsenal",
            "Bologna FC 1909" to "Bologna",
            "Wolverhampton Wanderers" to "Wolverhampton",
            "Sport Lisboa e Benfica" to "Benfica",
            "AFC Bournemouth" to "Bournemouth",
        )
        for ((a, b) in same) {
            assert(Value.similar(a, b)) { "gagal mencocokkan: $a ↔ $b" }
        }
        val different = listOf(
            "Manchester United" to "Manchester City",
            "Real Madrid" to "Real Sociedad",
            "Sheffield United" to "Sheffield Wednesday",
        )
        for ((a, b) in different) {
            assert(!Value.similar(a, b)) { "salah menyamakan dua klub berbeda: $a ↔ $b" }
        }
        println()
        println("Pencocokan nama klub lolos: ${same.size} pasangan sama, ${different.size} pasangan beda.")
    }
}
