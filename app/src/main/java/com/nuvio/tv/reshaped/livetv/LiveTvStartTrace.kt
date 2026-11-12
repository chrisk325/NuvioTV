package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import android.util.SparseArray
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.R
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What one channel start is waiting for, step by step, from OK (or a channel change in the player)
 * or from the preview asking for it, to its first picture. The loading line and the preview show
 * it as it goes ([describe]); the full start is logged at the end: `adb logcat -s LiveTvStart`.
 *
 * Fed by [TracingDataSource] (the connection and its data) and [TracingExtractors] (when the
 * stream's video and sound are recognised), so it says whether a slow start is the server, the
 * network, or a stream that sends its picture details seldom.
 */
internal class LiveTvStartTrace private constructor(val url: String, private val sinceNanos: Long, val forPlayer: Boolean) {
    @Volatile private var attempts = 0
    @Volatile private var refusedCode = 0
    @Volatile private var failure: String? = null
    @Volatile private var connectedNanos = 0L
    @Volatile private var joined = false
    private val bytes = AtomicLong()
    @Volatile private var firstByteNanos = 0L
    @Volatile private var tracksEndedNanos = 0L
    @Volatile private var videoTracks = 0
    @Volatile private var audioTracks = 0
    @Volatile private var videoKnown = 0
    @Volatile private var audioKnown = 0
    @Volatile private var videoKnownNanos = 0L
    @Volatile private var audioKnownNanos = 0L
    @Volatile private var preparedNanos = 0L
    @Volatile private var pictureNanos = 0L

    // The connection's own steps, until it is made (see TracingNetworkEvents).
    @Volatile private var netStep = NetStep.NONE
    @Volatile private var netStepSinceNanos = 0L
    private val dnsNanos = AtomicLong()
    private val connectNanos = AtomicLong()
    private val answerNanos = AtomicLong()
    @Volatile private var redirects = 0
    @Volatile private var failedConnections = 0
    @Volatile private var connectFailure: String? = null

    enum class NetStep { NONE, DNS, CONNECT, ANSWER }

    /** The connection moved on to [step]; the time of the step it left is added up. */
    @Synchronized
    fun netStep(step: NetStep) {
        if (connectedNanos != 0L) return
        val now = System.nanoTime()
        endNetStep(now)
        netStep = step
        netStepSinceNanos = now
    }

    @Synchronized
    fun netStepDone() {
        if (connectedNanos != 0L) return
        endNetStep(System.nanoTime())
        netStep = NetStep.NONE
    }

    private fun endNetStep(now: Long) {
        val spent = now - netStepSinceNanos
        when (netStep) {
            NetStep.DNS -> dnsNanos.addAndGet(spent)
            NetStep.CONNECT -> connectNanos.addAndGet(spent)
            NetStep.ANSWER -> answerNanos.addAndGet(spent)
            NetStep.NONE -> Unit
        }
    }

    fun redirected() {
        if (connectedNanos == 0L) redirects++
    }

    fun connectionFailed(error: Throwable) {
        if (connectedNanos != 0L) return
        failedConnections++
        connectFailure = error.javaClass.simpleName
    }

    val done: Boolean get() = pictureNanos != 0L

    fun opening() {
        attempts++
    }

    @Synchronized
    fun connected(joinedOpenConnection: Boolean) {
        if (connectedNanos == 0L) {
            endNetStep(System.nanoTime())
            netStep = NetStep.NONE
            connectedNanos = System.nanoTime()
            joined = joinedOpenConnection
        }
    }

    fun failed(error: Throwable) {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                refusedCode = cause.responseCode
                return
            }
            cause = cause.cause
        }
        failure = (error.cause ?: error).javaClass.simpleName
    }

    fun received(count: Int) {
        if (bytes.getAndAdd(count.toLong()) == 0L) firstByteNanos = System.nanoTime()
    }

    @Synchronized
    fun trackDeclared(type: Int) {
        when (type) {
            C.TRACK_TYPE_VIDEO -> videoTracks++
            C.TRACK_TYPE_AUDIO -> audioTracks++
        }
    }

    fun tracksEnded() {
        if (tracksEndedNanos == 0L) tracksEndedNanos = System.nanoTime()
    }

    @Synchronized
    fun formatKnown(type: Int) {
        val now = System.nanoTime()
        when (type) {
            C.TRACK_TYPE_VIDEO -> {
                videoKnown++
                if (videoKnown == videoTracks) videoKnownNanos = now
            }
            C.TRACK_TYPE_AUDIO -> {
                audioKnown++
                if (audioKnown == audioTracks) audioKnownNanos = now
            }
        }
    }

    /** The player has the channel's tracks: what is left is the decoder and the screen. */
    fun prepared() {
        if (preparedNanos == 0L) preparedNanos = System.nanoTime()
    }

    /** The first picture is on screen: logs the start and returns its summary, once. */
    @Synchronized
    fun picture(): Summary? {
        if (pictureNanos != 0L) return null
        pictureNanos = System.nanoTime()
        val summary = Summary(ms(pictureNanos), shortSteps())
        Log.i(TAG, "${if (forPlayer) "player" else "preview"} ${summary.totalMs} ms: ${describeSteps()}")
        return summary
    }

    class Summary(val totalMs: Long, val steps: String)

    private fun s(nanos: Long): String = String.format(Locale.US, "%.1f s", nanos / 1e9)

    /** The start in a line that fits a short message: the connection's steps first. */
    private fun shortSteps(): String = buildList {
        if (joined) {
            add("used the open connection")
        } else {
            add("address lookup ${s(dnsNanos.get())}")
            add("connecting ${s(connectNanos.get())}")
            add("server answer ${s(answerNanos.get())}")
            if (redirects > 0) add("redirects $redirects")
        }
        if (failedConnections > 0) add("failed connections $failedConnections ($connectFailure)")
        if (refusedCode != 0) add("refused HTTP $refusedCode")
        if (connectedNanos != 0L && pictureNanos != 0L) add("then picture ${s(pictureNanos - connectedNanos)}")
    }.joinToString(", ")

    private fun ms(at: Long): Long = if (at == 0L) -1L else (at - sinceNanos) / 1_000_000L

    private fun describeSteps(): String = buildList {
        add("attempts $attempts")
        if (refusedCode != 0) add("refused HTTP $refusedCode")
        failure?.let { add("error $it") }
        add("address lookup ${dnsNanos.get() / 1_000_000} ms, connecting ${connectNanos.get() / 1_000_000} ms, " +
            "server answer ${answerNanos.get() / 1_000_000} ms, redirects $redirects, failed connections $failedConnections" +
            (connectFailure?.let { " ($it)" } ?: ""))
        add(if (joined) "took the open connection ${ms(connectedNanos)} ms" else "connected ${ms(connectedNanos)} ms")
        add("first data ${ms(firstByteNanos)} ms")
        add("tracks listed ${ms(tracksEndedNanos)} ms")
        if (videoTracks > 0) add("video recognised ${ms(videoKnownNanos)} ms")
        if (audioTracks > 0) add("sound recognised ${ms(audioKnownNanos)} ms")
        add("prepared ${ms(preparedNanos)} ms")
        add("picture ${ms(pictureNanos)} ms")
        add("${bytes.get() / 1024} KB")
    }.joinToString(", ")

    /** What the start is waiting for now, with the seconds since it began; null once it shows. */
    fun describe(context: Context): String? {
        if (pictureNanos != 0L) return null
        val seconds = String.format(Locale.getDefault(), "%.1f s", (System.nanoTime() - sinceNanos) / 1e9)
        val received = Formatter.formatShortFileSize(context, bytes.get())
        return when {
            preparedNanos != 0L -> context.getString(R.string.live_tv_start_picture, seconds)
            connectedNanos == 0L && refusedCode != 0 -> context.getString(R.string.live_tv_start_refused, refusedCode, seconds)
            connectedNanos == 0L && failure != null -> context.getString(R.string.live_tv_start_failed, failure, seconds)
            connectedNanos == 0L && netStep == NetStep.DNS -> context.getString(R.string.live_tv_start_dns, seconds)
            connectedNanos == 0L && netStep == NetStep.ANSWER -> context.getString(R.string.live_tv_start_answer, seconds)
            connectedNanos == 0L -> context.getString(R.string.live_tv_start_connecting, seconds)
            bytes.get() == 0L -> context.getString(R.string.live_tv_start_waiting_data, seconds)
            tracksEndedNanos == 0L -> context.getString(R.string.live_tv_start_reading, received, seconds)
            videoKnown < videoTracks -> context.getString(R.string.live_tv_start_waiting_video, received, seconds)
            audioKnown < audioTracks -> context.getString(R.string.live_tv_start_waiting_audio, received, seconds)
            else -> context.getString(R.string.live_tv_start_buffering, received, seconds)
        }
    }

    companion object {
        private const val TAG = "LiveTvStart"
        /** How long after OK a player start is still counted from it. */
        private const val PRESS_VALID_NS = 30_000L * 1_000_000L
        private const val MAX_TRACES = 8

        @Volatile private var pressedNanos = 0L
        @Volatile private var pending: LiveTvStartTrace? = null
        private val latest = ConcurrentHashMap<String, LiveTvStartTrace>()

        /** OK on a channel, or a channel change in the player: the next player start counts from now. */
        fun pressed() {
            pressedNanos = System.nanoTime()
        }

        /** The trace of the full screen player's start of [url] (the one OK began, when there is one). */
        @Synchronized
        fun forPlayer(url: String): LiveTvStartTrace {
            pending?.takeIf { it.url == url && !it.done }?.let { return it }
            val press = pressedNanos
            val since = if (press != 0L && System.nanoTime() - press < PRESS_VALID_NS) press else System.nanoTime()
            pressedNanos = 0L
            return LiveTvStartTrace(url, since, forPlayer = true).also {
                pending = it
                remember(it)
            }
        }

        /** A new trace for the list's preview of [url]. */
        fun forPreview(url: String): LiveTvStartTrace =
            LiveTvStartTrace(url, System.nanoTime(), forPlayer = false).also(::remember)

        fun latestFor(url: String?): LiveTvStartTrace? = url?.let(latest::get)

        private fun remember(trace: LiveTvStartTrace) {
            if (latest.size >= MAX_TRACES) latest.clear()
            latest[trace.url] = trace
        }
    }
}

