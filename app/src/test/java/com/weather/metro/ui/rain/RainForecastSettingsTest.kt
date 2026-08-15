package com.weather.metro.ui.rain

import org.junit.Assert.assertEquals
import org.junit.Test

class RainForecastSettingsTest {
    @Test
    fun `opacity is clamped to valid raster range`() {
        assertEquals(0f, normalizedForecastOpacity(-0.4f), 0.0001f)
        assertEquals(0.42f, normalizedForecastOpacity(0.42f), 0.0001f)
        assertEquals(1f, normalizedForecastOpacity(1.4f), 0.0001f)
    }

    @Test
    fun `playback delay restores known values and defaults safely`() {
        assertEquals(RainForecastPlaybackSpeed.SLOW, RainForecastPlaybackSpeed.fromDelay(1_100L))
        assertEquals(RainForecastPlaybackSpeed.NORMAL, RainForecastPlaybackSpeed.fromDelay(750L))
        assertEquals(RainForecastPlaybackSpeed.FAST, RainForecastPlaybackSpeed.fromDelay(500L))
        assertEquals(RainForecastPlaybackSpeed.NORMAL, RainForecastPlaybackSpeed.fromDelay(123L))
    }

    @Test
    fun `playback speed cycles slow normal fast`() {
        assertEquals(
            RainForecastPlaybackSpeed.NORMAL,
            nextForecastPlaybackSpeed(RainForecastPlaybackSpeed.SLOW),
        )
        assertEquals(
            RainForecastPlaybackSpeed.FAST,
            nextForecastPlaybackSpeed(RainForecastPlaybackSpeed.NORMAL),
        )
        assertEquals(
            RainForecastPlaybackSpeed.SLOW,
            nextForecastPlaybackSpeed(RainForecastPlaybackSpeed.FAST),
        )
    }
}
