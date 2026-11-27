package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerFavorites
import com.nuvio.tv.data.mediaserver.ServerFavoritesState
import com.nuvio.tv.data.mediaserver.ServerLibraryRef
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServersUiState
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.domain.model.CatalogRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryServerShelf(
    val key: String,
    val title: String,
    val row: CatalogRow? = null,
    val failure: ServerFailure? = null
)

@HiltViewModel
class LibraryServersViewModel @Inject constructor(
    private val serverCatalog: ServerCatalog,
    private val favorites: ServerFavorites,
    private val store: LibraryServerShelfStore,
    libraryPreferences: LibraryPreferences,
    repository: ServerRepository
) : ViewModel() {
    val shelvesAsGrid: StateFlow<Boolean> = libraryPreferences.serverShelvesAsGrid
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val servers: StateFlow<ServersUiState> = repository.uiState
    val favoritesState: StateFlow<ServerFavoritesState> = favorites.state
    private val _shelves = MutableStateFlow<List<LibraryServerShelf>?>(null)
    val shelves: StateFlow<List<LibraryServerShelf>?> = _shelves.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private var loadJob: Job? = null
    private var loadedRevision: Int? = null
    private var loadedWrites: Int? = null
    private var shownSetupKey: String? = null
    var focusedShelfKey: String? = null
        private set
    val focusedIndexes = mutableMapOf<String, Int>()

    fun onItemFocused(shelfKey: String, index: Int) {
        focusedShelfKey = shelfKey
        focusedIndexes[shelfKey] = index
    }

    /**
     * Loads the shelves again when the servers changed or a favourite was written since the last load. With nothing on
     * screen yet, as after the app starts, the shelves last kept for the same server setup show at once while they
     * load again.
     */
    fun load() {
        val revision = servers.value.revision
        val writes = favorites.state.value.writes
        if (loadedRevision == revision && loadedWrites == writes && _shelves.value != null) return
        loadedRevision = revision
        loadedWrites = writes
        favorites.ensureFresh()
        loadJob?.cancel()
        _refreshing.value = false
        val setupKey = store.setupKey(servers.value.enabledConnections)
        val showKept = _shelves.value == null || shownSetupKey != setupKey
        loadJob = viewModelScope.launch {
            if (showKept) {
                val kept = withContext(Dispatchers.IO) { store.read(setupKey) }
                // Shelves from another server setup would show the wrong libraries; the spinner is better.
                _shelves.value = kept ?: _shelves.value?.takeIf { shownSetupKey == setupKey }
                if (kept != null) _refreshing.value = true
            }
            try {
                show(setupKey, loadShelves())
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Reloads the favourites and every shelf from the servers on request, keeping the shelves already on screen until
     * the new ones arrive.
     */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                favorites.ensureFresh(force = true)?.join()
                loadedRevision = servers.value.revision
                loadedWrites = favorites.state.value.writes
                show(store.setupKey(servers.value.enabledConnections), loadShelves())
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun show(setupKey: String, shelves: List<LibraryServerShelf>) {
        _shelves.value = shelves
        shownSetupKey = setupKey
        withContext(Dispatchers.IO) { store.write(setupKey, shelves) }
    }

    private suspend fun loadShelves(): List<LibraryServerShelf> = coroutineScope {
        serverCatalog.libraryShelves().map { ref -> async { shelf(ref) } }.awaitAll()
    }

    private suspend fun shelf(ref: ServerLibraryRef): LibraryServerShelf {
        val key = "${ref.connection.id}:${ref.library.id}"
        return try {
            LibraryServerShelf(key, ref.title, row = serverCatalog.row(ref.connection.id, ref.library.id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LibraryServerShelf(key, ref.title, failure = error.serverFailure())
        }
    }
}
