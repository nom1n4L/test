package com.skorsnap.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skorsnap.app.capture.CaptureBus
import com.skorsnap.app.data.Analyst
import com.skorsnap.app.data.Appetite
import com.skorsnap.app.data.Football
import com.skorsnap.app.data.MatchPrediction
import com.skorsnap.app.data.Mode
import com.skorsnap.app.data.Lens
import com.skorsnap.app.data.Odds
import com.skorsnap.app.data.Outcome
import com.skorsnap.app.data.Parlay
import com.skorsnap.app.data.Slip
import com.skorsnap.app.data.Store
import com.skorsnap.app.data.SavedSlip
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
    data object History : Screen
    data class AddMore(val id: String) : Screen
    data object Browse : Screen
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

    /** True while the floating capture button is on screen. */
    val capturing: StateFlow<Boolean> = CaptureBus.running

    val captureProblem: StateFlow<String?> = CaptureBus.problem

    /** Screens already read into text, waiting to be analysed. */
    val notes: StateFlow<List<String>> = CaptureBus.notes

    fun dropNote(index: Int) = CaptureBus.dropNote(index)

    fun clearNotes() = CaptureBus.clearNotes()

    init {
        // Screenshots taken by the floating button join the staging area exactly as
        // picked images do, so every path below this — band splitting, analysis,
        // token accounting — is the one already in use rather than a second one.
        viewModelScope.launch {
            CaptureBus.shots.collect { shots ->
                if (shots.isNotEmpty()) _staged.value = _staged.value + CaptureBus.take()
            }
        }
    }

    /**
     * How low a probability the user wants to be pointed at.
     *
     * Kept as a setting because the two complaints behind it contradict each other:
     * recommendations that were not marked safe, and recommendations that were too
     * safe to pay anything. Both are fair, so the floor belongs to the user.
     */
    private val _appetite = MutableStateFlow(store.appetite)
    val appetite: StateFlow<Appetite> = _appetite.asStateFlow()

    fun setAppetite(value: Appetite) {
        store.appetite = value
        _appetite.value = value
    }

    /** Fixtures fetched for a date, so a match can be picked instead of photographed. */
    private val _fixtures = MutableStateFlow<List<Football.Fixture>>(emptyList())
    val fixtures: StateFlow<List<Football.Fixture>> = _fixtures.asStateFlow()

    private val _fixturesBusy = MutableStateFlow(false)
    val fixturesBusy: StateFlow<Boolean> = _fixturesBusy.asStateFlow()

    /**
     * What happened to the prices last pasted for a match.
     *
     * Kept per match and shown inside its own card rather than in the snackbar: the
     * report runs to several lines and a snackbar would cut it off, which is how a
     * working feature came to look like nothing happening.
     */
    private val _oddsReport = MutableStateFlow<Map<String, String>>(emptyMap())
    val oddsReport: StateFlow<Map<String, String>> = _oddsReport.asStateFlow()

    /** Bookmaker prices fetched with a fixture, kept as a reference per match. */
    private val _fetchedOdds = MutableStateFlow<Map<String, Map<String, Double>>>(emptyMap())
    val fetchedOdds: StateFlow<Map<String, Map<String, Double>>> = _fetchedOdds.asStateFlow()

    private val _footballReport = MutableStateFlow<String?>(null)
    val footballReport: StateFlow<String?> = _footballReport.asStateFlow()

    fun saveAndCheckFootballKey(key: String) {
        store.footballKey = key
        if (key.isBlank()) {
            _footballReport.value = "Kunci dikosongkan."
            return
        }
        viewModelScope.launch {
            _fixturesBusy.value = true
            _footballReport.value = try {
                Football(store.footballKey).probe(today())
            } catch (e: Exception) {
                "Gagal: ${e.message}"
            }
            _fixturesBusy.value = false
        }
    }

    fun loadFixtures(date: String) {
        if (_fixturesBusy.value) return
        viewModelScope.launch {
            _fixturesBusy.value = true
            _footballReport.value = null
            try {
                val found = Football(store.footballKey).fixtures(date)
                _fixtures.value = found
                if (found.isEmpty()) {
                    _footballReport.value = "Tidak ada pertandingan pada $date."
                }
            } catch (e: Exception) {
                _footballReport.value = "Gagal: ${e.message}"
            }
            _fixturesBusy.value = false
        }
    }

    /**
     * Analyses a fixture from fetched statistics, plus any staged screenshots.
     *
     * The screenshots stay optional and stay useful: the free feed carries no corner
     * data at all, which is the one thing this user's strategy runs on. Fetching what
     * is available and photographing only what is not is the whole point of the
     * arrangement.
     */
    fun analyseFixture(fixture: Football.Fixture, note: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                val football = Football(store.footballKey)
                val stats = football.statsBrief(fixture)
                val analyst = Analyst(store.apiKey)
                val result = analyst.analyse(
                    _staged.value, note, store.model, _mode.value,
                    _matches.value, _slips.value, null, _appetite.value, stats,
                ).copy(
                    model = store.model,
                    home = fixture.home,
                    away = fixture.away,
                    league = fixture.where,
                )
                _lastUsage.value = analyst.lastUsage
                val updated = _matches.value + result
                _matches.value = updated
                store.save(updated)
                _staged.value = emptyList()
                _screen.value = Screen.Detail(result.id)
                // Shown as a reference rather than written into the legs. The feed
                // names markets its own way ("Match Winner: Home"), and quietly
                // matching those to the app's names would put a price on the wrong
                // bet — the one error here that costs money silently.
                runCatching { football.odds(fixture.id) }.getOrNull()?.let { prices ->
                    if (prices.isNotEmpty()) {
                        _fetchedOdds.value = _fetchedOdds.value + (result.id to prices)
                    }
                }
            } catch (e: Exception) {
                _message.value = "Gagal: ${e.message}"
            }
            _busy.value = false
        }
    }

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

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

    /**
     * A market picked by hand for a match, overriding the strategy.
     *
     * Telling the user a leg is badly priced without letting them do anything about
     * it is half a feature; this is the other half.
     */
    private val _chosen = MutableStateFlow<Map<String, String>>(emptyMap())
    val chosen: StateFlow<Map<String, String>> = _chosen.asStateFlow()

    private val _slips = MutableStateFlow(store.loadSlips())
    val slips: StateFlow<List<SavedSlip>> = _slips.asStateFlow()

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
        val pages = CaptureBus.notes.value
        if (images.isEmpty() && pages.isEmpty()) {
            _message.value = "Belum ada layar terbaca atau gambar."
            return
        }
        // Pages already transcribed go in as text. They cost a few hundred tokens
        // each where the same screens as images cost tens of thousands, and they
        // are the numbers the user could already see and check.
        val read = if (pages.isEmpty()) "" else buildString {
            append("STATISTIK YANG SUDAH DIBACA DARI LAYAR PENGGUNA ")
            append("(${pages.size} halaman). Angka di sini hasil salinan langsung dari ")
            append("aplikasi statistiknya — perlakukan seperti angka yang kamu baca sendiri ")
            append("dari gambar. Baris berawalan \"TIDAK JELAS\" berarti angkanya tidak ")
            append("terbaca; masukkan ke stats_missing, jangan ditebak.\n\n")
            pages.forEachIndexed { i, page -> append("--- Halaman ${i + 1} ---\n$page\n\n") }
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                val analyst = Analyst(store.apiKey)
                val result = analyst
                    .analyse(
                        images, note, store.model, _mode.value,
                        _matches.value, _slips.value, null, _appetite.value, read,
                    )
                    .copy(model = store.model)
                _lastUsage.value = analyst.lastUsage
                val updated = _matches.value + result
                _matches.value = updated
                store.save(updated)
                _staged.value = emptyList()
                CaptureBus.clearNotes()
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
     * A second pass over the same match with extra screenshots.
     *
     * Replaces the analysis in place, keeping the match's id and every verdict
     * already recorded against it: the markets are the same markets, and a result
     * the user marked is a fact about the world, not about this reading.
     */
    fun reanalyse(id: String, note: String) {
        if (_busy.value) return
        val previous = _matches.value.firstOrNull { it.id == id } ?: return
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
                val fresh = analyst.analyse(
                    images, note, store.model, previous.mode,
                    _matches.value, _slips.value, previous, _appetite.value,
                ).copy(
                    id = previous.id,
                    model = store.model,
                    marketOutcomes = previous.marketOutcomes,
                    backed = previous.backed,
                )
                _lastUsage.value = analyst.lastUsage
                val updated = _matches.value.map { if (it.id == id) fresh else it }
                _matches.value = updated
                store.save(updated)
                _staged.value = emptyList()
                _screen.value = Screen.Detail(id)
                _message.value = "Analisis diperbarui dengan data tambahan."
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
        // A settled match has already been played; leaving it checked would build a
        // parlay out of results that are already known.
        updated.firstOrNull { it.id == id && it.settled }?.let {
            _selected.value = _selected.value - id
        }
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

    fun setStrategy(value: Strategy) {
        _strategy.value = value
        // A strategy is a rule for choosing; keeping hand-picked legs on top of it
        // would show a slip that matches neither.
        _chosen.value = emptyMap()
    }

    fun chooseMarket(matchId: String, market: String) {
        _chosen.value = _chosen.value + (matchId to market)
    }

    /** Swaps every leg for the best-priced market in its match, where one exists. */
    fun takeBestPriced(matches: List<MatchPrediction>) {
        val better = matches.mapNotNull { m ->
            Parlay.bestPriced(m, _legOdds.value)?.let { m.id to it.market }
        }
        _chosen.value = _chosen.value + better
    }

    fun saveSlip(slip: Slip, stake: Double) {
        if (slip.size == 0) return
        val record = SavedSlip(
            id = java.util.UUID.randomUUID().toString(),
            placedAt = System.currentTimeMillis(),
            strategy = _strategy.value.label,
            legs = slip.legs,
            stake = stake,
        )
        val updated = _slips.value + record
        _slips.value = updated
        store.saveSlips(updated)
        _message.value = "Slip disimpan. Tandai hasilnya di Rapor setelah laganya selesai."
    }

    fun markSlip(id: String, outcome: Outcome) {
        val updated = _slips.value.map { s ->
            if (s.id != id) s
            else s.copy(outcome = if (s.outcome == outcome) Outcome.PENDING else outcome)
        }
        _slips.value = updated
        store.saveSlips(updated)
    }

    fun removeSlip(id: String) {
        val updated = _slips.value.filterNot { it.id == id }
        _slips.value = updated
        store.saveSlips(updated)
    }

    /** Empties the slip. Typed prices are kept — they cost effort to re-enter. */
    fun clearSelection() { _selected.value = emptySet() }

    /** A price at or below 1.00 is not a price; it clears the entry instead. */
    fun setLegOdds(key: String, odds: Double) {
        _legOdds.value =
            if (odds <= 1.0) _legOdds.value - key else _legOdds.value + (key to odds)
        autoSwap(key.substringBefore('|'))?.let { _message.value = it }
    }

    /**
     * Pastes a whole block of prices at once and reports what could not be placed.
     *
     * The swap is only useful when several markets have prices — with one entered
     * there is nothing to swap to — so this is what makes the feature work at all.
     */
    fun applyOdds(matchId: String, text: String) {
        val match = _matches.value.firstOrNull { it.id == matchId } ?: return
        val entries = Odds.parse(text)
        if (entries.isEmpty()) {
            _oddsReport.value = _oddsReport.value + (matchId to
                "Tidak ada harga yang terbaca. Satu market per baris, harganya di akhir baris.")
            return
        }
        val matched = Odds.match(entries, match.markets)
        _legOdds.value = _legOdds.value + matched.pairs.mapKeys { "$matchId|${it.key.substringAfter('|')}" }
        val swap = autoSwap(matchId)

        // Reporting only a count told the user nothing: not which market the price
        // landed on, not whether it clears, not why the leg did or did not move.
        // "1 harga terpasang" looked exactly like nothing happening.
        val report = Odds.describe(matched, match.markets) +
            (swap ?: nothingSwapped(match, matchId))
        _oddsReport.value = _oddsReport.value + (matchId to report)
        _message.value = null
    }

    /** Why a leg stayed put, which is as worth saying as why it moved. */
    private fun nothingSwapped(match: MatchPrediction, matchId: String): String {
        val currentName = _chosen.value[matchId]
            ?: Parlay.build(listOf(match), _strategy.value).legs.firstOrNull()?.market
        val current = match.markets.firstOrNull { it.name == currentName }
        val price = current?.let { _legOdds.value["$matchId|${it.name}"] } ?: 0.0
        return when {
            current == null -> "Laga ini belum punya leg di parlaymu."
            price > 1.0 && price * current.prob - 1.0 > 0 ->
                "Leg tetap ${current.name} — harganya sudah di atas minimal, jadi tidak diganti."
            _legOdds.value.keys.count { it.startsWith("$matchId|") } < 2 ->
                "Leg masih ${current.name}. Isi harga beberapa market lain dulu — " +
                    "dengan satu harga saja tidak ada pembandingnya."
            else ->
                "Leg masih ${current.name}. Tidak ada market lain yang harganya di atas " +
                    "minimal, jadi tidak ada yang layak ditukar."
        }
    }

    /**
     * Moves a leg off a market the bookmaker underpays for.
     *
     * Runs on every price change, so entering 1.27 against a market that needs 1.30
     * moves the leg by itself rather than leaving a losing bet in the slip with a
     * warning next to it.
     */
    private fun autoSwap(matchId: String): String? {
        val match = _matches.value.firstOrNull { it.id == matchId } ?: return null
        val currentName = _chosen.value[matchId]
            ?: Parlay.build(listOf(match), _strategy.value).legs.firstOrNull()?.market
        val current = match.markets.firstOrNull { it.name == currentName }
        val better = Parlay.swapIfUnderpriced(match, current, _legOdds.value, _appetite.value.floor)
            ?: return null
        _chosen.value = _chosen.value + (matchId to better.name)
        // Returned rather than written straight to the message, because applyOdds
        // writes afterwards and used to overwrite this — the swap happened and the
        // user was never told.
        return "Leg diganti: ${current?.name ?: "kosong"} → ${better.name}."
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
