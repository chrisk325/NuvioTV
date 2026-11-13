package com.nuvio.shared

import com.nuvio.shared.storage.UserDefaultsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.Dispatchers

internal actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)

/** Swift: `NuvioCoreFactoryKt.createNuvioCore()` – UserDefaults storage, main-thread callbacks. */
fun createNuvioCore(config: NuvioConfig): NuvioCore = NuvioCore(
    store = UserDefaultsStore(),
    httpClient = createHttpClient(),
    dispatcher = Dispatchers.Main,
    config = config
)
