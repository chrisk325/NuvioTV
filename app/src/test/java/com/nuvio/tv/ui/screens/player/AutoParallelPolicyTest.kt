package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoParallelPolicyTest {

    private val remux = 60L * 1024L * 1024L * 1024L
    private val debridUrl = "https://aiostreams.example.ink/api/v1/debrid/playback/abc/Movie.2160p.REMUX.mkv"

    private fun decide(
        customNetworkEnabled: Boolean = false,
        lowRamTier: Boolean = false,
        singleConnectionWindowRaised: Boolean = false,
        liveTv: Boolean = false,
        torrent: Boolean = false,
        adaptive: Boolean = false,
        url: String = debridUrl,
        sanitizedHeaders: Map<String, String> = emptyMap(),
        fileSizeBytes: Long? = remux,
    ) = AutoParallelPolicy.shouldUse(
        customNetworkEnabled, lowRamTier, singleConnectionWindowRaised, liveTv, torrent, adaptive,
        url, sanitizedHeaders, fileSizeBytes
    )

    @Test
    fun `large remote debrid file on a high-RAM box uses parallel connections`() {
        assertTrue(decide())
    }

    @Test
    fun `exactly the threshold counts as large`() {
        assertTrue(decide(fileSizeBytes = AutoParallelPolicy.MIN_FILE_BYTES))
    }

    @Test
    fun `smaller or unknown files stay on one connection`() {
        assertFalse(decide(fileSizeBytes = AutoParallelPolicy.MIN_FILE_BYTES - 1))
        assertFalse(decide(fileSizeBytes = null))
    }

    @Test
    fun `user's Custom Network choice always wins`() {
        assertFalse(decide(customNetworkEnabled = true))
    }

    @Test
    fun `device and stream exclusions keep one connection`() {
        assertFalse(decide(lowRamTier = true))
        assertFalse(decide(singleConnectionWindowRaised = true))
        assertFalse(decide(liveTv = true))
        assertFalse(decide(torrent = true))
        assertFalse(decide(adaptive = true))
        assertFalse(decide(sanitizedHeaders = mapOf("Referer" to "https://example.com")))
    }

    @Test
    fun `cleartext and local servers stay on one connection`() {
        assertFalse(decide(url = "http://cdn.example.com/movie.mkv"))
        assertFalse(decide(url = "https://127.0.0.1:8080/movie.mkv"))
        assertFalse(decide(url = "https://localhost/movie.mkv"))
        assertFalse(decide(url = "https://192.168.1.20/movie.mkv"))
        assertFalse(decide(url = "https://10.0.0.5/movie.mkv"))
        assertFalse(decide(url = "https://172.20.0.5/movie.mkv"))
        assertFalse(decide(url = "https://nas.local/movie.mkv"))
        assertFalse(decide(url = "https://jellyfin/movie.mkv"))
        assertFalse(decide(url = "https://[::1]/movie.mkv"))
        assertFalse(decide(url = "not a url"))
    }

    @Test
    fun `public addresses count as remote`() {
        assertTrue(AutoParallelPolicy.isRemoteHttps("https://203.0.113.7/movie.mkv"))
        assertTrue(AutoParallelPolicy.isRemoteHttps("https://172.32.0.1/movie.mkv"))
        assertTrue(AutoParallelPolicy.isRemoteHttps("https://download.real-debrid.com/d/abc/movie.mkv"))
    }
}
