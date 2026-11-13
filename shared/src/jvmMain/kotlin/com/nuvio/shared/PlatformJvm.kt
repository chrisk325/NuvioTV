package com.nuvio.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp)
