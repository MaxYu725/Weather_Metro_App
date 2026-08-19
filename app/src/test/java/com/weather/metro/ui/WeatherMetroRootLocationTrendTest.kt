package com.weather.metro.ui

import com.weather.metro.data.settings.PageColourSlot
import com.weather.metro.ui.rain.RainResourceStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherMetroRootLocationTrendTest {
    @Test
    fun trendWaitsUntilFastPointPathHasSettled() {
        assertFalse(
            locationTrendMayRun(
                page = PageColourSlot.CURRENT,
                hasActiveTool = false,
                hasLocation = true,
                pointStatus = RainResourceStatus.IDLE,
            ),
        )
        assertFalse(
            locationTrendMayRun(
                page = PageColourSlot.CURRENT,
                hasActiveTool = false,
                hasLocation = true,
                pointStatus = RainResourceStatus.LOADING,
            ),
        )
        assertTrue(
            locationTrendMayRun(
                page = PageColourSlot.CURRENT,
                hasActiveTool = false,
                hasLocation = true,
                pointStatus = RainResourceStatus.READY,
            ),
        )
        assertTrue(
            locationTrendMayRun(
                page = PageColourSlot.CURRENT,
                hasActiveTool = false,
                hasLocation = true,
                pointStatus = RainResourceStatus.ERROR,
            ),
        )
    }

    @Test
    fun trendNeverRunsOutsideUnobstructedCurrentPage() {
        assertFalse(
            locationTrendMayRun(
                page = PageColourSlot.FORECAST,
                hasActiveTool = false,
                hasLocation = true,
                pointStatus = RainResourceStatus.READY,
            ),
        )
        assertFalse(
            locationTrendMayRun(
                page = PageColourSlot.CURRENT,
                hasActiveTool = true,
                hasLocation = true,
                pointStatus = RainResourceStatus.READY,
            ),
        )
        assertFalse(
            locationTrendMayRun(
                page = PageColourSlot.CURRENT,
                hasActiveTool = false,
                hasLocation = false,
                pointStatus = RainResourceStatus.READY,
            ),
        )
    }
}
