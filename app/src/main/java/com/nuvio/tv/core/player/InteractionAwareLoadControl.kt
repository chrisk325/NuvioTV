package com.nuvio.tv.core.player

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Wraps the player's [LoadControl] and pauses buffer top-up while the user is interacting
 * with the player UI, as long as at least [comfortBufferUs] is already buffered.
 *
 * Everything else, including whether to start playback, is the delegate's decision. The
 * delegate is still asked first on every call so its own per-player loading state stays
 * current; the gate can only turn a "keep loading" into a "not now", never the reverse.
 * Below the comfort level, during a rebuffer, or once the UI goes idle, loading carries on
 * exactly as the delegate decides. ExoPlayer re-asks on every work loop, so loading resumes
 * on its own when the hold ends.
 */
@UnstableApi
class InteractionAwareLoadControl(
    private val delegate: LoadControl,
    private val comfortBufferUs: Long = DEFAULT_COMFORT_BUFFER_US,
    private val isHolding: () -> Boolean = PlaybackInteractionGate::isHolding
) : LoadControl {

    override fun getAllocator(): Allocator = delegate.allocator

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val delegateSays = delegate.shouldContinueLoading(parameters)
        val held = delegateSays && shouldHold(parameters)
        // The stats overlay shows this decision (LoadDecisionProbe); recording it changes nothing.
        LoadDecisionProbe.record(parameters.bufferedDurationUs, delegateSays, held, delegate)
        return delegateSays && !held
    }

    private fun shouldHold(parameters: LoadControl.Parameters): Boolean =
        !parameters.rebuffering &&
            parameters.bufferedDurationUs >= comfortBufferUs &&
            isHolding()

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long
    ): Boolean = delegate.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    companion object {
        /**
         * 20 s is far above any start or rebuffer threshold, and at 70 Mbps still under 200 MB,
         * so a byte-capped buffer that cannot reach it simply never holds.
         */
        const val DEFAULT_COMFORT_BUFFER_US = 20_000_000L
    }
}
