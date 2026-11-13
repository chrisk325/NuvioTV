package com.nuvio.shared.storage

import platform.Foundation.NSUserDefaults

class UserDefaultsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val prefix: String = "nuvio."
) : KeyValueStore {
    override fun getString(key: String): String? = defaults.stringForKey(prefix + key)

    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(prefix + key) else defaults.setObject(value, prefix + key)
    }
}
