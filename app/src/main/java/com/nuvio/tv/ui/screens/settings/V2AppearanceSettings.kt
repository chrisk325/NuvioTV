package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.V2AppearancePreferenceStore
import com.nuvio.tv.domain.model.NavigationStyle
import com.nuvio.tv.domain.model.PlayerChromeStyle
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import kotlinx.coroutines.launch

private enum class V2Picker { STYLE, NAVIGATION, PLAYER }

/** Visual style, navigation style and player controls of the Nuvio V2 look. */
@Composable
internal fun V2AppearanceSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by remember(context) { V2AppearancePreferenceStore.flow(context) }
        .collectAsState(initial = V2AppearancePreferences())
    var picker by remember { mutableStateOf<V2Picker?>(null) }
    val update: ((V2AppearancePreferences) -> V2AppearancePreferences) -> Unit = { transform ->
        scope.launch { V2AppearancePreferenceStore.update(context, transform) }
    }

    val styleNames = mapOf(
        VisualStyle.ORIGINAL to stringResource(R.string.v2_style_original),
        VisualStyle.CINEMATIC_GLASS to stringResource(R.string.v2_style_cinematic),
        VisualStyle.PURE_LIQUID_DARK to stringResource(R.string.v2_style_dark)
    )
    val navigationNames = mapOf(
        NavigationStyle.FLOATING_SIDEBAR to stringResource(R.string.v2_navigation_sidebar),
        NavigationStyle.TOP_NAVIGATION to stringResource(R.string.v2_navigation_top)
    )
    val playerNames = mapOf(
        PlayerChromeStyle.CONTROL_DECK to stringResource(R.string.v2_player_deck),
        PlayerChromeStyle.INVISIBLE to stringResource(R.string.v2_player_invisible)
    )

    SettingsGroupCard(
        modifier = modifier.fillMaxWidth(),
        title = stringResource(R.string.v2_look_title),
        subtitle = stringResource(R.string.v2_look_subtitle)
    ) {
        SettingsActionRow(
            title = stringResource(R.string.v2_visual_style_title),
            subtitle = stringResource(R.string.v2_visual_style_subtitle),
            value = styleNames.getValue(prefs.visualStyle),
            onClick = { picker = V2Picker.STYLE }
        )
        if (prefs.isV2) {
            SettingsActionRow(
                title = stringResource(R.string.v2_navigation_title),
                subtitle = stringResource(R.string.v2_navigation_subtitle),
                value = navigationNames.getValue(prefs.navigationStyle),
                onClick = { picker = V2Picker.NAVIGATION }
            )
            SettingsActionRow(
                title = stringResource(R.string.v2_player_chrome),
                subtitle = stringResource(R.string.v2_player_chrome_subtitle),
                value = playerNames.getValue(prefs.playerChromeStyle),
                onClick = { picker = V2Picker.PLAYER }
            )
        }
    }

    when (picker) {
        V2Picker.STYLE -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.v2_visual_style_title),
            options = listOf(
                SettingsPickerOption(VisualStyle.ORIGINAL, styleNames.getValue(VisualStyle.ORIGINAL),
                    stringResource(R.string.v2_visual_style_subtitle)),
                SettingsPickerOption(VisualStyle.CINEMATIC_GLASS, styleNames.getValue(VisualStyle.CINEMATIC_GLASS),
                    stringResource(R.string.v2_style_cinematic_description)),
                SettingsPickerOption(VisualStyle.PURE_LIQUID_DARK, styleNames.getValue(VisualStyle.PURE_LIQUID_DARK),
                    stringResource(R.string.v2_style_dark_description))
            ),
            selectedValue = prefs.visualStyle,
            onOptionSelected = { style ->
                update { it.copy(visualStyle = style) }
                picker = null
            },
            onDismiss = { picker = null }
        )
        V2Picker.NAVIGATION -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.v2_navigation_title),
            options = NavigationStyle.entries.map { SettingsPickerOption(it, navigationNames.getValue(it)) },
            selectedValue = prefs.navigationStyle,
            onOptionSelected = { style ->
                update { it.copy(navigationStyle = style) }
                picker = null
            },
            onDismiss = { picker = null }
        )
        V2Picker.PLAYER -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.v2_player_chrome),
            options = listOf(
                SettingsPickerOption(PlayerChromeStyle.CONTROL_DECK, playerNames.getValue(PlayerChromeStyle.CONTROL_DECK),
                    stringResource(R.string.v2_player_deck_description)),
                SettingsPickerOption(PlayerChromeStyle.INVISIBLE, playerNames.getValue(PlayerChromeStyle.INVISIBLE),
                    stringResource(R.string.v2_player_invisible_description))
            ),
            selectedValue = prefs.playerChromeStyle,
            onOptionSelected = { style ->
                update { it.copy(playerChromeStyle = style) }
                picker = null
            },
            onDismiss = { picker = null }
        )
        null -> Unit
    }
}