/** Feeds a [LiveTvStartTrace] with the connection's opening, its refusals and its data. */
@OptIn(UnstableApi::class)
internal class TracingDataSource(private val upstream: DataSource, private val trace: LiveTvStartTrace) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        trace.opening()
        val joined = (upstream as? LiveTvSharedDataSource)?.joinsOpenConnection(dataSpec) == true
        return try {
            upstream.open(dataSpec).also { trace.connected(joined) }
        } catch (error: IOException) {
            trace.failed(error)
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length).also { if (it > 0) trace.received(it) }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory, private val trace: LiveTvStartTrace) : DataSource.Factory {
        override fun createDataSource(): DataSource = TracingDataSource(upstream.createDataSource(), trace)
    }
}

/**
 * Extractors that tell a [LiveTvStartTrace] when the stream has listed its tracks and when each
 * video and sound track is recognised: the player starts only once every track is.
 */
@OptIn(UnstableApi::class)
internal class TracingExtractors(private val delegate: ExtractorsFactory, private val trace: LiveTvStartTrace) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    private fun wrap(extractor: Extractor): Extractor = TracingExtractor(extractor, trace)

    private class TracingExtractor(private val delegate: Extractor, private val trace: LiveTvStartTrace) : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)
        override fun getSniffFailureDetails() = delegate.sniffFailureDetails
        override fun init(output: ExtractorOutput) = delegate.init(TracingOutput(output, trace))
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)
        override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
        override fun release() = delegate.release()
        override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
    }

    private class TracingOutput(private val delegate: ExtractorOutput, private val trace: LiveTvStartTrace) : ExtractorOutput {
        private val tracks = SparseArray<TrackOutput>()

        override fun track(id: Int, type: Int): TrackOutput {
            tracks[id]?.let { return it }
            trace.trackDeclared(type)
            return TracingTrackOutput(delegate.track(id, type), type, trace).also { tracks.put(id, it) }
        }

        override fun endTracks() {
            trace.tracksEnded()
            delegate.endTracks()
        }

        override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
    }

    private class TracingTrackOutput(
        private val delegate: TrackOutput,
        private val type: Int,
        private val trace: LiveTvStartTrace,
    ) : TrackOutput {
        private var known = false

        override fun format(format: Format) {
            if (!known) {
                known = true
                trace.formatKnown(type)
            }
            delegate.format(format)
        }

        override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            delegate.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}

