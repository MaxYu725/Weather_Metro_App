package com.weather.metro.domain.rain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RainFinePointSeriesTest {
    @Test
    fun keepsSixMinuteCadenceWithoutRelabellingRollingAccumulation() {
        val rolling = List(16) { 1.25 + it * 0.1 }
        val series = swirlsTimeline(rolling).buildFinePointSeries(
            latitude = 22.5,
            longitude = 114.5,
        )

        assertNotNull(series)
        requireNotNull(series)
        assertEquals(16, series.rollingSamples.size)
        assertEquals((30..120 step 6).toList(), series.rollingSamples.map { it.leadMinutes })
        assertEquals(30, series.accumulationMinutes)
        assertEquals(6, series.cadenceMinutes)
        assertEquals(1.25, series.rollingSamples.first().accumulationMm, 0.000001)
        assertFalse(series.hasReconstructedSixMinuteBuckets)
        assertNull(series.reconstructedSixMinuteBuckets)
    }

    @Test
    fun reconstructsSixMinuteBucketsWhenFirstThirtyMinutesAreDry() {
        val buckets = listOf(
            0.0, 0.0, 0.0, 0.0, 0.0,
            0.20, 0.45, 0.80, 1.20, 0.75,
            0.40, 0.15, 0.05, 0.0, 0.0,
            0.0, 0.10, 0.30, 0.20, 0.0,
        )
        val series = swirlsTimeline(rollingFromBuckets(buckets)).buildFinePointSeries(
            latitude = 22.5,
            longitude = 114.5,
        )

        requireNotNull(series)
        val reconstructed = requireNotNull(series.reconstructedSixMinuteBuckets)
        assertTrue(series.hasReconstructedSixMinuteBuckets)
        assertEquals(30, series.reconstructionAnchorLeadMinutes)
        assertEquals(20, reconstructed.size)
        assertEquals((0 until 120 step 6).toList(), reconstructed.map { it.startLeadMinutes })
        buckets.forEachIndexed { index, expected ->
            assertEquals(expected, reconstructed[index].amountMm, 0.000001)
        }
    }

    @Test
    fun dryWindowInMiddleAnchorsBothBackwardAndForwardReconstruction() {
        val buckets = listOf(
            0.90, 0.60, 0.30, 0.15, 0.05,
            0.0, 0.0, 0.0, 0.0, 0.0,
            0.10, 0.25, 0.55, 0.80, 0.40,
            0.20, 0.05, 0.0, 0.0, 0.0,
        )
        val series = swirlsTimeline(rollingFromBuckets(buckets)).buildFinePointSeries(
            latitude = 22.5,
            longitude = 114.5,
        )

        requireNotNull(series)
        val reconstructed = requireNotNull(series.reconstructedSixMinuteBuckets)
        assertEquals(60, series.reconstructionAnchorLeadMinutes)
        buckets.forEachIndexed { index, expected ->
            assertEquals(expected, reconstructed[index].amountMm, 0.000001)
        }
    }

    @Test
    fun doesNotInventSixMinuteTotalsWhenEveryRollingWindowIsWet() {
        val buckets = List(20) { index -> 0.20 + (index % 4) * 0.05 }
        val rolling = rollingFromBuckets(buckets)
        assertTrue(rolling.all { it > 0.0 })

        val series = swirlsTimeline(rolling).buildFinePointSeries(
            latitude = 22.5,
            longitude = 114.5,
        )

        requireNotNull(series)
        assertNull(series.reconstructedSixMinuteBuckets)
        assertNull(series.reconstructionAnchorLeadMinutes)
    }

    @Test
    fun bilinearInterpolationUsesFourNeighbouringGridCentres() {
        val timeline = swirlsTimeline(
            rolling = List(16) { 1.0 },
            firstFrameValues = doubleArrayOf(0.0, 10.0, 20.0, 30.0),
        )
        val series = timeline.buildFinePointSeries(
            latitude = 22.5,
            longitude = 114.5,
        )

        requireNotNull(series)
        assertEquals(15.0, series.rollingSamples.first().accumulationMm, 0.000001)
    }

    @Test
    fun waitsForCompleteSwirlsTimelineBeforeBuildingFineSeries() {
        val complete = swirlsTimeline(List(16) { 0.0 })
        val incomplete = complete.copy(
            frames = complete.frames.mapIndexed { index, slot ->
                if (index == 7) slot.copy(frame = null) else slot
            },
        )

        assertNull(incomplete.buildFinePointSeries(22.5, 114.5))
    }

    private fun rollingFromBuckets(buckets: List<Double>): List<Double> {
        require(buckets.size == 20)
        return List(16) { windowIndex ->
            buckets.subList(windowIndex, windowIndex + 5).sum()
        }
    }

    private fun swirlsTimeline(
        rolling: List<Double>,
        firstFrameValues: DoubleArray? = null,
    ): RainForecastTimeline {
        require(rolling.size == 16)
        val issue = Instant.parse("2026-08-18T12:00:00Z")
        val grid = RainForecastGrid(
            rows = 2,
            cols = 2,
            cellCount = 4,
            orientation = "row-major-north-to-south-west-to-east",
            latitudes = doubleArrayOf(23.0, 22.0),
            longitudes = doubleArrayOf(114.0, 115.0),
            stepLat = 1.0,
            stepLon = 1.0,
            bounds = RainGridBounds(
                north = 23.5,
                south = 21.5,
                east = 115.5,
                west = 113.5,
            ),
        )
        val slots = rolling.mapIndexed { index, amount ->
            val lead = 30 + index * 6
            val valid = issue.plusSeconds(lead * 60L).toString()
            val values = if (index == 0 && firstFrameValues != null) {
                firstFrameValues
            } else {
                DoubleArray(4) { amount }
            }
            val frame = RainForecastFrame(
                frameIndex = index,
                runTime = issue.toString(),
                validTime = valid,
                leadMinutes = lead,
                windowStart = issue.plusSeconds((lead - 30) * 60L).toString(),
                windowEnd = valid,
                unit = "mm / 30 min",
                grid = grid,
                values = values,
            )
            RainForecastSlot(
                frameIndex = index,
                validTime = valid,
                leadMinutes = lead,
                windowStart = frame.windowStart,
                windowEnd = frame.windowEnd,
                frame = frame,
            )
        }
        return RainForecastTimeline(
            source = RainForecastSource.SWIRLS,
            issueTime = issue.toString(),
            unit = "mm / 30 min",
            cadenceMinutes = 6,
            accumulationMinutes = 30,
            horizonMinutes = 120,
            grid = grid,
            frames = slots,
        )
    }
}
