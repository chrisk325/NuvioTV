package com.nuvio.shared

import com.nuvio.shared.data.parseMetaResponse
import com.nuvio.shared.model.Video
import com.nuvio.shared.storage.InMemoryStore
import com.nuvio.shared.util.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Upcoming row: unaired next episodes of shows that are caught up (as on Android). */
class UpcomingEpisodesTest {
    // 2026-11-25T00:00:00Z
    private val now = 1_795_564_800_000L

    private fun ep(season: Int, episode: Int, released: String?, available: Boolean? = null) = Video(
        id = "tt1:$season:$episode", title = "E$episode", released = released, thumbnail = null,
        season = season, episode = episode, overview = null, available = available
    )

    @Test
    fun nextEpisodeOfTheSeasonAiringLaterIsUpcoming() {
        val videos = listOf(ep(1, 1, "2026-11-11"), ep(1, 2, "2026-11-18"), ep(1, 3, "2027-01-20"))
        val (video, released) = upcomingEpisodeAfter(videos, 1, 2, null, inclusive = false, nowMs = now)!!
        assertEquals("tt1:1:3", video.id)
        assertEquals(com.nuvio.shared.util.parseReleaseEpochMs("2027-01-20"), released)
    }

    @Test
    fun airedNextEpisodeIsNextUpNotUpcoming() {
        val videos = listOf(ep(1, 1, "2026-11-11"), ep(1, 2, "2026-11-18"))
        assertNull(upcomingEpisodeAfter(videos, 1, 1, null, inclusive = false, nowMs = now))
    }

    @Test
    fun nextSeasonOnlyWithinSevenDays() {
        val soon = listOf(ep(1, 1, "2026-01-01"), ep(2, 1, "2026-12-01"))
        assertEquals("tt1:2:1", upcomingEpisodeAfter(soon, 1, 1, null, false, now)?.first?.id)
        val later = listOf(ep(1, 1, "2026-01-01"), ep(2, 1, "2026-12-20"))
        assertNull(upcomingEpisodeAfter(later, 1, 1, null, false, now))
        // Undated premiere: skipped.
        val undated = listOf(ep(1, 1, "2026-01-01"), ep(2, 1, null))
        assertNull(upcomingEpisodeAfter(undated, 1, 1, null, false, now))
    }

    @Test
    fun undatedOrMissingNextEpisodeIsNotUpcoming() {
        assertNull(upcomingEpisodeAfter(listOf(ep(1, 1, "2026-01-01"), ep(1, 2, null)), 1, 1, null, false, now))
        assertNull(upcomingEpisodeAfter(listOf(ep(1, 1, "2026-01-01")), 1, 1, null, false, now))
        // Seed not in the list at all.
        assertNull(upcomingEpisodeAfter(listOf(ep(1, 1, "2026-01-01"), ep(1, 2, "2027-01-01")), 3, 9, null, false, now))
    }

    @Test
    fun specialsAreLeftOutAndNamedNextEpisodeCounts() {
        val videos = listOf(ep(0, 1, "2026-12-01"), ep(1, 1, "2026-01-01"), ep(1, 2, "2027-02-01"))
        assertEquals("tt1:1:2", upcomingEpisodeAfter(videos, 1, 1, null, false, now)?.first?.id)
        // A tracker's next episode (inclusive): that episode itself.
        assertEquals("tt1:1:2", upcomingEpisodeAfter(videos, 1, 2, null, true, now)?.first?.id)
        // Found by its id when the numbers don't match.
        assertEquals("tt1:1:2", upcomingEpisodeAfter(videos, 5, 5, "tt1:1:1", false, now)?.first?.id)
    }

    private val meta = parseMetaResponse("""
        {"meta":{"id":"tt9","type":"series","name":"Show","poster":"p.jpg","videos":[
          {"id":"tt9:1:1","title":"One","season":1,"episode":1,"released":"2020-01-01T00:00:00.000Z"},
          {"id":"tt9:1:2","title":"Two","season":1,"episode":2,"released":"2020-01-08T00:00:00.000Z"},
          {"id":"tt9:1:3","title":"Three","season":1,"episode":3,"released":"2099-01-15T00:00:00.000Z"}]}}
    """.trimIndent())!!

    private fun client() = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        when {
            url.startsWith("${Fixtures.CINEMETA}/manifest.json") ->
                respond(Fixtures.cinemetaManifest, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            url.startsWith("${Fixtures.CINEMETA}/meta/series/tt9.json") ->
                respond("""{"meta":{"id":"tt9","type":"series","name":"Show","videos":[
                    {"id":"tt9:1:1","title":"One","season":1,"episode":1,"released":"2020-01-01T00:00:00.000Z"},
                    {"id":"tt9:1:2","title":"Two","season":1,"episode":2,"released":"2020-01-08T00:00:00.000Z"},
                    {"id":"tt9:1:3","title":"Three","season":1,"episode":3,"released":"2099-01-15T00:00:00.000Z"}]}}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    })

    @Test
    fun finishingTheLatestEpisodePutsTheNextOneInUpcoming() = runTest {
        val core = NuvioCore(InMemoryStore(), client(), StandardTestDispatcher(testScheduler))
        // Episode 2 is the last one aired; watched to 90 %.
        core.saveProgress(meta, "tt9:1:2", 1, 2, "Two", positionMs = 2_700_000, durationMs = 3_000_000)
        val upcoming = core.continueWatching().filter { core.isUpcoming(it) }
        assertEquals(listOf("tt9:1:3"), upcoming.map { it.videoId })
        assertEquals("Three", upcoming.single().episodeTitle)
        core.close()
    }

    @Test
    fun syncedFinishedEpisodeIsLookedUp() = runTest {
        // A finished episode from elsewhere (no up next entry yet): found on refresh.
        val store = InMemoryStore()
        val core = NuvioCore(store, client(), StandardTestDispatcher(testScheduler))
        core.refreshAddons()
        core.watchProgress.update(
            com.nuvio.shared.model.WatchProgress(
                contentId = "tt9", contentType = "series", videoId = "tt9:1:2", name = "Show",
                season = 1, episode = 2, positionMs = 2_900_000, durationMs = 3_000_000,
                updatedAtMs = currentTimeMillis() - 86_400_000L
            )
        )
        assertTrue(core.continueWatching().none { core.isUpcoming(it) })
        core.refreshUpcoming()
        assertEquals(listOf("tt9:1:3"), core.continueWatching().filter { core.isUpcoming(it) }.map { it.videoId })
        core.close()
    }
}
