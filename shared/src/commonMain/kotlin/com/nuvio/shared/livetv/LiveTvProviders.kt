package com.nuvio.shared.livetv

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asArray
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.asString
import com.nuvio.shared.data.nonBlank
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.str
import com.nuvio.shared.util.encodeUrlComponent
import com.nuvio.shared.util.gunzip
import com.nuvio.shared.util.isGzip
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal const val MAG_USER_AGENT =
    "Mozilla/5.0 (QtEmbedded; U; Linux; MAG254; en) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3"

internal class LiveTvHttp(private val http: HttpClient) {
    suspend fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val response = http.get(url) { headers.forEach { (k, v) -> header(k, v) } }
        if (!response.status.isSuccess()) throw LiveTvException("HTTP ${response.status.value} from ${url.substringBefore('?')}")
        val body = response.bodyAsBytes()
        return if (body.isGzip()) gunzip(body) else body
    }

    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String = bytes(url, headers).decodeToString()

    suspend fun json(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        AddonJson.parseToJsonElement(text(url, headers).removePrefix("﻿"))
}

/** Port of LiveTvXtream. */
internal class XtreamProvider(private val http: LiveTvHttp) {
    suspend fun channels(source: LiveTvSource): List<LiveTvChannel> {
        val server = source.url.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/')
        val user = source.username.trim()
        val pass = source.password.trim()
        val headers = if (source.userAgent.isNotBlank()) mapOf("User-Agent" to source.userAgent) else emptyMap()
        val base = "$server/player_api.php?username=${encodeUrlComponent(user)}&password=${encodeUrlComponent(pass)}"
        val login = runCatching { http.json(base, headers).asObject() }.getOrNull()
        val formats = login?.obj("user_info")?.get("allowed_output_formats").asArray()?.mapNotNull { it.asString()?.lowercase() }.orEmpty()
        val ext = if (formats.isEmpty() || "ts" in formats || "m3u8" !in formats) "ts" else "m3u8"
        val categories = runCatching {
            http.json("$base&action=get_live_categories", headers).asArray()?.mapNotNull { it.asObject() }
                ?.associate { (it.str("category_id") ?: "") to (it.str("category_name") ?: "") }
        }.getOrNull().orEmpty()
        val streams = http.json("$base&action=get_live_streams", headers).asArray()
            ?: throw LiveTvException("The Xtream server sent no channels")
        val seen = HashSet<String>()
        return streams.mapIndexedNotNull { index, element ->
            val o = element.asObject() ?: return@mapIndexedNotNull null
            val name = o.nonBlank("name") ?: return@mapIndexedNotNull null
            val streamId = o.str("stream_id") ?: return@mapIndexedNotNull null
            val url = "$server/live/${encodeUrlComponent(user)}/${encodeUrlComponent(pass)}/${encodeUrlComponent(streamId)}.$ext"
            if (!seen.add(url)) return@mapIndexedNotNull null
            val archive = o.str("tv_archive").let { it == "true" || (it?.toDoubleOrNull() ?: 0.0) > 0 }
            LiveTvChannel(
                id = "${source.id}-x$streamId-$index",
                name = name,
                streamUrl = url,
                tvgId = o.nonBlank("epg_channel_id"),
                tvgName = null,
                logoUrl = o.nonBlank("stream_icon")?.takeIf { it.startsWith("http") },
                group = o.str("category_id")?.let(categories::get).orEmpty(),
                headers = headers,
                sourceId = source.id,
                catchup = if (archive) LiveTvCatchup(CatchupKind.XTREAM, o.str("tv_archive_duration")?.toIntOrNull()?.coerceIn(1, 30) ?: 1) else null,
                xtreamStreamId = streamId
            )
        }
    }

    /** Xtream panels publish an XMLTV guide at /xmltv.php. */
    fun guideUrl(source: LiveTvSource): String {
        val server = source.url.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/')
        return "$server/xmltv.php?username=${encodeUrlComponent(source.username.trim())}&password=${encodeUrlComponent(source.password.trim())}"
    }
}

/** Port of LiveTvStalker (handshake, profile, genres, channels, per-play create_link). */
internal class StalkerProvider(private val http: LiveTvHttp) {
    private val tokens = HashMap<String, Pair<String, String>>() // source id → (endpoint, token)

    private fun mac(source: LiveTvSource): String {
        val raw = source.macAddress.trim().uppercase()
        val hex = raw.filter { it in '0'..'9' || it in 'A'..'F' }
        return if (hex.length == 12) hex.chunked(2).joinToString(":") else raw
    }

