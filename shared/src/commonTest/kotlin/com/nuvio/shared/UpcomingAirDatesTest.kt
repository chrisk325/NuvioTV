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
import kotlin.test.assertTrue

/** The Upcoming row's air dates: a failed episode-list lookup is tried again, not given up on. */
class UpcomingAirDatesTest {
    private val upNextJson = """[{"contentId":"tt9","contentType":"series","videoId":"tt9:1:2","name":"Show",
        "season":1,"episode":2,"positionMs":0,"durationMs":0,"updatedAtMs":1000}]"""

    @Test
    fun failedLookupIsRetried() = runTest {
        var metaCalls = 0
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            when {
                url.startsWith("${Fixtures.CINEMETA}/manifest.json") ->
                    respond(Fixtures.cinemetaManifest, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                url.startsWith("${Fixtures.CINEMETA}/meta/series/tt9.json") -> {
                    metaCalls++
                    // The add-on is down for the first lookup.
                    if (metaCalls == 1) respond("busy", HttpStatusCode.ServiceUnavailable)
                    else respond("""{"meta":{"id":"tt9","type":"series","name":"Show","videos":[
                        {"id":"tt9:1:1","season":1,"episode":1,"released":"2020-01-01T00:00:00.000Z"},
                        {"id":"tt9:1:2","season":1,"episode":2,"released":"2099-01-08T00:00:00.000Z"}]}}""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply { putString("upnext.simkl", upNextJson) }
        val core = NuvioCore(store, client, StandardTestDispatcher(testScheduler))
        core.refreshAddons()
        assertTrue(core.continueWatching().none { core.isUpcoming(it) })

        core.refreshAirDates()

        val upcoming = core.continueWatching().filter { core.isUpcoming(it) }
        assertEquals(listOf("tt9:1:2"), upcoming.map { it.videoId })
        assertTrue(metaCalls >= 2)
        core.close()
    }
}
