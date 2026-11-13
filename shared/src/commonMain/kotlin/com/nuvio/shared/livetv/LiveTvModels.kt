package com.nuvio.shared.livetv

import kotlinx.serialization.Serializable

// Port of app/.../reshaped/livetv (models, M3U / Xtream / Stalker providers, XMLTV guide, catch-up).

enum class LiveTvSourceType { M3U, XTREAM, STALKER }

@Serializable
data class LiveTvSource(
    val id: String,
    val type: String,          // "m3u" | "xtream" | "stalker"
    val name: String,
    val url: String,           // M3U link, Xtream server or Stalker portal
    val username: String = "",
    val password: String = "",
    val macAddress: String = "",
    val epgUrl: String = "",
    val userAgent: String = ""
) {
    val sourceType: LiveTvSourceType
        get() = when (type) {
            "xtream" -> LiveTvSourceType.XTREAM
            "stalker" -> LiveTvSourceType.STALKER
            else -> LiveTvSourceType.M3U
        }
}

enum class CatchupKind { XTREAM, DEFAULT, APPEND, SHIFT, FLUSSONIC }

data class LiveTvCatchup(val kind: CatchupKind, val days: Int, val template: String? = null)

data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val group: String,
    val headers: Map<String, String>,
    val sourceId: String,
    val catchup: LiveTvCatchup? = null,
    val guideShiftMinutes: Int = 0,
    /** Stalker: the command a fresh link is created from on every play. */
    val stalkerCommand: String? = null,
    /** Xtream stream id (catch-up links). */
    val xtreamStreamId: String? = null
) {
    /** Key the guide is stored under: the tvg-id, else a normalised name. */
    val guideKey: String get() = tvgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: liveTvNameKey(tvgName ?: name)
    val hasCatchup: Boolean get() = catchup != null
}

data class LiveTvProgramme(
    val title: String,
    val startEpochMs: Long,
    val stopEpochMs: Long,
    val description: String? = null,
    val image: String? = null
) {
    fun isOnAir(nowMs: Long) = nowMs in startEpochMs until stopEpochMs
    fun progress(nowMs: Long): Double =
        if (stopEpochMs <= startEpochMs) 0.0 else ((nowMs - startEpochMs).toDouble() / (stopEpochMs - startEpochMs)).coerceIn(0.0, 1.0)
}

data class LiveTvGroup(val name: String, val channels: List<LiveTvChannel>)

class LiveTvException(message: String) : Exception(message)

/** Name normalised for guide matching: lower case, no quality tags or punctuation. */
fun liveTvNameKey(raw: String): String =
    raw.lowercase()
        .replace(Regex("""\b(uhd|fhd|hd|sd|4k|hevc|h265|1080p|720p|backup|\+1)\b"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")
        .trim()
