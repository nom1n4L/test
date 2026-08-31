package com.skorlogi.app.data

/**
 * Reads the football-data.co.uk feeds. Everything is public CSV — no account, no
 * API key, no rate limit to negotiate.
 */
object FootballData {

    private const val BASE = "https://www.football-data.co.uk"

    fun mainUrl(code: String, season: String) = "$BASE/mmz4281/$season/$code.csv"

    fun extraUrl(code: String) = "$BASE/new/$code.csv"

    const val FIXTURES_URL = "$BASE/fixtures.csv"

    private fun Map<String, String>.int(key: String): Int =
        this[key]?.trim()?.toIntOrNull() ?: -1

    private fun Map<String, String>.dbl(key: String): Double =
        this[key]?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

    /** Parses a MAIN feed season file. */
    fun parseMain(code: String, body: String): List<Match> {
        val out = ArrayList<Match>(600)
        for (row in Csv.parse(body)) {
            val home = row["HomeTeam"].orEmpty()
            val away = row["AwayTeam"].orEmpty()
            if (home.isEmpty() || away.isEmpty()) continue
            val hg = row.int("FTHG")
            val ag = row.int("FTAG")
            if (hg < 0 || ag < 0) continue
            val date = Dates.parseFeedDate(row["Date"].orEmpty()) ?: continue

            val hy = row.int("HY")
            val hr = row.int("HR")
            val ay = row.int("AY")
            val ar = row.int("AR")

            out.add(
                Match(
                    league = code,
                    dateEpochDay = date,
                    home = home,
                    away = away,
                    homeGoals = hg,
                    awayGoals = ag,
                    htHomeGoals = row.int("HTHG"),
                    htAwayGoals = row.int("HTAG"),
                    homeCorners = row.int("HC"),
                    awayCorners = row.int("AC"),
                    // A red card counts double, matching how card lines are usually priced.
                    homeCards = if (hy < 0) -1 else hy + 2 * maxOf(hr, 0),
                    awayCards = if (ay < 0) -1 else ay + 2 * maxOf(ar, 0),
                    homeShotsOnTarget = row.int("HST"),
                    awayShotsOnTarget = row.int("AST"),
                )
            )
        }
        return out
    }

    /**
     * Parses an EXTRA feed. One file holds every season, so recent seasons are
     * selected by date rather than by a season column whose format varies
     * between split-year and single-year competitions.
     */
    fun parseExtra(code: String, body: String, sinceEpochDay: Long): List<Match> {
        val out = ArrayList<Match>(600)
        for (row in Csv.parse(body)) {
            val home = row["Home"].orEmpty()
            val away = row["Away"].orEmpty()
            if (home.isEmpty() || away.isEmpty()) continue
            val hg = row.int("HG")
            val ag = row.int("AG")
            if (hg < 0 || ag < 0) continue
            val date = Dates.parseFeedDate(row["Date"].orEmpty()) ?: continue
            if (date < sinceEpochDay) continue

            out.add(
                Match(
                    league = code,
                    dateEpochDay = date,
                    home = home,
                    away = away,
                    homeGoals = hg,
                    awayGoals = ag,
                )
            )
        }
        return out
    }

    /** Parses the shared upcoming-fixtures file, keeping only leagues we know. */
    fun parseFixtures(body: String): List<Fixture> {
        val out = ArrayList<Fixture>(256)
        for (row in Csv.parse(body)) {
            val div = row["Div"].orEmpty()
            if (Leagues.byCode(div) == null) continue
            val home = row["HomeTeam"].orEmpty()
            val away = row["AwayTeam"].orEmpty()
            if (home.isEmpty() || away.isEmpty()) continue
            val date = Dates.parseFeedDate(row["Date"].orEmpty()) ?: continue

            out.add(
                Fixture(
                    league = div,
                    dateEpochDay = date,
                    time = row["Time"].orEmpty(),
                    home = home,
                    away = away,
                    oddsHome = row.dbl("B365H").takeIf { it > 1.0 } ?: row.dbl("AvgH"),
                    oddsDraw = row.dbl("B365D").takeIf { it > 1.0 } ?: row.dbl("AvgD"),
                    oddsAway = row.dbl("B365A").takeIf { it > 1.0 } ?: row.dbl("AvgA"),
                )
            )
        }
        return out
    }
}
