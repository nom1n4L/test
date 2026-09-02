package com.skorsnap.app

import com.skorsnap.app.data.Analyst
import com.skorsnap.app.data.MarketOption
import com.skorsnap.app.data.Markets
import com.skorsnap.app.data.Grid
import com.skorsnap.app.data.Images
import com.skorsnap.app.data.MatchPrediction
import com.skorsnap.app.data.Mode
import com.skorsnap.app.data.Outcome
import com.skorsnap.app.data.Parlay
import com.skorsnap.app.data.Report
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The two things in this app that must be exact.
 *
 * Reading the screenshots is the model's job and cannot be unit tested. Turning
 * its answer into numbers, and combining those numbers into a slip, is the app's
 * job — and a wrong parlay probability would be believed.
 */
class CoreTest {

    private fun match(prob: Double, id: String = java.util.UUID.randomUUID().toString()) =
        MatchPrediction(
            id = id, home = "A", away = "B", league = "L", readable = true, problem = "",
            statsSeen = emptyList(), statsMissing = emptyList(),
            probHome = 0.5, probDraw = 0.25, probAway = 0.25, xgHome = 1.5, xgAway = 1.0,
            markets = emptyList(), pick = "Over 1.5", pickProb = prob,
            confidence = "tinggi", confidenceWhy = "",
        )

    @Test
    fun probabilitiesMultiplyAcrossLegs() {
        val slip = Parlay.of(listOf(match(0.80), match(0.80), match(0.80), match(0.80)))
        assert(abs(slip.combined - 0.8.pow(4)) < 1e-9) { "gabungan salah: ${slip.combined}" }
        assert(slip.percent == 41) { "empat leg 80% harusnya 41%, dapat ${slip.percent}%" }
        println("4 leg @80%% → %d%% (1 dari %d)".format(slip.percent, slip.oneInN))
    }

    @Test
    fun sixLegsIsTheCaseTheUserAsksFor() {
        val slip = Parlay.of((1..6).map { match(0.75) })
        println()
        println("6 leg @75%%: tembus semua %d%% (1 dari %d)".format(slip.percent, slip.oneInN))
        println("   diperkirakan tembus %.1f dari 6".format(slip.expectedHits))
        println("   bayaran wajar %.2f, setelah margin %.2f".format(slip.fairOdds, slip.realisticOdds))
        println("   imbal hasil harapan %.0f%%".format(slip.expectedReturn * 100))
        assert(slip.percent in 17..19) { "6 leg 75% harusnya sekitar 18%, dapat ${slip.percent}%" }
    }

    @Test
    fun expectedReturnDependsOnlyOnLegCount() {
        val safe = Parlay.of(listOf(match(0.90), match(0.88), match(0.91)))
        val risky = Parlay.of(listOf(match(0.55), match(0.60), match(0.52)))
        assert(abs(safe.expectedReturn - risky.expectedReturn) < 1e-12) {
            "harapan berbeda padahal jumlah leg sama"
        }
        println()
        println("3 leg aman    : tembus %.1f%%, harapan %.1f%%"
            .format(safe.combined * 100, safe.expectedReturn * 100))
        println("3 leg berisiko: tembus %.1f%%, harapan %.1f%%"
            .format(risky.combined * 100, risky.expectedReturn * 100))
        println("=> peluang beda jauh, harapan identik.")
    }

    @Test
    fun duplicateLegsAreCollapsed() {
        val same = match(0.8, id = "x")
        val slip = Parlay.of(listOf(same, same, match(0.7)))
        assert(slip.size == 2) { "leg kembar tidak dilebur: ${slip.size}" }
    }

