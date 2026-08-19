package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainLocationTrendSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainLocationTrendPresentationTest {
    @Test
    fun partialDrySamplesDoNotOverrideFastHeadline() {
        val samples = (0..4).map { sample(it, amountMm = 0.0) }

        assertNull(locationTrendHeadline(samples))
        assertFalse(isCompleteLocationTrend(samples))
    }

    @Test
    fun completeDryTrendCanStateNoSignificantRain() {
        val samples = (0..15).map { sample(it, amountMm = 0.0) }

        assertEquals("未來 2 小時暫無明顯降雨", locationTrendHeadline(samples))
        assertTrue(isCompleteLocationTrend(samples))
    }

    @Test
    fun adjacentDryToWetSamplesNarrowPossibleOnsetToNativeCadence() {
        val samples = listOf(
            sample(0, amountMm = 0.0),
            sample(1, amountMm = 0.0),
            sample(2, amountMm = 0.4),
            sample(3, amountMm = 0.8),
        )

        assertEquals("約 42 分鐘後可能開始有雨", locationTrendHeadline(samples))
    }

    @Test
    fun increasingRollingSignalNeverClaimsSixMinuteRainfallTotals() {
        val samples = listOf(
            sample(0, amountMm = 0.2),
            sample(1, amountMm = 0.3),
            sample(2, amountMm = 0.5),
            sample(3, amountMm = 0.8),
            sample(4, amountMm = 1.1),
            sample(5, amountMm = 1.4),
            sample(6, amountMm = 1.8),
            sample(7, amountMm = 2.2),
            sample(8, amountMm = 2.7),
        )

        val headline = locationTrendHeadline(samples)

        assertEquals("未來 1 小時降雨訊號逐步增強", headline)
        assertFalse(headline.orEmpty().contains("mm"))
        assertFalse(headline.orEmpty().contains("6 分鐘雨量"))
    }

    @Test
    fun wetButFlatTrendUsesSignalLanguageInsteadOfInventedAccumulation() {
        val samples = (0..5).map { sample(it, amountMm = 0.6) }

        assertEquals("未來 2 小時有降雨訊號", locationTrendHeadline(samples))
    }

    @Test
    fun displaySamplesAreProgressiveSortedAndDeduplicated() {
        val frame2Old = sample(2, amountMm = 0.4)
        val frame0 = sample(0, amountMm = 0.1)
        val frame2Duplicate = sample(2, amountMm = 1.2)

        val display = locationTrendDisplaySamples(listOf(frame2Old, frame0, frame2Duplicate))

        assertEquals(listOf(0, 2), display.map { it.frameIndex })
        assertEquals(0.4, display.last().amountMm, 0.0001)
    }

    private fun sample(frameIndex: Int, amountMm: Double): RainLocationTrendSample =
        RainLocationTrendSample(
            frameIndex = frameIndex,
            runTime = "2026-08-19T05:00:00.000Z",
            validTime = "2026-08-19T05:${(30 + frameIndex * 6).toString().padStart(2, '0')}:00.000Z",
            leadMinutes = 30 + frameIndex * 6,
            windowStart = "2026-08-19T05:00:00.000Z",
            windowEnd = "2026-08-19T05:30:00.000Z",
            cadenceMinutes = 6,
            accumulationMinutes = 30,
            unit = "mm / 30 min",
            latitude = 22.3193,
            longitude = 114.1694,
            interpolation = "bilinear-four-grid-points",
            amountMm = amountMm,
            clampedToGridCentreBoundary = false,
        )
}
