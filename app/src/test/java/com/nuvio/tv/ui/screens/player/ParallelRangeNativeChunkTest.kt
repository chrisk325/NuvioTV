package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.Allocation
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Chunks live in native memory only, a stalled chunk's restart carries on after the bytes it has,
 * and a stall shared by every connection restarts none.
 */
class ParallelRangeNativeChunkTest {

    private val server = MockWebServer()
    private val ranges = ConcurrentLinkedQueue<LongRange>()
    private lateinit var allocateBefore: (Int) -> Allocation?
    private lateinit var freeBefore: (Allocation) -> Unit

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns "/file"
        every { getQueryParameter(any()) } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class, SystemClock::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        // The stall watchdog measures windows of real time.
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        every { SystemClock.uptimeMillis() } answers { System.nanoTime() / 1_000_000L }
        allocateBefore = ParallelRangeDataSource.allocateChunk
        freeBefore = ParallelRangeDataSource.freeChunk
        ParallelRangeDataSource.allocateChunk = { size -> Allocation(ByteBuffer.allocateDirect(size), 0) }
        ParallelRangeDataSource.freeChunk = { }
    }

    @After
    fun tearDown() {
        ParallelRangeDataSource.releaseRetainedSession()
        ParallelRangeDataSource.trimIdleBuffers()
        ParallelRangeDataSource.allocateChunk = allocateBefore
        ParallelRangeDataSource.freeChunk = freeBefore
        server.shutdown()
        unmockkStatic(Uri::class, SystemClock::class)
    }

    /** Serves [file] by range; [slow] says which requests' bodies trickle at [slowBytesPerSecond]. */
    private fun serve(file: ByteArray, slowBytesPerSecond: Long = 0L, slow: (LongRange) -> Boolean = { false }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: return MockResponse().setResponseCode(200)
                    .setBody(Buffer().write(file))
                val (from, to) = range.removePrefix("bytes=").split("-").let { parts ->
                    val start = parts[0].toLong()
                    val end = parts.getOrNull(1)?.toLongOrNull() ?: (file.size - 1L)
                    start to minOf(end, file.size - 1L)
                }
                ranges.add(from..to)
                val response = MockResponse().setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                    .setBody(Buffer().write(file, from.toInt(), (to - from + 1).toInt()))
                if (slowBytesPerSecond > 0L && slow(from..to)) {
                    response.throttleBody(slowBytesPerSecond / 10L, 100L, TimeUnit.MILLISECONDS)
                }
                return response
            }
        }
        server.start()
    }

    private fun readAll(chunkSize: Long, connections: Int = 2): ByteArray {
        val source = ParallelRangeDataSource(
            OkHttpDataSource.Factory(OkHttpClient()),
            parallelConnections = connections,
            chunkSize = chunkSize,
        )
        val spec = DataSpec.Builder().setUri(uriOf(server.url("/file").toString())).build()
        val out = ByteArrayOutputStream()
        val scratch = ByteArray(32 * 1024)
        try {
            source.open(spec)
            while (true) {
                val read = source.read(scratch, 0, scratch.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(scratch, 0, read)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    @Test
    fun `a chunk never falls back to the Java heap when native memory is out`() {
        val file = Random(1).nextBytes(512 * 1024)
        serve(file)
        ParallelRangeDataSource.allocateChunk = { null }
        try {
            readAll(chunkSize = 128L * 1024L)
            fail("read past a chunk with no native memory")
        } catch (expected: IOException) {
        }
        assertTrue(ParallelRangeDataSource.hudNativeChunkFailures > 0)
    }

    @Test
    fun `a chunk waits for native memory that comes back`() {
        val file = Random(2).nextBytes(512 * 1024)
        serve(file)
        val refusals = AtomicInteger(3)
        ParallelRangeDataSource.allocateChunk = { size ->
            if (refusals.getAndDecrement() > 0) null else Allocation(ByteBuffer.allocateDirect(size), 0)
        }
        assertArrayEquals(file, readAll(chunkSize = 128L * 1024L))
        assertTrue(ParallelRangeDataSource.hudNativeChunkWaits > 0)
        assertEquals(0, ParallelRangeDataSource.hudNativeChunkFailures)
    }

    @Test
    fun `a lone stalled chunk restarts after the bytes it already has`() {
        val chunk = 256L * 1024L
        val file = Random(3).nextBytes((8 * chunk).toInt())
        val stalledStart = 5 * chunk
        val stalledOnce = AtomicInteger(1)
        serve(file, slowBytesPerSecond = 64L * 1024L) { range ->
            range.first == stalledStart && stalledOnce.getAndDecrement() > 0
        }
        assertArrayEquals(file, readAll(chunkSize = chunk))
        assertTrue("no stall restart", ParallelRangeDataSource.hudHedgeRestarts > 0)
        val resumed = ranges.filter { it.first > stalledStart && it.first < stalledStart + chunk }
        assertTrue("the restart asked for the whole chunk again: $ranges", resumed.isNotEmpty())
        assertTrue(ParallelRangeDataSource.hudHedgeResumedBytes > 0L)
    }

    @Test
    fun `a stall every connection shares restarts none`() {
        val chunk = 256L * 1024L
        val file = Random(4).nextBytes((8 * chunk).toInt())
        // Past the first 1 MB, which earns full prefetch depth, the two chunks the two connections
        // download side by side trickle. (A connection left slow on its own is restarted: that is the
        // lone stall the previous test covers.)
        serve(file, slowBytesPerSecond = 96L * 1024L) { range -> range.first == 5 * chunk || range.first == 6 * chunk }
        assertArrayEquals(file, readAll(chunkSize = chunk))
        assertEquals("restarted though every connection was slow", 0, ParallelRangeDataSource.hudHedgeRestarts)
        assertTrue(ParallelRangeDataSource.hudHedgeSkipped > 0)
    }
}
