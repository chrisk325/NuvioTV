package com.nuvio.shared

import com.nuvio.shared.data.parseMetaResponse
import com.nuvio.shared.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextUpLocalTest {
    private val meta = parseMetaResponse("""
        {"meta":{"id":"tt9","type":"series","name":"Show","videos":[
          {"id":"tt9:1:1","title":"One","season":1,"episode":1,"released":"2020-01-01"},
          {"id":"tt9:1:2","title":"Two","season":1,"episode":2,"released":"2020-01-08"},
          {"id":"tt9:1:3","title":"Three","season":1,"episode":3,"released":"2020-01-15"}]}}
    """.trimIndent())!!

    private fun core(scope: kotlinx.coroutines.test.TestScope) = NuvioCore(
        InMemoryStore(), HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }), StandardTestDispatcher(scope.testScheduler)
    )

    @Test
    fun finishedEpisodeBecomesNextUp() = runTest {
        val core = core(this)
        val e1 = meta.videos[0]
        core.saveProgress(meta, e1.id, 1, 1, "One", positionMs = 2_700_000, durationMs = 3_000_000) // 90 %
        val cw = core.continueWatching()
        assertEquals(listOf("tt9:1:2"), cw.map { it.videoId })
        assertTrue(core.isUpNext(cw.single()))
        assertTrue(core.latestProgressFor("tt9")!!.isCompleted)
        core.close()
    }

    @Test
    fun watchedAfterMostOfItPlayedIsNotResumable() = runTest {
        val core = core(this)
        val e1 = meta.videos[0]
        core.saveProgress(meta, e1.id, 1, 1, "One", positionMs = 2_550_000, durationMs = 3_000_000) // 85 %
        assertEquals(listOf("tt9:1:1"), core.continueWatching().map { it.videoId })
        // A tracker (Trakt counts 80 %) or the episode card marks it watched.
        core.setWatched(meta, e1, true)
        assertEquals(listOf("tt9:1:2"), core.continueWatching().map { it.videoId })
        assertFalse(core.progressFor("tt9:1:1")?.isResumable == true)
        core.close()
    }
}
