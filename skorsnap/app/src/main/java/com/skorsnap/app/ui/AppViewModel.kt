package com.skorsnap.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skorsnap.app.data.Analyst
import com.skorsnap.app.data.MatchPrediction
import com.skorsnap.app.data.Mode
import com.skorsnap.app.data.Lens
import com.skorsnap.app.data.Outcome
import com.skorsnap.app.data.Parlay
import com.skorsnap.app.data.Slip
import com.skorsnap.app.data.Store
import com.skorsnap.app.data.Strategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Add : Screen
    data class Detail(val id: String) : Screen
    data object Slip : Screen
    data object Report : Screen
    data object Settings : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val store = Store(app)

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _matches = MutableStateFlow(store.load())
    val matches: StateFlow<List<MatchPrediction>> = _matches.asStateFlow()

    /** Images staged for the next analysis, as raw bytes read from the picker. */
    private val _staged = MutableStateFlow<List<ByteArray>>(emptyList())
    val staged: StateFlow<List<ByteArray>> = _staged.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _mode = MutableStateFlow(Mode.MATCH)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    fun setMode(mode: Mode) {
        _mode.value = mode
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** How the parlay screen takes one market from each selected match. */
    private val _strategy = MutableStateFlow(Strategy.RECOMMENDED)
    val strategy: StateFlow<Strategy> = _strategy.asStateFlow()

    /**
     * Bookmaker prices the user typed in, keyed by match and market.
     *
     * Held here rather than in the screen so switching strategy or opening a leg
     * does not throw away numbers that were read off another app by hand.
     */
    private val _legOdds = MutableStateFlow<Map<String, Double>>(emptyMap())
    val legOdds: StateFlow<Map<String, Double>> = _legOdds.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** Models this key can actually call, fetched from Google rather than guessed. */
    private val _modelReport = MutableStateFlow<String?>(null)
    val modelReport: StateFlow<String?> = _modelReport.asStateFlow()

    /**
     * Tokens the last analysis consumed.
     *
     * Spending was invisible until the bill arrived: a long capture is tens of
     * thousands of input tokens and the user had no way to see that before choosing
     * a model. Showing it after each run makes the cost of a Pro model obvious while
     * there is still a decision to make.
     */
    private val _lastUsage = MutableStateFlow<Analyst.Usage?>(null)
    val lastUsage: StateFlow<Analyst.Usage?> = _lastUsage.asStateFlow()

    private val _models = MutableStateFlow<List<Analyst.Model>>(emptyList())
    val models: StateFlow<List<Analyst.Model>> = _models.asStateFlow()

    private val _modelsBusy = MutableStateFlow(false)
    val modelsBusy: StateFlow<Boolean> = _modelsBusy.asStateFlow()

    /**
     * Saves the key and immediately checks it.
     *
     * The field used to save silently on every keystroke, which left no way to
     * tell whether anything had been stored — the user pasted a key and had no
     * idea what to do next. Saving and verifying in one press removes the
     * question.
     */
    fun saveAndCheckKey(key: String) {
        store.apiKey = key
        if (key.isBlank()) {
            _modelReport.value = "Kunci dikosongkan."
            return
        }
        loadModels()
    }

    fun loadModels() {
        if (_modelsBusy.value) return
        viewModelScope.launch {
            _modelsBusy.value = true
            try {
                val found = Analyst(store.apiKey).listModels()
                _models.value = found
                _modelReport.value = if (found.isEmpty()) {
                    "Kunci diterima, tapi tidak ada model yang bisa dipakai."
                } else {
                    "Kunci tersimpan dan berfungsi. ${found.size} model tersedia — " +
                        "memuat daftar ini tidak memakai kuota."
                }
                // A remembered choice that is no longer offered would fail again on
                // the next analysis, so move to something that works.
                if (found.isNotEmpty() && found.none { it.id == store.model }) {
                    val preferred = found.firstOrNull { "flash" in it.id } ?: found.first()
                    store.model = preferred.id
                    _modelReport.value = "Model sebelumnya tidak tersedia. Diganti ke ${preferred.id}."
                }
            } catch (e: Exception) {
                _modelReport.value = "Gagal: ${e.message}"
            }
            _modelsBusy.value = false
        }
    }

    fun go(screen: Screen) {
        _screen.value = screen
    }

    fun back() {
        _screen.value = Screen.Home
    }

    fun dismissMessage() {
        _message.value = null
    }

    /**
     * Reads the picked images into memory. Screenshots run to a few megabytes at
     * most and there are only ever a handful, so holding the bytes is simpler than
     * juggling content URIs whose permission grants expire.
     */
    fun stage(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            var oversized = 0
            val read = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        // Reading the file is cheap next to decoding it, but a
                        // pathological pick should still not take the process down.
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null && bytes.size > MAX_FILE_BYTES) {
                            oversized++
                            null
                        } else {
                            bytes
                        }
                    }.getOrNull()
                }
            }
            if (read.isEmpty()) {
                _message.value = if (oversized > 0) {
                    "Gambarnya terlalu besar (di atas 40 MB). Coba screenshot yang lebih pendek."
                } else {
                    "Gambarnya tidak bisa dibaca."
                }
                return@launch
            }
            if (oversized > 0) {
                _message.value = "$oversized gambar dilewati karena di atas 40 MB."
            }
            _staged.value = _staged.value + read
        }
    }

    fun clearStaged() {
        _staged.value = emptyList()
    }

    fun removeStaged(index: Int) {
        _staged.value = _staged.value.filterIndexed { i, _ -> i != index }
    }

    fun analyse(note: String) {
        if (_busy.value) return
        val images = _staged.value
        if (images.isEmpty()) {
            _message.value = "Tambahkan dulu gambarnya."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                val analyst = Analyst(store.apiKey)
                val result = analyst
                    .analyse(images, note, store.model, _mode.value, _matches.value)
                    .copy(model = store.model)
                _lastUsage.value = analyst.lastUsage
                val updated = _matches.value + result
                _matches.value = updated
                store.save(updated)
                _staged.value = emptyList()
                _screen.value = Screen.Detail(result.id)
                if (!result.readable) {
                    _message.value = "Gambar terbaca sebagian: ${result.problem}"
                }
            } catch (e: Analyst.AnalystException) {
                _message.value = "Gagal: ${e.message}"
            } catch (e: Exception) {
                _message.value = "Gagal: ${e.message}"
            }
            _busy.value = false
        }
    }

    /**
     * Private on purpose. Reading the flow's value is not a Compose state read, so
     * a screen calling this would render once and then never update — the bug that
     * made saved verdicts invisible until the screen was reopened. Screens observe
     * [matches] instead.
     */
    private fun matchOf(id: String): MatchPrediction? = _matches.value.firstOrNull { it.id == id }

    /**
     * Records how a pick turned out. Tapping the same verdict twice clears it, so a
     * mis-tap does not quietly poison the record the whole screen exists to keep
     * honest.
     */
    /**
     * Records how one market turned out, by its key.
     *
     * Tapping the same verdict twice clears it, so a mis-tap does not quietly
     * poison the record the whole screen exists to keep honest.
     */
    fun markMarket(id: String, key: String, outcome: Outcome) {
        val updated = _matches.value.map { m ->
            if (m.id != id) m else {
                val next = m.marketOutcomes.toMutableMap()
                if (next[key] == outcome) next.remove(key) else next[key] = outcome
                m.copy(marketOutcomes = next)
            }
        }
        _matches.value = updated
        store.save(updated)
    }

    /** The same thing addressed by role rather than by key. */
    fun markOutcome(id: String, lens: Lens, outcome: Outcome) {
        val m = matchOf(id) ?: return
        markMarket(id, m.keyOf(m.marketFor(lens)), outcome)
    }

    /**
     * Records which market was actually backed, which is often not the pick.
     *
     * Changing it clears any result already recorded against it: that verdict was
     * about a different bet, and carrying it over would quietly file the outcome of
     * one market under another.
     */
    fun setBacked(id: String, market: String) {
        val updated = _matches.value.map { m ->
            when {
                m.id != id -> m
                m.backedMarket == market -> m
                // The verdict already recorded belongs to whichever market it
                // names, so switching bets neither carries it over nor loses it.
                else -> m.copy(backed = market)
            }
        }
        _matches.value = updated
        store.save(updated)
    }

    fun remove(id: String) {
        val updated = _matches.value.filterNot { it.id == id }
        _matches.value = updated
        _selected.value = _selected.value - id
        store.save(updated)
        if ((_screen.value as? Screen.Detail)?.id == id) _screen.value = Screen.Home
    }

    fun clearAll() {
        _matches.value = emptyList()
        _selected.value = emptySet()
        store.save(emptyList())
    }

    fun setStrategy(value: Strategy) { _strategy.value = value }

    /** Empties the slip. Typed prices are kept — they cost effort to re-enter. */
    fun clearSelection() { _selected.value = emptySet() }

    /** A price at or below 1.00 is not a price; it clears the entry instead. */
    fun setLegOdds(key: String, odds: Double) {
        _legOdds.value =
            if (odds <= 1.0) _legOdds.value - key else _legOdds.value + (key to odds)
    }

    fun toggle(id: String) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    fun setApiKey(key: String) {
        store.apiKey = key
    }

    fun setModel(model: String) {
        store.model = model
    }

    private companion object {
        /** Well above any real screenshot, low enough to refuse a pathological file. */
        const val MAX_FILE_BYTES = 40 * 1024 * 1024
    }

    /** Checks the chosen model with one cheap call rather than a whole analysis. */
    fun testModel() {
        if (_modelsBusy.value) return
        viewModelScope.launch {
            _modelsBusy.value = true
            _modelReport.value = try {
                Analyst(store.apiKey).testModel(store.model)
            } catch (e: Exception) {
                // A bare "Gagal." was what the broken test button showed for weeks,
                // and it told the user nothing they could act on.
                e.message ?: "Gagal tanpa keterangan (${e.javaClass.simpleName})."
            }
            _modelsBusy.value = false
        }
    }
}
