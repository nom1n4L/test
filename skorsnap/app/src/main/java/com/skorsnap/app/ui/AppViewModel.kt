package com.skorsnap.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skorsnap.app.data.Analyst
import com.skorsnap.app.data.MatchPrediction
import com.skorsnap.app.data.Parlay
import com.skorsnap.app.data.Slip
import com.skorsnap.app.data.Store
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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** Models this key can actually call, fetched from Google rather than guessed. */
    private val _models = MutableStateFlow<List<Analyst.Model>>(emptyList())
    val models: StateFlow<List<Analyst.Model>> = _models.asStateFlow()

    private val _modelsBusy = MutableStateFlow(false)
    val modelsBusy: StateFlow<Boolean> = _modelsBusy.asStateFlow()

    fun loadModels() {
        if (_modelsBusy.value) return
        viewModelScope.launch {
            _modelsBusy.value = true
            try {
                val found = Analyst(store.apiKey).listModels()
                _models.value = found
                _message.value = if (found.isEmpty()) {
                    "Tidak ada model yang bisa dipakai kunci ini."
                } else {
                    "${found.size} model tersedia. Memuat daftar ini tidak memakai kuota."
                }
                // A remembered choice that is no longer offered would fail again on
                // the next analysis, so move to something that works.
                if (found.isNotEmpty() && found.none { it.id == store.model }) {
                    val preferred = found.firstOrNull { "flash" in it.id } ?: found.first()
                    store.model = preferred.id
                    _message.value = "Model sebelumnya tidak tersedia. Diganti ke ${preferred.id}."
                }
            } catch (e: Exception) {
                _message.value = "Gagal: ${e.message}"
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
            val read = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
            }
            if (read.isEmpty()) {
                _message.value = "Gambarnya tidak bisa dibaca."
                return@launch
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
                val result = Analyst(store.apiKey).analyse(images, note, store.model)
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

    fun matchOf(id: String): MatchPrediction? = _matches.value.firstOrNull { it.id == id }

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

    fun toggle(id: String) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    fun slip(): Slip = Parlay.of(_matches.value.filter { it.id in _selected.value })

    fun setApiKey(key: String) {
        store.apiKey = key
    }

    fun setModel(model: String) {
        store.model = model
    }
}