    @Test
    fun parsesACleanReply() {
        val json = """
        {"home":"Preston","away":"Bristol City","league":"Championship","readable":true,
         "problem":"","stats_seen":["form 5 laga","rata-rata gol"],"stats_missing":["head-to-head"],
         "prob_home":0.34,"prob_draw":0.28,"prob_away":0.38,"xg_home":1.3,"xg_away":1.5,
         "markets":[{"name":"Over 1.5","prob":0.82,"why":"kedua tim rata-rata 3 gol"},
                    {"name":"BTTS","prob":0.61,"why":"keduanya jarang clean sheet"}],
         "pick":"Over 1.5","pick_prob":0.82,"confidence":"sedang","confidence_why":"h2h tidak ada"}
        """.trimIndent()
        val m = Analyst("dummy").parse(json)
        assert(m.home == "Preston" && m.away == "Bristol City")
        assert(m.markets.size == 2)
        assert(m.markets.first().name == "Over 1.5") { "market tidak urut dari peluang tertinggi" }
        assert(m.pickPercent == 82)
        assert(m.statsMissing == listOf("head-to-head"))
        assert(abs(m.pickBreakEven - 1.0 / 0.82) < 1e-9)
        println()
        println(
            "Terbaca: ${m.title}, pilih ${m.pick} ${m.pickPercent}%, " +
                "impas di " + "%.2f".format(m.pickBreakEven)
        )
    }

    @Test
    fun survivesAStraySentenceBeforeTheJson() {
        val reply = "Berikut hasilnya:\n\n{\"home\":\"A\",\"away\":\"B\",\"pick\":\"Over 1.5\"," +
            "\"pick_prob\":0.7,\"markets\":[]}\n\nSemoga membantu."
        val m = Analyst("dummy").parse(reply)
        assert(m.pickPercent == 70) { "kalimat liar merusak parsing" }
        println("Kalimat tambahan di luar JSON tidak merusak hasil.")
    }

    @Test
    fun refusesGarbageInsteadOfInventing() {
        val thrown = try {
            Analyst("dummy").parse("Maaf, gambarnya tidak bisa saya baca.")
            false
        } catch (e: Analyst.AnalystException) {
            true
        }
        assert(thrown) { "balasan tanpa JSON malah diterima" }
        println("Balasan tanpa JSON ditolak dengan pesan, bukan diam-diam dianggap kosong.")
    }

    /**
     * The schema is what stops a malformed reply reaching the user as a blank
     * match, so a typo in it would quietly remove that protection.
     */
    @Test
    fun responseSchemaCoversEveryFieldTheParserNeeds() {
        val schema = Analyst.RESPONSE_SCHEMA
        val props = schema.getJSONObject("properties")
        val needed = listOf(
            "home", "away", "league", "readable", "problem", "stats_seen", "stats_missing",
            "prob_home", "prob_draw", "prob_away", "xg_home", "xg_away",
            "markets", "pick", "pick_prob", "confidence", "confidence_why",
        )
        for (field in needed) {
            assert(props.has(field)) { "skema tidak punya field '$field' yang dibaca parser" }
        }

        val required = schema.getJSONArray("required")
        val requiredNames = (0 until required.length()).map { required.getString(it) }
        // Without these the analysis is not usable, so the model must supply them.
        for (field in listOf("readable", "stats_seen", "stats_missing", "markets", "pick", "pick_prob")) {
            assert(field in requiredNames) { "'$field' harusnya wajib diisi" }
        }

        val market = props.getJSONObject("markets").getJSONObject("items")
        assert(market.getJSONObject("properties").has("prob")) { "market tanpa field peluang" }
        println()
        println("Skema mencakup ${props.length()} field, ${requiredNames.size} di antaranya wajib.")
    }

    /**
     * The models endpoint returns everything the key can call, most of which
     * cannot read a picture. A user shown forty rows will pick a wrong one.
     */
    @Test
    fun onlyOffersModelsThatCanReadAScreenshot() {
        val keep = listOf(
            "gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-flash",
            "gemini-flash-latest", "gemini-pro-latest",
        )
        val drop = listOf(
            "gemini-embedding-001", "gemini-2.5-flash-preview-tts",
            "gemini-2.5-flash-image", "gemini-robotics-er-2-preview",
            "gemini-2.5-flash-native-audio-preview", "gemini-3.5-transcribe",
            "gemini-live-2.5-flash", "text-bison-001",
        )
        for (m in keep) assert(Analyst.usable(m)) { "'$m' harusnya ditawarkan" }
        for (m in drop) assert(!Analyst.usable(m)) { "'$m' harusnya disembunyikan" }
        println()
        println("Penyaring model: ${keep.size} dipertahankan, ${drop.size} disembunyikan.")
    }

