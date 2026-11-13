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
