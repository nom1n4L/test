package com.skorlogi.app.data

/**
 * Where a league's history comes from. The two football-data.co.uk feeds differ in
 * what they carry, and that difference decides which markets we can model.
 */
enum class Feed {
    /** mmz4281/<season>/<code>.csv — goals, half-time goals, shots, corners, cards. */
    MAIN,

    /** new/<code>.csv — one file for every season, but goals and odds only. */
    EXTRA,

    /** API-Football. Wide coverage and a long fixture list, no corner or card data. */
    API,
}

data class League(
    val code: String,
    val country: String,
    val name: String,
    val feed: Feed,
) {
    val id: String get() = code
    val label: String get() = "$country — $name"

    /** Only the MAIN archive carries corner and card columns. */
    val hasRichStats: Boolean get() = feed == Feed.MAIN
}

object Leagues {
    /**
     * Season codes for MAIN feeds, newest first — the archive names its files
     * `2627.csv` for the 2026/27 season. Derived from the date rather than fixed,
     * so the app keeps following the current season without a new release.
     *
     * Three seasons is enough history for the time-decay weighting to work with,
     * and matches the window the model keeps.
     */
    fun recentSeasons(today: Long = Dates.today()): List<String> {
        val (year, month, _) = Dates.fromEpochDay(today)
        // European seasons roll over in the summer; July is a safe boundary.
        val startYear = if (month >= 7) year else year - 1
        return (0..2).map { back ->
            val a = (startYear - back) % 100
            val b = (startYear - back + 1) % 100
            "%02d%02d".format(a, b)
        }
    }

    val ALL: List<League> = listOf(
        // --- Full statistics: goals, half-time, shots, corners, cards ---
        League("E0", "Inggris", "Premier League", Feed.MAIN),
        League("E1", "Inggris", "Championship", Feed.MAIN),
        League("E2", "Inggris", "League One", Feed.MAIN),
        League("E3", "Inggris", "League Two", Feed.MAIN),
        League("EC", "Inggris", "National League", Feed.MAIN),
        League("SC0", "Skotlandia", "Premiership", Feed.MAIN),
        League("SC1", "Skotlandia", "Championship", Feed.MAIN),
        League("SC2", "Skotlandia", "League One", Feed.MAIN),
        League("SC3", "Skotlandia", "League Two", Feed.MAIN),
        League("D1", "Jerman", "Bundesliga", Feed.MAIN),
        League("D2", "Jerman", "2. Bundesliga", Feed.MAIN),
        League("I1", "Italia", "Serie A", Feed.MAIN),
        League("I2", "Italia", "Serie B", Feed.MAIN),
        League("SP1", "Spanyol", "La Liga", Feed.MAIN),
        League("SP2", "Spanyol", "Segunda División", Feed.MAIN),
        League("F1", "Prancis", "Ligue 1", Feed.MAIN),
        League("F2", "Prancis", "Ligue 2", Feed.MAIN),
        League("N1", "Belanda", "Eredivisie", Feed.MAIN),
        League("B1", "Belgia", "Pro League", Feed.MAIN),
        League("P1", "Portugal", "Primeira Liga", Feed.MAIN),
        League("T1", "Turki", "Süper Lig", Feed.MAIN),
        League("G1", "Yunani", "Super League", Feed.MAIN),

        // --- Goals and odds only ---
        League("ARG", "Argentina", "Liga Profesional", Feed.EXTRA),
        League("AUT", "Austria", "Bundesliga", Feed.EXTRA),
        League("BRA", "Brasil", "Serie A", Feed.EXTRA),
        League("CHN", "China", "Super League", Feed.EXTRA),
        League("DNK", "Denmark", "Superliga", Feed.EXTRA),
        League("FIN", "Finlandia", "Veikkausliiga", Feed.EXTRA),
        League("IRL", "Irlandia", "Premier Division", Feed.EXTRA),
        League("JPN", "Jepang", "J1 League", Feed.EXTRA),
        League("MEX", "Meksiko", "Liga MX", Feed.EXTRA),
        League("NOR", "Norwegia", "Eliteserien", Feed.EXTRA),
        League("POL", "Polandia", "Ekstraklasa", Feed.EXTRA),
        League("ROU", "Rumania", "Liga I", Feed.EXTRA),
        League("RUS", "Rusia", "Premier League", Feed.EXTRA),
        League("SWE", "Swedia", "Allsvenskan", Feed.EXTRA),
        League("SWZ", "Swiss", "Super League", Feed.EXTRA),
        League("USA", "Amerika Serikat", "MLS", Feed.EXTRA),
    )

    private val archiveByCode = ALL.associateBy { it.code }

    /**
     * Leagues the user follows through API-Football. Held here rather than in the
     * catalog above because the set is chosen at runtime and varies per key, but
     * label lookups need to resolve any code from anywhere in the app.
     */
    private val apiByCode = java.util.concurrent.ConcurrentHashMap<String, League>()

    fun registerApiLeagues(leagues: List<ApiLeague>) {
        apiByCode.clear()
        for (l in leagues) {
            apiByCode[l.code] = League(l.code, l.country, l.name, Feed.API)
        }
    }

    fun apiLeagues(): List<League> = apiByCode.values.sortedBy { it.label }

    fun byCode(code: String): League? = archiveByCode[code] ?: apiByCode[code]

    fun label(code: String): String = byCode(code)?.label ?: code
}
