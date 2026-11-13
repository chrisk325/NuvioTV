package com.nuvio.shared

/**
 * Build-time keys (Android: local.properties → BuildConfig; tvOS: Secrets.xcconfig → Info.plist).
 * Blank values disable the matching integration.
 */
data class NuvioConfig(
    val tmdbApiKey: String,
    val traktClientId: String,
    val traktClientSecret: String,
    val simklClientId: String,
    val simklAppName: String,
    val mdbListClientId: String,
    val aniListClientId: String,
    val premiumizeClientId: String,
    val introDbApiUrl: String,
    val trailerApiUrl: String,
    val supabaseUrl: String,
    val supabaseAnonKey: String
) {
    val hasTmdb: Boolean get() = tmdbApiKey.isNotBlank()
    val hasTrakt: Boolean get() = traktClientId.isNotBlank() && traktClientSecret.isNotBlank()
    val hasSimkl: Boolean get() = simklClientId.isNotBlank()
    val hasAniList: Boolean get() = aniListClientId.isNotBlank()

    companion object {
        val EMPTY = NuvioConfig("", "", "", "", "nuvio", "", "", "", "", "", "", "")
    }
}
