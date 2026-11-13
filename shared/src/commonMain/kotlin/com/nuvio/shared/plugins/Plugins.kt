package com.nuvio.shared.plugins

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asArray
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.bool
import com.nuvio.shared.data.int
import com.nuvio.shared.data.nonBlank
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.str
import com.nuvio.shared.data.stringMap
import com.nuvio.shared.data.strings
import com.nuvio.shared.model.AddonStreams
import com.nuvio.shared.model.ProxyHeaders
import com.nuvio.shared.model.Stream
import com.nuvio.shared.model.StreamBehaviorHints
import com.nuvio.shared.model.Subtitle
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * Runs one scraper's JavaScript. Implemented natively (JavaScriptCore on tvOS) because the JS
 * engine is platform specific; [completion] gets the JSON array the scraper's getStreams returned.
 */
interface PluginEngine {
    fun execute(
        code: String,
        callArgsJson: String,
        scraperId: String,
        settingsJson: String,
        tmdbApiKey: String,
        completion: (String) -> Unit
    )
}

@Serializable
data class PluginRepositoryInfo(
    val id: String,
    val name: String,
    val url: String,
    val description: String? = null,
    val lastUpdated: Long = 0
)

@Serializable
data class ScraperInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val repositoryId: String,
    val supportedTypes: List<String> = listOf("movie", "tv"),
    val logo: String? = null,
    val enabled: Boolean = true
) {
    fun supportsType(type: String): Boolean {
        val wanted = normalizePluginType(type)
        return supportedTypes.any { normalizePluginType(it) == wanted }
    }
}

internal fun normalizePluginType(type: String): String = when (type.trim().lowercase()) {
    "series", "tv", "show", "anime" -> "tv"
    else -> "movie"
}

class PluginException(message: String) : Exception(message)

