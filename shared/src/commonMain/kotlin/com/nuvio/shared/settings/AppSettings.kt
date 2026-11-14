package com.nuvio.shared.settings

import com.nuvio.shared.storage.KeyValueStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * User settings as plain read/write properties (Swift sees `var`s). Defaults follow the Android
 * PlayerSettingsDataStore / SubtitleStyleSettings where an equivalent exists.
 */
class AppSettings(private val store: KeyValueStore) {

    private val listJson = Json { ignoreUnknownKeys = true }

    private fun str(key: String, default: String) = store.getString(key) ?: default
    private fun bool(key: String, default: Boolean) = store.getString(key)?.toBooleanStrictOrNull() ?: default
    private fun int(key: String, default: Int) = store.getString(key)?.toIntOrNull() ?: default
    private fun put(key: String, value: Any?) = store.putString(key, value?.toString())
    private fun list(key: String): List<String> = store.getString(key)?.let {
        runCatching { listJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
    }.orEmpty()
    private fun putList(key: String, value: List<String>) =
        store.putString(key, listJson.encodeToString(ListSerializer(String.serializer()), value))

    // --- Player ---------------------------------------------------------------------------

    /** "mpv" (default: MKV, libass, every codec) or "avplayer" (Apple's player, HLS/MP4 only). */
    var playerEngine: String
        get() = str("player.engine", ENGINE_MPV)
        set(value) = put("player.engine", value)

    var hardwareDecoding: Boolean
        get() = bool("player.hwdec", true)
        set(value) = put("player.hwdec", value)

    /** Pass HDR/Dolby Vision to the TV (mpv target-colorspace-hint) instead of tone mapping. */
    var hdrPassthrough: Boolean
        get() = bool("player.hdrPassthrough", true)
        set(value) = put("player.hdrPassthrough", value)

    /** Forward-buffer size in seconds for network streams. */
    var cacheSeconds: Int
        get() = int("player.cacheSeconds", 120)
        set(value) = put("player.cacheSeconds", value.coerceIn(10, 600))

    var seekStepSeconds: Int
        get() = int("player.seekStep", 10)
        set(value) = put("player.seekStep", value.coerceIn(5, 60))

    /** ISO 639-1/2 code, or "" for the stream default. */
    var preferredAudioLanguage: String
        get() = str("player.audioLang", "")
        set(value) = put("player.audioLang", value)

    var secondaryAudioLanguage: String
        get() = str("player.audioLang2", "")
        set(value) = put("player.audioLang2", value)

    var audioDelayMs: Int
        get() = int("player.audioDelayMs", 0)
        set(value) = put("player.audioDelayMs", value.coerceIn(-60_000, 60_000))

    var autoPlayNextEpisode: Boolean
        get() = bool("player.autoNext", true)
        set(value) = put("player.autoNext", value)

    /** Seconds before the end at which the "Next episode" prompt appears. */
    var nextEpisodePromptSeconds: Int
        get() = int("player.nextPromptSec", 30)
        set(value) = put("player.nextPromptSec", value.coerceIn(5, 180))

    /** Auto-select the first stream whose name matches this regex (Android `streamAutoPlayRegex`). */
    var streamAutoPlayRegex: String
        get() = str("player.autoPlayRegex", "")
        set(value) = put("player.autoPlayRegex", value)

    var skipIntroEnabled: Boolean
        get() = bool("player.skipIntro", true)
        set(value) = put("player.skipIntro", value)

    // --- Subtitles ------------------------------------------------------------------------

    var subtitleLanguage: String
        get() = str("subs.lang", "en")
        set(value) = put("subs.lang", value)

    var secondarySubtitleLanguage: String
        get() = str("subs.lang2", "")
        set(value) = put("subs.lang2", value)

    var subtitlesEnabled: Boolean
        get() = bool("subs.enabled", true)
        set(value) = put("subs.enabled", value)

    var useForcedSubtitles: Boolean
        get() = bool("subs.forced", true)
        set(value) = put("subs.forced", value)

    /** Load add-on (OpenSubtitles, …) subtitles alongside embedded tracks. */
    var loadAddonSubtitles: Boolean
        get() = bool("subs.addons", true)
        set(value) = put("subs.addons", value)

    /** Percent, 50–200 (Android default 120 maps to mpv sub-scale 1.0 on a 1080p canvas). */
    var subtitleSize: Int
        get() = int("subs.size", 100)
        set(value) = put("subs.size", value.coerceIn(50, 200))

    /** Percent from the bottom, 0–50. */
    var subtitleVerticalOffset: Int
        get() = int("subs.offset", 5)
        set(value) = put("subs.offset", value.coerceIn(0, 50))

    var subtitleBold: Boolean
        get() = bool("subs.bold", false)
        set(value) = put("subs.bold", value)

    /** "#RRGGBB" or "#AARRGGBB". */
    var subtitleTextColor: String
        get() = str("subs.color", "#FFFFFF")
        set(value) = put("subs.color", value)

    var subtitleOutlineColor: String
        get() = str("subs.outlineColor", "#000000")
        set(value) = put("subs.outlineColor", value)

    var subtitleOutlineWidth: Int
        get() = int("subs.outline", 2)
        set(value) = put("subs.outline", value.coerceIn(0, 5))

    var subtitleBackgroundColor: String
        get() = str("subs.bg", "#00000000")
        set(value) = put("subs.bg", value)

    /** Apply the style above to ASS/SSA too (mpv sub-ass-override=force). Off keeps typesetting. */
    var overrideAssStyle: Boolean
        get() = bool("subs.assOverride", false)
        set(value) = put("subs.assOverride", value)

    var subtitleDelayMs: Int
        get() = int("subs.delayMs", 0)
        set(value) = put("subs.delayMs", value.coerceIn(-60_000, 60_000))

    // --- Home / content -------------------------------------------------------------------

    /** Android HomeLayout: "classic", "grid" or "modern". */
    var homeLayout: String
        get() = str("home.layout", LAYOUT_MODERN)
        set(value) = put("home.layout", value)

    var showHero: Boolean
        get() = bool("home.hero", true)
        set(value) = put("home.hero", value)

    /** CatalogSlot.key values feeding the hero; empty = every row (Android fallback). */
    var heroCatalogKeys: List<String>
        get() = list("home.heroCatalogs")
        set(value) = putList("home.heroCatalogs", value.distinct())

    fun isHeroCatalog(key: String): Boolean = key in heroCatalogKeys

    fun setHeroCatalog(key: String, enabled: Boolean) {
        heroCatalogKeys = if (enabled) heroCatalogKeys + key else heroCatalogKeys - key
    }

    var showContinueWatching: Boolean
        get() = bool("home.continueWatching", true)
        set(value) = put("home.continueWatching", value)

    /** CatalogSlot.key values the user hid from Home. */
    var hiddenCatalogKeys: List<String>
        get() = list("home.hiddenCatalogs")
        set(value) = putList("home.hiddenCatalogs", value.distinct())

    fun isCatalogHidden(key: String): Boolean = key in hiddenCatalogKeys

    fun setCatalogHidden(key: String, hidden: Boolean) {
        hiddenCatalogKeys = if (hidden) hiddenCatalogKeys + key else hiddenCatalogKeys - key
    }

    /**
     * Enrich add-on metadata with TMDB (needs a TMDB key). Off by default, like Android's
     * TmdbSettings; when off — or a group below is off — the add-on's own data is shown.
     */
    var tmdbEnrichment: Boolean
        get() = bool("tmdb.enabled", false)
        set(value) = put("tmdb.enabled", value)

    /** Logo and backdrop. */
    var tmdbUseArtwork: Boolean
        get() = bool("tmdb.artwork", true)
        set(value) = put("tmdb.artwork", value)

    /** Title, description, genres, TMDB rating. */
    var tmdbUseBasicInfo: Boolean
        get() = bool("tmdb.basic", true)
        set(value) = put("tmdb.basic", value)

    /** Runtime, status, age rating, country, language. */
    var tmdbUseDetails: Boolean
        get() = bool("tmdb.details", true)
        set(value) = put("tmdb.details", value)

    /** Cast with photos, director, writer. */
    var tmdbUseCredits: Boolean
        get() = bool("tmdb.credits", true)
        set(value) = put("tmdb.credits", value)

    var tmdbUseProductions: Boolean
        get() = bool("tmdb.productions", true)
        set(value) = put("tmdb.productions", value)

    var tmdbUseNetworks: Boolean
        get() = bool("tmdb.networks", true)
        set(value) = put("tmdb.networks", value)

    /** Episode titles, overviews, thumbnails, runtimes. */
    var tmdbUseEpisodes: Boolean
        get() = bool("tmdb.episodes", true)
        set(value) = put("tmdb.episodes", value)

    var tmdbUseTrailers: Boolean
        get() = bool("tmdb.trailers", true)
        set(value) = put("tmdb.trailers", value)

    var tmdbUseMoreLikeThis: Boolean
        get() = bool("tmdb.moreLikeThis", true)
        set(value) = put("tmdb.moreLikeThis", value)

    var tmdbUseCollections: Boolean
        get() = bool("tmdb.collections", true)
        set(value) = put("tmdb.collections", value)

    /** TMDB language, e.g. "en-US". */
    var tmdbLanguage: String
        get() = str("tmdb.language", "en-US")
        set(value) = put("tmdb.language", value)

    var showMdbListRatings: Boolean
        get() = bool("mdblist.enabled", true)
        set(value) = put("mdblist.enabled", value)

    /** Android mdbListShowOnHero: rating logos on the Home hero. */
    var showMdbListOnHero: Boolean
        get() = bool("mdblist.hero", true)
        set(value) = put("mdblist.hero", value)

    var mdbListApiKey: String
        get() = str("mdblist.apiKey", "")
        set(value) = put("mdblist.apiKey", value.trim())

    companion object {
        const val ENGINE_MPV = "mpv"
        const val ENGINE_AVPLAYER = "avplayer"
        const val LAYOUT_CLASSIC = "classic"
        const val LAYOUT_GRID = "grid"
        const val LAYOUT_MODERN = "modern"
    }
}
