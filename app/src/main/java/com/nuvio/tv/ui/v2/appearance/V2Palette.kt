package com.nuvio.tv.ui.v2.appearance

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.ThemeColorPalette

/** Keep the existing accent (including supporter palettes), while the style owns all dark surfaces. */
fun v2Palette(accent: ThemeColorPalette, appearance: V2AppearancePreferences): ThemeColorPalette {
    if (!appearance.isV2) return accent
    val cinematic = appearance.visualStyle == VisualStyle.CINEMATIC_GLASS
    return accent.copy(
        background = if (cinematic) Color(0xFF0B1119) else Color(0xFF03080D),
        backgroundElevated = if (cinematic) Color(0xFF1B2733) else Color(0xFF0A141E),
        backgroundCard = if (cinematic) Color(0xFF22303C) else Color(0xFF0C1822),
        surface = if (cinematic) Color(0xFF1A2834) else Color(0xFF08121B),
        surfaceVariant = if (cinematic) Color(0xFF304351) else Color(0xFF142430),
        panel = if (cinematic) Color(0xFF162532) else Color(0xFF07111A),
        overlay = Color(0xE6000000),
        field = if (cinematic) Color(0xFF263643) else Color(0xFF10212D),
        menu = if (cinematic) Color(0xFF1B2A38) else Color(0xFF091520),
        modal = if (cinematic) Color(0xFF1D2C3B) else Color(0xFF0B1925),
        playerOverlay = Color(0xE6000000),
        focusBackground = accent.secondary.copy(alpha = 0.12f)
    )
}
