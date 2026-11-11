package com.nuvio.tv.reshaped.livetv

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvStreamTypeTest {

    @Test
    fun `kodiprop manifest type and mimetype name the channel format`() {
        assertEquals("hls", m3uKodiStreamType("inputstream.adaptive.manifest_type=hls"))
        assertEquals("dash", m3uKodiStreamType("inputstream.adaptive.manifest_type=MPD"))
        assertEquals("ss", m3uKodiStreamType("inputstream.adaptive.manifest_type=ism"))
        assertEquals("hls", m3uKodiStreamType("mimetype=application/x-mpegURL"))
        assertEquals("dash", m3uKodiStreamType("mimetype=application/dash+xml"))
        assertNull(m3uKodiStreamType("inputstream=inputstream.adaptive"))
        assertNull(m3uKodiStreamType("inputstream.adaptive.manifest_type="))
    }

    @Test
    fun `playlist gives the kodiprop format to the next channel only`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,One
            #KODIPROP:inputstream.adaptive.manifest_type=hls
            http://example.com/live/1
            #EXTINF:-1,Two
            http://example.com/live/2
        """.trimIndent()
        val channels = parseM3uPlaylist(playlist.lineSequence()).channels
        assertEquals(listOf("hls", null), channels.map { it.streamType })
    }

    @Test
    fun `stream type maps to the adaptive mime types only`() {
        assertEquals(MimeTypes.APPLICATION_M3U8, liveTvStreamMimeType("m3u8"))
        assertEquals(MimeTypes.APPLICATION_M3U8, liveTvStreamMimeType("HLS"))
        assertEquals(MimeTypes.APPLICATION_MPD, liveTvStreamMimeType("dash"))
        assertEquals(MimeTypes.APPLICATION_SS, liveTvStreamMimeType("ss"))
        assertNull(liveTvStreamMimeType("ts"))
        assertNull(liveTvStreamMimeType(null))
    }

    @Test
    fun `registry keeps the mime type of the url it was registered with`() {
        LiveTvPlaybackRegistry.register("http://example.com/a", mimeType = MimeTypes.APPLICATION_M3U8)
        LiveTvPlaybackRegistry.register("http://example.com/b")
        assertEquals(MimeTypes.APPLICATION_M3U8, LiveTvPlaybackRegistry.mimeTypeFor("http://example.com/a"))
        assertNull(LiveTvPlaybackRegistry.mimeTypeFor("http://example.com/b"))
        // Registering the same link again without a type drops the old one.
        LiveTvPlaybackRegistry.register("http://example.com/a")
        assertNull(LiveTvPlaybackRegistry.mimeTypeFor("http://example.com/a"))
    }
}