    @Test
    fun putsTheUsefulModelsFirst() {
        val listed = listOf(
            "gemini-2.0-flash-lite", "gemini-exp-1206", "gemini-2.5-pro", "gemini-3-flash",
        ).map { Analyst.Model(it, it, "") }
        val ranked = Analyst.rank(listed).map { it.id }
        assert(ranked.first().contains("flash") || ranked.first().contains("pro")) {
            "model pilihan utama tidak di atas: $ranked"
        }
        assert(ranked.last() == "gemini-exp-1206") { "model tak dikenal harusnya di bawah: $ranked" }
        println("Urutan model: $ranked")
    }

    /**
     * The retry only helps if capping thinking actually leaves room for the answer.
     * A future edit that raised the budget past the ceiling would make the fallback
     * silently pointless.
     */
    @Test
    fun cappedThinkingLeavesRoomForTheAnswer() {
        val room = Analyst.MAX_OUTPUT_TOKENS - Analyst.THINKING_BUDGET
        assert(room >= 8000) {
            "sisa jatah untuk jawaban cuma $room token — terlalu sempit untuk JSON-nya"
        }
        println()
        println("Jatah output ${Analyst.MAX_OUTPUT_TOKENS}, berpikir dibatasi " +
            "${Analyst.THINKING_BUDGET}, sisa $room untuk jawaban.")
    }

    private fun settled(prob: Double, won: Boolean) =
        match(prob).copy(outcome = if (won) Outcome.WON else Outcome.LOST)

    /**
     * The run that prompted this screen: eleven from twelve. It has to read as a
     * good run rather than as proof, because acting on it as proof is the
     * expensive mistake.
     */
    @Test
    fun elevenFromTwelveIsNotYetEvidence() {
        val r = Report(List(11) { settled(0.78, true) } + settled(0.78, false))
        assert(r.total == 12 && r.won == 11)
        assert(Math.round(r.actual * 100) == 92L) { "akurasi salah: ${r.actual}" }
        assert(Math.round(r.promised * 100) == 78L) { "janji salah: ${r.promised}" }
        assert(!r.meaningful) { "12 hasil seharusnya belum dianggap cukup" }
        assert(r.precision >= 15) { "ketelitian dilaporkan terlalu optimis: ±${r.precision}" }
        println()
        println("11 dari 12: nyata %d%%, dijanjikan %d%%, sejatinya antara %d%% dan %d%% (±%d poin)"
            .format(
                Math.round(r.actual * 100), Math.round(r.promised * 100),
                Math.round(r.low * 100), Math.round(r.high * 100), r.precision,
            ))
        println("Vonis: ${r.verdict}")
    }

    @Test
    fun precisionTightensAsResultsAccumulate() {
        val small = Report(List(12) { settled(0.78, it < 9) })
        val large = Report(List(120) { settled(0.78, it < 94) })
        assert(large.precision < small.precision) { "sampel besar harusnya lebih teliti" }
        assert(large.meaningful && !small.meaningful)
        println()
        println("12 hasil  → ±%d poin (%s)".format(small.precision, if (small.meaningful) "cukup" else "belum cukup"))
        println("120 hasil → ±%d poin (%s)".format(large.precision, if (large.meaningful) "cukup" else "belum cukup"))
    }

    /** The number the screen exists for: claimed against delivered. */
    @Test
    fun reportsTheGapBetweenPromisedAndDelivered() {
        val honest = Report(List(60) { settled(0.75, it < 45) })
        assert(kotlin.math.abs(honest.gap) < 0.02) { "selisih salah hitung: ${honest.gap}" }
        assert(honest.verdict.contains("bisa dipercaya")) { "vonis salah: ${honest.verdict}" }

        val overconfident = Report(List(60) { settled(0.85, it < 33) })
        assert(overconfident.gap < -0.2) { "kelewat pede tidak terdeteksi: ${overconfident.gap}" }
        assert(overconfident.verdict.contains("terlalu percaya diri")) {
            "vonis salah: ${overconfident.verdict}"
        }
        println()
        println("Janji 75%, tembus 75% → ${honest.verdict.take(70)}…")
        println("Janji 85%, tembus 55% → ${overconfident.verdict.take(70)}…")
    }

