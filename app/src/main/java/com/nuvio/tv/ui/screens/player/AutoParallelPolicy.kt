package com.nuvio.tv.ui.screens.player

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns parallel connections on by itself for large remote files when the user has not set
 * Custom Network up.
 *
 * A single TCP connection tops out at its receive window divided by the round trip, so a far
 * debrid server can hold one connection well under a UHD remux's bitrate on a fast line. Ranged
 * requests over several connections each get their own window. The parallel source already falls
 * back to one connection when the server does not take ranges and backs off on 429/503.
 *
 * Left off wherever it cannot help or the parallel path is not proven: low-RAM devices, a box
 * that already gives a single connection a raised receive window (the parallel sockets keep the
 * kernel's own, so two of them could fetch less), Live TV, torrents, HLS/DASH, streams that carry
 * addon headers (the parallel path has no HTTP/2-on-403 retry), cleartext and local servers, and
 * files whose size is unknown or below [MIN_FILE_BYTES].
 */
internal object AutoParallelPolicy {
    /** Roughly 25 Mbps over two hours: below this one connection already keeps up. */
    const val MIN_FILE_BYTES: Long = 20L * 1024L * 1024L * 1024L

    // The values a user gets by switching Parallel Connections on without changing anything else.
    const val CONNECTIONS = 2
    const val CHUNK_SIZE_KB = 16 * 1024

    fun shouldUse(
        customNetworkEnabled: Boolean,
        lowRamTier: Boolean,
        singleConnectionWindowRaised: Boolean,
        liveTv: Boolean,
        torrent: Boolean,
        adaptive: Boolean,
        url: String,
        sanitizedHeaders: Map<String, String>,
        fileSizeBytes: Long?,
    ): Boolean {
        if (customNetworkEnabled) return false
        if (lowRamTier || singleConnectionWindowRaised) return false
        if (liveTv || torrent || adaptive) return false
        if (sanitizedHeaders.isNotEmpty()) return false
        if (fileSizeBytes == null || fileSizeBytes < MIN_FILE_BYTES) return false
        return isRemoteHttps(url)
    }

    internal fun isRemoteHttps(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        val host = parsed.host.lowercase()
        // IPv6 literals: not worth classifying, leave them on one connection.
        if (host.contains(':')) return false
        if (!host.contains('.')) return false
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return false
        ipv4Octets(host)?.let { return !isPrivateIpv4(it) }
        return true
    }

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal")

    private fun ipv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPrivateIpv4(o: IntArray): Boolean = when {
        o[0] == 10 || o[0] == 127 || o[0] == 0 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        o[0] == 100 && o[1] in 64..127 -> true
        else -> false
    }
}
