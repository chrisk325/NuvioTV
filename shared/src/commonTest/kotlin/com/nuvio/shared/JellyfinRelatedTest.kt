package com.nuvio.shared

import com.nuvio.shared.mediaserver.MediaServerService
import com.nuvio.shared.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JellyfinRelatedTest {
    private val json = headersOf("Content-Type", "application/json")
    private val serverStore get() = InMemoryStore().apply {
        putString("mediaservers.v1", """[{"id":"srv","kind":"jellyfin","name":"AIOMetadata","address":"http://jf","userId":"u1","token":"tok"}]""")
    }

    private fun client(serverDelayMs: Long) = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("/movie/42/external_ids") -> """{"imdb_id":"tt42"}"""
            path.endsWith("/movie/42") -> """{"id":42,"title":"From TMDB","external_ids":{"imdb_id":"tt42"}}"""
            url.startsWith("http://jf/") -> { delay(serverDelayMs); """{"Items":[],"TotalRecordCount":0}""" }
            else -> return@MockEngine respond("nf", HttpStatusCode.NotFound)
        }
        respond(body, HttpStatusCode.OK, json)
    })

    @Test
    fun relatedTitleOpensWithJellyfinAndNoAddons() = runTest {
        val core = NuvioCore(serverStore, client(0), StandardTestDispatcher(testScheduler), NuvioConfig.EMPTY.copy(tmdbApiKey = "key"))
        assertEquals("From TMDB", core.meta("movie", "tmdb:42", null).name)
        core.close()
    }

    @Test
    fun slowServerDoesNotHoldUpRelatedTitles() = runTest {
        val core = NuvioCore(serverStore, client(120_000), StandardTestDispatcher(testScheduler), NuvioConfig.EMPTY.copy(tmdbApiKey = "key"))
        val started = testScheduler.currentTime
        assertEquals("From TMDB", core.meta("movie", "tmdb:42", null).name)
        kotlin.test.assertTrue(testScheduler.currentTime - started <= 9_000, "waited ${testScheduler.currentTime - started} ms")
        core.close()
    }

    @Test
    fun storedStatisticsEntriesAreKept() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Users/u1/Items/m1" -> respond(
                    """{"Id":"m1","Name":"Movie","Type":"Movie","MediaSources":[
                        {"Id":"stats","Name":"📊 Statistics\n> 120 results","Type":"Placeholder"},
                        {"Id":"p1","Name":"AIOMetadata - Streams load when played","Type":"Placeholder"}]}""",
                    HttpStatusCode.OK, json
                )
                "/Items/m1/PlaybackInfo" -> respond(
                    """{"MediaSources":[
                        {"Id":"stats","Name":"📊 Statistics\n> 120 results","Type":"Placeholder"},
                        {"Id":"a1","Name":"[RD+] 1080p","Path":"https://aio.example/a1","Protocol":"Http"}]}""",
                    HttpStatusCode.OK, json
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val streams = MediaServerService(http, serverStore).streams("movie", "mediaserver:srv:m1").single().streams
        assertEquals(2, streams.size)
        assertEquals("📊 Statistics", streams[0].name)
        assertEquals("[RD+] 1080p", streams[1].title)
        assertNull(streams.first().url)
    }

    @Test
    fun infoEntriesWithoutIdOrPathAreShown() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Users/u1/Items/m1" -> respond("""{"Id":"m1","Name":"Movie","Type":"Movie","MediaSources":[]}""", HttpStatusCode.OK, json)
                "/Items/m1/PlaybackInfo" -> respond(
                    """{"MediaSources":[
                        {"Name":"📊 Statistics\n> 3 addons"},
                        {"Name":"ℹ️ No results from Torrentio","Type":"Placeholder"},
                        {"Id":"a1","Name":"[RD+] 1080p","Path":"https://aio.example/a1","Protocol":"Http"}]}""",
                    HttpStatusCode.OK, json
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val streams = MediaServerService(http, serverStore).streams("movie", "mediaserver:srv:m1").single().streams
        assertEquals(3, streams.size)
        assertEquals("📊 Statistics", streams[0].name)
        assertNull(streams[0].url)
        assertNull(streams[1].url)
        assertEquals("https://aio.example/a1", streams[2].url)
    }
}
