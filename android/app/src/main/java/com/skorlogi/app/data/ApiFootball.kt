package com.skorlogi.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for API-Football v3 (v3.football.api-sports.io).
 *
 * This is the source that covers leagues the open archive does not — Liga 1 among
 * them — and publishes a fixture list that runs weeks ahead rather than days.
 *
 * Quota is the design constraint. A free key allows a small number of calls per
 * day, so everything here is shaped to ask for as much as possible per request:
 * a whole season of results comes back in one call, and a whole day of fixtures
 * across every league in another. Per-match endpoints are deliberately not used —
 * corner and card statistics would cost one request per fixture, which no free
 * quota can carry. Those markets keep coming from the archive instead.
 */
object ApiFootball {

    /**
     * The service sells the same API through two front doors, and a key from one
     * is rejected by the other with a message that reads like the key is invalid.
     * People sign up through whichever they land on, so the app accepts both and
     * works out which it has been given.
     */
    enum class KeyMode(val host: String) {
        /** dashboard.api-football.com — header `x-apisports-key`. */
        DIRECT("https://v3.football.api-sports.io"),

        /** rapidapi.com — headers `x-rapidapi-key` and `x-rapidapi-host`. */
        RAPIDAPI("https://api-football-v1.p.rapidapi.com/v3"),
    }

    private const val TIMEOUT_MS = 25_000

    class ApiException(message: String) : Exception(message)

    /** What the account is allowed to do, straight from the service. */
    data class Status(
        val account: String,
        val plan: String,
        val used: Int,
        val limitPerDay: Int,
    ) {
        val remaining: Int get() = (limitPerDay - used).coerceAtLeast(0)
    }

    private fun request(
        key: String,
        path: String,
        query: String = "",
        mode: KeyMode = KeyMode.DIRECT,
    ): JSONObject {
        if (key.isBlank()) throw ApiException("Kunci API belum diisi.")
        val url = "${mode.host}/$path" + if (query.isEmpty()) "" else "?$query"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                if (mode == KeyMode.RAPIDAPI) {
                    setRequestProperty("x-rapidapi-key", key)
                    setRequestProperty("x-rapidapi-host", "api-football-v1.p.rapidapi.com")
                } else {
                    setRequestProperty("x-apisports-key", key)
                }
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                ?: throw ApiException("Tidak ada balasan dari server.")

            if (code == 499 || code == 429) throw ApiException("Kuota harian habis.")
            // The service answers a rejected key with a bare status code, which
            // tells the user nothing about what to do next.
            if (code == 401 || code == 403) {
                throw ApiException(
                    "Kunci ditolak. Pastikan disalin utuh dari menu Profile di " +
                        "dashboard.api-football.com, tanpa spasi di ujung."
                )
            }
            if (code !in 200..299) throw ApiException("Server menolak (HTTP $code)")

            val json = JSONObject(body)
            // The service reports failures inside a 200 response. "errors" is an
            // empty array when all is well and an object describing the problem
            // when it is not.
            val errors = json.opt("errors")
            if (errors is JSONObject && errors.length() > 0) {
                val first = errors.keys().asSequence().firstOrNull()
                throw ApiException(first?.let { errors.optString(it) } ?: "Permintaan ditolak.")
            }
            return json
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Verifies a key and reports the quota attached to it, trying the direct front
     * door first and RapidAPI second. Returns the mode that worked so the rest of
     * the session can skip the guessing.
     */
    fun status(key: String): Pair<Status, KeyMode> {
        var firstFailure: ApiException? = null
        for (mode in KeyMode.entries) {
            try {
                val response = request(key, "status", mode = mode).optJSONObject("response")
                    ?: throw ApiException("Balasan tidak dikenali.")
                val requests = response.optJSONObject("requests")
                return Status(
                    account = response.optJSONObject("account")?.optString("email").orEmpty(),
                    plan = response.optJSONObject("subscription")?.optString("plan").orEmpty(),
                    used = requests?.optInt("current") ?: 0,
                    limitPerDay = requests?.optInt("limit_day") ?: 0,
                ) to mode
            } catch (e: ApiException) {
                if (firstFailure == null) firstFailure = e
            }
        }
        throw firstFailure
            ?: ApiException("Kunci tidak dikenali, baik lewat jalur langsung maupun RapidAPI.")
    }

    /** Every league the key can see, so the user can pick which to follow. */
    fun leagues(key: String, mode: KeyMode = KeyMode.DIRECT): List<ApiLeague> {
        val arr = request(key, "leagues", mode = mode).optJSONArray("response") ?: JSONArray()
        val out = ArrayList<ApiLeague>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val league = item.optJSONObject("league") ?: continue
            val country = item.optJSONObject("country")?.optString("name").orEmpty()
            // Seasons run newest last; the one flagged current is what we want.
            val seasons = item.optJSONArray("seasons")
            var current = -1
            if (seasons != null) {
                for (s in 0 until seasons.length()) {
                    val season = seasons.optJSONObject(s) ?: continue
                    if (season.optBoolean("current")) current = season.optInt("year")
                }
                if (current < 0 && seasons.length() > 0) {
                    current = seasons.optJSONObject(seasons.length() - 1)?.optInt("year") ?: -1
                }
            }
            out.add(
                ApiLeague(
                    id = league.optInt("id"),
                    name = league.optString("name"),
                    country = country,
                    type = league.optString("type"),
                    currentSeason = current,
                )
            )
        }
        return out
    }

