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
}
