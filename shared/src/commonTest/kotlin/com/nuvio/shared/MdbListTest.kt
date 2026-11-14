package com.nuvio.shared

import com.nuvio.shared.tracking.MdbListService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MdbListTest {
    @Test
    fun ratingsMapToIconsInAndroidOrder() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("keyword", request.url.parameters["append_to_response"])
            respond(
                """{"keywords":[{"name":"certified-fresh"}],"ratings":[
                    {"source":"metacritic","value":81},{"source":"tomatoes","value":93},
                    {"source":"popcorn","value":55},{"source":"letterboxd","value":3.9,"score":78},
                    {"source":"imdb","value":7.6},{"source":"tmdb","value":76},{"source":"rogerebert","value":3.5},
                    {"source":"trakt","value":0}]}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json")
            )
        })
        val ratings = MdbListService(http).ratings("key", "tt1", isShow = false)
        assertEquals(listOf("imdb", "tmdb", "tomatoes", "audience", "letterboxd", "metacritic"), ratings.map { it.source })
        assertEquals(
            listOf("rating_imdb", "rating_tmdb", "rating_tomatoes_certified", "rating_audience_stale", "rating_letterboxd", "rating_metacritic"),
            ratings.map { it.icon }
        )
        assertEquals(listOf("7.6", "76%", "93%", "55%", "3.9", "81"), ratings.map { it.value })
    }
}
