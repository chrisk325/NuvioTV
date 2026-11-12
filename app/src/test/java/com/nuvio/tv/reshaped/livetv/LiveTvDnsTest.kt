package com.nuvio.tv.reshaped.livetv

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LiveTvDnsTest {
    private val v4 = InetAddress.getByAddress("panel.example", byteArrayOf(10, 0, 0, 1))
    private val v4b = InetAddress.getByAddress("panel.example", byteArrayOf(10, 0, 0, 2))
    private val v6 = InetAddress.getByAddress("panel.example", ByteArray(16).also { it[15] = 1 })

    /** A system lookup that hangs the way one waiting on an unanswered IPv6 question does. */
    private class SlowDns(private val delayMs: Long, private val answer: List<InetAddress>) : Dns {
        val calls = AtomicInteger()
        override fun lookup(hostname: String): List<InetAddress> {
            calls.incrementAndGet()
            Thread.sleep(delayMs)
            return answer
        }
    }

    @Test(timeout = 10_000)
    fun `the IPv4 answer is used without waiting for the slow system lookup`() {
        val system = SlowDns(5_000, listOf(v6, v4))
        val dns = LiveTvDns(system, ipv4Only = { listOf(v4) })
        val started = System.nanoTime()
        assertEquals(listOf(v4), dns.lookup("panel.example"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test(timeout = 10_000)
    fun `without the IPv4 lookup the system answer is used, IPv4 first`() {
        val dns = LiveTvDns(SlowDns(0, listOf(v6, v4)), ipv4Only = null)
        assertEquals(listOf(v4, v6), dns.lookup("panel.example"))
    }

    @Test(timeout = 10_000)
    fun `an empty IPv4 answer waits for the system lookup`() {
        val dns = LiveTvDns(SlowDns(200, listOf(v6)), ipv4Only = { emptyList() })
        assertEquals(listOf(v6), dns.lookup("panel.example"))
    }

    @Test(timeout = 10_000)
    fun `a failed IPv4 lookup leaves the system answer`() {
        val dns = LiveTvDns(SlowDns(100, listOf(v4b)), ipv4Only = { null })
        assertEquals(listOf(v4b), dns.lookup("panel.example"))
    }

    @Test(timeout = 10_000)
    fun `an unknown host fails as the system lookup does`() {
        val dns = LiveTvDns(Dns { throw UnknownHostException("nope.example") }, ipv4Only = { null })
        try {
            dns.lookup("nope.example")
            fail("expected UnknownHostException")
        } catch (expected: UnknownHostException) {
        }
    }

    @Test(timeout = 10_000)
    fun `a recent answer is reused without a lookup`() {
        val system = SlowDns(0, listOf(v4))
        val dns = LiveTvDns(system, ipv4Only = null)
        dns.lookup("panel.example")
        dns.lookup("PANEL.example")
        assertEquals(1, system.calls.get())
    }

    @Test(timeout = 10_000)
    fun `an older answer is used at once and looked up again meanwhile`() {
        val now = AtomicLong(0L)
        val system = SlowDns(0, listOf(v4))
        val dns = LiveTvDns(system, ipv4Only = null, nanoTime = now::get)
        dns.lookup("panel.example")
        now.set(30L * 60 * 1_000_000_000L)
        assertEquals(listOf(v4), dns.lookup("panel.example"))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (system.calls.get() < 2) {
            if (System.nanoTime() > deadline) fail("not looked up again")
            Thread.sleep(10)
        }
    }

    @Test(timeout = 10_000)
    fun `a forgotten host is looked up again`() {
        val system = SlowDns(0, listOf(v4))
        val dns = LiveTvDns(system, ipv4Only = null)
        dns.lookup("panel.example")
        dns.forget("panel.example")
        dns.lookup("panel.example")
        assertEquals(2, system.calls.get())
    }

    @Test(timeout = 10_000)
    fun `addresses written as numbers are not looked up`() {
        val system = SlowDns(0, listOf(v4))
        val dns = LiveTvDns(Dns { host -> listOf(InetAddress.getByName(host)) }, ipv4Only = { fail("looked up"); null })
        assertEquals("10.1.2.3", dns.lookup("10.1.2.3").single().hostAddress)
        assertEquals(0, system.calls.get())
    }
}
