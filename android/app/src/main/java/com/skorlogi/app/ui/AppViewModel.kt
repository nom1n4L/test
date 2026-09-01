package com.skorlogi.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skorlogi.app.data.ApiFootball
import com.skorlogi.app.data.Assistant
import com.skorlogi.app.data.ChatMessage
import com.skorlogi.app.data.ApiLeague
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.Leagues
import com.skorlogi.app.data.Repository
import com.skorlogi.app.data.SyncProgress
import com.skorlogi.app.data.SyncResult
import com.skorlogi.app.data.TrackedPick
import com.skorlogi.app.data.Tracker
import com.skorlogi.app.data.TrackerStats
import com.skorlogi.app.engine.Analysis
import com.skorlogi.app.engine.Insight
import com.skorlogi.app.engine.Pick
import com.skorlogi.app.engine.Picks
import com.skorlogi.app.engine.Parlay
import com.skorlogi.app.engine.ParlayOption
import com.skorlogi.app.engine.Prediction
import com.skorlogi.app.engine.TeamProfile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Fixtures : Screen
    data object Picks : Screen
    data object Tracker : Screen
    data object Search : Screen
    data object Chat : Screen
    data object Parlay : Screen
    data class Team(val team: String, val league: String) : Screen
    data class Match(val fixture: Fixture) : Screen
    data object Leagues : Screen
    data object Settings : Screen
}

data class SearchResults(
    val teams: List<Pair<String, String>> = emptyList(),
    val fixtures: List<Fixture> = emptyList(),
) {
    val isEmpty: Boolean get() = teams.isEmpty() && fixtures.isEmpty()
}

