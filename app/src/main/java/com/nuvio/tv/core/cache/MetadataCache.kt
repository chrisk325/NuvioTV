package com.nuvio.tv.core.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.io.IOException

/**
 * Disk cache for metadata responses: addon catalogs (Home rows, Discover, Search), addon meta
 * (details screen) and TMDB enrichment.
 *
 * While enabled:
 * - A cached response is returned without a network request until it is older than the refresh
 *   interval (12/24/36/48 hours); then it is fetched again and the cache is updated.
 * - A forced refresh (a request sent with `Cache-Control: no-cache`) always goes to the network
 *   and updates the cache.
 * - If the network request fails, the last cached response is used even if it is stale, so
 *   previously seen content still loads offline.
 *
 * The cache is applied with [MetadataCacheInterceptor] on the addon and TMDB HTTP clients.
 */
object MetadataCache {
    private const val TRIM_EVERY_WRITES = 25
    private const val DEFAULT_MEDIA_TYPE = "application/json"

    private val writeLock = Any()
    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writesSinceTrim = 0

    private val directory: File
        get() = CacheSettingsRepository.cacheBaseDirectory().resolve(CacheType.Metadata.directoryName)

    private fun fileFor(key: String): File = File(directory, key.encodeUtf8().sha256().hex() + ".json")

    val isEnabled: Boolean
        get() = CacheSettingsRepository.isEnabled(CacheType.Metadata)

    internal class CachedPayload(val cachedAt: Long, val mediaType: String, val body: ByteArray) {
        fun isFresh(): Boolean {
            val age = cacheNowMillis() - cachedAt
            return age in 0L until CacheSettingsRepository.refreshIntervalMillis
        }
    }

    /** Trims the cache to its (new) share of the storage limit in the background. */
    internal fun onStorageLimitChanged() {
        trimScope.launch {
            synchronized(writeLock) { runCatching { trimToMaxSize() } }
        }
    }

    /** Size of the metadata cache on disk, in bytes. */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        runCatching {
            directory.listFiles()?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    /** Deletes every cached metadata response. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                runCatching { directory.deleteRecursively() }
                writesSinceTrim = 0
            }
        }
    }

    /** Reads the cached response for [key]. Blocking; call off the main thread. */
    internal fun read(key: String): CachedPayload? = runCatching {
        val file = fileFor(key)
        if (!file.exists()) return@runCatching null
        val bytes = file.readBytes()
        val firstBreak = bytes.indexOf('\n'.code.toByte())
        if (firstBreak < 0) return@runCatching null
        val secondBreak = (firstBreak + 1 until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() }
            ?: return@runCatching null
        val cachedAt = String(bytes, 0, firstBreak, Charsets.UTF_8).trim().toLong()
        val mediaType = String(bytes, firstBreak + 1, secondBreak - firstBreak - 1, Charsets.UTF_8).trim()
        CachedPayload(
            cachedAt = cachedAt,
            mediaType = mediaType.ifBlank { DEFAULT_MEDIA_TYPE },
            body = bytes.copyOfRange(secondBreak + 1, bytes.size),
        )
    }.getOrNull()

    /** Stores [body] as the response for [key]. Blocking; call off the main thread. */
    internal fun write(key: String, mediaType: MediaType?, body: ByteArray) {
        synchronized(writeLock) {
            runCatching {
                directory.mkdirs()
                val target = fileFor(key)
                val temp = File(directory, target.name + ".tmp")
                temp.outputStream().buffered().use { out ->
                    out.write(cacheNowMillis().toString().toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                    out.write((mediaType?.toString() ?: DEFAULT_MEDIA_TYPE).toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                    out.write(body)
                }
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
            }
            writesSinceTrim += 1
            if (writesSinceTrim >= TRIM_EVERY_WRITES) {
                writesSinceTrim = 0
                runCatching { trimToMaxSize() }
            }
        }
    }

    /** Deletes the least recently written responses until the cache fits its size limit. */
    private fun trimToMaxSize() {
        val files = directory.listFiles() ?: return
        val entries = files.map { file -> Triple(file, file.length(), file.lastModified()) }
        var total = entries.sumOf { it.second }
        val maxBytes = CacheSettingsRepository.maxSizeBytes(CacheType.Metadata)
        if (total <= maxBytes) return
        val targetBytes = maxBytes * 9 / 10
        for ((file, size, _) in entries.sortedBy { it.third }) {
            if (total <= targetBytes) break
            file.delete()
            total -= size
        }
    }
}

/**
 * OkHttp interceptor that serves GET requests accepted by [shouldCache] from [MetadataCache]
 * while the metadata cache is enabled (see [MetadataCache] for the exact behaviour).
 */
class MetadataCacheInterceptor(
    private val shouldCache: (HttpUrl) -> Boolean,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" || !MetadataCache.isEnabled || !shouldCache(request.url)) {
            return chain.proceed(request)
        }
        val key = cacheKey(request)
        val forceRefresh = request.cacheControl.noCache
        val cached = MetadataCache.read(key)
        if (!forceRefresh && cached != null && cached.isFresh()) {
            return cached.toResponse(request)
        }

        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            // Offline or unreachable: fall back to the last cached copy, however old.
            return cached?.toResponse(request) ?: throw error
        }
        if (!response.isSuccessful) {
            if (cached != null) {
                response.close()
                return cached.toResponse(request)
            }
            return response
        }

        val body = response.body ?: return response
        val mediaType = body.contentType()
        val bytes = try {
            body.bytes()
        } catch (error: IOException) {
            response.close()
            return cached?.toResponse(request) ?: throw error
        }
        if (bytes.isNotEmpty()) MetadataCache.write(key, mediaType, bytes)
        return response.newBuilder()
            .body(bytes.toResponseBody(mediaType))
            .build()
    }

    /** Responses can be localized, so the requested language is part of the key. */
    private fun cacheKey(request: Request): String {
        val language = request.header("Accept-Language").orEmpty()
        return "${request.url}|$language"
    }

    private fun MetadataCache.CachedPayload.toResponse(request: Request): Response {
        val now = cacheNowMillis()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", mediaType)
            .header(CACHED_RESPONSE_HEADER, cachedAt.toString())
            .sentRequestAtMillis(now)
            .receivedResponseAtMillis(now)
            .body(body.toResponseBody(mediaType.toMediaTypeOrNull()))
            .build()
    }

    companion object {
        /** Set on responses served from [MetadataCache]; the value is when it was cached. */
        const val CACHED_RESPONSE_HEADER = "X-Nuvio-Metadata-Cache"

        /** Addon catalog (`/catalog/…json`) and meta (`/meta/…json`) resources. */
        fun isAddonMetadataUrl(url: HttpUrl): Boolean {
            val segments = url.pathSegments
            val last = segments.lastOrNull() ?: return false
            if (!last.endsWith(".json")) return false
            val resourceIndex = segments.size - 3 // …/{resource}/{type}/{id}.json
            val resourceWithExtraIndex = segments.size - 4 // …/{resource}/{type}/{id}/{extra}.json
            val resource = segments.getOrNull(resourceIndex)
            val resourceWithExtra = segments.getOrNull(resourceWithExtraIndex)
            return resource == "catalog" || resource == "meta" || resourceWithExtra == "catalog"
        }
    }
}