    @Test
    fun pendingMatchesStayOutOfTheRecord() {
        val mixed = listOf(settled(0.8, true), match(0.8), settled(0.8, false))
        val r = Report(mixed.filter { it.settled })
        assert(r.total == 2) { "laga yang belum ditandai ikut terhitung" }
    }

    /**
     * Slicing a long capture is silent when it goes wrong: a gap between bands
     * loses a row of numbers and nothing on screen would say so.
     */
    @Test
    fun bandsCoverALongCaptureWithoutGaps() {
        for (height in listOf(2600, 4000, 8000, 20000, 45000)) {
            val bands = Images.plan(height)
            assert(bands.first().first == 0) { "band pertama tidak mulai dari atas" }
            assert(bands.last().second == height) { "band terakhir tidak sampai bawah ($height)" }
            for (i in 1 until bands.size) {
                val previousBottom = bands[i - 1].second
                val currentTop = bands[i].first
                assert(currentTop < previousBottom) {
                    "ada celah antara band di $height: $previousBottom lalu $currentTop"
                }
            }
            assert(bands.size <= 12) { "terlalu banyak potongan untuk $height: ${bands.size}" }
            val tallest = bands.maxOf { it.second - it.first }
            println("  tinggi %5d → %2d potong, tertinggi %d px".format(height, bands.size, tallest))
        }
    }

    @Test
    fun ordinaryScreenshotsAreNotSliced() {
        for (height in listOf(800, 1600, 2400, 2600)) {
            assert(Images.plan(height).size == 1) { "screenshot biasa ($height) ikut dipotong" }
        }
        println()
        println("Screenshot biasa dibiarkan utuh; hanya long capture yang dipotong.")
    }

    /**
     * The prompt names the group for each market and the screen sorts by those
     * names. If the two ever drift, every market silently lands in "Lainnya" and
     * the grouping quietly stops working.
     */
    @Test
    fun everyGroupInThePromptIsOneTheScreenKnows() {
        val inPrompt = Regex("""\[([^\]]+)]""")
            .findAll(Analyst.MATCH_MARKETS + Analyst.CORNER_MARKETS)
            .map { it.groupValues[1] }
            .toSet()
        assert(inPrompt.isNotEmpty()) { "tidak ada grup yang terbaca dari prompt" }
        for (group in inPrompt) {
            assert(group in Markets.order) { "grup '$group' ada di prompt tapi tidak dikenali layar" }
        }
        println()
        println("Grup di prompt: ${inPrompt.sorted()}")
    }

    @Test
    fun thePromptCoversTheMarketsAsked() {
        val match = Analyst.MATCH_MARKETS
        for (needle in listOf(
            "Double Chance", "Babak 1", "Handicap Asia", "Handicap Eropa",
            "BTTS", "Minimal satu tim", "1X & Over 2.5", "Tuan rumah -0.25",
        )) {
            assert(match.contains(needle)) { "market '$needle' hilang dari katalog" }
        }
        val corner = Analyst.CORNER_MARKETS
        for (needle in listOf(
            "Total corner Over 9.5", "Corner babak 1", "Corner tuan rumah", "Corner tandang",
        )) {
            assert(corner.contains(needle)) { "market corner '$needle' hilang" }
        }
        println("Katalog match dan corner lengkap.")
    }

    @Test
    fun marketsAreGroupedInCatalogueOrder() {
        val m = match(0.8).copy(
            markets = listOf(
                MarketOption("Handicap A", 0.5, "", "Handicap Asia"),
                MarketOption("Over 2.5", 0.7, "", "Total Gol"),
                MarketOption("Over 1.5", 0.9, "", "Total Gol"),
                MarketOption("1X", 0.8, "", "Double Chance"),
            )
        )
        val groups = m.grouped()
        assert(groups.map { it.first } == listOf("Double Chance", "Total Gol", "Handicap Asia")) {
            "urutan grup salah: ${groups.map { it.first }}"
        }
        val totals = groups.first { it.first == "Total Gol" }.second
        assert(totals.first().name == "Over 1.5") { "isi grup tidak diurutkan dari peluang tertinggi" }
        println("Urutan grup: ${groups.map { it.first }}")
    }

