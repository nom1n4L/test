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
            }
        }
        matches = all
        loadFixturesFromCache()
    }

    /** Reads just the schedule — cheap, and all the first screen needs. */
    fun loadFixturesFromCache() {
        val today = Dates.today()
        fixtures = readCache("fixtures")
            ?.let { FootballData.parseFixtures(it) }
            ?.filter { it.dateEpochDay >= today - 1 }
            ?.sortedWith(compareBy({ it.dateEpochDay }, { it.league }, { it.time }))
            ?: emptyList()
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

    private companion object {
        /** Enough to saturate a phone connection without stampeding the archive. */
        const val MAX_PARALLEL_DOWNLOADS = 6
    }
}
