package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainLocationTrendSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainLocationTrendViewModelTest {
    @Test
    fun publishesEachSuccessfulFrameWithoutWaitingForAllSixteen() = runBlocking {
        val requested = mutableListOf<Int>()
        val published = mutableListOf<Int>()
        val source = RainLocationTrendSampleSource { frameIndex, latitude, longitude ->
            requested += frameIndex
            sample(frameIndex, latitude, longitude, runTime = "run-a")
        }

        val warning = loadLocationTrendProgressively(
            source = source,
            latitude = LAT,
            longitude = LON,
            onReset = { error("unexpected reset") },
            onSample = { value ->
                published += value.frameIndex
                assertEquals(requested.size, published.size)
            },
        )

        assertEquals((0..15).toList(), requested)
        assertEquals((0..15).toList(), published)
        assertEquals(null, warning)
    }

    @Test
    fun isolatedFrameFailureDoesNotBlockLaterSamples() = runBlocking {
        val requested = mutableListOf<Int>()
        val published = mutableListOf<Int>()
        val source = RainLocationTrendSampleSource { frameIndex, latitude, longitude ->
            requested += frameIndex
            if (frameIndex == 3) error("temporary frame failure")
            sample(frameIndex, latitude, longitude, runTime = "run-a")
        }

        val warning = loadLocationTrendProgressively(
            source = source,
            latitude = LAT,
            longitude = LON,
            onReset = {},
            onSample = { published += it.frameIndex },
        )

        assertEquals((0..15).toList(), requested)
        assertTrue(3 !in published)
        assertTrue(4 in published)
        assertNotNull(warning)
    }

    @Test
    fun stopsAfterTwoConsecutiveFailuresInsteadOfHammeringUpstream() = runBlocking {
        val requested = mutableListOf<Int>()
        val published = mutableListOf<Int>()
        val source = RainLocationTrendSampleSource { frameIndex, latitude, longitude ->
            requested += frameIndex
            if (frameIndex in 1..2) error("upstream unavailable")
            sample(frameIndex, latitude, longitude, runTime = "run-a")
        }

        val warning = loadLocationTrendProgressively(
            source = source,
            latitude = LAT,
            longitude = LON,
            onReset = {},
            onSample = { published += it.frameIndex },
        )

        assertEquals(listOf(0, 1, 2), requested)
        assertEquals(listOf(0), published)
        assertNotNull(warning)
    }

    @Test
    fun runChangeClearsOldSamplesAndRestartsOnceFromFrameZero() = runBlocking {
        var switched = false
        var resetCount = 0
        val requested = mutableListOf<Int>()
        val published = mutableListOf<RainLocationTrendSample>()
        val source = RainLocationTrendSampleSource { frameIndex, latitude, longitude ->
            requested += frameIndex
            if (!switched && frameIndex == 1) {
                switched = true
                sample(frameIndex, latitude, longitude, runTime = "run-b")
            } else {
                sample(frameIndex, latitude, longitude, runTime = if (switched) "run-b" else "run-a")
            }
        }

        val warning = loadLocationTrendProgressively(
            source = source,
            latitude = LAT,
            longitude = LON,
            onReset = {
                resetCount += 1
                published.clear()
            },
            onSample = { published += it },
        )

        assertEquals(listOf(0, 1, 0), requested.take(3))
        assertEquals(1, resetCount)
        assertEquals((0..15).toList(), published.map { it.frameIndex })
        assertTrue(published.all { it.runTime == "run-b" })
        assertEquals(null, warning)
    }

    @Test
    fun cancellationPropagatesImmediately() {
        val result = runCatching {
            runBlocking {
                loadLocationTrendProgressively(
                    source = RainLocationTrendSampleSource { _, _, _ -> throw CancellationException("cancel") },
                    latitude = LAT,
                    longitude = LON,
                    onReset = {},
                    onSample = {},
                )
            }
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun mergeReplacesDuplicateFrameAndKeepsFrameOrder() {
        val frame2 = sample(2, LAT, LON, runTime = "run-a", amountMm = 1.0)
        val frame0 = sample(0, LAT, LON, runTime = "run-a", amountMm = 0.2)
        val replacement2 = sample(2, LAT, LON, runTime = "run-a", amountMm = 2.5)

        val merged = mergeTrendSample(
            mergeTrendSample(listOf(frame2), frame0),
            replacement2,
        )

        assertEquals(listOf(0, 2), merged.map { it.frameIndex })
        assertEquals(2.5, merged.last().amountMm, 0.0001)
    }

    private fun sample(
        frameIndex: Int,
        latitude: Double,
        longitude: Double,
        runTime: String?,
        amountMm: Double = frameIndex / 10.0,
    ): RainLocationTrendSample = RainLocationTrendSample(
        frameIndex = frameIndex,
        runTime = runTime,
        validTime = "2026-08-19T05:${frameIndex.toString().padStart(2, '0')}:00.000Z",
        leadMinutes = 30 + frameIndex * 6,
        windowStart = "2026-08-19T04:30:00.000Z",
        windowEnd = "2026-08-19T05:00:00.000Z",
        cadenceMinutes = 6,
        accumulationMinutes = 30,
        unit = "mm / 30 min",
        latitude = latitude,
        longitude = longitude,
        interpolation = "bilinear-four-grid-points",
        amountMm = amountMm,
        clampedToGridCentreBoundary = false,
    )

    companion object {
        private const val LAT = 22.3193
        private const val LON = 114.1694
    }
}