/** Port of PluginManager (Nuvio JS repositories only; Android .dex extensions can't run on tvOS). */
class PluginService(private val http: HttpClient, private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    var engine: PluginEngine? = null

    var pluginsEnabled: Boolean
        get() = store.getString("plugins.enabled")?.toBooleanStrictOrNull() ?: true
        set(value) = store.putString("plugins.enabled", value.toString())

    val repositories: List<PluginRepositoryInfo>
        get() = store.getString(REPOS)?.let { runCatching { json.decodeFromString(ListSerializer(PluginRepositoryInfo.serializer()), it) }.getOrNull() }.orEmpty()

    val scrapers: List<ScraperInfo>
        get() = store.getString(SCRAPERS)?.let { runCatching { json.decodeFromString(ListSerializer(ScraperInfo.serializer()), it) }.getOrNull() }.orEmpty()

    private fun saveRepos(list: List<PluginRepositoryInfo>) = store.putString(REPOS, json.encodeToString(ListSerializer(PluginRepositoryInfo.serializer()), list))
    private fun saveScrapers(list: List<ScraperInfo>) = store.putString(SCRAPERS, json.encodeToString(ListSerializer(ScraperInfo.serializer()), list))

    private fun manifestUrl(input: String): String {
        var url = input.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return if (url.substringAfterLast('/').endsWith(".json", ignoreCase = true)) url else "$url/manifest.json"
    }

    @Throws(Exception::class)
    suspend fun addRepository(input: String): PluginRepositoryInfo {
        val url = manifestUrl(input)
        if (repositories.any { it.url.equals(url, ignoreCase = true) }) throw PluginException("Repository already added")
        val repo = PluginRepositoryInfo(id = "repo${currentTimeMillis()}", name = url, url = url)
        val loaded = download(repo)
        saveRepos(repositories + loaded)
        return loaded
    }

    fun removeRepository(id: String) {
        saveRepos(repositories.filterNot { it.id == id })
        val removed = scrapers.filter { it.repositoryId == id }
        removed.forEach { store.putString(codeKey(it.id), null) }
        saveScrapers(scrapers.filterNot { it.repositoryId == id })
    }

    @Throws(Exception::class)
    suspend fun refreshAll() {
        val refreshed = repositories.map { repo ->
            try { download(repo) } catch (e: CancellationException) { throw e } catch (e: Exception) { repo }
        }
        saveRepos(refreshed)
    }

    fun setScraperEnabled(id: String, enabled: Boolean) = saveScrapers(scrapers.map { if (it.id == id) it.copy(enabled = enabled) else it })

    /** Downloads the manifest and every scraper's code (port of downloadJsScrapers). */
    private suspend fun download(repo: PluginRepositoryInfo): PluginRepositoryInfo {
        val response = http.get(repo.url) { header("User-Agent", "NuvioTV/1.0") }
        if (!response.status.isSuccess()) throw PluginException("Couldn't load ${repo.url} (HTTP ${response.status.value})")
        val manifest = AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: throw PluginException("Not a plugin manifest")
        val entries = manifest["scrapers"].asArray()?.mapNotNull { it.asObject() } ?: throw PluginException("Not a Nuvio plugin repository")
        val base = repo.url.substringBeforeLast('/')
        val previous = scrapers.associateBy { it.id }
        val loaded = coroutineScope {
            entries.map { e ->
                async {
                    val id = e.nonBlank("id") ?: return@async null
                    if (e.strings("disabledPlatforms").any { it.equals("ios", true) || it.equals("tvos", true) }) return@async null
                    val file = e.nonBlank("filename") ?: return@async null
                    val codeUrl = if (file.startsWith("http")) file else "$base/$file"
                    val code = runCatching {
                        val r = http.get(codeUrl) { header("User-Agent", "NuvioTV/1.0") }
                        if (r.status.isSuccess()) r.bodyAsText().takeIf { it.length < MAX_CODE } else null
                    }.getOrNull() ?: return@async null
                    store.putString(codeKey(id), code)
                    ScraperInfo(
                        id = id,
                        name = e.str("name") ?: id,
                        description = e.str("description").orEmpty(),
                        version = e.str("version").orEmpty(),
                        repositoryId = repo.id,
                        supportedTypes = e.strings("supportedTypes").ifEmpty { listOf("movie", "tv") },
                        logo = e.nonBlank("logo"),
                        enabled = previous[id]?.enabled ?: (e.bool("enabled") ?: true)
                    )
                }
            }.awaitAll().filterNotNull()
        }
        saveScrapers(scrapers.filterNot { it.repositoryId == repo.id } + loaded)
        return repo.copy(name = manifest.str("name") ?: repo.name, description = manifest.str("description"), lastUpdated = currentTimeMillis())
    }

    /** Runs every enabled scraper for the type in parallel; each group is one scraper's results. */
    suspend fun streams(tmdbId: String, type: String, season: Int?, episode: Int?, tmdbApiKey: String): List<AddonStreams> {
        val engine = engine ?: return emptyList()
        if (!pluginsEnabled) return emptyList()
        val mediaType = normalizePluginType(type)
        val args = buildJsonObject {
            put("tmdbId", tmdbId); put("mediaType", mediaType)
            season?.let { put("season", it) }; episode?.let { put("episode", it) }
        }.toString()
        return coroutineScope {
            scrapers.filter { it.enabled && it.supportsType(type) }.map { scraper ->
                async {
                    val code = store.getString(codeKey(scraper.id)) ?: return@async null
                    val resultJson = withTimeoutOrNull(TIMEOUT_MS) {
                        suspendCancellableCoroutine<String> { cont ->
                            engine.execute(code, args, scraper.id, "{}", tmdbApiKey) { result ->
                                if (cont.isActive) cont.resume(result)
                            }
                        }
                    } ?: return@async null
                    val streams = parseResults(resultJson, scraper.name)
                    if (streams.isEmpty()) null else AddonStreams(scraper.name, scraper.logo, streams)
                }
            }.awaitAll().filterNotNull()
        }
    }

    companion object {
        private const val REPOS = "plugins.repos"
        private const val SCRAPERS = "plugins.scrapers"
        private const val TIMEOUT_MS = 45_000L
        private const val MAX_CODE = 5_000_000
        private fun codeKey(id: String) = "plugins.code.$id"

        /** Port of PluginRuntime.parseJsonResults → Stream. */
        fun parseResults(json: String, scraperName: String): List<Stream> {
            val array = runCatching { AddonJson.parseToJsonElement(json).asArray() }.getOrNull() ?: return emptyList()
            return array.mapNotNull { element ->
                val o = element.asObject() ?: return@mapNotNull null
                val url = (o.str("url") ?: o.obj("url")?.str("url"))?.takeIf { it.isNotBlank() && "[object" !in it } ?: return@mapNotNull null
                fun text(key: String) = o.str(key)?.takeIf { it.isNotBlank() && "[object" !in it }
                val title = text("title") ?: text("name") ?: "Unknown"
                val quality = text("quality")
                val details = listOfNotNull(title, listOfNotNull(quality, text("size"), text("language")).joinToString(" · ").ifEmpty { null })
                val headers = o.stringMap("headers")?.takeIf { it.isNotEmpty() }
                Stream(
                    name = listOfNotNull(text("provider") ?: scraperName, quality).joinToString("\n"),
                    title = title,
                    description = details.joinToString("\n"),
                    url = url.takeUnless { it.startsWith("magnet:", true) },
                    ytId = null,
                    infoHash = text("infoHash"),
                    fileIdx = null,
                    externalUrl = url.takeIf { it.startsWith("magnet:", true) },
                    behaviorHints = headers?.let { StreamBehaviorHints(null, null, ProxyHeaders(it, null)) },
                    addonName = scraperName,
                    addonLogo = null,
                    subtitles = o["subtitles"].asArray()?.mapNotNull { s ->
                        val so = s.asObject() ?: return@mapNotNull null
                        val sUrl = so.nonBlank("url") ?: return@mapNotNull null
                        Subtitle(so.nonBlank("id") ?: sUrl, sUrl, so.nonBlank("lang") ?: so.nonBlank("language") ?: "und", scraperName, null, true)
                    }.orEmpty()
                )
            }
        }
    }
}
