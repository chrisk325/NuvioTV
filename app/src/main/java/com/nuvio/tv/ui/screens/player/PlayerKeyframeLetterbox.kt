package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.nuvio.tv.core.player.LetterboxDetector
import com.nuvio.tv.core.player.LetterboxVote
import com.nuvio.tv.ui.screens.player.seekpreview.openSeekPreviewFeed
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

private const val KEYFRAME_COUNT = 12
private const val KEYFRAME_TIMEOUT_MS = 20_000L
private const val KEYFRAME_REQUIRED_VOTES = 5

/** Where to ask for keyframes: spread over the film, past opening logos and before end credits. */
internal fun keyframeLetterboxPositions(durationMs: Long, count: Int = KEYFRAME_COUNT): List<Long> {
    if (durationMs <= 0L || count <= 0) return emptyList()
    val first = durationMs * 0.08
    val last = durationMs * 0.88
    if (count == 1) return listOf(((first + last) / 2).toLong())
    return (0 until count).map { (first + (last - first) * it / (count - 1)).toLong() }
}

/**
 * Finds a stream's baked-in bars from keyframes the native seek-preview thumbnailer decodes in
 * software (HDR tone-mapped to SDR). This works on TVs whose decoder output cannot be read back,
 * at the cost of fetching a dozen keyframes, so it runs only when nothing else found the bars.
 *
 * Returns the agreed bar (0 for none), or null when the keyframes could not be read or did not agree.
 */
internal suspend fun detectLetterboxFromKeyframes(
    url: String,
    headers: Map<String, String>,
    durationMs: Long
): Float? {
    val positions = keyframeLetterboxPositions(durationMs)
    if (positions.isEmpty()) return null
    val frames = Channel<KeyframeEvent>(Channel.UNLIMITED)
    val feed = openSeekPreviewFeed(url, headers) { positionMs, bitmap, _ ->
        frames.trySend(
            when {
                // -1 when the file could not be opened at all.
                positionMs < 0L -> KeyframeEvent.Unavailable
                bitmap != null -> KeyframeEvent.Frame(bitmap)
                else -> KeyframeEvent.Skipped
            }
        )
    } ?: return null
    val vote = LetterboxVote(requiredVotes = KEYFRAME_REQUIRED_VOTES, maxSamples = KEYFRAME_COUNT * 2)
    val pixels = IntArray(LetterboxDetector.SAMPLE_WIDTH * LetterboxDetector.SAMPLE_HEIGHT)
    try {
        for (position in positions) {
            feed.want(position, null)
            var event = withTimeoutOrNull(KEYFRAME_TIMEOUT_MS) { frames.receive() } ?: return null
            // The thumbnailer may hand over neighbouring keyframes too; each one is a sample.
            while (true) {
                when (event) {
                    KeyframeEvent.Unavailable -> return null
                    KeyframeEvent.Skipped -> Unit
                    is KeyframeEvent.Frame -> barOf(event.bitmap, pixels).let(vote::onSample)?.let { return it }
                }
                event = frames.tryReceive().getOrNull() ?: break
            }
            if (vote.isExhausted) return null
        }
        return null
    } finally {
        feed.close()
        frames.close()
    }
}

private sealed interface KeyframeEvent {
    data class Frame(val bitmap: ImageBitmap) : KeyframeEvent
    data object Skipped : KeyframeEvent
    data object Unavailable : KeyframeEvent
}

private fun barOf(frame: ImageBitmap, pixels: IntArray): Float? {
    val scaled = Bitmap.createScaledBitmap(
        frame.asAndroidBitmap(),
        LetterboxDetector.SAMPLE_WIDTH,
        LetterboxDetector.SAMPLE_HEIGHT,
        true
    )
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    return LetterboxDetector.detectBar(pixels, scaled.width, scaled.height)
}
