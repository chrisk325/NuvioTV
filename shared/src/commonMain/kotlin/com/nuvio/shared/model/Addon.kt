package com.nuvio.shared.model

// Ported from app/.../domain/model/Addon.kt and CatalogDescriptorExtensions.kt
data class Addon(
    val id: String,
    val name: String,
    val displayName: String = name,
    val version: String,
    val description: String?,
    val logo: String?,
    val background: String? = null,
    /** Canonical transport URL without the trailing /manifest.json (may carry a query string). */
    val baseUrl: String,
    val catalogs: List<CatalogDescriptor>,
    val types: List<ContentType>,
    val rawTypes: List<String> = types.map { it.toApiString() },
    val resources: List<AddonResource>,
    val idPrefixes: List<String> = emptyList(),
    val behaviorHints: AddonBehaviorHints? = null,
    val manifestLanguage: String? = null,
    val enabled: Boolean = true
) {
    val manifestUrl: String
        get() {
            val queryStart = baseUrl.indexOf('?')
            return if (queryStart >= 0) {
                baseUrl.substring(0, queryStart) + "/manifest.json" + baseUrl.substring(queryStart)
            } else {
                "$baseUrl/manifest.json"
            }
        }

    fun hasResource(name: String): Boolean = resources.any { it.name == name }

    /** Port of core/streams/PlaybackAvailability.kt `supportsResource`. */
    fun supportsResource(name: String, type: String, id: String): Boolean =
        resources.any { resource ->
            resource.name == name &&
                (resource.types.isEmpty() || resource.types.contains(type)) &&
                run {
                    val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                        ?: idPrefixes.takeIf { it.isNotEmpty() }
                    prefixes == null || prefixes.any { id.startsWith(it) }
                }
        }

    /** Catalogs that can be shown on the home screen (no required extras such as search/genre). */
    val homeCatalogs: List<CatalogDescriptor>
        get() = catalogs.filter { it.isHomeEligible() }

    /** Catalogs that accept a `search` extra. */
    val searchCatalogs: List<CatalogDescriptor>
        get() = catalogs.filter { it.supportsExtra("search") }
}

fun List<Addon>.enabledAddons(): List<Addon> = filter { it.enabled }

data class CatalogDescriptor(
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val id: String,
    val name: String,
    val extra: List<CatalogExtra> = emptyList(),
    val pageSize: Int? = null,
    val showInHome: Boolean = false,
    val hasExplicitShowInHome: Boolean = false,
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList()
) {
    val apiType: String
        get() = rawType.trim().ifBlank { type.toApiString() }

    /** True when the catalog is only usable through search (it has a required "search" extra). */
    fun isSearchOnlyCatalog(): Boolean =
        extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired } ||
            extraRequired.any { it.equals("search", ignoreCase = true) }

    fun hasRequiredExtras(): Boolean = extra.any { it.isRequired } || extraRequired.isNotEmpty()

    fun isHomeEligible(): Boolean {
        if (hasExplicitShowInHome && !showInHome) return false
        return !hasRequiredExtras()
    }

    /**
     * Whether this catalog supports a given extra argument (e.g. "skip", "search", "genre").
     * Manifests declare extras either in full form (`extra: [{name}]`) or short form
     * (`extraSupported` / `extraRequired`); both are checked.
     */
    fun supportsExtra(name: String): Boolean {
        if (extra.any { it.name.equals(name, ignoreCase = true) }) return true
        if (extraSupported.any { it.equals(name, ignoreCase = true) }) return true
        if (extraRequired.any { it.equals(name, ignoreCase = true) }) return true
        return false
    }

    fun skipStep(defaultStep: Int = DEFAULT_SKIP_STEP): Int {
        if (pageSize != null && pageSize > 0) return pageSize
        val skipExtra = extra.firstOrNull { it.name.equals("skip", ignoreCase = true) }
        val numericOptions = skipExtra?.options
            .orEmpty()
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }
            .distinct()
            .sorted()
        if (numericOptions.size >= 2) {
            val step = numericOptions
                .zipWithNext()
                .mapNotNull { (a, b) -> (b - a).takeIf { it > 0 } }
                .minOrNull()
            if (step != null && step > 0) return step
        }
        return defaultStep
    }

    private companion object {
        const val DEFAULT_SKIP_STEP = 100
    }
}

data class CatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null,
    val defaultValue: String? = null,
    val optionsLimit: Int? = null
)

data class AddonResource(
    val name: String,
    val types: List<String>,
    val idPrefixes: List<String>?
)

data class AddonBehaviorHints(
    val configurable: Boolean? = null,
    val configurationRequired: Boolean? = null,
    val newEpisodeNotifications: Boolean? = null
)
