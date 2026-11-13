package com.nuvio.shared.data

import com.nuvio.shared.model.Addon
import com.nuvio.shared.model.AddonBehaviorHints
import com.nuvio.shared.model.AddonResource
import com.nuvio.shared.model.CatalogDescriptor
import com.nuvio.shared.model.CatalogExtra
import com.nuvio.shared.model.ContentType
import com.nuvio.shared.model.Meta
import com.nuvio.shared.model.MetaBehaviorHints
import com.nuvio.shared.model.MetaCastMember
import com.nuvio.shared.model.MetaLink
import com.nuvio.shared.model.MetaPreview
import com.nuvio.shared.model.PosterShape
import com.nuvio.shared.model.ProxyHeaders
import com.nuvio.shared.model.Stream
import com.nuvio.shared.model.StreamBehaviorHints
import com.nuvio.shared.model.StreamClientResolve
import com.nuvio.shared.model.Subtitle
import com.nuvio.shared.model.Video
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Ports of app/.../data/mapper/{AddonMapper,CatalogMapper,MetaMapper,StreamMapper,MetadataFieldMappers}.kt

class AddonParseException(message: String) : Exception(message)

fun parseManifest(json: String, baseUrl: String): Addon {
    val root = runCatching { AddonJson.parseToJsonElement(json) }.getOrNull().asObject()
        ?: throw AddonParseException("Manifest is not a JSON object")
    return root.toAddon(baseUrl)
}

internal fun JsonObject.toAddon(baseUrl: String): Addon {
    val id = nonBlank("id") ?: throw AddonParseException("Manifest has no id")
    val name = nonBlank("name") ?: id
    val manifestTypes = strings("types").map { it.trim() }.filter { it.isNotEmpty() }
    return Addon(
        id = id,
        name = name,
        version = str("version") ?: "0.0.0",
        description = str("description"),
        logo = nonBlank("logo"),
        background = nonBlank("background"),
        baseUrl = baseUrl,
        catalogs = objects("catalogs").mapNotNull { it.toCatalogDescriptor() },
        types = manifestTypes.map { ContentType.fromString(it) },
        rawTypes = manifestTypes,
        resources = parseResources(this["resources"], manifestTypes),
        idPrefixes = strings("idPrefixes"),
        behaviorHints = obj("behaviorHints")?.let {
            AddonBehaviorHints(
                configurable = it.bool("configurable"),
                configurationRequired = it.bool("configurationRequired"),
                newEpisodeNotifications = it.bool("newEpisodeNotifications")
            )
        },
        manifestLanguage = str("language")
    )
}

internal fun JsonObject.toCatalogDescriptor(): CatalogDescriptor? {
    val type = str("type")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id = nonBlank("id") ?: return null
    val showInHome = bool("showInHome")
    return CatalogDescriptor(
        type = ContentType.fromString(type),
        rawType = type,
        id = id,
        name = nonBlank("name") ?: id,
        extra = parseCatalogExtras(this["extra"]),
        pageSize = int("pageSize"),
        showInHome = showInHome == true,
        hasExplicitShowInHome = showInHome != null,
        extraSupported = strings("extraSupported"),
        extraRequired = strings("extraRequired")
    )
}

private fun parseResources(resources: JsonElement?, defaultTypes: List<String>): List<AddonResource> {
    val array = resources as? JsonArray ?: return emptyList()
    return array.mapNotNull { resource ->
        when (resource) {
            is JsonPrimitive -> resource.asString()?.let { AddonResource(it, defaultTypes, null) }
            is JsonObject -> {
                val name = resource.str("name") ?: return@mapNotNull null
                val types = if (resource["types"] is JsonArray) resource.strings("types") else defaultTypes
                val idPrefixes = if (resource["idPrefixes"] is JsonArray) resource.strings("idPrefixes") else null
                AddonResource(name, types, idPrefixes)
            }
            else -> null
        }
    }
}

private fun parseCatalogExtras(rawExtras: JsonElement?): List<CatalogExtra> {
    val array = rawExtras as? JsonArray ?: return emptyList()
    return array.mapNotNull { raw ->
        when (raw) {
            is JsonPrimitive -> raw.asString()?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { CatalogExtra(it) }
            is JsonObject -> {
                val name = raw.str("name")?.trim()?.lowercase().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                CatalogExtra(
                    name = name,
                    isRequired = raw.bool("isRequired") ?: false,
                    options = raw.strings("options").takeIf { it.isNotEmpty() },
                    defaultValue = raw.nonBlank("default"),
                    optionsLimit = raw.int("optionsLimit")
                )
            }
            else -> null
        }
    }.distinct()
}

