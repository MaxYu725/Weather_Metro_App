package com.weather.metro.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class MetroVisualTokensTest {
    @Test
    fun deviceApprovedTileDepthAndRadiusStayStable() {
        assertEquals(18f, MetroGlassTokens.TileCornerRadiusDp, 0.0001f)
        assertEquals(12f, MetroGlassTokens.TileRestElevationDp, 0.0001f)
        assertEquals(7f, MetroGlassTokens.TilePressedElevationDp, 0.0001f)
    }

    @Test
    fun deviceApprovedGlassOpacityRangeStaysStable() {
        assertEquals(0.12f, MetroGlassTokens.TileHighlightRestAlpha, 0.0001f)
        assertEquals(0.155f, MetroGlassTokens.TileHighlightPressedAlpha, 0.0001f)
        assertEquals(0.48f, MetroGlassTokens.TileCoreRestAlpha, 0.0001f)
        assertEquals(0.515f, MetroGlassTokens.TileCorePressedAlpha, 0.0001f)
        assertEquals(0.19f, MetroGlassTokens.TileOutlineRestAlpha, 0.0001f)
        assertEquals(0.30f, MetroGlassTokens.TileOutlinePressedAlpha, 0.0001f)
    }

    @Test
    fun tokenInterpolationIsBoundedAndMatchesPressEndpoints() {
        assertEquals(
            MetroGlassTokens.TileRestElevationDp,
            metroTokenLerp(
                MetroGlassTokens.TileRestElevationDp,
                MetroGlassTokens.TilePressedElevationDp,
                -2f,
            ),
            0.0001f,
        )
        assertEquals(
            9.5f,
            metroTokenLerp(
                MetroGlassTokens.TileRestElevationDp,
                MetroGlassTokens.TilePressedElevationDp,
                0.5f,
            ),
            0.0001f,
        )
        assertEquals(
            MetroGlassTokens.TilePressedElevationDp,
            metroTokenLerp(
                MetroGlassTokens.TileRestElevationDp,
                MetroGlassTokens.TilePressedElevationDp,
                4f,
            ),
            0.0001f,
        )
    }

    @Test
    fun environmentPaletteMatchesValidatedGlassBaseline() {
        assertEquals(Color(0xFF102431), MetroEnvironmentTokens.BackdropBase)
        assertEquals(Color(0xFF172D3A), MetroEnvironmentTokens.Surface)
        assertEquals(Color(0xFF223B49), MetroEnvironmentTokens.SurfaceVariant)
        assertEquals(0.10f, MetroEnvironmentTokens.AccentAmbientGlowAlpha, 0.0001f)
        assertEquals(0.08f, MetroEnvironmentTokens.VignetteAlpha, 0.0001f)
    }
}
