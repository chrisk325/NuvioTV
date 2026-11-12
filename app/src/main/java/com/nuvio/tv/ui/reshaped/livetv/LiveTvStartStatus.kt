package com.nuvio.tv.ui.reshaped.livetv

import android.widget.Toast
import com.nuvio.tv.R
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

/** A start this slow tells the viewer, once it plays, where the time went. */
private const val SLOW_START_MS = 2_000L

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

/**
 * The channel's first picture is on screen: the start is logged, and a slow one is summed up on
 * screen for a moment, so the viewer can say where the time went.
 */
internal fun PlayerRuntimeController.reportLiveTvStart() {
    synchronized(startStatusJobs) { startStatusJobs.remove(this)?.cancel() }
    val summary = LiveTvStartTrace.latestFor(currentStreamUrl)?.picture() ?: return
    if (summary.totalMs < SLOW_START_MS) return
    Toast.makeText(
        context,
        context.getString(R.string.live_tv_start_summary, summary.totalMs / 1000.0, summary.steps),
        Toast.LENGTH_LONG,
    ).show()
}
