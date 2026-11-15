package com.nuvio.shared.model

// Ported from app/.../domain/model/CatalogRow.kt
data class CatalogRow(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val items: List<MetaPreview>,
    val hasMore: Boolean = false,
    val supportsSkip: Boolean = false,
    val skipStep: Int = 100,
    val nextSkip: Int = 0,
    val extraArgs: Map<String, String> = emptyMap()
) {
    val apiType: String
        get() = rawType.trim().ifBlank { type.toApiString() }

    val stableKey: String
        get() = "${addonId}_${addonBaseUrl}_${apiType}_$catalogId"

    /** Row title as shown in Nuvio: "Popular - Movie". */
    val displayTitle: String
        get() {
            val label = when (val type = apiType.trim().lowercase()) {
                "movie" -> "Movie"
                "series" -> "Series"
                "tv", "channel", "anime" -> type.replaceFirstChar { it.uppercase() }
                else -> {
                    // Custom catalog types (e.g. "AIOMetadata"): say what the row holds instead.
                    val kinds = items.map { if (it.apiType == "movie") "movie" else "series" }.toSet()
                    when {
                        kinds == setOf("movie") -> "Movies"
                        kinds == setOf("series") -> "Series"
                        kinds.size > 1 -> "Movies & Series"
                        else -> null
                    }
                }
            }
            val name = com.nuvio.shared.repository.catalogNameWithoutAddon(catalogName, addonName)
            return if (label == null) name else "$name - $label"
        }
}
