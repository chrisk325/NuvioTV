package com.nuvio.shared.livetv

/** Parsed M3U playlist: channels plus guide links from the #EXTM3U header. */
data class ParsedM3u(val channels: List<LiveTvChannel>, val epgUrls: List<String>)

private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)=("([^"]*)"|'([^']*)'|([^\s,]+))""")

internal fun parseM3uAttributes(line: String): Map<String, String> =
    ATTRIBUTE.findAll(line).associate { m ->
        m.groupValues[1].lowercase() to (m.groups[3]?.value ?: m.groups[4]?.value ?: m.groups[5]?.value ?: "").trim()
    }

/** Port of LiveTvPlaylistParser.parseM3uPlaylist (EXTINF attributes, EXTGRP, EXTVLCOPT/EXTHTTP headers, catch-up). */
fun parseM3u(text: String, sourceId: String, userAgent: String = ""): ParsedM3u {
    val channels = ArrayList<LiveTvChannel>()
    val seen = HashSet<String>()
    val epg = LinkedHashSet<String>()
    var attrs: Map<String, String>? = null
    var name: String? = null
    var group: String? = null
    var headers = emptyMap<String, String>()
    var defaultCatchup = emptyMap<String, String>()
    var defaultShift = 0
    val baseHeaders = if (userAgent.isNotBlank()) mapOf("User-Agent" to userAgent) else emptyMap()

    for (raw in text.lineSequence()) {
        val line = raw.trim().removePrefix("﻿")
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                val a = parseM3uAttributes(line)
                defaultCatchup = a.filterKeys { it in CATCHUP_KEYS }
                defaultShift = shiftMinutes(a["tvg-shift"]) ?: 0
                listOfNotNull(a["url-tvg"], a["x-tvg-url"], a["tvg-url"])
                    .flatMap { it.split(',', ';') }.map(String::trim)
                    .filter { it.startsWith("http", ignoreCase = true) }
                    .forEach(epg::add)
            }
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                attrs = parseM3uAttributes(line.substringBefore(',', line))
                name = line.substringAfterLast(',', "").trim().ifBlank { null }
            }
            line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim()
            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> headers = headers + ("User-Agent" to line.substringAfter('=').trim())
            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) || line.startsWith("#EXTVLCOPT:http-referer=", ignoreCase = true) ->
                headers = headers + ("Referer" to line.substringAfter('=').trim())
            line.startsWith("#EXTVLCOPT:http-origin=", ignoreCase = true) -> headers = headers + ("Origin" to line.substringAfter('=').trim())
            line.startsWith("#EXTHTTP:", ignoreCase = true) -> headers = headers + parseExtHttp(line.substringAfter(':'))
            line.startsWith("#") -> Unit
            else -> {
                val a = attrs.orEmpty()
                val url = line.substringBefore('|').trim()
                val urlHeaders = line.substringAfter('|', "").split('&').mapNotNull { pair ->
                    val k = pair.substringBefore('=').trim()
                    val v = pair.substringAfter('=', "").trim()
                    if (k.isEmpty() || v.isEmpty()) null else k to v
                }.toMap()
                val currentName = name ?: a["tvg-name"] ?: "Channel ${channels.size + 1}"
                val currentGroup = a["group-title"]?.takeIf(String::isNotBlank) ?: group.orEmpty()
                val entryHeaders = baseHeaders + headers + urlHeaders
                attrs = null; name = null; group = null; headers = emptyMap()
                if ("://" !in url || !seen.add(url)) continue
                if (currentName.trim().matches(Regex("""^[#=*\-~ ]{3,}.*[#=*\-~ ]{3,}$"""))) continue // "#### NEWS ####"
                channels += LiveTvChannel(
                    id = "$sourceId-m${channels.size}",
                    name = currentName,
                    streamUrl = url,
                    tvgId = a["tvg-id"]?.takeIf(String::isNotBlank),
                    tvgName = a["tvg-name"]?.takeIf { it.isNotBlank() && it != currentName },
                    logoUrl = a["tvg-logo"]?.takeIf { it.startsWith("http", ignoreCase = true) },
                    group = currentGroup,
                    headers = entryHeaders,
                    sourceId = sourceId,
                    catchup = m3uCatchup(a.filterKeys { it in CATCHUP_KEYS }, defaultCatchup),
                    guideShiftMinutes = shiftMinutes(a["tvg-shift"]) ?: defaultShift
                )
            }
        }
    }
    return ParsedM3u(channels, epg.toList())
}

private val CATCHUP_KEYS = setOf("catchup", "catchup-type", "catchup-days", "catchup-source", "tvg-rec", "timeshift")

private fun shiftMinutes(value: String?): Int? {
    val hours = value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
    if (hours < -24 || hours > 24) return null
    return kotlin.math.round(hours * 60).toInt()
}

private fun parseExtHttp(json: String): Map<String, String> =
    Regex(""""([^"]+)"\s*:\s*"([^"]*)"""").findAll(json).associate { it.groupValues[1] to it.groupValues[2] }

/** Port of m3uCatchup. */
internal fun m3uCatchup(entry: Map<String, String>, playlist: Map<String, String>): LiveTvCatchup? {
    if (entry.isEmpty() && playlist.isEmpty()) return null
    fun v(n: String) = entry[n]?.takeIf(String::isNotBlank) ?: playlist[n]?.takeIf(String::isNotBlank)
    val type = (v("catchup") ?: v("catchup-type"))?.trim()?.lowercase()
    val days = (v("catchup-days") ?: v("tvg-rec") ?: v("timeshift"))?.trim()?.toIntOrNull()
    val template = v("catchup-source")?.trim()
    if (type == null && (days ?: 0) <= 0) return null
    if (type == "disabled" || type == "none" || type == "0" || days == 0) return null
    val kind = when (type) {
        null, "default", "1" -> if (template == null) CatchupKind.SHIFT else CatchupKind.DEFAULT
        "append" -> CatchupKind.APPEND
        "shift", "timeshift" -> CatchupKind.SHIFT
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> CatchupKind.FLUSSONIC
        "xc", "xtream" -> CatchupKind.XTREAM
        else -> if (template != null) CatchupKind.DEFAULT else return null
    }
    if ((kind == CatchupKind.DEFAULT || kind == CatchupKind.APPEND) && template == null) return null
    return LiveTvCatchup(kind, (days ?: 1).coerceIn(1, 30), template)
}
