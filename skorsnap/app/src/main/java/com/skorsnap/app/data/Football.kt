package com.skorsnap.app.data

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fixtures, team statistics and prices from API-Football.
 *
 * The point is not that the app can now predict on its own — it is that the
 * numbers arrive as numbers. A screenshot costs roughly thirty thousand tokens and
 * can be misread; the same statistics as text cost about a thousand and cannot.
 *
 * What this cannot supply is the first-half corner split, which no free provider
 * carries and which is exactly what the user's corner strategy runs on. So this
 * supplements the screenshots rather than replacing them, and the analysis says
 * which numbers came from where.
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
     * Both teams' season statistics, written out for the model to read.
     *
     * Costs two requests. The minute bands matter more than the totals here: a side
     * that concedes most of its goals after the break is a different bet in the
     * first half from one that starts slowly, and that split is invisible in a
     * season average.
     */
    suspend fun statsBrief(fixture: Fixture): String = withContext(Dispatchers.IO) {
        val home = teamStats(fixture.homeId, fixture.leagueId, fixture.season)
        val away = teamStats(fixture.awayId, fixture.leagueId, fixture.season)
        buildString {
            append("STATISTIK RESMI DARI API-FOOTBALL (bukan dari gambar, tidak perlu dibaca ulang).\n")
            append("Liga: ${fixture.where}, musim ${fixture.season}.\n\n")
            append("TUAN RUMAH — ${fixture.home}\n")
            append(home)
            append("\nTANDANG — ${fixture.away}\n")
            append(away)
            append(
                "\nCATATAN PENTING: sumber ini TIDAK memuat statistik sepak pojok sama " +
                    "sekali, dan tidak memuat pecahan babak pertama untuk corner. Kalau " +
                    "analisis ini soal corner dan tidak ada gambar yang memuatnya, katakan " +
                    "di \"need_more\" bahwa kamu butuh statistik corner, dan jangan " +
                    "mengarang angkanya."
            )
        }
    }

    private suspend fun teamStats(teamId: Long, leagueId: Long, season: Int): String {
        val json = get("teams/statistics?team=$teamId&league=$leagueId&season=$season")
        val r = json.optJSONObject("response") ?: return "  (statistik tidak tersedia)\n"
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
