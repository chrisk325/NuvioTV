package com.nuvio.shared.util

import java.util.zip.GZIPInputStream

actual fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }
