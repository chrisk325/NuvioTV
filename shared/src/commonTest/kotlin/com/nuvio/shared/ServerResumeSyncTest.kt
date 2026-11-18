package com.nuvio.shared

import com.nuvio.shared.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerResumeSyncTest {
    private val json = headersOf("Content-Type", "application/json")

    private fun movie(id: String, imdb: String, played: String) =
        """{"Id":"$id","Type":"Movie","Name":"$imdb","RunTimeTicks":60000000000,"ProviderIds":{"Imdb":"$imdb"},
            "UserData":{"PlaybackPositionTicks":12000000000,"LastPlayedDate":"$played"}}"""

    @Test
    fun onlyServerChangesReachContinueWatching() = runTest {
        var resume = listOf(movie("m1", "tt1", "2026-11-01T10:00:00Z"), movie("m2", "tt2", "2026-11-02T10:00:00Z"))
        var serverDown = false
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                serverDown -> respond("", HttpStatusCode.ServiceUnavailable)
                path == "/Users/u1/Items/Resume" -> respond("""{"Items":[${resume.joinToString(",")}]}""", HttpStatusCode.OK, json)
                path.startsWith("http://jf") || path.startsWith("/") -> respond("""{"Items":[]}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply {
            putString("mediaservers.v1", """[{"id":"srv","kind":"jellyfin","name":"JF","address":"http://jf","userId":"u1","token":"tok"}]""")
        }
        val core = NuvioCore(store, http, StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<List<String>>()
        core.observeContinueWatching { list -> seen += list.map { it.videoId } }
        testScheduler.advanceUntilIdle()

        core.syncTrackers(); testScheduler.advanceUntilIdle()
        assertEquals(listOf("tt2", "tt1"), core.continueWatching().map { it.videoId })
        val afterFirst = seen.size

        // Same list again: no redraw.
        core.syncTrackers(); testScheduler.advanceUntilIdle()
        assertEquals(afterFirst, seen.size)

        // Server unreachable: nothing removed.
        serverDown = true
        core.syncTrackers(); testScheduler.advanceUntilIdle()
        assertEquals(listOf("tt2", "tt1"), core.continueWatching().map { it.videoId })
        serverDown = false

        // tt1 finished on the server, tt3 started: one change.
        resume = listOf(movie("m2", "tt2", "2026-11-02T10:00:00Z"), movie("m3", "tt3", "2026-11-03T10:00:00Z"))
        core.syncTrackers(); testScheduler.advanceUntilIdle()
        assertEquals(listOf("tt3", "tt2"), core.continueWatching().map { it.videoId })
        assertEquals(afterFirst + 1, seen.size)
        core.close()
    }
}
