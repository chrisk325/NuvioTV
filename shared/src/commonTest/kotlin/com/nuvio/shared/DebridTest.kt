package com.nuvio.shared

import com.nuvio.shared.data.parseStreamResponse
import com.nuvio.shared.debrid.DebridFile
import com.nuvio.shared.debrid.DebridProviders
import com.nuvio.shared.debrid.DebridService
import com.nuvio.shared.debrid.FileTarget
import com.nuvio.shared.debrid.selectDebridFile
import com.nuvio.shared.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebridTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun fileSelectionMatchesEpisodeThenLargest() {
        val files = listOf(
            DebridFile("1", "Show/Show.S01E01.mkv", 100, false),
            DebridFile("2", "Show/Show.S01E02.mkv", 120, false),
            DebridFile("3", "Show/sample.txt", 5, false),
        )
        assertEquals("2", selectDebridFile(files, FileTarget(emptyList(), 1, 2, null))?.id)
        assertEquals("2", selectDebridFile(files, FileTarget(emptyList(), null, null, null))?.id)
        assertEquals("1", selectDebridFile(files, FileTarget(listOf("Show.S01E01.mkv"), null, null, null))?.id)
        assertNull(selectDebridFile(files, FileTarget(emptyList(), 2, 1, null)))
    }

    @Test
    fun magnetIncludesTrackersAndName() {
        val stream = parseStreamResponse(
            """{"streams":[{"name":"T","infoHash":"$hash","sources":["tracker:udp://t.example:80","dht:$hash"],
                "behaviorHints":{"filename":"Movie 2020.mkv"}}]}""",
            "A", null
        ).single()
        assertTrue(stream.needsDebridResolve)
        assertEquals(
            "magnet:?xt=urn:btih:$hash&dn=Movie%202020.mkv&tr=udp%3A%2F%2Ft.example%3A80",
            DebridService.buildMagnet(stream)
        )
    }

    @Test
    fun torboxResolveAndCacheCheck() = runTest {
        val http = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            val body = when {
                "checkcached" in url -> """{"success":true,"data":{"$hash":{"name":"x","size":1}}}"""
                "createtorrent" in url -> """{"success":true,"data":{"torrent_id":42}}"""
                "mylist" in url -> """{"success":true,"data":{"id":42,"files":[{"id":7,"name":"Movie.mkv","mimetype":"video/x-matroska","size":999}]}}"""
                "requestdl" in url -> """{"success":true,"data":"https://cdn.torbox/dl/7"}"""
                else -> return@MockEngine respond("", HttpStatusCode.NotFound)
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val service = DebridService(http, InMemoryStore(), "")
        service.setApiKey(DebridProviders.TORBOX_ID, "key")
        assertEquals(DebridProviders.TORBOX_ID, service.preferredProviderId)
        val stream = parseStreamResponse("""{"streams":[{"name":"T","infoHash":"$hash"}]}""", "A", null).single()

        val annotated = service.annotateCache(listOf(com.nuvio.shared.model.AddonStreams("A", null, listOf(stream))))
        assertEquals(true, annotated.single().streams.single().debridCache?.isCached)

        val playable = service.resolveToPlayable(stream, 0, 0)
        assertEquals("https://cdn.torbox/dl/7", playable.directPlaybackUrl)
        assertEquals("Movie.mkv", playable.behaviorHints?.filename)
        assertTrue(playable.debridResolved)
    }
}
