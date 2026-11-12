package com.nuvio.tv.ui.reshaped.livetv

import com.nuvio.tv.reshaped.livetv.LiveTvMediaSource
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.isUsingMpvEngine

/**
 * A channel change from one Live TV channel to another keeps the ExoPlayer it already has, so a
 * zap skips rebuilding renderers, codecs and the audio sink, which takes seconds on weak TVs.
 * The new channel is then set on that player by Nuvio's own switch. Returns false (rebuild as
 * usual) for anything else, including the mpv engine.
 */
internal fun PlayerRuntimeController.keepPlayerForLiveTvZap(newUrl: String): Boolean {
    val player = _exoPlayer ?: return false
    if (!LiveTvPlaybackRegistry.isLiveTv(currentStreamUrl) || !LiveTvPlaybackRegistry.isLiveTv(newUrl)) return false
    // Between a live channel and a past programme, the seek bar comes and goes: start its state over.
    if (LiveTvPlaybackRegistry.isCatchup(currentStreamUrl) || LiveTvPlaybackRegistry.isCatchup(newUrl)) resetPlaybackTimeline()
    com.nuvio.tv.reshaped.livetv.LiveTvStartTrace.begin()
    errorRetryJob?.cancel()
    errorRetryJob = null
    playbackPreparationJob?.cancel()
    playbackPreparationJob = null
    runCatching {
        player.stop()
        player.clearMediaItems()
    }
    return true
}

/**
 * Leaving the full screen player with a channel playing live: its connection is handed back to
 * the list's preview, which shows the channel last watched, instead of being closed and opened
 * again. Only when there is a preview to take it (previews on, and a link the list also plays:
 * a Stalker link is made for one play).
 */
internal fun PlayerRuntimeController.handLiveTvChannelBack() {
    if (_exoPlayer == null || isUsingMpvEngine()) return
    val url = currentStreamUrl
    if (!LiveTvPlaybackRegistry.isLiveTv(url) || LiveTvPlaybackRegistry.isCatchup(url)) return
    if (LiveTvPlaybackRegistry.listUrlFor(url) != url || !LiveTvPreferences.previews.value) return
    LiveTvMediaSource.handOver()
}