    @Test
    fun theSafeBandMatchesTheOneThePickUses() {
        assert(!MarketOption("x", 0.60, "").safe) { "60% harusnya belum masuk aman" }
        assert(MarketOption("x", 0.75, "").safe)
        assert(!MarketOption("x", 0.96, "").safe) { "96% odds-nya terlalu kecil untuk dipasang" }
    }

    /**
     * Reproduces the user's own record: the headline looked healthy while one
     * market was quietly losing. If the split cannot surface that, it is not
     * worth having.
     */
    @Test
    fun theSplitFindsTheMarketTheAverageHides() {
        fun bet(group: String, prob: Double, won: Boolean) = match(prob).copy(
            markets = listOf(MarketOption("m", prob, "", group)),
            backed = "m",
            outcome = if (won) Outcome.WON else Outcome.LOST,
        )
        val record =
            List(8) { bet("Corner", 0.76, true) } + List(2) { bet("Corner", 0.76, false) } +
                List(5) { bet("Total Gol", 0.72, true) } +
                List(3) { bet("Over 1.5", 0.74, true) } + List(3) { bet("Over 1.5", 0.74, false) }

        val report = Report(record)
        assert(Math.round(report.actual * 100) == 76L) { "akurasi total salah: ${report.actual}" }

        val slices = report.byGroup().associateBy { it.name }
        assert(Math.round(slices.getValue("Corner").actual * 100) == 80L)
        assert(Math.round(slices.getValue("Over 1.5").actual * 100) == 50L)
        assert(slices.getValue("Over 1.5").worthWatching) { "market yang meleset jauh tidak ditandai" }
        assert(!slices.getValue("Corner").worthWatching) { "market yang sehat malah ditandai" }

        println()
        println("Total terlihat sehat: %d%% lawan janji %d%%"
            .format(Math.round(report.actual * 100), Math.round(report.promised * 100)))
        report.byGroup().forEach {
            println("  %-12s %d/%-2d  nyata %3d%%  janji %3d%%  selisih %+d %s"
                .format(it.name, it.won, it.total, Math.round(it.actual * 100),
                    Math.round(it.promised * 100), Math.round(it.gap * 100),
                    if (it.worthWatching) "← awasi" else ""))
        }
    }

    @Test
    fun aThinSliceIsNotFlagged() {
        fun bet(won: Boolean) = match(0.8).copy(
            markets = listOf(MarketOption("m", 0.8, "", "Baru")),
            backed = "m",
            outcome = if (won) Outcome.WON else Outcome.LOST,
        )
        val slice = Report(listOf(bet(false), bet(false), bet(true))).byGroup().first()
        assert(!slice.worthWatching) { "3 hasil seharusnya belum ditandai" }
    }

    @Test
    fun theRecordFollowsTheBetNotTheRecommendation() {
        val m = match(0.85).copy(
            pick = "Over 1.5",
            markets = listOf(
                MarketOption("Over 1.5", 0.85, "", "Total Gol"),
                MarketOption("Double Chance 1X", 0.71, "", "Double Chance"),
            ),
            backed = "Double Chance 1X",
            outcome = Outcome.WON,
        )
        assert(m.trackedMarket == "Double Chance 1X")
        assert(Math.round(m.trackedProb * 100) == 71L) { "peluang yang dicatat masih ikut rekomendasi" }
        assert(m.trackedGroup == "Double Chance")
        println()
        println("Direkomendasikan '${m.pick}', dipasang '${m.trackedMarket}' — yang dicatat yang dipasang.")
    }

    private fun withMarkets(pick: String, vararg m: Pair<String, Double>) = match(0.5).copy(
        pick = pick,
        pickProb = m.first { it.first == pick }.second,
        markets = m.map { (name, prob) -> MarketOption(name, prob, "", "Total Gol") },
    )

    /**
     * The badge and the recommendation were defined separately, so a 57% market
     * could be recommended while nothing on the page called it safe. The band is
     * enforced in the app rather than trusted to the model.
     */
    @Test
    fun aRecommendationBelowTheBandIsReplaced() {
        val fixed = Analyst("k").enforceSafePick(
            withMarkets("Over 1.5", "Over 1.5" to 0.57, "Double Chance 1X" to 0.81, "BTTS" to 0.71)
        )
        assert(fixed.pick == "Double Chance 1X") { "tidak diganti ke yang aman: ${fixed.pick}" }
        assert(fixed.pickCorrected) { "penggantian tidak diberitahukan" }
        println()
        println("57% diganti jadi '${fixed.pick}' ${fixed.pickPercent}% — dan dikabari ke pengguna.")
    }

