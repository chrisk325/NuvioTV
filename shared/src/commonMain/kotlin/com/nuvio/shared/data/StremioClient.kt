package com.nuvio.shared.data

import com.nuvio.shared.model.Addon
import com.nuvio.shared.model.Meta
import com.nuvio.shared.model.MetaPreview
import com.nuvio.shared.model.Stream
import com.nuvio.shared.model.Subtitle
import com.nuvio.shared.util.canonicalizeAddonUrl
import com.nuvio.shared.util.encodeUrlComponent
import com.nuvio.shared.util.splitAddonBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

class AddonHttpException(val url: String, val statusCode: Int) :
    Exception("HTTP $statusCode for $url")

/**
 * Stremio add-on protocol client (manifest, catalog, meta, stream, subtitles).
 * URL shapes match the Android repositories (CatalogRepositoryImpl, MetaRepositoryImpl,
 * StreamRepositoryImpl) so both apps hit add-ons identically.
 */
class StremioClient(private val http: HttpClient) {

    suspend fun fetchText(url: String): String {
        val response = http.get(url) {
            header(HttpHeaders.Accept, "application/json")
        }
        if (!response.status.isSuccess()) throw AddonHttpException(url, response.status.value)
        return response.bodyAsText()
    }

    suspend fun fetchManifest(url: String): Addon {
        val baseUrl = canonicalizeAddonUrl(url)
        val addon = parseManifest(fetchText(manifestUrlFor(baseUrl)), baseUrl)
        return addon
    }

    /** Fetches the raw manifest JSON so it can be cached alongside the URL. */
    suspend fun fetchManifestJson(url: String): String = fetchText(manifestUrlFor(canonicalizeAddonUrl(url)))

    suspend fun fetchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int = 0,
        extraArgs: Map<String, String> = emptyMap()
    ): List<MetaPreview> {
        val url = buildCatalogUrl(baseUrl, type, catalogId, skip, extraArgs)
        return parseCatalogResponse(fetchText(url), type, baseUrl)
    }

    suspend fun fetchMeta(baseUrl: String, type: String, id: String): Meta? =
        parseMetaResponse(fetchText(buildResourceUrl(baseUrl, "meta", type, id)))

    suspend fun fetchStreams(addon: Addon, type: String, videoId: String): List<Stream> =
        parseStreamResponse(
            fetchText(buildResourceUrl(addon.baseUrl, "stream", type, videoId)),
            addon.displayName,
            addon.logo
        )

    suspend fun fetchSubtitles(addon: Addon, type: String, videoId: String): List<Subtitle> =
        parseSubtitleResponse(
            fetchText(buildResourceUrl(addon.baseUrl, "subtitles", type, videoId)),
            addon.displayName,
            addon.logo
        )

    companion object {
        fun manifestUrlFor(baseUrl: String): String {
            val (path, query) = splitAddonBaseUrl(baseUrl)
            return "$path/manifest.json$query"
        }

        fun buildResourceUrl(baseUrl: String, resource: String, type: String, id: String): String {
            val (path, query) = splitAddonBaseUrl(baseUrl)
            return "$path/$resource/${encodeUrlComponent(type)}/${encodeUrlComponent(id)}.json$query"
        }

        /** Port of CatalogRepositoryImpl.buildCatalogUrl. */
        fun buildCatalogUrl(
            baseUrl: String,
            type: String,
            catalogId: String,
            skip: Int,
            extraArgs: Map<String, String>
        ): String {
            val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)
            val catalogPath = if (extraArgs.isEmpty()) {
                if (skip > 0) "$basePath/catalog/$type/$catalogId/skip=$skip.json"
                else "$basePath/catalog/$type/$catalogId.json"
            } else {
                val allArgs = LinkedHashMap(extraArgs)
                if (!allArgs.containsKey("skip") && skip > 0) allArgs["skip"] = skip.toString()
                val encoded = allArgs.entries.joinToString("&") { (k, v) ->
                    "${encodeUrlComponent(k)}=${encodeUrlComponent(v)}"
                }
                "$basePath/catalog/$type/$catalogId/$encoded.json"
            }
            return catalogPath + baseQuery
        }
    }
}
