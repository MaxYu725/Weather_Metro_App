package com.weather.metro.ui.rain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RainForecastPlaybackPolicyTest {
    @Test
    fun `playback advances and wraps`() {
        assertEquals(1, nextForecastPlaybackIndex(0, 4, emptySet()))
        assertEquals(0, nextForecastPlaybackIndex(3, 4, emptySet()))
    }

    @Test
    fun `playback skips frames that failed automatic loading`() {
        assertEquals(3, nextForecastPlaybackIndex(0, 5, setOf(1, 2)))
        assertEquals(0, nextForecastPlaybackIndex(4, 5, setOf(1, 2)))
    }

    @Test
    fun `playback stops rather than spinning when every other frame failed`() {
        assertNull(nextForecastPlaybackIndex(2, 4, setOf(0, 1, 3)))
        assertNull(nextForecastPlaybackIndex(0, 1, emptySet()))
    }

    @Test
    fun `rollover keeps exact forecast lead when new run arrives`() {
        assertEquals(5, alignedForecastFrameIndex(listOf(0, 6, 12, 18, 24, 30), 30))
    }

    @Test
    fun `rollover chooses nearest lead if new timeline differs`() {
        assertEquals(2, alignedForecastFrameIndex(listOf(0, 10, 20, 30), 18))
        assertEquals(0, alignedForecastFrameIndex(listOf(0, 10, 20), null))
        assertEquals(-1, alignedForecastFrameIndex(emptyList(), 30))
    }
}