    @Test
    fun aRecommendationAboveTheBandIsAlsoReplaced() {
        val fixed = Analyst("k").enforceSafePick(
            withMarkets("Over 0.5", "Over 0.5" to 0.97, "Over 1.5" to 0.84)
        )
        assert(fixed.pick == "Over 1.5") { "97% tetap direkomendasikan: ${fixed.pick}" }
        println("97% (odds di bawah 1,04) diganti jadi ${fixed.pickPercent}%.")
    }

    @Test
    fun aRecommendationInsideTheBandIsLeftAlone() {
        val original = withMarkets("BTTS", "BTTS" to 0.74, "Over 1.5" to 0.88)
        val after = Analyst("k").enforceSafePick(original)
        assert(after.pick == "BTTS") { "rekomendasi yang sudah aman ikut diganti" }
        assert(!after.pickCorrected)
        println("Yang sudah di rentang aman tidak diutak-atik — walau ada yang lebih tinggi.")
    }

    @Test
    fun withNothingSafeTheAppDoesNotInventOne() {
        val original = withMarkets("Over 2.5", "Over 2.5" to 0.55, "BTTS" to 0.61)
        val after = Analyst("k").enforceSafePick(original)
        assert(after.pick == "Over 2.5") { "memaksakan pilihan padahal tidak ada yang aman" }
        assert(!after.pickCorrected)
        assert(after.safePicks().isEmpty())
        println("Kalau tidak ada yang masuk rentang, tidak dipaksakan — layar bilang apa adanya.")
    }

    @Test
    fun theSafeListRunsHighestFirstAndExcludesTheRest() {
        val m = withMarkets(
            "Over 1.5",
            "Over 1.5" to 0.84, "Terlalu rendah" to 0.60,
            "BTTS" to 0.71, "Terlalu tinggi" to 0.95, "DC" to 0.90,
        )
        val safe = m.safePicks().map { it.name }
        assert(safe == listOf("DC", "Over 1.5", "BTTS")) { "urutan atau saringan salah: $safe" }
        println("Daftar aman: $safe — tertinggi di atas, yang di luar rentang dibuang.")
    }

    // ------------------------------------------------------------ market grid

    /**
     * The reported bug: whole groups came back on one match and were gone on the
     * next, because the model chose how many markets to bother listing.
     */
    @Test
    fun everyGroupIsCoveredWhateverTheModelReturns() {
        val filled = Grid.fill(match(0.5).copy(markets = listOf(
            MarketOption("Over 1.5", 0.80, "dari model", "Total Gol")
        )))
        val groups = filled.markets.map { it.group }.toSet()
        val wanted = Markets.order.filterNot { it.startsWith("Corner") || it == "Lainnya" }
        val missing = wanted.filterNot { it in groups }
        assert(missing.isEmpty()) { "grup masih kosong: $missing" }
        println()
        println("Model kasih 1 market → layar dapat ${filled.markets.size}, ${groups.size} grup lengkap.")
    }

    @Test
    fun theModelsOwnNumberIsNeverOverwritten() {
        val mine = MarketOption("Over 1.5", 0.42, "kata model", "Total Gol")
        val filled = Grid.fill(match(0.5).copy(markets = listOf(mine)))
        val kept = filled.markets.filter { it.name == "Over 1.5" && it.group == "Total Gol" }
        assert(kept.size == 1) { "market model diduplikasi: ${kept.size}" }
        assert(kept[0].prob == 0.42) { "angka model ditimpa jadi ${kept[0].prob}" }
        println("Angka model dipertahankan (42%), hitungan cuma mengisi yang kosong.")
    }

    /**
     * An unreadable screenshot must not become fifty confident percentages.
     */
    @Test
    fun anUnreadableMatchIsLeftAlone() {
        val blank = match(0.5).copy(readable = false, markets = emptyList())
        assert(Grid.fill(blank).markets.isEmpty()) { "gambar tak terbaca malah diisi angka" }
        println("Gambar tidak terbaca → tetap kosong, tidak dikarang.")
    }

