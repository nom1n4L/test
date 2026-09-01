package com.skorsnap.app

import com.skorsnap.app.data.Analyst
import com.skorsnap.app.data.MarketOption
import com.skorsnap.app.data.MatchPrediction
import com.skorsnap.app.data.Parlay
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
