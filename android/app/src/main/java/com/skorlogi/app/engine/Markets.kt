package com.skorlogi.app.engine

import kotlin.math.abs
import kotlin.math.roundToInt

data class Line(val label: String, val prob: Double) {
    val fairOdds: Double get() = if (prob > 1e-6) 1.0 / prob else 0.0
    val percent: Int get() = (prob * 100).roundToInt()
}

data class MarketGroup(val title: String, val lines: List<Line>, val note: String? = null)

/**
 * Turns fitted grids into the market list. Everything here is a sum over the same
 * joint distributions, so the numbers agree with each other by construction: the
 * over/under lines, the correct scores and the 1X2 all come from one grid.
 */
object Markets {

    fun matchResult(ft: Grid, homeName: String, awayName: String): MarketGroup {
        val h = ft.pHome
        val d = ft.pDraw
        val a = ft.pAway
        return MarketGroup(
            "Hasil Akhir",
            listOf(
                Line("$homeName menang", h),
                Line("Seri", d),
                Line("$awayName menang", a),
            ),
        )
    }

    fun doubleChance(ft: Grid, homeName: String, awayName: String): MarketGroup {
        val h = ft.pHome
        val d = ft.pDraw
        val a = ft.pAway
        return MarketGroup(
            "Double Chance & Draw No Bet",
            listOf(
                Line("$homeName atau seri (1X)", h + d),
                Line("$homeName atau $awayName (12)", h + a),
                Line("Seri atau $awayName (X2)", d + a),
                Line("DNB — $homeName", h / (h + a).coerceAtLeast(1e-9)),
                Line("DNB — $awayName", a / (h + a).coerceAtLeast(1e-9)),
            ),
            note = "Draw no bet: taruhan kembali kalau seri.",
        )
    }

    fun correctScore(ft: Grid, count: Int = 10): MarketGroup = MarketGroup(
        "Skor Akhir Paling Mungkin",
        ft.topScores(count).map { (i, j, p) -> Line("$i - $j", p) },
    )

    fun totalGoals(ft: Grid): MarketGroup {
        val lines = ArrayList<Line>()
        for (l in listOf(0.5, 1.5, 2.5, 3.5, 4.5, 5.5)) {
            val over = ft.over(l)
            lines.add(Line("Over $l", over))
            lines.add(Line("Under $l", 1.0 - over))
        }
        val odd = ft.sumWhere { i, j -> (i + j) % 2 == 1 }
        lines.add(Line("Total gol ganjil", odd))
        lines.add(Line("Total gol genap", 1.0 - odd))
        return MarketGroup(
            "Total Gol",
            lines,
            note = "Perkiraan total gol: %.2f".format(ft.expectedTotal),
        )
    }

    fun btts(ft: Grid): MarketGroup {
        val yes = ft.sumWhere { i, j -> i > 0 && j > 0 }
        return MarketGroup(
            "Kedua Tim Cetak Gol (BTTS)",
            listOf(Line("Ya", yes), Line("Tidak", 1.0 - yes)),
        )
    }

    fun teamTotals(ft: Grid, homeName: String, awayName: String): MarketGroup {
        val lines = ArrayList<Line>()
        for (l in listOf(0.5, 1.5, 2.5, 3.5)) {
            lines.add(Line("$homeName over $l", ft.homeOver(l)))
        }
        for (l in listOf(0.5, 1.5, 2.5, 3.5)) {
            lines.add(Line("$awayName over $l", ft.awayOver(l)))
        }
        lines.add(Line("$homeName clean sheet", ft.sumWhere { _, j -> j == 0 }))
        lines.add(Line("$awayName clean sheet", ft.sumWhere { i, _ -> i == 0 }))
        return MarketGroup("Gol per Tim & Clean Sheet", lines)
    }

