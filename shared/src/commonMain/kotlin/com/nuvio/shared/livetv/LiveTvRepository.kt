package com.nuvio.shared.livetv

import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.currentTimeMillis
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class LiveTvState(
    val groups: List<LiveTvGroup>,
    val isLoading: Boolean,
    val errors: List<String>,
    val guideLoaded: Boolean
) {
    val channelCount: Int get() = groups.sumOf { it.channels.size }
}

/** Sources, channels, guide, favourites and recents (port of LiveTvRepository, simplified). */
class LiveTvRepository(http: HttpClient, private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val net = LiveTvHttp(http)
    private val xtream = XtreamProvider(net)
    private val stalker = StalkerProvider(net)

    private val _state = MutableStateFlow(LiveTvState(emptyList(), false, emptyList(), false))
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    private var channels: List<LiveTvChannel> = emptyList()
    private var guide: Map<String, List<LiveTvProgramme>> = emptyMap()
    private var lastLoadMs = 0L

    // --- Sources ----------------------------------------------------------------------------

    val sources: List<LiveTvSource>
        get() = store.getString(SOURCES)?.let {
            runCatching { json.decodeFromString(ListSerializer(LiveTvSource.serializer()), it) }.getOrNull()
        }.orEmpty()

    private fun saveSources(list: List<LiveTvSource>) =
        store.putString(SOURCES, json.encodeToString(ListSerializer(LiveTvSource.serializer()), list))

    fun addSource(type: String, name: String, url: String, username: String, password: String, macAddress: String, epgUrl: String): LiveTvSource {
        val source = LiveTvSource(
            id = "src${currentTimeMillis()}",
            type = type,
            name = name.ifBlank { url.substringAfter("://").substringBefore('/') },
            url = url.trim(),
            username = username.trim(),
            password = password.trim(),
            macAddress = macAddress.trim(),
            epgUrl = epgUrl.trim()
        )
        saveSources(sources + source)
        lastLoadMs = 0
        return source
    }

    fun removeSource(id: String) {
        saveSources(sources.filterNot { it.id == id })
        lastLoadMs = 0
    }

    // --- Favourites / recents ---------------------------------------------------------------

    private fun list(key: String): List<String> = store.getString(key)?.let {
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
    }.orEmpty()

    private fun putList(key: String, value: List<String>) =
        store.putString(key, json.encodeToString(ListSerializer(String.serializer()), value))

    fun isFavorite(channel: LiveTvChannel): Boolean = channel.streamUrl in list(FAVORITES)

    fun toggleFavorite(channel: LiveTvChannel): Boolean {
        val current = list(FAVORITES)
        val now = channel.streamUrl !in current
        putList(FAVORITES, if (now) current + channel.streamUrl else current - channel.streamUrl)
        publish()
        return now
    }

    fun markWatched(channel: LiveTvChannel) {
        putList(RECENTS, (listOf(channel.streamUrl) + list(RECENTS).filterNot { it == channel.streamUrl }).take(20))
        publish()
    }

    // --- Loading ----------------------------------------------------------------------------

    /** Loads every source's channels, then the guide. Cached for 30 minutes unless [force]. */
    suspend fun load(force: Boolean) {
        if (!force && channels.isNotEmpty() && currentTimeMillis() - lastLoadMs < 30 * 60_000) return
        val all = sources
        _state.value = _state.value.copy(isLoading = true, errors = emptyList())
        val errors = mutableListOf<String>()
        val epgUrls = LinkedHashMap<String, Map<String, String>>()
        val loaded = coroutineScope {
            all.map { source ->
                async {
                    try {
                        when (source.sourceType) {
                            LiveTvSourceType.M3U -> {
                                val headers = if (source.userAgent.isNotBlank()) mapOf("User-Agent" to source.userAgent) else emptyMap()
                                val parsed = parseM3u(net.text(source.url, headers), source.id, source.userAgent)
                                if (source.epgUrl.isBlank()) parsed.epgUrls.forEach { epgUrls[it] = headers }
                                parsed.channels
                            }
                            LiveTvSourceType.XTREAM -> xtream.channels(source).also {
                                if (source.epgUrl.isBlank()) epgUrls[xtream.guideUrl(source)] = emptyMap()
                            }
                            LiveTvSourceType.STALKER -> stalker.channels(source)
                        }.also { if (source.epgUrl.isNotBlank()) epgUrls[source.epgUrl] = emptyMap() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors += "${source.name}: ${e.message ?: "couldn't load"}"
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        channels = loaded
        lastLoadMs = currentTimeMillis()
        _state.value = LiveTvState(groupsFor(loaded), isLoading = false, errors = errors, guideLoaded = false)
        loadGuide(epgUrls)
    }

    private suspend fun loadGuide(urls: Map<String, Map<String, String>>) {
        if (urls.isEmpty() || channels.isEmpty()) return
        val wanted = channels.map { it.guideKey }.toSet()
        val byName = channels.associate { liveTvNameKey(it.tvgName ?: it.name) to it.guideKey }
        val now = currentTimeMillis()
        val merged = HashMap<String, List<LiveTvProgramme>>()
        for ((url, headers) in urls) {
            try {
                val xml = net.text(url, headers)
                parseXmlTv(xml, wanted, byName, now - 3 * 3_600_000L, now + 24 * 3_600_000L).forEach { (k, v) ->
                    if (merged[k].isNullOrEmpty()) merged[k] = v
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        guide = merged
        _state.value = _state.value.copy(guideLoaded = true)
    }

    private fun groupsFor(list: List<LiveTvChannel>): List<LiveTvGroup> {
        val favorites = list(FAVORITES).toSet()
        val recents = list(RECENTS)
        val byUrl = list.associateBy { it.streamUrl }
        val groups = mutableListOf<LiveTvGroup>()
        recents.mapNotNull(byUrl::get).takeIf { it.isNotEmpty() }?.let { groups += LiveTvGroup(RECENT_GROUP, it) }
        list.filter { it.streamUrl in favorites }.takeIf { it.isNotEmpty() }?.let { groups += LiveTvGroup(FAVORITES_GROUP, it) }
        list.groupBy { it.group.ifBlank { "Uncategorised" } }.forEach { (name, chans) -> groups += LiveTvGroup(name, chans) }
        return groups
    }

    private fun publish() {
        _state.value = _state.value.copy(groups = groupsFor(channels))
    }

    // --- Guide / playback -------------------------------------------------------------------

    fun programmes(channel: LiveTvChannel): List<LiveTvProgramme> {
        val shift = channel.guideShiftMinutes * 60_000L
        return guide[channel.guideKey].orEmpty().map {
            if (shift == 0L) it else it.copy(startEpochMs = it.startEpochMs + shift, stopEpochMs = it.stopEpochMs + shift)
        }
    }

    fun nowPlaying(channel: LiveTvChannel): LiveTvProgramme? {
        val now = currentTimeMillis()
        return programmes(channel).firstOrNull { it.isOnAir(now) }
    }

    fun upNext(channel: LiveTvChannel): LiveTvProgramme? {
        val now = currentTimeMillis()
        return programmes(channel).firstOrNull { it.startEpochMs > now }
    }

    /** A playable URL + headers (Stalker creates a fresh link per play). */
    @Throws(Exception::class)
    suspend fun playback(channel: LiveTvChannel): LiveTvPlayback {
        val source = sources.firstOrNull { it.id == channel.sourceId }
        markWatched(channel)
        if (channel.stalkerCommand != null && source != null) {
            val (url, headers) = stalker.resolve(source, channel)
            return LiveTvPlayback(url, headers, channel.name)
        }
        return LiveTvPlayback(channel.streamUrl, channel.headers, channel.name)
    }

    /** Catch-up playback for a past programme, or null when the channel keeps no archive. */
    fun catchupPlayback(channel: LiveTvChannel, programme: LiveTvProgramme): LiveTvPlayback? {
        val source = sources.firstOrNull { it.id == channel.sourceId }
        val url = catchupUrl(channel, source, programme, currentTimeMillis()) ?: return null
        return LiveTvPlayback(url, channel.headers, "${channel.name} · ${programme.title}")
    }

    companion object {
        const val RECENT_GROUP = "Recently watched"
        const val FAVORITES_GROUP = "Favourites"
        private const val SOURCES = "livetv.sources"
        private const val FAVORITES = "livetv.favorites"
        private const val RECENTS = "livetv.recents"
    }
}

data class LiveTvPlayback(val url: String, val headers: Map<String, String>, val title: String)
