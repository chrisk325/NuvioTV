package com.nuvio.shared.trailer

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.int
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.objects
import com.nuvio.shared.data.str
import com.nuvio.shared.util.currentTimeMillis
import com.nuvio.shared.util.encodeUrlComponent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Port of the Android app's InAppYouTubeExtractor (single-URL mode, as its main player uses):
 * asks YouTube's player API, as YouTube's own visionOS / Android / iOS apps, for a video's
 * streams and returns one link carrying picture and sound — the HLS master playlist when there
 * is one (every quality, plays in mpv and AVPlayer), else the best progressive MP4.
 * Backs off while YouTube is rate limiting (429 or the "not a bot" check), like Android.
 */
internal class YouTubeExtractor(
    private val http: HttpClient,
    private val now: () -> Long = { currentTimeMillis() }
) {
    private class Client(
        val key: String,
        val id: String,
        val version: String,
        val userAgent: String,
        val context: Map<String, Any>
    )

    private class RateLimited : Exception()
    private class StaleConfig : Exception()

    private class Config(val apiKey: String, val visitorData: String?, val at: Long, val ttlMs: Long)

    private var config: Config? = null
    private val configLock = Mutex()
    private var strikes = 0
    private var blockedUntil = 0L

    /** A playable link for the YouTube video [videoId] (11 characters), or null. */
    suspend fun singleUrl(videoId: String): String? {
        if (!VIDEO_ID.matches(videoId)) return null
        if (now() < blockedUntil) return null
        val url = try {
            try {
                withTimeout(TIMEOUT_MS) { attempt(videoId, forceConfig = false) }
            } catch (e: StaleConfig) {
                // Every client asked to sign in: a fresh visitor id from the watch page may fix it.
                withTimeout(TIMEOUT_MS) { attempt(videoId, forceConfig = true) }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimited) {
            strikes++
            val pause = (BASE_COOLDOWN_MS shl (strikes - 1).coerceAtMost(4)).coerceAtMost(MAX_COOLDOWN_MS)
            blockedUntil = now() + pause
            null
        } catch (e: Exception) {
            null
        }
        if (url != null) {
            strikes = 0
            blockedUntil = 0L
        }
        return url
    }

    private suspend fun attempt(videoId: String, forceConfig: Boolean): String? {
        val config = watchConfig(forceConfig)
        val manifests = mutableListOf<String>()
        // url, height, has throttling "n" parameter, mp4
        val progressive = mutableListOf<Progressive>()
        var loginRequired = 0
        var botCheck = 0
        for (client in CLIENTS) {
            val response = try {
                player(config, videoId, client)
            } catch (e: RateLimited) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val playability = response.obj("playabilityStatus")
            val status = playability?.str("status")
            if (status == "LOGIN_REQUIRED") {
                val reason = playability?.str("reason").orEmpty()
                when {
                    BOT.containsMatchIn(reason) -> botCheck++
                    AGE.containsMatchIn(reason) -> {}
                    else -> loginRequired++
                }
                continue
            }
            if (status != null && status != "OK") continue
            val streaming = response.obj("streamingData") ?: continue
            streaming.str("hlsManifestUrl")?.takeIf { it.isNotBlank() }?.let { manifests += it }
            for (format in streaming.objects("formats")) {
                val url = format.str("url") ?: continue
                val mime = format.str("mimeType").orEmpty()
                if (mime.isNotBlank() && !mime.contains("video/")) continue
                val height = format.int("height")
                    ?: QUALITY.find(format.str("qualityLabel").orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                progressive += Progressive(url, height, hasN(url), !mime.contains("webm"))
            }
            // The HLS playlist is what we'd use: no need to ask the other clients.
            if (manifests.isNotEmpty()) break
        }
        if (manifests.isEmpty() && progressive.isEmpty()) {
            if (botCheck > 0) throw RateLimited()
            if (loginRequired == CLIENTS.size) {
                this.config = null
                throw StaleConfig()
            }
            return null
        }
        // HLS master playlist first (checked to be one), then the best progressive file.
        for (manifest in manifests) {
            val ok = try {
                val response = http.get(manifest) { DEFAULT_HEADERS.forEach { (k, v) -> header(k, v) } }
                response.status.isSuccess() && response.bodyAsText().trimStart().startsWith("#EXTM3U")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (ok) return manifest
        }
        return progressive
            .sortedWith(compareByDescending<Progressive> { it.height }.thenBy { it.hasN }.thenBy { !it.mp4 })
            .firstOrNull()?.url
    }

    private class Progressive(val url: String, val height: Int, val hasN: Boolean, val mp4: Boolean)

    private suspend fun watchConfig(force: Boolean): Config {
        if (!force) config?.takeIf { now() - it.at < it.ttlMs }?.let { return it }
        return configLock.withLock {
            if (!force) config?.takeIf { now() - it.at < it.ttlMs }?.let { return@withLock it }
            val response = try {
                http.get("https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en") {
                    DEFAULT_HEADERS.forEach { (k, v) -> header(k, v) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (response?.status?.value == 429) throw RateLimited()
            if (response == null || !response.status.isSuccess()) {
                // A consent or bot-check page instead of the watch page: the player API may still
                // answer with the public key; asked again soon.
                val stale = config
                if (stale != null) return@withLock stale
                return@withLock Config(FALLBACK_API_KEY, null, now(), FALLBACK_TTL_MS).also { config = it }
            }
            val html = response.bodyAsText()
            val apiKey = API_KEY.find(html)?.groupValues?.get(1) ?: FALLBACK_API_KEY
            val visitor = VISITOR.find(html)?.groupValues?.get(1)
            Config(apiKey, visitor, now(), CONFIG_TTL_MS).also { config = it }
        }
    }

    private suspend fun player(config: Config, videoId: String, client: Client): JsonObject {
        val body = buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("context") {
                putJsonObject("client") {
                    client.context.forEach { (k, v) ->
                        when (v) {
                            is Int -> put(k, v)
                            else -> put(k, v.toString())
                        }
                    }
                }
            }
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") { put("html5Preference", "HTML5_PREF_WANTS") }
            }
        }
        val response = http.post("https://www.youtube.com/youtubei/v1/player?key=${encodeUrlComponent(config.apiKey)}") {
            header("accept-language", "en-US,en;q=0.9")
            header("origin", "https://www.youtube.com")
            header("x-youtube-client-name", client.id)
            header("x-youtube-client-version", client.version)
            header("User-Agent", client.userAgent)
            config.visitorData?.takeIf { it.isNotBlank() }?.let { header("x-goog-visitor-id", it) }
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        if (response.status.value == 429) throw RateLimited()
        if (!response.status.isSuccess()) error("player API ${client.key}: ${response.status.value}")
        return AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: JsonObject(emptyMap())
    }

    private fun hasN(url: String): Boolean =
        Regex("[?&]n=[^&]+").containsMatchIn(url)

    companion object {
        /** The YouTube video id in a YouTube link, or the id itself. */
        fun videoId(input: String): String? {
            val text = input.trim()
            if (VIDEO_ID.matches(text)) return text
            Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(text)?.let { return it.groupValues[1] }
            Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(text)?.let { return it.groupValues[1] }
            Regex("/(?:embed|shorts|live)/([a-zA-Z0-9_-]{11})").find(text)?.let { return it.groupValues[1] }
            return null
        }

        private val VIDEO_ID = Regex("^[a-zA-Z0-9_-]{11}$")
        private val API_KEY = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
        private val VISITOR = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
        private val QUALITY = Regex("(\\d{2,4})p")
        private val BOT = Regex("\\bbot\\b", RegexOption.IGNORE_CASE)
        private val AGE = Regex("\\bage\\b", RegexOption.IGNORE_CASE)
        private const val FALLBACK_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val TIMEOUT_MS = 30_000L
        private const val CONFIG_TTL_MS = 3 * 60 * 60 * 1000L
        private const val FALLBACK_TTL_MS = 10 * 60 * 1000L
        private const val BASE_COOLDOWN_MS = 60_000L
        private const val MAX_COOLDOWN_MS = 15 * 60_000L

        private val DEFAULT_HEADERS = mapOf(
            "accept-language" to "en-US,en;q=0.9",
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"
        )

        private val CLIENTS = listOf(
            Client(
                key = "visionos", id = "101", version = "1.02",
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                    "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                context = mapOf(
                    "clientName" to "VISIONOS", "clientVersion" to "1.02", "deviceMake" to "Apple",
                    "deviceModel" to "RealityDevice17,1", "osName" to "visionOS",
                    "osVersion" to "26.5.23O471", "hl" to "en", "gl" to "US"
                )
            ),
            Client(
                key = "ios", id = "5", version = "20.10.1",
                userAgent = "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
                context = mapOf(
                    "clientName" to "IOS", "clientVersion" to "20.10.1", "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone", "osVersion" to "17.4.0.21E219", "platform" to "MOBILE",
                    "hl" to "en", "gl" to "US"
                )
            ),
            Client(
                key = "android", id = "3", version = "20.10.35",
                userAgent = "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
                context = mapOf(
                    "clientName" to "ANDROID", "clientVersion" to "20.10.35", "osName" to "Android",
                    "osVersion" to "14", "platform" to "MOBILE", "androidSdkVersion" to 34,
                    "hl" to "en", "gl" to "US"
                )
            )
        )
    }
}
