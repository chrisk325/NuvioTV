package com.nuvio.shared.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
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
