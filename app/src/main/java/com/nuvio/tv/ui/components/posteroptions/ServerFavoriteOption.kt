package com.nuvio.tv.ui.components.posteroptions

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerFavoriteResult
import com.nuvio.tv.data.mediaserver.ServerFavorites
import com.nuvio.tv.data.mediaserver.ServerFavoritesState
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServersUiState
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backs the "Favorite on Jellyfin" entry of the poster options dialogs. */
@HiltViewModel
class ServerFavoriteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favorites: ServerFavorites,
    repository: ServerRepository
) : ViewModel() {
    val servers: StateFlow<ServersUiState> = repository.uiState
    val favoritesState: StateFlow<ServerFavoritesState> = favorites.state

    fun isAvailable(type: String?): Boolean = favorites.supportsType(type) && favorites.isAvailable()

    fun serverName(): String? = favorites.serverName()

    fun ensureFresh() {
        favorites.ensureFresh()
    }

    fun isFavorite(ids: Collection<String>, type: String?, state: ServerFavoritesState): Boolean =
        ids.any { favorites.isFavorite(it, type, state) }

    /** Changes the favourite in the background, so it finishes after the dialog closes, and says how it went. */
    fun setFavorite(id: String, type: String, favorite: Boolean, name: String) {
        val serverName = favorites.serverName() ?: context.getString(R.string.servers_generic_name)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { favorites.setFavorite(id, type, favorite, name) }
            val message = when (result) {
                ServerFavoriteResult.ADDED -> context.getString(R.string.servers_favorite_added, serverName)
                ServerFavoriteResult.REMOVED -> context.getString(R.string.servers_favorite_removed, serverName)
                ServerFavoriteResult.NOT_FOUND -> context.getString(R.string.servers_favorite_not_found)
                ServerFavoriteResult.FAILED,
                ServerFavoriteResult.UNAVAILABLE -> context.getString(R.string.servers_favorite_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * A "Favorite on <server>" / "Unfavorite on <server>" button for [item], shown only while a connected server keeps
 * favourites. [aliases] are other ids [item] is known by (e.g. the "tmdb:" id it had before being looked up on IMDb).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ServerFavoriteOption(
    item: MetaPreview,
    onSelected: () -> Unit,
    aliases: List<String?> = emptyList(),
    viewModel: ServerFavoriteViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsState()
    val favoritesState by viewModel.favoritesState.collectAsState()
    val available = remember(item.apiType, servers.revision) { viewModel.isAvailable(item.apiType) }
    LaunchedEffect(available) {
        if (available) viewModel.ensureFresh()
    }
    if (!available) return

    val ids = remember(item.id, item.imdbId, aliases) {
        (listOf(item.id, item.imdbId) + aliases).filterNotNull().filter { it.isNotBlank() }.distinct()
    }
    val isFavorite = viewModel.isFavorite(ids, item.apiType, favoritesState)
    val serverName = remember(servers.revision) { viewModel.serverName() }
    // A server item changes by itself; any other title is matched to the server's copy by its ids.
    val targetId = ids.firstOrNull(ServerItemRef::isServerId) ?: item.id

    Button(
        onClick = {
            viewModel.setFavorite(targetId, item.apiType, favorite = !isFavorite, name = item.name)
            onSelected()
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Text(
            when {
                serverName == null && isFavorite -> stringResource(R.string.servers_favorite_remove_generic)
                serverName == null -> stringResource(R.string.servers_favorite_add_generic)
                isFavorite -> stringResource(R.string.servers_favorite_remove, serverName)
                else -> stringResource(R.string.servers_favorite_add, serverName)
            }
        )
    }
}
