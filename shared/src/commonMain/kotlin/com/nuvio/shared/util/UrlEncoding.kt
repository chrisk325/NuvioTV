package com.nuvio.shared.util

/**
 * Same output as `java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")`, which the
 * Android app uses for Stremio path segments and extra args.
 */
fun encodeUrlComponent(value: String): String {
    val bytes = value.encodeToByteArray()
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        val c = (b.toInt() and 0xFF)
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '*') {
            sb.append(ch)
        } else {
            sb.append('%')
            sb.append(HEX[c shr 4])
            sb.append(HEX[c and 0x0F])
        }
    }
    return sb.toString()
}

private const val HEX = "0123456789ABCDEF"

/** Splits an add-on base URL into (path without trailing slash, "?query" or ""). */
internal fun splitAddonBaseUrl(baseUrl: String): Pair<String, String> {
    val trimmed = baseUrl.trim().trimEnd('/')
    val queryStart = trimmed.indexOf('?')
    return if (queryStart >= 0) {
        trimmed.substring(0, queryStart).trimEnd('/') to trimmed.substring(queryStart)
    } else {
        trimmed to ""
    }
}

private const val MANIFEST_SUFFIX = "/manifest.json"

/**
 * Port of AddonRepositoryImpl.canonicalizeUrl: strips whitespace, trailing slashes and a
 * trailing /manifest.json while keeping any query string (configurable add-ons).
 * Also accepts stremio:// links.
 */
fun canonicalizeAddonUrl(url: String): String {
    var trimmed = url.trim().trimEnd('/')
    if (trimmed.startsWith("stremio://", ignoreCase = true)) {
        trimmed = "https://" + trimmed.substring("stremio://".length)
    }
    val queryStart = trimmed.indexOf('?')
    val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
    val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
    val cleanPath = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
        path.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
    } else {
        path.trimEnd('/')
    }
    return cleanPath + query
}

fun normalizeAddonKey(url: String): String = canonicalizeAddonUrl(url).lowercase()
