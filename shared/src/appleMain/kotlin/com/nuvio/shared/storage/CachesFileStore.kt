package com.nuvio.shared.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Larger caches as one file per key in Library/Caches/<folder> (tvOS keeps UserDefaults small).
 * The system may clear Caches when storage runs low; everything here can be downloaded again.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class CachesFileStore(folder: String) : KeyValueStore {
    private val directory: String = run {
        val base = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?: NSTemporaryDirectory()
        val path = "$base/$folder"
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        path
    }

    private fun file(key: String): String = "$directory/" + key.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")

    override fun getString(key: String): String? =
        NSString.stringWithContentsOfFile(file(key), encoding = NSUTF8StringEncoding, error = null)

    override fun putString(key: String, value: String?) {
        if (value == null) {
            NSFileManager.defaultManager.removeItemAtPath(file(key), error = null)
        } else {
            NSString.create(string = value).writeToFile(file(key), atomically = true, encoding = NSUTF8StringEncoding, error = null)
        }
    }
}
