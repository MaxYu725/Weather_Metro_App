package com.weather.metro.ui.rain

import com.weather.metro.domain.LocationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainSwirlsPointSeriesPolicyTest {
    @Test
    fun normalGpsJitterKeepsTheSameCompactSeriesIdentity() {
        val first = LocationInfo(latitude = 22.30230, longitude = 114.17460, label = "A")
        val jittered = LocationInfo(latitude = 22.30255, longitude = 114.17435, label = "B")

        assertTrue(samePointSeriesLocation(first, jittered))
    }

    @Test
    fun meaningfulMovementInvalidatesCompactSeriesIdentity() {
        val first = LocationInfo(latitude = 22.30230, longitude = 114.17460, label = "A")
        val moved = LocationInfo(latitude = 22.30310, longitude = 114.17460, label = "B")

        assertFalse(samePointSeriesLocation(first, moved))
    }

    @Test
    fun snapshotMissRetriesQuietlyAtWorkerGuidanceCadence() {
        assertEquals(60_000L, pointSeriesRetryDelayMs(1))
        assertEquals(60_000L, pointSeriesRetryDelayMs(2))
        assertEquals(60_000L, pointSeriesRetryDelayMs(8))
    }
}
