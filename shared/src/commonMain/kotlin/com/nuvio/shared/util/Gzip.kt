package com.nuvio.shared.util

/** Decompresses gzip data (XMLTV guides are often served as .xml.gz). */
expect fun gunzip(data: ByteArray): ByteArray

fun ByteArray.isGzip(): Boolean = size > 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()

/** Offset of the raw DEFLATE stream after a gzip header (RFC 1952). */
internal fun gzipPayloadOffset(data: ByteArray): Int {
    require(data.isGzip()) { "not gzip" }
    val flags = data[3].toInt()
    var pos = 10
    if (flags and 0x04 != 0) { // FEXTRA
        val xlen = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2 + xlen
    }
    if (flags and 0x08 != 0) { while (data[pos].toInt() != 0) pos++; pos++ } // FNAME
    if (flags and 0x10 != 0) { while (data[pos].toInt() != 0) pos++; pos++ } // FCOMMENT
    if (flags and 0x02 != 0) pos += 2 // FHCRC
    return pos
}

/** Unpacks raw DEFLATE data fed in pieces (the platform's zlib). */
internal interface RawInflater {
    /**
     * Unpacks [count] bytes of [input] from [offset]; each unpacked piece goes to [out] (the array
     * is reused, so copy what you keep). Returns false once the compressed data has ended.
     */
    fun feed(input: ByteArray, offset: Int, count: Int, out: (ByteArray, Int) -> Unit): Boolean
    fun close()
}

internal expect fun rawInflater(): RawInflater

/** Length of a gzip header at the start of [data], or null when it hasn't fully arrived yet. */
internal fun gzipHeaderLength(data: ByteArray, size: Int = data.size): Int? {
    if (size < 10) return null
    val flags = data[3].toInt()
    var pos = 10
    if (flags and 0x04 != 0) { // FEXTRA
        if (pos + 2 > size) return null
        pos += 2 + ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8))
    }
    for (flag in intArrayOf(0x08, 0x10)) { // FNAME, FCOMMENT: zero-terminated
        if (flags and flag != 0) {
            while (pos < size && data[pos].toInt() != 0) pos++
            if (pos >= size) return null
            pos++
        }
    }
    if (flags and 0x02 != 0) pos += 2 // FHCRC
    return if (pos <= size) pos else null
}

/**
 * Gzip unpacked piece by piece as it downloads: only the current piece is ever in memory.
 * Unpacked bytes go to [out] (the array is reused).
 */
internal class GunzipStream(private val out: (ByteArray, Int) -> Unit) {
    private var head = ByteArray(0)
    private var inflater: RawInflater? = null
    private var ended = false

    fun feed(bytes: ByteArray, count: Int) {
        if (ended || count <= 0) return
        val current = inflater
        if (current != null) {
            if (!current.feed(bytes, 0, count, out)) ended = true
            return
        }
        // Wait until the whole header is here, then unpack what follows it.
        head += bytes.copyOf(count)
        val start = gzipHeaderLength(head) ?: return
        val started = rawInflater()
        inflater = started
        val rest = head
        head = ByteArray(0)
        if (start < rest.size && !started.feed(rest, start, rest.size - start, out)) ended = true
    }

    fun close() {
        inflater?.close()
        inflater = null
    }
}
