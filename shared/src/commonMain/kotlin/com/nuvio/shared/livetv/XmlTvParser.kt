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
    // A plain scan with indexOf (no regular expressions over the whole file): big guides parse
    // fast, with little memory and no deep recursion on a background thread's small stack.
    // Guide channel id → our guide keys (by id, or by display-name match).
    val idToKeys = HashMap<String, MutableSet<String>>()
    forEachElement(xml, "channel") { attrs, body ->
        val id = parseM3uAttributes(attrs)["id"]?.trim()?.lowercase() ?: return@forEachElement
        val keys = idToKeys.getOrPut(id) { HashSet() }
        if (id in wantedKeys) keys += id
        forEachElement(body, "display-name") { _, name ->
            nameToKey[liveTvNameKey(unescape(name))]?.let { keys += it }
        }
    }
    val result = HashMap<String, MutableList<LiveTvProgramme>>()
    forEachElement(xml, "programme") { attrText, body ->
        val attrs = parseM3uAttributes(attrText)
        val channel = attrs["channel"]?.trim()?.lowercase() ?: return@forEachElement
        val keys = idToKeys[channel] ?: if (channel in wantedKeys) setOf(channel) else return@forEachElement
        if (keys.isEmpty()) return@forEachElement
        val start = parseXmlTvTime(attrs["start"]) ?: return@forEachElement
        val stop = parseXmlTvTime(attrs["stop"]) ?: (start + 3_600_000)
        if (stop < windowStartMs || start > windowEndMs) return@forEachElement
        val t = innerText(body, "title")?.let(::unescape)?.trim()?.takeIf(String::isNotEmpty) ?: return@forEachElement
        val p = LiveTvProgramme(
            title = t,
            startEpochMs = start,
            stopEpochMs = stop,
            description = innerText(body, "desc")?.let(::unescape)?.trim()?.take(400),
            image = iconSrc(body)
        )
        keys.forEach { result.getOrPut(it) { ArrayList() } += p }
    }
    return result.mapValues { (_, list) -> list.sortedBy { it.startEpochMs }.distinctBy { it.startEpochMs } }
}

/** Calls [block] with each `<tag …>body</tag>` in [text] (attributes text, body); self-closing ones get "". */
private inline fun forEachElement(text: String, tag: String, block: (attrs: String, body: String) -> Unit) {
    val open = "<$tag"
    val close = "</$tag>"
    var from = 0
    while (true) {
        val start = text.indexOf(open, from)
        if (start < 0) return
        val after = start + open.length
        // "<programme" must not match "<programmeX": the tag name has to end here.
        if (after < text.length && !(text[after] == '>' || text[after] == '/' || text[after].isWhitespace())) {
            from = after
            continue
        }
        val tagEnd = text.indexOf('>', after)
        if (tagEnd < 0) return
        if (text[tagEnd - 1] == '/') {
            block(text.substring(after, tagEnd - 1), "")
            from = tagEnd + 1
            continue
        }
        val end = text.indexOf(close, tagEnd + 1)
        if (end < 0) return
        block(text.substring(after, tagEnd), text.substring(tagEnd + 1, end))
        from = end + close.length
    }
}

/** The text inside the first `<tag …>…</tag>` of [body], or null. */
private fun innerText(body: String, tag: String): String? {
    var result: String? = null
    forEachElement(body, tag) { _, inner -> if (result == null) result = inner }
    return result
}

/** The first `<icon src="…">`'s address. */
private fun iconSrc(body: String): String? {
    var result: String? = null
    forEachElement(body, "icon") { attrs, _ ->
        if (result == null) result = parseM3uAttributes(attrs)["src"]?.takeIf { it.isNotBlank() }
    }
    return result
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