fun parseCatalogResponse(json: String, catalogType: String, sourceAddonBaseUrl: String?): List<MetaPreview> {
    val root = AddonJson.parseToJsonElement(json).asObject() ?: return emptyList()
    return root.objects("metas")
        .mapNotNull { it.toMetaPreviewOrNull(catalogType, sourceAddonBaseUrl) }
        .distinctBy { it.id }
}

internal fun JsonObject.toMetaPreviewOrNull(catalogType: String, sourceAddonBaseUrl: String?): MetaPreview? {
    val id = nonBlank("id") ?: return null
    val name = nonBlank("name") ?: return null
    val type = nonBlank("type") ?: catalogType
    return MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = nonBlank("poster"),
        posterShape = PosterShape.fromString(str("posterShape")),
        background = nonBlank("background"),
        logo = nonBlank("logo"),
        description = str("description"),
        releaseInfo = str("releaseInfo"),
        imdbRating = nonBlank("imdbRating")?.takeIf { it.toDoubleOrNull() != null },
        genres = strings("genres"),
        runtime = nonBlank("runtime"),
        released = nonBlank("released"),
        imdbId = nonBlank("imdb_id"),
        landscapePoster = nonBlank("landscapePoster"),
        trailerYtIds = collectTrailerYtIds(this),
        sourceAddonBaseUrl = sourceAddonBaseUrl
    )
}

/** Returns null when the response has no `meta` object. */
fun parseMetaResponse(json: String, episodeLabel: String = "Episode"): Meta? {
    val root = AddonJson.parseToJsonElement(json).asObject() ?: return null
    return root.obj("meta")?.toMeta(episodeLabel)
}

internal fun JsonObject.toMeta(episodeLabel: String = "Episode"): Meta? {
    val id = nonBlank("id") ?: return null
    val type = nonBlank("type") ?: "movie"
    val appExtras = obj("app_extras")
    val directorMembers = mapPeople(appExtras?.objects("directors"), roleFallback = "Director", forceRole = true)
    val writerMembers = mapPeople(appExtras?.objects("writers"), roleFallback = "Writer", forceRole = true)
    val castMembers = mapPeople(appExtras?.objects("cast"))
    val directors = coerceStringList(this["director"]).ifEmpty { directorMembers.map { it.name } }
    val writers = coerceStringList(this["writer"]).ifEmpty { coerceStringList(this["writers"]) }
        .ifEmpty { writerMembers.map { it.name } }
    val cast = coerceStringList(this["cast"]).ifEmpty { castMembers.map { it.name } }
    return Meta(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = nonBlank("name") ?: id,
        poster = nonBlank("poster"),
        posterShape = PosterShape.fromString(str("posterShape")),
        background = nonBlank("background"),
        logo = nonBlank("logo"),
        description = str("description"),
        releaseInfo = str("releaseInfo"),
        status = nonBlank("status"),
        imdbRating = nonBlank("imdbRating")?.takeIf { it.toDoubleOrNull() != null },
        genres = strings("genres"),
        runtime = nonBlank("runtime"),
        director = directors,
        writer = writers,
        cast = cast,
        castMembers = directorMembers + writerMembers + castMembers,
        videos = objects("videos").mapNotNull { it.toVideo(episodeLabel) },
        ageRating = listOfNotNull(appExtras?.nonBlank("certificationLocal"), appExtras?.nonBlank("certification")).firstOrNull(),
        country = nonBlank("country"),
        awards = nonBlank("awards"),
        language = nonBlank("language"),
        links = objects("links").mapNotNull { link ->
            val url = link.nonBlank("url") ?: return@mapNotNull null
            MetaLink(name = link.str("name").orEmpty(), category = link.str("category").orEmpty(), url = url)
        },
        trailerYtIds = collectTrailerYtIds(this),
        imdbId = nonBlank("imdb_id"),
        released = nonBlank("released"),
        landscapePoster = nonBlank("landscapePoster"),
        behaviorHints = obj("behaviorHints")?.let {
            MetaBehaviorHints(
                defaultVideoId = it.nonBlank("defaultVideoId"),
                hasScheduledVideos = it.bool("hasScheduledVideos")
            )
        }
    )
}

internal fun JsonObject.toVideo(episodeLabel: String): Video? {
    val id = nonBlank("id") ?: return null
    val episode = int("episode") ?: int("number")
    return Video(
        id = id,
        title = nonBlank("name") ?: nonBlank("title") ?: "$episodeLabel ${episode ?: 0}",
        released = nonBlank("released"),
        thumbnail = nonBlank("thumbnail"),
        streams = objects("streams").map { it.toStream(addonName = "Embedded Streams", addonLogo = null) },
        season = int("season"),
        episode = episode,
        overview = str("overview") ?: str("description"),
        runtimeMinutes = parseEpisodeRuntimeMinutes(str("runtime")) ?: 0,
        available = bool("available")
    )
}

