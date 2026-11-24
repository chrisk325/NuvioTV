package com.nuvio.shared.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.zlib.MAX_WBITS
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream_s
import platform.zlib.zlibVersion
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.create
import platform.Foundation.decompressedDataUsingAlgorithm
import platform.posix.memcpy

/** Foundation's "zlib" algorithm is raw DEFLATE, so the gzip header is skipped first. */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual fun gunzip(data: ByteArray): ByteArray {
    val offset = gzipPayloadOffset(data)
    val payload = data.copyOfRange(offset, data.size - 8) // drop CRC32 + ISIZE trailer
    val nsData = payload.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = payload.size.toULong())
    }
    val out = nsData.decompressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, null)
        ?: throw IllegalStateException("Couldn't decompress guide")
    val size = out.length.toInt()
    val result = ByteArray(size)
    if (size > 0) result.usePinned { memcpy(it.addressOf(0), out.bytes, out.length) }
    return result
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun rawInflater(): RawInflater = ZlibInflater()

/** Raw DEFLATE through the system zlib, a piece at a time. */
@OptIn(ExperimentalForeignApi::class)
private class ZlibInflater : RawInflater {
    private val stream = nativeHeap.alloc<z_stream_s>()
    private val buffer = ByteArray(256 * 1024)
    private var open = false

    init {
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.next_in = null
        stream.avail_in = 0u
        val result = inflateInit2_(stream.ptr, -MAX_WBITS, zlibVersion()!!.toKString(), sizeOf<z_stream_s>().toInt())
        if (result != Z_OK) {
            nativeHeap.free(stream)
            throw IllegalStateException("Couldn't unpack the guide (zlib $result)")
        }
        open = true
    }

    override fun feed(input: ByteArray, offset: Int, count: Int, out: (ByteArray, Int) -> Unit): Boolean {
        if (!open) return false
        if (count <= 0) return true
        var more = true
        input.usePinned { inPin ->
            buffer.usePinned { outPin ->
                stream.next_in = inPin.addressOf(offset).reinterpret()
                stream.avail_in = count.toUInt()
                var going = true
                while (going) {
                    stream.next_out = outPin.addressOf(0).reinterpret()
                    stream.avail_out = buffer.size.toUInt()
                    val result = inflate(stream.ptr, Z_NO_FLUSH)
                    val produced = buffer.size - stream.avail_out.toInt()
                    if (produced > 0) out(buffer, produced)
                    when (result) {
                        Z_STREAM_END -> { more = false; going = false }
                        // A full buffer may mean more output is waiting; otherwise all input is used.
                        Z_OK -> if (stream.avail_out != 0u) going = false
                        Z_BUF_ERROR -> going = false
                        else -> throw IllegalStateException("The guide is damaged (zlib $result)")
                    }
                }
            }
        }
        return more
    }

    override fun close() {
        if (!open) return
        open = false
        inflateEnd(stream.ptr)
        nativeHeap.free(stream)
    }
}
