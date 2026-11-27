package com.nuvio.tv.core.player

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl

/**
 * The player's last loading decision, for the stats overlay (PlayerDebugStatsOverlay): whether it
 * kept loading, and if not, what stopped it. [InteractionAwareLoadControl] records it each time
 * Media3 asks, which is every 1 MB or so while a load runs and every work loop while it does not.
 *
 * Written on the playback thread, read on another; each field is read on its own, so one reading
 * can mix two decisions, which is fine for a once-a-second display.
 */
@UnstableApi
internal object LoadDecisionProbe {
    /** When the last decision was recorded ([SystemClock.elapsedRealtime]), or 0 for never. */
    @Volatile var decidedAtMs: Long = 0L
        private set
    /** What the wrapped load control said. */
    @Volatile var delegateSaidLoad: Boolean = false
        private set
    /** The interaction hold turned a yes into a no. */
    @Volatile var held: Boolean = false
        private set
    @Volatile var bufferedUs: Long = 0L
        private set
    /** Bytes the allocator has handed out, or -1 when the load control does not say. */
    @Volatile var allocatedBytes: Long = -1L
        private set
    /** The byte target the load control stops at, or -1 when it does not say. */
    @Volatile var targetBytes: Long = -1L
        private set
    /** The duration the load control stops at, or -1 when it does not say. */
    @Volatile var maxBufferUs: Long = -1L
        private set

    val loading: Boolean get() = delegateSaidLoad && !held

    fun record(bufferedDurationUs: Long, delegateSays: Boolean, heldNow: Boolean, delegate: LoadControl) {
        bufferedUs = bufferedDurationUs
        delegateSaidLoad = delegateSays
        held = heldNow
        allocatedBytes = runCatching { delegate.allocator.totalBytesAllocated.toLong() }.getOrDefault(-1L)
        val topUp = delegate as? TopUpLoadControl
        targetBytes = topUp?.let { runCatching { it.currentTargetBytes().toLong() }.getOrNull() } ?: -1L
        maxBufferUs = topUp?.maxBufferDurationUs ?: -1L
        decidedAtMs = SystemClock.elapsedRealtime()
    }

    fun reset() {
        decidedAtMs = 0L
        delegateSaidLoad = false
        held = false
        bufferedUs = 0L
        allocatedBytes = -1L
        targetBytes = -1L
        maxBufferUs = -1L
    }
}
