package com.weather.metro.ui.rain

import com.weather.metro.domain.LocationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainHostStateTest {
    @Test
    fun sameCoordinatesReuseHostLocationEvenWhenLabelChanges() {
        val first = location(22.3019, 114.1742, "香港天文台")
        val renamed = location(22.3019, 114.1742, "尖沙咀附近")

        assertTrue(sameRainLocation(first, renamed))
    }

    @Test
    fun coordinateChangeInvalidatesPointIdentity() {
        val first = location(22.3019, 114.1742, "香港天文台")
        val moved = location(22.3025, 114.1742, "附近位置")

        assertFalse(sameRainLocation(first, moved))
    }

    @Test
    fun pointRequestKeyIsScopedByCoordinatesAndRadius() {
        val host = location(22.3019, 114.1742, "香港天文台")

        val radiusTwo = RainPointRequestKey.from(host, 2)
        val radiusFive = RainPointRequestKey.from(host, 5)

        assertEquals(22.3019, radiusTwo.latitude, 0.000001)
        assertEquals(114.1742, radiusTwo.longitude, 0.000001)
        assertEquals(2, radiusTwo.radiusKm)
        assertFalse(radiusTwo == radiusFive)
    }

    private fun location(latitude: Double, longitude: Double, label: String) = LocationInfo(
        latitude = latitude,
        longitude = longitude,
        label = label,
    )
}
