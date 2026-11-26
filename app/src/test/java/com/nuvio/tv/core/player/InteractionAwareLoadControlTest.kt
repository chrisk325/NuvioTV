package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class InteractionAwareLoadControlTest {

    private class FakeDelegate(var continueLoading: Boolean = true) : LoadControl {
        var continueLoadingCalls = 0
        override fun getAllocator(): Allocator = throw UnsupportedOperationException()
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
            continueLoadingCalls++
            return continueLoading
        }
        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = true
        override fun getBackBufferDurationUs(playerId: PlayerId): Long = 7L
    }

    private fun params(bufferedMs: Long, rebuffering: Boolean = false) = LoadControl.Parameters(
        PlayerId.UNSET,
        Timeline.EMPTY,
        MediaSource.MediaPeriodId(Any()),
        /* playbackPositionUs= */ 0L,
        /* bufferedDurationUs= */ bufferedMs * 1000L,
        /* playbackSpeed= */ 1f,
        /* playWhenReady= */ true,
        rebuffering,
        /* targetLiveOffsetUs= */ C.TIME_UNSET,
        /* lastRebufferRealtimeMs= */ C.TIME_UNSET
    )

    @After
    fun tearDown() {
        PlaybackInteractionGate.reset()
    }

    @Test
    fun `holds loading above the comfort buffer while interacting`() {
        val lc = InteractionAwareLoadControl(FakeDelegate(), isHolding = { true })
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 25_000)))
    }

    @Test
    fun `keeps loading below the comfort buffer even while interacting`() {
        val lc = InteractionAwareLoadControl(FakeDelegate(), isHolding = { true })
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 19_000)))
    }

    @Test
    fun `keeps loading during a rebuffer even with a full buffer`() {
        val lc = InteractionAwareLoadControl(FakeDelegate(), isHolding = { true })
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 30_000, rebuffering = true)))
    }

    @Test
    fun `loads normally when the user is idle`() {
        val lc = InteractionAwareLoadControl(FakeDelegate(), isHolding = { false })
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 40_000)))
    }

    @Test
    fun `never loads when the delegate says stop, and always asks the delegate`() {
        val delegate = FakeDelegate(continueLoading = false)
        val lc = InteractionAwareLoadControl(delegate, isHolding = { false })
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 1_000)))
        val holding = InteractionAwareLoadControl(delegate, isHolding = { true })
        holding.shouldContinueLoading(params(bufferedMs = 30_000))
        assertEquals(2, delegate.continueLoadingCalls)
    }

    @Test
    fun `start playback and back buffer come from the delegate`() {
        val lc = InteractionAwareLoadControl(FakeDelegate(), isHolding = { true })
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 0)))
        assertEquals(7L, lc.getBackBufferDurationUs(PlayerId.UNSET))
    }

    @Test
    fun `gate holds for a key press window and while the ui is active`() {
        var now = 1_000L
        PlaybackInteractionGate.clock = { now }
        try {
            assertFalse(PlaybackInteractionGate.isHolding())
            PlaybackInteractionGate.noteInteraction()
            now += PlaybackInteractionGate.KEY_HOLD_MS - 1
            assertTrue(PlaybackInteractionGate.isHolding())
            now += 1
            assertFalse(PlaybackInteractionGate.isHolding())

            PlaybackInteractionGate.setUiActive(true)
            assertTrue(PlaybackInteractionGate.isHolding())
            PlaybackInteractionGate.reset()
            assertFalse(PlaybackInteractionGate.isHolding())
        } finally {
            PlaybackInteractionGate.clock = { android.os.SystemClock.elapsedRealtime() }
        }
    }
}
