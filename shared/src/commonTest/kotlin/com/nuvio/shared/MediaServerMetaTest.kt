package com.nuvio.shared

import com.nuvio.shared.mediaserver.MediaServerService
import com.nuvio.shared.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaServerMetaTest {
    @Test
    fun seriesDetailsFromJellyfin() = runTest {
        val json = headersOf("Content-Type", "application/json")
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Users/u1/Items" -> respond(
                    """{"Items":[{"Id":"s1","Name":"Show","ProviderIds":{"Imdb":"tt100"}}]}""", HttpStatusCode.OK, json
                )
                "/Users/u1/Items/s1" -> respond(
                    """{"Id":"s1","Name":"Show","Overview":"Plot","ProductionYear":2024,"CommunityRating":8.25,
                        "Genres":["Drama"],"OfficialRating":"TV-MA","BackdropImageTags":["b"],"ImageTags":{"Logo":"l"},
                        "People":[{"Name":"Ann","Role":"Lead","Type":"Actor","Id":"p1","PrimaryImageTag":"x"},
                                  {"Name":"Dee","Type":"Director","Id":"p2"}]}""",
                    HttpStatusCode.OK, json
                )
                "/Shows/s1/Episodes" -> respond(
                    """{"Items":[{"Id":"e1","Name":"Pilot","ParentIndexNumber":1,"IndexNumber":1,"RunTimeTicks":27000000000,"Overview":"First"},
                                 {"Id":"e2","Name":"Two","ParentIndexNumber":1,"IndexNumber":2}]}""",
                    HttpStatusCode.OK, json
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply {
            putString("mediaservers.v1", """[{"id":"srv","kind":"jellyfin","name":"Home","address":"http://jf","userId":"u1","token":"tok"}]""")
        }
        val meta = assertNotNull(MediaServerService(http, store).meta("series", "tt100"))
        assertEquals("Show", meta.name)
        assertEquals("8.2", meta.imdbRating)
        assertEquals(listOf("Dee"), meta.director)
        assertEquals("Lead", meta.castMembers.single().character)
        assertTrue(meta.logo!!.contains("/Items/s1/Images/Logo"))
        assertEquals(listOf("tt100:1:1", "tt100:1:2"), meta.videos.map { it.id })
        assertEquals(45, meta.videos.first().runtimeMinutes)
    }
}

class MediaServerLookupTest {
    private val json = headersOf("Content-Type", "application/json")
    private fun store() = InMemoryStore().apply {
        putString("mediaservers.v1", """[{"id":"srv","kind":"jellyfin","name":"Home","address":"http://jf","userId":"u1","token":"tok"}]""")
    }

