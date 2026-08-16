package com.weather.metro.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationHeavyRainEvaluatorTest {
    @Test
    fun `missing data retains an active episode without notifying`() {
        val result = evaluateLocationHeavyRain(null, LocationHeavyRainLevel.HEAVY_50)

        assertEquals(LocationHeavyRainLevel.NONE, result.observedLevel)
        assertEquals(LocationHeavyRainLevel.HEAVY_50, result.nextActiveLevel)
        assertNull(result.notificationLevel)
    }

    @Test
    fun `below fifty resets the episode`() {
        val result = evaluateLocationHeavyRain(49.9, LocationHeavyRainLevel.VERY_HEAVY_70)

        assertEquals(LocationHeavyRainLevel.NONE, result.observedLevel)
        assertEquals(LocationHeavyRainLevel.NONE, result.nextActiveLevel)
        assertNull(result.notificationLevel)
    }

    @Test
    fun `first fifty crossing notifies heavy rain`() {
        val result = evaluateLocationHeavyRain(50.0, LocationHeavyRainLevel.NONE)

        assertEquals(LocationHeavyRainLevel.HEAVY_50, result.observedLevel)
        assertEquals(LocationHeavyRainLevel.HEAVY_50, result.nextActiveLevel)
        assertEquals(LocationHeavyRainLevel.HEAVY_50, result.notificationLevel)
    }

    @Test
    fun `same fifty level does not repeat`() {
        val result = evaluateLocationHeavyRain(69.9, LocationHeavyRainLevel.HEAVY_50)

        assertEquals(LocationHeavyRainLevel.HEAVY_50, result.nextActiveLevel)
        assertNull(result.notificationLevel)
    }

    @Test
    fun `fifty to seventy escalation notifies once`() {
        val result = evaluateLocationHeavyRain(70.0, LocationHeavyRainLevel.HEAVY_50)

        assertEquals(LocationHeavyRainLevel.VERY_HEAVY_70, result.nextActiveLevel)
        assertEquals(LocationHeavyRainLevel.VERY_HEAVY_70, result.notificationLevel)
    }

    @Test
    fun `seventy episode does not downgrade or repeat at sixty five`() {
        val result = evaluateLocationHeavyRain(65.0, LocationHeavyRainLevel.VERY_HEAVY_70)

        assertEquals(LocationHeavyRainLevel.VERY_HEAVY_70, result.nextActiveLevel)
        assertNull(result.notificationLevel)
    }

    @Test
    fun `new fifty crossing can notify after a completed episode`() {
        val reset = evaluateLocationHeavyRain(40.0, LocationHeavyRainLevel.VERY_HEAVY_70)
        val next = evaluateLocationHeavyRain(50.0, reset.nextActiveLevel)

        assertEquals(LocationHeavyRainLevel.NONE, reset.nextActiveLevel)
        assertEquals(LocationHeavyRainLevel.HEAVY_50, next.notificationLevel)
    }
}
