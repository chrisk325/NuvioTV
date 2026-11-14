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

class SimklContinueWatchingTest {
    private val json = headersOf("Content-Type", "application/json")

    @Test
    fun playbackAndUpNextIncludeShowsAndAnime() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/sync/playback" -> respond(
                    """[
                      {"id":1,"progress":40,"paused_at":"2026-11-10T20:00:00Z","type":"episode",
                       "episode":{"season":2,"number":3,"title":"Ep"},"show":{"title":"Show","ids":{"imdb":"tt100"}}},
                      {"id":2,"progress":50,"paused_at":"2026-11-11T20:00:00Z","type":"episode",
                       "episode":{"number":7},"anime":{"title":"Anime","ids":{"kitsu":"42"}}},
                      {"id":3,"progress":10,"paused_at":"2026-11-12T20:00:00Z","type":"movie",
                       "movie":{"title":"Film","ids":{"imdb":"tt200"}}}
                    ]""", HttpStatusCode.OK, json
                )
                "/sync/all-items/shows/watching" -> {
                    assertEquals("full", request.url.parameters["extended"])
                    respond(
                        """{"shows":[
                          {"show":{"title":"Next Show","ids":{"imdb":"tt300"}},"next_to_watch":"S01E05",
                           "last_watched_at":"2026-11-09T10:00:00Z","total_episodes_count":10,"watched_episodes_count":4,"not_aired_episodes_count":0},
                          {"show":{"title":"Caught Up","ids":{"imdb":"tt400"}},"next_to_watch":"S02E01",
                           "total_episodes_count":10,"watched_episodes_count":8,"not_aired_episodes_count":2}
                        ]}""", HttpStatusCode.OK, json
                    )
                }
                "/sync/all-items/anime/watching" -> respond(
                    """{"anime":[{"show":{"title":"Anime Next","ids":{"kitsu":"77"}},"next_to_watch":"E12",
                        "total_episodes_count":24,"watched_episodes_count":11}]}""", HttpStatusCode.OK, json
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply { writeToken("simkl.token.v2", StoredToken("tok")) }
        val simkl = SimklService(http, store, "cid")

        val playback = simkl.playbackProgress()
        assertEquals(listOf("tt100:2:3", "kitsu:42:7", "tt200"), playback.map { it.videoId })
        assertEquals(0.4, playback[0].fraction, 0.001)
        assertEquals("series", playback[1].contentType)

        val upNext = simkl.upNext()
        assertEquals(listOf("tt300:1:5", "kitsu:77:12"), upNext.map { it.videoId })
        assertTrue(upNext.all { it.durationMs == 0L })
    }
}

class IntroDbTest {
    private fun parse(json: String) = com.nuvio.shared.data.AddonJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject

    @Test
    fun episodesGetIntroRecapOutro() {
        val segments = com.nuvio.shared.tracking.introDbIntervals(parse(
            """{"intro":{"start_sec":60.5,"end_sec":90},"recap":{"start_ms":0,"end_ms":30000},"outro":{"start_sec":1200,"end_sec":1260}}"""
        ), isMovie = false)
        assertEquals(listOf("intro", "recap", "outro"), segments.map { it.type })
        assertEquals(30.0, segments[1].endSeconds)
    }

    @Test
    fun movieCreditsStopAtPostCreditsScene() {
        val segments = com.nuvio.shared.tracking.introDbIntervals(parse(
            """{"intro":{"start_sec":0,"end_sec":60},"outro":{"start_sec":6000,"end_sec":6300},"post_credits":{"start_sec":6200,"end_sec":6280}}"""
        ), isMovie = true)
        assertEquals(1, segments.size)
        assertEquals("movie-credits", segments[0].type)
        assertEquals(6200.0, segments[0].endSeconds)
        assertEquals("Skip to post-credits scene", segments[0].label)
    }

    @Test
    fun introDbAndAniSkipMergePerCategory() {
        val introDb = listOf(com.nuvio.shared.tracking.SkipSegment("intro", 60.0, 90.0))
        val aniSkip = listOf(
            com.nuvio.shared.tracking.SkipSegment("intro", 55.0, 145.0),
            com.nuvio.shared.tracking.SkipSegment("outro", 1300.0, 1390.0)
        )
        val merged = com.nuvio.shared.tracking.mergeByCategory(introDb, aniSkip)
        assertEquals(listOf("intro" to 60.0, "outro" to 1300.0), merged.map { it.type to it.startSeconds })
    }
}
