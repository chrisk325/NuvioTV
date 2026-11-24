package com.nuvio.shared.util

import java.util.zip.GZIPInputStream

actual fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }

internal actual fun rawInflater(): RawInflater = object : RawInflater {
    private val inflater = java.util.zip.Inflater(true)
    private val buffer = ByteArray(256 * 1024)

    override fun feed(input: ByteArray, offset: Int, count: Int, out: (ByteArray, Int) -> Unit): Boolean {
        if (count <= 0) return !inflater.finished()
        inflater.setInput(input, offset, count)
        while (true) {
            val n = inflater.inflate(buffer)
            if (n > 0) out(buffer, n)
            if (inflater.finished()) return false
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return true
        }
    }

    override fun close() = inflater.end()
}
