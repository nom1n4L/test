package com.skorsnap.app.data

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Fixtures, team statistics and prices from API-Football.
 *
 * The point is not that the app can now predict on its own — it is that the
 * numbers arrive as numbers. A screenshot costs roughly thirty thousand tokens and
 * can be misread; the same statistics as text cost about a thousand and cannot.
 *
 * Measured against a real free key rather than assumed, because the documentation
 * is unreachable from here and the limits turned out to matter: today's fixtures
 * and odds are served, while season aggregates, past dates and a team's recent
 * matches are not. Per-fixture statistics do include corners, but with no way to
 * enumerate a team's history there is no route to an average — so screenshots stay
 * essential for corners, and the brief says so rather than inventing a number.
 *
 * The response shapes are handled defensively throughout: the documentation is
 * behind a block from here, so every field is read with a fallback and [probe]
 * exists to show what the API actually returned rather than leaving a silently
 * empty screen.
 */
class Football(private val apiKey: String) {

    class FootballException(message: String) : Exception(message)

    data class Fixture(
        val id: Long,
        val home: String,
        val away: String,
        val homeId: Long,
        val awayId: Long,
        val leagueId: Long,
        val leagueName: String,
        val country: String,
        val season: Int,
        val kickoff: String,
    ) {
        val title: String get() = "$home vs $away"
        val where: String get() = listOf(country, leagueName).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** Matches kicking off on a given date, newest leagues first. */
    suspend fun fixtures(date: String): List<Fixture> = withContext(Dispatchers.IO) {
        parseFixtures(get("fixtures?date=$date"))
    }

    /**
     * Everything this fixture's data can supply, written out for the model to read.
     *
     * On a paid plan that includes both sides' season aggregates. On the free plan
     * those come back "try from 2022 to 2024" and are skipped, leaving the market
     * prices — which are the more useful half anyway, and which the free plan does
     * serve.
     */
    suspend fun statsBrief(fixture: Fixture): String = withContext(Dispatchers.IO) {
        val prices = runCatching { odds(fixture.id) }.getOrDefault(emptyMap())
        val season = runCatching { teamStats(fixture.homeId, fixture.leagueId, fixture.season) }
            .getOrNull()
        val awaySeason = runCatching { teamStats(fixture.awayId, fixture.leagueId, fixture.season) }
            .getOrNull()

        buildString {
            append("DATA RESMI DARI API-FOOTBALL untuk laga ini. Angka di sini sudah pasti " +
                "benar — tidak perlu dibaca ulang dari gambar.\n")
            append("${fixture.home} vs ${fixture.away} — ${fixture.where}, ${fixture.kickoff}.\n\n")

            if (season != null || awaySeason != null) {
                append("STATISTIK MUSIM\n")
                season?.let { append("TUAN RUMAH — ${fixture.home}\n$it") }
                awaySeason?.let { append("TANDANG — ${fixture.away}\n$it") }
                append("\n")
            }

            append(marketBrief(prices))

            append(
                "\nYANG TIDAK ADA DI SUMBER INI: statistik sepak pojok, dan semua pecahan " +
                    "babak pertama. Kalau analisis ini soal corner dan tidak ada gambar yang " +
                    "memuat angkanya, tulis di \"need_more\" bahwa kamu butuh statistik " +
                    "corner babak 1. Jangan mengarang angkanya dari harga bandar."
            )
        }
    }

    /**
     * Season aggregates, where the plan allows them.
     *
     * The free plan answers "try from 2022 to 2024" for a current season, so this
     * returns null rather than an empty block: a paid plan gets real numbers and a
     * free one simply gets a brief without this section, instead of a heading with
     * nothing under it.
     */
    private suspend fun teamStats(teamId: Long, leagueId: Long, season: Int): String? {
        val json = get("teams/statistics?team=$teamId&league=$leagueId&season=$season")
        (json.opt("errors") as? JSONObject)?.takeIf { it.length() > 0 }?.let { return null }
        val r = json.optJSONObject("response")?.takeIf { it.length() > 0 } ?: return null
        val fixtures = r.optJSONObject("fixtures") ?: JSONObject()
        val goals = r.optJSONObject("goals") ?: JSONObject()
        val scored = goals.optJSONObject("for") ?: JSONObject()
        val conceded = goals.optJSONObject("against") ?: JSONObject()

        fun played(key: String) = fixtures.optJSONObject(key)?.optInt("total") ?: 0
        fun avg(side: JSONObject) = side.optJSONObject("average")?.optString("total").orEmpty()

        return buildString {
            append("  Main ${played("played")}: menang ${played("wins")}, ")
            append("seri ${played("draws")}, kalah ${played("loses")}\n")
            append("  Form terakhir: ${r.optString("form").takeLast(10)}\n")
            append("  Rata-rata gol: cetak ${avg(scored)}, kebobolan ${avg(conceded)}\n")
            minutes(scored)?.let { append("  Gol dicetak per menit: $it\n") }
            minutes(conceded)?.let { append("  Gol kebobolan per menit: $it\n") }
            r.optJSONObject("clean_sheet")?.optInt("total")?.let { append("  Clean sheet: $it\n") }
            r.optJSONObject("failed_to_score")?.optInt("total")?.let {
                append("  Gagal cetak gol: $it\n")
            }
        }
    }

    /**
     * The goal distribution across the match, which is where first-half value hides.
     */
    private fun minutes(side: JSONObject): String? {
        val m = side.optJSONObject("minute") ?: return null
        val bands = listOf("0-15", "16-30", "31-45", "46-60", "61-75", "76-90")
        val parts = bands.mapNotNull { band ->
            val total = m.optJSONObject(band)?.opt("total")?.takeIf { it != JSONObject.NULL }
            total?.let { "$band: $it" }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * Prices for one fixture, market name to decimal odds.
     *
     * Only the first bookmaker returned is used. Shopping between books is a real
     * edge, but reading twenty of them costs twenty times the quota; one price is
     * enough to tell whether a bet clears its break-even.
     */
    suspend fun odds(fixtureId: Long): Map<String, Double> = withContext(Dispatchers.IO) {
        parseOdds(get("odds?fixture=$fixtureId"))
    }

    /**
     * One cheap call that reports what came back, field names and all.
     *
     * Written because the documentation could not be reached from the build
     * environment: rather than guessing at a shape and leaving the user with an
     * empty list and no explanation, this shows whether the key works, how much
     * quota is left, and what the payload actually looks like.
     */
    suspend fun probe(date: String): String = withContext(Dispatchers.IO) {
        val json = get("fixtures?date=$date")
        val errors = json.opt("errors")
        if (errors is JSONObject && errors.length() > 0) {
            throw FootballException("API menolak: $errors")
        }
        val results = json.optInt("results")
        val first = json.optJSONArray("response")?.optJSONObject(0)
        buildString {
            append("Kunci berfungsi. $results pertandingan pada $date.\n")
            append("Sisa kuota hari ini: ${lastRemaining ?: "tidak dilaporkan"}.\n")
            if (first != null) {
                append("Contoh yang terbaca: ")
                val teams = first.optJSONObject("teams")
                append(
                    "${teams?.optJSONObject("home")?.optString("name")} vs " +
                        "${teams?.optJSONObject("away")?.optString("name")}"
                )
            } else {
                append("Tidak ada pertandingan di tanggal itu — coba tanggal lain.")
            }
            append(
                "\n\nCatatan paket gratis: hanya jadwal HARI INI yang bisa diambil, dan " +
                    "statistik musim berjalan tidak dibuka. Yang dipakai aplikasi ini dari " +
                    "sumber tersebut adalah daftar pertandingan dan harga bandar."
            )
        }
    }

    /** Requests left on today's free allowance, as the API last reported it. */
    @Volatile
    var lastRemaining: String? = null
        private set

    private fun get(path: String): JSONObject {
        if (apiKey.isBlank()) throw FootballException("Kunci API-Football belum diisi.")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$HOST/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 25_000
                readTimeout = 45_000
                setRequestProperty("x-apisports-key", apiKey)
            }
            val code = conn.responseCode
            conn.getHeaderField("x-ratelimit-requests-remaining")?.let { lastRemaining = it }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            // A suspended account answers 200 with the reason in the body, so the
            // status code alone would report success and the screen would show raw
            // JSON. It is also the one failure the user can actually act on.
            if (text.contains("suspended", ignoreCase = true)) {
                throw FootballException(
                    "Akun API-Football-mu sedang disuspend, bukan kuncinya yang salah. " +
                        "Buka dashboard.api-football.com dan pakai tombol Chat di pojok " +
                        "kanan bawah untuk minta diaktifkan lagi — sebutkan ini dipakai " +
                        "untuk aplikasi pribadi, satu pengguna. Sementara itu aplikasi " +
                        "tetap jalan penuh lewat screenshot."
                )
            }
            if (code == 401 || code == 403) {
                throw FootballException("Kunci API-Football ditolak (HTTP $code).")
            }
            if (code == 429) {
                throw FootballException("Kuota API-Football hari ini habis. Coba lagi besok.")
            }
            if (code !in 200..299) throw FootballException("Gagal ambil data (HTTP $code).")
            return runCatching { JSONObject(text) }.getOrElse {
                throw FootballException("Jawaban API tidak berbentuk JSON.")
            }
        } catch (e: FootballException) {
            throw e
        } catch (e: Exception) {
            throw FootballException(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val HOST = "https://v3.football.api-sports.io"

        /**
         * Separated from the request so it can be tested without a key or a network.
         *
         * The provider's documentation is unreachable from the build environment, so
         * this shape is inferred rather than confirmed. Every field falls back
         * rather than throwing, a fixture missing a home team is dropped instead of
         * appearing blank, and [probe] reports what actually arrived — the failure
         * mode to avoid is a silently empty list that looks like "no matches today".
         */
        internal fun parseFixtures(json: JSONObject): List<Fixture> {
            val arr = json.optJSONArray("response") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val fx = o.optJSONObject("fixture") ?: return@mapNotNull null
                val league = o.optJSONObject("league") ?: JSONObject()
                val teams = o.optJSONObject("teams") ?: JSONObject()
                val home = teams.optJSONObject("home") ?: JSONObject()
                val away = teams.optJSONObject("away") ?: JSONObject()
                val name = home.optString("name")
                if (name.isBlank()) return@mapNotNull null
                Fixture(
                    id = fx.optLong("id"),
                    home = name,
                    away = away.optString("name"),
                    homeId = home.optLong("id"),
                    awayId = away.optLong("id"),
                    leagueId = league.optLong("id"),
                    leagueName = league.optString("name"),
                    country = league.optString("country"),
                    season = league.optInt("season"),
                    kickoff = fx.optString("date").take(16).replace("T", " "),
                )
            }
        }

        /**
         * The market's own view, with the bookmaker's margin taken out.
         *
         * This is the single most useful thing the free plan can supply. Prices are a
         * consensus forecast that is hard to beat — SofaScore's published "AI"
         * probabilities land within a few points of them — so handing them over as a
         * starting point is more honest, and more accurate, than asking a model to
         * guess a base rate from a team name.
         *
         * They are labelled as the market's view rather than as evidence about the
         * teams, because a price is what other people believe, not what happened.
         */
        fun marketBrief(prices: Map<String, Double>): String {
            if (prices.isEmpty()) return "HARGA BANDAR: tidak tersedia untuk laga ini.\n"
            val oneXTwo = listOf("Match Winner: Home", "Match Winner: Draw", "Match Winner: Away")
                .mapNotNull { prices[it] }
            return buildString {
                append("HARGA BANDAR (pasar taruhan, margin belum dibuang):\n")
                prices.entries.take(30).forEach { (name, price) ->
                    append("  $name = $price\n")
                }
                if (oneXTwo.size == 3) {
                    val raw = oneXTwo.map { 1.0 / it }
                    val sum = raw.sum()
                    val fair = raw.map { (it / sum * 100).roundToInt() }
                    append(
                        "\nSetelah margin dibuang, pasar menilai: tuan rumah ${fair[0]}%, " +
                            "seri ${fair[1]}%, tandang ${fair[2]}%.\n"
                    )
                }
                append(
                    "\nCARA MEMAKAI HARGA INI: pasar adalah perkiraan gabungan banyak orang dan " +
                        "sulit dikalahkan, jadi jadikan titik awal, bukan lawan. Kalau angkamu " +
                        "jauh berbeda dari pasar, kamu harus punya alasan konkret dari gambar — " +
                        "kalau tidak punya, dekatkan angkamu ke pasar. Tapi ingat harga sudah " +
                        "memuat margin bandar, jadi persentase mentah dari odds selalu " +
                        "kebesaran.\n"
                )
            }
        }

        /** Market name to price, flattened from the first bookmaker in the payload. */
        internal fun parseOdds(json: JSONObject): Map<String, Double> {
            val book = json.optJSONArray("response")?.optJSONObject(0)
                ?.optJSONArray("bookmakers")?.optJSONObject(0) ?: return emptyMap()
            val bets = book.optJSONArray("bets") ?: JSONArray()
            val out = LinkedHashMap<String, Double>()
            for (i in 0 until bets.length()) {
                val bet = bets.optJSONObject(i) ?: continue
                val label = bet.optString("name")
                val values = bet.optJSONArray("values") ?: continue
                for (j in 0 until values.length()) {
                    val v = values.optJSONObject(j) ?: continue
                    val price = v.optString("odd").toDoubleOrNull() ?: continue
                    out["$label: ${v.optString("value")}"] = price
                }
            }
            return out
        }
    }
}
