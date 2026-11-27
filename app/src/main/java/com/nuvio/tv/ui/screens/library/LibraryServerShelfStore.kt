package com.nuvio.tv.ui.screens.library

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Library > Servers shelves last loaded in full, kept on the device so the tab shows them at once after the app
 * starts, while they load again from the servers. One file per profile, tagged with the server setup it was loaded
 * for ([setupKey]); a different setup reads as nothing kept.
 */
@Singleton
class LibraryServerShelfStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** What decides which shelves the tab shows: the profile, and each enabled server's user and selected libraries. */
    fun setupKey(connections: List<ServerConnection>): String =
        "${profileManager.activeProfileId.value}|" + connections.joinToString(",") { connection ->
            "${connection.id}@${connection.remoteUserId}:${connection.useCatalogMetadata}:" +
                connection.selectedLibraries.joinToString("+") { it.id }
        }

    fun read(setupKey: String): List<LibraryServerShelf>? = synchronized(lock) {
        runCatching {
            val file = file().takeIf { it.exists() } ?: return@runCatching null
            json.decodeFromString(StoredShelves.serializer(), file.readText())
                .takeIf { it.version == VERSION && it.setupKey == setupKey }
                ?.shelves
                ?.map(StoredShelf::toShelf)
        }.onFailure { Log.w(TAG, "Reading the kept server shelves failed", it) }.getOrNull()
    }

    /** Keeps [shelves] for [setupKey]; a load in which any shelf failed is not kept, so the last full one stays. */
    fun write(setupKey: String, shelves: List<LibraryServerShelf>) {
        if (shelves.any { it.failure != null }) return
        val stored = StoredShelves(VERSION, setupKey, shelves.map(LibraryServerShelf::toStored))
        synchronized(lock) {
            runCatching {
                val file = file()
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(json.encodeToString(StoredShelves.serializer(), stored))
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }.onFailure { Log.w(TAG, "Keeping the server shelves failed", it) }
        }
    }

    private fun file() = File(context.cacheDir, "library_server_shelves/${profileManager.activeProfileId.value}.json")

    private companion object {
        const val TAG = "LibraryServerShelves"
        const val VERSION = 1
    }
}

@Serializable
private data class StoredShelves(
    val version: Int,
    val setupKey: String,
    val shelves: List<StoredShelf> = emptyList()
)

@Serializable
private data class StoredShelf(
    val key: String,
    val title: String,
    val row: StoredRow? = null
) {
    fun toShelf() = LibraryServerShelf(key, title, row = row?.toRow())
}

@Serializable
private data class StoredRow(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val rawType: String,
    val items: List<StoredPreview> = emptyList(),
    val hasMore: Boolean = false,
    val currentPage: Int = 0,
    val supportsSkip: Boolean = false,
    val skipStep: Int = 100,
    val nextSkip: Int = 0,
    val extraArgs: Map<String, String> = emptyMap()
) {
    fun toRow() = CatalogRow(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = enumOr(type, ContentType.UNKNOWN),
        rawType = rawType,
        items = items.map(StoredPreview::toPreview),
        hasMore = hasMore,
        currentPage = currentPage,
        supportsSkip = supportsSkip,
        skipStep = skipStep,
        nextSkip = nextSkip,
        extraArgs = extraArgs
    )
}

/** The parts of a title a server shelf shows and opens; the rest arrives with the next load. */
@Serializable
private data class StoredPreview(
    val id: String,
    val type: String,
    val rawType: String,
    val name: String,
    val poster: String? = null,
    val posterShape: String = PosterShape.POSTER.name,
    val background: String? = null,
    val logo: String? = null,
    val landscapePoster: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val released: String? = null,
    val imdbId: String? = null,
    val sourceAddonBaseUrl: String? = null
) {
    fun toPreview() = MetaPreview(
        id = id,
        type = enumOr(type, ContentType.UNKNOWN),
        rawType = rawType,
        name = name,
        poster = poster,
        posterShape = enumOr(posterShape, PosterShape.POSTER),
        background = background,
        logo = logo,
        landscapePoster = landscapePoster,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        released = released,
        imdbId = imdbId,
        sourceAddonBaseUrl = sourceAddonBaseUrl
    )
}

private fun LibraryServerShelf.toStored() = StoredShelf(key, title, row?.toStored())

private fun CatalogRow.toStored() = StoredRow(
    addonId = addonId,
    addonName = addonName,
    addonBaseUrl = addonBaseUrl,
    catalogId = catalogId,
    catalogName = catalogName,
    type = type.name,
    rawType = rawType,
    items = items.map(MetaPreview::toStored),
    hasMore = hasMore,
    currentPage = currentPage,
    supportsSkip = supportsSkip,
    skipStep = skipStep,
    nextSkip = nextSkip,
    extraArgs = extraArgs
)

private fun MetaPreview.toStored() = StoredPreview(
    id = id,
    type = type.name,
    rawType = rawType,
    name = name,
    poster = poster,
    posterShape = posterShape.name,
    background = background,
    logo = logo,
    landscapePoster = landscapePoster,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    released = released,
    imdbId = imdbId,
    sourceAddonBaseUrl = sourceAddonBaseUrl
)

private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
    runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
