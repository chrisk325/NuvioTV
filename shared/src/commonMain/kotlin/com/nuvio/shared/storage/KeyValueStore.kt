package com.nuvio.shared.storage

/**
 * Minimal persistent string store. tvOS uses [UserDefaultsStore]; tests use [InMemoryStore].
 * Swift code can also implement this protocol directly.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class InMemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
