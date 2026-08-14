package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainPeriod
import com.weather.metro.domain.rain.RainPointForecast
import com.weather.metro.domain.rain.RainPointSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class RainPointUiModelTest {
    @Test
    fun pointPresentationKeepsThirtyMinuteRainfallSemantics() {
        val forecast = RainPointForecast(
            workerVersion = "2.5.0",
            unit = "mm / 30 min",
            sourceUpdatedAt = null,
            issueTime = "2026-08-14T04:00:00Z",
            generatedAt = null,
            location = null,
            nearbyRadiusKm = 2.0,
            interpolation = null,
            grid = null,
            summary = RainPointSummary(
                text = "短暫有雨",
                totalMm = 3.25,
                peakMm = 1.5,
                peakTime = null,
                peakWindowStart = null,
                peakWindowEnd = null,
                rainStartTime = "2026-08-14T04:30:00Z",
                rainStartWindowStart = null,
                rainStartWindowEnd = null,
                rainStartLeadMinutes = 30,
                rainEndTime = null,
                rainEndWindowStart = null,
                rainEndWindowEnd = null,
                wetPeriodCount = 2,
            ),
            periods = listOf(
                RainPeriod(
                    time = "2026-08-14T04:30:00Z",
                    leadMinutes = 30,
                    amountMm = 1.5,
                    nearbyMaxMm = 2.25,
                    nearbyMeanMm = 1.2,
                    nearestGridKm = 0.4,
                    spatialSpreadMm = 0.3,
                    level = "rain",
                ),
            ),
            quality = null,
        )

        val model = buildRainPointUiModel(forecast)

        assertEquals("短暫有雨", model.headline)
        assertEquals("3.25 mm", model.total)
        assertEquals("1.5 mm", model.peak)
        assertEquals("12:30", model.rainStart)
        assertEquals("12:30", model.periods.single().time)
        assertEquals("1.5 mm", model.periods.single().amount)
        assertEquals("附近最高 2.25 mm", model.periods.single().nearby)
    }

    @Test
    fun zeroAndMissingAmountsStayExplicit() {
        assertEquals("0 mm", formatMm(0.0))
        assertEquals("-- mm", formatMm(null))
    }
}
