package com.weather.metro.ui.rain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainForecastRenderMemoryPolicyTest {
    @Test
    fun `current SWIRLS 121 by 121 grid can cache all 16 rendered frames under budget`() {
        val capacity = forecastBitmapCacheCapacity(
            rows = 121,
            cols = 121,
            frameCount = 16,
        )
        val bytes = forecastBitmapCacheEstimatedBytes(
            rows = 121,
            cols = 121,
            cachedFrameCount = capacity,
        )

        assertEquals(16, capacity)
        assertTrue(bytes < 1_000_000L)
        assertTrue(bytes <= FORECAST_BITMAP_CACHE_BUDGET_BYTES)
    }

    @Test
    fun `larger raster is bounded by memory budget instead of frame count`() {
        val capacity = forecastBitmapCacheCapacity(
            rows = 500,
            cols = 500,
            frameCount = 16,
        )

        assertEquals(2, capacity)
        assertTrue(
            forecastBitmapCacheEstimatedBytes(500, 500, capacity) <=
                FORECAST_BITMAP_CACHE_BUDGET_BYTES,
        )
    }

    @Test
    fun `cache always keeps at least current frame even when one frame exceeds budget`() {
        assertEquals(
            1,
            forecastBitmapCacheCapacity(
                rows = 1000,
                cols = 1000,
                frameCount = 16,
                budgetBytes = 512L * 1024L,
            ),
        )
    }
}
