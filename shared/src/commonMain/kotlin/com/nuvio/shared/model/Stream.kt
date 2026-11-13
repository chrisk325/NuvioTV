package com.nuvio.shared.model

// Ported from app/.../domain/model/Stream.kt (debrid/server/plugin fields are Android-only for now)
data class Stream(
    val name: String?,
    val title: String?,
    val description: String?,
    val url: String?,
    val ytId: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val externalUrl: String?,
    val behaviorHints: StreamBehaviorHints?,
    val addonName: String,
    val addonLogo: String?,
    val sources: List<String>? = null,
    val subtitles: List<Subtitle> = emptyList(),
    val clientResolve: StreamClientResolve? = null,
    /** Set when the stream was checked against the user's debrid service. */
    val debridCache: StreamDebridCache? = null,
    /** True when [url] was produced by Nuvio's own debrid resolver. */
    val debridResolved: Boolean = false
) {
    /** Info hash from the stream, its clientResolve block, or a magnet / torrent: URL. */
    fun effectiveInfoHash(): String? =
        infoHash?.takeIf { it.isNotBlank() }
            ?: clientResolve?.infoHash?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(url, externalUrl, clientResolve?.magnetUri).firstNotNullOfOrNull { extractInfoHash(it) }

    fun effectiveFileIdx(): Int? = fileIdx ?: clientResolve?.fileIdx

    /** Torrent the user's debrid service could resolve (tvOS has no torrent engine). */
    val needsDebridResolve: Boolean
        get() = !isDirectlyPlayable && (isTorrent() || clientResolve?.type.equals("debrid", ignoreCase = true) ||
            clientResolve?.type.equals("torrent", ignoreCase = true)) && effectiveInfoHash() != null

    /** Primary playable URL (never a magnet / torrent: link). */
    fun getStreamUrl(): String? =
        listOfNotNull(url, externalUrl).firstOrNull { !it.isMagnetLink() && !it.isTorrentUrl() }

    /** True for torrent-only streams. tvOS has no torrent engine, so these can't be played there. */
    fun isTorrent(): Boolean =
        getStreamUrl().isNullOrBlank() &&
            (!infoHash.isNullOrBlank() || listOfNotNull(url, externalUrl).any { it.isMagnetLink() || it.isTorrentUrl() })

    fun isYouTube(): Boolean = ytId != null

    /** Opens in a browser rather than a player. */
    fun isExternal(): Boolean = externalUrl != null && url == null && !externalUrl.isMagnetLink()

    /** An http(s) URL that a native player can open directly. */
    val directPlaybackUrl: String?
        get() = url?.trim()?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }

    val isDirectlyPlayable: Boolean
        get() = directPlaybackUrl != null

    /** Request headers the add-on asked the player to send. */
    val requestHeaders: Map<String, String>
        get() = behaviorHints?.proxyHeaders?.request.orEmpty()

    fun getDisplayName(): String = name ?: title ?: description ?: "Unknown Stream"

    fun getDisplayDescription(): String? = description ?: title

    /** Rough quality rank for sorting (2160 > 1080 > 720 ...), -1 when unknown. */
    val qualityRank: Int
        get() {
            val text = listOfNotNull(name, title, description).joinToString(" ").lowercase()
            return when {
                "2160" in text || "4k" in text || "uhd" in text -> 2160
                "1440" in text -> 1440
                "1080" in text -> 1080
                "720" in text -> 720
                "480" in text -> 480
                else -> -1
            }
        }

    fun stableKey(occurrence: Int = 0): String = buildString {
        append(addonName); append('\u0000')
        append(url ?: infoHash ?: ytId ?: externalUrl ?: ""); append('\u0000')
        append(fileIdx ?: ""); append('\u0000')
        append(name ?: ""); append('\u0000')
        append(title ?: ""); append('\u0000')
        append(description ?: ""); append('\u0000')
        append(occurrence)
    }
}

data class StreamBehaviorHints(
    val notWebReady: Boolean?,
    val bingeGroup: String?,
    val proxyHeaders: ProxyHeaders?,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null
)

data class ProxyHeaders(
    val request: Map<String, String>?,
    val response: Map<String, String>?
)

data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?,
    val isStreamProvided: Boolean = false,
    val headers: Map<String, String>? = null
)

/** Streams grouped by the add-on that returned them. */
data class AddonStreams(
    val addonName: String,
    val addonLogo: String?,
    val streams: List<Stream>
)

/** Progressive stream loading state delivered to the UI. */
data class StreamsUpdate(
    val groups: List<AddonStreams>,
    val isComplete: Boolean,
    val failedAddons: List<String>
)

/** `clientResolve` block emitted by debrid-aware add-ons (Comet, AIOStreams, …). */
data class StreamClientResolve(
    val type: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val magnetUri: String?,
    val sources: List<String>?,
    val torrentName: String?,
    val filename: String?,
    val season: Int?,
    val episode: Int?,
    val service: String?,
    val isCached: Boolean?
)

data class StreamDebridCache(
    val providerId: String,
    val providerName: String,
    val isCached: Boolean
)

internal fun extractInfoHash(value: String): String? {
    val trimmed = value.trim()
    val candidate = when {
        trimmed.startsWith("magnet:", ignoreCase = true) ->
            trimmed.substringAfter("urn:btih:", "").substringBefore('&').substringBefore('?')
        trimmed.startsWith("torrent:", ignoreCase = true) ->
            trimmed.substringAfter("torrent://").substringAfter("torrent:").substringBefore('?').substringBefore('/')
        else -> return null
    }
    return candidate.takeIf { it.length == 40 || it.length == 32 }
}

private fun String?.isMagnetLink(): Boolean =
    this?.trimStart()?.startsWith("magnet:", ignoreCase = true) == true

private fun String.isTorrentUrl(): Boolean =
    trimStart().startsWith("torrent:", ignoreCase = true)
