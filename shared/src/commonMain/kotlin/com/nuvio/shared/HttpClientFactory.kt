package com.nuvio.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent

/** Platform engine (Darwin on tvOS, OkHttp on JVM). */
internal expect fun platformHttpClient(): HttpClient

fun createHttpClient(): HttpClient = platformHttpClient().config {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    install(UserAgent) {
        agent = "NuvioTV-tvOS/0.1"
    }
}