    @Test
    fun derivedMarketsAgreeWithEachOther() {
        val m = Grid.matchMarkets(1.6, 1.1, 0.47, 0.26, 0.27).associateBy { it.group to it.name }
        fun p(g: String, n: String) = m[g to n]!!.prob

        val ou = p("Total Gol", "Over 2.5") + p("Total Gol", "Under 2.5")
        assert(abs(ou - 1.0) < 1e-6) { "Over+Under 2.5 = $ou" }
        val x = p("Hasil Akhir", "Tuan rumah menang") + p("Hasil Akhir", "Seri") +
            p("Hasil Akhir", "Tandang menang")
        assert(abs(x - 1.0) < 1e-6) { "1X2 = $x" }
        assert(p("Double Chance", "1X (tuan rumah atau seri)") >= p("Hasil Akhir", "Tuan rumah menang")) {
            "Double Chance di bawah komponennya"
        }
        assert(p("Total Gol", "Over 1.5") >= p("Total Gol", "Over 2.5")) { "garis Over tidak menurun" }
        println("Pasangan berjumlah 1,0; DC tidak pernah di bawah komponennya.")
    }

    /**
     * Both handicap families list "Tuan rumah -1" and they settle differently, so
     * the fill has to key on group as well as name or one silently eats the other.
     */
    @Test
    fun theTwoHandicapFamiliesBothSurvive() {
        val m = Grid.matchMarkets(1.6, 1.1, 0.47, 0.26, 0.27)
        val named = m.filter { it.name == "Tuan rumah -1" }.map { it.group }.toSet()
        assert(named == setOf("Handicap Asia", "Handicap Eropa")) { "handicap saling menimpa: $named" }
        println("Handicap Asia dan Eropa dengan nama sama tetap dua baris terpisah.")
    }

    @Test
    fun theGridIsFittedToTheModelsOwnCall() {
        val (lh, la) = Grid.fit(1.5, 1.5, 0.62, 0.20, 0.18)
        val m = Grid.matchMarkets(1.5, 1.5, 0.62, 0.20, 0.18)
        val home = m.first { it.group == "Hasil Akhir" && it.name == "Tuan rumah menang" }.prob
        assert(abs(home - 0.62) < 0.03) { "grid bilang $home padahal model bilang 0,62" }
        println("Model bilang 62%% menang → grid disetel ke %.2f/%.2f, bacanya %d%%."
            .format(lh, la, Math.round(home * 100)))
    }

    @Test
    fun cornerModeGetsItsOwnCatalogue() {
        val filled = Grid.fill(match(0.5).copy(mode = Mode.CORNER, xgHome = 5.4, xgAway = 4.6))
        val groups = filled.markets.map { it.group }.toSet()
        assert(groups.all { it.startsWith("Corner") }) { "market gol bocor ke analisis corner: $groups" }
        val o = filled.markets.first { it.name == "Total corner Over 9.5" }.prob
        val u = filled.markets.first { it.name == "Total corner Under 9.5" }.prob
        assert(abs(o + u - 1.0) < 1e-6)
        println("Corner 5,4-4,6 → Over 9.5 %d%%.".format(Math.round(o * 100)))
    }

    // ------------------------------------------------------------ model layer

    /**
     * The exact bug behind "dah beli tapi gabisa make model apapun": the default was
     * a dated name, and dated names get closed to new keys.
     */
    @Test
    fun theDefaultModelIsAnAliasThatCannotRetire() {
        assert(Analyst.DEFAULT_MODEL.endsWith("-latest")) {
            "default kembali ke nama bertanggal: ${Analyst.DEFAULT_MODEL}"
        }
        assert(Analyst.MODELS.all { it.second.endsWith("-latest") }) {
            "daftar cadangan masih memuat nama bertanggal"
        }
        println()
        println("Default sekarang '${Analyst.DEFAULT_MODEL}' — alias, tidak bisa pensiun.")
    }

