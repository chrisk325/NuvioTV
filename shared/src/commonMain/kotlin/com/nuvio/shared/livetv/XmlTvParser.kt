package com.nuvio.shared.livetv

/**
 * Small streaming-ish XMLTV reader (no XML library in common code): scans `<channel>` and
 * `<programme>` elements and keeps programmes for the wanted guide keys within a time window.
 */
fun parseXmlTv(
    xml: String,
    wantedKeys: Set<String>,
    nameToKey: Map<String, String>,
    windowStartMs: Long,
    windowEndMs: Long
): Map<String, List<LiveTvProgramme>> {
    // Guide channel id → our guide keys (by id, or by display-name match).
    val idToKeys = HashMap<String, MutableSet<String>>()
    val channelRegex = Regex("""<channel\s+id="([^"]*)"[^>]*>(.*?)</channel>""", RegexOption.DOT_MATCHES_ALL)
    val displayName = Regex("""<display-name[^>]*>(.*?)</display-name>""", RegexOption.DOT_MATCHES_ALL)
    for (m in channelRegex.findAll(xml)) {
        val id = m.groupValues[1].trim().lowercase()
        val keys = idToKeys.getOrPut(id) { HashSet() }
        if (id in wantedKeys) keys += id
        displayName.findAll(m.groupValues[2]).forEach { dn ->
            nameToKey[liveTvNameKey(unescape(dn.groupValues[1]))]?.let { keys += it }
        }
    }
    val result = HashMap<String, MutableList<LiveTvProgramme>>()
    val programme = Regex("""<programme\s+([^>]*)>(.*?)</programme>""", RegexOption.DOT_MATCHES_ALL)
    val title = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    val desc = Regex("""<desc[^>]*>(.*?)</desc>""", RegexOption.DOT_MATCHES_ALL)
    val icon = Regex("""<icon\s+src="([^"]+)"""")
    for (m in programme.findAll(xml)) {
        val attrs = parseM3uAttributes(m.groupValues[1])
        val channel = attrs["channel"]?.trim()?.lowercase() ?: continue
        val keys = idToKeys[channel] ?: if (channel in wantedKeys) setOf(channel) else continue
        if (keys.isEmpty()) continue
        val start = parseXmlTvTime(attrs["start"]) ?: continue
        val stop = parseXmlTvTime(attrs["stop"]) ?: (start + 3_600_000)
        if (stop < windowStartMs || start > windowEndMs) continue
        val body = m.groupValues[2]
        val t = title.find(body)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf(String::isNotEmpty) ?: continue
        val p = LiveTvProgramme(
            title = t,
            startEpochMs = start,
            stopEpochMs = stop,
            description = desc.find(body)?.groupValues?.get(1)?.let(::unescape)?.trim()?.take(400),
            image = icon.find(body)?.groupValues?.get(1)
        )
        keys.forEach { result.getOrPut(it) { ArrayList() } += p }
    }
    return result.mapValues { (_, list) -> list.sortedBy { it.startEpochMs }.distinctBy { it.startEpochMs } }
}

/** "20261113203000 +0100" → epoch ms. */
fun parseXmlTvTime(value: String?): Long? {
    val v = value?.trim() ?: return null
    if (v.length < 12) return null
    val y = v.substring(0, 4).toIntOrNull() ?: return null
    val mo = v.substring(4, 6).toIntOrNull() ?: return null
    val d = v.substring(6, 8).toIntOrNull() ?: return null
    val h = v.substring(8, 10).toIntOrNull() ?: return null
    val mi = v.substring(10, 12).toIntOrNull() ?: return null
    val s = if (v.length >= 14) v.substring(12, 14).toIntOrNull() ?: 0 else 0
    val utc = com.nuvio.shared.util.parseReleaseEpochMs(
        "${y.toString().padStart(4, '0')}-${mo.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}T" +
            "${h.toString().padStart(2, '0')}:${mi.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    ) ?: return null
    val tz = Regex("""([+-])(\d{2})(\d{2})""").find(v.substring(minOf(14, v.length)))
    val offsetMs = tz?.let {
        val sign = if (it.groupValues[1] == "-") -1 else 1
        sign * (it.groupValues[2].toInt() * 60 + it.groupValues[3].toInt()) * 60_000L
    } ?: 0L
    return utc - offsetMs
}

private fun unescape(s: String): String =
    s.replace("<![CDATA[", "").replace("]]>", "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&amp;", "&")
