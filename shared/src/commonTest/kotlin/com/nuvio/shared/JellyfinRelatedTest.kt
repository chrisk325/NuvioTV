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

    @Test
    fun nextUpBecomesUpNextEntries() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Shows/NextUp" -> respond(
                    """{"Items":[
                        {"Id":"e4","Type":"Episode","SeriesId":"s1","SeriesName":"Show One","Name":"Four","ParentIndexNumber":1,"IndexNumber":4},
                        {"Id":"e9","Type":"Episode","SeriesId":"s2","SeriesName":"Show Two","Name":"Nine","ParentIndexNumber":2,"IndexNumber":9}]}""",
                    HttpStatusCode.OK, json
                )
                "/Users/u1/Items" -> respond(
                    """{"Items":[
                        {"Id":"s1","Type":"Series","ProviderIds":{"Imdb":"tt100"},"UserData":{"LastPlayedDate":"2026-11-01T10:00:00Z"}},
                        {"Id":"s2","Type":"Series","ProviderIds":{},"UserData":{}}]}""",
                    HttpStatusCode.OK, json
                )
                "/Users/u1/Items/s2" -> respond("""{"Id":"s2","Type":"Series","ProviderIds":{}}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val next = MediaServerService(http, serverStore).nextUp()
        assertEquals(listOf("tt100:1:4", "mediaserver:srv:e9"), next.map { it.videoId })
        assertEquals(listOf("tt100", "mediaserver:srv:s2"), next.map { it.contentId })
        assertEquals(0L, next[0].durationMs)
        assertEquals(com.nuvio.shared.util.parseReleaseEpochMs("2026-11-01T10:00:00Z"), next[0].updatedAtMs)
        assertEquals("Four", next[0].episodeTitle)
        kotlin.test.assertTrue(next[1].updatedAtMs < next[0].updatedAtMs)
    }

    @Test
    fun standInsWithoutPlaceholderTypeAreResolvedThroughTheStreamListOne() = runTest {
        val asked = mutableListOf<String?>()
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Users/u1/Items/e1" -> respond(
                    """{"Id":"e1","Name":"Marnukh","Type":"Episode","MediaSources":[
                        {"Id":"auto","Name":"Streams load when played","Container":"mp4","Protocol":"File"},
                        {"Id":"list","Name":"Load the stream list","Container":"mp4","Protocol":"File"}]}""",
                    HttpStatusCode.OK, json
                )
                "/Items/e1/PlaybackInfo" -> {
                    val source = request.url.parameters["MediaSourceId"]
                    asked += source
                    if (source == "list") respond(
                        """{"MediaSources":[
                            {"Name":"📊 Statistics\n> 42 results"},
                            {"Id":"r1","Name":"[RD+] 2160p\n> 12 GB","Path":"https://aio.example/r1","Protocol":"Http"}]}""",
                        HttpStatusCode.OK, json
                    ) else respond(
                        """{"MediaSources":[
                            {"Id":"auto","Name":"Streams load when played","Container":"mp4","Protocol":"File"},
                            {"Id":"list","Name":"Load the stream list","Container":"mp4","Protocol":"File"}]}""",
                        HttpStatusCode.OK, json
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val streams = MediaServerService(http, serverStore).streams("series", "mediaserver:srv:e1").single().streams
        assertEquals(listOf("📊 Statistics", "[RD+] 2160p"), streams.map { it.name })
        assertEquals("list", asked[1]) // the stream-list stand-in is asked first
    }

    @Test
    fun onlyTheServersPickRemainsWhenNothingResolves() = runTest {
        val http = HttpClient(MockEngine { request ->
            val body = """{"Id":"e1","Name":"X","Type":"Episode","MediaSources":[
                {"Id":"auto","Name":"Streams load when played","Container":"mp4","Protocol":"File"},
                {"Id":"list","Name":"Load the stream list","Container":"mp4","Protocol":"File"}]}"""
            when (request.url.encodedPath) {
                "/Users/u1/Items/e1", "/Items/e1/PlaybackInfo" -> respond(body, HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val streams = MediaServerService(http, serverStore).streams("series", "mediaserver:srv:e1").single().streams
        assertEquals(1, streams.size)
        assertEquals("Streams load when played", streams.single().title)
    }
    @Test
    fun streamListComesFromTheItemsMediaSourcesLikeAndroid() = runTest {
        var playbackInfoAsked = false
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val androidStyle = path == "/Items/m1" && request.url.parameters["userId"] == "u1" &&
                request.url.parameters["fields"] == "MediaSources" &&
                request.headers["Authorization"]?.startsWith("MediaBrowser Client=\"Nuvio\"") == true &&
                request.headers["Authorization"]?.contains("Token=\"tok\"") == true
            when {
                androidStyle -> respond(
                    """{"Id":"m1","Name":"The Odyssey","Type":"Movie","MediaSources":[
                        {"Name":"📊 Statistics\n> 42 results"},
                        {"Id":"r1","Name":"[RD+] 2160p","Path":"https://aio.example/r1","Protocol":"Http"},
                        {"Id":"r2","Name":"[RD+] 1080p","Path":"https://aio.example/r2","Protocol":"Http"}]}""",
                    HttpStatusCode.OK, json
                )
                path == "/Users/u1/Items/m1" -> respond(
                    """{"Id":"m1","Name":"The Odyssey","Type":"Movie","MediaSources":[
                        {"Id":"auto","Name":"AIOMetadata MP4 – Streams load when played","Container":"mp4","Protocol":"File"}]}""",
                    HttpStatusCode.OK, json
                )
                path == "/Items/m1/PlaybackInfo" -> { playbackInfoAsked = true; respond("", HttpStatusCode.NotFound) }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val streams = MediaServerService(http, serverStore).streams("movie", "mediaserver:srv:m1").single().streams
        assertEquals(listOf("📊 Statistics", "[RD+] 2160p", "[RD+] 1080p"), streams.map { if (it.name == "📊 Statistics") it.name else it.title })
        assertEquals("https://aio.example/r1", streams[1].url)
        kotlin.test.assertFalse(playbackInfoAsked)
    }
    @Test
    fun removingFromContinueWatchingClearsTheJellyfinResumePoint() = runTest {
        val cleared = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/Users/u1/Items/Resume" -> respond(
                    """{"Items":[
                        {"Id":"m1","Name":"Movie","Type":"Movie","RunTimeTicks":72000000000,"ProviderIds":{"Imdb":"tt1"},
                         "UserData":{"PlaybackPositionTicks":6000000000,"LastPlayedDate":"2026-11-18T10:00:00Z"}},
                        {"Id":"e1","Name":"Pilot","Type":"Episode","SeriesId":"s1","SeriesName":"Show","ParentIndexNumber":1,"IndexNumber":1,
                         "RunTimeTicks":36000000000,"UserData":{"PlaybackPositionTicks":6000000000,"LastPlayedDate":"2026-11-18T11:00:00Z"}}]}""",
                    HttpStatusCode.OK, json
                )
                path == "/Items/s1" -> respond("""{"Id":"s1","Type":"Series","ProviderIds":{"Imdb":"tt2"}}""", HttpStatusCode.OK, json)
                path.startsWith("/UserItems/") && path.endsWith("/UserData") -> {
                    cleared += path.removePrefix("/UserItems/").removeSuffix("/UserData")
                    respond("{}", HttpStatusCode.OK, json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val service = MediaServerService(http, serverStore)
        assertEquals(1, service.removeFromResume("tt2"))
        assertEquals(listOf("e1"), cleared)
        assertEquals(1, service.removeFromResume("tt1"))
        assertEquals(listOf("e1", "m1"), cleared)
    }
    @Test
    fun discoverBrowsesALibraryByGenre() = runTest {
        var asked: io.ktor.http.Url? = null
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Genres" -> respond("""{"Items":[{"Name":"Action"},{"Name":"Drama"}]}""", HttpStatusCode.OK, json)
                "/Users/u1/Items" -> {
                    asked = request.url
                    respond("""{"Items":[{"Id":"m1","Name":"Heat","Type":"Movie","ProviderIds":{"Imdb":"tt0113277"}}]}""", HttpStatusCode.OK, json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val service = MediaServerService(http, serverStore)
        val library = com.nuvio.shared.mediaserver.ServerLibrary(service.servers.single(), "lib1", "Films", null)
        assertEquals(listOf("Action", "Drama"), service.genres(library))
        val page = service.browse(library, 0, 60, "Action", "Movie")
        assertEquals(listOf("tt0113277"), page.map { it.id })
        assertEquals("Action", asked?.parameters?.get("Genres"))
        assertEquals("Movie", asked?.parameters?.get("IncludeItemTypes"))
        assertEquals("lib1", asked?.parameters?.get("ParentId"))
    }
}
