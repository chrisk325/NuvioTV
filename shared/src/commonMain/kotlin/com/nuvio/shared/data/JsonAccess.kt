package com.nuvio.shared.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Tolerant accessors for Stremio add-on JSON. Add-ons disagree on field types (numbers as
 * strings, strings vs lists, objects vs names), so every read coerces instead of failing.
 */
internal val AddonJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asString(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

internal fun JsonObject.str(key: String): String? = this[key].asString()

internal fun JsonObject.nonBlank(key: String): String? = str(key)?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.int(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull?.toInt() ?: p.doubleOrNull?.toInt() ?: p.content.trim().toIntOrNull()
}

internal fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.trim().toLongOrNull()
}

internal fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.booleanOrNull ?: when (p.content.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

internal fun JsonObject.objects(key: String): List<JsonObject> =
    this[key].asArray()?.mapNotNull { it.asObject() }.orEmpty()

/** List of strings; non-string primitives are stringified. */
internal fun JsonObject.strings(key: String): List<String> =
    this[key].asArray()?.mapNotNull { it.asString() }.orEmpty()

internal fun JsonObject.stringMap(key: String): Map<String, String>? {
    val o = obj(key) ?: return null
    val out = LinkedHashMap<String, String>()
    o.forEach { (k, v) -> v.asString()?.let { out[k] = it } }
    return out
}

/**
 * Port of MetadataFieldMappers.coerceStringList: accepts "a", ["a","b"], [{"name":"a"}] or {"name":"a"}.
 */
internal fun coerceStringList(value: JsonElement?): List<String> {
    return when (value) {
        null, is JsonNull -> emptyList()
        is JsonPrimitive -> listOf(value.content)
        is JsonArray -> value.mapNotNull { entry ->
            when (entry) {
                is JsonObject -> entry.str("name")
                is JsonPrimitive -> entry.asString()
                else -> null
            }
        }
        is JsonObject -> listOfNotNull(value.str("name"))
    }.mapNotNull { it.trim().takeIf(String::isNotBlank) }
}
