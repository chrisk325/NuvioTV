package com.nuvio.tv.core.network

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dns

/**
 * Address lookups for playback: Live TV (channel lists, guides, logos and the channels
 * themselves) and the player's streams (addon and debrid links, and the servers they redirect to).
 *
 * The system lookup asks for IPv4 and IPv6 addresses together and answers only once both are in.
 * On many home networks the DNS server never answers the IPv6 question, so every lookup waits for
 * the resolver's timeout and retry: about ten seconds before a stream even starts to connect.
 * Here the IPv4 question is also asked on its own (Android 10 and later), and whichever answer
 * comes first is used. Answers are kept for a while, so a stream opened again, or another
 * stream on the same server, does not wait for a lookup at all.
 *
 * Only the TV's own DNS server is asked, as before; nothing goes to another DNS provider.
 */
internal class FastDns(
    private val system: Dns = Dns.SYSTEM,
    private val ipv4Only: ((String) -> List<InetAddress>?)? = if (Build.VERSION.SDK_INT >= 29) ::queryIpv4 else null,
    private val nanoTime: () -> Long = System::nanoTime,
) : Dns {
    private class Entry(val addresses: List<InetAddress>, val atNanos: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase()
        if (isIpLiteral(host) || host == "localhost") return system.lookup(hostname)
        val cached = cache[host]
        if (cached != null) {
            val age = nanoTime() - cached.atNanos
            if (age < FRESH_NS) return cached.addresses
            if (age < STALE_NS) {
                // Used now, looked up again meanwhile: a server that moved is found on the next start.
                refreshInBackground(host)
                return cached.addresses
            }
        }
        return resolve(host)
    }

    /** Forgets [hostname]'s addresses: one of them could not be connected to. */
    fun forget(hostname: String) {
        cache.remove(hostname.lowercase())
    }

    /**
     * Looks up [hostnames] in the background (a stream list's servers), so the stream picked from
     * it does not wait for its lookup. Only names not looked up recently are asked, at most
     * [PREFETCH_MAX] per call. Only the DNS server is asked: nothing is sent to the servers.
     */
    fun prefetch(hostnames: Iterable<String>) {
        hostnames.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "localhost" && !isIpLiteral(it) }
            .distinct()
            .filter { host -> cache[host]?.let { nanoTime() - it.atNanos >= FRESH_NS } ?: true }
            .take(PREFETCH_MAX)
            .forEach(::refreshInBackground)
    }

    private fun refreshInBackground(host: String) {
        if (!refreshing.add(host)) return
        Thread({
            try {
                resolve(host)
            } catch (error: Exception) {
                // The kept addresses stay until they are too old.
            } finally {
                refreshing.remove(host)
            }
        }, "FastDns").apply { isDaemon = true }.start()
    }

    /** The system lookup and the IPv4-only one side by side: the first with addresses wins. */
    private fun resolve(host: String): List<InetAddress> {
        val answer = AtomicReference<List<InetAddress>?>()
        val systemError = AtomicReference<UnknownHostException?>()
        val pending = AtomicInteger(if (ipv4Only != null) 2 else 1)
        val done = CountDownLatch(1)

        fun offer(addresses: List<InetAddress>?) {
            if (!addresses.isNullOrEmpty() && answer.compareAndSet(null, ipv4First(addresses))) done.countDown()
            if (pending.decrementAndGet() == 0) done.countDown()
        }

        Thread({
            val addresses = try {
                system.lookup(host)
            } catch (error: UnknownHostException) {
                systemError.set(error)
                null
            } catch (error: Exception) {
                systemError.set(UnknownHostException(host).apply { initCause(error) })
                null
            }
            offer(addresses)
        }, "FastDns").apply { isDaemon = true }.start()
        ipv4Only?.let { query ->
            Thread({ offer(runCatching { query(host) }.getOrNull()) }, "FastDns4").apply { isDaemon = true }.start()
        }

        try {
            if (!done.await(LOOKUP_TIMEOUT_S, TimeUnit.SECONDS)) throw UnknownHostException("$host: no answer in ${LOOKUP_TIMEOUT_S}s")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UnknownHostException(host).apply { initCause(interrupted) }
        }
        val addresses = answer.get() ?: throw (systemError.get() ?: UnknownHostException(host))
        cache[host] = Entry(addresses, nanoTime())
        return addresses
    }

    companion object {
        private const val TAG = "FastDns"

        /** Addresses this recent are used without a lookup. */
        private const val FRESH_NS = 10L * 60 * 1_000_000_000L

        /** Older ones, up to this age, are used while they are looked up again. */
        private const val STALE_NS = 6L * 60 * 60 * 1_000_000_000L

        /** Most names [prefetch] looks up at once: a list's addons, not every stream's link. */
        private const val PREFETCH_MAX = 8

        /** As long as the system lookup could take before connecting gives up anyway. */
        private const val LOOKUP_TIMEOUT_S = 15L

        /** The one Live TV and the player share, so a host either has looked up is ready for the other. */
        val shared = FastDns()

        private val ipv4Literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        private fun isIpLiteral(host: String): Boolean = host.contains(':') || ipv4Literal.matches(host)

        /** IPv4 first, as Nuvio's other clients order them: an IPv6 route that is broken is tried last. */
        private fun ipv4First(addresses: List<InetAddress>): List<InetAddress> =
            addresses.sortedBy { if (it is Inet4Address) 0 else 1 }

        /** The IPv4 addresses of [host] from the TV's DNS server, asked on their own; null on failure. */
        @androidx.annotation.RequiresApi(29)
        private fun queryIpv4(host: String): List<InetAddress>? {
            val result = AtomicReference<List<InetAddress>?>()
            val done = CountDownLatch(1)
            val cancel = CancellationSignal()
            DnsResolver.getInstance().query(
                null,
                host,
                DnsResolver.TYPE_A,
                DnsResolver.FLAG_EMPTY,
                Executor(Runnable::run),
                cancel,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        result.set(answer)
                        done.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        Log.d(TAG, "IPv4 lookup of $host failed: ${error.code}")
                        done.countDown()
                    }
                },
            )
            if (!done.await(LOOKUP_TIMEOUT_S, TimeUnit.SECONDS)) cancel.cancel()
            return result.get()
        }
    }
}
