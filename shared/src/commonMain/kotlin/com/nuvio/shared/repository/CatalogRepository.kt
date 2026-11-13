package com.nuvio.shared.repository

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.model.Addon
import com.nuvio.shared.model.CatalogDescriptor
import com.nuvio.shared.model.CatalogRow

/** A catalog to show, before its items are loaded. */
data class CatalogSlot(
    val addon: Addon,
    val catalog: CatalogDescriptor
) {
    val key: String get() = "${addon.id}_${addon.baseUrl}_${catalog.apiType}_${catalog.id}"
    val title: String get() = "${catalog.name} - ${catalog.apiType.replaceFirstChar { it.uppercase() }}"
}

class CatalogRepository(private val client: StremioClient) {

    /** Home catalogs in add-on order, then manifest order (same ordering the Android home uses by default). */
    fun homeSlots(addons: List<Addon>): List<CatalogSlot> =
        addons.filter { it.enabled }.flatMap { addon ->
            addon.homeCatalogs.map { CatalogSlot(addon, it) }
        }

    @Throws(Exception::class)
    suspend fun loadRow(
        addon: Addon,
        catalog: CatalogDescriptor,
        skip: Int = 0,
        extraArgs: Map<String, String> = emptyMap()
    ): CatalogRow {
        val items = client.fetchCatalog(addon.baseUrl, catalog.apiType, catalog.id, skip, extraArgs)
        val supportsSkip = catalog.supportsExtra("skip")
        return CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.type,
            rawType = catalog.rawType,
            items = items,
            hasMore = supportsSkip && items.isNotEmpty(),
            supportsSkip = supportsSkip,
            skipStep = catalog.skipStep(),
            nextSkip = if (supportsSkip && items.isNotEmpty()) skip + items.size else skip,
            extraArgs = extraArgs
        )
    }

    @Throws(Exception::class)
    suspend fun loadSlot(slot: CatalogSlot, skip: Int = 0): CatalogRow = loadRow(slot.addon, slot.catalog, skip)
}
