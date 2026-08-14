package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainDataQuality
import com.weather.metro.domain.rain.RainForecastGrid
import com.weather.metro.domain.rain.RainForecastSource
import com.weather.metro.domain.rain.RainForecastTimeline
import com.weather.metro.domain.rain.RainFreshness
import com.weather.metro.domain.rain.RainGridBounds
import com.weather.metro.domain.rain.RainPointForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RainToolRefreshPolicyTest {
    private val now = Instant.parse("2026-08-14T12:00:00Z").toEpochMilli()

    @Test
    fun pointDataAtFifteenMinutesIsStillFresh() {
        assertFalse(
            shouldRefreshRainPoint(
                status = RainResourceStatus.READY,
                isStale = false,
                forecast = pointForecast("2026-08-14T11:45:00Z"),
                acceptedAtEpochMs = now - 60_000L,
                lastAttemptEpochMs = null,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun pointDataOlderThanFifteenMinutesRefreshes() {
        assertTrue(
            shouldRefreshRainPoint(
                status = RainResourceStatus.READY,
                isStale = false,
                forecast = pointForecast("2026-08-14T11:44:00Z"),
                acceptedAtEpochMs = now - 60_000L,
                lastAttemptEpochMs = null,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun stalePointRespectsFiveMinuteRetryBackoff() {
        val forecast = pointForecast("2026-08-14T11:30:00Z")
        assertFalse(
            shouldRefreshRainPoint(
                status = RainResourceStatus.READY,
                isStale = true,
                forecast = forecast,
                acceptedAtEpochMs = now - 10 * 60_000L,
                lastAttemptEpochMs = now - 2 * 60_000L,
                nowEpochMs = now,
            ),
        )
        assertTrue(
            shouldRefreshRainPoint(
                status = RainResourceStatus.READY,
                isStale = true,
                forecast = forecast,
                acceptedAtEpochMs = now - 10 * 60_000L,
                lastAttemptEpochMs = now - 5 * 60_000L,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun forecastUsesIssueTimeForStaleness() {
        assertFalse(
            shouldRefreshRainForecast(
                status = RainResourceStatus.READY,
                isStale = false,
                timeline = timeline("2026-08-14T11:45:00Z"),
                acceptedAtEpochMs = now - 60_000L,
                lastAttemptEpochMs = null,
                nowEpochMs = now,
            ),
        )
        assertTrue(
            shouldRefreshRainForecast(
                status = RainResourceStatus.READY,
                isStale = false,
                timeline = timeline("2026-08-14T11:44:00Z"),
                acceptedAtEpochMs = now - 60_000L,
                lastAttemptEpochMs = null,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun cancelledIdleResourceCanReloadImmediately() {
        assertTrue(
            shouldRefreshRainForecast(
                status = RainResourceStatus.IDLE,
                isStale = false,
                timeline = null,
                acceptedAtEpochMs = null,
                lastAttemptEpochMs = now - 30_000L,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun boundedPrefetchFavoursForwardFramesAroundSelection() {
        assertEquals(listOf(1, 2, 3), forecastPrefetchIndexes(frameCount = 16, selectedIndex = 0))
        assertEquals(listOf(6, 4, 7), forecastPrefetchIndexes(frameCount = 16, selectedIndex = 5))
        assertEquals(listOf(14, 13, 12), forecastPrefetchIndexes(frameCount = 16, selectedIndex = 15))
        assertTrue(forecastPrefetchIndexes(frameCount = 1, selectedIndex = 0).isEmpty())
    }

    private fun pointForecast(sourceUpdatedAt: String) = RainPointForecast(
        workerVersion = "test",
        unit = "mm / 30 min",
        sourceUpdatedAt = sourceUpdatedAt,
        issueTime = null,
        generatedAt = null,
        location = null,
        nearbyRadiusKm = 2.0,
        interpolation = null,
        grid = null,
        summary = null,
        periods = emptyList(),
        quality = RainDataQuality(
            freshness = RainFreshness(
                status = "fresh",
                label = null,
                note = null,
                sourceAgeMinutes = 0.0,
            ),
            spatial = null,
        ),
    )

    private fun timeline(issueTime: String) = RainForecastTimeline(
        source = RainForecastSource.SWIRLS,
        issueTime = issueTime,
        unit = "mm / 30 min",
        cadenceMinutes = 6,
        accumulationMinutes = 30,
        horizonMinutes = 120,
        grid = RainForecastGrid(
            rows = 1,
            cols = 1,
            cellCount = 1,
            orientation = "test",
            latitudes = doubleArrayOf(22.3),
            longitudes = doubleArrayOf(114.2),
            stepLat = null,
            stepLon = null,
            bounds = RainGridBounds(22.4, 22.2, 114.3, 114.1),
        ),
        frames = emptyList(),
    )
}
