package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * How Live TV opens a channel, in the list's preview and in the full screen player alike: Live
 * TV's own HTTP client, Media3's plain extractors with the live MPEG-TS flags, and Live TV's
 * retry policy. None of the steps Nuvio puts files through before and while they load (the
 * playback HTTP client and its connection learning, disk cache, parallel ranged requests,
 * Blu-ray and AVI handling, the HLS parameter-set check, Dolby Vision and subtitle sync extractor
 * wrappers) touch a channel: they wait on or open extra connections that a live stream, often on a
 * one-connection account, can't spare.
 *
 * A channel read in one piece (MPEG-TS) also keeps its connection when the full screen player
 * takes over from the preview, or the other way round: see [LiveTvStreamHub].
 */
@OptIn(UnstableApi::class)
object LiveTvMediaSource {
    /** Sent when the list names none: what IPTV servers expect, and what the preview always sent. */
    const val USER_AGENT = "VLC/3.0.0 LibVLC/3.0.0"

    /**
     * [sharedUrl] is the channel's link when its connection is to be shared between the preview
     * and the full screen player (see [sharesConnection]); the factory then serves that link only.
     */
    internal fun dataSourceFactory(
        context: Context,
        headers: Map<String, String>,
        sharedUrl: String? = null,
        trace: LiveTvStartTrace? = null,
    ): DataSource.Factory {
        val connection: DataSource.Factory = if (sharedUrl != null) {
            LiveTvStreamShare.ringBytes =
                if (LiveTvDevice.isLowMemory(context)) LiveTvStreamShare.RING_BYTES_LOW_MEMORY else LiveTvStreamShare.RING_BYTES
            DataSource.Factory { LiveTvSharedDataSource(sharedUrl, headers) }
        } else {
            LiveTvStreamShare.httpFactory(LiveTvHttp.client, headers)
        }
        val http = if (trace != null) TracingDataSource.Factory(connection, trace) else connection
        // Plain http(s) goes through OkHttp; udp:// and file links, which some lists carry, still open.
        return DefaultDataSource.Factory(context, http)
    }

    /**
     * Whether the channel at [url] keeps one connection from the preview into the full screen
     * player: a live stream read in one piece over http (MPEG-TS, as most IPTV channels are).
     * HLS and DASH fetch many small files, and a past programme is a file that is sought in.
     */
    fun sharesConnection(url: String, mimeType: String?): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
        if (LiveTvPlaybackRegistry.isCatchup(url)) return false
        return Util.inferContentTypeForUriAndMimeType(Uri.parse(url), mimeType) == C.CONTENT_TYPE_OTHER
    }

    /**
     * Opens the channel at [url] now, as OK is pressed, so its connection is made while the full
     * screen player is still being built; that player then reads the same connection. Does
     * nothing when the list's preview already has the channel open, or for formats not shared.
     * [delayMs] waits first: set when another channel's connection was only just closed, which a
     * one-connection account needs a moment to notice.
     */
    fun prewarm(context: Context, url: String, headers: Map<String, String>, mimeType: String?, delayMs: Long = 0L) {
        if (!sharesConnection(url, mimeType)) return
        LiveTvStreamShare.ringBytes =
            if (LiveTvDevice.isLowMemory(context)) LiveTvStreamShare.RING_BYTES_LOW_MEMORY else LiveTvStreamShare.RING_BYTES
        LiveTvStreamShare.prewarm(url, headers, delayMs, LiveTvStartTrace.forPlayer(url))
    }

    /**
     * The player showing a channel is about to let go of it for the other one (the preview for
     * the full screen player, or back): the channel's connection waits for that player.
     */
    fun handOver() = LiveTvStreamShare.hub.hold(LiveTvStreamShare.HANDOFF_MS)

    /**
     * The channel at [url] (already normalized: no user:password@ part), opened as [mimeType]
     * when one is known (HLS, DASH) and else by sniffing, as the preview does.
     */
    fun create(
        context: Context,
        url: String,
        headers: Map<String, String>,
        mimeType: String?,
        tsFlags: Int = LiveTvTsFlags.LIVE,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        mediaMetadata: MediaMetadata? = null,
        forPlayer: Boolean = false,
    ): MediaSource {
        val builder = MediaItem.Builder().setUri(url)
        if (mimeType != null) builder.setMimeType(mimeType)
        if (subtitleConfigurations.isNotEmpty()) builder.setSubtitleConfigurations(subtitleConfigurations)
        if (mediaMetadata != null) builder.setMediaMetadata(mediaMetadata)
        val item = builder.build()
        // What the start waits for, shown while it loads (see LiveTvStartTrace).
        val trace = if (forPlayer) LiveTvStartTrace.forPlayer(url) else LiveTvStartTrace.forPreview(url)
        val extractors = TracingExtractors(DefaultExtractorsFactory().setTsExtractorFlags(tsFlags), trace)
        val sharedUrl = url.takeIf { sharesConnection(it, mimeType) }
        return DefaultMediaSourceFactory(dataSourceFactory(context, headers, sharedUrl, trace), extractors)
            .setLoadErrorHandlingPolicy(LiveTvLoadErrors.policy)
            .createMediaSource(item)
    }

    /**
     * The full screen player's buffer for a channel, instead of Nuvio's file buffer settings (their
     * byte budgets and 3 to 5 s start): a live stream arrives in real time, so a modest read-ahead
     * is all it can use. Playback starts after [LiveTvStartLoadControl.START_MS] of video.
     */
    fun loadControl(context: Context): LoadControl {
        val maxBufferMs = if (LiveTvDevice.isLowMemory(context)) 20_000 else 30_000
        val delegate = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 10_000,
                /* maxBufferMs = */ maxBufferMs,
                /* bufferForPlaybackMs = */ LiveTvStartLoadControl.START_MS.toInt(),
                /* bufferForPlaybackAfterRebufferMs = */ LiveTvStartLoadControl.RESTART_MS.toInt(),
            )
            // A second and a half kept behind, so a short rewind does not reload the channel.
            .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
            .build()
        return LiveTvStartLoadControl(delegate)
    }

    const val BACK_BUFFER_MS = 1_500
}
