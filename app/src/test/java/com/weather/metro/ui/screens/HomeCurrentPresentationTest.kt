package com.weather.metro.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCurrentPresentationTest {
    @Test
    fun rainBarBucketsRemainMonotonic() {
        assertEquals(2, homeRainBarHeightValue(0.0))
        assertEquals(8, homeRainBarHeightValue(0.1))
        assertEquals(14, homeRainBarHeightValue(0.5))
        assertEquals(22, homeRainBarHeightValue(2.0))
        assertEquals(30, homeRainBarHeightValue(5.0))
    }
}
