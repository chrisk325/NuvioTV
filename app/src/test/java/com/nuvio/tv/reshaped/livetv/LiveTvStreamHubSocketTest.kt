package com.nuvio.tv.reshaped.livetv

import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [LiveTvStreamHub] over a real connection: a small server that sends an endless transport stream
 * the way IPTV servers do (no length, a steady trickle), read with OkHttp as the app reads it.
 */
class LiveTvStreamHubSocketTest {

    /** Sends numbered 188 byte packets to each client until it hangs up; counts who came and went. */
    private class LiveServer {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        val connections = AtomicInteger()
        val disconnects = AtomicInteger()
        private val sockets = CopyOnWriteArrayList<Socket>()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (closed: IOException) {
                        break
                    }
                    sockets += socket
                    connections.incrementAndGet()
                    thread(isDaemon = true) { serve(socket) }
                }
            }
        }

        private fun serve(socket: Socket) {
            try {
                val input = socket.getInputStream().bufferedReader()
                while (true) {
                    val line = input.readLine() ?: return
                    if (line.isEmpty()) break
                }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                var number = 0
                val packet = ByteArray(188)
                while (true) {
                    // About 1.5 Mbit/s: ten packets every ten milliseconds.
                    repeat(10) {
                        packet.fill(0x11)
                        packet[0] = 0x47
                        packet[1] = (number ushr 24).toByte()
                        packet[2] = (number ushr 16).toByte()
                        packet[3] = (number ushr 8).toByte()
                        packet[4] = number.toByte()
                        output.write(packet)
                        number++
                    }
                    output.flush()
                    Thread.sleep(10)
                }
            } catch (gone: IOException) {
                // The client hung up.
            } finally {
                disconnects.incrementAndGet()
                runCatching { socket.close() }
            }
        }

        fun close() {
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private val server = LiveServer()
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @After
    fun tearDown() = server.close()

    /** A connection as the app makes one: read by one thread, cancelled by any. */
    private fun connect(): LiveTvStreamHub.Opened {
        val call: Call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/live/1.ts").build())
        val response: Response = call.execute()
        val body: InputStream = response.body!!.byteStream()
        return LiveTvStreamHub.Opened(
            upstream = object : LiveTvStreamHub.Upstream {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = body.read(buffer, offset, length)
                override fun cancel() = call.cancel()
                override fun release() {
                    runCatching { response.close() }
                }
            },
            length = response.body!!.contentLength(),
            joinable = true,
            info = null,
        )
    }

    @Test(timeout = 30_000)
    fun `the preview's connection carries on in the full screen player`() {
        val hub = LiveTvStreamHub({ 512 * 1024 })
        val preview = hub.open("channel") { connect() }
        val previewFirst = readPackets(preview, 200)
        assertEquals(0, previewFirst)

        // OK is pressed: the preview hands the channel over and stops.
        hub.hold(graceMs = 5_000L)
        preview.close()
        // The full screen player takes a moment to be built; the stream keeps arriving meanwhile.
        Thread.sleep(700)

        val started = System.nanoTime()
        val player = hub.open("channel") { fail("the player made a second connection"); connect() }
        val playerFirst = readPackets(player, 200)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        // It starts in what the preview's connection already brought (all of it is recent here),
        // without waiting for the server: 200 packets take the server 200 ms to send.
        assertEquals(0, playerFirst)
        assertTrue("took $tookMs ms", tookMs < 150)
        assertEquals(1, server.connections.get())
        assertEquals(0, server.disconnects.get())

        // And it plays on live from there, without a gap.
        readPackets(player, 300, expectedFirst = playerFirst + 200)

        // Leaving the channel hangs up, and the server sees it at once.
        player.close()
        assertFalse(hub.isOpen("channel"))
        await { server.disconnects.get() == 1 }
        assertEquals(1, server.connections.get())
    }

    @Test(timeout = 30_000)
    fun `moving to another channel hangs up the first before the next connects`() {
        val hub = LiveTvStreamHub({ 512 * 1024 })
        val first = hub.open("one") { connect() }
        readPackets(first, 50)
        first.close()
        await { server.disconnects.get() == 1 }

        val second = hub.open("two") { connect() }
        assertEquals(0, readPackets(second, 50))
        assertEquals(2, server.connections.get())
        second.close()
        await { server.disconnects.get() == 2 }
    }

    @Test(timeout = 30_000)
    fun `opened ahead, the connection is ready when the player asks`() {
        val hub = LiveTvStreamHub({ 512 * 1024 })
        assertTrue(hub.prewarm("channel", graceMs = 5_000L) { connect() })
        Thread.sleep(500)
        val started = System.nanoTime()
        val player = hub.open("channel") { fail("the player made a second connection"); connect() }
        assertEquals(0, readPackets(player, 200))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 300)
        assertEquals(1, server.connections.get())
        player.close()
        await { server.disconnects.get() == 1 }
    }

    @Test(timeout = 30_000)
    fun `a connection nobody takes is hung up after its grace`() {
        val hub = LiveTvStreamHub({ 512 * 1024 })
        assertTrue(hub.prewarm("channel", graceMs = 300L) { connect() })
        await { server.disconnects.get() == 1 }
        assertFalse(hub.isOpen("channel"))
    }

    /** Reads [count] packets, checks they follow one another, and returns the first one's number. */
    private fun readPackets(stream: LiveTvStreamHub.Stream, count: Int, expectedFirst: Int? = null): Int {
        val bytes = ByteArray(count * 188)
        var done = 0
        while (done < bytes.size) {
            val read = stream.read(bytes, done, bytes.size - done)
            assertTrue(read > 0)
            done += read
        }
        fun number(packet: Int): Int {
            val at = packet * 188
            assertEquals("packet $packet starts with a sync byte", 0x47.toByte(), bytes[at])
            return ((bytes[at + 1].toInt() and 0xFF) shl 24) or ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 8) or (bytes[at + 4].toInt() and 0xFF)
        }
        val first = number(0)
        if (expectedFirst != null) assertEquals(expectedFirst, first)
        for (packet in 1 until count) assertEquals(first + packet, number(packet))
        return first
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("condition not met in time")
            Thread.sleep(10)
        }
    }
}
