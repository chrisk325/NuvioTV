package com.nuvio.shared

import com.nuvio.shared.mediaserver.MediaServerService
import com.nuvio.shared.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaServerMetaTest {
    @Test
    fun seriesDetailsFromJellyfin() = runTest {
        val json = headersOf("Content-Type", "application/json")
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/Users/u1/Items" -> respond(
                    """{"Items":[{"Id":"s1","Name":"Show","ProviderIds":{"Imdb":"tt100"}}]}""", HttpStatusCode.OK, json
                )
                "/Users/u1/Items/s1" -> respond(
                    """{"Id":"s1","Name":"Show","Overview":"Plot","ProductionYear":2024,"CommunityRating":8.25,
                        "Genres":["Drama"],"OfficialRating":"TV-MA","BackdropImageTags":["b"],"ImageTags":{"Logo":"l"},
                        "People":[{"Name":"Ann","Role":"Lead","Type":"Actor","Id":"p1","PrimaryImageTag":"x"},
                                  {"Name":"Dee","Type":"Director","Id":"p2"}]}""",
                    HttpStatusCode.OK, json
                )
                "/Shows/s1/Episodes" -> respond(
                    """{"Items":[{"Id":"e1","Name":"Pilot","ParentIndexNumber":1,"IndexNumber":1,"RunTimeTicks":27000000000,"Overview":"First"},
                                 {"Id":"e2","Name":"Two","ParentIndexNumber":1,"IndexNumber":2}]}""",
                    HttpStatusCode.OK, json
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore().apply {
            putString("mediaservers.v1", """[{"id":"srv","kind":"jellyfin","name":"Home","address":"http://jf","userId":"u1","token":"tok"}]""")
        }
        val meta = assertNotNull(MediaServerService(http, store).meta("series", "tt100"))
        assertEquals("Show", meta.name)
        assertEquals("8.2", meta.imdbRating)
        assertEquals(listOf("Dee"), meta.director)
        assertEquals("Lead", meta.castMembers.single().character)
        assertTrue(meta.logo!!.contains("/Items/s1/Images/Logo"))
        assertEquals(listOf("tt100:1:1", "tt100:1:2"), meta.videos.map { it.id })
        assertEquals(45, meta.videos.first().runtimeMinutes)
    }
}
