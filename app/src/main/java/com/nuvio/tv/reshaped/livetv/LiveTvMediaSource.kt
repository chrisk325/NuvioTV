package com.nuvio.tv.reshaped.livetv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
 */
@OptIn(UnstableApi::class)
object LiveTvMediaSource {
    /** Sent when the list names none: what IPTV servers expect, and what the preview always sent. */
    const val USER_AGENT = "VLC/3.0.0 LibVLC/3.0.0"

    fun dataSourceFactory(context: Context, headers: Map<String, String>): DataSource.Factory {
        val http = OkHttpDataSource.Factory(LiveTvHttp.client).apply {
            setDefaultRequestProperties(headers)
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) setUserAgent(USER_AGENT)
        }
        // Plain http(s) goes through OkHttp; udp:// and file links, which some lists carry, still open.
        return DefaultDataSource.Factory(context, http)
    }

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
    ): MediaSource {
        val builder = MediaItem.Builder().setUri(url)
        if (mimeType != null) builder.setMimeType(mimeType)
        if (subtitleConfigurations.isNotEmpty()) builder.setSubtitleConfigurations(subtitleConfigurations)
        if (mediaMetadata != null) builder.setMediaMetadata(mediaMetadata)
        val item = builder.build()
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)
        return DefaultMediaSourceFactory(dataSourceFactory(context, headers), extractors)
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
