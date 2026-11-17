package com.nuvio.tv.core.cache

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The content caches the user can turn on, refresh and clear from settings. */
enum class CacheType(
    internal val enabledKey: String,
    internal val directoryName: String,
    /** Share (percent) of the user's maximum cache storage this cache may use. */
    internal val storageSharePercent: Int,
) {
    /** Posters on Home, Discover and Search. */
    Posters("poster_cache_enabled", "poster_cache", 35),

    /** Background artwork (backdrops) on the Home hero, details and streams screens. */
    Backgrounds("background_cache_enabled", "background_cache", 55),

    /** Addon catalog / meta responses and TMDB metadata. */
    Metadata("metadata_cache_enabled", "metadata_cache", 10),
}

/**
 * Settings for the content caches. Each cache can be turned on separately; all of them share one
 * refresh interval, after which cached entries are fetched again on next use.
 *
 * The caches are shared by every profile on the device, so these values are stored in plain
 * device-wide [SharedPreferences] and are intentionally not profile scoped. They are read
 * synchronously from the image and network pipelines, which is why this is not a DataStore.
 */
object CacheSettingsRepository {
    private const val PREFERENCES_NAME = "nuvio_content_cache"

    val refreshIntervalOptions: List<Int> = listOf(12, 24, 36, 48)
    const val DEFAULT_REFRESH_INTERVAL_HOURS = 24
    private const val REFRESH_INTERVAL_KEY = "cache_refresh_interval_hours"

    /** Maximum storage (all caches together) the user can pick, in MB, and the slider step. */
    const val MIN_MAX_STORAGE_MB = 256
    const val MAX_MAX_STORAGE_MB = 10 * 1024
    const val MAX_STORAGE_STEP_MB = 256
    const val DEFAULT_MAX_STORAGE_MB = 2 * 1024
    private const val MAX_STORAGE_KEY = "cache_max_storage_mb"

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    private var baseDirectory: File? = null

    private val _maxStorageMb = MutableStateFlow(DEFAULT_MAX_STORAGE_MB)

    /** Maximum storage all caches may use together, in MB. */
    val maxStorageMb: StateFlow<Int> = _maxStorageMb.asStateFlow()

    private val enabledFlows: Map<CacheType, MutableStateFlow<Boolean>> =
        CacheType.entries.associateWith { MutableStateFlow(false) }

    private val _refreshIntervalHours = MutableStateFlow(DEFAULT_REFRESH_INTERVAL_HOURS)
    val refreshIntervalHours: StateFlow<Int> = _refreshIntervalHours.asStateFlow()

    @Volatile
    private var hasLoaded = false

    /** Call once from [android.app.Application.onCreate], before anything loads images or metadata. */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        baseDirectory = appContext.cacheDir
        hasLoaded = false
        ensureLoaded()
    }

    /** Directory that holds the content caches (each cache uses its own sub directory). */
    internal fun cacheBaseDirectory(): File =
        baseDirectory ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")

    fun ensureLoaded() {
        if (hasLoaded) return
        synchronized(this) {
            if (hasLoaded) return
            val prefs = preferences ?: return
            CacheType.entries.forEach { type ->
                enabledFlows.getValue(type).value = prefs.getBoolean(type.enabledKey, false)
            }
            _refreshIntervalHours.value = prefs.getInt(REFRESH_INTERVAL_KEY, DEFAULT_REFRESH_INTERVAL_HOURS)
                .takeIf { it in refreshIntervalOptions }
                ?: DEFAULT_REFRESH_INTERVAL_HOURS
            _maxStorageMb.value = normalizeMaxStorageMb(
                prefs.getInt(MAX_STORAGE_KEY, DEFAULT_MAX_STORAGE_MB),
            )
            hasLoaded = true
        }
    }

    /** Rounds [mb] to the slider step and clamps it to the allowed range. */
    fun normalizeMaxStorageMb(mb: Int): Int {
        val stepped = ((mb + MAX_STORAGE_STEP_MB / 2) / MAX_STORAGE_STEP_MB) * MAX_STORAGE_STEP_MB
        return stepped.coerceIn(MIN_MAX_STORAGE_MB, MAX_MAX_STORAGE_MB)
    }

    /** The most [type] may store on disk: its share of the user's maximum cache storage. */
    fun maxSizeBytes(type: CacheType): Long = maxSizeBytes(type, maxStorageMbValue())

    /** The most [type] may store on disk for a maximum storage of [maxStorageMb]. */
    fun maxSizeBytes(type: CacheType, maxStorageMb: Int): Long =
        maxStorageMb * 1024L * 1024L * type.storageSharePercent / 100L

    private fun maxStorageMbValue(): Int {
        ensureLoaded()
        return _maxStorageMb.value
    }

    /**
     * Sets the maximum storage all caches may use together. Caches over their new share drop
     * their least recently used entries straight away.
     */
    fun setMaxStorageMb(mb: Int) {
        ensureLoaded()
        val normalized = normalizeMaxStorageMb(mb)
        if (_maxStorageMb.value == normalized) return
        _maxStorageMb.value = normalized
        preferences?.edit()?.putInt(MAX_STORAGE_KEY, normalized)?.apply()
        ImageCache.onStorageLimitChanged()
        MetadataCache.onStorageLimitChanged()
    }

    fun enabled(type: CacheType): StateFlow<Boolean> {
        ensureLoaded()
        return enabledFlows.getValue(type).asStateFlow()
    }

    fun isEnabled(type: CacheType): Boolean {
        ensureLoaded()
        return enabledFlows.getValue(type).value
    }

    val refreshIntervalMillis: Long
        get() {
            ensureLoaded()
            return _refreshIntervalHours.value * 60L * 60L * 1000L
        }

    fun setEnabled(type: CacheType, enabled: Boolean) {
        ensureLoaded()
        val flow = enabledFlows.getValue(type)
        if (flow.value == enabled) return
        flow.value = enabled
        preferences?.edit()?.putBoolean(type.enabledKey, enabled)?.apply()
    }

    fun setRefreshIntervalHours(hours: Int) {
        ensureLoaded()
        if (hours !in refreshIntervalOptions || _refreshIntervalHours.value == hours) return
        _refreshIntervalHours.value = hours
        preferences?.edit()?.putInt(REFRESH_INTERVAL_KEY, hours)?.apply()
        // Entries are re-validated against the new interval on their next read; drop the
        // prefetch bookkeeping so screens are re-checked straight away.
        ImageCache.resetPrefetchHistory()
    }
}
