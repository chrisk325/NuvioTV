package com.nuvio.shared

import com.nuvio.shared.cloud.NuvioCloud
import com.nuvio.shared.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudSignInTest {
    @Test
    fun emailSignInIsVisibleThroughTheAccountStore() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/auth/v1/token", request.url.encodedPath)
            assertEquals("password", request.url.parameters["grant_type"])
            respond(
                """{"access_token":"at","refresh_token":"rt","expires_in":3600,"user":{"email":"me@example.com"}}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json")
            )
        })
        // Profile storage and the device-wide account storage are different stores.
        val cloud = NuvioCloud(http, InMemoryStore(), "https://example.supabase.co", "anon", 1, InMemoryStore())
        assertFalse(cloud.isSignedIn)
        cloud.signInWithEmail("me@example.com", "secret")
        assertTrue(cloud.isSignedIn)
        assertEquals("me@example.com", cloud.email)
    }

    @Test
    fun addonPullErrorIsAnErrorNotAnEmptyList() = runTest {
        val http = HttpClient(MockEngine { request ->
            val json = headersOf("Content-Type", "application/json")
            when (request.url.encodedPath) {
                "/auth/v1/token" -> respond("""{"access_token":"at","expires_in":3600}""", HttpStatusCode.OK, json)
                "/rest/v1/rpc/get_sync_owner" -> respond("\"owner-1\"", HttpStatusCode.OK, json)
                "/rest/v1/addons" -> respond("""{"message":"JWT expired"}""", HttpStatusCode.Unauthorized, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val cloud = NuvioCloud(http, InMemoryStore(), "https://example.supabase.co", "anon")
        cloud.signInWithEmail("me@example.com", "secret")
        val error = kotlin.runCatching { cloud.pullAddons() }.exceptionOrNull()
        assertEquals("JWT expired", error?.message)
    }
}
