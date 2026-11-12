package com.nuvio.tv.reshaped.livetv

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.metadata.icy.IcyHeaders
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Request

/**
 * Reads a Live TV channel through [LiveTvStreamShare], so the list's preview and the full screen
 * player use one connection to it (see [LiveTvStreamHub]). The request itself is Media3's own
 * OkHttp data source, unchanged: same headers, same errors.
 *
 * Only the channel's own link, from its start, goes through the shared connection. Anything else
 * a player asks for (a byte range, another file) is fetched as before.
 */
@OptIn(UnstableApi::class)
internal class LiveTvSharedDataSource(
    private val channelUrl: String,
    private val headers: Map<String, String>,
) : BaseDataSource(/* isNetwork = */ true) {
    private var plain: DataSource? = null
    private var stream: LiveTvStreamHub.Stream? = null
    private var requested: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        requested = dataSpec.uri
        transferInitializing(dataSpec)
        val length = if (isChannelFromStart(dataSpec)) {
            LiveTvStreamShare.hub.open(LiveTvStreamShare.key(channelUrl, headers)) {
                LiveTvStreamShare.connect(dataSpec, headers)
            }.also { stream = it }.length
        } else {
            LiveTvStreamShare.httpFactory(LiveTvHttp.client, headers).createDataSource().also { plain = it }.open(dataSpec)
        }
        opened = true
        transferStarted(dataSpec)
        return length
    }

    private fun isChannelFromStart(dataSpec: DataSpec): Boolean =
        dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong() &&
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.uri.toString() == channelUrl

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = plain?.read(buffer, offset, length)
            ?: stream?.read(buffer, offset, length)
            ?: return C.RESULT_END_OF_INPUT
        if (read < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = plain?.uri ?: (stream?.info as? LiveTvStreamShare.Response)?.uri ?: requested

    override fun getResponseHeaders(): Map<String, List<String>> =
        plain?.responseHeaders ?: (stream?.info as? LiveTvStreamShare.Response)?.headers ?: emptyMap()

    override fun close() {
        try {
            plain?.close()
            stream?.close()
        } finally {
            plain = null
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** The one [LiveTvStreamHub] of the app, and how its connections are made. */
@OptIn(UnstableApi::class)
internal object LiveTvStreamShare {
    /** How long a channel's connection waits for the player it is handed to: a weak TV takes seconds to build one. */
    const val HANDOFF_MS = 8_000L

    /** Set before the first use, from the TV's memory (see [LiveTvMediaSource]). */
    @Volatile var ringBytes: Int = RING_BYTES

    val hub = LiveTvStreamHub(
        capacityBytes = { ringBytes },
        log = { event ->
            Log.d("LiveTvStream", event)
            LiveTvStartTrace.step("connected ($event)")
        },
    )

    /** What the server answered, for the players reading the session. */
    class Response(val uri: Uri?, val headers: Map<String, List<String>>)

    /** One session per link and set of request headers. */
    fun key(url: String, headers: Map<String, String>): String = buildString {
        append(url)
        headers.entries
            .map { (name, value) -> name.trim().lowercase() to value.trim() }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .forEach { (name, value) -> append('\n').append(name).append(':').append(value) }
    }

    fun httpFactory(calls: Call.Factory, headers: Map<String, String>): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(calls).apply {
            setDefaultRequestProperties(headers)
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) setUserAgent(LiveTvMediaSource.USER_AGENT)
        }

    /** Opens [dataSpec] on a connection that another thread can then read, and cancel. */
    fun connect(dataSpec: DataSpec, headers: Map<String, String>): LiveTvStreamHub.Opened {
        val call = AtomicReference<Call?>()
        val calls = object : Call.Factory {
            override fun newCall(request: Request): Call = LiveTvHttp.client.newCall(request).also(call::set)
        }
        val source = httpFactory(calls, headers).createDataSource()
        val length = try {
            source.open(dataSpec)
        } catch (error: Throwable) {
            runCatching { source.close() }
            throw error
        }
        val responseHeaders = source.responseHeaders
        return LiveTvStreamHub.Opened(
            upstream = object : LiveTvStreamHub.Upstream {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)
                override fun cancel() {
                    call.get()?.cancel()
                }
                override fun release() {
                    runCatching { source.close() }
                }
            },
            length = if (length == C.LENGTH_UNSET.toLong()) -1L else length,
            // Radio streams with titles woven in (ICY) are counted from their first byte.
            joinable = responseHeaders.keys.none { it.equals("icy-metaint", ignoreCase = true) },
            info = Response(source.uri, responseHeaders),
        )
    }

    /**
     * Opens [url] for the player about to ask for it (see [LiveTvStreamHub.prewarm]), on a thread
     * of its own, after [delayMs] (see [LiveTvMediaSource.prewarm]). A link that turned out to be
     * a file with a length is not opened ahead again.
     */
    fun prewarm(url: String, headers: Map<String, String>, delayMs: Long = 0L) {
        if (url in files) return
        Thread({
            runCatching {
                if (delayMs > 0L) Thread.sleep(delayMs)
                // The request a player makes for a stream, so the server answers both alike.
                val dataSpec = DataSpec.Builder()
                    .setUri(url)
                    .setHttpRequestHeaders(mapOf(IcyHeaders.REQUEST_HEADER_ENABLE_METADATA_NAME to IcyHeaders.REQUEST_HEADER_ENABLE_METADATA_VALUE))
                    .build()
                val live = hub.prewarm(key(url, headers), HANDOFF_MS) { connect(dataSpec, headers) }
                if (!live) {
                    if (files.size >= MAX_FILES) files.clear()
                    files += url
                }
            }
        }, "LiveTvPrewarm").apply { isDaemon = true }.start()
    }

    private val files: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private const val MAX_FILES = 64

    /** 6 MB holds four seconds of a 12 Mbit/s channel. */
    const val RING_BYTES = 6 * 1024 * 1024
    const val RING_BYTES_LOW_MEMORY = 3 * 1024 * 1024
}
