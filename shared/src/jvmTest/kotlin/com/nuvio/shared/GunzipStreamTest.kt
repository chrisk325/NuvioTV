package com.nuvio.shared

import com.nuvio.shared.livetv.Utf8ChunkDecoder
import com.nuvio.shared.util.GunzipStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class GunzipStreamTest {
    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @Test
    fun unpacksPieceByPiece() {
        val text = buildString {
            repeat(20_000) { append("<programme channel=\"c$it\"><title>Café ☕ $it</title></programme>\n") }
        }
        val packed = gzip(text.encodeToByteArray())
        for (size in listOf(1, 7, 4096, 64 * 1024)) {
            val result = StringBuilder()
            val decoder = Utf8ChunkDecoder()
            val stream = GunzipStream { bytes, count -> result.append(decoder.decode(bytes, count)) }
            var offset = 0
            while (offset < packed.size) {
                val n = minOf(size, packed.size - offset)
                stream.feed(packed.copyOfRange(offset, offset + n), n)
                offset += n
            }
            stream.close()
            result.append(decoder.finish())
            assertEquals(text, result.toString(), "piece size $size")
        }
    }
}
