package com.nuvio.tv.core.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine

object LetterboxDetector {

    const val SAMPLE_INTERVAL_MS = 400L
    private const val SAMPLE_WINDOW_FRACTION = 0.2f

    const val SAMPLE_WIDTH = 64
    const val SAMPLE_HEIGHT = 120

    private const val DARK_LUMA = 40
    private const val BRIGHT_PIXEL_TOLERANCE = 0.03f
    private const val MIN_BAR_FRACTION = 0.03f
    private const val MAX_BAR_FRACTION = 0.2f
    private const val EDGE_PADDING = 0.01f

    fun detectBar(pixels: IntArray, width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val top = (0 until height).firstOrNull { !isDarkRow(pixels, width, it) } ?: return null
        val bottom = (height - 1 downTo 0).first { !isDarkRow(pixels, width, it) }
        val bar = minOf(top, height - 1 - bottom).toFloat() / height
        return when {
            bar > MAX_BAR_FRACTION -> null
            bar < MIN_BAR_FRACTION -> 0f
            else -> bar + EDGE_PADDING
        }
    }

    fun isSampleWindowOver(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * SAMPLE_WINDOW_FRACTION

    fun zoomFor(bar: Float): Float = if (bar <= 0f) 1f else 1f / (1f - 2f * bar)

    private fun isDarkRow(pixels: IntArray, width: Int, row: Int): Boolean {
        val offset = row * width
        val allowedBright = (width * BRIGHT_PIXEL_TOLERANCE).toInt()
        var bright = 0
        for (x in 0 until width) {
            if (luma(pixels[offset + x]) > DARK_LUMA && ++bright > allowedBright) return false
        }
        return true
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

class LetterboxSampler {

    private val bitmap = Bitmap.createBitmap(
        LetterboxDetector.SAMPLE_WIDTH,
        LetterboxDetector.SAMPLE_HEIGHT,
        Bitmap.Config.ARGB_8888
    )
    private val pixels = IntArray(LetterboxDetector.SAMPLE_WIDTH * LetterboxDetector.SAMPLE_HEIGHT)

    fun sample(textureView: TextureView): Float? {
        if (!textureView.isAvailable) return null
        textureView.getBitmap(bitmap)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return LetterboxDetector.detectBar(pixels, bitmap.width, bitmap.height)
    }

    fun release() {
        bitmap.recycle()
    }
}

class LetterboxTracker {

    private var streakBar: Float? = null
    private var streak = 0

    fun onSample(bar: Float): Float? {
        val current = streakBar
        val stableBar = if (current != null && abs(bar - current) <= STABLE_TOLERANCE) {
            streak++
            minOf(current, bar)
        } else {
            streak = 1
            bar
        }
        streakBar = stableBar
        if (streak < STABLE_SAMPLES || stableBar <= 0f) return null
        return LetterboxDetector.zoomFor(stableBar)
    }

    private companion object {
        const val STABLE_SAMPLES = 4
        const val STABLE_TOLERANCE = 0.015f
    }
}

/**
 * Decides the baked-in letterbox of a feature from frames sampled during playback.
 *
 * Unlike [LetterboxTracker] (trailers), a feature opens on logos, dark scenes and subtitles drawn
 * into the bars, so one stable streak is not enough: the bar must be the clear majority of the
 * frames that show a picture, and the smallest agreeing bar is kept so the picture is never
 * cropped past its edge.
 */
class LetterboxVote(
    private val requiredVotes: Int = REQUIRED_VOTES,
    private val maxSamples: Int = MAX_SAMPLES
) {
    private val bars = ArrayList<Float>()
    private var samples = 0

    val isExhausted: Boolean get() = samples >= maxSamples

    /**
     * Adds one sampled frame ([bar] is null for a frame without a usable picture) and returns the
     * agreed bar once enough frames agree: 0 when the frame has no bars.
     */
    fun onSample(bar: Float?): Float? {
        samples++
        if (bar == null) return null
        bars += bar
        val candidate = bars.maxBy { c -> bars.count { abs(it - c) <= TOLERANCE } }
        val agreeing = bars.filter { abs(it - candidate) <= TOLERANCE }
        if (agreeing.size < requiredVotes || agreeing.size * 2 <= bars.size) return null
        return agreeing.min()
    }

    private companion object {
        const val REQUIRED_VOTES = 6
        const val MAX_SAMPLES = 90
        const val TOLERANCE = 0.015f
    }
}

/**
 * Samples the frame shown on the player's video surface. A [SurfaceView] is read with
 * [PixelCopy], which copies the decoded frame regardless of how the view is laid out.
 * Tunneled and protected surfaces cannot be copied; those samples come back as [Failed].
 */
class VideoSurfaceLetterboxSampler {

    sealed interface Sample
    data class Copied(val bar: Float?) : Sample
    data object Failed : Sample

    private val bitmap = Bitmap.createBitmap(
        LetterboxDetector.SAMPLE_WIDTH,
        LetterboxDetector.SAMPLE_HEIGHT,
        Bitmap.Config.ARGB_8888
    )
    private val pixels = IntArray(LetterboxDetector.SAMPLE_WIDTH * LetterboxDetector.SAMPLE_HEIGHT)
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun sample(surfaceView: View): Sample = when (surfaceView) {
        is TextureView -> if (surfaceView.isAvailable) {
            surfaceView.getBitmap(bitmap)
            Copied(detect())
        } else {
            Failed
        }
        is SurfaceView -> copy(surfaceView)
        else -> Failed
    }

    private suspend fun copy(surfaceView: SurfaceView): Sample {
        if (surfaceView.holder.surface?.isValid != true) return Failed
        return suspendCancellableCoroutine { continuation ->
            val requested = runCatching {
                PixelCopy.request(surfaceView, bitmap, { result ->
                    if (!continuation.isActive) return@request
                    continuation.resume(if (result == PixelCopy.SUCCESS) Copied(detect()) else Failed)
                }, mainHandler)
            }.isSuccess
            if (!requested && continuation.isActive) continuation.resume(Failed)
        }
    }

    private fun detect(): Float? {
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return LetterboxDetector.detectBar(pixels, bitmap.width, bitmap.height)
    }
}