    @Test
    fun retiredModelsHandGoogleTheirOwnReplacement() {
        // Google's wording, verbatim from the 404 the user's new key received.
        val body = """{"error":{"code":404,"message":"This model models/gemini-2.5-flash """ +
            """is no longer available to new users. Please update your code to use """ +
            """models/gemini-3.6-flash for the latest features and improvements."}}"""
        val fix = Analyst("k").retirementReplacement(404, body)
        assert(fix == "gemini-3.6-flash") { "pengganti tidak terbaca: $fix" }
        assert(Analyst("k").retirementReplacement(404, """{"error":{"message":"not found"}}""") == null)
        println("404 'sudah pensiun' → otomatis pindah ke $fix, bukan gagal total.")
    }

    @Test
    fun aliasesLeadAndPreviewsTrail() {
        val ids = listOf(
            "gemini-3-flash-preview", "gemini-2.5-flash", "gemini-flash-latest",
            "gemini-3.7-flash", "gemini-3.5-flash-lite",
        )
        val order = Analyst.rank(ids.map { Analyst.Model(it, it, "") }).map { it.id }
        assert(order.first() == "gemini-flash-latest") { "urutan: $order" }
        assert(Analyst.score("gemini-3.1-flash-lite-preview") < Analyst.score("gemini-3.1-flash-lite")) {
            "preview tidak kalah dari versi stabil segenerasi"
        }
        assert(order.indexOf("gemini-3.7-flash") < order.indexOf("gemini-2.5-flash")) {
            "model lama di atas yang baru: $order"
        }
        println("Urutan model: $order")
    }

    @Test
    fun theOmniVariantsAreFilteredOut() {
        assert(!Analyst.usable("gemini-omni-flash-preview")) { "omni lolos padahal cuma Interactions API" }
        assert(!Analyst.usable("gemini-omni-1.1-flash"))
        assert(Analyst.usable("gemini-3.7-flash"))
        println("Model omni disaring — dulu muncul di daftar lalu selalu gagal 400.")
    }

    /**
     * The test button reported every model broken while all of them worked.
     *
     * It allowed 16 output tokens, and current models spend their first tokens
     * thinking: the reply came back finishReason=MAX_TOKENS with no text at all, and
     * the screen showed a bare "Gagal." Measured against the user's own key, the
     * thinking alone was 13 tokens on Flash and 161 on Pro before a word was written.
     */
    @Test
    fun theModelTestLeavesRoomToActuallyAnswer() {
        assert(Analyst.TEST_OUTPUT_TOKENS >= 512) {
            "jatah tes terlalu kecil lagi: ${Analyst.TEST_OUTPUT_TOKENS} token"
        }
        println()
        println("Tes model: ${Analyst.TEST_OUTPUT_TOKENS} token — cukup untuk berpikir lalu menjawab.")
    }

    /**
     * A truncated answer used to retry the whole request, and a retry re-uploads
     * every screenshot — the expensive half, charged twice for one answer. Thinking
     * is bounded on the first attempt so the retry stays rare.
     */
    @Test
    fun thinkingIsBoundedBeforeTheExpensiveRetry() {
        assert(Analyst.THINKING_BUDGET < Analyst.MAX_OUTPUT_TOKENS) {
            "berpikir bisa menghabiskan seluruh jatah jawaban"
        }
        assert(Analyst.TIGHT_THINKING_BUDGET < Analyst.THINKING_BUDGET) {
            "percobaan ulang tidak lebih ketat dari yang pertama"
        }
        val room = Analyst.MAX_OUTPUT_TOKENS - Analyst.THINKING_BUDGET
        assert(room >= 16384) { "sisa ruang untuk JSON cuma $room token" }
        println("Berpikir dibatasi ${Analyst.THINKING_BUDGET}, sisa $room token untuk jawaban.")
    }

    @Test
    fun ignoresImpossibleProbabilities() {
        val json = """{"markets":[{"name":"Baik","prob":0.7,"why":""},
                                  {"name":"Rusak","prob":1.8,"why":""},
                                  {"name":"Negatif","prob":-0.2,"why":""}],"pick":"Baik","pick_prob":0.7}"""
        val m = Analyst("dummy").parse(json)
        assert(m.markets.size == 1) { "peluang di luar 0-1 ikut masuk: ${m.markets.map { it.name }}" }
        println("Peluang mustahil dibuang, bukan ditampilkan.")
    }
}