data class UiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val progress: SyncProgress? = null,
    val message: String? = null,
    val fixtures: List<Fixture> = emptyList(),
    val lastSync: Long = -1L,
    val matchCount: Int = 0,
    val leagueFilter: String? = null,
    /** The open archive is unreachable on this network and nothing replaces it. */
    val blocked: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = Repository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Picks)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val predictions = HashMap<String, Prediction?>()
    private val _prediction = MutableStateFlow<Prediction?>(null)
    val prediction: StateFlow<Prediction?> = _prediction.asStateFlow()

    private val _predicting = MutableStateFlow(false)
    val predicting: StateFlow<Boolean> = _predicting.asStateFlow()

    private val _insights = MutableStateFlow<List<Insight>>(emptyList())
    val insights: StateFlow<List<Insight>> = _insights.asStateFlow()

    // --- Assistant -----------------------------------------------------------

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy.asStateFlow()

    fun sendChat(text: String) {
        if (_chatBusy.value || text.isBlank()) return
        val history = _chat.value + ChatMessage(fromUser = true, text = text.trim())
        _chat.value = history
        _chatBusy.value = true
        viewModelScope.launch {
            val reply = try {
                Assistant(repo.claudeKey).ask(history, buildContext(), repo.claudeModel)
            } catch (e: Assistant.AssistantException) {
                "Gagal menghubungi Claude: ${e.message}"
            } catch (e: Exception) {
                "Gagal: ${e.message}"
            }
            _chat.value = _chat.value + ChatMessage(fromUser = false, text = reply)
            _chatBusy.value = false
        }
    }

    fun clearChat() {
        _chat.value = emptyList()
    }

    /**
     * The figures the assistant is allowed to talk about. Everything it might be
     * asked comes from here, because the system prompt forbids it inventing any
     * number that is not in this text.
     */
    private fun buildContext(): String = buildString {
        val today = Dates.today()
        appendLine("Tanggal hari ini: ${Dates.formatWithDay(today)}")
        appendLine("Pertandingan mendatang tersimpan: ${repo.fixtures.size}")
        appendLine("Riwayat pertandingan tersimpan: ${repo.matches.size}")
        appendLine()

        val picks = _picks.value
        if (picks.isEmpty()) {
            appendLine("PILIHAN TERBAIK: belum ada yang lolos saringan hari ini.")
        } else {
            appendLine("PILIHAN TERBAIK (${picks.size} lolos saringan, ambang 68-92%):")
            picks.take(20).forEach { p ->
                appendLine(
                    "- ${p.fixture.home} vs ${p.fixture.away} (${Leagues.label(p.fixture.league)}, " +
                        "${Dates.formatShort(p.fixture.dateEpochDay)}): ${p.selection} [${p.market}] " +
                        "${p.percent}%, odds adil %.2f, keyakinan ${p.confidence.label}".format(p.fairOdds)
                )
            }
        }
        appendLine()

        val open = _prediction.value
        if (open != null) {
            appendLine("PERTANDINGAN YANG SEDANG DIBUKA PENGGUNA:")
            appendLine("${open.fixture.home} vs ${open.fixture.away} — ${Leagues.label(open.fixture.league)}")
            appendLine("Perkiraan gol: %.2f - %.2f".format(open.lambdaHome, open.lambdaAway))
            appendLine(
                "1X2: kandang %.0f%%, seri %.0f%%, tandang %.0f%%"
                    .format(open.pHome * 100, open.pDraw * 100, open.pAway * 100)
            )
            appendLine("Keyakinan: ${open.confidence.label} — ${open.confidence.note}")
            open.groups.forEach { g ->
                appendLine("  [${g.title}] " + g.lines.take(8).joinToString("; ") {
                    "${it.label} %.0f%%".format(it.prob * 100)
                })
            }
            open.homeForm?.let {
                appendLine(
                    "Form ${it.team}: ${it.formString}, cetak %.2f, kebobolan %.2f per laga"
                        .format(it.avgScored, it.avgConceded)
                )
            }
            open.awayForm?.let {
                appendLine(
                    "Form ${it.team}: ${it.formString}, cetak %.2f, kebobolan %.2f per laga"
                        .format(it.avgScored, it.avgConceded)
                )
            }
            appendLine()
        }

        val stats = _trackerStats.value
        if (stats.settled > 0) {
            appendLine("RAPOR PELACAK PENGGUNA:")
            appendLine(
                "${stats.won} tembus dari ${stats.settled} selesai (%.0f%%), " +
                    "sedangkan model menjanjikan %.0f%%."
                        .format(stats.hitRate * 100, stats.expected * 100)
            )
            if (stats.hasMoney) {
                appendLine("Untung/rugi: %+.2f satuan, ROI %+.0f%%".format(stats.profit, stats.roi * 100))
            }
            appendLine()
        }

        val parlays = Parlay.suggestions(picks)
        if (parlays.isNotEmpty()) {
            appendLine("HITUNGAN PARLAY dari pilihan di atas:")
            parlays.forEach { o ->
                appendLine(
                    "- ${o.size} leg: peluang tembus semua %.1f%% (sekitar 1 dari ${o.oneInN}), " +
                        "bayaran wajar %.2f, bayaran nyata setelah margin %.2f, " +
                        "imbal hasil harapan %.0f%% (rugi %d%% rata-rata)"
                            .format(o.combinedProb * 100, o.fairOdds, o.realisticOdds,
                                o.expectedReturn * 100, o.expectedLossPercent)
                )
            }
        }
    }

    // --- Search --------------------------------------------------------------

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    private var searchJob: Job? = null

    private val _profile = MutableStateFlow<TeamProfile?>(null)
    val profile: StateFlow<TeamProfile?> = _profile.asStateFlow()

    private val _teamFixtures = MutableStateFlow<List<Pair<Fixture, DoubleArray?>>>(emptyList())
    val teamFixtures: StateFlow<List<Pair<Fixture, DoubleArray?>>> = _teamFixtures.asStateFlow()

    private val _profileLoading = MutableStateFlow(false)
    val profileLoading: StateFlow<Boolean> = _profileLoading.asStateFlow()

    /**
     * Searches teams and upcoming matches by name.
     *
     * Team names are read off the stored matches rather than any fitted model, so
     * this answers instantly and works before a single league has been fitted.
     */
    fun search(text: String) {
        _query.value = text
        searchJob?.cancel()
        val q = text.trim()
        if (q.length < 2) {
            _results.value = SearchResults()
            return
        }
        searchJob = viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                val teams = LinkedHashMap<String, String>()
                for (m in repo.matches) {
                    if (m.home.contains(q, true)) teams.putIfAbsent(m.home, m.league)
                    if (m.away.contains(q, true)) teams.putIfAbsent(m.away, m.league)
                }
                for (f in repo.fixtures) {
                    if (f.home.contains(q, true)) teams.putIfAbsent(f.home, f.league)
                    if (f.away.contains(q, true)) teams.putIfAbsent(f.away, f.league)
                }
                val fixtures = repo.fixtures.filter {
                    it.home.contains(q, true) || it.away.contains(q, true) ||
                        Leagues.label(it.league).contains(q, true)
                }
                SearchResults(
                    teams = teams.entries.take(30).map { it.key to it.value },
                    fixtures = fixtures.take(40),
                )
            }
            _results.value = found
        }
    }

    fun openTeam(team: String, league: String) {
        pushCurrent()
        _screen.value = Screen.Team(team, league)
        _profile.value = null
        _teamFixtures.value = emptyList()
        _profileLoading.value = true
        viewModelScope.launch {
            val model = repo.modelFor(league)
            _profile.value = model?.profile(team)
            _teamFixtures.value = withContext(Dispatchers.Default) {
                repo.fixtures
                    .filter { it.league == league && (it.home == team || it.away == team) }
                    .sortedBy { it.dateEpochDay }
                    .take(10)
                    .map { f ->
                        val p = predictions[f.key] ?: model?.predict(f)?.also { predictions[f.key] = it }
                        f to p?.let { doubleArrayOf(it.pHome, it.pDraw, it.pAway) }
                    }
            }
            _profileLoading.value = false
        }
    }

    /** Fixture key to home/draw/away, filled in progressively so the list can show
     *  a preview bar without blocking on every league being fitted first. */
    private val _quick = MutableStateFlow<Map<String, DoubleArray>>(emptyMap())
    val quick: StateFlow<Map<String, DoubleArray>> = _quick.asStateFlow()

    private var warmUpJob: Job? = null

    private val _picks = MutableStateFlow<List<Pick>>(emptyList())
    val picks: StateFlow<List<Pick>> = _picks.asStateFlow()

    private val _tracked = MutableStateFlow<List<TrackedPick>>(emptyList())
    val tracked: StateFlow<List<TrackedPick>> = _tracked.asStateFlow()

    private val _trackerStats = MutableStateFlow(Tracker(app.getSharedPreferences("skorlogi", Application.MODE_PRIVATE)).stats())
    val trackerStats: StateFlow<TrackerStats> = _trackerStats.asStateFlow()

    private val tracker = Tracker(app.getSharedPreferences("skorlogi", Application.MODE_PRIVATE))

    init {
        viewModelScope.launch {
            repo.restoreApiLeagues()
            repo.loadFromCache()
            refreshTracker()
            refreshLists()
            _state.value = _state.value.copy(loading = false)
            // First run: nothing cached, so fetch straight away.
            if (!repo.hasData) sync() else warmUp()
        }
    }

    /** Remembers the current screen unless it is already the one we came from. */
    private fun pushCurrent() {
        val current = _screen.value
        if (backStack.lastOrNull() != current) backStack.addLast(current)
        if (backStack.size > 8) backStack.removeFirst()
    }

    private fun refreshTracker() {
        tracker.settle(repo.matches)
        _tracked.value = tracker.all().sortedByDescending { it.dateEpochDay }
        _trackerStats.value = tracker.stats()
    }

    fun follow(pick: Pick, odds: Double = 0.0) {
        tracker.follow(pick, odds)
        refreshTracker()
    }

    fun unfollow(id: String) {
        tracker.unfollow(id)
        refreshTracker()
    }

    fun setOdds(id: String, odds: Double) {
        tracker.setOdds(id, odds)
        refreshTracker()
    }

    fun clearSettled() {
        tracker.clearSettled()
        refreshTracker()
    }

    fun isFollowed(pick: Pick): Boolean = _tracked.value.any {
        it.id == "${pick.fixture.key}|${pick.kind.name}"
    }

    private fun refreshLists() {
        _state.value = _state.value.copy(
            fixtures = repo.fixtures,
            lastSync = repo.lastSyncEpochDay,
            matchCount = repo.matches.size,
        )
    }

    fun sync() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(syncing = true, message = null)
            val result = repo.sync(
                enabled = repo.enabledLeagues(),
                onProgress = { p -> _state.value = _state.value.copy(progress = p) },
                // The schedule lands long before the history does; show it at once
                // rather than leaving the user on an empty screen.
                onFixturesReady = { refreshLists() },
            )
            predictions.clear()
            refreshLists()
            warmUp()
            _state.value = _state.value.copy(
                syncing = false,
                progress = null,
                message = when (result) {
                    is SyncResult.Ok ->
                        "Selesai — ${result.matches} pertandingan dari ${result.leagues} liga, ${result.fixtures} jadwal."
                    is SyncResult.Partial ->
                        "Selesai sebagian — ${result.matches} pertandingan. Gagal: ${result.failed.take(2).joinToString()}"
                    is SyncResult.Failed -> "Gagal: ${result.reason}"
                    is SyncResult.Blocked ->
                        "Sumber data arsip diblokir oleh jaringan ini. Buka Pengaturan untuk " +
                            "memasang sumber alternatif."
                },
                blocked = result is SyncResult.Blocked,
            )
        }
    }

    /**
     * Fits each league that has fixtures, publishing 1X2 previews as it goes.
     * Leagues are independent, so they are fitted in parallel across cores.
     */
    private fun warmUp() {
        warmUpJob?.cancel()
        _quick.value = emptyMap()
        warmUpJob = viewModelScope.launch {
            val byLeague = repo.fixtures.groupBy { it.league }
            val acc = ConcurrentHashMap<String, DoubleArray>()
            val gate = Semaphore(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            coroutineScope {
                byLeague.map { (league, list) ->
                    async(Dispatchers.Default) {
                        gate.withPermit {
                            val model = repo.modelFor(league)
                            if (model != null) {
                                for (f in list) {
                                    val p = model.predict(f) ?: continue
                                    predictions[f.key] = p
                                    acc[f.key] = doubleArrayOf(p.pHome, p.pDraw, p.pAway)
                                }
                                _quick.value = HashMap(acc)
                            }
                        }
                    }
                }.forEach { it.await() }
            }
            _picks.value = Picks.best(predictions.values.filterNotNull())
            refreshTracker()
        }
    }

    fun open(fixture: Fixture) {
        pushCurrent()
        _screen.value = Screen.Match(fixture)
        _prediction.value = null
        _insights.value = emptyList()
        _predicting.value = true
        viewModelScope.launch {
            val model = repo.modelFor(fixture.league)
            val p = predictions[fixture.key] ?: model?.predict(fixture)
            predictions[fixture.key] = p
            _prediction.value = p
            _insights.value = if (p != null && model != null) {
                withContext(Dispatchers.Default) { Analysis.generate(p, model) }
            } else {
                emptyList()
            }
            _predicting.value = false
        }
    }

    /** Where back should return to, so a match opened from search goes back there. */
    private val backStack = ArrayDeque<Screen>()

    fun go(screen: Screen) {
        backStack.clear()
        _screen.value = screen
    }

    fun back() {
        _screen.value = backStack.removeLastOrNull() ?: Screen.Picks
    }

    fun setLeagueFilter(code: String?) {
        _state.value = _state.value.copy(leagueFilter = code)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun setDecay(value: Double) {
        repo.decay = value
        predictions.clear()
        _prediction.value = null
    }

    fun setEnabledLeagues(codes: Set<String>) {
        repo.setEnabledLeagues(codes)
    }

    fun setEnabledOpenLeagues(codes: Set<String>) {
        repo.setEnabledOpenLeagues(codes)
    }

    /** One tap out of a blocked archive: switch on the keyless source and refetch. */
    fun useFallbackSource() {
        repo.enableAllOpenLeagues()
        _state.value = _state.value.copy(blocked = false)
        sync()
    }

    // --- API-Football --------------------------------------------------------

    private val _apiStatus = MutableStateFlow<String?>(null)
    val apiStatus: StateFlow<String?> = _apiStatus.asStateFlow()

    private val _apiCatalog = MutableStateFlow<List<ApiLeague>>(emptyList())
    val apiCatalog: StateFlow<List<ApiLeague>> = _apiCatalog.asStateFlow()

    private val _apiBusy = MutableStateFlow(false)
    val apiBusy: StateFlow<Boolean> = _apiBusy.asStateFlow()

    fun saveApiKey(key: String) {
        repo.apiKey = key
        _apiStatus.value = null
    }

    /** Verifies the key and reports the quota it carries. Costs one request. */
    fun testApiKey(key: String) {
        if (_apiBusy.value) return
        viewModelScope.launch {
            _apiBusy.value = true
            _apiStatus.value = try {
                val s = repo.testApiKey(key)
                repo.apiKey = key
                val via = if (repo.apiKeyMode == ApiFootball.KeyMode.RAPIDAPI) {
                    " (lewat RapidAPI)"
                } else {
                    ""
                }
                "Kunci valid$via. Paket: ${s.plan.ifBlank { "gratis" }}. " +
                    "Kuota hari ini: ${s.used}/${s.limitPerDay} terpakai, sisa ${s.remaining}."
            } catch (e: Exception) {
                "Gagal: ${e.message}"
            }
            _apiBusy.value = false
        }
    }

    /** Downloads the league catalogue so leagues can be chosen. Costs one request. */
    fun loadApiCatalog() {
        if (_apiBusy.value) return
        viewModelScope.launch {
            _apiBusy.value = true
            try {
                _apiCatalog.value = repo.fetchApiLeagueCatalog()
                _apiStatus.value = "${_apiCatalog.value.size} liga tersedia. Pilih yang mau diikuti."
            } catch (e: Exception) {
                _apiStatus.value = "Gagal ambil daftar liga: ${e.message}"
            }
            _apiBusy.value = false
        }
    }

    fun setFollowedApiLeagues(leagues: List<ApiLeague>) {
        repo.setFollowedApiLeagues(leagues)
    }

    fun followedApiLeagues(): List<ApiLeague> = repo.followedApiLeagues()

    // --- Parlay --------------------------------------------------------------

    private val _parlayLegs = MutableStateFlow<List<Pick>>(emptyList())
    val parlayLegs: StateFlow<List<Pick>> = _parlayLegs.asStateFlow()

    fun toggleParlayLeg(pick: Pick) {
        val key = pick.fixture.key
        val current = _parlayLegs.value
        _parlayLegs.value = if (current.any { it.fixture.key == key && it.kind == pick.kind }) {
            current.filterNot { it.fixture.key == key && it.kind == pick.kind }
        } else {
            // One leg per match: two bets on the same game are not independent, and
            // multiplying them as if they were would overstate the parlay badly.
            current.filterNot { it.fixture.key == key } + pick
        }
    }

    fun clearParlay() {
        _parlayLegs.value = emptyList()
    }

    fun parlayOf(legs: List<Pick>): ParlayOption = Parlay.combine(legs)

    fun parlaySuggestions(): List<ParlayOption> = Parlay.suggestions(_picks.value)

    fun setClaudeKey(key: String) {
        repo.claudeKey = key
    }

    fun setClaudeModel(model: String) {
        repo.claudeModel = model
    }

    fun daysSinceSync(): Long {
        val last = repo.lastSyncEpochDay
        return if (last < 0) -1 else Dates.today() - last
    }
}