    /** Asian handicap. Half lines cannot push; whole lines can, and are shown as such. */
    fun handicap(ft: Grid, homeName: String, awayName: String): MarketGroup {
        val lines = ArrayList<Line>()
        for (hcp in listOf(-2.5, -2.0, -1.5, -1.0, -0.5, 0.5, 1.0, 1.5, 2.5)) {
            val win = ft.sumWhere { i, j -> (i - j) + hcp > 0 }
            val push = if (hcp == hcp.roundToInt().toDouble()) {
                ft.sumWhere { i, j -> abs((i - j) + hcp) < 1e-9 }
            } else {
                0.0
            }
            val sign = if (hcp > 0) "+$hcp" else "$hcp"
            val label = if (push > 0.001) {
                "$homeName $sign (seri uang ${(push * 100).roundToInt()}%)"
            } else {
                "$homeName $sign"
            }
            lines.add(Line(label, win))
        }
        lines.add(Line("$awayName menang dengan selisih 2+", ft.sumWhere { i, j -> j - i >= 2 }))
        lines.add(Line("$homeName menang dengan selisih 2+", ft.sumWhere { i, j -> i - j >= 2 }))
        return MarketGroup("Handicap Asia", lines)
    }

    fun winningMargin(ft: Grid, homeName: String, awayName: String): MarketGroup {
        val lines = ArrayList<Line>()
        for (m in 1..3) {
            lines.add(Line("$homeName menang $m gol", ft.sumWhere { i, j -> i - j == m }))
        }
        lines.add(Line("$homeName menang 4+ gol", ft.sumWhere { i, j -> i - j >= 4 }))
        lines.add(Line("Seri", ft.pDraw))
        for (m in 1..3) {
            lines.add(Line("$awayName menang $m gol", ft.sumWhere { i, j -> j - i == m }))
        }
        lines.add(Line("$awayName menang 4+ gol", ft.sumWhere { i, j -> j - i >= 4 }))
        return MarketGroup("Selisih Gol", lines)
    }

    fun halfTime(ht: Grid, homeName: String, awayName: String): MarketGroup {
        val lines = ArrayList<Line>()
        lines.add(Line("$homeName unggul di babak 1", ht.pHome))
        lines.add(Line("Seri di babak 1", ht.pDraw))
        lines.add(Line("$awayName unggul di babak 1", ht.pAway))
        for (l in listOf(0.5, 1.5, 2.5)) {
            lines.add(Line("Babak 1 over $l", ht.over(l)))
            lines.add(Line("Babak 1 under $l", ht.under(l)))
        }
        lines.add(Line("Babak 1 BTTS", ht.sumWhere { i, j -> i > 0 && j > 0 }))
        return MarketGroup(
            "Babak Pertama",
            lines,
            note = "Perkiraan gol babak 1: %.2f".format(ht.expectedTotal),
        )
    }

    fun secondHalf(sh: Grid, homeName: String, awayName: String): MarketGroup {
        val lines = ArrayList<Line>()
        lines.add(Line("$homeName menang babak 2", sh.pHome))
        lines.add(Line("Seri di babak 2", sh.pDraw))
        lines.add(Line("$awayName menang babak 2", sh.pAway))
        for (l in listOf(0.5, 1.5, 2.5)) {
            lines.add(Line("Babak 2 over $l", sh.over(l)))
            lines.add(Line("Babak 2 under $l", sh.under(l)))
        }
        return MarketGroup(
            "Babak Kedua",
            lines,
            note = "Perkiraan gol babak 2: %.2f".format(sh.expectedTotal),
        )
    }

