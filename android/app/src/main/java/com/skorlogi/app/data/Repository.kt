package com.skorlogi.app.data

import android.content.Context
import com.skorlogi.app.engine.LeagueModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

data class SyncProgress(val done: Int, val total: Int, val current: String)

sealed interface SyncResult {
    data class Ok(val matches: Int, val fixtures: Int, val leagues: Int) : SyncResult
    data class Partial(val matches: Int, val fixtures: Int, val leagues: Int, val failed: List<String>) : SyncResult
    data class Failed(val reason: String) : SyncResult
}

/**
 * Owns the downloaded feeds and the fitted models.
 *
 * Feed bodies are cached on disk so the app opens with data even offline; models are
 * fitted lazily per league on first use and kept in memory afterwards, since fitting
 * is the expensive part and most sessions only touch a few leagues.
 */
class Repository(private val context: Context) {

    private val cacheDir: File by lazy {
        File(context.filesDir, "feeds").apply { mkdirs() }
    }

    private val prefs by lazy {
        context.getSharedPreferences("skorlogi", Context.MODE_PRIVATE)
    }

    @Volatile var matches: List<Match> = emptyList()
        private set

    @Volatile var fixtures: List<Fixture> = emptyList()
        private set

    private val models = HashMap<String, LeagueModel?>()
    private val modelMutex = Mutex()

    var lastSyncEpochDay: Long
        get() = prefs.getLong("last_sync", -1L)
        private set(v) = prefs.edit().putLong("last_sync", v).apply()

    var decay: Double
        get() = prefs.getFloat("decay", com.skorlogi.app.engine.LeagueModel.DEFAULT_DECAY.toFloat()).toDouble()
        set(v) {
            prefs.edit().putFloat("decay", v.toFloat()).apply()
            models.clear()
        }

    val hasData: Boolean get() = matches.isNotEmpty()

    /** Requests spent against the API quota by the most recent sync. */
    @Volatile var lastApiRequests: Int = 0
        private set

    private fun fileFor(name: String) = File(cacheDir, "$name.csv")

    private fun readCache(name: String): String? =
        fileFor(name).takeIf { it.exists() && it.length() > 0 }?.readText()

    private fun writeCache(name: String, body: String) {
        runCatching { fileFor(name).writeText(body) }
    }

    /** Loads whatever is already on disk. Fast path for app start. */
    suspend fun loadFromCache() = withContext(Dispatchers.IO) {
        val today = Dates.today()
        val since = today - LeagueModel.HISTORY_DAYS
        val all = ArrayList<Match>(20_000)

        for (league in Leagues.ALL) {
            when (league.feed) {
                Feed.MAIN -> for (season in Leagues.recentSeasons()) {
                    readCache("${league.code}_$season")?.let {
                        all.addAll(FootballData.parseMain(league.code, it))
                    }
                }
                Feed.EXTRA -> readCache(league.code)?.let {
                    all.addAll(FootballData.parseExtra(league.code, it, since))
                }
                // API leagues are cached in their own format and loaded below.
                Feed.API -> Unit
            }
        }
        val (apiMatches, _) = loadApiFromCache()
        all.addAll(apiMatches)
        matches = all
        loadFixturesFromCache()
    }

