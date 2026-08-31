package com.skorlogi.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.Repository
import com.skorlogi.app.data.SyncProgress
import com.skorlogi.app.data.SyncResult
import com.skorlogi.app.engine.Prediction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Fixtures : Screen
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

    private val _screen = MutableStateFlow<Screen>(Screen.Fixtures)
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

    private var warmUpJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            repo.loadFromCache()
            refreshLists()
            _state.value = _state.value.copy(loading = false)
            // First run: nothing cached, so fetch straight away.
            if (!repo.hasData) sync() else warmUp()
        }
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
            val result = repo.sync(repo.enabledLeagues()) { p ->
                _state.value = _state.value.copy(progress = p)
            }
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

    /** Fits each league that has fixtures, publishing 1X2 previews as it goes. */
    private fun warmUp() {
        warmUpJob?.cancel()
        _quick.value = emptyMap()
        warmUpJob = viewModelScope.launch {
            val byLeague = repo.fixtures.groupBy { it.league }
            val acc = HashMap<String, DoubleArray>()
            for ((league, list) in byLeague) {
                val model = repo.modelFor(league) ?: continue
                for (f in list) {
                    val p = model.predict(f) ?: continue
                    predictions[f.key] = p
                    acc[f.key] = doubleArrayOf(p.pHome, p.pDraw, p.pAway)
                }
                _quick.value = HashMap(acc)
            }
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
        _screen.value = Screen.Fixtures
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

    fun daysSinceSync(): Long {
        val last = repo.lastSyncEpochDay
        return if (last < 0) -1 else Dates.today() - last
    }
}
