package com.nuvio.shared

import com.nuvio.shared.storage.UserDefaultsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.Dispatchers

internal actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)

/** Swift: `NuvioCoreFactoryKt.createNuvioCore()` – UserDefaults storage, main-thread callbacks. */
fun createNuvioCore(config: NuvioConfig): NuvioCore {
    val device = UserDefaultsStore(prefix = "nuvio.device.")
    val profile = com.nuvio.shared.settings.ProfileStore(device).lastProfileIndex
    return createNuvioCoreForProfile(config, profile)
}

/** Profile 1 keeps the original "nuvio." keys so existing data stays where it was. */
fun createNuvioCoreForProfile(config: NuvioConfig, profileIndex: Int): NuvioCore {
    val device = UserDefaultsStore(prefix = "nuvio.device.")
    com.nuvio.shared.settings.ProfileStore(device).lastProfileIndex = profileIndex
    return NuvioCore(
        store = UserDefaultsStore(prefix = if (profileIndex == 1) "nuvio." else "nuvio.p$profileIndex."),
        httpClient = createHttpClient(),
        dispatcher = Dispatchers.Main,
        config = config,
        profileIndex = profileIndex,
        deviceStore = device
    )
}
