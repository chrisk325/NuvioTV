package com.nuvio.shared.repository

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.data.parseManifest
import com.nuvio.shared.model.Addon
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.canonicalizeAddonUrl
import com.nuvio.shared.util.normalizeAddonKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class InstalledAddonRecord(
    val url: String,
    val enabled: Boolean = true,
    /** Last good manifest JSON, so the app starts offline/fast and refreshes in the background. */
    val manifestJson: String? = null
)

class DuplicateAddonException(url: String) : Exception("Add-on already installed: $url")

/**
 * Installed add-ons in user order, persisted in [store]. Mirrors the Android AddonRepositoryImpl
 * behaviour (canonical URLs, default add-ons on first run, cached manifests) without cloud sync.
 */
class AddonRepository(
    private val client: StremioClient,
    private val store: KeyValueStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var records: List<InstalledAddonRecord> = loadRecords()
    private val _addons = MutableStateFlow(records.mapNotNull { it.toAddonOrNull() })

    /** All installed add-ons (enabled and disabled) whose manifest is known. */
    val addons: StateFlow<List<Addon>> = _addons.asStateFlow()

    val currentAddons: List<Addon> get() = _addons.value

    val enabledAddons: List<Addon> get() = _addons.value.filter { it.enabled }

    /** Installed URLs, including ones whose manifest has not loaded yet. */
    val installedUrls: List<String> get() = records.map { it.url }

    private fun loadRecords(): List<InstalledAddonRecord> {
        val raw = store.getString(KEY) ?: return DEFAULT_ADDONS.map { InstalledAddonRecord(it) }
        return runCatching { json.decodeFromString(ListSerializer(InstalledAddonRecord.serializer()), raw) }
            .getOrElse { DEFAULT_ADDONS.map { InstalledAddonRecord(it) } }
    }

    private fun persistAndPublish() {
        store.putString(KEY, json.encodeToString(ListSerializer(InstalledAddonRecord.serializer()), records))
        _addons.value = records.mapNotNull { it.toAddonOrNull() }
    }

    private fun InstalledAddonRecord.toAddonOrNull(): Addon? {
        val manifest = manifestJson ?: return null
        return runCatching { parseManifest(manifest, url).copy(enabled = enabled) }.getOrNull()
    }

    /** Re-downloads every manifest. Failures keep the cached manifest. Returns failed URLs. */
    suspend fun refreshAll(): List<String> {
        val snapshot = mutex.withLock { records }
        val results = coroutineScope {
            snapshot.map { record ->
                async {
                    record.url to try {
                        client.fetchManifestJson(record.url).also { parseManifest(it, record.url) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }.toMap()
        mutex.withLock {
            records = records.map { record ->
                val fresh = results[record.url]
                if (fresh != null) record.copy(manifestJson = fresh) else record
            }
            persistAndPublish()
        }
        return results.filterValues { it == null }.keys.toList()
    }

    /** Validates and installs an add-on from a manifest or transport URL. */
    @Throws(Exception::class)
    suspend fun install(url: String): Addon {
        val canonical = canonicalizeAddonUrl(url)
        require(canonical.startsWith("http://") || canonical.startsWith("https://")) {
            "Add-on URL must start with http:// or https://"
        }
        val key = normalizeAddonKey(canonical)
        if (records.any { normalizeAddonKey(it.url) == key }) throw DuplicateAddonException(canonical)
        val manifest = client.fetchManifestJson(canonical)
        val addon = parseManifest(manifest, canonical)
        mutex.withLock {
            records = records + InstalledAddonRecord(canonical, enabled = true, manifestJson = manifest)
            persistAndPublish()
        }
        return addon
    }

    suspend fun remove(baseUrl: String) = mutex.withLock {
        val key = normalizeAddonKey(baseUrl)
        records = records.filterNot { normalizeAddonKey(it.url) == key }
        persistAndPublish()
    }

    suspend fun setEnabled(baseUrl: String, enabled: Boolean) = mutex.withLock {
        val key = normalizeAddonKey(baseUrl)
        records = records.map { if (normalizeAddonKey(it.url) == key) it.copy(enabled = enabled) else it }
        persistAndPublish()
    }

    /** Moves an add-on up (-1) or down (+1) in priority order. */
    suspend fun move(baseUrl: String, offset: Int) = mutex.withLock {
        val key = normalizeAddonKey(baseUrl)
        val index = records.indexOfFirst { normalizeAddonKey(it.url) == key }
        if (index < 0) return@withLock
        val target = (index + offset).coerceIn(0, records.lastIndex)
        if (target == index) return@withLock
        val list = records.toMutableList()
        val item = list.removeAt(index)
        list.add(target, item)
        records = list
        persistAndPublish()
    }

    companion object {
        private const val KEY = "installed_addons_v1"

        /** Same defaults as AddonPreferences.getDefaultAddons on Android. */
        val DEFAULT_ADDONS = listOf(
            "https://v3-cinemeta.strem.io",
            "https://opensubtitles-v3.strem.io"
        )
    }
}
