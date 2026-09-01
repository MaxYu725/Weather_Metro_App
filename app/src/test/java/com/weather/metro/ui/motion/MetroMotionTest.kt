package com.weather.metro.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroMotionTest {
    @Test
    fun restProgressKeepsIdentityScale() {
        val scale = metroPressScale(MetroPressPreset.CompactControl, 0f)
        assertEquals(1f, scale.x, 0.0001f)
        assertEquals(1f, scale.y, 0.0001f)
    }

    @Test
    fun fullProgressUsesPresetCompression() {
        val scale = metroPressScale(MetroPressPreset.CompactControl, 1f)
        assertEquals(0.965f, scale.x, 0.0001f)
        assertEquals(0.900f, scale.y, 0.0001f)
        assertTrue(scale.y < scale.x)
    }

    @Test
    fun releaseOvershootIsSmallAndBounded() {
        val overshoot = metroPressScale(MetroPressPreset.Tile, -1f)
        val fullyPressed = metroPressScale(MetroPressPreset.Tile, 2f)

        assertEquals(1.003f, overshoot.x, 0.0001f)
        assertEquals(1.014f, overshoot.y, 0.0001f)
        assertTrue(overshoot.x > 1f)
        assertTrue(overshoot.y > 1f)
        assertEquals(0.985f, fullyPressed.x, 0.0001f)
        assertEquals(0.930f, fullyPressed.y, 0.0001f)
    }

    @Test
    fun presetsKeepLargeTilesQuieterThanCompactControls() {
        val compact = metroPressScale(MetroPressPreset.CompactControl, 1f)
        val tile = metroPressScale(MetroPressPreset.Tile, 1f)

        assertTrue(tile.x > compact.x)
        assertTrue(tile.y > compact.y)
    }
}
