package com.skorlogi.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.Repository
import com.skorlogi.app.data.SyncProgress
import com.skorlogi.app.data.SyncResult
import com.skorlogi.app.engine.Pick
import com.skorlogi.app.engine.Picks
import com.skorlogi.app.engine.Prediction
import com.skorlogi.app.data.ApiFootball
import com.skorlogi.app.data.ApiLeague
import com.skorlogi.app.data.TrackedPick
import com.skorlogi.app.data.Tracker
import com.skorlogi.app.data.TrackerStats
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
import java.util.concurrent.ConcurrentHashMap

sealed interface Screen {
    data object Fixtures : Screen
    data object Picks : Screen
    data object Tracker : Screen
    data class Match(val fixture: Fixture) : Screen
    data object Leagues : Screen
    data object Settings : Screen
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
                },
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
        _screen.value = Screen.Match(fixture)
        val cached = predictions[fixture.key]
        if (predictions.containsKey(fixture.key)) {
            _prediction.value = cached
            return
        }
        _prediction.value = null
        _predicting.value = true
        viewModelScope.launch {
            val model = repo.modelFor(fixture.league)
            val p = model?.predict(fixture)
            predictions[fixture.key] = p
            _prediction.value = p
            _predicting.value = false
        }
    }

    fun go(screen: Screen) {
        _screen.value = screen
    }

    fun back() {
        _screen.value = Screen.Picks
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
                "Kunci valid. Paket: ${s.plan.ifBlank { "gratis" }}. " +
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

    fun daysSinceSync(): Long {
        val last = repo.lastSyncEpochDay
        return if (last < 0) -1 else Dates.today() - last
    }
}