    /**
     * A whole season of finished matches in one request.
     *
     * Half-time scores come back with the fixture, so first-half and second-half
     * markets survive this source. Corners and cards do not — see the class note.
     */
    fun seasonResults(
        key: String,
        leagueId: Int,
        season: Int,
        leagueCode: String,
        mode: KeyMode = KeyMode.DIRECT,
    ): List<Match> {
        val arr = request(key, "fixtures", "league=$leagueId&season=$season&status=FT", mode)
            .optJSONArray("response") ?: JSONArray()
        val out = ArrayList<Match>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val goals = item.optJSONObject("goals") ?: continue
            val home = goals.opt("home")
            val away = goals.opt("away")
            if (home == JSONObject.NULL || away == JSONObject.NULL) continue

            val teams = item.optJSONObject("teams") ?: continue
            val homeName = teams.optJSONObject("home")?.optString("name").orEmpty()
            val awayName = teams.optJSONObject("away")?.optString("name").orEmpty()
            if (homeName.isEmpty() || awayName.isEmpty()) continue

            val date = parseIsoDate(item.optJSONObject("fixture")?.optString("date")) ?: continue
            val half = item.optJSONObject("score")?.optJSONObject("halftime")

            out.add(
                Match(
                    league = leagueCode,
                    dateEpochDay = date,
                    home = homeName,
                    away = awayName,
                    homeGoals = (home as? Int) ?: continue,
                    awayGoals = (away as? Int) ?: continue,
                    htHomeGoals = half?.optIntOrMissing("home") ?: -1,
                    htAwayGoals = half?.optIntOrMissing("away") ?: -1,
                )
            )
        }
        return out
    }

    /**
     * Upcoming fixtures for a single day across every league the key can see.
     * One request covers the whole day, which is what keeps this affordable.
     */
    fun fixturesOn(
        key: String,
        isoDate: String,
        leagueCodes: Map<Int, String>,
        mode: KeyMode = KeyMode.DIRECT,
    ): List<Fixture> {
        val arr = request(key, "fixtures", "date=$isoDate", mode)
            .optJSONArray("response") ?: JSONArray()
        val out = ArrayList<Fixture>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val leagueId = item.optJSONObject("league")?.optInt("id") ?: continue
            val code = leagueCodes[leagueId] ?: continue

            val teams = item.optJSONObject("teams") ?: continue
            val homeName = teams.optJSONObject("home")?.optString("name").orEmpty()
            val awayName = teams.optJSONObject("away")?.optString("name").orEmpty()
            if (homeName.isEmpty() || awayName.isEmpty()) continue

            val fixture = item.optJSONObject("fixture") ?: continue
            val iso = fixture.optString("date")
            val date = parseIsoDate(iso) ?: continue

            out.add(
                Fixture(
                    league = code,
                    dateEpochDay = date,
                    time = iso.substringAfter('T').take(5),
                    home = homeName,
                    away = awayName,
                )
            )
        }
        return out
    }

    /** `2026-08-31T14:00:00+00:00` to an epoch day. */
    private fun parseIsoDate(iso: String?): Long? {
        if (iso.isNullOrBlank() || iso.length < 10) return null
        val y = iso.substring(0, 4).toIntOrNull() ?: return null
        val m = iso.substring(5, 7).toIntOrNull() ?: return null
        val d = iso.substring(8, 10).toIntOrNull() ?: return null
        return Dates.toEpochDay(y, m, d)
    }

    private fun JSONObject.optIntOrMissing(name: String): Int =
        if (isNull(name)) -1 else optInt(name, -1)
}

data class ApiLeague(
    val id: Int,
    val name: String,
    val country: String,
    val type: String,
    val currentSeason: Int,
) {
    /** Stable code used to key matches, distinct from archive codes. */
    val code: String get() = "AF$id"
    val label: String get() = "$country — $name"
}
