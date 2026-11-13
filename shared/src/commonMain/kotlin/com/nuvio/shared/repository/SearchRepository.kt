package com.nuvio.shared.repository

import com.nuvio.shared.model.Addon
import com.nuvio.shared.model.CatalogRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class SearchRepository(private val catalogs: CatalogRepository) {

    /** Queries every enabled search catalog in parallel; empty and failed catalogs are dropped. */
    @Throws(Exception::class)
    suspend fun search(addons: List<Addon>, query: String): List<CatalogRow> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val slots = addons.filter { it.enabled }.flatMap { addon ->
            addon.searchCatalogs.map { addon to it }
        }
        return coroutineScope {
            slots.map { (addon, catalog) ->
                async {
                    try {
                        catalogs.loadRow(addon, catalog, extraArgs = mapOf("search" to trimmed))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull().filter { it.items.isNotEmpty() }
    }
}