private fun mapPeople(people: List<JsonObject>?, roleFallback: String? = null, forceRole: Boolean = false): List<MetaCastMember> =
    people.orEmpty().mapNotNull { person ->
        val name = person.nonBlank("name") ?: return@mapNotNull null
        MetaCastMember(
            name = name,
            character = if (forceRole) roleFallback else person.nonBlank("character") ?: roleFallback,
            photo = person.nonBlank("photo"),
            tmdbId = person.int("tmdbId")
        )
    }

private fun collectTrailerYtIds(meta: JsonObject): List<String> {
    val fromTrailers = meta.objects("trailers").mapNotNull { (it.nonBlank("source") ?: it.nonBlank("ytId")) }
    val fromStreams = meta.objects("trailerStreams").mapNotNull { it.nonBlank("ytId") }
    return (fromTrailers + fromStreams).distinct()
}

private fun parseEpisodeRuntimeMinutes(runtime: String?): Int? {
    val normalized = runtime?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val hours = Regex("""(\d+)\s*h""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val minutes = Regex("""(\d+)\s*m(?:in)?""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (hours != null || minutes != null) return (hours ?: 0) * 60 + (minutes ?: 0)
    return normalized.filter(Char::isDigit).toIntOrNull()
}

fun parseStreamResponse(json: String, addonName: String, addonLogo: String?): List<Stream> {
    val root = AddonJson.parseToJsonElement(json).asObject() ?: return emptyList()
    return root.objects("streams").map { it.toStream(addonName, addonLogo) }
}

internal fun JsonObject.toStream(addonName: String, addonLogo: String?): Stream = Stream(
    name = str("name"),
    title = str("title"),
    description = str("description"),
    url = nonBlank("url"),
    ytId = nonBlank("ytId"),
    infoHash = nonBlank("infoHash"),
    fileIdx = int("fileIdx"),
    externalUrl = nonBlank("externalUrl"),
    behaviorHints = obj("behaviorHints")?.let { hints ->
        StreamBehaviorHints(
            notWebReady = hints.bool("notWebReady"),
            bingeGroup = hints.nonBlank("bingeGroup"),
            proxyHeaders = hints.obj("proxyHeaders")?.let { headers ->
                ProxyHeaders(
                    request = sanitizeHeaderMap(headers.stringMap("request")),
                    response = sanitizeHeaderMap(headers.stringMap("response"))
                )
            },
            videoHash = hints.nonBlank("videoHash"),
            videoSize = hints.long("videoSize"),
            filename = hints.nonBlank("filename")
        )
    },
    addonName = addonName,
    addonLogo = addonLogo,
    sources = strings("sources").takeIf { it.isNotEmpty() },
    clientResolve = obj("clientResolve")?.let { cr ->
        StreamClientResolve(
            type = cr.nonBlank("type"),
            infoHash = cr.nonBlank("infoHash"),
            fileIdx = cr.int("fileIdx"),
            magnetUri = cr.nonBlank("magnetUri"),
            sources = cr.strings("sources").takeIf { it.isNotEmpty() },
            torrentName = cr.nonBlank("torrentName"),
            filename = cr.nonBlank("filename") ?: cr.obj("stream")?.obj("raw")?.nonBlank("filename"),
            season = cr.int("season"),
            episode = cr.int("episode"),
            service = cr.nonBlank("service"),
            isCached = cr.bool("isCached")
        )
    },
    subtitles = objects("subtitles").mapNotNull { sub ->
        val url = sub.nonBlank("url") ?: return@mapNotNull null
        Subtitle(
            id = sub.nonBlank("id") ?: url,
            url = url,
            lang = sub.nonBlank("lang") ?: "Unknown",
            addonName = addonName,
            addonLogo = addonLogo,
            isStreamProvided = true,
            headers = sub.stringMap("headers")
        )
    }
)

fun parseSubtitleResponse(json: String, addonName: String, addonLogo: String?): List<Subtitle> {
    val root = AddonJson.parseToJsonElement(json).asObject() ?: return emptyList()
    return root.objects("subtitles").mapNotNull { sub ->
        val url = sub.nonBlank("url") ?: return@mapNotNull null
        Subtitle(
            id = sub.nonBlank("id") ?: url,
            url = url,
            lang = sub.nonBlank("lang") ?: sub.nonBlank("language") ?: "Unknown",
            addonName = addonName,
            addonLogo = addonLogo,
            headers = sub.stringMap("headers")
        )
    }
}

private fun sanitizeHeaderMap(headers: Map<String, String>?): Map<String, String>? {
    if (headers.isNullOrEmpty()) return null
    val sanitized = LinkedHashMap<String, String>(headers.size)
    headers.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (key.equals("Range", ignoreCase = true)) return@forEach
        sanitized[key] = value
    }
    return sanitized.takeIf { it.isNotEmpty() }
}
