package com.nuvio.tv.ui.reshaped.livetv

import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import com.nuvio.tv.reshaped.livetv.LiveTvStartTrace
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import java.util.WeakHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The job keeping each player screen's loading line on what its channel start waits for. */
private val startStatusJobs = WeakHashMap<PlayerRuntimeController, Job>()

/** How often the loading line is brought up to date. */
private const val STATUS_TICK_MS = 250L

/**
 * While the channel just asked for loads, the loading line says what it is waiting for (see
 * [LiveTvStartTrace.describe]) instead of a plain "Starting stream", until its first picture.
 */
internal fun PlayerRuntimeController.followLiveTvStart() {
    val url = currentStreamUrl
    if (!LiveTvPlaybackRegistry.isLiveTv(url)) return
    val player = _exoPlayer ?: return
    synchronized(startStatusJobs) { startStatusJobs.remove(this)?.cancel() }
    val job = scope.launch {
        while (isActive && !hasRenderedFirstFrame && currentStreamUrl == url && _exoPlayer === player) {
            val text = LiveTvStartTrace.latestFor(url)?.describe(context)
            if (text != null) {
                _uiState.update { state ->
                    if (state.showLoadingOverlay && state.loadingMessage != text) state.copy(loadingMessage = text) else state
                }
            }
            delay(STATUS_TICK_MS)
        }
    }
    synchronized(startStatusJobs) { startStatusJobs[this] = job }
}

/** The channel's first picture is on screen: the loading line stops following it, and the start is logged. */
internal fun PlayerRuntimeController.reportLiveTvStart() {
    synchronized(startStatusJobs) { startStatusJobs.remove(this)?.cancel() }
    LiveTvStartTrace.latestFor(currentStreamUrl)?.picture()
}
