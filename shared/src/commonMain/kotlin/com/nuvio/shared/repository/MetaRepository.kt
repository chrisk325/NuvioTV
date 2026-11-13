package com.nuvio.shared.repository

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.model.Addon
import com.nuvio.shared.model.AddonResource
import com.nuvio.shared.model.Meta
import com.nuvio.shared.util.normalizeAddonKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MetaNotFoundException(type: String, id: String, tried: List<String>) :
    Exception(
        if (tried.isEmpty()) "No add-on provides details for $type/$id"
        else "Couldn't load $type/$id from ${tried.joinToString()}"
    )

/**
 * Port of MetaRepositoryImpl.getMetaFromAllAddons candidate selection: tries the source add-on
 * first, then add-ons matching type + id prefix, the inferred canonical type, and finally
 * prefix-less add-ons.
 */
class MetaRepository(private val client: StremioClient) {
    private val cache = LinkedHashMap<String, Meta>()
    private val mutex = Mutex()

    fun cached(type: String, id: String): Meta? = cache["$type:$id"]

    @Throws(Exception::class)
    suspend fun getMeta(addons: List<Addon>, type: String, id: String, sourceAddonBaseUrl: String? = null): Meta {
        val cacheKey = "$type:$id"
        mutex.withLock { cache[cacheKey] }?.let { return it }

        val candidates = metaCandidates(addons.filter { it.enabled }, type, id, sourceAddonBaseUrl)
        val tried = mutableListOf<String>()
        for ((addon, candidateType) in candidates) {
            tried += addon.displayName
            try {
                val meta = client.fetchMeta(addon.baseUrl, candidateType, id) ?: continue
                mutex.withLock {
                    cache[cacheKey] = meta
                    if (cache.size > MAX_CACHE) cache.remove(cache.keys.first())
                }
                return meta
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // try next candidate
            }
        }
        throw MetaNotFoundException(type, id, tried)
    }

    companion object {
        private const val MAX_CACHE = 64

        fun metaCandidates(
            addons: List<Addon>,
            type: String,
            id: String,
            sourceAddonBaseUrl: String? = null
        ): List<Pair<Addon, String>> {
            val requestedType = type.trim()
            val inferredType = inferCanonicalType(requestedType, id)
            val metaAddons = addons.filter { it.hasResource("meta") }
            val candidates = linkedSetOf<Pair<Addon, String>>()

            sourceAddonBaseUrl?.let { source ->
                val key = normalizeAddonKey(source)
                metaAddons.firstOrNull { normalizeAddonKey(it.baseUrl) == key && it.supportsMetaId(id) }
                    ?.let { addon -> addon.supportedCandidateType(requestedType, inferredType)?.let { candidates += addon to it } }
            }
            addons.forEach { addon ->
                if (addon.supportsMetaType(requestedType) && addon.supportsMetaId(id)) candidates += addon to requestedType
            }
            if (!inferredType.equals(requestedType, ignoreCase = true)) {
                addons.forEach { addon ->
                    if (addon.supportsMetaType(inferredType) && addon.supportsMetaId(id)) candidates += addon to inferredType
                }
            }
            metaAddons.firstOrNull { it.supportsMetaId(id) }?.let { top ->
                top.supportedCandidateType(requestedType, inferredType)?.let { candidates += top to it }
            }
            if (candidates.isEmpty()) {
                addons.forEach { addon ->
                    if (addon.supportsMetaType(requestedType) && addon.idPrefixes.isEmpty()) candidates += addon to requestedType
                }
                metaAddons.firstOrNull { it.idPrefixes.isEmpty() }?.let { top ->
                    top.supportedCandidateType(requestedType, inferredType)?.let { candidates += top to it }
                }
            }
            if (candidates.isEmpty()) {
                metaAddons.filter { addon -> addon.rawTypes.any { it.equals(requestedType, ignoreCase = true) } }
                    .forEach { candidates += it to requestedType }
            }
            return candidates.toList()
        }

        internal fun inferCanonicalType(type: String, id: String): String {
            val normalizedType = type.trim()
            if (normalizedType.equals("tv", ignoreCase = true)) return "series"
            if (normalizedType.lowercase() in setOf("movie", "series", "channel", "anime")) return normalizedType
            val normalizedId = id.lowercase()
            return when {
                ":movie:" in normalizedId -> "movie"
                ":series:" in normalizedId -> "series"
                ":tv:" in normalizedId -> "series"
                ":anime:" in normalizedId -> "anime"
                else -> normalizedType
            }
        }

        private fun Addon.supportsMetaType(type: String): Boolean {
            val target = type.trim()
            if (target.isBlank()) return false
            return resources.any { it.name == "meta" && it.supportsType(target) }
        }

        private fun Addon.supportsMetaId(id: String): Boolean {
            val metaResource = resources.firstOrNull { it.name == "meta" }
            val resourcePrefixes = metaResource?.idPrefixes
            if (!resourcePrefixes.isNullOrEmpty()) {
                return resourcePrefixes.any { id.startsWith(it, ignoreCase = true) }
            }
            if (idPrefixes.isNotEmpty()) return idPrefixes.any { id.startsWith(it, ignoreCase = true) }
            return true
        }

        private fun AddonResource.supportsType(type: String): Boolean =
            types.isEmpty() || types.any { it.equals(type, ignoreCase = true) }

        private fun Addon.supportedCandidateType(requestedType: String, inferredType: String): String? = when {
            supportsMetaType(requestedType) -> requestedType
            supportsMetaType(inferredType) -> inferredType
            else -> null
        }
    }
}
