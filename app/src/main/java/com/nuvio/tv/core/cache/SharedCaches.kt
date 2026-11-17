package com.nuvio.tv.core.cache

import android.content.Context
import coil3.SingletonImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import javax.inject.Named

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SharedCachesEntryPoint {
    /** First-party client; its HTTP cache holds the TMDB responses. */
    fun okHttpClient(): OkHttpClient

    /** Addon client; its HTTP cache holds the addon catalog and meta responses. */
    @Named("addonPermissive")
    fun addonOkHttpClient(): OkHttpClient
}

/**
 * App caches outside the content caches that hold the same kind of data, and count towards the
 * app's cache in the system's storage settings:
 *
 * - the app's default image disk cache, which keeps every image loaded outside the poster and
 *   background caches (e.g. before a cache was turned on, while it is off, logos and avatars);
 * - the HTTP response caches of the addon and TMDB clients.
 *
 * The cache "Clear" rows clear these too, so clearing actually frees the space.
 */
object SharedCaches {

    private fun networkCaches(context: Context): List<Cache> {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SharedCachesEntryPoint::class.java,
        )
        return listOfNotNull(
            entryPoint.addonOkHttpClient().cache,
            entryPoint.okHttpClient().cache,
        ).distinctBy { it.directory.absolutePath }
    }

    /** Size of the app's default image disk cache, in bytes. */
    suspend fun imageCacheSizeBytes(context: Context): Long = withContext(Dispatchers.IO) {
        runCatching { SingletonImageLoader.get(context).diskCache?.size ?: 0L }.getOrDefault(0L)
    }

    /** Deletes everything in the app's default image disk cache. */
    suspend fun clearImageCache(context: Context) {
        withContext(Dispatchers.IO) {
            runCatching { SingletonImageLoader.get(context).diskCache?.clear() }
        }
    }

    /** Size of the addon and TMDB HTTP response caches, in bytes. */
    suspend fun networkCacheSizeBytes(context: Context): Long = withContext(Dispatchers.IO) {
        runCatching {
            networkCaches(context).sumOf { cache -> runCatching { cache.size() }.getOrDefault(0L) }
        }.getOrDefault(0L)
    }

    /** Deletes every cached addon and TMDB HTTP response. */
    suspend fun clearNetworkCache(context: Context) {
        withContext(Dispatchers.IO) {
            runCatching {
                networkCaches(context).forEach { cache -> runCatching { cache.evictAll() } }
            }
        }
    }
}
