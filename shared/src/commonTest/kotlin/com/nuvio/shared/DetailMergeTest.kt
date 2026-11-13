package com.nuvio.shared

import com.nuvio.shared.data.parseMetaResponse
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
import kotlin.test.assertNull

class DetailMergeTest {
    private val meta = parseMetaResponse("""
        {"meta":{"id":"tt1","type":"movie","name":"Addon Name","logo":"https://addon/logo.png","background":"https://addon/bg.jpg",
          "description":"Addon description","cast":["Plain Name"],
          "app_extras":{"cast":[{"name":"Actor A","character":"Hero","photo":"https://addon/a.jpg","tmdbId":5}]}}}
    """.trimIndent())!!

    private val tmdb = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("/find/tt1") -> """{"movie_results":[{"id":42}]}"""
            path.endsWith("/movie/42") -> """{"id":42,"title":"TMDB Title","overview":"TMDB overview","backdrop_path":"/b.jpg",
                "genres":[{"name":"Drama"}],"vote_average":7.84,"runtime":120,
                "images":{"logos":[{"file_path":"/l.png","iso_639_1":"en"}]},
                "credits":{"cast":[{"id":9,"name":"TMDB Actor","character":"Lead","profile_path":"/p.jpg"}],"crew":[]}}"""
            else -> return@MockEngine respond("", HttpStatusCode.NotFound)
        }
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    })

    private fun core(scope: kotlinx.coroutines.test.TestScope) = NuvioCore(
        InMemoryStore(), tmdb, StandardTestDispatcher(scope.testScheduler),
        NuvioConfig.EMPTY.copy(tmdbApiKey = "key")
    )

    @Test
    fun enrichmentOffKeepsAddonMetadataAndCastPhotos() = runTest {
        val details = core(this).details(meta)
        assertEquals("Addon Name", details.meta.name)
        assertEquals("https://addon/logo.png", details.meta.logo)
        assertEquals("Addon description", details.meta.description)
        assertEquals(listOf("Actor A"), details.people.map { it.name })
        assertEquals("https://addon/a.jpg", details.people.single().photo)
        assertEquals(5, details.people.single().id)
        assertNull(details.tmdbRating)
    }

    @Test
    fun groupsApplyIndividually() = runTest {
        val core = core(this)
        core.settings.tmdbEnrichment = true
        core.settings.tmdbUseArtwork = false
        core.settings.tmdbUseCredits = false
        val details = core.details(meta)
        // Artwork and credits off: add-on logo/background and add-on cast stay.
        assertEquals("https://addon/logo.png", details.meta.logo)
        assertEquals("https://addon/bg.jpg", details.meta.background)
        assertEquals(listOf("Actor A"), details.people.map { it.name })
        // Basic info and details on: TMDB wins.
        assertEquals("TMDB Title", details.meta.name)
        assertEquals("TMDB overview", details.meta.description)
        assertEquals(listOf("Drama"), details.meta.genres)
        assertEquals("120 min", details.meta.runtime)
        assertEquals("7.8", details.tmdbRating)

        core.settings.tmdbUseArtwork = true
        core.settings.tmdbUseCredits = true
        val all = core.details(meta)
        assertEquals("https://image.tmdb.org/t/p/w500/l.png", all.meta.logo)
        assertEquals(listOf("TMDB Actor"), all.people.map { it.name })
    }
}
