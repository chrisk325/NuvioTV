package com.nuvio.shared

import com.nuvio.shared.storage.InMemoryStore
import com.nuvio.shared.tracking.SimklService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklAuthTest {
    @Test
    fun deviceFlowLikeAndroidAuthV2() = runTest {
        var polls = 0
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("cid", request.url.parameters["client_id"])
            assertEquals("nuvio", request.url.parameters["app-name"])
            (request.body as? TextContent)?.text?.let { bodies += it }
            val json = headersOf("Content-Type", "application/json")
            when (request.url.encodedPath) {
                "/oauth2/device" -> respond(
                    """{"device_code":"dev1","user_code":"AB12CD","verification_uri":"https://simkl.com/activate",
                       "verification_uri_complete":"https://simkl.com/activate?code=AB12CD","expires_in":900,"interval":5}""",
                    HttpStatusCode.OK, json
                )
                "/oauth2/token" -> if (polls++ == 0) {
                    respond("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest, json)
                } else {
                    respond("""{"access_token":"tok","refresh_token":"ref","expires_in":604800}""", HttpStatusCode.OK, json)
                }
                "/users/settings" -> respond("""{"user":{"name":"tester"}}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val simkl = SimklService(http, InMemoryStore(), "cid")
        val signIn = simkl.startSignIn()
        assertEquals("AB12CD", signIn.userCode)
        assertEquals("https://simkl.com/activate?code=AB12CD", signIn.verificationUrl)
        assertTrue("scope=media%3Aread%20media%3Awrite" in bodies.first())
        assertFalse(simkl.pollSignIn(signIn))
        assertTrue(simkl.pollSignIn(signIn))
        assertTrue("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code" in bodies[1])
        assertTrue(simkl.isConnected)
        assertEquals("tester", simkl.username)
    }
}
