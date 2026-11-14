package com.nuvio.shared

import com.nuvio.shared.storage.InMemoryStore
import com.nuvio.shared.tracking.SimklService
import com.nuvio.shared.tracking.StoredToken
import com.nuvio.shared.tracking.TraktService
import com.nuvio.shared.tracking.writeToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerWatchedTest {
    private val json = headersOf("Content-Type", "application/json")

    @Test
    fun traktShowIsFullyWatchedWhenWatchedEpisodesReachAired() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/sync/watched/movies" -> respond("""[{"movie":{"ids":{"imdb":"tt0111161"}}}]""", HttpStatusCode.OK, json)
                "/sync/watched/shows" -> {
                    assertEquals("full", request.url.parameters["extended"])
                    respond(
                        """[
                          {"show":{"aired_episodes":3,"ids":{"imdb":"tt1","tmdb":11}},
                           "seasons":[{"number":0,"episodes":[{"number":1}]},
                                      {"number":1,"episodes":[{"number":1},{"number":2}]},
                                      {"number":2,"episodes":[{"number":1}]}]},
                          {"show":{"aired_episodes":10,"ids":{"imdb":"tt2"}},
                           "seasons":[{"number":1,"episodes":[{"number":1}]}]}
                        ]""",
                        HttpStatusCode.OK, json
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply { writeToken("trakt.token", StoredToken("tok")) }
        val state = TraktService(http, store, "id", "secret").watchedState()
        assertTrue("movie:tt0111161" in state.keys)
        assertTrue("tt1:1:2" in state.keys)
        assertEquals(setOf("series:tt1", "series:tmdb:11"), state.completedSeries)
    }

    @Test
    fun simklCompletedListsBecomeWatchedKeys() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/sync/all-items/movies/completed" ->
                    respond("""{"movies":[{"movie":{"ids":{"imdb":"tt0068646","tmdb":238}}}]}""", HttpStatusCode.OK, json)
                "/sync/all-items/shows/completed" ->
                    respond("""{"shows":[{"show":{"ids":{"imdb":"tt0903747","tmdb":"1396"}}}]}""", HttpStatusCode.OK, json)
                "/sync/all-items/anime/completed" ->
                    respond("""{"anime":[{"show":{"ids":{"imdb":"tt2560140","mal":"16498","kitsu":7442}}}]}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply { writeToken("simkl.token.v2", StoredToken("tok")) }
        val state = SimklService(http, store, "cid").completedItems()
        assertTrue("movie:tt0068646" in state.keys)
        assertTrue("movie:tmdb:238" in state.keys)
        assertTrue("series:tt0903747" in state.completedSeries)
        assertTrue("series:tmdb:1396" in state.completedSeries)
        assertTrue("series:kitsu:7442" in state.completedSeries)
        assertTrue("series:mal:16498" in state.completedSeries)
        // Anime is per season on Simkl: never mark the whole imdb show from it.
        assertFalse("series:tt2560140" in state.completedSeries)
    }
}
