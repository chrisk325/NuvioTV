package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.data.mediaserver.ServerNextUpSource
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerResumeImport
import com.nuvio.tv.data.mediaserver.ServersUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class ServerResumeImportViewModel @Inject constructor(
    private val resumeImport: ServerResumeImport,
    private val nextUpSource: ServerNextUpSource,
    repository: ServerRepository
) : ViewModel() {
    val servers: StateFlow<ServersUiState> = repository.uiState

    fun refresh() {
        resumeImport.refresh()
        nextUpSource.refresh()
    }
}

/**
 * Servers with "Import watch progress" on fill Continue watching with what is in progress on them, and Next Up
 * with the next episode of each show (AIOMetadata's Jellyfin Next Up included): each time Home comes back, and
 * when the servers change.
 */
@Composable
fun ServerResumeImportEffect(viewModel: ServerResumeImportViewModel = hiltViewModel()) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, servers.revision) {
        // Added to a resumed screen, the observer hears ON_RESUME right away.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
