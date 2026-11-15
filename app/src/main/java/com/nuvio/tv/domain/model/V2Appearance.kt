package com.nuvio.tv.domain.model

/** Look ported from the Nuvio V2 interface. [ORIGINAL] keeps the existing Nuvio look untouched. */
enum class VisualStyle { ORIGINAL, CINEMATIC_GLASS, PURE_LIQUID_DARK }
enum class NavigationStyle { FLOATING_SIDEBAR, TOP_NAVIGATION }
enum class PlayerChromeStyle { CONTROL_DECK, INVISIBLE }

/** Purely visual choices; nothing here changes behaviour or data. */
data class V2AppearancePreferences(
    val visualStyle: VisualStyle = VisualStyle.ORIGINAL,
    val navigationStyle: NavigationStyle = NavigationStyle.FLOATING_SIDEBAR,
    val playerChromeStyle: PlayerChromeStyle = PlayerChromeStyle.INVISIBLE,
    val glassTransparencyPercent: Int = 60,
    val glassBlurStrengthPercent: Int = 65
) {
    val isV2: Boolean get() = visualStyle != VisualStyle.ORIGINAL
}
