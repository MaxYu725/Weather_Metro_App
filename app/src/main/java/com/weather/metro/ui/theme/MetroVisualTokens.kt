package com.weather.metro.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Device-validated Visual V2.3 Glass material values.
 *
 * Keep these values centralized so cards, future floating islands and contextual controls share one
 * visual language instead of growing independent hard-coded alpha / depth / radius values.
 */
internal object MetroGlassTokens {
    const val SectionLabelAlpha = 0.76f

    const val TileCornerRadiusDp = 18f
    const val TileRestElevationDp = 12f
    const val TilePressedElevationDp = 7f

    const val TileHighlightRestAlpha = 0.12f
    const val TileHighlightPressedAlpha = 0.155f
    const val TileCoreRestAlpha = 0.48f
    const val TileCorePressedAlpha = 0.515f
    const val TileAccentWashRestAlpha = 0.12f
    const val TileAccentWashPressedAlpha = 0.165f

    const val TileOutlineRestAlpha = 0.19f
    const val TileOutlinePressedAlpha = 0.30f
    const val TileSelectedOutlineAlpha = 0.78f
    const val TileOutlineWidthDp = 1f

    const val AccentBarRestWidthDp = 3.5f
    const val AccentBarPressedWidthDp = 6f
    const val AccentBarRestAlpha = 0.88f
    const val AccentBarPressedAlpha = 1f

    const val TopHighlightRestAlpha = 0.19f
    const val TopHighlightPressedAlpha = 0.27f
    const val TopHighlightInsetDp = 18f
    const val TopHighlightStrokeDp = 1f

    const val ExpandedDividerAlpha = 0.28f

    const val StatCornerRadiusDp = 12f
    const val StatFillAlpha = 0.075f
    const val StatOutlineAlpha = 0.11f
    const val StatOutlineWidthDp = 0.7f

    /** 44465-inspired contextual island. Kept separate from Tile values so island tuning stays local. */
    const val IslandCollapsedCornerRadiusDp = 28f
    const val IslandExpandedCornerRadiusDp = 20f
    const val IslandElevationDp = 18f
    const val IslandHighlightAlpha = 0.16f
    const val IslandCoreAlpha = 0.58f
    const val IslandAccentWashAlpha = 0.14f
    const val IslandOutlineAlpha = 0.25f
    const val IslandOutlineWidthDp = 1f
    const val IslandCollapsedHorizontalPaddingDp = 9f
    const val IslandExpandedHorizontalPaddingDp = 10f
    const val IslandVerticalPaddingDp = 8f
}

/** Background / environment palette that gives translucent Glass surfaces a readable depth reference. */
internal object MetroEnvironmentTokens {
    val BackdropBase = Color(0xFF102431)
    val Surface = Color(0xFF172D3A)
    val SurfaceVariant = Color(0xFF223B49)

    const val BackdropTopWashAlpha = 0.12f
    const val BackdropBottomWashAlpha = 0.18f
    const val AccentAmbientGlowAlpha = 0.10f
    const val VignetteAlpha = 0.08f
    const val MapAttributionAlpha = 0.54f
}

internal fun metroTokenLerp(start: Float, end: Float, progress: Float): Float {
    val fraction = progress.coerceIn(0f, 1f)
    return start + (end - start) * fraction
}
