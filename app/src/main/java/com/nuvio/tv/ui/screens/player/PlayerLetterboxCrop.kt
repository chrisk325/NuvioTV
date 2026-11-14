package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.nuvio.tv.core.player.DolbyVisionActiveAreaProbe
import com.nuvio.tv.core.player.LetterboxVote
import com.nuvio.tv.core.player.VideoSurfaceLetterboxSampler
import kotlinx.coroutines.delay

/*
 * UHD Blu-ray (and their remuxes) always store a 3840x2160 frame, so a 2.39:1 film carries its
 * black bars inside the 16:9 frame. On a 16:9 TV, Crop, Stretch, Fit Width and Fit Height then
 * have nothing to change, because the frame already matches the screen. A phone (about 20:9) still
 * shows a difference, which is why those modes seem to work only on mobile.
 *
 * The player samples a few frames, finds the baked-in bars, and then lays these modes out for the
 * picture inside the bars. Libass subtitles keep the Fit geometry of the full frame, so lines
 * placed in the bars stay on screen and are drawn exactly as they are in Fit.
 */

internal const val LETTERBOX_SAMPLE_INTERVAL_MS = 1_000L
private const val LETTERBOX_MAX_COPY_FAILURES = 8
private const val LETTERBOX_TAG = "LetterboxCrop"

/** The stream and decoded frame size a letterbox decision belongs to. */
internal data class LetterboxKey(val streamUrl: String, val frameWidth: Int, val frameHeight: Int)

/** Aspect of the picture inside a frame of [frameAspect] with a [bar] (fraction of height) above and below. */
internal fun letterboxPictureAspect(frameAspect: Float, bar: Float): Float? {
    if (frameAspect <= 0f || bar <= 0f || bar >= 0.5f) return null
    return frameAspect / (1f - 2f * bar)
}

/** Modes that resize to the picture rather than the frame. The zooms and Force Stretch keep their fixed factors. */
internal fun aspectModeCropsLetterbox(mode: AspectMode): Boolean = when (mode) {
    AspectMode.FULL_SCREEN,
    AspectMode.STRETCH,
    AspectMode.VERTICAL_STRETCH,
    AspectMode.HORIZONTAL_STRETCH -> true

    AspectMode.ORIGINAL,
    AspectMode.FORCE_STRETCH,
    AspectMode.SLIGHT_ZOOM,
    AspectMode.CINEMA_ZOOM -> false
}

/**
 * The size of the whole decoded frame, as a fraction of the player, that applies [mode] to the
 * picture of [pictureAspect] inside the frame's bars. The picture spans the frame's full width and
 * [frameAspect] / [pictureAspect] of its height.
 */
internal fun letterboxFrameFraction(
    mode: AspectMode,
    frameAspect: Float,
    pictureAspect: Float,
    viewAspect: Float
): AspectScale {
    val picture = aspectModeVideoFraction(mode, pictureAspect, viewAspect)
    return AspectScale(
        scaleX = picture.scaleX,
        scaleY = picture.scaleY * pictureAspect / frameAspect
    )
}

/**
 * Aspect of the picture inside the Dolby Vision L5 active area of a [frameWidth] x [frameHeight]
 * frame, or null without a letterbox. Side offsets (pillarbox) are not cropped.
 */
internal fun activeAreaPictureAspect(
    area: DolbyVisionActiveAreaProbe.ActiveArea,
    frameWidth: Int,
    frameHeight: Int,
    pixelWidthHeightRatio: Float
): Float? {
    if (!area.hasLetterbox || frameWidth <= 0 || frameHeight <= 0) return null
    val pictureHeight = frameHeight - area.top - area.bottom
    // Implausible offsets (more than 40% of the frame) are metadata errors.
    if (pictureHeight < frameHeight * 0.6f) return null
    return frameWidth * pixelWidthHeightRatio / pictureHeight
}

/** The detected picture aspect for the frame [player] is showing now, or null when none is known. */
internal fun PlayerRuntimeController.letterboxPictureAspectFor(player: Player?): Float? {
    val size = player?.videoSize ?: return null
    val key = letterboxKey ?: return null
    if (key != LetterboxKey(currentStreamUrl, size.width, size.height)) return null
    return letterboxPictureAspect
}

/**
 * Samples the frames [playerView] shows until the bars of the current stream are known, and calls
 * [onDetected] when a letterbox is found. The decision is kept on [controller] per stream and frame
 * size, so it survives player rebuilds (keystone zoom, libass pipeline) and runs once per stream.
 */
internal suspend fun runLetterboxDetection(
    player: Player,
    playerView: PlayerView,
    controller: PlayerRuntimeController,
    onDetected: () -> Unit
) {
    val sampler = VideoSurfaceLetterboxSampler()
    var vote = LetterboxVote()
    var voteKey: LetterboxKey? = null
    var copyFailures = 0
    var framesFoundNoBars = false
    while (true) {
        delay(LETTERBOX_SAMPLE_INTERVAL_MS)
        val size = player.videoSize
        if (size.width <= 0 || size.height <= 0) continue
        val key = LetterboxKey(controller.currentStreamUrl, size.width, size.height)
        if (controller.letterboxKey == key) continue
        if (key != voteKey) {
            vote = LetterboxVote()
            voteKey = key
            copyFailures = 0
            framesFoundNoBars = false
        }
        // Dolby Vision says where the picture is; no frame needs to be read back.
        val activeArea = controller.dolbyVisionActiveAreaProbe?.activeArea
        val activeAreaAspect = activeArea?.let {
            activeAreaPictureAspect(it, size.width, size.height, size.pixelWidthHeightRatio)
        }
        if (activeAreaAspect != null) {
            controller.letterboxKey = key
            controller.letterboxPictureAspect = activeAreaAspect
            Log.i(LETTERBOX_TAG, "source=dolby-vision-l5 area=$activeArea frame=${size.width}x${size.height} picture=$activeAreaAspect")
            onDetected()
            continue
        }
        if (framesFoundNoBars || vote.isExhausted || copyFailures >= LETTERBOX_MAX_COPY_FAILURES) continue
        if (!player.isPlaying) continue
        // A GPU zoom already scales the frame inside the surface, so its bars are not the source's.
        if (controller.keystoneZoomEffect?.isIdentity == false) continue
        val surface = playerView.videoSurfaceView ?: continue
        val bar = when (val sample = sampler.sample(surface)) {
            VideoSurfaceLetterboxSampler.Failed -> {
                if (++copyFailures == LETTERBOX_MAX_COPY_FAILURES) {
                    Log.i(LETTERBOX_TAG, "source=frames unavailable: the video surface cannot be read back")
                }
                continue
            }
            is VideoSurfaceLetterboxSampler.Copied -> sample.bar
        }
        copyFailures = 0
        val agreedBar = vote.onSample(bar) ?: continue
        val frameAspect = size.width * size.pixelWidthHeightRatio / size.height
        val pictureAspect = letterboxPictureAspect(frameAspect, agreedBar)
        Log.i(LETTERBOX_TAG, "source=frames bar=$agreedBar frame=${size.width}x${size.height} picture=$pictureAspect")
        if (pictureAspect == null) {
            // No bars in the frames; Dolby Vision metadata may still arrive and win.
            framesFoundNoBars = true
            continue
        }
        controller.letterboxKey = key
        controller.letterboxPictureAspect = pictureAspect
        onDetected()
    }
}
