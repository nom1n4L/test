package com.skorsnap.app

import com.skorsnap.app.data.Analyst
import com.skorsnap.app.data.Appetite
import com.skorsnap.app.data.Comparison
import com.skorsnap.app.data.Migration
import org.json.JSONObject
import com.skorsnap.app.data.Coach
import com.skorsnap.app.data.Football
import com.skorsnap.app.data.Lens
import com.skorsnap.app.data.MarketOption
import com.skorsnap.app.data.Markets
import com.skorsnap.app.data.Grid
import com.skorsnap.app.data.Images
import com.skorsnap.app.data.MatchPrediction
import com.skorsnap.app.data.Mode
import com.skorsnap.app.data.Outcome
import com.skorsnap.app.data.Leg
import com.skorsnap.app.data.Parlay
import com.skorsnap.app.data.priceLabel
import com.skorsnap.app.data.SavedSlip
import com.skorsnap.app.data.SlipReport
import com.skorsnap.app.data.Strategy
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
            markets = listOf(MarketOption("Over 1.5", prob, "", "Total Gol")),
            pick = "Over 1.5", pickProb = prob,
            confidence = "tinggi", confidenceWhy = "",
        )

    /** A slip built the ordinary way: one recommended market per match. */
    private fun slipOf(vararg probs: Double) =
        Parlay.build(probs.map { match(it) }, Strategy.RECOMMENDED)

    @Test
    fun probabilitiesMultiplyAcrossLegs() {
        val slip = slipOf(0.80, 0.80, 0.80, 0.80)
        assert(abs(slip.combined - 0.8.pow(4)) < 1e-9) { "gabungan salah: ${slip.combined}" }
        assert(slip.percent == 41) { "empat leg 80% harusnya 41%, dapat ${slip.percent}%" }
        println("4 leg @80%% → %d%% (1 dari %d)".format(slip.percent, slip.oneInN))
    }

    @Test
    fun sixLegsIsTheCaseTheUserAsksFor() {
        val slip = Parlay.build((1..6).map { match(0.75) }, Strategy.RECOMMENDED)
        println()
        println("6 leg @75%%: tembus semua %d%% (1 dari %d)".format(slip.percent, slip.oneInN))
        println("   diperkirakan tembus %.1f dari 6".format(slip.expectedHits))
        println("   bayaran wajar %.2f".format(slip.fairOdds))
        println("   imbal hasil harapan %.0f%%".format(slip.expectedReturn * 100))
        assert(slip.percent in 17..19) { "6 leg 75% harusnya sekitar 18%, dapat ${slip.percent}%" }
    }

    @Test
    fun expectedReturnDependsOnlyOnLegCount() {
        val safe = slipOf(0.90, 0.88, 0.91)
        val risky = slipOf(0.55, 0.60, 0.52)
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
        val slip = Parlay.build(listOf(same, same, match(0.7)), Strategy.RECOMMENDED)
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

    /**
     * Marks named markets, mirroring what tapping Tembus/Meleset does. The roles
     * (recommendation, bet) read straight off this map, so a test cannot set them
     * to disagree with each other the way separate fields allowed.
     */
    private fun MatchPrediction.marking(vararg pairs: Pair<String, Outcome>) =
        copy(marketOutcomes = marketOutcomes + pairs.associate { (name, o) -> keyOf(name) to o })

    private fun verdict(won: Boolean) = if (won) Outcome.WON else Outcome.LOST

    private fun settled(prob: Double, won: Boolean) =
        match(prob).marking("Over 1.5" to verdict(won))

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
            pick = "m",
        ).marking("m" to verdict(won))
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
            pick = "m",
        ).marking("m" to verdict(won))
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
        ).marking("Double Chance 1X" to Outcome.WON, "Over 1.5" to Outcome.LOST)
        assert(m.marketFor(Lens.BACKED) == "Double Chance 1X")
        assert(Math.round(m.probFor(Lens.BACKED) * 100) == 71L) {
            "peluang yang dicatat masih ikut rekomendasi"
        }
        assert(m.groupFor(Lens.BACKED) == "Double Chance")
        assert(m.marketFor(Lens.PICK) == "Over 1.5")
        assert(Math.round(m.probFor(Lens.PICK) * 100) == 85L)
        println()
        println("Rekomendasi '${m.pick}' meleset, pasangan '${m.marketFor(Lens.BACKED)}' tembus — " +
            "dua-duanya tercatat terpisah.")
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
        // Only what Grid added is under test; the fixture's own market is not.
        val added = filled.markets.filter { it.derived }.map { it.group }.toSet()
        assert(added.isNotEmpty() && added.all { it.startsWith("Corner") }) {
            "market gol bocor ke analisis corner: $added"
        }
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

    // ------------------------------------------------ dua catatan, bukan satu

    private fun bothWays(pick: String, backedMarket: String, pickWon: Boolean, backedWon: Boolean) =
        match(0.8).copy(
            pick = pick,
            markets = listOf(
                MarketOption(pick, 0.80, "", "Total Gol"),
                MarketOption(backedMarket, 0.71, "", "Double Chance"),
            ),
            backed = backedMarket,
        ).marking(pick to verdict(pickWon), backedMarket to verdict(backedWon))

    /**
     * The complaint behind this split: the recommendation missed, the safe market
     * the user actually backed landed, and the report could only show one of them.
     */
    @Test
    fun oneMatchNowAnswersTwoQuestions() {
        val m = bothWays("Over 1.5", "1X", pickWon = false, backedWon = true)
        val asPick = Report(listOf(m), Lens.PICK)
        val asBacked = Report(listOf(m), Lens.BACKED)
        assert(asPick.won == 0 && asPick.total == 1) { "rekomendasi salah dicatat" }
        assert(asBacked.won == 1 && asBacked.total == 1) { "pilihan pengguna salah dicatat" }
        assert(Math.round(asPick.promised * 100) == 80L)
        assert(Math.round(asBacked.promised * 100) == 71L)
        println()
        println("Satu laga: rekomendasi meleset, pilihan sendiri tembus — keduanya tercatat.")
    }

    @Test
    fun theComparisonOnlyCountsMatchesWhereBothAreKnown() {
        val complete = bothWays("Over 1.5", "1X", pickWon = true, backedWon = false)
        val halfDone = complete.copy(
            id = "b",
            marketOutcomes = complete.marketOutcomes - complete.keyOf("1X"),
        )
        val sameMarket = match(0.8).copy(id = "c", pick = "Over 1.5", backed = "Over 1.5")
            .marking("Over 1.5" to Outcome.WON)
        val c = Comparison(listOf(complete, halfDone, sameMarket))
        assert(c.n == 1) { "yang dibandingkan seharusnya 1, dapat ${c.n}" }
        assert(c.pickWon == 1 && c.backedWon == 0)
        println("Hanya laga yang dua-duanya tercatat DAN pilihannya beda yang dibandingkan.")
    }

    @Test
    fun theComparisonRefusesToCallAWinnerTooEarly() {
        val lopsided = List(5) { bothWays("Over 1.5", "1X", pickWon = true, backedWon = false)
            .copy(id = "m$it") }
        val verdict = Comparison(lopsided).verdict
        assert(verdict.contains("Terlalu sedikit")) { "5-0 langsung disimpulkan: $verdict" }
        println("5-0 pun belum disimpulkan: \"${verdict.take(60)}…\"")
    }

    @Test
    fun theExactMarketSplitSeparatesOppositeBets() {
        fun bet(name: String, won: Boolean) = match(0.74).copy(
            markets = listOf(MarketOption(name, 0.74, "", "Total Gol")),
            backed = name, pick = name,
        ).marking(name to verdict(won))
        val record = List(3) { bet("Over 1.5", false) } + List(3) { bet("Under 3.5", true) }
        val group = Report(record, Lens.BACKED).byGroup()
        val markets = Report(record, Lens.BACKED).byMarket().associateBy { it.name }
        assert(group.size == 1) { "keduanya memang satu kelompok" }
        assert(markets["Over 1.5"]!!.won == 0 && markets["Under 3.5"]!!.won == 3) {
            "market berlawanan masih tercampur"
        }
        println("Kelompok 'Total Gol' 50%, tapi Over 1.5 0/3 dan Under 3.5 3/3 — beda jauh.")
    }

    // ------------------------------------------------ catatan lama tidak hilang

    /**
     * The old save had one flag, and it described whichever market was backed. A
     * divergent bet's verdict therefore belongs to the bet, and the recommendation
     * was never judged — crediting the app with it would invent a result.
     */
    @Test
    fun oldRecordsSurviveWithoutInventingResults() {
        val divergent = JSONObject("""{"pick":"Over 1.5","backed":"1X","outcome":"WON"}""")
        val (p1, b1) = Migration.outcomes(divergent)
        assert(b1 == Outcome.WON) { "hasil taruhan lama hilang" }
        assert(p1 == Outcome.PENDING) { "rekomendasi diberi hasil yang tak pernah dinilai" }

        val same = JSONObject("""{"pick":"Over 1.5","backed":"","outcome":"LOST"}""")
        val (p2, b2) = Migration.outcomes(same)
        assert(p2 == Outcome.LOST && b2 == Outcome.LOST) { "taruhan sama market malah kosong" }

        val fresh = JSONObject("""{"pick_outcome":"WON","backed_outcome":"LOST"}""")
        assert(Migration.outcomes(fresh) == Outcome.WON to Outcome.LOST)
        println()
        println("Catatan lama terbaca: hasil taruhan tetap, rekomendasi tidak dikarang.")
    }

    // ------------------------------------------------ umpan balik ke model

    @Test
    fun theCoachFlagsAnOverconfidentMarketAndSaysHowMany() {
        fun bet(group: String, prob: Double, won: Boolean) = match(prob).copy(
            markets = listOf(MarketOption("m", prob, "", group)),
            pick = "m",
        ).marking("m" to verdict(won))
        val record = List(3) { bet("Total Gol", 0.74, false) } + List(3) { bet("Total Gol", 0.74, true) } +
            List(8) { bet("Corner", 0.76, true) } + List(2) { bet("Corner", 0.76, false) }
        val brief = Coach.brief(record)
        assert(brief.contains("TERLALU PERCAYA DIRI")) { "market yang overclaim tidak ditandai:\n$brief" }
        assert(brief.contains("dari 6 taruhan")) { "jumlah data tidak disebut:\n$brief" }
        assert(brief.contains("masih sedikit")) { "tidak mengakui sampelnya kecil" }
        println()
        println(brief)
    }

    @Test
    fun theCoachStaysQuietUntilThereIsSomethingToSay() {
        assert(Coach.brief(emptyList()).isBlank())
        assert(Coach.brief(List(2) { match(0.8).marking("Over 1.5" to Outcome.WON) }).isBlank()) {
            "menyimpulkan dari 2 hasil"
        }
        println("Di bawah ${Coach.MIN_SAMPLE} hasil, tidak ada yang diumpankan — tidak mengarang pola.")
    }

    /** The same result must not be counted twice just because two fields hold it. */
    @Test
    fun backingTheRecommendationCountsOnce() {
        val same = List(6) {
            match(0.8).copy(pick = "m", backed = "", markets = listOf(MarketOption("m", 0.8, "", "G")))
                .marking("m" to Outcome.WON)
        }
        assert(Coach.brief(same).contains("(6 taruhan sudah selesai)")) {
            "hasil yang sama dihitung dua kali:\n${Coach.brief(same)}"
        }
        println("Pasang sesuai rekomendasi → dihitung sekali, bukan dua.")
    }

    // -------------------------------------- menandai dari daftar aman

    private fun corners() = match(0.85).copy(
        pick = "Total corner Under 11.5",
        backed = "Total corner Under 10.5",
        markets = listOf(
            MarketOption("Total corner Under 11.5", 0.85, "", "Corner"),
            MarketOption("Total corner Under 10.5", 0.70, "", "Corner"),
            MarketOption("Corner babak 1 Under 5.5", 0.70, "", "Corner Babak 1"),
        ),
    )

    /**
     * One map behind every role. Ticking the recommendation off in the safe list
     * has to settle the recommendation too — two stores would let the same market
     * be both a hit and a miss depending on where the user tapped.
     */
    @Test
    fun markingInTheSafeListSettlesTheRoleItFills() {
        val m = corners().marking("Total corner Under 11.5" to Outcome.WON)
        assert(m.pickOutcome == Outcome.WON) { "rekomendasi tidak ikut tercatat" }
        assert(m.backedOutcome == Outcome.PENDING) { "market lain ikut tertandai" }
        println()
        println("Tandai 'Under 11.5' di daftar aman → rekomendasi otomatis tercatat tembus.")
    }

    /** Three ticks on one match, three separate observations for the record. */
    @Test
    fun oneMatchCanContributeSeveralObservations() {
        val m = corners().marking(
            "Total corner Under 11.5" to Outcome.WON,
            "Total corner Under 10.5" to Outcome.LOST,
            "Corner babak 1 Under 5.5" to Outcome.WON,
        )
        assert(m.marks().size == 3) { "cuma ${m.marks().size} tanda terbaca" }
        val report = Report(listOf(m), Lens.BACKED)
        assert(report.allMarks().size == 3)
        assert(report.total == 1) { "satu laga tetap satu baris di rapor peran" }
        println("Satu laga ditandai 3 market → 3 bukti kalibrasi, tapi tetap 1 laga.")
    }

    @Test
    fun everyTickReachesTheModelsBrief() {
        val record = List(5) {
            corners().copy(id = "m$it").marking(
                "Total corner Under 11.5" to Outcome.LOST,
                "Total corner Under 10.5" to Outcome.WON,
            )
        }
        val brief = Coach.brief(record)
        assert(brief.contains("(10 taruhan sudah selesai)")) { "tanda tidak terhitung:\n$brief" }
        assert(brief.contains("TERLALU PERCAYA DIRI")) { "pola tidak tertangkap:\n$brief" }
        println()
        println(brief)
    }

    /**
     * Three save formats exist on real devices: one flag, then two, now a map.
     */
    @Test
    fun everySaveFormatStillReadsBack() {
        val markets = listOf(
            MarketOption("Over 1.5", 0.8, "", "Total Gol"),
            MarketOption("1X", 0.7, "", "Double Chance"),
        )
        val ancient = JSONObject("""{"pick":"Over 1.5","backed":"1X","outcome":"WON"}""")
        assert(Migration.marketOutcomes(ancient, markets) == mapOf("Double Chance|1X" to Outcome.WON)) {
            "hasil taruhan lama salah tempat: ${Migration.marketOutcomes(ancient, markets)}"
        }

        val previous = JSONObject(
            """{"pick":"Over 1.5","backed":"1X","pick_outcome":"LOST","backed_outcome":"WON"}"""
        )
        assert(Migration.marketOutcomes(previous, markets) ==
            mapOf("Total Gol|Over 1.5" to Outcome.LOST, "Double Chance|1X" to Outcome.WON))

        val current = JSONObject("""{"market_outcomes":{"Corner|Under 9.5":"WON"}}""")
        assert(Migration.marketOutcomes(current, markets) == mapOf("Corner|Under 9.5" to Outcome.WON))
        println()
        println("Tiga format simpanan lama terbaca semua — catatanmu tidak hilang.")
    }

    /**
     * The stale-screen bug, twice over: the report showed the previous total, and a
     * verdict stayed grey until the screen was reopened. Both came from a screen
     * calling a view-model function that reads the flow's `.value`, which Compose
     * does not observe.
     *
     * Guarded structurally rather than by eye: no public function on the view model
     * may hand a screen data derived from the match list. Screens observe the flow.
     */
    @Test
    fun theViewModelCannotHandScreensUnobservedData() {
        val own = com.skorsnap.app.ui.AppViewModel::class.java.methods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filter { it.declaringClass.name.startsWith("com.skorsnap") }
        // Without this the check could pass by inspecting nothing at all.
        assert(own.size > 5) { "refleksi tidak menemukan apa-apa — penjaganya palsu" }

        val leaky = own
            .filter {
                val t = it.returnType.name
                t.endsWith("MatchPrediction") || t.endsWith("Slip") || t.endsWith("Report")
            }
            .map { it.name }
        assert(leaky.isEmpty()) {
            "fungsi ini bisa dipanggil layar dan bikin tampilan basi lagi: $leaky"
        }
        println()
        println("${own.size} fungsi publik diperiksa — tak satu pun menyerahkan " +
            "data laga tanpa diawasi Compose.")
    }

    // ------------------------------------------------ buatkan parlay

    private fun choice(id: String, pick: String, vararg m: Pair<String, Double>) = match(0.8, id).copy(
        home = "Tim$id", away = "Lawan$id",
        pick = pick,
        markets = m.map { (n, p) -> MarketOption(n, p, "", "Total Gol") },
    )

    private fun threeMatches() = (1..3).map {
        choice("$it", "Over 1.5", "Over 0.5" to 0.95, "Over 1.5" to 0.84, "BTTS" to 0.70)
    }

    @Test
    fun eachStrategyTakesADifferentMarket() {
        val ms = threeMatches()
        assert(Parlay.build(ms, Strategy.RECOMMENDED).legs.all { it.market == "Over 1.5" })
        assert(Parlay.build(ms, Strategy.SAFEST).legs.all { it.market == "Over 1.5" }) {
            "0.95 di luar rentang aman, seharusnya tidak dipilih"
        }
        assert(Parlay.build(ms, Strategy.HIGHER_PAYING).legs.all { it.market == "BTTS" })
        println()
        println("Rekomendasi & paling aman → Over 1.5; bayaran lebih tinggi → BTTS.")
    }

    /** The trade the third option makes, stated in numbers rather than implied. */
    @Test
    fun higherPayingMeansHigherOddsAndLowerChance() {
        val ms = threeMatches()
        val safe = Parlay.build(ms, Strategy.SAFEST)
        val paying = Parlay.build(ms, Strategy.HIGHER_PAYING)
        assert(paying.fairOdds > safe.fairOdds) { "bayarannya tidak naik" }
        assert(paying.combined < safe.combined) { "peluangnya tidak turun" }
        println("Paling aman  : tembus %d%%, bayaran wajar %.2f".format(safe.percent, safe.fairOdds))
        println("Bayaran naik : tembus %d%%, bayaran wajar %.2f".format(paying.percent, paying.fairOdds))
    }

    /**
     * The label "value" is withheld deliberately: without the bookmaker's price both
     * slips have exactly the same expected return, so calling the longer one better
     * value would be false.
     */
    @Test
    fun withoutRealPricesNeitherStrategyIsBetterValue() {
        val ms = threeMatches()
        val safe = Parlay.build(ms, Strategy.SAFEST)
        val paying = Parlay.build(ms, Strategy.HIGHER_PAYING)
        assert(abs(safe.expectedReturn - paying.expectedReturn) < 1e-12) {
            "harapan berbeda padahal harga bandar belum dimasukkan"
        }
        assert(!safe.priced && !paying.priced)
        println("Tanpa odds asli, harapan keduanya identik %.1f%% — bayaran naik bukan nilai naik."
            .format(safe.expectedReturn * 100))
    }

    @Test
    fun aMatchWithNothingSafeIsSkippedNotPadded() {
        val thin = choice("4", "Over 2.5", "Over 2.5" to 0.55, "BTTS" to 0.60)
        val ms = threeMatches() + thin
        val slip = Parlay.build(ms, Strategy.SAFEST)
        assert(slip.size == 3) { "laga tanpa market aman ikut masuk: ${slip.size} leg" }
        assert(Parlay.skipped(ms, Strategy.SAFEST).map { it.id } == listOf("4"))
        println("Laga tanpa market di rentang aman dilewati, slip tidak ditambal lemparan koin.")
    }

    // ------------------------------------------------ odds asli dari bandar

    /**
     * The number beside a market is the break-even price, not the bookmaker's. The
     * two were being read as the same thing; they are opposites.
     */
    @Test
    fun theAppsNumberIsTheMinimumPriceNotTheOffer() {
        val leg = Parlay.build(listOf(choice("1", "Over 1.5", "Over 1.5" to 0.80)), Strategy.RECOMMENDED).legs.first()
        assert(abs(leg.breakEven - 1.25) < 1e-9) { "harga minimal salah: ${leg.breakEven}" }
        println()
        println("Peluang 80% → harga minimal 1,25. Kalau Melbet bayar 1,20, itu rugi.")
    }

    @Test
    fun realPricesDecideWhetherASlipIsWorthTaking() {
        val ms = threeMatches()
        var slip = Parlay.build(ms, Strategy.SAFEST)
        // Fair price for 84% is 1.19; a generous book and a stingy one.
        slip.legs.forEach { slip = slip.withOdds(it.matchId, it.market, 1.30) }
        assert(slip.priced)
        assert(slip.worthTaking) { "harga di atas minimal tapi dibilang rugi" }
        val good = slip.expectedReturn

        var mean = Parlay.build(ms, Strategy.SAFEST)
        mean.legs.forEach { mean = mean.withOdds(it.matchId, it.market, 1.12) }
        assert(!mean.worthTaking) { "harga di bawah minimal tapi dibilang untung" }

        println("Tiga leg 84%%: di odds 1,30 harapan %.0f%%, di odds 1,12 harapan %.0f%%."
            .format(good * 100, mean.expectedReturn * 100))
        println("Angka aplikasi tidak berubah — harganya yang menentukan.")
    }

    @Test
    fun aBadlyPricedLegIsNamed() {
        val ms = threeMatches()
        var slip = Parlay.build(ms, Strategy.SAFEST)
        slip.legs.forEachIndexed { i, leg ->
            slip = slip.withOdds(leg.matchId, leg.market, if (i == 0) 1.05 else 1.40)
        }
        assert(slip.badlyPriced.size == 1) { "leg yang kemurahan tidak ditandai" }
        assert(slip.legs.first().edge < 0 && slip.legs.last().edge > 0)
        println("Leg dengan harga di bawah minimal ditunjuk satu per satu.")
    }

    // ------------------------------------------------ ganti leg & rapor parlay

    private fun priced(vararg pairs: Pair<String, Double>) =
        pairs.associate { (name, o) -> "1|$name" to o }

    /**
     * "Rugi" without a way out is half a feature. The swap picks by the only thing
     * that decides value: how far the price sits above break-even.
     */
    @Test
    fun theSwapPicksTheBestPricedMarketNotTheLikeliest() {
        val m = choice("1", "Over 1.5", "Over 1.5" to 0.84, "BTTS" to 0.70, "DC" to 0.90)
        // Over 1.5 needs 1.19 and gets 1.20; BTTS needs 1.43 and gets 1.70.
        val best = Parlay.bestPriced(m, priced("Over 1.5" to 1.20, "BTTS" to 1.70))
        assert(best?.market == "BTTS") { "yang dipilih: ${best?.market}" }
        println()
        println("Over 1.5 84% (+1%) vs BTTS 70% (+19%) → dipilih BTTS, bukan yang peluangnya tertinggi.")
    }

    @Test
    fun theSwapRefusesWhenNoPriceBeatsBreakEven() {
        val m = choice("1", "Over 1.5", "Over 1.5" to 0.84, "BTTS" to 0.70)
        assert(Parlay.bestPriced(m, priced("Over 1.5" to 1.10, "BTTS" to 1.30)) == null) {
            "menukar ke market yang sama-sama rugi"
        }
        println("Kalau semua harga di bawah minimal, tidak ada yang ditukar — bukan asal ganti.")
    }

    @Test
    fun aMarketWithNoPriceIsNeverChosenBlind() {
        val m = choice("1", "Over 1.5", "Over 1.5" to 0.84, "BTTS" to 0.70)
        val best = Parlay.bestPriced(m, priced("Over 1.5" to 1.40))
        assert(best?.market == "Over 1.5") { "market tanpa odds ikut dipertimbangkan" }
        println("Market yang belum diisi odds-nya tidak pernah dipilih — tak ada yang bisa dibandingkan.")
    }

    @Test
    fun aHandPickedLegOverridesTheStrategy() {
        val ms = threeMatches()
        val slip = Parlay.build(ms, Strategy.SAFEST, mapOf("2" to "BTTS"))
        assert(slip.legs.first { it.matchId == "2" }.market == "BTTS")
        assert(slip.legs.first { it.matchId == "1" }.market == "Over 1.5") {
            "laga lain ikut berubah"
        }
        println("Ganti satu leg tidak mengubah leg lainnya.")
    }

    private fun savedSlip(id: String, legs: Int, prob: Double, won: Boolean?, stake: Double = 0.0) =
        SavedSlip(
            id = id, placedAt = 0L, strategy = "uji", stake = stake,
            outcome = when (won) { true -> Outcome.WON; false -> Outcome.LOST; null -> Outcome.PENDING },
            legs = (1..legs).map {
                Leg("m$it", "A", "B", "Over 1.5", "Total Gol", prob, odds = 1.0 / prob + 0.10)
            },
        )

    @Test
    fun theParlayReportCountsSlipsNotLegs() {
        val r = SlipReport(
            listOf(
                savedSlip("a", 3, 0.80, true), savedSlip("b", 3, 0.80, false),
                savedSlip("c", 2, 0.80, false), savedSlip("d", 3, 0.80, null),
            )
        )
        assert(r.total == 3) { "slip yang belum ditandai ikut dihitung" }
        assert(r.won == 1)
        assert(r.byLegCount().map { it.name } == listOf("2 leg", "3 leg"))
        println()
        println("3 slip selesai, 1 tembus — dipecah per jumlah leg, bukan per market.")
    }

    @Test
    fun theParlayReportRefusesToConcludeEarly() {
        val r = SlipReport(List(4) { savedSlip("s$it", 3, 0.8, false) })
        assert(r.verdict.contains("Terlalu sedikit")) { "4 slip langsung disimpulkan: ${r.verdict}" }
        println("4 slip 0 tembus pun belum disimpulkan — parlay jarang tembus, itu wajar.")
    }

    @Test
    fun theMoneyIsCountedOnlyWhereAStakeWasEntered() {
        val r = SlipReport(
            listOf(
                savedSlip("a", 2, 0.80, true, stake = 100000.0),
                savedSlip("b", 2, 0.80, false, stake = 100000.0),
                savedSlip("c", 2, 0.80, true),
            )
        )
        assert(r.staked == 200000.0) { "slip tanpa nominal ikut dihitung: ${r.staked}" }
        assert(r.returned > 0 && r.profit != 0.0)
        println("Rp %,.0f dipasang, kembali Rp %,.0f — slip tanpa nominal tidak diikutkan."
            .format(r.staked, r.returned))
    }

    /**
     * A parlay result is its legs multiplied, so it is only new information when it
     * disagrees with that multiplication — which is what correlated legs look like.
     */
    @Test
    fun theBriefOnlyMentionsParlaysWhenTheyAddSomething() {
        val record = List(6) {
            choice("$it", "Over 1.5", "Over 1.5" to 0.80)
                .marking("Over 1.5" to Outcome.WON)
        }
        val few = Coach.brief(record, List(3) { savedSlip("s$it", 3, 0.8, false) })
        assert(!few.contains("Parlay:")) { "3 slip sudah dijadikan pelajaran" }

        val correlated = Coach.brief(record, List(8) { savedSlip("s$it", 2, 0.85, false) })
        assert(correlated.contains("Parlay:")) { "8 slip diabaikan" }
        assert(correlated.contains("saling terkait")) { "pola leg berkorelasi tidak ditangkap" }
        println()
        println(correlated.lines().first { it.startsWith("Parlay:") })
    }

    // ------------------------------------------------ teks yang bikin crash

    /**
     * The app crashed on "Ganti market". The label interpolated the percentage into
     * the string and then called format() on the result, so format() met the bare
     * "%" in "84% · minimal" and threw UnknownFormatConversionException.
     *
     * Moved out of the composable precisely so it can be run here.
     */
    @Test
    fun theSwapLabelDoesNotThrowOnItsOwnPercentSign() {
        val option = MarketOption("Over 1.5", 0.84, "", "Total Gol")
        val plain = priceLabel(option, 0.0)
        assert(plain == "84% · minimal 1.19") { "teks salah: $plain" }

        val good = priceLabel(option, 1.70)
        assert(good == "84% · minimal 1.19 · +43%") { "teks salah: $good" }

        val bad = priceLabel(option, 1.10)
        assert(bad == "84% · minimal 1.19 · -8%") { "teks salah: $bad" }
        println()
        println("Tanpa odds : $plain")
        println("Odds 1,70  : $good")
        println("Odds 1,10  : $bad")
    }

    /**
     * The same mistake was made twice — once in a test months ago, once in the code
     * that shipped. Checked mechanically rather than by eye.
     */
    @Test
    fun noSourceMixesAnInterpolatedPercentWithFormat() {
        val root = java.io.File("src/main/java/com/skorsnap/app")
        assert(root.isDirectory) { "sumber tidak ditemukan di ${root.absolutePath}" }
        val offenders = root.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                // A literal "%" followed by a space or the string's end is not a
                // format specifier, and format() rejects it at runtime.
                Regex("""\"[^"\n]*\$\{[^}]*\}%[^"\n]*\"\s*\n?\s*\.format\(""")
                    .findAll(file.readText())
                    .map { "${file.name}: ${it.value.take(70)}" }
            }
            .toList()
        assert(offenders.isEmpty()) { "teks ini akan crash saat dijalankan:\n" + offenders.joinToString("\n") }
        println("Tidak ada teks yang menyisipkan %% lalu memanggil format() — pola yang bikin crash.")
    }

    // ------------------------------------------------ mode corner babak 1

    @Test
    fun theFocusedModeReturnsExactlyTheOnePair() {
        val m = Grid.firstHalfCornerMarkets(2.8, 2.4)
        assert(m.size == 2) { "seharusnya dua baris, dapat ${m.size}" }
        assert(m.map { it.name } == listOf("Corner babak 1 Over 4.5", "Corner babak 1 Under 4.5"))
        assert(m.all { it.group == "Corner Babak 1" })
        assert(abs(m[0].prob + m[1].prob - 1.0) < 1e-9) { "dua sisi tidak berjumlah 1,0" }
        println()
        println("Corner babak 1 %.1f + %.1f → Over 4.5 %d%%, Under 4.5 %d%%."
            .format(2.8, 2.4, m[0].percent, m[1].percent))
    }

    /**
     * The counts handed in are already first-half, so applying the 45% split again
     * would halve a number the model was told to give in halves.
     */
    @Test
    fun theFirstHalfCountsAreNotHalvedTwice() {
        val direct = Grid.firstHalfCornerMarkets(2.7, 2.3)
        val full = Grid.cornerMarkets(6.0, 5.1)
        val fullFirstHalf = full.first { it.name == "Corner babak 1 Over 4.5" }
        assert(abs(direct[0].prob - fullFirstHalf.prob) < 0.06) {
            "mode khusus ${direct[0].percent}% vs mode corner umum ${fullFirstHalf.percent}%"
        }
        println("Mode khusus %d%% vs corner umum %d%% — sepadan, tidak dibagi dua kali."
            .format(direct[0].percent, fullFirstHalf.percent))
    }

    @Test
    fun theFocusedModeDoesNotLeakOtherMarkets() {
        val m = match(0.5).copy(mode = Mode.CORNER_1H, xgHome = 2.8, xgAway = 2.4, markets = emptyList())
        val filled = Grid.fill(m)
        assert(filled.markets.size == 2) { "market lain ikut masuk: ${filled.markets.size}" }
        println("Satu pasaran diminta, dua angka dikembalikan — tidak ada 51 baris.")
    }

    @Test
    fun theModelIsToldToUseFirstHalfNumbers() {
        val prompt = Analyst.CORNER_1H_MARKETS
        assert(prompt.contains("BABAK PERTAMA")) { "babak pertama tidak ditegaskan" }
        assert(prompt.contains("4.5"))
        assert(prompt.contains("stats_missing")) { "tidak menyuruh mengaku kalau datanya tidak ada" }
        assert(prompt.contains("45%")) { "tidak memberi cara aman kalau cuma ada angka laga penuh" }
        println("Prompt menegaskan babak pertama, dan menyuruh mengaku kalau datanya tidak ada.")
    }

    // ------------------------------------------------ cara berpikir

    /**
     * The prompt teaches a shrinkage rule with worked examples. If the arithmetic in
     * those examples is wrong the model learns the wrong rule, and nothing at
     * runtime would ever catch it.
     */
    @Test
    fun theShrinkageExamplesInThePromptAreArithmeticallyRight() {
        fun shrunk(k: Int, n: Int) = Math.round((k + 2.0) / (n + 4.0) * 100).toInt()
        val worked = listOf(Triple(5, 5, 78), Triple(8, 10, 71), Triple(3, 4, 63))
        worked.forEach { (k, n, expected) ->
            assert(shrunk(k, n) == expected) { "$k dari $n seharusnya $expected%, dapat ${shrunk(k, n)}%" }
            assert(Analyst.SYSTEM_PROMPT.contains("($k+2)/($n+4)")) {
                "contoh $k dari $n tidak ada di prompt"
            }
        }
        println()
        worked.forEach { (k, n, _) -> println("$k dari $n laga → ${shrunk(k, n)}%, bukan ${k * 100 / n}%") }
    }

    /** The rule only helps if it is actually in the instructions the model receives. */
    @Test
    fun theThinkingRulesReachTheModel() {
        val p = Analyst.SYSTEM_PROMPT
        listOf(
            "ANGKA KECIL BUKAN ANGKA PASTI",
            "TANYAKAN LAWANNYA SIAPA",
            "PIKIRKAN SEBABNYA",
            "LAWAN ANGKA ITU SENDIRI",
            "PATOKAN NORMAL",
        ).forEach { assert(p.contains(it)) { "aturan hilang: $it" } }
        assert(p.contains("\"risks\"")) { "model tidak disuruh menulis alasan keraguan" }
        println("Lima aturan penalaran ada di instruksi yang benar-benar dikirim.")
    }

    @Test
    fun theDoubtsSurviveTheRoundTrip() {
        val json = """
        {"home":"A","away":"B","readable":true,"stats_seen":["corner 1H"],"stats_missing":[],
         "prob_home":0.4,"prob_draw":0.3,"prob_away":0.3,"xg_home":2.6,"xg_away":2.2,
         "markets":[{"name":"Corner babak 1 Over 4.5","prob":0.62,"why":"tempo tinggi","group":"Corner Babak 1"}],
         "risks":["rata-rata cuma dari 4 laga","angka dikumpulkan melawan tim promosi"],
         "pick":"Corner babak 1 Over 4.5","pick_prob":0.62,"confidence":"sedang",
         "confidence_why":"susunan pemain belum ada"}
        """.trimIndent()
        val m = Analyst("k").parse(json)
        assert(m.risks.size == 2) { "alasan keraguan hilang: ${m.risks}" }
        assert(m.risks.first().contains("4 laga"))
        println("Dua alasan keraguan terbaca dan siap ditampilkan: ${m.risks}")
    }

    @Test
    fun theBiggerThinkingBudgetStillLeavesRoomForTheAnswer() {
        val room = Analyst.MAX_OUTPUT_TOKENS - Analyst.THINKING_BUDGET
        assert(Analyst.THINKING_BUDGET >= 16384) { "ruang berpikir tidak dinaikkan" }
        assert(room >= 30000) { "sisa untuk JSON cuma $room token" }
        println("Berpikir ${Analyst.THINKING_BUDGET} token, sisa $room untuk jawabannya.")
    }

    // ------------------------------------------------ keraguan harus menggerakkan angka

    /**
     * The failure this addresses: the model wrote "cup tie, cautious opening" and
     * "away average padded against bottom sides", then recommended Over 4.5 at 72%
     * anyway — the same number the raw stats gave. The doubts were decoration.
     *
     * Structured output is generated in the order the schema declares, so the
     * doubts are now declared before the probabilities that should move because of
     * them. If that ordering is lost, the fix is lost with it.
     */
    @Test
    fun theDoubtsAreWrittenBeforeTheNumbersTheyShouldMove() {
        val order = Analyst.RESPONSE_SCHEMA.optJSONArray("propertyOrdering")!!
        val at = (0 until order.length()).associateBy({ order.optString(it) }, { it })
        listOf("first_read", "risks", "risk_side", "adjustment").forEach { field ->
            assert(at.containsKey(field)) { "$field tidak ada di urutan" }
            listOf("prob_home", "markets", "pick", "pick_prob").forEach { later ->
                assert(at[field]!! < at[later]!!) { "$field ditulis setelah $later" }
            }
        }
        println()
        println("Urutan: ${(0 until order.length()).joinToString(" → ") { order.optString(it) }.take(120)}…")
    }

    @Test
    fun theChainIsRequiredNotOptional() {
        val required = Analyst.RESPONSE_SCHEMA.optJSONArray("required")!!
        val names = (0 until required.length()).map { required.optString(it) }
        listOf("first_read", "risks", "risk_side", "adjustment").forEach {
            assert(it in names) { "$it boleh dikosongkan — keraguannya jadi opsional" }
        }
        println("Empat langkah penalaran wajib diisi, bukan opsional.")
    }

    @Test
    fun theHardRulesAboutMovingTheNumberAreStated() {
        val p = Analyst.SYSTEM_PROMPT
        assert(p.contains("minimal 8 poin")) { "tidak ada kewajiban menggeser angka" }
        assert(p.contains("di bawah 55%")) { "tidak ada aturan membatalkan rekomendasi" }
        assert(p.contains("SETELAH digeser")) { "tidak ditegaskan angka mana yang dipakai" }
        println("Aturan keras ada: geser minimal 8 poin, batal kalau turun di bawah 55%.")
    }

    /**
     * The user asked for strength gaps and competition type to count. They are
     * reasoning, not data — so they are allowed, while inventing numbers from
     * memory stays banned.
     */
    @Test
    fun structuralReasoningIsAllowedButInventedNumbersAreNot() {
        val p = Analyst.SYSTEM_PROMPT
        assert(p.contains("Jenis kompetisi")) { "jenis kompetisi tidak dipertimbangkan" }
        assert(p.contains("Jurang kekuatan")) { "beda kekuatan tim tidak dipertimbangkan" }
        assert(p.contains("peringkat FIFA")) { "tidak ada larangan mengarang peringkat" }
        assert(p.contains("rekor pertemuan")) { "tidak ada larangan mengarang rekor" }
        println("Boleh menalar soal piala dan jurang kekuatan; dilarang mengarang peringkat FIFA.")
    }

    @Test
    fun theChainSurvivesTheRoundTrip() {
        val json = """
        {"home":"Sabah","away":"Selangor","readable":true,"stats_seen":["corner 1H"],
         "stats_missing":[],
         "first_read":"Corner 1H gabungan 5,88 → kesan awal Over 4.5 sekitar 72%.",
         "risks":["laga piala sistem gugur, awal cenderung tertutup",
                  "rata-rata tandang dikumpulkan melawan papan bawah"],
         "risk_side":"Under 4.5",
         "adjustment":"Digeser 14 poin ke 58%, jadi Over 4.5 tidak layak dipasang.",
         "prob_home":0.4,"prob_draw":0.3,"prob_away":0.3,"xg_home":2.6,"xg_away":2.2,
         "markets":[{"name":"Corner babak 1 Under 4.5","prob":0.58,"why":"tempo awal","group":"Corner Babak 1"}],
         "pick":"Corner babak 1 Under 4.5","pick_prob":0.58,"confidence":"sedang",
         "confidence_why":"susunan pemain belum ada"}
        """.trimIndent()
        val m = Analyst("k").parse(json)
        assert(m.firstRead.contains("72%")) { "kesan awal hilang" }
        assert(m.riskSide == "Under 4.5") { "arah risiko hilang: ${m.riskSide}" }
        assert(m.adjustment.contains("58%")) { "hasil geseran hilang" }
        println()
        println("Kesan awal 72% → digeser → ${m.riskSide} → dipakai ${Math.round(m.pickProb * 100)}%.")
    }

    // ------------------------------------------------ kesimpulan & analisis ulang

    /**
     * The page used to end on considerations, leaving the reader to draw the verdict
     * themselves from a screen full of caveats — the opposite of what the app is for.
     */
    @Test
    fun theAnswerIsWrittenLastSoItAccountsForEverythingAbove() {
        val order = Analyst.RESPONSE_SCHEMA.optJSONArray("propertyOrdering")!!
        val at = (0 until order.length()).associateBy({ order.optString(it) }, { it })
        listOf("action", "verdict").forEach { field ->
            listOf("first_read", "risks", "adjustment", "markets", "pick").forEach { earlier ->
                assert(at[field]!! > at[earlier]!!) { "$field ditulis sebelum $earlier" }
            }
        }
        val required = Analyst.RESPONSE_SCHEMA.optJSONArray("required")!!
        val names = (0 until required.length()).map { required.optString(it) }
        assert("action" in names && "verdict" in names) { "kesimpulan boleh dikosongkan" }
        println()
        println("Kesimpulan ditulis paling akhir, setelah semua pertimbangan — dan wajib ada.")
    }

    @Test
    fun theThreeWayDecisionIsSpelledOut() {
        val p = Analyst.SYSTEM_PROMPT
        listOf("\"pasang\"", "\"lewatkan\"", "\"butuh data\"").forEach {
            assert(p.contains(it)) { "pilihan $it tidak dijelaskan" }
        }
        assert(p.contains("Ini jawaban")) { "lewatkan tidak ditegaskan sebagai jawaban sah" }
        assert(p.contains("sekonkret mungkin")) { "permintaan data boleh kabur" }
        println("Tiga keputusan jelas, dan 'lewatkan' ditegaskan sebagai jawaban yang sah.")
    }

    @Test
    fun aMatchThatWantsMoreDataSaysSo() {
        val asking = match(0.6).copy(
            action = "butuh data",
            needMore = listOf("rata-rata corner babak 1 León khusus tandang"),
        )
        assert(asking.wantsMore && !asking.standDown)

        val skipping = match(0.6).copy(action = "lewatkan")
        assert(skipping.standDown && !skipping.wantsMore) { "lewatkan disalahartikan jadi butuh data" }

        val betting = match(0.6).copy(action = "pasang", verdict = "Pasang Under 4.5 di 62%.")
        assert(!betting.wantsMore && !betting.standDown)
        println("Tiga keadaan terbaca terpisah: pasang, lewatkan, butuh data.")
    }

    /**
     * A model shown its own conclusion tends to defend it, so the revision note has
     * to say outright that changing its mind is the point.
     */
    @Test
    fun theRevisionInvitesTheModelToChangeItsMind() {
        val p = Analyst.SYSTEM_PROMPT
        assert(p.isNotBlank())
        val note = Analyst("k").let { analyst ->
            val m = match(0.72).copy(
                firstRead = "kesan awal 76%",
                risks = listOf("laga piala"),
                adjustment = "digeser ke 68%",
                pick = "Corner babak 1 Over 4.5",
                needMore = listOf("corner 1H León tandang"),
            )
            // Same text the model receives on a second pass.
            analyst.javaClass.getDeclaredMethod("revisionNote", MatchPrediction::class.java)
                .apply { isAccessible = true }
                .invoke(analyst, m) as String
        }
        assert(note.contains("kesan awal 76%")) { "pembacaan lama tidak diserahkan kembali" }
        assert(note.contains("corner 1H León tandang")) { "permintaan data lama hilang" }
        assert(note.contains("berubah pikiran")) { "tidak diizinkan berubah pikiran" }
        assert(note.contains("jangan minta hal yang sama dua kali")) { "bisa memutar terus" }
        println()
        println("Catatan analisis ulang membawa pembacaan lama dan izin untuk berubah pikiran.")
    }

    // ------------------------------------------------ selera risiko & market luas

    private fun spread() = match(0.5).copy(
        pick = "Over 0.5",
        markets = listOf(
            MarketOption("Over 0.5", 0.94, "", "Total Gol"),
            MarketOption("Over 1.5", 0.78, "", "Total Gol"),
            MarketOption("Total gol 2-3", 0.61, "", "Multigol"),
            MarketOption("Tuan rumah menang & Over 2.5", 0.47, "", "Kombinasi Hasil + Total"),
            MarketOption("Skor 3-1", 0.06, "", "Lainnya"),
        ),
    )

    /**
     * The 68% floor is what made every recommendation a short price: the markets
     * that pay sit below it by construction. Lowering the floor opens them without
     * touching a single probability.
     */
    @Test
    fun aLowerFloorOpensTheMarketsThatActuallyPay() {
        val m = spread()
        assert(m.safePicks(Appetite.SAFE.floor).map { it.name } == listOf("Over 1.5"))
        assert(m.safePicks(Appetite.BALANCED.floor).map { it.name } ==
            listOf("Over 1.5", "Total gol 2-3"))
        assert(m.safePicks(Appetite.BOLD.floor).map { it.name } ==
            listOf("Over 1.5", "Total gol 2-3", "Tuan rumah menang & Over 2.5"))
        println()
        Appetite.entries.forEach {
            println("${it.label.padEnd(9)} batas ${Math.round(it.floor * 100)}% → " +
                m.safePicks(it.floor).joinToString { o -> "${o.name} ${o.percent}%" })
        }
    }

    /** The ceiling never moves: above 92% the price is not worth staking. */
    @Test
    fun noAppetiteEverRecommendsAnUnbettablePrice() {
        val m = spread()
        Appetite.entries.forEach {
            assert(m.safePicks(it.floor).none { o -> o.prob > 0.92 }) {
                "${it.label} merekomendasikan odds di bawah 1,09"
            }
        }
        println("Tidak ada selera risiko yang merekomendasikan 94% — odds-nya 1,06.")
    }

    @Test
    fun theEnforcedPickFollowsTheChosenFloor() {
        val m = spread().copy(pick = "Skor 3-1", pickProb = 0.06)
        val safe = Analyst("k").enforceSafePick(m, Appetite.SAFE.floor)
        val bold = Analyst("k").enforceSafePick(m, Appetite.BOLD.floor)
        assert(safe.pick == "Over 1.5") { "aman malah memilih ${safe.pick}" }
        assert(bold.pick == "Over 1.5") { "berani seharusnya tetap ambil yang tertinggi dulu" }
        assert(safe.pickCorrected && bold.pickCorrected)
        println("Pilihan 6% diganti; batas mana pun tidak akan membiarkannya lewat.")
    }

    /**
     * The probabilities must not move with appetite. Boldness is about which market
     * is recommended, not about inflating numbers to justify one.
     */
    @Test
    fun appetiteNeverChangesTheProbabilitiesThemselves() {
        val m = spread()
        val before = m.markets.map { it.prob }
        Appetite.entries.forEach { m.safePicks(it.floor) }
        assert(m.markets.map { it.prob } == before) { "angka peluang ikut berubah" }
        println("Angka peluangnya identik di semua selera risiko — yang berubah cuma pilihannya.")
    }

    @Test
    fun theWiderCatalogueIsActuallyDerived() {
        val names = Grid.matchMarkets(1.7, 1.2, 0.48, 0.26, 0.26).map { it.name }
        listOf(
            "Total gol 2-3", "Total gol 2-4", "Total gol 1-3", "Total gol 3-5",
            "Tuan rumah menang & Over 2.5", "Tandang menang & Over 2.5",
            "Tuan rumah menang & BTTS Ya", "1X & BTTS Ya",
        ).forEach { assert(it in names) { "market baru tidak ikut dihitung: $it" } }
        assert("Multigol" in Markets.order) { "Multigol tidak punya judul kelompok" }
        println()
        println("Katalog jadi ${names.size} market, termasuk Multigol dan Menang & Over 2.5.")
    }

    @Test
    fun multigoalBandsAreConsistentWithTheOverLines() {
        val m = Grid.matchMarkets(1.7, 1.2, 0.48, 0.26, 0.26).associateBy { it.name }
        val twoToThree = m["Total gol 2-3"]!!.prob
        val over15 = m["Over 1.5"]!!.prob
        assert(twoToThree < over15) { "2-3 tidak boleh lebih besar dari Over 1.5" }
        assert(m["Total gol 2-4"]!!.prob > twoToThree) { "2-4 harus mencakup 2-3" }
        println("Total gol 2-3 %d%% < 2-4 %d%% < Over 1.5 %d%% — bandnya konsisten."
            .format(m["Total gol 2-3"]!!.percent, m["Total gol 2-4"]!!.percent, m["Over 1.5"]!!.percent))
    }

    // ------------------------------------------------ bias satu arah

    /**
     * The record that forced this: twelve of thirteen first-half corner picks were
     * Under, Under 4.5 went nought from five, and the group summary alone would have
     * said only "come down 45 points" — leaving the model pricing Under at 55%
     * instead of 70%, still Under, still wrong.
     */
    @Test
    fun aOneSidedLosingHabitIsNamedNotJustCalledOverconfident() {
        fun bet(name: String, won: Boolean) = match(0.70).copy(
            markets = listOf(MarketOption(name, 0.70, "", "Corner Babak 1")),
            pick = name,
        ).marking(name to verdict(won))

        val record = List(5) { bet("Corner babak 1 Under 4.5", false) } +
            List(4) { bet("Corner babak 1 Under 5.5", false) } +
            List(3) { bet("Corner babak 1 Under 5.5", true) } +
            List(1) { bet("Corner babak 1 Over 4.5", false) }

        val brief = Coach.brief(record)
        assert(brief.contains("PERHATIAN")) { "kebiasaan satu arah tidak ditandai:\n$brief" }
        assert(brief.contains("arahnya Under")) { "arah yang salah tidak disebut" }
        assert(brief.contains("terlalu rendah")) { "tidak bilang perkiraannya kerendahan" }
        assert(brief.contains("periksa sisi Over")) { "tidak menyuruh melihat sisi lawan" }
        println()
        println(brief.lines().first { it.contains("PERHATIAN") }.take(220))
    }

    /** The same warning has to point the other way when Over is the losing habit. */
    @Test
    fun theWarningPointsTheRightWayForEitherSide() {
        fun bet(name: String, won: Boolean) = match(0.70).copy(
            markets = listOf(MarketOption(name, 0.70, "", "Total Gol")),
            pick = name,
        ).marking(name to verdict(won))
        val record = List(8) { bet("Over 2.5", false) } + List(2) { bet("Over 2.5", true) }
        val brief = Coach.brief(record)
        assert(brief.contains("arahnya Over")) { "arah tidak terbaca" }
        assert(brief.contains("terlalu tinggi")) { "seharusnya bilang perkiraannya ketinggian" }
        assert(brief.contains("periksa sisi Under"))
        println("Kalau Over yang kalah terus, dia disuruh menurunkan perkiraan — bukan menaikkan.")
    }

    /** A side that is merely unlucky, or a balanced record, must not be flagged. */
    @Test
    fun aBalancedOrWinningRecordIsLeftAlone() {
        fun bet(name: String, won: Boolean) = match(0.70).copy(
            markets = listOf(MarketOption(name, 0.70, "", "Corner")),
            pick = name,
        ).marking(name to verdict(won))

        val mixed = List(5) { bet("Total corner Over 8.5", false) } +
            List(5) { bet("Total corner Under 8.5", false) }
        assert(!Coach.brief(mixed).contains("PERHATIAN")) { "dua arah seimbang malah ditandai" }

        val winning = List(7) { bet("Total corner Over 8.5", true) } +
            List(3) { bet("Total corner Over 8.5", false) }
        assert(!Coach.brief(winning).contains("PERHATIAN")) { "sisi yang menang ikut ditandai" }
        println("Catatan seimbang atau menang tidak ikut ditandai — cuma kebiasaan yang merugi.")
    }

    // ------------------------------------------------ patokan corner yang salah

    /**
     * The old anchor said first-half Over 4.5 was about 45%, which made Under look
     * like the default. Checked against the app's own distribution it is a coin
     * flip, and the record agreed with the maths rather than with the anchor.
     */
    @Test
    fun theFirstHalfCornerAnchorMatchesTheAppsOwnMaths() {
        val cases = listOf(4.0 to 37, 4.7 to 49, 5.0 to 54, 6.0 to 68)
        cases.forEach { (combined, expected) ->
            val over = Grid.firstHalfCornerMarkets(combined / 2, combined / 2)
                .first { it.name.contains("Over") }
            assert(abs(over.percent - expected) <= 1) {
                "gabungan $combined seharusnya sekitar $expected%, hitungan ${over.percent}%"
            }
            assert(Analyst.SYSTEM_PROMPT.contains("$expected%")) {
                "tabel di prompt tidak memuat $expected%"
            }
        }
        println()
        println("Tabel patokan di prompt cocok dengan sebaran yang dipakai aplikasi.")
    }

    @Test
    fun theOldWrongAnchorIsGone() {
        val p = Analyst.SYSTEM_PROMPT
        assert(!p.contains("corner babak 1 Over 4.5 +-45%")) { "patokan lama yang salah masih ada" }
        assert(p.contains("LEMPARAN KOIN")) { "tidak ditegaskan ini lemparan koin" }
        assert(p.contains("JANGAN memilih Under 4.5")) { "tidak ada rem untuk Under 4.5" }
        println("Patokan lama yang bikin condong ke Under sudah dicabut.")
    }

    @Test
    fun doubtsMustBeRaisedInBothDirections() {
        val p = Analyst.SYSTEM_PROMPT
        assert(p.contains("KETINGGIAN")) { "tidak diminta alasan arah atas" }
        assert(p.contains("KERENDAHAN")) { "tidak diminta alasan arah bawah" }
        assert(p.contains("menebak Under terus")) { "jebakan satu arah tidak dijelaskan" }
        println("Keraguan wajib dua arah — itu yang dulu bikin 12 dari 13 pilihan jadi Under.")
    }

    // ------------------------------------------------ ambil data otomatis

    @Test
    fun fixturesAreReadFromTheFeedShape() {
        val json = JSONObject("""
        {"results":2,"response":[
          {"fixture":{"id":1198,"date":"2026-09-05T19:30:00+00:00"},
           "league":{"id":72,"name":"Serie B","country":"Brazil","season":2026},
           "teams":{"home":{"id":1,"name":"Náutico"},"away":{"id":2,"name":"Botafogo-SP"}}},
          {"fixture":{"id":1199,"date":"2026-09-05T21:00:00+00:00"},
           "league":{"id":39,"name":"Premier League","country":"England","season":2026},
           "teams":{"home":{"id":3,"name":"Arsenal"},"away":{"id":4,"name":"Chelsea"}}}
        ]}
        """.trimIndent())
        val out = Football.parseFixtures(json)
        assert(out.size == 2) { "jadwal tidak terbaca: ${out.size}" }
        assert(out[0].title == "Náutico vs Botafogo-SP")
        assert(out[0].where == "Brazil · Serie B")
        assert(out[0].season == 2026 && out[0].homeId == 1L)
        assert(out[0].kickoff == "2026-09-05 19:30") { "jam salah: ${out[0].kickoff}" }
        println()
        println("Jadwal terbaca: ${out.joinToString { "${it.title} (${it.where})" }}")
    }

    /**
     * The provider's documentation could not be reached from the build environment,
     * so the shape here is inferred. A wrong guess must degrade rather than crash:
     * a fixture missing its teams is dropped, never shown blank.
     */
    @Test
    fun aMalformedFeedDegradesInsteadOfCrashing() {
        val ragged = JSONObject("""
        {"response":[
          {"fixture":{"id":1}},
          {"league":{"name":"X"}},
          {"fixture":{"id":3,"date":"2026-09-05T10:00:00+00:00"},
           "teams":{"home":{"id":9,"name":"Ada"},"away":{"id":10,"name":"Lawan"}}}
        ]}
        """.trimIndent())
        val out = Football.parseFixtures(ragged)
        assert(out.size == 1) { "baris rusak ikut lolos: ${out.map { it.title }}" }
        assert(out.first().home == "Ada")
        assert(Football.parseFixtures(JSONObject("{}")).isEmpty())
        println("Baris tanpa nama tim dibuang, bukan ditampilkan kosong.")
    }

    @Test
    fun pricesAreFlattenedWithTheirMarketNames() {
        val json = JSONObject("""
        {"response":[{"bookmakers":[{"name":"Bet365","bets":[
          {"name":"Match Winner","values":[{"value":"Home","odd":"1.96"},{"value":"Draw","odd":"3.10"}]},
          {"name":"Goals Over/Under","values":[{"value":"Over 2.5","odd":"2.25"},{"value":"rusak","odd":"-"}]}
        ]}]}]}
        """.trimIndent())
        val out = Football.parseOdds(json)
        assert(out["Match Winner: Home"] == 1.96) { "harga tidak terbaca: $out" }
        assert(out["Goals Over/Under: Over 2.5"] == 2.25)
        assert(out.none { it.key.contains("rusak") }) { "harga tidak sah ikut masuk" }
        println("Harga terbaca: ${out.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    @Test
    fun textStatsAreFarCheaperThanTheSameNumbersAsAnImage() {
        // A screenshot is tiled at 258 tokens per 768x768 patch; a long capture runs
        // to tens of thousands. The same numbers as text are a rounding error.
        val statsBlock = "Main 26: menang 10, seri 8, kalah 8\nRata-rata gol: cetak 1.2, " +
            "kebobolan 1.1\nGol per menit: 0-15: 2, 16-30: 3, 31-45: 6"
        val roughTokens = statsBlock.length / 4
        assert(roughTokens < 200) { "blok statistik terlalu besar: $roughTokens token" }
        println()
        println("Statistik sebagai teks ≈ $roughTokens token, versus ~30.000 untuk screenshot.")
    }

    /**
     * Verified against a real free key rather than assumed: today's fixtures and
     * odds are served, season aggregates and past dates are not. The brief has to
     * stay useful under those limits instead of printing an empty heading.
     */
    @Test
    fun theBriefHoldsUpOnAFreePlanWithNoSeasonStats() {
        val prices = mapOf(
            "Match Winner: Home" to 1.96,
            "Match Winner: Draw" to 3.10,
            "Match Winner: Away" to 4.10,
            "Goals Over/Under: Over 2.5" to 2.25,
        )
        val brief = Football.marketBrief(prices)
        assert(brief.contains("47%")) { "pasar tidak dihitung tanpa margin:\n$brief" }
        assert(brief.contains("30%") && brief.contains("23%"))
        assert(brief.contains("titik awal")) { "tidak diberi tahu cara memakai harga" }
        assert(!brief.contains("STATISTIK MUSIM")) { "menampilkan bagian yang kosong" }
        println()
        println(brief.lines().first { it.contains("pasar menilai") })
    }

    @Test
    fun aFixtureWithoutPricesSaysSoRatherThanGoingBlank() {
        val brief = Football.marketBrief(emptyMap())
        assert(brief.contains("tidak tersedia")) { "diam saja saat harga tidak ada: $brief" }
        println("Tanpa harga, briefnya bilang apa adanya — bukan kosong tanpa keterangan.")
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