/**
 * The connection's own steps for a [LiveTvStartTrace]: looking up the server's address,
 * connecting to it, and waiting for its answer (for each redirect the server sends).
 */
internal class TracingNetworkEvents(private val trace: LiveTvStartTrace) : EventListener() {
    override fun dnsStart(call: Call, domainName: String) = trace.netStep(LiveTvStartTrace.NetStep.DNS)

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = trace.netStepDone()

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        trace.netStep(LiveTvStartTrace.NetStep.CONNECT)

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = trace.netStepDone()

    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        trace.netStepDone()
        trace.connectionFailed(ioe)
        // An address that could not be reached is looked up again next time.
        inetSocketAddress.hostString?.let(LiveTvDns.shared::forget)
        call.request().url.host.let(LiveTvDns.shared::forget)
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = Unit

    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) = trace.netStep(LiveTvStartTrace.NetStep.ANSWER)

    override fun responseHeadersEnd(call: Call, response: Response) {
        trace.netStepDone()
        if (response.isRedirect) trace.redirected()
    }

    override fun callFailed(call: Call, ioe: IOException) = trace.netStepDone()

    companion object {
        /** Live TV's client, reporting the connection's steps to [trace]. */
        fun client(trace: LiveTvStartTrace?): OkHttpClient =
            if (trace == null) LiveTvHttp.client else LiveTvHttp.client.newBuilder().eventListener(TracingNetworkEvents(trace)).build()
    }
}
