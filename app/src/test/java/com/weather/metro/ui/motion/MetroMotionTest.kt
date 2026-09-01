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
        assertEquals(0.920f, scale.y, 0.0001f)
        assertTrue(scale.y < scale.x)
    }

    @Test
    fun progressIsClampedToSafeRange() {
        val below = metroPressScale(MetroPressPreset.Tile, -1f)
        val above = metroPressScale(MetroPressPreset.Tile, 2f)

        assertEquals(1f, below.x, 0.0001f)
        assertEquals(1f, below.y, 0.0001f)
        assertEquals(0.985f, above.x, 0.0001f)
        assertEquals(0.965f, above.y, 0.0001f)
    }

    @Test
    fun presetsKeepLargeTilesQuieterThanCompactControls() {
        val compact = metroPressScale(MetroPressPreset.CompactControl, 1f)
        val tile = metroPressScale(MetroPressPreset.Tile, 1f)

        assertTrue(tile.x > compact.x)
        assertTrue(tile.y > compact.y)
    }
}
