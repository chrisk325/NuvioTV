package com.nuvio.shared.repository

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.data.parseSubtitleResponse
import com.nuvio.shared.model.Addon
import com.nuvio.shared.model.AddonResource
import com.nuvio.shared.model.Subtitle
import com.nuvio.shared.util.encodeUrlComponent
import com.nuvio.shared.util.splitAddonBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** Port of SubtitleRepositoryImpl: queries every subtitles add-on in parallel. */
class SubtitleRepository(private val client: StremioClient) {

    fun subtitleAddons(addons: List<Addon>, type: String, id: String): List<Addon> {
        val requestType = canonicalSubtitleType(type)
        return addons.filter { it.enabled }.filter { addon ->
            addon.resources.any { isSubtitleResource(it.name) && supportsType(addon, it, requestType, id) }
        }
    }

    suspend fun subtitles(
        addons: List<Addon>,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): List<Subtitle> = supervisorScope {
        subtitleAddons(addons, type, id).map { addon ->
            async {
                try {
                    withTimeoutOrNull(PER_ADDON_TIMEOUT_MS) {
                        val url = buildSubtitleUrl(addon.baseUrl, type, id, videoId, videoHash, videoSize, filename)
                        parseSubtitleResponse(client.fetchText(url), addon.displayName, addon.logo)
                    }.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    companion object {
        private const val PER_ADDON_TIMEOUT_MS = 15_000L

        fun canonicalSubtitleType(type: String): String =
            if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

        private fun isSubtitleResource(name: String) =
            name.equals("subtitles", ignoreCase = true) || name.equals("subtitle", ignoreCase = true)

        private fun supportsType(addon: Addon, resource: AddonResource, type: String, id: String): Boolean {
            if (resource.types.isNotEmpty()) {
                val reqType = canonicalSubtitleType(type)
                if (resource.types.none { canonicalSubtitleType(it) == reqType }) return false
            }
            val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() } ?: addon.idPrefixes.takeIf { it.isNotEmpty() }
            return prefixes == null || prefixes.any { id.startsWith(it) }
        }

        fun buildSubtitleUrl(
            baseUrl: String,
            type: String,
            id: String,
            videoId: String?,
            videoHash: String?,
            videoSize: Long?,
            filename: String?
        ): String {
            val normalizedType = canonicalSubtitleType(type)
            val actualId = videoId?.takeIf { it.isNotBlank() } ?: id
            val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)
            val params = buildList {
                videoHash?.let { add("videoHash=$it") }
                videoSize?.let { add("videoSize=$it") }
                filename?.let { add("filename=${encodeUrlComponent(it)}") }
            }.joinToString("&")
            val idPart = "${encodeUrlComponent(normalizedType)}/${encodeUrlComponent(actualId)}"
            return if (params.isNotEmpty()) {
                "$basePath/subtitles/$idPart/$params.json$baseQuery"
            } else {
                "$basePath/subtitles/$idPart.json$baseQuery"
            }
        }
    }
}
