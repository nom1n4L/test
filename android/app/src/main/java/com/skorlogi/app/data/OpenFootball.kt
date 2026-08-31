package com.skorlogi.app.data

import org.json.JSONObject

/**
 * The openfootball archive, served as plain JSON from GitHub.
 *
 * It exists here as the source that keeps working when the others do not. Some
 * networks resolve football-data.co.uk to a block server because that archive
 * carries bookmaker odds; openfootball carries none, and lives on GitHub, so it
 * tends to be reachable where the other is not.
 *
 * It also happens to be better at the one thing the app needs most: each file
 * holds a whole season's schedule, so fixtures run months ahead rather than the
 * week the odds archive publishes. What it does not carry is corners, cards and
 * odds — the markets that measured badly anyway.
 */
object OpenFootball {

    private const val BASE =
        "https://raw.githubusercontent.com/openfootball/football.json/master"

    fun url(season: String, code: String) = "$BASE/$season/$code.json"

    /** Season directories are named `2026-27`, newest first. */
    fun recentSeasons(today: Long = Dates.today()): List<String> {
        val (year, month, _) = Dates.fromEpochDay(today)
        val start = if (month >= 7) year else year - 1
        return (0..2).map { back -> "%d-%02d".format(start - back, (start - back + 1) % 100) }
    }

    /**
     * Parses one season file into finished matches and scheduled ones. A match with
     * no score has not been played yet, which is how the fixture list is built.
     */
    fun parse(code: String, body: String): Pair<List<Match>, List<Fixture>> {
        val played = ArrayList<Match>()
        val upcoming = ArrayList<Fixture>()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return played to upcoming
        val arr = root.optJSONArray("matches") ?: return played to upcoming

        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val home = m.optString("team1")
            val away = m.optString("team2")
            if (home.isEmpty() || away.isEmpty()) continue
            val date = parseDate(m.optString("date")) ?: continue
            val time = m.optString("time")

            val score = m.optJSONObject("score")
            val ft = score?.optJSONArray("ft")
            if (score == null || ft == null || ft.length() < 2) {
                upcoming.add(Fixture(code, date, time, home, away))
                continue
            }
            val ht = score.optJSONArray("ht")
            played.add(
                Match(
                    league = code,
                    dateEpochDay = date,
                    home = home,
                    away = away,
                    homeGoals = ft.optInt(0, -1).takeIf { it >= 0 } ?: continue,
                    awayGoals = ft.optInt(1, -1).takeIf { it >= 0 } ?: continue,
                    htHomeGoals = if (ht != null && ht.length() >= 2) ht.optInt(0, -1) else -1,
                    htAwayGoals = if (ht != null && ht.length() >= 2) ht.optInt(1, -1) else -1,
                )
            )
        }
        return played to upcoming
    }

    /** `2026-08-28` to an epoch day. */
    private fun parseDate(text: String?): Long? {
        if (text == null || text.length < 10) return null
        val y = text.substring(0, 4).toIntOrNull() ?: return null
        val m = text.substring(5, 7).toIntOrNull() ?: return null
        val d = text.substring(8, 10).toIntOrNull() ?: return null
        return Dates.toEpochDay(y, m, d)
    }
}
