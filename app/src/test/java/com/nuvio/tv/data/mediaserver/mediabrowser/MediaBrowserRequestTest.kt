package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerIndexEntry
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.verifiedTitleMatches
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest

class MediaBrowserRequestTest {
    private fun session(providerId: String, address: String) = ServerSession(
        connection = ServerConnection(
            id = "cabc",
            providerId = providerId,
            name = "Home",
            address = address,
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE))
        ),
        token = "secret"
    )

    @Test
    fun searchesAioStreamsOnceAcrossItsCatalogs() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jellyfin/System/Info/Public" -> """{"ServerName": "Den", "Version": "10.10.7", "aiostreams": {"features": {}}}"""
                else -> """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val libraries = listOf(ServerLibrary("lib1", "Popular", ServerMediaKind.MOVIE), ServerLibrary("lib2", "Trending", ServerMediaKind.MOVIE))

        val first = jellyfin.search(session, ServerMediaKind.MOVIE, libraries, "heat", 30)
        jellyfin.search(session, ServerMediaKind.MOVIE, libraries, "heat", 30)

        assertEquals(listOf("Heat"), first.map { it.preview.name })
        val searches = http.requests.filter { it.url.encodedPath.endsWith("/Items") }
        assertEquals(1, http.requests.count { it.url.encodedPath.endsWith("/System/Info/Public") })
        assertEquals(2, searches.size)
        searches.forEach { request ->
            assertNull(request.url.queryParameter("parentId"))
            assertEquals("heat", request.url.queryParameter("searchTerm"))
            assertEquals("Movie", request.url.queryParameter("includeItemTypes"))
        }
    }

    @Test
    fun opensLibrariesThatNeedAGenreOnTheirFirstOne() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/Genres") -> """{"Items": [{"Id": "g1", "Name": "Action"}]}"""
                request.url.queryParameter("genreIds") == "g1" ->
                    """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}], "TotalRecordCount": 60}"""
                else -> """{"Items": [], "TotalRecordCount": 0}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val library = ServerLibrary("lib1", "By genre", ServerMediaKind.MOVIE)

        val first = jellyfin.libraryPage(session, library, start = 0, limit = 50)
        jellyfin.libraryPage(session, library, start = 50, limit = 50)

        assertEquals(listOf("Heat"), first.items.map { it.preview.name })
        assertEquals(60, first.totalCount)
        assertEquals(1, http.requests.count { it.url.encodedPath.endsWith("/Genres") })
        assertEquals("lib1", http.requests.single { it.url.encodedPath.endsWith("/Genres") }.url.queryParameter("parentId"))
        assertEquals("g1", http.requests.last().url.queryParameter("genreIds"))
    }

    @Test
    fun listsMixedLibrariesWithMoviesAndShows() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/UserViews")) {
                """{"Items": [{"Id": "lib1", "Name": "Movies", "Type": "CollectionFolder", "CollectionType": "movies"},
                              {"Id": "lib2", "Name": "Everything", "Type": "CollectionFolder"},
                              {"Id": "lib3", "Name": "Music", "Type": "CollectionFolder", "CollectionType": "music"}]}"""
            } else {
                """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}], "TotalRecordCount": 1}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")

        val libraries = jellyfin.libraries(session)
        jellyfin.libraryPage(session, libraries.last(), start = 0, limit = 50)

        assertEquals(listOf(ServerMediaKind.MOVIE, ServerMediaKind.MIXED), libraries.map { it.kind })
        assertEquals("Movie,Series", http.requests.last().url.queryParameter("includeItemTypes"))
    }

    @Test
    fun searchesEachSelectedLibraryOnJellyfin() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jellyfin/System/Info/Public" -> """{"ServerName": "Den", "Version": "10.10.7"}"""
                else -> """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val libraries = listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE), ServerLibrary("lib2", "4K", ServerMediaKind.MOVIE))

        val results = jellyfin.search(session("jellyfin", "https://media.example.com/jellyfin"), ServerMediaKind.MOVIE, libraries, "heat", 30)

        assertEquals(listOf("Heat"), results.map { it.preview.name })
        assertEquals(
            setOf("lib1", "lib2"),
            http.requests.filter { it.url.encodedPath.endsWith("/Items") }.map { it.url.queryParameter("parentId") }.toSet()
        )
    }

    @Test
    fun embySignInUsesApiRootAndEmbyHeader() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/emby/System/Info/Public" -> """{"ServerName": "Den", "Version": "4.8.10.0", "Id": "srv"}"""
                "/emby/Users/AuthenticateByName" -> """{"User": {"Id": "u1", "Name": "viewer"}, "AccessToken": "tok", "ServerId": "srv"}"""
                else -> error("Unexpected ${request.url}")
            }
        }
        val signIn = EmbyProvider(http.client, testIdentity).signIn("http://den.local:8096/emby/web/index.html", "viewer", "pw")

        assertEquals("http://den.local:8096", signIn.address)
        assertEquals("Den", signIn.serverName)
        assertEquals("srv", signIn.serverId)
        assertEquals("u1", signIn.userId)
        assertEquals("tok", signIn.token)
        val auth = http.requests.last()
        assertEquals("POST", auth.method)
        assertTrue(auth.text.contains("\"Pw\":\"pw\""))
        val header = auth.header("X-Emby-Authorization").orEmpty()
        assertTrue(header.startsWith("MediaBrowser Client=\"Nuvio\""))
        assertFalse(header.contains("Token="))
        assertNull(auth.header("Authorization"))
    }

    @Test
    fun embyRejectsJellyfinServers() = runTest {
        val http = TestHttp { """{"ServerName": "Den", "Version": "10.10.7", "ProductName": "Jellyfin Server", "Id": "srv"}""" }
        val error = runCatching {
            EmbyProvider(http.client, testIdentity).signIn("http://den.local:8096", "viewer", "pw")
        }.exceptionOrNull() as ServerException
        assertEquals(ServerFailure.UNSUPPORTED, error.failure)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun embyUsesUserScopedRoutes() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/Views") ->
                    """{"Items": [{"Id": "lib1", "Name": "Movies", "CollectionType": "movies"}, {"Id": "m", "Name": "Music", "CollectionType": "music"}]}"""
                request.url.encodedPath.endsWith("/Items/Resume") -> """{"Items": []}"""
                else -> ""
            }
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val session = session("emby", "https://media.example.com")

        val libraries = emby.libraries(session)
        emby.resumeItems(session, limit = 10)
        emby.setPlayed(session, "i1", played = true)
        emby.setPlayed(session, "i1", played = false)

        assertEquals(listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)), libraries)
        assertEquals(
            listOf(
                "GET https://media.example.com/emby/Users/u1/Views",
                "GET https://media.example.com/emby/Users/u1/Items/Resume",
                "POST https://media.example.com/emby/Users/u1/PlayedItems/i1",
                "DELETE https://media.example.com/emby/Users/u1/PlayedItems/i1"
            ),
            http.requests.map { "${it.method} ${it.url.toString().substringBefore('?')}" }
        )
        http.requests.forEach { request ->
            assertTrue(request.header("X-Emby-Authorization").orEmpty().endsWith("Token=\"secret\""))
            assertNull(request.url.queryParameter("userId"))
        }
    }

    @Test
    fun jellyfinUsesQueryScopedRoutes() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/UserViews")) """{"Items": []}""" else ""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")

        jellyfin.libraries(session)
        jellyfin.setPlayed(session, "i1", played = true)

        assertEquals(
            listOf(
                "GET https://media.example.com/jellyfin/UserViews?userId=u1",
                "POST https://media.example.com/jellyfin/UserPlayedItems/i1?userId=u1"
            ),
            http.requests.map { "${it.method} ${it.url}" }
        )
        http.requests.forEach { request ->
            assertTrue(request.header("Authorization").orEmpty().endsWith("Token=\"secret\""))
            assertNull(request.header("X-Emby-Authorization"))
        }
    }

    @Test
    fun embyLooksUpATitleByItsProviderIdsInOneRequest() = runTest {
        val http = TestHttp {
            """{"Items": [{"Id": "m1", "Name": "Apocalypse Now", "Type": "Movie", "ProductionYear": 1979,
                "ProviderIds": {"Imdb": "tt0078788", "Tmdb": "28"}}]}"""
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val query = ServerTitleQuery(TrackingExternalIds(imdb = "tt0078788", tmdb = 28), name = "Apocalypse Now", year = 1979)

        val found = emby.lookupTitle(session("emby", "https://media.example.com"), ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE), query)

        assertEquals(listOf(ServerIndexEntry("m1", TrackingExternalIds(imdb = "tt0078788", tmdb = 28), year = 1979)), found)
        val request = http.lookups.single()
        assertEquals("/emby/Users/u1/Items", request.url.encodedPath)
        assertEquals("imdb.tt0078788,tmdb.28", request.url.queryParameter("anyProviderIdEquals"))
        assertEquals("lib1", request.url.queryParameter("parentId"))
        assertEquals("Movie", request.url.queryParameter("includeItemTypes"))
        assertEquals("true", request.url.queryParameter("recursive"))
        assertEquals("ProviderIds,ProductionYear", request.url.queryParameter("fields"))
        assertNull(request.url.queryParameter("searchTerm"))
    }

    @Test
    fun embyCannotLookUpATitleWithoutImdbTmdbOrTvdbIds() = runTest {
        val http = TestHttp()
        val emby = EmbyProvider(http.client, testIdentity)
        val query = ServerTitleQuery(TrackingExternalIds(kitsu = 1), name = "Show", year = null)

        assertNull(emby.lookupTitle(session("emby", "https://media.example.com"), ServerLibrary("lib1", "Shows", ServerMediaKind.SERIES), query))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun jellyfinLooksUpATitleByName() = runTest {
        val http = TestHttp { """{"Items": [{"Id": "m1", "ProductionYear": 1979, "ProviderIds": {"Imdb": "tt0078788"}}]}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com")
        val library = ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)
        val ids = TrackingExternalIds(imdb = "tt0078788")

        val found = jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, name = "Apocalypse Now", year = 1979))

        assertEquals(listOf(ServerIndexEntry("m1", ids, year = 1979)), found)
        val request = http.lookups.single()
        assertEquals("/Items", request.url.encodedPath)
        assertEquals("u1", request.url.queryParameter("userId"))
        assertEquals("Apocalypse Now", request.url.queryParameter("searchTerm"))
        assertEquals("lib1", request.url.queryParameter("parentId"))
        assertEquals("20", request.url.queryParameter("limit"))
        assertNull(request.url.queryParameter("anyProviderIdEquals"))

        assertNull(jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, name = null, year = null)))
        assertEquals(1, http.lookups.size)
    }

    @Test
    fun jellyfinTriesTheOriginalTitleOnceWhenTheNameFindsNothing() = runTest {
        val http = TestHttp { request ->
            if (request.url.queryParameter("searchTerm") == "Intouchables") {
                """{"Items": [{"Id": "m1", "ProductionYear": 2011, "ProviderIds": {"Imdb": "tt1675434"}}]}"""
            } else {
                """{"Items": [{"Id": "m9", "ProductionYear": 2011, "ProviderIds": {"Imdb": "tt0000009"}}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com")
        val library = ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)
        val ids = TrackingExternalIds(imdb = "tt1675434")

        val found = jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "The Intouchables", 2011, originalName = "Intouchables"))!!

        assertTrue(ServerIndexEntry("m1", ids, year = 2011) in found)
        assertEquals(listOf("The Intouchables", "Intouchables"), http.lookups.map { it.url.queryParameter("searchTerm") })

        jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "Intouchables", 2011, originalName = "The Intouchables"))
        assertEquals(3, http.lookups.size)
        jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "The Intouchables", 2011))
        assertEquals(4, http.lookups.size)
        jellyfin.lookupTitle(session, library, ServerTitleQuery(ids, "The Intouchables", 2011, originalName = " the intouchables "))
        assertEquals(5, http.lookups.size)
    }

    @Test
    fun embyAsksOnceWhateverTheOriginalTitle() = runTest {
        val http = TestHttp { """{"Items": []}""" }
        val emby = EmbyProvider(http.client, testIdentity)
        val query = ServerTitleQuery(TrackingExternalIds(imdb = "tt1675434"), "The Intouchables", 2011, originalName = "Intouchables")

        val found = emby.lookupTitle(session("emby", "https://media.example.com"), ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE), query)

        assertTrue(found!!.isEmpty())
        assertEquals(1, http.lookups.size)
    }

    @Test
    fun jellyfinMarksAndClearsFavourites() = runTest {
        val http = TestHttp { "{}" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com")

        jellyfin.setFavorite(session, "m1", favorite = true)
        jellyfin.setFavorite(session, "m1", favorite = false)

        assertEquals(listOf("POST", "DELETE"), http.requests.map { it.method })
        http.requests.forEach { request ->
            assertEquals("/UserFavoriteItems/m1", request.url.encodedPath)
            assertEquals("u1", request.url.queryParameter("userId"))
        }
    }

    @Test
    fun embyMarksFavouritesUnderTheUser() = runTest {
        val http = TestHttp { "{}" }
        val emby = EmbyProvider(http.client, testIdentity)

        emby.setFavorite(session("emby", "https://media.example.com"), "m1", favorite = true)

        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/emby/Users/u1/FavoriteItems/m1", request.url.encodedPath)
    }

    @Test
    fun listsTheUsersFavouriteMoviesAndShows() = runTest {
        val http = TestHttp {
            """{"Items": [
                {"Id": "m1", "Name": "Heat", "Type": "Movie", "ProviderIds": {"Imdb": "tt0113277"}},
                {"Id": "s1", "Name": "Dark", "Type": "Series", "ProviderIds": {"Tmdb": "70523"}}
            ], "TotalRecordCount": 2}"""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)

        val page = jellyfin.favoritesPage(session("jellyfin", "https://media.example.com"), start = 0, limit = 50)

        assertEquals(listOf("movie", "series"), page.items.map { it.preview.apiType })
        assertEquals(TrackingExternalIds(imdb = "tt0113277"), page.items.first().externalIds)
        assertEquals(2, page.totalCount)
        val request = http.requests.single()
        assertEquals("/Items", request.url.encodedPath)
        assertEquals("IsFavorite", request.url.queryParameter("filters"))
        assertEquals("Movie,Series", request.url.queryParameter("includeItemTypes"))
        assertEquals("true", request.url.queryParameter("recursive"))
        assertEquals("u1", request.url.queryParameter("userId"))
        assertEquals("50", request.url.queryParameter("limit"))
    }

    @Test
    fun looksUpTitlesAcrossAioStreamsRatherThanInOneCatalog() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jellyfin/System/Info/Public" -> """{"ServerName": "Den", "Version": "10.10.7", "aiostreams": {"features": {}}}"""
                else -> """{"Items": [{"Id": "m1", "ProductionYear": 1994, "ProviderIds": {"Imdb": "tt0111161"}}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val popular = ServerLibrary("lib1", "Popular", ServerMediaKind.MOVIE)
        val trending = ServerLibrary("lib2", "Trending", ServerMediaKind.MOVIE)
        val ids = TrackingExternalIds(imdb = "tt0111161")

        val found = jellyfin.lookupTitle(session, popular, ServerTitleQuery(ids, name = "The Shawshank Redemption", year = 1994))

        assertEquals(listOf(ServerIndexEntry("m1", ids, year = 1994)), found)
        val lookup = http.lookups.single()
        assertNull(lookup.url.queryParameter("parentId"))
        assertEquals("The Shawshank Redemption", lookup.url.queryParameter("searchTerm"))
        assertEquals(jellyfin.titleLookupScope(session, popular), jellyfin.titleLookupScope(session, trending))
        assertTrue(
            jellyfin.titleLookupScope(session, popular) !=
                jellyfin.titleLookupScope(session, ServerLibrary("lib3", "Shows", ServerMediaKind.SERIES))
        )
    }

    @Test
    fun treatsAioMetadataLikeAioStreams() = runTest {
        // AIOMetadata's /System/Info/Public: it names itself AIOMetadata and carries the "aiostreams" extension key.
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jellyfin/cfg/System/Info/Public" ->
                    """{"ServerName": "AIOMetadata", "Version": "12.1.0", "ProductName": "Jellyfin Server", "Id": "srv",
                        "aiostreams": {"features": {"watchlistFavorites": true}}}"""
                else -> """{"Items": []}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://meta.example.com/jellyfin/cfg")
        val movies = ServerLibrary("cat1", "Popular", ServerMediaKind.MOVIE)

        jellyfin.lookupTitle(session, movies, ServerTitleQuery(TrackingExternalIds(imdb = "tt0111161"), "Heat", 1995))

        assertTrue(jellyfin.servesCatalogs(session))
        // Its ids find nothing here, so the name search follows; neither is kept inside one catalog.
        val (byIds, byName) = http.lookups
        assertEquals("imdb.tt0111161", byIds.url.queryParameter("anyProviderIdEquals"))
        assertNull(byIds.url.queryParameter("searchTerm"))
        assertEquals("Heat", byName.url.queryParameter("searchTerm"))
        assertNull(byName.url.queryParameter("anyProviderIdEquals"))
        http.lookups.forEach { assertNull(it.url.queryParameter("parentId")) }
        assertEquals(
            jellyfin.titleLookupScope(session, movies),
            jellyfin.titleLookupScope(session, ServerLibrary("cat2", "Trending", ServerMediaKind.MOVIE))
        )
    }

    @Test
    fun findsAioMetadataTitlesByTheirIdsWithoutTheName() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jellyfin/cfg/System/Info/Public" -> """{"ServerName": "AIOMetadata", "Version": "12.1.0", "aiostreams": {}}"""
                else -> """{"Items": [{"Id": "m1", "ProductionYear": 1974,
                    "ProviderIds": {"Imdb": "tt0071562", "Tmdb": "240"}}], "TotalRecordCount": 1}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://meta.example.com/jellyfin/cfg")
        val ids = TrackingExternalIds(imdb = "tt0071562", tmdb = 240)

        // A name the server would not find; the ids still do.
        val found = jellyfin.lookupTitle(
            session,
            ServerLibrary("cat1", "Popular", ServerMediaKind.MOVIE),
            ServerTitleQuery(ids, name = "Der Pate – Teil II", year = 1974)
        )

        assertEquals(listOf("m1"), found?.map { it.itemId })
        val lookup = http.lookups.single()
        assertEquals("imdb.tt0071562,tmdb.240", lookup.url.queryParameter("anyProviderIdEquals"))
        assertEquals("Movie", lookup.url.queryParameter("includeItemTypes"))
        assertNull(lookup.url.queryParameter("searchTerm"))
    }

    @Test
    fun matchesAioMetadataAnimeByItsKitsuIdThroughTheNameSearch() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jellyfin/cfg/System/Info/Public" -> """{"ServerName": "AIOMetadata", "Version": "12.1.0", "aiostreams": {}}"""
                else -> """{"Items": [{"Id": "a1", "ProductionYear": 1998, "ProviderIds": {"Kitsu": "1", "MyAnimeList": "1"}}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val query = ServerTitleQuery(TrackingExternalIds(kitsu = 1), name = "Cowboy Bebop", year = 1998)

        val found = jellyfin.lookupTitle(
            session("jellyfin", "https://meta.example.com/jellyfin/cfg"),
            ServerLibrary("cat1", "Anime", ServerMediaKind.SERIES),
            query
        )!!

        // AIOMetadata's id filter takes only IMDb, TMDB and TVDB, so the name search finds it and Kitsu confirms it.
        assertEquals(listOf("a1"), verifiedTitleMatches(found, query))
        val lookup = http.lookups.single()
        assertNull(lookup.url.queryParameter("anyProviderIdEquals"))
        assertEquals("Cowboy Bebop", lookup.url.queryParameter("searchTerm"))
    }

    @Test
    fun searchesByNameWhenAioMetadatasIdLookupFails() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/System/Info/Public") ->
                    """{"ServerName": "AIOMetadata", "Version": "12.1.0", "aiostreams": {}}"""
                request.url.queryParameter("anyProviderIdEquals") != null -> "not json"
                else -> """{"Items": [{"Id": "m1", "ProductionYear": 1994, "ProviderIds": {"Imdb": "tt0111161"}}]}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val ids = TrackingExternalIds(imdb = "tt0111161")

        val found = jellyfin.lookupTitle(
            session("jellyfin", "https://meta.example.com/jellyfin/cfg"),
            ServerLibrary("cat1", "Popular", ServerMediaKind.MOVIE),
            ServerTitleQuery(ids, name = "The Shawshank Redemption", year = 1994)
        )

        assertEquals(listOf(ServerIndexEntry("m1", ids, year = 1994)), found)
        assertEquals("The Shawshank Redemption", http.lookups.last().url.queryParameter("searchTerm"))
    }

    @Test
    fun leavesIdLookupsToAioMetadata() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jellyfin/System/Info/Public" -> """{"ServerName": "Den", "Version": "10.10.7", "aiostreams": {}}"""
                else -> """{"Items": []}"""
            }
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)

        jellyfin.lookupTitle(
            session("jellyfin", "https://media.example.com/jellyfin"),
            ServerLibrary("lib1", "Popular", ServerMediaKind.MOVIE),
            ServerTitleQuery(TrackingExternalIds(imdb = "tt0111161"), name = "Heat", year = 1995)
        )

        assertTrue(http.lookups.none { it.url.queryParameter("anyProviderIdEquals") != null })
    }

    @Test
    fun keepsTitleLookupsInsideEachLibraryOnAPlainJellyfin() = runTest {
        val http = TestHttp { """{"ServerName": "Home", "Version": "10.10.7"}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com")

        val first = jellyfin.titleLookupScope(session, ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE))
        val second = jellyfin.titleLookupScope(session, ServerLibrary("lib2", "4K Movies", ServerMediaKind.MOVIE))

        assertEquals(listOf("lib1", "lib2"), listOf(first, second))
        assertFalse(jellyfin.servesCatalogs(session))
    }

    @Test
    fun offersEveryFormatUntilDirectPlayFails() = runTest {
        val http = TestHttp {
            """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true, "TranscodingUrl": "/videos/i1/master.m3u8"}]}"""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities()))
        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities(allowDirectPlay = false)))

        val (direct, fallback) = http.requests
        assertEquals("1000000000", direct.url.queryParameter("maxStreamingBitrate"))
        assertTrue(direct.text.contains("\"MaxStreamingBitrate\":1000000000"))
        assertTrue(direct.text.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        assertFalse(fallback.text.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        assertTrue(fallback.text.contains("\"AudioCodec\":\"aac,mp3,ac3,eac3,flac,opus,vorbis\""))
    }

    @Test
    fun transcodesToHevcWithDolbyAudioAndBurnsInPgs() = runTest {
        val http = TestHttp { """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "TranscodingUrl": "/videos/i1/master.m3u8"}]}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        jellyfin.preparePlayback(
            session("jellyfin", "https://media.example.com/jellyfin"),
            ServerPlaybackRequest(target, ServerPlayerCapabilities(allowDirectPlay = false), subtitleStreamIndex = 3)
        )

        val request = http.requests.single()
        assertEquals("3", request.url.queryParameter("subtitleStreamIndex"))
        assertTrue(request.text.contains("\"SubtitleStreamIndex\":3"))
        assertTrue(request.text.contains("\"VideoCodec\":\"hevc,h264\",\"AudioCodec\":\"ac3,eac3,aac,mp3\""))
        assertTrue(request.text.contains("\"MaxAudioChannels\":\"8\""))
        assertTrue(request.text.contains("{\"Format\":\"pgssub\",\"Method\":\"Encode\"}"))
    }

    @Test
    fun embyPreparesAndReportsPlaybackThroughApiRoot() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/PlaybackInfo")) {
                """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true}]}"""
            } else {
                ""
            }
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val session = session("emby", "https://media.example.com")
        val playback = emby.preparePlayback(
            session,
            ServerPlaybackRequest(
                target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1"),
                capabilities = ServerPlayerCapabilities(),
                audioStreamIndex = 2
            )
        )
        emby.report(session, playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, positionMs = 1_500, isPaused = false))

        val info = http.requests[0]
        assertEquals("/emby/Items/i1/PlaybackInfo", info.url.encodedPath)
        assertEquals("u1", info.url.queryParameter("userId"))
        assertEquals("ms1", info.url.queryParameter("mediaSourceId"))
        assertEquals("2", info.url.queryParameter("audioStreamIndex"))
        assertTrue(info.text.contains("\"AudioStreamIndex\":2"))
        assertTrue(info.text.contains("\"IsPlayback\":true"))
        assertTrue(info.text.contains("{\"Format\":\"subrip\",\"Method\":\"Embed\"}"))
        assertTrue(info.text.contains("{\"Format\":\"subrip\",\"Method\":\"External\"}"))
        assertTrue(playback.url.startsWith("https://media.example.com/emby/Videos/i1/stream?static=true"))
        val report = http.requests[1]
        assertEquals("/emby/Sessions/Playing/Progress", report.url.encodedPath)
        assertTrue(report.text.contains("\"PositionTicks\":15000000"))
        assertTrue(report.text.contains("\"EventName\":\"TimeUpdate\""))
        assertTrue(report.text.contains("\"PlaySessionId\":\"ps1\""))
    }
}

// Title lookups first ask the server whether it is AIOStreams; these are the lookups themselves.
private val TestHttp.lookups
    get() = requests.filter { it.url.encodedPath.endsWith("/Items") }