    @Test
    fun jellyfinIgnoringTheProviderFilterStillFindsTheTitleThroughTheIndex() = runTest {
        val http = HttpClient(MockEngine { request ->
            val params = request.url.parameters
            when {
                request.url.encodedPath == "/Users/u1/Items" && params["AnyProviderIdEquals"] != null ->
                    // Filter ignored: unrelated items come back.
                    respond("""{"Items":[{"Id":"x","Type":"Movie","ProviderIds":{"Imdb":"tt999"}}]}""", HttpStatusCode.OK, json)
                request.url.encodedPath == "/Users/u1/Items" && params["EnableImages"] == "false" ->
                    respond(
                        """{"Items":[{"Id":"x","Type":"Movie","ProviderIds":{"Imdb":"tt999"}},
                                     {"Id":"m1","Type":"Movie","ProviderIds":{"Imdb":"tt555","Tmdb":"42"}}]}""",
                        HttpStatusCode.OK, json
                    )
                request.url.encodedPath == "/Users/u1/Items/m1" ->
                    respond("""{"Id":"m1","Name":"Superman","Type":"Movie","Overview":"Plot","ProviderIds":{"Imdb":"tt555"},
                        "MediaSources":[{"Id":"src","Container":"mkv","Size":2000000000}]}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val service = MediaServerService(http, store())
        assertEquals("Superman", service.meta("movie", "tt555")?.name)
        assertEquals("Superman", service.meta("movie", "tmdb:42")?.name)
        val streams = service.streams("movie", "tt555")
        assertTrue(streams.single().streams.single().url!!.contains("/Videos/m1/stream"))
    }

    @Test
    fun titlesWithoutImdbIdUseServerNativeIds() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Users/u1/Items" -> respond(
                    """{"Items":[{"Id":"m9","Name":"Home Video","Type":"Movie","ProviderIds":{}}]}""", HttpStatusCode.OK, json
                )
                "/Users/u1/Items/m9" -> respond(
                    """{"Id":"m9","Name":"Home Video","Type":"Movie","MediaSources":[{"Id":"s9","Container":"mp4"}]}""",
                    HttpStatusCode.OK, json
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val service = MediaServerService(http, store())
        val preview = service.search(service.servers.single(), "home", 10).single()
        assertEquals("mediaserver:srv:m9", preview.id)
        val meta = assertNotNull(service.meta("movie", preview.id))
        assertEquals("Home Video", meta.name)
        assertTrue(service.streams("movie", meta.defaultVideoId).single().streams.single().url!!.contains("/Videos/m9/stream"))
    }
}

class MediaServerNewerJellyfinTest {
    private val json = headersOf("Content-Type", "application/json")

    @Test
    fun rowItemsOpenDirectlyAndNewItemRouteIsUsed() = runTest {
        var indexCalls = 0
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                // Library row listing (search here) includes the provider ids.
                path == "/Users/u1/Items" && request.url.parameters["SearchTerm"] != null -> respond(
                    """{"Items":[{"Id":"m1","Name":"Project Hail Mary","Type":"Movie","ProviderIds":{"Imdb":"tt777"}}]}""",
                    HttpStatusCode.OK, json
                )
                path == "/Users/u1/Items" -> { indexCalls++; respond("", HttpStatusCode.BadRequest) }
                // Old per-user item route gone; the new one answers.
                path == "/Users/u1/Items/m1" -> respond("", HttpStatusCode.NotFound)
                path == "/Items/m1" && request.url.parameters["userId"] == "u1" -> respond(
                    """{"Id":"m1","Name":"Project Hail Mary","Type":"Movie","Overview":"Space",
                        "MediaSources":[{"Id":"s1","Container":"mkv"}]}""",
                    HttpStatusCode.OK, json
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply {
            putString("mediaservers.v1", """[{"id":"srv","kind":"jellyfin","name":"Home","address":"http://jf","userId":"u1","token":"tok"}]""")
        }
        val service = MediaServerService(http, store)
        service.search(service.servers.single(), "hail", 10)
        assertEquals("Space", service.meta("movie", "tt777")?.description)
        assertTrue(service.streams("movie", "tt777").single().streams.single().url!!.contains("/Videos/m1/stream"))
        assertEquals(0, indexCalls)
    }
}

class MergeMetaTest {
    @Test
    fun serverFillsOnlyTheGaps() {
        fun meta(name: String, description: String?, genres: List<String>, poster: String?) = com.nuvio.shared.model.Meta(
            id = "tt1", type = com.nuvio.shared.model.ContentType.MOVIE, name = name, poster = poster,
            posterShape = com.nuvio.shared.model.PosterShape.POSTER, background = null, logo = null,
            description = description, releaseInfo = null, imdbRating = null, genres = genres, runtime = null,
            director = emptyList(), cast = emptyList(), videos = emptyList(), country = null, awards = null,
            language = null, links = emptyList()
        )
        val addon = meta("Superman", null, emptyList(), "addon-poster")
        val server = meta("Superman (server)", "Plot", listOf("Action"), "server-poster")
        val merged = com.nuvio.shared.mergeMeta(addon, server)
        assertEquals("Superman", merged.name)
        assertEquals("addon-poster", merged.poster)
        assertEquals("Plot", merged.description)
        assertEquals(listOf("Action"), merged.genres)
    }
}

class CatalogNameTest {
    @Test
    fun addonSuffixIsStripped() {
        assertEquals("TMDB Trending", com.nuvio.shared.repository.catalogNameWithoutAddon("TMDB Trending - AIOMetadata", "AIOMetadata"))
        assertEquals("TMDB Trending", com.nuvio.shared.repository.catalogNameWithoutAddon("TMDB Trending (AIOMetadata)", "aiometadata"))
        assertEquals("Popular", com.nuvio.shared.repository.catalogNameWithoutAddon("Popular", "Cinemeta"))
    }
}

class MediaServerPlaybackInfoTest {
    private val json = headersOf("Content-Type", "application/json")

    @Test
    fun placeholderSourcesAreReplacedByWhatPlaybackInfoResolves() = runTest {
        var posted = 0
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Users/u1/Items/m1" -> respond(
                    """{"Id":"m1","Name":"Superman","Type":"Movie","MediaSources":[
                        {"Id":"p1","Name":"AIOMetadata MP4 - Streams load when played","Type":"Placeholder","Container":"mp4"}]}""",
                    HttpStatusCode.OK, json
                )
                "/Items/m1/PlaybackInfo" -> {
                    posted++
                    respond(
                        """{"PlaySessionId":"ps","MediaSources":[
                            {"Id":"a1","Name":"[RD+] 2160p Superman.mkv","Path":"https://aio.example/play/a1.mkv","Protocol":"Http",
                             "SupportsDirectPlay":true,"Size":40000000000,
                             "MediaStreams":[{"Type":"Video","Width":3840,"Height":2160}]},
                            {"Id":"a2","Name":"[RD+] 1080p Superman.mkv","Protocol":"File","SupportsDirectPlay":true}]}""",
                        HttpStatusCode.OK, json
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply {
            putString("mediaservers.v1", """[{"id":"srv","kind":"jellyfin","name":"AIOMetadata","address":"http://jf","userId":"u1","token":"tok"}]""")
        }
        val streams = MediaServerService(http, store).streams("movie", "mediaserver:srv:m1").single().streams
        assertTrue(posted >= 1)
        assertEquals(listOf("[RD+] 2160p Superman.mkv", "[RD+] 1080p Superman.mkv"), streams.map { it.title })
        assertEquals("https://aio.example/play/a1.mkv", streams[0].url)
        assertEquals("AIOMetadata\n4K", streams[0].name)
        assertEquals("http://jf/Videos/m1/stream?static=true&MediaSourceId=a2&api_key=tok", streams[1].url)
    }

    @Test
    fun formatterTextSplitsIntoNameAndDescription() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Users/u1/Items/m1" -> respond("""{"Id":"m1","Name":"Show","Type":"Episode","MediaSources":[]}""", HttpStatusCode.OK, json)
                "/Items/m1/PlaybackInfo" -> respond(
                    """{"MediaSources":[{"Id":"a1","Name":"Web T1 : 6375\n> 4K\n> 11.21 GB","Path":"https://aio.example/a1","Protocol":"Http","Size":11208000000}]}""",
                    HttpStatusCode.OK, json
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply {
            putString("mediaservers.v1", """[{"id":"srv","kind":"jellyfin","name":"AIOMetadata","address":"http://jf","userId":"u1","token":"tok"}]""")
        }
        val stream = MediaServerService(http, store).streams("series", "mediaserver:srv:m1").single().streams.single()
        assertEquals("Web T1 : 6375", stream.name)
        assertEquals("> 4K\n> 11.21 GB", stream.description)
    }
}
