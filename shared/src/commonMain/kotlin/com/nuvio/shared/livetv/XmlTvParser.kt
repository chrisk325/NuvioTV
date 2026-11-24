package com.nuvio.shared.livetv

/** Parses a whole XMLTV document (kept for small guides and tests); see [XmlTvStreamParser]. */
fun parseXmlTv(
    xml: String,
    wantedKeys: Set<String>,
    nameToKey: Map<String, String>,
    windowStartMs: Long,
    windowEndMs: Long
): Map<String, List<LiveTvProgramme>> =
    XmlTvStreamParser(wantedKeys, nameToKey, windowStartMs, windowEndMs).apply { feed(xml) }.finish()

/**
 * XMLTV read piece by piece: [feed] it text as it downloads; complete `<channel>` and
 * `<programme>` elements are processed and dropped at once, so only the unfinished tail and the
 * programmes kept (wanted channels, inside the time window) stay in memory — never the file.
 * Channels come before programmes in XMLTV; a programme for a channel not seen yet still matches
 * by its id.
 */
class XmlTvStreamParser(
    private val wantedKeys: Set<String>,
    private val nameToKey: Map<String, String>,
    private val windowStartMs: Long,
    private val windowEndMs: Long
) {
    // Guide channel id → our guide keys (by id, or by display-name match).
    private val idToKeys = HashMap<String, MutableSet<String>>()
    private val result = HashMap<String, MutableList<LiveTvProgramme>>()
    private val pending = StringBuilder()

    fun feed(text: String) {
        if (text.isEmpty()) return
        pending.append(text)
        drain()
    }

    fun finish(): Map<String, List<LiveTvProgramme>> {
        drain()
        pending.clear()
        return result.mapValues { (_, list) -> list.sortedBy { it.startEpochMs }.distinctBy { it.startEpochMs } }
    }

    /** Handles every complete element in [pending] and keeps only what may still be incomplete. */
    private fun drain() {
        var pos = 0
        val text = pending
        while (true) {
            val channelAt = indexOfTag(text, "<channel", pos)
            val programmeAt = indexOfTag(text, "<programme", pos)
            val start = when {
                channelAt < 0 -> programmeAt
                programmeAt < 0 -> channelAt
                else -> minOf(channelAt, programmeAt)
            }
            if (start < 0) {
                // Nothing starts here; keep a short tail in case a tag name was cut in two.
                pos = maxOf(pos, text.length - 16)
                break
            }
            val isChannel = start == channelAt
            val nameLength = if (isChannel) "<channel".length else "<programme".length
            val tagEnd = text.indexOf(">", start + nameLength)
            if (tagEnd < 0) { pos = start; break }
            val attrs: String
            val body: String
            val next: Int
            if (text[tagEnd - 1] == '/') {
                attrs = text.substring(start + nameLength, tagEnd - 1)
                body = ""
                next = tagEnd + 1
            } else {
                val close = if (isChannel) "</channel>" else "</programme>"
                val end = text.indexOf(close, tagEnd + 1)
                if (end < 0) { pos = start; break }
                attrs = text.substring(start + nameLength, tagEnd)
                body = text.substring(tagEnd + 1, end)
                next = end + close.length
            }
            if (isChannel) channel(attrs, body) else programme(attrs, body)
            pos = next
        }
        if (pos > 0) pending.deleteRange(0, minOf(pos, pending.length))
    }

    private fun channel(attrText: String, body: String) {
        val id = parseM3uAttributes(attrText)["id"]?.trim()?.lowercase() ?: return
        val keys = idToKeys.getOrPut(id) { HashSet() }
        if (id in wantedKeys) keys += id
        forEachElement(body, "display-name") { _, name ->
            nameToKey[liveTvNameKey(unescape(name))]?.let { keys += it }
        }
    }

    private fun programme(attrText: String, body: String) {
        val attrs = parseM3uAttributes(attrText)
        val channel = attrs["channel"]?.trim()?.lowercase() ?: return
        val keys = idToKeys[channel] ?: if (channel in wantedKeys) setOf(channel) else return
        if (keys.isEmpty()) return
        val start = parseXmlTvTime(attrs["start"]) ?: return
        val stop = parseXmlTvTime(attrs["stop"]) ?: (start + 3_600_000)
        if (stop < windowStartMs || start > windowEndMs) return
        val t = innerText(body, "title")?.let(::unescape)?.trim()?.takeIf(String::isNotEmpty) ?: return
        val p = LiveTvProgramme(
            title = t,
            startEpochMs = start,
            stopEpochMs = stop,
            description = innerText(body, "desc")?.let(::unescape)?.trim()?.take(400),
            image = iconSrc(body)
        )
        keys.forEach { result.getOrPut(it) { ArrayList() } += p }
    }
}

/** Where `<name` starts as a whole tag name (not "<programmeX"), from [from]; -1 if not found. */
private fun indexOfTag(text: CharSequence, open: String, from: Int): Int {
    var at = from
    while (true) {
        val i = text.indexOf(open, at)
        if (i < 0) return -1
        val after = i + open.length
        if (after >= text.length) return i // may be cut off: the caller waits for more text
        val c = text[after]
        if (c == '>' || c == '/' || c.isWhitespace()) return i
        at = after
    }
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
