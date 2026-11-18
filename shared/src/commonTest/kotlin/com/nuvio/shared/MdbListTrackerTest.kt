package com.nuvio.shared

import com.nuvio.shared.storage.InMemoryStore
import com.nuvio.shared.tracking.MdbListTracker
import com.nuvio.shared.tracking.ScrobbleAction
import com.nuvio.shared.tracking.TrackedMedia
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdbListTrackerTest {
    private val posted = mutableListOf<Pair<String, String>>()
    private var approved = false

    private val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val body = (request.body as? TextContent)?.text.orEmpty()
        if (request.method.value == "POST") posted += path to body
        val json = when (path) {
            "/oauth/device-authorization/" -> """{"device_code":"dev","user_code":"ABCD","verification_uri":"https://mdblist.com/device","expires_in":600,"interval":5}"""
            "/oauth/token/" -> if (approved) """{"access_token":"tok","refresh_token":"ref","token_type":"Bearer","expires_in":3600,"scope":"write"}"""
                else return@MockEngine respond("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))
            "/user" -> """{"user_id":7,"username":"me"}"""
            "/sync/watched" -> """{"movies":[{"movie":{"ids":{"imdb":"tt1"}},"last_watched_at":"2026-01-01T00:00:00Z"}],
                "shows":[{"show":{"ids":{"imdb":"tt2"}},"seasons":[{"number":1,"episodes":[{"number":1},{"number":2}]}]}],
                "episodes":[{"episode":{"season":3,"number":4},"show":{"ids":{"tmdb":99}},"watched_at":"2026-01-01T00:00:00Z"}],
                "pagination":{"has_more":false}}"""
            "/sync/playback" -> """[{"id":1,"type":"movie","progress":"50.0","paused_at":"2026-02-01T00:00:00Z","runtime":100,"movie":{"title":"M","ids":{"imdb":"tt5"}}},
                {"id":2,"type":"episode","progress":"25","paused_at":"2026-02-01T00:00:00Z","episode":{"season":1,"number":3,"title":"E3"},"show":{"title":"S","ids":{"imdb":"tt6"}}}]"""
            "/watchlist/items" -> """{"items":[{"mediatype":"movie","id":42,"imdb_id":"tt42","title":"W","release_year":2026,"poster":"/p.jpg"},
                {"mediatype":"show","id":43,"title":"NoImdb"}]}"""
            else -> "{}"
        }
        respond(json, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    })

    @Test
    fun deviceSignInAndSync() = runTest {
        val tracker = MdbListTracker(client, InMemoryStore(), "cid")
        val signIn = tracker.startSignIn()
        assertEquals("ABCD", signIn.userCode)
        assertEquals("https://mdblist.com/device?user_code=ABCD", signIn.verificationUrl)
        assertFalse(tracker.pollSignIn(signIn))
        approved = true
        assertTrue(tracker.pollSignIn(signIn))
        assertTrue(tracker.isConnected)
        assertEquals("me", tracker.username)

        assertEquals(listOf("movie:tt1", "tt2:1:1", "tt2:1:2", "tmdb:99:3:4"), tracker.watchedKeys())

        val progress = tracker.playbackProgress()
        assertEquals(listOf("tt5", "tt6:1:3"), progress.map { it.videoId })
        assertEquals(3_000_000L, progress.first().positionMs)

        val watchlist = tracker.watchlist()
        assertEquals(listOf("tt42", "tmdb:43"), watchlist.map { it.id })
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", watchlist.first().poster)

        tracker.scrobble(TrackedMedia(true, "tt6", null, null, "S", null, 1, 3), ScrobbleAction.PAUSE, 25.555)
        val scrobble = posted.last { it.first == "/scrobble/pause" }.second
        assertEquals("""{"show":{"ids":{"imdb":"tt6"},"season":1,"episode":3},"progress":25.55}""", scrobble)

        tracker.markWatched(TrackedMedia(false, "tt1", null, null, "M", null, null, null))
        assertEquals("""{"movies":[{"ids":{"imdb":"tt1"}}]}""", posted.last { it.first == "/sync/watched" }.second)
    }
}
