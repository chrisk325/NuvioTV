package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.domain.model.NavigationStyle
import com.nuvio.tv.domain.model.PlayerChromeStyle
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class V2AppearancePreferenceStoreTest {
    @Test fun `unset installs keep the Original look`() {
        val decoded = V2AppearancePreferenceStore.decode(emptyPreferences())
        assertEquals(V2AppearancePreferences(), decoded)
        assertEquals(VisualStyle.ORIGINAL, decoded.visualStyle)
        assertFalse(decoded.isV2)
    }

    @Test fun `unknown stored values fall back to defaults`() {
        val decoded = V2AppearancePreferenceStore.decode(
            mutablePreferencesOf(
                stringPreferencesKey("visual_style") to "SOMETHING_ELSE",
                stringPreferencesKey("navigation_style") to "MINIMAL",
                stringPreferencesKey("player_chrome_style") to ""
            )
        )
        assertEquals(V2AppearancePreferences(), decoded)
    }

    @Test fun `every combination survives a storage roundtrip`() {
        VisualStyle.entries.forEach { style ->
            NavigationStyle.entries.forEach { navigation ->
                PlayerChromeStyle.entries.forEach { player ->
                    val expected = V2AppearancePreferences(
                        visualStyle = style,
                        navigationStyle = navigation,
                        playerChromeStyle = player
                    )
                    val stored = mutablePreferencesOf()
                    V2AppearancePreferenceStore.encode(stored, expected)
                    assertEquals(expected, V2AppearancePreferenceStore.decode(stored))
                }
            }
        }
    }
}
