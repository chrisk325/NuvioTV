package com.nuvio.shared.tracking

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asArray
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.bool
import com.nuvio.shared.data.int
import com.nuvio.shared.data.long
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.objects
import com.nuvio.shared.data.str
import com.nuvio.shared.util.encodeUrlComponent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** "intro", "recap", "outro". Times in seconds. */
data class SkipSegment(val type: String, val startSeconds: Double, val endSeconds: Double) {
    val label: String
        get() = when (type) {
            "recap" -> "Skip recap"
            "outro" -> "Skip credits"
            else -> "Skip intro"
        }
}

/** Port of the Android skip-intro sources: IntroDB (IMDb ids) and AniSkip (anime via MAL ids). */
class SkipSegmentsService(private val http: HttpClient, introDbBaseUrl: String) {
    private val introDb = introDbBaseUrl.trim().trimEnd('/')

    @Throws(Exception::class)
    suspend fun segments(media: TrackedMedia, episodeLengthSeconds: Double): List<SkipSegment> {
        val fromIntroDb = media.imdbId?.let { introDbSegments(it, media) }.orEmpty()
        if (fromIntroDb.isNotEmpty()) return fromIntroDb
        if (media.kitsuId != null && media.episode != null) return aniSkip(media, episodeLengthSeconds)
        return emptyList()
    }

    private suspend fun introDbSegments(imdbId: String, media: TrackedMedia): List<SkipSegment> {
        if (introDb.isEmpty()) return emptyList()
        val query = buildString {
            append("$introDb/segments?imdb_id=${encodeUrlComponent(imdbId)}")
            if (media.isEpisode) append("&season=${media.season}&episode=${media.episode}") else append("&is_movie=true")
        }
        val body = fetch(query).asObject() ?: return emptyList()
        return listOf("recap", "intro", "outro").mapNotNull { type ->
            val seg = body.obj(if (type == "outro") "outro" else type) ?: return@mapNotNull null
            val start = seg.str("start_sec")?.toDoubleOrNull() ?: seg.long("start_ms")?.div(1000.0) ?: return@mapNotNull null
            val end = seg.str("end_sec")?.toDoubleOrNull() ?: seg.long("end_ms")?.div(1000.0) ?: return@mapNotNull null
            if (end > start) SkipSegment(type, start, end) else null
        }
    }

    private suspend fun aniSkip(media: TrackedMedia, length: Double): List<SkipSegment> {
        val mapping = fetch("https://arm.haglund.dev/api/v2/ids?source=kitsu&id=${encodeUrlComponent(media.kitsuId!!)}")
        val malId = (mapping.asObject() ?: mapping.asArray()?.firstOrNull().asObject())?.int("myanimelist") ?: return emptyList()
        val body = fetch(
            "https://api.aniskip.com/v2/skip-times/$malId/${media.episode}?types[]=op&types[]=ed&types[]=recap&episodeLength=${length.toInt()}"
        ).asObject() ?: return emptyList()
        if (body.bool("found") != true) return emptyList()
        return body.objects("results").mapNotNull { r ->
            val interval = r.obj("interval") ?: return@mapNotNull null
            val start = interval.str("startTime")?.toDoubleOrNull() ?: return@mapNotNull null
            val end = interval.str("endTime")?.toDoubleOrNull() ?: return@mapNotNull null
            val type = when (r.str("skipType")) { "op", "mixed-op" -> "intro"; "ed", "mixed-ed" -> "outro"; "recap" -> "recap"; else -> return@mapNotNull null }
            SkipSegment(type, start, end)
        }
    }

    private suspend fun fetch(url: String): kotlinx.serialization.json.JsonElement? {
        val response = runCatching { http.get(url) }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching { AddonJson.parseToJsonElement(response.bodyAsText()) }.getOrNull()
    }
}
