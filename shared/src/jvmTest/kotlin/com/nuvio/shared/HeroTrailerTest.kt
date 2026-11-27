package com.nuvio.shared

import com.nuvio.shared.model.ContentType
import com.nuvio.shared.model.MetaPreview
import com.nuvio.shared.model.PosterShape
import com.nuvio.shared.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Home hero's trailer reuses the Details page's lookup (and the other way round). Runs on the
 * real clock: trailer lookups have time limits, which a test clock would expire at once while
 * the mock network answers on another thread.
 */
class HeroTrailerTest {
    private val json = headersOf("Content-Type", "application/json")

    private fun preview(id: String) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = "Film", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )

    @Test
    fun oneLookupPerTitleSharedByHeroAndDetails() = runBlocking {
        var trailerCalls = 0
        var metaCalls = 0
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            when {
                url.startsWith("${Fixtures.CINEMETA}/manifest.json") -> respond(Fixtures.cinemetaManifest, HttpStatusCode.OK, json)
                url.startsWith("${Fixtures.CINEMETA}/meta/movie/tt1.json") -> {
                    metaCalls++
                    respond("""{"meta":{"id":"tt1","type":"movie","name":"Film","trailers":[{"source":"yt1","type":"Trailer"}]}}""", HttpStatusCode.OK, json)
                }
                url.startsWith("${Fixtures.CINEMETA}/meta/movie/tt2.json") ->
                    respond("""{"meta":{"id":"tt2","type":"movie","name":"No trailer"}}""", HttpStatusCode.OK, json)
                url.startsWith("https://trailers.test/trailer") -> {
                    trailerCalls++
                    respond("""{"url":"https://video.test/yt1.mp4"}""", HttpStatusCode.OK, json)
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })
        val core = NuvioCore(InMemoryStore(), client, Dispatchers.Default,
            NuvioConfig.EMPTY.copy(trailerApiUrl = "https://trailers.test"))
        core.refreshAddons()

        // Hero first: one trailer lookup; asking again (or from Details) reuses it.
        assertEquals("https://video.test/yt1.mp4", core.heroTrailerUrl(preview("tt1")))
        assertEquals("https://video.test/yt1.mp4", core.heroTrailerUrl(preview("tt1")))
        val meta = core.meta("movie", "tt1", null)
        assertEquals("https://video.test/yt1.mp4", core.trailerUrl(meta, emptyList()))
        assertEquals(1, trailerCalls)

        // A title without a trailer: nothing played, and not looked up again right away.
        assertNull(core.heroTrailerUrl(preview("tt2")))
        assertNull(core.heroTrailerUrl(preview("tt2")))
        assertEquals(1, trailerCalls)
        core.close()
    }

    @Test
    fun noTrailerServiceNoTrailer() = runBlocking {
        val core = NuvioCore(InMemoryStore(), HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
            Dispatchers.Default)
        assertNull(core.heroTrailerUrl(preview("tt1")))
        core.close()
    }
}
