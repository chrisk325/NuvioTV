package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * DefaultLoadControl that keeps the forward buffer topped up during playback.
 *
 * Stock DefaultLoadControl loads until Max Buffer (or the byte target) is reached, then stops
 * and only resumes once the buffer has drained below Min Buffer. So the first fill goes all
 * the way ahead, but afterwards the buffer sags to Min Buffer before any more is fetched,
 * which looks like the player stopped buffering ahead. Here loading resumes as soon as there
 * is room below Max Buffer and under the byte target again.
 */
@UnstableApi
open class TopUpLoadControl(
    allocator: DefaultAllocator,
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    private val fixedTargetBufferBytes: Int,
    prioritizeTimeOverSizeThresholds: Boolean,
    backBufferDurationMs: Int,
    retainBackBufferFromKeyframe: Boolean
) : DefaultLoadControl(
    allocator,
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    fixedTargetBufferBytes,
    prioritizeTimeOverSizeThresholds,
    backBufferDurationMs,
    retainBackBufferFromKeyframe
) {

    private val topUpAllocator = allocator
    private val maxBufferUs = maxBufferMs.toLong() * 1000L

    /**
     * Byte target the top-up must stay under. Unknown ([C.LENGTH_UNSET]) disables the top-up,
     * leaving the stock behaviour, since loading past the memory target is never safe.
     */
    protected open fun topUpTargetBytes(): Int = fixedTargetBufferBytes

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // Super still owns the below-Min Buffer rules (including prioritizeTimeOverSizeThresholds)
        // and its per-player loading state.
        if (super.shouldContinueLoading(parameters)) return true
        if (parameters.bufferedDurationUs >= maxBufferUs) return false
        val targetBytes = topUpTargetBytes()
        if (targetBytes == C.LENGTH_UNSET || targetBytes <= 0) return false
        return topUpAllocator.totalBytesAllocated < targetBytes
    }
}