    /** Reads just the schedule — cheap, and all the first screen needs. */
    fun loadFixturesFromCache() {
        val today = Dates.today()
        val archive = readCache("fixtures")
            ?.let { FootballData.parseFixtures(it) }
            ?.filter { it.dateEpochDay >= today - 1 }
            ?: emptyList()
        val (_, api) = loadApiFromCache()
        fixtures = (archive + api)
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.dateEpochDay }, { it.league }, { it.time }))
    }

    /**
     * Downloads every enabled feed.
     *
     * Order matters more than it looks. The fixture list is small and is the only
     * thing the first screen needs, so it is fetched first and published straight
     * away; the ~80 season files behind it are then pulled concurrently, with the
     * leagues that actually have fixtures going first. Individual failures are
     * collected rather than aborting the run — one dead season file should not
     * cost the user the other 37.
     *
     * @param onFixturesReady invoked as soon as the schedule is usable, long
     *   before the history finishes downloading.
     */
    suspend fun sync(
        enabled: Set<String>,
        onProgress: (SyncProgress) -> Unit,
        onFixturesReady: suspend () -> Unit = {},
    ): SyncResult = withContext(Dispatchers.IO) {
        val leagues = Leagues.ALL.filter { it.code in enabled }
        if (leagues.isEmpty()) return@withContext SyncResult.Failed("Belum ada liga yang dipilih.")

        val failed = ArrayList<String>()

        // 1. The schedule, first and alone.
        onProgress(SyncProgress(0, 1, "Jadwal pertandingan"))
        var fixtureLeagues = emptyList<String>()
        try {
            val body = Http.getText(FootballData.FIXTURES_URL)
            if (body.length > 200) {
                writeCache("fixtures", body)
                fixtureLeagues = FootballData.parseFixtures(body).map { it.league }.distinct()
            }
        } catch (e: FetchException) {
            failed.add("Jadwal pertandingan (${e.message})")
        }
        loadFixturesFromCache()
        onFixturesReady()

        // 2. History, leagues with fixtures first so predictions can start early.
        val order = leagues.sortedByDescending { it.code in fixtureLeagues }
        val jobs = ArrayList<Pair<String, String>>()
        for (l in order) {
            when (l.feed) {
                Feed.MAIN -> for (s in Leagues.recentSeasons()) {
                    jobs.add("${l.code}_$s" to FootballData.mainUrl(l.code, s))
                }
                Feed.EXTRA -> jobs.add(l.code to FootballData.extraUrl(l.code))
                Feed.API -> Unit
            }
        }

        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = Semaphore(MAX_PARALLEL_DOWNLOADS)
        coroutineScope {
            jobs.map { (name, url) ->
                async {
                    gate.withPermit {
                        val label = Leagues.label(name.substringBefore('_'))
                        try {
                            val body = Http.getText(url)
                            // A season that has not started yet returns a near-empty
                            // file; keep what is cached rather than replacing it with
                            // nothing.
                            if (body.length > 200) writeCache(name, body)
                        } catch (e: FetchException) {
                            // A missing season file is normal early in a season.
                            if (e.message != "404") {
                                synchronized(failed) { failed.add("$label (${e.message})") }
                            }
                        }
                        onProgress(SyncProgress(done.incrementAndGet(), jobs.size, label))
                    }
                }
            }.forEach { it.await() }
        }

        // API-Football, when a key is configured. It runs after the archive so a
        // quota failure never costs the free data.
        val apiRequests = try {
            syncApi(onProgress, failed)
        } catch (e: Exception) {
            failed.add("API-Football (${e.message})")
            0
        }
        lastApiRequests = apiRequests

        onProgress(SyncProgress(jobs.size, jobs.size, "Menyusun data"))
        loadFromCache()
        modelMutex.withLock { models.clear() }
        lastSyncEpochDay = Dates.today()

        val leagueCount = matches.map { it.league }.distinct().size
        return@withContext when {
            matches.isEmpty() -> SyncResult.Failed(
                failed.firstOrNull() ?: "Tidak ada data yang berhasil diunduh."
            )
            failed.isEmpty() -> SyncResult.Ok(matches.size, fixtures.size, leagueCount)
            else -> SyncResult.Partial(matches.size, fixtures.size, leagueCount, failed)
        }
    }

    /** Fits a league's models on first request, then serves the cached fit. */
    suspend fun modelFor(league: String): LeagueModel? = modelMutex.withLock {
        if (models.containsKey(league)) return@withLock models[league]
        val built = withContext(Dispatchers.Default) {
            LeagueModel.build(
                league = league,
                all = matches.filter { it.league == league },
                today = Dates.today(),
                decay = decay,
            )
        }
        models[league] = built
        built
    }

    fun clearModels() {
        models.clear()
    }

    // --- League selection -------------------------------------------------

    fun enabledLeagues(): Set<String> =
        prefs.getStringSet("leagues", null) ?: Leagues.ALL.map { it.code }.toSet()

    fun setEnabledLeagues(codes: Set<String>) {
        prefs.edit().putStringSet("leagues", codes).apply()
    }

    // --- API-Football --------------------------------------------------------

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /**
     * Leagues the user follows through the API, cached locally so their names and
     * season numbers survive a restart without spending a request.
     */
    fun followedApiLeagues(): List<ApiLeague> {
        val raw = prefs.getString("api_leagues", null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    ApiLeague(
                        id = it.optInt("id"),
                        name = it.optString("name"),
                        country = it.optString("country"),
                        type = it.optString("type"),
                        currentSeason = it.optInt("season"),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setFollowedApiLeagues(leagues: List<ApiLeague>) {
        val arr = org.json.JSONArray()
        for (l in leagues) {
            arr.put(
                org.json.JSONObject()
                    .put("id", l.id)
                    .put("name", l.name)
                    .put("country", l.country)
                    .put("type", l.type)
                    .put("season", l.currentSeason)
            )
        }
        prefs.edit().putString("api_leagues", arr.toString()).apply()
        Leagues.registerApiLeagues(leagues)
        models.clear()
    }

    /** Makes cached API league names available for label lookups at startup. */
    fun restoreApiLeagues() {
        Leagues.registerApiLeagues(followedApiLeagues())
    }

    suspend fun testApiKey(key: String): ApiFootball.Status = withContext(Dispatchers.IO) {
        ApiFootball.status(key)
    }

    suspend fun fetchApiLeagueCatalog(): List<ApiLeague> = withContext(Dispatchers.IO) {
        ApiFootball.leagues(apiKey)
    }

    /**
     * Pulls fixtures and season history for the followed API leagues.
     *
     * Requests are the scarce resource, so history is refetched only once a week
     * per league-season and the fixture sweep asks for whole days rather than
     * per-league lists. Returns how many requests were spent and what failed.
     */
    private suspend fun syncApi(
        onProgress: (SyncProgress) -> Unit,
        failed: MutableList<String>,
    ): Int = withContext(Dispatchers.IO) {
        val key = apiKey
        val leagues = followedApiLeagues()
        if (key.isBlank() || leagues.isEmpty()) return@withContext 0

        var spent = 0
        val today = Dates.today()
        val codes = leagues.associate { it.id to it.code }

        // Fixtures: one request per day, covering every followed league at once.
        val fixtureFile = StringBuilder()
        for (offset in 0 until API_FIXTURE_DAYS) {
            val day = today + offset
            val (y, m, d) = Dates.fromEpochDay(day)
            val iso = "%04d-%02d-%02d".format(y, m, d)
            onProgress(SyncProgress(offset, API_FIXTURE_DAYS, "Jadwal API $iso"))
            try {
                val list = ApiFootball.fixturesOn(key, iso, codes)
                spent++
                for (f in list) {
                    fixtureFile.append(f.league).append('\t')
                        .append(f.dateEpochDay).append('\t')
                        .append(f.time).append('\t')
                        .append(f.home).append('\t')
                        .append(f.away).append('\n')
                }
            } catch (e: ApiFootball.ApiException) {
                failed.add("Jadwal API $iso (${e.message})")
                // Quota errors will hit every subsequent day too.
                if (e.message?.contains("Kuota") == true) break
            }
        }
        if (fixtureFile.isNotEmpty()) writeCache("api_fixtures", fixtureFile.toString())

        // History: three seasons per league, refreshed weekly.
        for (l in leagues) {
            if (l.currentSeason <= 0) continue
            for (back in 0 until API_HISTORY_SEASONS) {
                val season = l.currentSeason - back
                val name = "${l.code}_$season"
                val age = today - prefs.getLong("fetched_$name", 0L)
                if (readCache(name) != null && age < API_HISTORY_REFRESH_DAYS) continue
                onProgress(SyncProgress(0, 0, "${l.name} $season"))
                try {
                    val results = ApiFootball.seasonResults(key, l.id, season, l.code)
                    spent++
                    if (results.isNotEmpty()) {
                        writeCache(name, results.joinToString("\n") { m ->
                            "${m.dateEpochDay}\t${m.home}\t${m.away}\t${m.homeGoals}\t" +
                                "${m.awayGoals}\t${m.htHomeGoals}\t${m.htAwayGoals}"
                        })
                        prefs.edit().putLong("fetched_$name", today).apply()
                    }
                } catch (e: ApiFootball.ApiException) {
                    failed.add("${l.name} $season (${e.message})")
                    if (e.message?.contains("Kuota") == true) return@withContext spent
                }
            }
        }
        spent
    }

    /** Reads back the tab-separated caches written by [syncApi]. */
    private fun loadApiFromCache(): Pair<List<Match>, List<Fixture>> {
        val leagues = followedApiLeagues()
        val matches = ArrayList<Match>()
        val today = Dates.today()
        for (l in leagues) {
            for (back in 0 until API_HISTORY_SEASONS) {
                val body = readCache("${l.code}_${l.currentSeason - back}") ?: continue
                for (line in body.lineSequence()) {
                    val p = line.split('\t')
                    if (p.size < 7) continue
                    matches.add(
                        Match(
                            league = l.code,
                            dateEpochDay = p[0].toLongOrNull() ?: continue,
                            home = p[1],
                            away = p[2],
                            homeGoals = p[3].toIntOrNull() ?: continue,
                            awayGoals = p[4].toIntOrNull() ?: continue,
                            htHomeGoals = p[5].toIntOrNull() ?: -1,
                            htAwayGoals = p[6].toIntOrNull() ?: -1,
                        )
                    )
                }
            }
        }
        val fixtures = ArrayList<Fixture>()
        readCache("api_fixtures")?.lineSequence()?.forEach { line ->
            val p = line.split('\t')
            if (p.size < 5) return@forEach
            val day = p[1].toLongOrNull() ?: return@forEach
            if (day < today - 1) return@forEach
            fixtures.add(Fixture(p[0], day, p[2], p[3], p[4]))
        }
        return matches to fixtures
    }

    private companion object {
        /** Enough to saturate a phone connection without stampeding the archive. */
        const val MAX_PARALLEL_DOWNLOADS = 6

        /** Days of API fixtures to sweep — one request each, so this is a quota dial. */
        const val API_FIXTURE_DAYS = 5

        const val API_HISTORY_SEASONS = 3

        /** A finished season does not change; a running one barely does week to week. */
        const val API_HISTORY_REFRESH_DAYS = 7
    }
}