    private fun endpoints(source: LiveTvSource): List<String> {
        val normalized = source.url.trim().trimEnd('/')
        val path = normalized.substringBefore('?')
        if (path.endsWith("portal.php", true) || path.endsWith("load.php", true)) return listOf(normalized)
        val portal = if (path.endsWith("/c", true)) path.dropLast(2) + "/portal.php" else "$path/portal.php"
        val ministra = path.indexOf("/stalker_portal", ignoreCase = true)
        val load = if (ministra >= 0) path.substring(0, ministra + "/stalker_portal".length) + "/server/load.php"
        else (if (path.endsWith("/c", true)) path.dropLast(2) else path) + "/server/load.php"
        return listOf(portal, load).distinct()
    }

    private fun referer(source: LiveTvSource): String {
        val path = source.url.trim().substringBefore('?').trimEnd('/')
        val ministra = path.indexOf("/stalker_portal", ignoreCase = true)
        val base = when {
            ministra >= 0 -> path.substring(0, ministra + "/stalker_portal".length)
            path.endsWith("/portal.php", true) -> path.dropLast("/portal.php".length)
            path.endsWith("/load.php", true) -> path.dropLast("/load.php".length)
            path.endsWith("/c", true) -> path.dropLast(2)
            else -> path
        }
        return "$base/c/"
    }

    fun headers(source: LiveTvSource, token: String?): Map<String, String> = buildMap {
        put("User-Agent", MAG_USER_AGENT)
        put("X-User-Agent", "Model: MAG254; Link: Ethernet")
        put("Referer", referer(source))
        put("Cookie", "mac=${mac(source)}; stb_lang=en; timezone=UTC")
        if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
    }

    private fun url(endpoint: String, source: LiveTvSource, token: String?, type: String, action: String, extra: Map<String, String> = emptyMap()): String {
        val params = buildMap {
            put("type", type); put("action", action); put("JsHttpRequest", "1-xml")
            if (!token.isNullOrBlank()) put("token", token)
            if (source.username.isNotBlank()) put("login", source.username)
            if (source.password.isNotBlank()) put("password", source.password)
            putAll(extra)
        }
        return endpoint + params.entries.joinToString("&", prefix = if ('?' in endpoint) "&" else "?") {
            "${encodeUrlComponent(it.key)}=${encodeUrlComponent(it.value)}"
        }
    }

