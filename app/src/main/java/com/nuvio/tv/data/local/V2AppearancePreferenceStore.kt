package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.domain.model.NavigationStyle
import com.nuvio.tv.domain.model.PlayerChromeStyle
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Device-level, look-only preferences. Kept out of profile sync and every other store.
private val Context.v2AppearanceDataStore by preferencesDataStore(
    name = "appearance_v2",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() }
)

object V2AppearancePreferenceStore {
    private val style = stringPreferencesKey("visual_style")
    private val navigation = stringPreferencesKey("navigation_style")
    private val player = stringPreferencesKey("player_chrome_style")

    internal fun decode(p: Preferences) = V2AppearancePreferences(
        visualStyle = VisualStyle.entries.firstOrNull { it.name == p[style] } ?: VisualStyle.ORIGINAL,
        navigationStyle = NavigationStyle.entries.firstOrNull { it.name == p[navigation] }
            ?: NavigationStyle.FLOATING_SIDEBAR,
        playerChromeStyle = PlayerChromeStyle.entries.firstOrNull { it.name == p[player] }
            ?: PlayerChromeStyle.INVISIBLE
    )

    internal fun encode(p: MutablePreferences, next: V2AppearancePreferences) {
        p[style] = next.visualStyle.name
        p[navigation] = next.navigationStyle.name
        p[player] = next.playerChromeStyle.name
    }

    fun flow(context: Context): Flow<V2AppearancePreferences> = context.applicationContext.v2AppearanceDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::decode)
        .distinctUntilChanged()

    suspend fun update(context: Context, transform: (V2AppearancePreferences) -> V2AppearancePreferences) {
        context.applicationContext.v2AppearanceDataStore.edit { p -> encode(p, transform(decode(p))) }
    }
}
