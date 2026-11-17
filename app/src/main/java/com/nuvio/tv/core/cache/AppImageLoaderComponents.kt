package com.nuvio.tv.core.cache

import coil3.ComponentRegistry
import coil3.annotation.ExperimentalCoilApi
import coil3.network.cachecontrol.CacheControlCacheStrategy
import com.nuvio.tv.core.image.CustomPosterFallbackInterceptor
import com.nuvio.tv.core.network.imageNetworkFetcherFactory

/**
 * The app's own image components (interceptors, decoders, network fetcher), registered by
 * whoever builds the app's singleton ImageLoader.
 *
 * The cache loaders must be built from these rather than from `ImageLoader.components`: the
 * finished registry of a built loader also contains that loader's internal engine interceptor,
 * which would run the request against the singleton (and its disk cache) instead of the cache
 * loader.
 */
object AppImageLoaderComponents {
    @Volatile
    private var registrar: (ComponentRegistry.Builder.() -> Unit)? = null

    /** Registers the app's components. Call this from the singleton ImageLoader factory. */
    fun register(block: ComponentRegistry.Builder.() -> Unit) {
        registrar = block
    }

    /** Adds the registered app components to [builder] (or a minimal default set). */
    @OptIn(ExperimentalCoilApi::class)
    internal fun addTo(builder: ComponentRegistry.Builder) {
        val block = registrar
        if (block != null) {
            builder.block()
        } else {
            builder.add(CustomPosterFallbackInterceptor())
            builder.add(
                imageNetworkFetcherFactory(
                    cacheStrategy = { ContentCacheStrategy(CacheControlCacheStrategy()) },
                ),
            )
        }
    }
}
