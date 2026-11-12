package com.nuvio.tv.reshaped.livetv

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LiveTvStreamHubTest {

    /**
     * A connection whose bytes say where in the stream they are. As a transport stream: packets of
     * 188 bytes, each a sync byte, its number, then filler. Otherwise: bytes that follow no packets.
     * It gives out only what [feed] allowed, as a live stream arrives over time.
     */
    private class FakeUpstream(
        private val limit: Long = Long.MAX_VALUE,
        private val phase: Int = 0,
        private val transportStream: Boolean = true,
        private val failAt: Long = -1L,
        private val failure: IOException? = null,
        private val onRead: () -> Unit = {},
    ) : LiveTvStreamHub.Upstream {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private var allowed = 0L
        @Volatile var offset = 0L
            private set
        @Volatile var cancelled = false
            private set
        @Volatile var released = false
            private set

        fun feed(bytes: Long) {
            lock.withLock {
                allowed += bytes
                changed.signalAll()
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            lock.withLock {
                while (!cancelled && this.offset < limit && this.offset != failAt && allowed - this.offset <= 0) changed.await()
                if (cancelled) throw IOException("cancelled")
                if (this.offset == failAt) throw failure!!
                if (this.offset >= limit) return -1
                var count = minOf(length.toLong(), allowed - this.offset, limit - this.offset)
                if (failAt > this.offset) count = minOf(count, failAt - this.offset)
                for (i in 0 until count.toInt()) buffer[offset + i] = byteAt(this.offset + i, phase, transportStream)
                this.offset += count
                onRead()
                return count.toInt()
            }
        }

        override fun cancel() {
            lock.withLock {
                cancelled = true
                changed.signalAll()
            }
        }

        override fun release() {
            released = true
        }
    }

    private fun live(upstream: LiveTvStreamHub.Upstream, joinable: Boolean = true) =
        LiveTvStreamHub.Opened(upstream, length = -1L, joinable = joinable, info = "info")

    private fun hub(ringBytes: Int, clock: () -> Long = System::nanoTime) = LiveTvStreamHub({ ringBytes }, clock)

    private val never: () -> LiveTvStreamHub.Opened = { throw AssertionError("a second connection was opened") }

    @Test(timeout = 20_000)
    fun `one player reads the whole stream in order through a small ring`() {
        val upstream = FakeUpstream(limit = 300_000L).apply { feed(300_000L) }
        val hub = hub(8 * 1024)
        val stream = hub.open("a") { live(upstream) }
        assertEquals(-1L, stream.length)
        assertEquals("info", stream.info)

        val buffer = ByteArray(1_000)
        var position = 0L
        while (true) {
            val read = stream.read(buffer, 0, buffer.size)
            if (read < 0) break
            for (i in 0 until read) assertEquals("byte $position", byteAt(position + i, 0, true), buffer[i])
            position += read
        }
        assertEquals(300_000L, position)
        stream.close()
        await { upstream.released }
        assertFalse(hub.isOpen("a"))
    }

    @Test(timeout = 20_000)
    fun `a player that leaves closes the connection`() {
        val upstream = FakeUpstream().apply { feed(10_000L) }
        val hub = hub(64 * 1024)
        val stream = hub.open("a") { live(upstream) }
        readFully(stream, 5_000)
        assertTrue(hub.isOpen("a"))
        stream.close()
        assertFalse(hub.isOpen("a"))
        await { upstream.cancelled && upstream.released }
    }

    @Test(timeout = 20_000)
    fun `handed over, the next player joins the same connection on a packet`() {
        val upstream = FakeUpstream().apply { feed(200_000L) }
        val hub = hub(64 * 1024)
        val preview = hub.open("a") { live(upstream) }
        readFully(preview, 100_000)
        hub.hold(graceMs = 10_000L)
        preview.close()
        assertTrue(hub.isOpen("a"))
        assertFalse(upstream.cancelled)
        // With nobody reading, the session keeps taking the stream in.
        await { upstream.offset == 200_000L }

        val player = hub.open("a", never)
        val first = readPackets(player, 20, phase = 0)
        // Somewhere in the ring's recent past, and from there on without a gap.
        assertTrue(first * 188L >= 200_000L - 64 * 1024)
        assertTrue(first * 188L < 200_000L)
        upstream.feed(100_000L)
        val more = (300_000L / 188 - (first + 20)).toInt()
        assertEquals(first + 20, readPackets(player, more, phase = 0, expectedFirst = first + 20))

        player.close()
        assertFalse(hub.isOpen("a"))
        await { upstream.released }
    }

    @Test(timeout = 20_000)
    fun `a session nobody takes closes after its grace`() {
        val upstream = FakeUpstream().apply { feed(10_000L) }
        val hub = hub(64 * 1024)
        val stream = hub.open("a") { live(upstream) }
        readFully(stream, 1_000)
        hub.hold(graceMs = 200L)
        stream.close()
        assertTrue(hub.isOpen("a"))
        await { !hub.isOpen("a") && upstream.released }
    }

    @Test(timeout = 20_000)
    fun `a hold not used in time does not keep a later session`() {
        val upstream = FakeUpstream().apply { feed(10_000L) }
        val hub = hub(64 * 1024)
        val stream = hub.open("a") { live(upstream) }
        hub.hold(graceMs = 10_000L, armedMs = 50L)
        Thread.sleep(150)
        stream.close()
        assertFalse(hub.isOpen("a"))
        await { upstream.released }
    }

    @Test(timeout = 20_000)
    fun `a stream that is not a transport stream is joined from its start while the ring has it`() {
        val upstream = FakeUpstream(transportStream = false).apply { feed(20_000L) }
        val hub = hub(64 * 1024)
        val preview = hub.open("a") { live(upstream) }
        readFully(preview, 20_000)
        hub.hold(graceMs = 10_000L)
        preview.close()

        val player = hub.open("a", never)
        val bytes = readFully(player, 20_000)
        for (i in bytes.indices) assertEquals(byteAt(i.toLong(), 0, false), bytes[i])
        player.close()
    }

    @Test(timeout = 20_000)
    fun `a stream that is not a transport stream is opened again once its start is gone`() {
        val first = FakeUpstream(transportStream = false).apply { feed(200_000L) }
        val hub = hub(64 * 1024)
        val preview = hub.open("a") { live(first) }
        readFully(preview, 200_000)
        hub.hold(graceMs = 10_000L)
        preview.close()
        assertTrue(hub.isOpen("a"))

        val second = FakeUpstream(transportStream = false).apply { feed(1_000L) }
        val opens = AtomicInteger()
        val player = hub.open("a") {
            opens.incrementAndGet()
            live(second)
        }
        assertEquals(1, opens.get())
        await { first.cancelled && first.released }
        val bytes = readFully(player, 1_000)
        for (i in bytes.indices) assertEquals(byteAt(i.toLong(), 0, false), bytes[i])
        player.close()
    }

    @Test(timeout = 20_000)
    fun `a stream framed from its first byte is not joined part way`() {
        val first = FakeUpstream().apply { feed(200_000L) }
        val hub = hub(64 * 1024)
        val preview = hub.open("a") { live(first, joinable = false) }
        readFully(preview, 200_000)
        hub.hold(graceMs = 10_000L)
        preview.close()

        val second = FakeUpstream().apply { feed(1_880L) }
        val player = hub.open("a") { live(second, joinable = false) }
        assertEquals(0, readPackets(player, 10, phase = 0))
        await { first.released }
        player.close()
    }

    @Test(timeout = 20_000)
    fun `the connection's own error reaches the player after the bytes before it`() {
        class ServerGone : IOException("gone")
        val error = ServerGone()
        val upstream = FakeUpstream(failAt = 5_000L, failure = error).apply { feed(50_000L) }
        val hub = hub(64 * 1024)
        val stream = hub.open("a") { live(upstream) }
        readFully(stream, 5_000)
        try {
            stream.read(ByteArray(10), 0, 10)
            fail("expected the error")
        } catch (thrown: IOException) {
            assertSame(error, thrown)
        }
        stream.close()
        assertFalse(hub.isOpen("a"))
        await { upstream.released }
    }

    @Test(timeout = 20_000)
    fun `an error opening the connection is thrown as it is`() {
        val error = IOException("403")
        val hub = hub(64 * 1024)
        try {
            hub.open("a") { throw error }
            fail("expected the error")
        } catch (thrown: IOException) {
            assertSame(error, thrown)
        }
        // And the next try opens it.
        val upstream = FakeUpstream().apply { feed(1_880L) }
        val stream = hub.open("a") { live(upstream) }
        assertEquals(0, readPackets(stream, 10, phase = 0))
        stream.close()
    }

    @Test(timeout = 20_000)
    fun `a file with a length is read straight from its connection`() {
        val upstream = FakeUpstream(limit = 1_000L, transportStream = false).apply { feed(1_000L) }
        val hub = hub(64 * 1024)
        val stream = hub.open("a") { LiveTvStreamHub.Opened(upstream, length = 1_000L, joinable = true, info = null) }
        assertEquals(1_000L, stream.length)
        assertFalse(hub.isOpen("a"))
        val bytes = readFully(stream, 1_000)
        for (i in bytes.indices) assertEquals(byteAt(i.toLong(), 0, false), bytes[i])
        assertEquals(-1, stream.read(ByteArray(10), 0, 10))
        stream.close()
        assertTrue(upstream.released)
    }

    @Test(timeout = 20_000)
    fun `opened ahead, the player joins instead of connecting again`() {
        val upstream = FakeUpstream().apply { feed(40_000L) }
        val hub = hub(64 * 1024)
        assertTrue(hub.prewarm("a", graceMs = 10_000L) { live(upstream) })
        assertTrue(hub.isOpen("a"))
        await { upstream.offset == 40_000L }

        val player = hub.open("a", never)
        // All of it is recent and still in the ring: from the first packet.
        assertEquals(0, readPackets(player, 200, phase = 0))
        player.close()
        assertFalse(hub.isOpen("a"))
        await { upstream.released }
    }

    @Test(timeout = 20_000)
    fun `opened ahead and not taken, it closes after its grace`() {
        val upstream = FakeUpstream().apply { feed(50_000L) }
        val hub = hub(64 * 1024)
        assertTrue(hub.prewarm("a", graceMs = 200L) { live(upstream) })
        await { !hub.isOpen("a") && upstream.released }
    }

    @Test(timeout = 20_000)
    fun `opening ahead a channel already open keeps its connection`() {
        val upstream = FakeUpstream().apply { feed(50_000L) }
        val hub = hub(64 * 1024)
        val preview = hub.open("a") { live(upstream) }
        assertTrue(hub.prewarm("a", graceMs = 10_000L, opener = never))
        assertFalse(upstream.cancelled)
        preview.close()
    }

    @Test(timeout = 20_000)
    fun `a file is not kept open ahead`() {
        val upstream = FakeUpstream(limit = 1_000L)
        val hub = hub(64 * 1024)
        assertFalse(hub.prewarm("a", graceMs = 10_000L) { LiveTvStreamHub.Opened(upstream, length = 1_000L, joinable = true, info = null) })
        assertFalse(hub.isOpen("a"))
        assertTrue(upstream.released)
    }

    @Test(timeout = 20_000)
    fun `an error opening ahead leaves the player to open it`() {
        val hub = hub(64 * 1024)
        assertTrue(hub.prewarm("a", graceMs = 10_000L) { throw IOException("403") })
        assertFalse(hub.isOpen("a"))
        val upstream = FakeUpstream().apply { feed(1_880L) }
        val stream = hub.open("a") { live(upstream) }
        assertEquals(0, readPackets(stream, 10, phase = 0))
        stream.close()
    }

    @Test(timeout = 20_000)
    fun `a player asking while the connection is being made waits for it`() {
        val upstream = FakeUpstream().apply { feed(50_000L) }
        val hub = hub(64 * 1024)
        val connecting = CountDownLatch(1)
        val connect = CountDownLatch(1)
        val opens = AtomicInteger()
        val ahead = thread {
            hub.prewarm("a", graceMs = 10_000L) {
                opens.incrementAndGet()
                connecting.countDown()
                connect.await()
                live(upstream)
            }
        }
        assertTrue(connecting.await(5, TimeUnit.SECONDS))
        val joined = AtomicReference<LiveTvStreamHub.Stream?>()
        val player = thread { joined.set(hub.open("a") { opens.incrementAndGet(); live(FakeUpstream()) }) }
        Thread.sleep(200)
        assertTrue(player.isAlive)
        connect.countDown()
        ahead.join(5_000)
        player.join(5_000)
        assertEquals(1, opens.get())
        val stream = joined.get()
        assertNotNull(stream)
        assertEquals(0, readPackets(stream!!, 10, phase = 0))
        stream.close()
    }

    @Test(timeout = 20_000)
    fun `another channel takes the connection`() {
        val one = FakeUpstream().apply { feed(1_000L) }
        val two = FakeUpstream().apply { feed(1_880L) }
        val hub = hub(64 * 1024)
        val first = hub.open("a") { live(one) }
        readFully(first, 1_000)
        val second = hub.open("b") { live(two) }
        assertTrue(one.cancelled)
        assertTrue(hub.isOpen("b"))
        try {
            first.read(ByteArray(10), 0, 10)
            fail("expected the first channel's stream to be closed")
        } catch (expected: IOException) {
        }
        first.close()
        // The first player leaving does not touch the channel that replaced it.
        assertTrue(hub.isOpen("b"))
        assertEquals(0, readPackets(second, 10, phase = 0))
        second.close()
    }

    @Test(timeout = 20_000)
    fun `packets that do not start at the first byte are still found`() {
        val upstream = FakeUpstream(phase = 57).apply { feed(200_000L) }
        val hub = hub(64 * 1024)
        val preview = hub.open("a") { live(upstream) }
        readFully(preview, 200_000)
        hub.hold(graceMs = 10_000L)
        preview.close()

        val player = hub.open("a", never)
        val first = readPackets(player, 20, phase = 57)
        assertTrue(first > 0)
        player.close()
    }

    @Test(timeout = 20_000)
    fun `only the last seconds are handed on`() {
        // Each read from the connection is a second later.
        val now = AtomicLong(1_000_000_000_000L)
        val upstream = FakeUpstream(onRead = { now.addAndGet(1_000_000_000L) })
        val hub = hub(1024 * 1024) { now.get() }
        val chunk = 32 * 1024L
        val preview = hub.open("a") { live(upstream) }
        for (i in 1..10) {
            upstream.feed(chunk)
            readFully(preview, chunk.toInt())
        }
        hub.hold(graceMs = 10_000L)
        preview.close()

        val player = hub.open("a", never)
        // Read 10 arrived now, so reads 6 to 10 arrived in the last four seconds: from 5 chunks in.
        val expected = Math.ceil(5 * chunk / 188.0).toInt()
        assertEquals(expected, readPackets(player, 20, phase = 0))
        player.close()
    }

    @Test(timeout = 20_000)
    fun `a paused player holds the stream back instead of losing it`() {
        val upstream = FakeUpstream().apply { feed(1_000_000L) }
        val hub = hub(8 * 1024)
        val stream = hub.open("a") { live(upstream) }
        // Nothing is read for a while: the connection is read no further than the ring holds.
        Thread.sleep(300)
        assertTrue(upstream.offset <= 8 * 1024L)
        assertEquals(0, readPackets(stream, 1_000, phase = 0))
        stream.close()
    }

    private fun readFully(stream: LiveTvStreamHub.Stream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var done = 0
        while (done < count) {
            val read = stream.read(bytes, done, count - done)
            assertTrue("stream ended after $done of $count bytes", read > 0)
            done += read
        }
        return bytes
    }

    /**
     * Reads [count] packets and checks they follow one another; returns the first one's number.
     * The stream must start on a packet.
     */
    private fun readPackets(stream: LiveTvStreamHub.Stream, count: Int, phase: Int, expectedFirst: Int? = null): Int {
        val bytes = readFully(stream, count * 188)
        assertEquals("starts on a packet", 0x47.toByte(), bytes[0])
        val first = packetNumber(bytes, 0)
        if (expectedFirst != null) assertEquals(expectedFirst, first)
        for (packet in 0 until count) {
            val start = (first + packet) * 188L + phase
            for (i in 0 until 188) {
                assertEquals("packet ${first + packet} byte $i", byteAt(start + i, phase, true), bytes[packet * 188 + i])
            }
        }
        return first
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("condition not met in time")
            Thread.sleep(10)
        }
    }

    private companion object {
        /** The stream's byte at [offset]. Packets start at [phase]; before it comes filler. */
        fun byteAt(offset: Long, phase: Int, transportStream: Boolean): Byte {
            if (!transportStream) return (offset % 251).toByte()
            if (offset < phase) return 0x11
            val inPacket = ((offset - phase) % 188).toInt()
            val number = ((offset - phase) / 188).toInt()
            return when (inPacket) {
                0 -> 0x47
                1 -> (number ushr 24).toByte()
                2 -> (number ushr 16).toByte()
                3 -> (number ushr 8).toByte()
                4 -> number.toByte()
                // Never a sync byte, so only real packet starts look like one.
                else -> (0x80 or ((inPacket + number) and 0x3F)).toByte()
            }
        }

        fun packetNumber(bytes: ByteArray, at: Int): Int =
            ((bytes[at + 1].toInt() and 0xFF) shl 24) or ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 8) or (bytes[at + 4].toInt() and 0xFF)
    }
}
