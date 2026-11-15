package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.v2.appearance.GlassQualityTokens
import com.nuvio.tv.ui.v2.appearance.LocalGlassTokens
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.v2Palette
import com.nuvio.tv.ui.v2.components.LocalGlassBackdrop

data class NuvioExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalNuvioColors = staticCompositionLocalOf {
    NuvioColorScheme(ThemeColors.Ocean)
}

val LocalNuvioExtendedColors = staticCompositionLocalOf {
    NuvioExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF808080),
        focusRing = ThemeColors.Ocean.focusRing,
        focusBackground = ThemeColors.Ocean.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalNuvioTextStyles = staticCompositionLocalOf { NuvioTextStyles }

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }

val LocalThemePalette = staticCompositionLocalOf { ThemeColors.White }

val LocalSettingsUiStyle = staticCompositionLocalOf { SettingsUiStyle.CLASSIC }

val LocalNuvioFocusRingStyle = staticCompositionLocalOf {
    createFocusRingStyle(ThemeColors.Ocean)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    customThemeColors: CustomThemeColors = CustomThemeColors.Default,
    v2Appearance: V2AppearancePreferences? = null,
    content: @Composable () -> Unit
) {
    // Original look: v2 is null and everything below resolves exactly as before.
    val v2 = v2Appearance?.takeIf { it.isV2 }
    val originalPalette = androidx.compose.runtime.remember(appTheme, customThemeColors) {
        ThemeColors.getColorPalette(appTheme, customThemeColors)
    }
    val palette = androidx.compose.runtime.remember(originalPalette, v2) {
        if (v2 == null) originalPalette else v2Palette(originalPalette, v2)
    }
    val focusRingStyle = createFocusRingStyle(palette)
    val colorScheme = NuvioColorScheme(
        palette = palette,
        amoledMode = v2 == null && amoledMode,
        amoledSurfacesMode = v2 == null && amoledSurfacesMode,
        glassPresentation = v2 != null
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceGlass = androidx.compose.runtime.remember(context) { GlassQualityTokens.forDevice(context) }
    val glassTokens = androidx.compose.runtime.remember(deviceGlass, v2?.visualStyle, v2?.glassBlurStrengthPercent) {
        when (v2?.visualStyle) {
            VisualStyle.CINEMATIC_GLASS -> deviceGlass.withBlurStrength(v2?.glassBlurStrengthPercent ?: 65)
            null -> GlassQualityTokens.Performance
            else -> deviceGlass.copy(liveBlur = false, blurRadiusDp = 0f, inputScale = 0f, noise = 0f)
        }
    }
    val glassBackdrop = androidx.compose.runtime.remember { dev.chrisbanes.haze.HazeState() }
    val typography = buildNuvioTypography(getFontFamily(appFont))
    val textStyles = buildNuvioTextStyles(typography)

    val materialColorScheme = darkColorScheme(
        primary = colorScheme.Primary,
        onPrimary = colorScheme.OnPrimary,
        secondary = colorScheme.Secondary,
        onSecondary = colorScheme.OnSecondary,
        background = colorScheme.Background,
        surface = colorScheme.Surface,
        surfaceVariant = colorScheme.SurfaceVariant,
        onBackground = colorScheme.TextPrimary,
        onSurface = colorScheme.TextPrimary,
        onSurfaceVariant = colorScheme.TextSecondary,
        error = colorScheme.Error
    )

    val extendedColors = NuvioExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalV2Appearance provides v2,
        LocalGlassTokens provides glassTokens,
        LocalGlassBackdrop provides glassBackdrop.takeIf { v2?.visualStyle == VisualStyle.CINEMATIC_GLASS },
        LocalNuvioColors provides colorScheme,
        LocalNuvioExtendedColors provides extendedColors,
        LocalNuvioTextStyles provides textStyles,
        LocalAppTheme provides appTheme,
        LocalThemePalette provides palette,
        // V2 renders settings flat on glass, as the Nuvio V2 interface does.
        LocalSettingsUiStyle provides if (v2 != null) SettingsUiStyle.ZEN else settingsUiStyle,
        LocalNuvioFocusRingStyle provides focusRingStyle
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = typography,
            content = content
        )
    }
}

object NuvioTheme {
    val palette: ThemeColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalThemePalette.current

    val colors: NuvioColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioColors.current

    val extendedColors: NuvioExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioExtendedColors.current

    val textStyles: NuvioTextStyleTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioTextStyles.current

    val spacing: NuvioSpacingTokens
        get() = NuvioSpacing.tokens

    val radii: NuvioRadiusTokens
        get() = NuvioRadii.tokens

    val shapes: NuvioShapeTokens
        get() = NuvioShapes.tokens

    val sizes: NuvioSizeTokens
        get() = NuvioSizes.tokens

    val strokes: NuvioStrokeTokens
        get() = NuvioStrokes.tokens

    val elevations: NuvioElevationTokens
        get() = NuvioElevations.tokens

    val effects: NuvioEffectTokens
        get() = NuvioEffects.tokens

    val motion: NuvioMotionTokens
        get() = NuvioMotion.tokens

    val focus: NuvioFocusTokens
        get() = NuvioFocus.tokens

    val focusRing: NuvioFocusRingStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioFocusRingStyle.current

    val layout: NuvioLayoutTokens
        get() = NuvioLayout.tokens

    val media: NuvioMediaTokens
        get() = NuvioMedia.tokens

    val components: NuvioComponentTokens
        get() = NuvioComponents.tokens

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current

    val settingsUiStyle: SettingsUiStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalSettingsUiStyle.current
}
