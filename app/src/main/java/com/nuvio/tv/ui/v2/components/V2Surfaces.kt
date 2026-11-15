package com.nuvio.tv.ui.v2.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance

/**
 * Glass body for an existing surface on the V2 looks; the exact original fill otherwise.
 * Only the paint changes: size, padding, focus and keys stay with the caller.
 */
@Composable
fun Modifier.v2Surface(
    shape: Shape,
    original: Color,
    role: GlassRole = GlassRole.PANEL,
    focused: Boolean = false
): Modifier = if (LocalV2Appearance.current != null) {
    nuvioGlass(role, focused, shape)
} else {
    clip(shape).background(original)
}

/** Same as [v2Surface] for callers that paint their background without clipping. */
@Composable
fun Modifier.v2Background(
    shape: Shape,
    original: Color,
    role: GlassRole = GlassRole.PANEL
): Modifier = if (LocalV2Appearance.current != null) {
    nuvioGlass(role, shape = shape)
} else {
    background(original, shape)
}