    /**
     * Half-time / full-time. The two halves are modelled as independent, so the
     * joint runs over both grids: the first-half outcome fixes the interim score,
     * the second-half grid decides where it ends up.
     */
    fun halfTimeFullTime(ht: Grid, sh: Grid, homeName: String, awayName: String): MarketGroup {
        // p[htOutcome][ftOutcome], 0 = home, 1 = draw, 2 = away.
        val joint = Array(3) { DoubleArray(3) }
        for (i in 0 until ht.size) for (j in 0 until ht.size) {
            val pHt = ht.p[i][j]
            if (pHt < 1e-12) continue
            val htOut = if (i > j) 0 else if (i == j) 1 else 2
            for (k in 0 until sh.size) for (l in 0 until sh.size) {
                val pSh = sh.p[k][l]
                if (pSh < 1e-12) continue
                val fh = i + k
                val fa = j + l
                val ftOut = if (fh > fa) 0 else if (fh == fa) 1 else 2
                joint[htOut][ftOut] += pHt * pSh
            }
        }
        val names = listOf(homeName, "Seri", awayName)
        val lines = ArrayList<Line>(9)
        for (a in 0..2) for (b in 0..2) {
            lines.add(Line("${names[a]} / ${names[b]}", joint[a][b]))
        }
        return MarketGroup(
            "Babak 1 / Babak Penuh",
            lines.sortedByDescending { it.prob },
            note = "Dibaca: hasil saat turun minum / hasil akhir.",
        )
    }

    fun highestScoringHalf(ht: Grid, sh: Grid): MarketGroup {
        var first = 0.0
        var second = 0.0
        var equal = 0.0
        for (i in 0 until ht.size) for (j in 0 until ht.size) {
            val pHt = ht.p[i][j]
            if (pHt < 1e-12) continue
            val t1 = i + j
            for (k in 0 until sh.size) for (l in 0 until sh.size) {
                val pSh = sh.p[k][l]
                if (pSh < 1e-12) continue
                val t2 = k + l
                when {
                    t1 > t2 -> first += pHt * pSh
                    t1 < t2 -> second += pHt * pSh
                    else -> equal += pHt * pSh
                }
            }
        }
        return MarketGroup(
            "Babak dengan Gol Terbanyak",
            listOf(
                Line("Babak 1", first),
                Line("Babak 2", second),
                Line("Sama banyak", equal),
            ),
        )
    }

    fun corners(c: Grid, homeName: String, awayName: String): MarketGroup {
        val lines = ArrayList<Line>()
        for (l in listOf(7.5, 8.5, 9.5, 10.5, 11.5, 12.5)) {
            lines.add(Line("Total corner over $l", c.over(l)))
            lines.add(Line("Total corner under $l", c.under(l)))
        }
        lines.add(Line("$homeName corner terbanyak", c.pHome))
        lines.add(Line("Corner sama banyak", c.pDraw))
        lines.add(Line("$awayName corner terbanyak", c.pAway))
        for (l in listOf(3.5, 4.5, 5.5, 6.5)) {
            lines.add(Line("$homeName over $l corner", c.homeOver(l)))
        }
        for (l in listOf(3.5, 4.5, 5.5, 6.5)) {
            lines.add(Line("$awayName over $l corner", c.awayOver(l)))
        }
        lines.add(Line("$homeName unggul 3+ corner", c.sumWhere { i, j -> i - j >= 3 }))
        lines.add(Line("$awayName unggul 3+ corner", c.sumWhere { i, j -> j - i >= 3 }))
        return MarketGroup(
            "Sepak Pojok",
            lines,
            note = "Perkiraan total corner: %.1f".format(c.expectedTotal),
        )
    }

    fun cards(c: Grid, homeName: String, awayName: String): MarketGroup {
        val lines = ArrayList<Line>()
        for (l in listOf(2.5, 3.5, 4.5, 5.5, 6.5)) {
            lines.add(Line("Total poin kartu over $l", c.over(l)))
            lines.add(Line("Total poin kartu under $l", c.under(l)))
        }
        lines.add(Line("$homeName kartu lebih banyak", c.pHome))
        lines.add(Line("$awayName kartu lebih banyak", c.pAway))
        lines.add(Line("Ada kartu merah (perkiraan)", 1.0 - kotlin.math.exp(-c.expectedTotal * 0.035)))
        return MarketGroup(
            "Kartu",
            lines,
            note = "Poin kartu: kuning = 1, merah = 2. Perkiraan total: %.1f".format(c.expectedTotal),
        )
    }
}
