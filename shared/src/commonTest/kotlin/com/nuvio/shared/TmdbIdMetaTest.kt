package com.nuvio.shared

import com.nuvio.shared.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** "tmdb:" ids (More Like This, Collection, actor pages) open through add-ons, else TMDB itself. */
class TmdbIdMetaTest {
    private fun client(cinemetaHasTitle: Boolean) = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        val path = request.url.encodedPath
        val body = when {
            url.startsWith("${Fixtures.CINEMETA}/manifest.json") -> Fixtures.cinemetaManifest
            path.endsWith("/movie/42/external_ids") -> """{"imdb_id":"tt42"}"""
            path.endsWith("/tv/7/external_ids") -> """{"imdb_id":"tt7"}"""
            cinemetaHasTitle && url.startsWith("${Fixtures.CINEMETA}/meta/movie/tt42.json") ->
                """{"meta":{"id":"tt42","type":"movie","name":"Found"}}"""
            path.endsWith("/movie/42") -> """{"id":42,"title":"From TMDB","overview":"o","poster_path":"/p.jpg",
                "release_date":"2026-01-02","runtime":95,"external_ids":{"imdb_id":"tt42"}}"""
            path.endsWith("/tv/7") -> """{"id":7,"name":"Show","seasons":[{"season_number":0},{"season_number":1}],
                "external_ids":{"imdb_id":"tt7"}}"""
            path.endsWith("/tv/7/season/1") -> """{"episodes":[{"episode_number":1,"name":"Pilot"},{"episode_number":2,"name":"Two"}]}"""
            else -> return@MockEngine respond("not found", HttpStatusCode.NotFound)
        }
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    })

    private suspend fun core(scope: TestScope, cinemetaHasTitle: Boolean) =
        NuvioCore(InMemoryStore(), client(cinemetaHasTitle), StandardTestDispatcher(scope.testScheduler),
            NuvioConfig.EMPTY.copy(tmdbApiKey = "key")).also { it.refreshAddons() }

    @Test
    fun tmdbIdOpensViaImdb() = runTest {
        val core = core(this, cinemetaHasTitle = true)
        assertEquals("Found", core.meta("movie", "tmdb:42", null).name)
        core.close()
    }

    @Test
    fun tmdbIdFallsBackToTmdbDetails() = runTest {
        val core = core(this, cinemetaHasTitle = false)
        val movie = core.meta("movie", "tmdb:42", null)
        assertEquals("From TMDB", movie.name)
        assertEquals("tt42", movie.id)
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", movie.poster)
        val show = core.meta("series", "tmdb:7", null)
        assertEquals(listOf("tt7:1:1", "tt7:1:2"), show.videos.map { it.id })
        core.close()
    }
}