    private suspend fun session(source: LiveTvSource, fresh: Boolean = false): Pair<String, String> {
        if (!fresh) tokens[source.id]?.let { return it }
        var failure: Exception? = null
        for (endpoint in endpoints(source)) {
            try {
                val js = http.json(url(endpoint, source, null, "stb", "handshake"), headers(source, null)).asObject()
                val token = (js?.obj("js") ?: js)?.nonBlank("token") ?: throw LiveTvException("The portal didn't accept this MAC address")
                runCatching { http.text(url(endpoint, source, token, "stb", "get_profile"), headers(source, token)) }
                return (endpoint to token).also { tokens[source.id] = it }
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: LiveTvException("Couldn't reach the portal")
    }

    private fun data(element: JsonElement?): List<JsonObject> {
        val root = element.asObject()
        val js = root?.get("js")
        js.asArray()?.let { arr -> return arr.mapNotNull { it.asObject() } }
        val holder = js.asObject() ?: root
        return holder?.get("data").asArray()?.mapNotNull { it.asObject() }.orEmpty()
    }

    suspend fun channels(source: LiveTvSource): List<LiveTvChannel> {
        suspend fun load(fresh: Boolean): List<LiveTvChannel> {
            val (endpoint, token) = session(source, fresh)
            val h = headers(source, token)
            val genres = runCatching {
                data(http.json(url(endpoint, source, token, "itv", "get_genres"), h)).associate {
                    (it.str("id") ?: "") to (it.str("title") ?: it.str("name") ?: "")
                }
            }.getOrNull().orEmpty()
            var entries = runCatching { data(http.json(url(endpoint, source, token, "itv", "get_all_channels"), h)) }.getOrNull().orEmpty()
            if (entries.isEmpty()) {
                val all = ArrayList<JsonObject>()
                var page = 1
                while (page <= 200) {
                    val batch = data(http.json(url(endpoint, source, token, "itv", "get_ordered_list", mapOf("p" to page.toString())), h))
                    if (batch.isEmpty()) break
                    all += batch
                    page++
                }
                entries = all
            }
            return entries.mapIndexedNotNull { index, o ->
                val name = o.nonBlank("name") ?: o.nonBlank("title") ?: return@mapIndexedNotNull null
                val cmd = o.nonBlank("cmd") ?: o.nonBlank("url") ?: return@mapIndexedNotNull null
                val link = playable(cmd).takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
                LiveTvChannel(
                    id = "${source.id}-s${o.str("id") ?: index}",
                    name = name,
                    streamUrl = link,
                    tvgId = o.nonBlank("xmltv_id"),
                    tvgName = null,
                    logoUrl = o.nonBlank("logo")?.let { logo(source, it) },
                    group = (o.str("tv_genre_id") ?: o.str("genre_id"))?.let(genres::get).orEmpty(),
                    headers = h,
                    sourceId = source.id,
                    stalkerCommand = cmd
                )
            }
        }
        val first = runCatching { load(fresh = false) }.getOrDefault(emptyList())
        return first.ifEmpty { load(fresh = true) }
    }

    /** Stalker links are created per play and expire. */
    suspend fun resolve(source: LiveTvSource, channel: LiveTvChannel): Pair<String, Map<String, String>> {
        val command = channel.stalkerCommand ?: return channel.streamUrl to channel.headers
        for (fresh in listOf(false, true)) {
            val (endpoint, token) = session(source, fresh)
            val result = runCatching {
                val root = http.json(url(endpoint, source, token, "itv", "create_link", mapOf("cmd" to command)), headers(source, token)).asObject()
                val js = root?.obj("js") ?: root
                (js?.nonBlank("cmd") ?: js?.nonBlank("url"))?.let(::playable)
            }.getOrNull()
            if (!result.isNullOrBlank()) return result to headers(source, token)
        }
        throw LiveTvException("The portal didn't create a link for ${channel.name}")
    }

    private fun playable(cmd: String): String {
        val parts = cmd.trim().split(' ', '\t').filter(String::isNotBlank)
        return (parts.firstOrNull { "://" in it } ?: parts.lastOrNull()).orEmpty()
    }

    private fun logo(source: LiveTvSource, value: String): String? {
        val logo = value.trim()
        val url = source.url.trim()
        val scheme = url.indexOf("://")
        val origin = if (scheme > 0) url.indexOf('/', scheme + 3).let { if (it < 0) url.trimEnd('/') else url.substring(0, it) } else null
        return when {
            logo.isEmpty() -> null
            logo.startsWith("http") -> logo
            logo.startsWith("//") -> "http:$logo"
            logo.startsWith("/") -> origin?.let { it + logo }
            "://" in logo -> null
            else -> origin?.let { "$it/stalker_portal/misc/logos/320/$logo" }
        }
    }
}

/**
 * Catch-up (archive) link for a past programme. Port of LiveTvCatchupLinks: Xtream timeshift,
 * shift (?utc=…&lutc=…), append / default templates and Flussonic.
 */
fun catchupUrl(channel: LiveTvChannel, source: LiveTvSource?, programme: LiveTvProgramme, nowMs: Long): String? {
    val catchup = channel.catchup ?: return null
    val startS = programme.startEpochMs / 1000
    val endS = programme.stopEpochMs / 1000
    val durationS = (endS - startS).coerceAtLeast(60)
    val nowS = nowMs / 1000
    fun fill(template: String): String {
        var t = template
        val dt = com.nuvio.shared.util.utcFields(startS)
        val map = mapOf(
            "{utc}" to "$startS", "\${start}" to "$startS", "{start}" to "$startS", "{utcend}" to "$endS", "\${end}" to "$endS",
            "{end}" to "$endS", "{lutc}" to "$nowS", "\${now}" to "$nowS", "\${timestamp}" to "$nowS", "{duration}" to "$durationS",
            "\${duration}" to "$durationS", "{offset}" to "${(nowS - startS) / 60}", "{Y}" to dt[0], "{m}" to dt[1], "{d}" to dt[2],
            "{H}" to dt[3], "{M}" to dt[4], "{S}" to dt[5]
        )
        map.forEach { (k, v) -> t = t.replace(k, v) }
        return t
    }
    return when (catchup.kind) {
        CatchupKind.XTREAM -> {
            val src = source ?: return null
            val server = src.url.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/')
            val streamId = channel.xtreamStreamId ?: return null
            val dt = com.nuvio.shared.util.utcFields(startS)
            val start = "${dt[0]}-${dt[1]}-${dt[2]}:${dt[3]}-${dt[4]}"
            "$server/timeshift/${encodeUrlComponent(src.username)}/${encodeUrlComponent(src.password)}/${durationS / 60}/$start/$streamId.ts"
        }
        CatchupKind.SHIFT -> channel.streamUrl + (if ('?' in channel.streamUrl) "&" else "?") + "utc=$startS&lutc=$nowS"
        CatchupKind.APPEND -> channel.streamUrl + fill(catchup.template.orEmpty())
        CatchupKind.DEFAULT -> catchup.template?.let { fill(it) }?.let { if ("://" in it) it else channel.streamUrl + it }
        CatchupKind.FLUSSONIC -> {
            val base = channel.streamUrl.substringBeforeLast('/')
            if (channel.streamUrl.contains(".m3u8")) "$base/index-$startS-$durationS.m3u8" else "$base/timeshift_abs-$startS.ts"
        }
    }
}
